package dev.che1s.craftenginerecipe.util;

import org.yaml.snakeyaml.LoaderOptions;
import org.yaml.snakeyaml.Yaml;
import org.yaml.snakeyaml.constructor.SafeConstructor;

import java.io.IOException;
import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Reads CraftEngine pack translations stored in
 * {@code resources/<namespace>/configuration/lang/<locale>.yml}.
 *
 * <p>CraftEngine exposes these keys through its locale-aware manager, but does not
 * copy them into the generated resource-pack ZIP. CER therefore loads the player
 * locale files directly so item display names do not fall back to the server locale.</p>
 */
public final class CraftEngineConfigTranslations {
    private static final long MAX_FILE_BYTES = 16L * 1024L * 1024L;
    private static final int MAX_DEPTH = 64;
    private static final int MAX_KEYS = 200_000;

    private CraftEngineConfigTranslations() {
    }

    public static Map<String, String> read(Path resourcesDirectory, String locale) throws IOException {
        if (resourcesDirectory == null || !Files.isDirectory(resourcesDirectory, LinkOption.NOFOLLOW_LINKS)) {
            return Map.of();
        }
        String normalized = normalize(locale);
        if (normalized.isBlank()) return Map.of();
        List<Path> files;
        try (var paths = Files.walk(resourcesDirectory, 4)) {
            files = paths.filter(path -> Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS))
                    .filter(path -> path.getParent() != null && path.getParent().getFileName() != null
                            && path.getParent().getFileName().toString().equals("lang"))
                    .filter(path -> path.getFileName().toString().equals(normalized + ".yml"))
                    .sorted()
                    .toList();
        }
        if (files.isEmpty()) return Map.of();

        Map<String, String> translations = new LinkedHashMap<>();
        for (Path file : files) {
            if (Files.size(file) > MAX_FILE_BYTES) continue;
            try (Reader reader = Files.newBufferedReader(file, StandardCharsets.UTF_8)) {
                LoaderOptions options = new LoaderOptions();
                options.setAllowDuplicateKeys(false);
                Object root = new Yaml(new SafeConstructor(options)).load(reader);
                readLocaleBranches(root, normalized, translations, 0);
            } catch (RuntimeException error) {
                CompatibilityWarnings.warn("CraftEngineConfigTranslations.read", error);
            }
        }
        return Collections.unmodifiableMap(translations);
    }

    private static void readLocaleBranches(Object value, String locale, Map<String, String> output, int depth) {
        if (depth > MAX_DEPTH || !(value instanceof Map<?, ?> map)) return;
        Object direct = localeValue(map, locale);
        if (direct != null) {
            flatten(direct, "", output, depth + 1);
            return;
        }
        for (Object child : map.values()) {
            readLocaleBranches(child, locale, output, depth + 1);
        }
    }

    private static Object localeValue(Map<?, ?> map, String locale) {
        for (Map.Entry<?, ?> entry : map.entrySet()) {
            if (entry.getKey() != null && normalize(String.valueOf(entry.getKey())).equals(locale)) {
                return entry.getValue();
            }
        }
        return null;
    }

    private static void flatten(Object value, String prefix, Map<String, String> output, int depth) {
        if (depth > MAX_DEPTH || output.size() >= MAX_KEYS) return;
        if (value instanceof String text) {
            if (!prefix.isEmpty()) output.putIfAbsent(prefix, text);
            return;
        }
        if (!(value instanceof Map<?, ?> map)) return;
        for (Map.Entry<?, ?> entry : map.entrySet()) {
            if (entry.getKey() == null) continue;
            String key = String.valueOf(entry.getKey());
            flatten(entry.getValue(), prefix.isEmpty() ? key : prefix + "." + key, output, depth + 1);
        }
    }

    private static String normalize(String locale) {
        return locale == null ? "" : locale.trim().replace('-', '_').toLowerCase(Locale.ROOT);
    }
}
