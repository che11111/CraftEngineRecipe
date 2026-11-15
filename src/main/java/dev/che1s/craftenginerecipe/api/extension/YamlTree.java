package dev.che1s.craftenginerecipe.api.extension;

import org.yaml.snakeyaml.LoaderOptions;
import org.yaml.snakeyaml.Yaml;
import org.yaml.snakeyaml.constructor.SafeConstructor;

import java.io.IOException;
import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Read-only helpers for plugin YAML trees loaded without Bukkit objects. */
public final class YamlTree {
    private YamlTree() {
    }

    public static Map<String, Object> read(Path file, String owner) {
        return read(file, owner, false);
    }

    public static Map<String, Object> read(Path file, String owner, boolean allowDuplicateKeys) {
        LoaderOptions options = new LoaderOptions();
        options.setAllowDuplicateKeys(allowDuplicateKeys);
        try (Reader reader = Files.newBufferedReader(file, StandardCharsets.UTF_8)) {
            Object loaded = new Yaml(new SafeConstructor(options)).load(reader);
            return loaded == null ? Map.of() : map(loaded);
        } catch (IOException | RuntimeException error) {
            throw new IllegalStateException("Failed to read " + owner + " file " + file
                    + ": " + error.getMessage(), error);
        }
    }

    public static Map<String, Object> map(Object raw) {
        if (!(raw instanceof Map<?, ?> values)) return Map.of();
        Map<String, Object> result = new LinkedHashMap<>();
        values.forEach((key, value) -> result.put(String.valueOf(key), value));
        return result;
    }

    public static Object path(Object root, String... keys) {
        Object current = root;
        for (String key : keys) {
            current = map(current).get(key);
            if (current == null) return Map.of();
        }
        return current;
    }

    public static String string(Object value) {
        return value == null ? "" : String.valueOf(value);
    }

    public static int integer(Object value, int fallback) {
        if (value instanceof Number number) return number.intValue();
        try {
            return value == null ? fallback : Integer.parseInt(String.valueOf(value).trim());
        } catch (NumberFormatException ignored) {
            return fallback;
        }
    }

    public static boolean bool(Object value, boolean fallback) {
        if (value instanceof Boolean bool) return bool;
        if (value == null) return fallback;
        String text = String.valueOf(value).trim();
        return text.equalsIgnoreCase("true") ? true
                : text.equalsIgnoreCase("false") ? false : fallback;
    }

    public static List<String> strings(Object raw) {
        if (raw == null) return List.of();
        if (raw instanceof Collection<?> collection) {
            List<String> values = new ArrayList<>();
            for (Object value : collection) values.addAll(strings(value));
            return List.copyOf(values);
        }
        if (raw instanceof Map<?, ?> map) {
            List<String> values = new ArrayList<>();
            for (Object value : map.values()) values.addAll(strings(value));
            return List.copyOf(values);
        }
        String value = String.valueOf(raw).trim();
        return value.isEmpty() || "null".equalsIgnoreCase(value) ? List.of() : List.of(value);
    }
}
