package dev.leon.cer.i18n;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.minimessage.MiniMessage;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.configuration.file.YamlConfiguration;

import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.function.Function;
import java.util.logging.Logger;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Loads translations once per reload. Message rendering never reads files. */
public final class LanguageManager {
    private static final List<String> BUILT_INS = List.of("zh_CN", "en_US");
    private static final Pattern ARGUMENT = Pattern.compile("\\{([a-zA-Z0-9_-]+)}");
    private static final MiniMessage MINI = MiniMessage.miniMessage();
    private final Path directory;
    private final Function<String, InputStream> resources;
    private final Logger logger;
    private final Map<String, Map<String, Object>> bundled = new LinkedHashMap<>();
    private volatile Bundle active;

    public LanguageManager(Path dataFolder, Function<String, InputStream> resources, Logger logger) {
        this.directory = dataFolder.resolve("lang");
        this.resources = resources;
        this.logger = logger;
        for (String locale : BUILT_INS) {
            try (InputStream stream = resources.apply("lang/" + locale + ".yml")) {
                if (stream == null) throw new IllegalStateException("Missing bundled language: " + locale);
                this.bundled.put(locale, read(new InputStreamReader(stream, StandardCharsets.UTF_8)));
            } catch (Exception e) {
                throw new IllegalStateException("Cannot load bundled language: " + locale, e);
            }
        }
        this.active = new Bundle("zh_CN", this.bundled.get("zh_CN"));
    }

    public void load(String requested) {
        try {
            Files.createDirectories(this.directory);
            for (String locale : BUILT_INS) {
                Path file = this.directory.resolve(locale + ".yml");
                if (Files.notExists(file)) {
                    try (InputStream stream = this.resources.apply("lang/" + locale + ".yml")) {
                        if (stream == null) throw new IllegalStateException("Missing bundled language: " + locale);
                        Files.copy(stream, file);
                    }
                }
            }
        } catch (Exception e) {
            this.logger.warning(this.text("logs.language-save-failed", "error", error(e)));
        }

        String locale = normalizeLocale(requested);
        if (locale == null || (!this.bundled.containsKey(locale)
                && !Files.isRegularFile(this.directory.resolve(locale + ".yml")))) {
            this.logger.warning(this.text("logs.language-unknown", "locale", String.valueOf(requested)));
            locale = "zh_CN";
        }
        Map<String, Object> values = new LinkedHashMap<>(this.bundled.get("en_US"));
        values.putAll(this.bundled.getOrDefault(locale, Map.of()));
        Path file = this.directory.resolve(locale + ".yml");
        if (Files.isRegularFile(file)) {
            try (var reader = Files.newBufferedReader(file, StandardCharsets.UTF_8)) {
                Map<String, Object> overrides = read(reader);
                for (var entry : overrides.entrySet()) {
                    Object expected = values.get(entry.getKey());
                    if (expected == null || expected.getClass() == entry.getValue().getClass()
                            || (expected instanceof List<?> && entry.getValue() instanceof List<?>)) {
                        values.put(entry.getKey(), entry.getValue());
                    }
                }
            } catch (Exception e) {
                this.logger.warning(this.text("logs.language-read-failed", "file", file.toString(), "error", error(e)));
            }
        }
        this.active = new Bundle(locale, Map.copyOf(values));
    }

    private static Map<String, Object> read(java.io.Reader reader) throws Exception {
        YamlConfiguration yaml = new YamlConfiguration();
        yaml.load(reader);
        Map<String, Object> values = new LinkedHashMap<>();
        for (var entry : yaml.getValues(true).entrySet()) {
            if (entry.getValue() instanceof String value) values.put(entry.getKey(), value);
            else if (entry.getValue() instanceof List<?> list && list.stream().allMatch(String.class::isInstance)) {
                values.put(entry.getKey(), List.copyOf(list));
            }
        }
        return Map.copyOf(values);
    }

    static String normalizeLocale(String requested) {
        if (requested == null) return "zh_CN";
        String value = requested.trim().replace('-', '_');
        if (value.equalsIgnoreCase("zh_CN")) return "zh_CN";
        if (value.equalsIgnoreCase("en_US")) return "en_US";
        return value.matches("[A-Za-z][A-Za-z0-9_]{0,31}") ? value : null;
    }

    public String locale() { return this.active.locale(); }

    public String text(String key, String... arguments) {
        Object value = this.active.values().get(key);
        return format(value instanceof String string ? string : key, arguments);
    }

    public List<String> list(String key) {
        Object value = this.active.values().get(key);
        if (!(value instanceof List<?> list)) return List.of();
        return list.stream().map(String.class::cast).toList();
    }

    public Component component(String key, String... arguments) {
        return render(this.text(key), arguments);
    }

    public Component message(String key, String... arguments) {
        return render(this.text("prefix") + this.text(key), arguments);
    }

    private static Component render(String template, String... arguments) {
        Map<String, String> replacements = arguments(arguments);
        // Resolve all placeholders in one pass AFTER parsing formatting. Values cannot inject tags
        // or recursively expand another placeholder (e.g. a player-entered search term).
        return MINI.deserialize(template).replaceText(builder -> builder.match(ARGUMENT)
                .replacement((match, ignored) -> Component.text(replacements.getOrDefault(match.group(1), match.group()))));
    }

    static String format(String template, String... arguments) {
        Map<String, String> replacements = arguments(arguments);
        Matcher matcher = ARGUMENT.matcher(template);
        StringBuilder result = new StringBuilder();
        while (matcher.find()) {
            matcher.appendReplacement(result, Matcher.quoteReplacement(
                    replacements.getOrDefault(matcher.group(1), matcher.group())));
        }
        matcher.appendTail(result);
        return result.toString();
    }

    private static Map<String, String> arguments(String... arguments) {
        Map<String, String> values = new LinkedHashMap<>();
        for (int i = 0; i + 1 < arguments.length; i += 2) {
            values.put(arguments[i], Objects.toString(arguments[i + 1], ""));
        }
        return values;
    }

    /** Old custom UI text remains an override; old shipped defaults follow the selected language. */
    public String uiText(FileConfiguration config, String path) {
        Object override = this.legacyOverride(config, path);
        return override instanceof String text ? text : this.text("ui." + path);
    }

    public List<String> uiList(FileConfiguration config, String path) {
        Object override = this.legacyOverride(config, path);
        if (override instanceof List<?> list && list.stream().allMatch(String.class::isInstance)) {
            return list.stream().map(String.class::cast).toList();
        }
        return this.list("ui." + path);
    }

    private Object legacyOverride(FileConfiguration config, String path) {
        Object value = config.get(path);
        for (var defaults : this.bundled.values()) {
            if (Objects.equals(value, defaults.get("ui." + path))) return null;
        }
        return value;
    }

    private static String error(Exception error) {
        return Objects.toString(error.getMessage(), error.getClass().getSimpleName());
    }

    private record Bundle(String locale, Map<String, Object> values) {}
}
