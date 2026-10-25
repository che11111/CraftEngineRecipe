package dev.che1s.craftenginerecipe.update;

import com.google.gson.stream.JsonReader;
import com.google.gson.stream.JsonToken;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.URI;
import java.net.URISyntaxException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.util.HashSet;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;

/** 有大小限制的同步客户端，应在后台线程调用。 */
public final class GitHubReleaseClient {
    static final URI ENDPOINT = URI.create("https://api.github.com/repos/che11111/CraftEngineRecipe/releases/latest");
    static final String RELEASE_PAGE = "https://github.com/che11111/CraftEngineRecipe/releases/latest";
    static final int MAX_RESPONSE_BYTES = 512 * 1024;
    private static final int TIMEOUT_MILLIS = 8_000;
    private static final int MAX_JSON_DEPTH = 32;
    private final ConnectionFactory connections;

    public GitHubReleaseClient() {
        this(uri -> (HttpURLConnection) uri.toURL().openConnection());
    }

    GitHubReleaseClient(ConnectionFactory connections) {
        this.connections = Objects.requireNonNull(connections);
    }

    public record Release(String version, String url) {
    }

    @FunctionalInterface
    interface ConnectionFactory {
        HttpURLConnection open(URI uri) throws IOException;
    }

    /** 没有最新发行版属于正常情况；其他 HTTP、网络或格式错误会报告给调用方。 */
    public Optional<Release> fetchLatest() throws IOException {
        HttpURLConnection connection = connections.open(ENDPOINT);
        try {
            connection.setConnectTimeout(TIMEOUT_MILLIS);
            connection.setReadTimeout(TIMEOUT_MILLIS);
            connection.setInstanceFollowRedirects(false);
            connection.setRequestProperty("Accept", "application/vnd.github+json");
            connection.setRequestProperty("X-GitHub-Api-Version", "2022-11-28");
            connection.setRequestProperty("User-Agent", "CraftEngineRecipe-update-checker");
            int status = connection.getResponseCode();
            if (status == HttpURLConnection.HTTP_NOT_FOUND) return Optional.empty();
            if (status != HttpURLConnection.HTTP_OK) throw new IOException("GitHub update check returned HTTP " + status);
            if (connection.getContentLengthLong() > MAX_RESPONSE_BYTES) {
                throw new IOException("GitHub release response exceeds size limit");
            }
            byte[] response;
            try (var input = connection.getInputStream()) {
                response = input.readNBytes(MAX_RESPONSE_BYTES + 1);
            }
            if (response.length > MAX_RESPONSE_BYTES) throw new IOException("GitHub release response exceeds size limit");
            return parseRelease(response);
        } finally {
            connection.disconnect();
        }
    }

    static Optional<Release> parseRelease(byte[] response) throws IOException {
        if (response.length > MAX_RESPONSE_BYTES) throw new IOException("GitHub release response exceeds size limit");
        var decoder = StandardCharsets.UTF_8.newDecoder()
                .onMalformedInput(CodingErrorAction.REPORT).onUnmappableCharacter(CodingErrorAction.REPORT);
        try (var reader = new JsonReader(new InputStreamReader(new ByteArrayInputStream(response), decoder))) {
            reader.setLenient(false);
            if (reader.peek() != JsonToken.BEGIN_OBJECT) throw new IOException("GitHub release JSON must be an object");
            String version = null;
            String url = null;
            Boolean draft = null;
            Boolean prerelease = null;
            Set<String> knownFields = new HashSet<>();
            reader.beginObject();
            while (reader.hasNext()) {
                String name = reader.nextName();
                switch (name) {
                    case "tag_name", "html_url", "draft", "prerelease" -> {
                        if (!knownFields.add(name)) throw new IOException("Duplicate GitHub release field: " + name);
                        switch (name) {
                            case "tag_name" -> version = readString(reader, name);
                            case "html_url" -> url = readString(reader, name);
                            case "draft" -> draft = readBoolean(reader, name);
                            case "prerelease" -> prerelease = readBoolean(reader, name);
                        }
                    }
                    default -> skipValue(reader, 1);
                }
            }
            reader.endObject();
            if (reader.peek() != JsonToken.END_DOCUMENT) throw new IOException("Unexpected trailing GitHub release JSON");
            if (version == null || draft == null || prerelease == null) {
                throw new IOException("Missing required GitHub release fields");
            }
            if (draft || prerelease) return Optional.empty();
            if (!ReleaseVersion.isStable(version)) throw new IOException("Unsupported GitHub release version");
            return Optional.of(new Release(version, safeReleasePage(url, version)));
        } catch (IllegalStateException error) {
            throw new IOException("Malformed GitHub release JSON", error);
        }
    }

    private static String readString(JsonReader reader, String name) throws IOException {
        if (reader.peek() != JsonToken.STRING) throw new IOException("GitHub release field must be a string: " + name);
        return reader.nextString();
    }

    private static boolean readBoolean(JsonReader reader, String name) throws IOException {
        if (reader.peek() != JsonToken.BOOLEAN) throw new IOException("GitHub release field must be a boolean: " + name);
        return reader.nextBoolean();
    }

    private static String safeReleasePage(String text, String version) {
        if (text == null) return RELEASE_PAGE;
        try {
            URI uri = new URI(text);
            if ("https".equals(uri.getScheme()) && "github.com".equals(uri.getHost())
                    && uri.getUserInfo() == null && uri.getPort() == -1
                    && uri.getQuery() == null && uri.getFragment() == null
                    && ("/che11111/CraftEngineRecipe/releases/tag/" + version).equals(uri.getPath())) {
                return uri.toASCIIString();
            }
        } catch (URISyntaxException ignored) {
            // 即使 GitHub 修改或省略 html_url，固定仓库地址仍可使用。
        }
        return RELEASE_PAGE;
    }

    private static void skipValue(JsonReader reader, int depth) throws IOException {
        if (depth > MAX_JSON_DEPTH) throw new IOException("GitHub release JSON exceeds nesting limit");
        switch (reader.peek()) {
            case BEGIN_ARRAY -> {
                reader.beginArray();
                while (reader.hasNext()) skipValue(reader, depth + 1);
                reader.endArray();
            }
            case BEGIN_OBJECT -> {
                reader.beginObject();
                while (reader.hasNext()) {
                    reader.nextName();
                    skipValue(reader, depth + 1);
                }
                reader.endObject();
            }
            default -> reader.skipValue();
        }
    }
}
