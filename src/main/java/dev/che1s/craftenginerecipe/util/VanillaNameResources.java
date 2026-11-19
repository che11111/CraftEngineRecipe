package dev.che1s.craftenginerecipe.util;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Map;

/** Loads the compact packaged vanilla item-name translations for one Minecraft version. */
public final class VanillaNameResources {
    private VanillaNameResources() {
    }

    public static Map<String, Map<String, String>> read(ClassLoader loader, String minecraftVersion)
            throws IOException {
        if (loader == null || minecraftVersion == null || minecraftVersion.isBlank()) return Map.of();
        String resource = "vanilla-name-lang/minecraft-" + minecraftVersion.trim() + ".json";
        try (InputStream stream = loader.getResourceAsStream(resource)) {
            if (stream == null) return Map.of();
            try (var reader = new InputStreamReader(stream, StandardCharsets.UTF_8)) {
                JsonElement parsed = JsonParser.parseReader(reader);
                if (!parsed.isJsonObject()) throw new IOException(resource + ": expected an object");
                Map<String, Map<String, String>> result = new LinkedHashMap<>();
                for (var localeEntry : parsed.getAsJsonObject().entrySet()) {
                    if (!localeEntry.getValue().isJsonObject()) continue;
                    Map<String, String> names = new LinkedHashMap<>();
                    flatten("", localeEntry.getValue().getAsJsonObject(), names);
                    result.put(localeEntry.getKey(), Map.copyOf(names));
                }
                return Map.copyOf(result);
            }
        }
    }

    private static void flatten(String prefix, JsonObject object, Map<String, String> output) {
        for (var entry : object.entrySet()) {
            String key = prefix.isEmpty() ? entry.getKey() : prefix + "." + entry.getKey();
            JsonElement value = entry.getValue();
            if (value.isJsonPrimitive() && value.getAsJsonPrimitive().isString()) {
                output.put(key, value.getAsString());
            } else if (value.isJsonObject()) {
                flatten(key, value.getAsJsonObject(), output);
            }
        }
    }
}
