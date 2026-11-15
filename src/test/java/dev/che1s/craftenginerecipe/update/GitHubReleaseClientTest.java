package dev.che1s.craftenginerecipe.update;

import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class GitHubReleaseClientTest {
    private static final String VERSION = "v1.0.17";
    private static final String TAG_PAGE = "https://github.com/che11111/CraftEngineRecipe/releases/tag/" + VERSION;

    @Test
    void fetchesOfficialReleaseWithoutAuthenticationAndClosesConnection() throws Exception {
        FakeConnection connection = new FakeConnection(200, release(VERSION, TAG_PAGE, false, false));
        GitHubReleaseClient client = new GitHubReleaseClient(endpoint -> {
            assertEquals(URI.create("https://api.github.com/repos/che11111/CraftEngineRecipe/releases/latest"), endpoint);
            return connection;
        });

        assertEquals(Optional.of(new GitHubReleaseClient.Release(VERSION, TAG_PAGE)), client.fetchLatest());
        assertTrue(connection.getConnectTimeout() > 0 && connection.getConnectTimeout() <= 10_000);
        assertTrue(connection.getReadTimeout() > 0 && connection.getReadTimeout() <= 10_000);
        assertFalse(connection.getInstanceFollowRedirects());
        assertEquals("application/vnd.github+json", connection.getRequestProperty("Accept"));
        assertEquals("2022-11-28", connection.getRequestProperty("X-GitHub-Api-Version"));
        assertTrue(connection.getRequestProperty("User-Agent").contains("CraftEngineRecipe"));
        assertNull(connection.getRequestProperty("Authorization"));
        assertTrue(connection.streamClosed);
        assertTrue(connection.disconnected);
    }

    @Test
    void missingLatestReleaseDoesNotFailOrReadAnErrorBody() throws Exception {
        FakeConnection connection = new FakeConnection(404, "not JSON");
        assertTrue(new GitHubReleaseClient(endpoint -> connection).fetchLatest().isEmpty());
        assertFalse(connection.streamOpened);
        assertTrue(connection.disconnected);
    }

    @Test
    void rejectsRedirectRateLimitAndServerFailureWithoutFollowingOtherUrls() throws Exception {
        for (int status : new int[]{301, 302, 307, 308, 403, 429, 500}) {
            FakeConnection connection = new FakeConnection(status, "untrusted error body");
            AtomicInteger opens = new AtomicInteger();
            GitHubReleaseClient client = new GitHubReleaseClient(endpoint -> {
                opens.incrementAndGet();
                return connection;
            });
            assertThrows(IOException.class, client::fetchLatest);
            assertEquals(1, opens.get());
            assertFalse(connection.streamOpened);
            assertTrue(connection.disconnected);
        }
    }

    @Test
    void networkFailureStillDisconnects() throws Exception {
        FakeConnection connection = new FakeConnection(200, "");
        connection.failure = new IOException("connection timed out");
        assertThrows(IOException.class, new GitHubReleaseClient(endpoint -> connection)::fetchLatest);
        assertTrue(connection.disconnected);
    }

    @Test
    void rejectsOversizedResponsesWithAndWithoutContentLength() throws Exception {
        FakeConnection declared = new FakeConnection(200, "");
        declared.contentLength = GitHubReleaseClient.MAX_RESPONSE_BYTES + 1L;
        assertThrows(IOException.class, new GitHubReleaseClient(endpoint -> declared)::fetchLatest);
        assertFalse(declared.streamOpened);
        assertTrue(declared.disconnected);

        FakeConnection streamed = new FakeConnection(200, " ".repeat(GitHubReleaseClient.MAX_RESPONSE_BYTES + 1));
        assertThrows(IOException.class, new GitHubReleaseClient(endpoint -> streamed)::fetchLatest);
        assertTrue(streamed.streamClosed);
        assertTrue(streamed.disconnected);
    }

    @Test
    void ignoresDraftsAndPrereleasesEvenIfEndpointReturnsThem() throws Exception {
        assertTrue(parse(release("1.0.17", TAG_PAGE, true, false)).isEmpty());
        assertTrue(parse(release("1.0.17-beta.1", TAG_PAGE, false, true)).isEmpty());
    }

    @Test
    void rejectsInvalidVersionsAndPrereleaseTagsMarkedAsStable() {
        for (String tag : new String[]{"latest", "1.0.17-rc.1", "1.0.17/other", "1..17", ""}) {
            assertThrows(IOException.class, () -> parse(release(tag, TAG_PAGE, false, false)));
        }
    }

    @Test
    void onlyUsesCorrespondingTagPageOnTrustedRepository() throws Exception {
        for (String page : new String[]{
                "https://evil.example/download.jar",
                "https://github.com.evil.example/che11111/CraftEngineRecipe/releases/tag/" + VERSION,
                "https://github.com@evil.example/che11111/CraftEngineRecipe/releases/tag/" + VERSION,
                "http://github.com/che11111/CraftEngineRecipe/releases/tag/" + VERSION,
                "https://github.com/other/CraftEngineRecipe/releases/tag/" + VERSION,
                "https://github.com/che11111/CraftEngineRecipe/releases/tag/v9.9.9",
                TAG_PAGE + "?redirect=https://evil.example",
                TAG_PAGE + "#fragment",
                "invalid URL"}) {
            assertEquals(GitHubReleaseClient.RELEASE_PAGE, parse(release(VERSION, page, false, false)).orElseThrow().url());
        }
        assertEquals(TAG_PAGE, parse(release(VERSION, TAG_PAGE, false, false)).orElseThrow().url());
        assertEquals(GitHubReleaseClient.RELEASE_PAGE,
                parse("{\"tag_name\":\"1.0.17\",\"draft\":false,\"prerelease\":false}").orElseThrow().url());
    }

    @Test
    void toleratesOtherGithubMetadataButRejectsExcessiveNesting() throws Exception {
        String extra = ",\"assets\":[{\"name\":\"plugin.jar\",\"size\":123}],\"author\":{\"login\":\"che11111\"}";
        String basic = release(VERSION, TAG_PAGE, false, false);
        assertEquals(VERSION, parse(basic.substring(0, basic.length() - 1) + extra + "}").orElseThrow().version());
        String nested = ",\"unknown\":" + "[".repeat(40) + "0" + "]".repeat(40);
        assertThrows(IOException.class, () -> parse(basic.substring(0, basic.length() - 1) + nested + "}"));
    }

    @Test
    void rejectsMalformedJsonAmbiguousTypesAndDuplicateFields() {
        for (String json : new String[]{
                "", "[]", "null", "{}", "{unquoted:true}",
                "{\"tag_name\":\"1.0.17\",\"draft\":false}",
                "{\"tag_name\":17,\"draft\":false,\"prerelease\":false}",
                "{\"tag_name\":\"1.0.17\",\"draft\":\"false\",\"prerelease\":false}",
                "{\"tag_name\":\"1.0.17\",\"draft\":false,\"prerelease\":false,\"draft\":true}",
                release(VERSION, TAG_PAGE, false, false) + " {}"}) {
            assertThrows(IOException.class, () -> parse(json));
        }
    }

    @Test
    void rejectsMalformedUtf8AndClosesFailedResponseStream() throws Exception {
        assertThrows(IOException.class, () -> GitHubReleaseClient.parseRelease(new byte[]{'{', (byte) 0xff, '}'}));
        FakeConnection connection = new FakeConnection(200, "not JSON");
        assertThrows(IOException.class, new GitHubReleaseClient(endpoint -> connection)::fetchLatest);
        assertTrue(connection.streamClosed);
        assertTrue(connection.disconnected);
    }

    private static Optional<GitHubReleaseClient.Release> parse(String json) throws IOException {
        return GitHubReleaseClient.parseRelease(json.getBytes(StandardCharsets.UTF_8));
    }

    private static String release(String version, String page, boolean draft, boolean prerelease) {
        return "{\"tag_name\":\"" + version + "\",\"html_url\":\"" + page
                + "\",\"draft\":" + draft + ",\"prerelease\":" + prerelease + "}";
    }

    private static final class FakeConnection extends HttpURLConnection {
        private final int status;
        private final byte[] bytes;
        private long contentLength = -1;
        private boolean streamOpened;
        private boolean streamClosed;
        private boolean disconnected;
        private IOException failure;

        FakeConnection(int status, String response) throws IOException {
            super(GitHubReleaseClient.ENDPOINT.toURL());
            this.status = status;
            bytes = response.getBytes(StandardCharsets.UTF_8);
        }

        @Override
        public int getResponseCode() throws IOException {
            if (failure != null) throw failure;
            return status;
        }

        @Override
        public long getContentLengthLong() {
            return contentLength;
        }

        @Override
        public InputStream getInputStream() {
            streamOpened = true;
            return new ByteArrayInputStream(bytes) {
                @Override
                public void close() throws IOException {
                    streamClosed = true;
                    super.close();
                }
            };
        }

        @Override
        public void disconnect() {
            disconnected = true;
        }

        @Override
        public boolean usingProxy() {
            return false;
        }

        @Override
        public void connect() {
        }
    }
}
