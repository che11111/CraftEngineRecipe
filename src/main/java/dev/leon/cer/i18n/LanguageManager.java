package dev.leon.cer.i18n;

import dev.leon.cer.config.YamlDefaultsUpdater;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.minimessage.MiniMessage;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.configuration.InvalidConfigurationException;

import java.io.IOException;
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
    private static final Pattern BOLD_TAG = Pattern.compile("</?(?:bold|b)(?::(?:true|false))?>|<!(?:bold|b)>", Pattern.CASE_INSENSITIVE);
    private static final Pattern RESET_TAG = Pattern.compile("<reset>", Pattern.CASE_INSENSITIVE);
    private static final Pattern COLOR_TAG = Pattern.compile("</?(?:black|dark_blue|dark_green|dark_aqua|dark_red|dark_purple|gold|gray|grey|dark_gray|dark_grey|blue|green|aqua|red|light_purple|yellow|white|#[0-9a-f]{6}|(?:color|colour|c|gradient|transition|rainbow)(?::[^>]*)?)>", Pattern.CASE_INSENSITIVE);
    private static final Map<String, String> LEGACY_BROWSER_TITLES = Map.of(
            "<dark_aqua>配方浏览器</dark_aqua> <gray>»</gray> <white><category></white>",
            "<bold><dark_aqua>配方浏览器</dark_aqua> <gray>»</gray> </bold><white><category></white>",
            "<bold><dark_aqua>配方浏览器</dark_aqua> <gray>»</gray> <white><category></white></bold>",
            "<bold><dark_aqua>配方浏览器</dark_aqua> <gray>»</gray> </bold><white><category></white>",
            "<dark_aqua>Recipe Browser</dark_aqua> <gray>»</gray> <white><category></white>",
            "<bold><dark_aqua>Recipe Browser</dark_aqua> <gray>»</gray> </bold><white><category></white>",
            "<bold><dark_aqua>Recipe Browser</dark_aqua> <gray>»</gray> <white><category></white></bold>",
            "<bold><dark_aqua>Recipe Browser</dark_aqua> <gray>»</gray> </bold><white><category></white>");
    private static final Map<String, String> LEGACY_TARGET_BLOCK_UNAVAILABLE = Map.of(
            "zh_CN", "<red>目标方块未在正式菜单中开放，或被权限、隐藏规则限制。</red>",
            "en_US", "<red>The target block is not available in the applied menu, or is restricted by permissions or hiding rules.</red>");
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
            } catch (IOException | InvalidConfigurationException | RuntimeException e) {
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
                } else {
                    try (InputStream stream = this.resources.apply("lang/" + locale + ".yml")) {
                        YamlDefaultsUpdater.update(file, stream);
                    } catch (IOException | InvalidConfigurationException | RuntimeException e) {
                        this.logger.warning(this.text("logs.language-update-failed", "file", file.toString(),
                                "error", error(e)));
                    }
                }
            }
        } catch (IOException | RuntimeException e) {
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
                    // Migrate only the exact former bundled message; keep server-owned translations intact.
                    if (entry.getKey().equals("messages.target-block-unavailable")
                            && Objects.equals(entry.getValue(), LEGACY_TARGET_BLOCK_UNAVAILABLE.get(locale))) continue;
                    if (sameDefault(entry.getKey(), entry.getValue(), expected)) continue;
                    if (expected == null || expected.getClass() == entry.getValue().getClass()
                            || (expected instanceof List<?> && entry.getValue() instanceof List<?>)) {
                        values.put(entry.getKey(), entry.getValue());
                    }
                }
            } catch (IOException | InvalidConfigurationException | RuntimeException e) {
                this.logger.warning(this.text("logs.language-read-failed", "file", file.toString(), "error", error(e)));
            }
        }
        this.active = new Bundle(locale, Map.copyOf(values));
    }

    private static Map<String, Object> read(java.io.Reader reader) throws IOException, InvalidConfigurationException {
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
        return format(styleTitle(key, value instanceof String string ? string : key), arguments);
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
        return override instanceof String text ? styleTitle("ui." + path, text) : this.text("ui." + path);
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
            Object fallback = defaults.get("ui." + path);
            if (sameDefault("ui." + path, value, fallback)) return null;
        }
        return value;
    }

    private static boolean sameDefault(String key, Object value, Object fallback) {
        if (Objects.equals(value, fallback)) return true;
        if (value instanceof String text && fallback instanceof String bundledText) {
            return Objects.equals(DefaultPalette.apply(styleTitle(key, text)), DefaultPalette.apply(styleTitle(key, bundledText)));
        }
        if (value instanceof List<?> lines && fallback instanceof List<?> bundledLines) {
            return lines.stream().allMatch(String.class::isInstance) && bundledLines.stream().allMatch(String.class::isInstance)
                    && lines.stream().map(String.class::cast).map(DefaultPalette::apply).toList()
                    .equals(bundledLines.stream().map(String.class::cast).map(DefaultPalette::apply).toList());
        }
        return false;
    }

    /** Apply title styling to old files too, without rewriting custom translations or tinting GUI images. */
    static String styleTitle(String key, String text) {
        String leaf = key.substring(key.lastIndexOf('.') + 1);
        if (!key.startsWith("ui.") || !(leaf.equals("title") || leaf.startsWith("title-") || leaf.endsWith("-title"))
                || text.isEmpty()) return text;
        int imageEnd = DefaultPalette.imagePrefixEnd(text);
        String prefix = BOLD_TAG.matcher(text.substring(0, imageEnd)).replaceAll("");
        String body = text.substring(imageEnd);
        // Browser titles may deliberately keep the category outside the bold heading.
        // Preserve that explicit scope; older titles without bold tags still get the default style.
        if (key.equals("ui.browser.title")) {
            for (var legacy : LEGACY_BROWSER_TITLES.entrySet()) {
                if (body.equals(legacy.getKey())) {
                    body = legacy.getValue();
                    break;
                }
                if (body.equals(DefaultPalette.apply(legacy.getKey()))) {
                    body = DefaultPalette.apply(legacy.getValue());
                    break;
                }
            }
        }
        if (key.equals("ui.browser.title") && BOLD_TAG.matcher(body).find()) return prefix + body;
        String visible = BOLD_TAG.matcher(body).replaceAll("");
        if (key.equals("ui.recipe.title") || key.startsWith("ui.recipe.title-")) {
            visible = COLOR_TAG.matcher(visible).replaceAll("");
            visible = RESET_TAG.matcher(visible).replaceAll("");
            return prefix + "<#292D35><bold>" + visible + "</bold></#292D35>";
        }
        visible = RESET_TAG.matcher(visible).replaceAll("<reset><bold>");
        return prefix + "<bold>" + visible + "</bold>";
    }

    private static String error(Exception error) {
        return Objects.toString(error.getMessage(), error.getClass().getSimpleName());
    }

    private record Bundle(String locale, Map<String, Object> values) {}
}
