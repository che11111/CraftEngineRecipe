package dev.leon.cer.util;

import com.google.gson.stream.JsonReader;
import com.google.gson.stream.JsonToken;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.Enumeration;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.PriorityQueue;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

/** Reads the generated resource pack without accessing server or player APIs. */
public final class ResourcePackTranslations {
    private static final List<String> DEFAULT_LANGUAGES = List.of("zh_cn", "en_us");
    private static final Pattern LANGUAGE = Pattern.compile("[a-z0-9]+(?:_[a-z0-9]+)*");
    private static final Pattern LANGUAGE_PATH = Pattern.compile(
            "assets/([a-z0-9_.-]+)/lang/([a-zA-Z0-9_-]+)\\.json");
    private static final int MAX_FILE_BYTES = 8 * 1024 * 1024;
    private static final int MAX_TOTAL_BYTES = 64 * 1024 * 1024;
    private static final int MAX_FILES = 1024;
    private static final int MAX_TRANSLATIONS = 200_000;
    private static final int MAX_DEPTH = 64;
    private static final int MAX_KEY_LENGTH = 4096;
    private static final int MAX_VALUE_LENGTH = 65_536;
    private static final Comparator<LanguageFile> FILE_ORDER = Comparator.comparingInt(LanguageFile::preference)
            .thenComparingInt(file -> file.namespace().equals("minecraft") ? 0 : 1)
            .thenComparing(LanguageFile::namespace)
            .thenComparing(file -> file.entry().getName());

    private ResourcePackTranslations() {
    }

    public record ReadResult(Map<String, String> translations, int files, List<String> problems) {
        public ReadResult {
            translations = Collections.unmodifiableMap(new LinkedHashMap<>(translations));
            problems = List.copyOf(problems);
        }
    }

    /** Invalid or empty preferences use the default Chinese/English fallback order. */
    public static List<String> normalizeLanguages(List<String> languages) {
        LinkedHashSet<String> normalized = new LinkedHashSet<>();
        if (languages != null) {
            for (String language : languages) {
                if (language == null) continue;
                String locale = language.trim().toLowerCase(Locale.ROOT).replace('-', '_');
                if (locale.length() <= 64 && LANGUAGE.matcher(locale).matches()) normalized.add(locale);
            }
        }
        return normalized.isEmpty() ? DEFAULT_LANGUAGES : List.copyOf(normalized);
    }

    /**
     * Reads preferred locales in order. Within a locale, minecraft takes precedence,
     * followed by other namespaces in lexical order. Failed files never contribute partial entries.
     * {@code files} counts successfully parsed language files, including empty files.
     */
    public static ReadResult read(Path zip, List<String> languages) throws IOException {
        Objects.requireNonNull(zip, "zip");
        List<String> locales = normalizeLanguages(languages);
        Map<String, String> translations = new LinkedHashMap<>();
        List<String> problems = new ArrayList<>();
        int files = 0;
        int consumedBytes = 0;

        try (ZipFile archive = new ZipFile(zip.toFile(), StandardCharsets.UTF_8)) {
            // Keep the best candidates even if a ZIP lists a preferred locale/namespace last.
            PriorityQueue<LanguageFile> candidates = new PriorityQueue<>(MAX_FILES, FILE_ORDER.reversed());
            boolean tooManyFiles = false;
            Enumeration<? extends ZipEntry> entries = archive.entries();
            while (entries.hasMoreElements()) {
                ZipEntry entry = entries.nextElement();
                if (entry.isDirectory()) continue;
                Matcher match = LANGUAGE_PATH.matcher(entry.getName());
                if (!match.matches()) continue;
                String locale = match.group(2).toLowerCase(Locale.ROOT).replace('-', '_');
                int preference = locales.indexOf(locale);
                if (preference < 0) continue;
                LanguageFile file = new LanguageFile(entry, match.group(1), preference);
                if (candidates.size() == MAX_FILES) {
                    tooManyFiles = true;
                    if (FILE_ORDER.compare(file, candidates.peek()) >= 0) continue;
                    candidates.remove();
                }
                candidates.add(file);
            }
            if (tooManyFiles) problems.add("Language file limit exceeded (" + MAX_FILES + ")");
            List<LanguageFile> selected = new ArrayList<>(candidates);
            selected.sort(FILE_ORDER);

            for (LanguageFile file : selected) {
                ZipEntry entry = file.entry();
                if (entry.getSize() > MAX_FILE_BYTES) {
                    problems.add(entry.getName() + ": uncompressed file exceeds " + MAX_FILE_BYTES + " bytes");
                    continue;
                }
                int remaining = MAX_TOTAL_BYTES - consumedBytes;
                if (remaining <= 0) {
                    problems.add("Total language input limit exceeded (" + MAX_TOTAL_BYTES + " bytes)");
                    break;
                }
                try {
                    byte[] contents;
                    try (var input = archive.getInputStream(entry)) {
                        contents = input.readNBytes(Math.min(MAX_FILE_BYTES, remaining) + 1);
                    }
                    consumedBytes += contents.length;
                    if (contents.length > MAX_FILE_BYTES) throw new IOException("uncompressed file limit exceeded");
                    if (contents.length > remaining) {
                        problems.add("Total language input limit exceeded (" + MAX_TOTAL_BYTES + " bytes)");
                        break;
                    }
                    Map<String, String> parsed = parse(contents);
                    // Limit the merged map before adding any part of this file.
                    long added = parsed.keySet().stream().filter(key -> !translations.containsKey(key)).count();
                    if (translations.size() + added > MAX_TRANSLATIONS) {
                        throw new IOException("merged translation limit exceeded (" + MAX_TRANSLATIONS + ")");
                    }
                    parsed.forEach(translations::putIfAbsent);
                    files++;
                } catch (IOException | IllegalStateException error) {
                    problems.add(entry.getName() + ": " + problemMessage(error));
                }
            }
        }
        return new ReadResult(translations, files, problems);
    }

    private static Map<String, String> parse(byte[] contents) throws IOException {
        var decoder = StandardCharsets.UTF_8.newDecoder()
                .onMalformedInput(CodingErrorAction.REPORT).onUnmappableCharacter(CodingErrorAction.REPORT);
        Map<String, String> result = new LinkedHashMap<>();
        try (var reader = new JsonReader(new InputStreamReader(new ByteArrayInputStream(contents), decoder))) {
            reader.setLenient(false);
            if (reader.peek() != JsonToken.BEGIN_OBJECT) throw new IOException("language JSON must be an object");
            readObject(reader, "", 1, result);
            if (reader.peek() != JsonToken.END_DOCUMENT) throw new IOException("unexpected trailing JSON content");
        }
        return result;
    }

    private static void readObject(JsonReader reader, String prefix, int depth,
                                   Map<String, String> result) throws IOException {
        checkDepth(depth);
        reader.beginObject();
        while (reader.hasNext()) {
            String name = reader.nextName();
            if (name.length() + prefix.length() > MAX_KEY_LENGTH) throw new IOException("translation key is too long");
            String key = prefix.isEmpty() ? name : prefix + "." + name;
            if (key.length() > MAX_KEY_LENGTH) throw new IOException("translation key is too long");
            JsonToken token = reader.peek();
            if (token == JsonToken.STRING) {
                String value = reader.nextString();
                if (value.length() > MAX_VALUE_LENGTH) throw new IOException("translation value is too long");
                if (!result.containsKey(key) && result.size() == MAX_TRANSLATIONS) {
                    throw new IOException("translation limit exceeded (" + MAX_TRANSLATIONS + ")");
                }
                result.put(key, value);
            } else if (token == JsonToken.BEGIN_OBJECT) {
                readObject(reader, key, depth + 1, result);
            } else {
                skipValue(reader, depth + 1);
            }
        }
        reader.endObject();
    }

    /** Skip non-string values while retaining a bound on deeply nested arrays/objects. */
    private static void skipValue(JsonReader reader, int depth) throws IOException {
        checkDepth(depth);
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

    private static void checkDepth(int depth) throws IOException {
        if (depth > MAX_DEPTH) throw new IOException("JSON nesting limit exceeded (" + MAX_DEPTH + ")");
    }

    private static String problemMessage(Exception error) {
        String message = error.getMessage();
        if (message == null || message.isBlank()) return error.getClass().getSimpleName();
        message = message.replace('\n', ' ').replace('\r', ' ');
        return message.length() > 300 ? message.substring(0, 300) + "..." : message;
    }

    private record LanguageFile(ZipEntry entry, String namespace, int preference) {
    }
}
