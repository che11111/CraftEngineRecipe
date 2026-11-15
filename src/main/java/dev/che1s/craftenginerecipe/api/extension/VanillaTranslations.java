package dev.che1s.craftenginerecipe.api.extension;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.jar.JarEntry;
import java.util.jar.JarFile;

/** 读取按版本归档的原版物品翻译，无需依赖客户端资源目录。 */
public final class VanillaTranslations {
    private VanillaTranslations() {}

    public static Map<String, String> read(Path jarPath, boolean chinese) throws IOException {
        return read(jarPath, chinese ? "zh_cn" : "en_us");
    }

    public static Map<String, String> read(Path jarPath, String locale) throws IOException {
        String normalized = locale == null || locale.isBlank() ? "en_us" : locale.toLowerCase(java.util.Locale.ROOT);
        try (JarFile jar = new JarFile(jarPath.toFile())) {
            JarEntry entry = jar.stream()
                    .filter(value -> !value.isDirectory())
                    .filter(value -> value.getName().startsWith("vanilla-lang/minecraft-")
                            && value.getName().endsWith("/" + normalized + ".json"))
                    .findFirst().orElse(null);
            if (entry == null && "en_us".equals(normalized)) {
                entry = jar.getJarEntry("assets/minecraft/lang/en_us.json");
            }
            if (entry == null) return Map.of();
            JsonObject root = JsonParser.parseString(
                    new String(jar.getInputStream(entry).readAllBytes(), StandardCharsets.UTF_8))
                    .getAsJsonObject();
            Map<String, String> values = new LinkedHashMap<>();
            flatten("", root, values);
            return Collections.unmodifiableMap(values);
        }
    }

    /** Reads only the translation bundle for the exact running Minecraft version. */
    public static Map<String, String> read(Path jarPath, String minecraftVersion,
                                           String locale) throws IOException {
        String normalizedLocale = locale == null || locale.isBlank()
                ? "en_us" : locale.toLowerCase(java.util.Locale.ROOT);
        String normalizedVersion = minecraftVersion == null ? "" : minecraftVersion.trim();
        if (normalizedVersion.isBlank()) return read(jarPath, normalizedLocale);
        String prefix = "vanilla-lang/minecraft-" + normalizedVersion + "/";
        try (JarFile jar = new JarFile(jarPath.toFile())) {
            JarEntry entry = jar.getJarEntry(prefix + normalizedLocale + ".json");
            if (entry == null) return Map.of();
            JsonObject root = JsonParser.parseString(
                    new String(jar.getInputStream(entry).readAllBytes(), StandardCharsets.UTF_8))
                    .getAsJsonObject();
            Map<String, String> values = new LinkedHashMap<>();
            flatten("", root, values);
            return Collections.unmodifiableMap(values);
        }
    }

    public static String itemName(Map<String, String> translations, String id) {
        if (translations == null || id == null || !id.startsWith("minecraft:")) return null;
        String path = id.substring("minecraft:".length());
        String value = translations.get("item.minecraft." + path);
        if (value == null) value = translations.get("block.minecraft." + path);
        return value;
    }

    private static void flatten(String prefix, JsonElement element, Map<String, String> values) {
        if (element.isJsonPrimitive() && element.getAsJsonPrimitive().isString()) {
            values.put(prefix, element.getAsString());
            return;
        }
        if (element.isJsonObject()) {
            element.getAsJsonObject().entrySet().forEach(entry ->
                    flatten(prefix.isEmpty() ? entry.getKey() : prefix + "." + entry.getKey(),
                            entry.getValue(), values));
        }
    }
}
