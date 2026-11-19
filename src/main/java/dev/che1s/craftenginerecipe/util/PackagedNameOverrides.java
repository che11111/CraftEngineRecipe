package dev.che1s.craftenginerecipe.util;

import com.google.gson.JsonElement;
import com.google.gson.JsonParser;

import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.jar.JarFile;

/** Loads language maps that fill translation gaps left by bundled CraftEngine packs. */
public final class PackagedNameOverrides {
    private PackagedNameOverrides() {
    }

    public static Map<String, Map<String, String>> read(ClassLoader loader, String resource)
            throws IOException {
        if (loader == null || resource == null || resource.isBlank()) return Map.of();
        try (InputStream stream = loader.getResourceAsStream(resource)) {
            if (stream == null) return Map.of();
            return parse(stream, resource);
        }
    }

    public static Map<String, Map<String, String>> readJar(Path jarPath, String resource)
            throws IOException {
        if (jarPath == null || !java.nio.file.Files.isRegularFile(jarPath)) return Map.of();
        try (JarFile jar = new JarFile(jarPath.toFile(), false)) {
            var entry = jar.getJarEntry(resource);
            if (entry == null) return Map.of();
            try (InputStream stream = jar.getInputStream(entry)) {
                return parse(stream, jarPath.getFileName() + "!" + resource);
            }
        }
    }

    private static Map<String, Map<String, String>> parse(InputStream stream, String source)
            throws IOException {
        try (var reader = new InputStreamReader(stream, StandardCharsets.UTF_8)) {
            JsonElement parsed = JsonParser.parseReader(reader);
            if (!parsed.isJsonObject()) throw new IOException(source + ": expected an object");
            Map<String, Map<String, String>> result = new LinkedHashMap<>();
            for (var localeEntry : parsed.getAsJsonObject().entrySet()) {
                if (!localeEntry.getValue().isJsonObject()) continue;
                Map<String, String> values = new LinkedHashMap<>();
                localeEntry.getValue().getAsJsonObject().entrySet().forEach(entry -> {
                    if (entry.getValue().isJsonPrimitive()
                            && entry.getValue().getAsJsonPrimitive().isString()) {
                        values.put(entry.getKey(), entry.getValue().getAsString());
                    }
                });
                result.put(localeEntry.getKey(), Map.copyOf(values));
            }
            return Map.copyOf(result);
        }
    }
}
