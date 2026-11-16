package dev.che1s.craftenginerecipe.i18n;

import dev.che1s.craftenginerecipe.config.YamlDefaultsUpdater;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.minimessage.MiniMessage;
import org.bukkit.configuration.InvalidConfigurationException;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.configuration.file.YamlConfiguration;

import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.function.BiFunction;
import java.util.function.Function;
import java.util.logging.Logger;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 每次重载只加载一次翻译。渲染消息时不会再读取文件。
 *
 * <p>内置语言提供基础键，磁盘文件允许服主覆盖文本或补充其他语言；
 * 选择非英文语言时仍以英文内置资源补齐缺失项。</p>
 */
public final class LanguageManager {
    public static final String DEFAULT_LOCALE = "en_US";
    private static final List<String> BUILT_INS = List.of(
            "en_US", "zh_CN", "zh_TW", "ja_JP", "ko_KR",
            "de_DE", "fr_FR", "es_ES", "ru_RU");
    private static final Pattern ARGUMENT = Pattern.compile("\\{([a-zA-Z0-9_-]+)}");
    private static final MiniMessage MINI = MiniMessage.miniMessage();
    private final Path directory;
    private final Function<String, InputStream> resources;
    private final Logger logger;
    private final IntegrationLanguageManager integrations;
    private final Map<String, Map<String, Object>> bundled = new LinkedHashMap<>();
    private volatile Map<String, Bundle> prepared = Map.of();
    private volatile String serverLocale = "en_US";

    public LanguageManager(Path dataFolder, Function<String, InputStream> resources, Logger logger) {
        this.directory = dataFolder.resolve("lang");
        this.resources = resources;
        this.logger = logger;
        this.integrations = new IntegrationLanguageManager(dataFolder, resources, logger);
        for (String locale : BUILT_INS) {
            try (InputStream stream = resources.apply("lang/" + locale + ".yml")) {
                if (stream == null) throw new IllegalStateException("Missing bundled language: " + locale);
                this.bundled.put(locale, read(new InputStreamReader(stream, StandardCharsets.UTF_8)));
            } catch (IOException | InvalidConfigurationException | RuntimeException e) {
                throw new IllegalStateException("Cannot load bundled language: " + locale, e);
            }
        }
        this.prepared = Map.of(
                "en_US", new Bundle("en_US", this.bundled.get("en_US")),
                "zh_CN", new Bundle("zh_CN", this.bundled.get("zh_CN")));
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
                        // 仅补齐新版本新增的键，避免覆盖服主已有的自定义翻译。
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

        String locale = supportedLocale(requested);
        if (!Objects.equals(normalizeLocale(requested), locale)) {
            this.logger.warning(this.text("logs.language-unknown", "locale", String.valueOf(requested)));
        }
        Map<String, Bundle> loaded = new LinkedHashMap<>();
        for (String candidate : BUILT_INS) {
            // 英文基础集保证缺失键仍可读；当前语言与磁盘内容依次覆盖。
            Map<String, Object> values = new LinkedHashMap<>(this.bundled.get("en_US"));
            values.putAll(this.bundled.getOrDefault(candidate, Map.of()));
            Path file = this.directory.resolve(candidate + ".yml");
            if (Files.isRegularFile(file)) {
                try (var reader = Files.newBufferedReader(file, StandardCharsets.UTF_8)) {
                    values.putAll(read(reader));
                } catch (IOException | InvalidConfigurationException | RuntimeException e) {
                    this.logger.warning(this.text("logs.language-read-failed",
                            "file", file.toString(), "error", error(e)));
                }
            }
            loaded.put(candidate, new Bundle(candidate, Map.copyOf(values)));
        }
        this.prepared = Map.copyOf(loaded);
        this.serverLocale = locale;
        this.integrations.load(locale);
    }

    public void loadAuto() {
        this.load(detectServerLocale(Locale.getDefault()));
    }

    private static Map<String, Object> read(java.io.Reader reader) throws IOException, InvalidConfigurationException {
        YamlConfiguration yaml = new YamlConfiguration();
        yaml.load(reader);
        Map<String, Object> values = new LinkedHashMap<>();
        for (var entry : yaml.getValues(true).entrySet()) {
            if (entry.getValue() instanceof String value) {
                values.put(entry.getKey(), value);
            } else if (entry.getValue() instanceof List<?> list
                    && list.stream().allMatch(String.class::isInstance)) {
                values.put(entry.getKey(), List.copyOf(list));
            }
        }
        return Map.copyOf(values);
    }

    static String normalizeLocale(String requested) {
        if (requested == null) return "en_US";
        String value = requested.trim().replace('-', '_');
        if (value.equalsIgnoreCase("zh_CN")) return "zh_CN";
        if (value.equalsIgnoreCase("en_US")) return "en_US";
        return value.matches("[A-Za-z][A-Za-z0-9_]{0,31}") ? value : null;
    }

    public String locale() {
        return this.serverLocale;
    }

    public static List<String> supportedLocales() {
        return BUILT_INS;
    }

    public static String detectServerLocale(Locale locale) {
        Locale effective = locale == null ? Locale.getDefault() : locale;
        String language = effective.getLanguage();
        if ("zh".equalsIgnoreCase(language)) {
            String country = effective.getCountry();
            return country.equalsIgnoreCase("TW") || country.equalsIgnoreCase("HK")
                    || country.equalsIgnoreCase("MO") ? "zh_TW" : "zh_CN";
        }
        String selected = normalizeSupportedLocale(language);
        return selected == null ? DEFAULT_LOCALE : selected;
    }

    public static String supportedLocale(String requested) {
        String selected = normalizeSupportedLocale(requested);
        return selected == null ? DEFAULT_LOCALE : selected;
    }

    /** Returns a supported canonical locale, or null when no installed language matches it. */
    public static String normalizeSupportedLocale(String requested) {
        if (requested == null || requested.isBlank()) return null;
        String normalized = normalizeLocale(requested);
        if (normalized == null) return null;
        for (String locale : BUILT_INS) {
            if (locale.equalsIgnoreCase(normalized)) return locale;
        }
        String[] parts = normalized.split("_", 2);
        String language = parts[0];
        String region = parts.length > 1 ? parts[1] : "";
        if ("zh".equals(language)) {
            return region.equals("tw") || region.equals("hk") || region.equals("mo")
                    ? "zh_TW" : "zh_CN";
        }
        return switch (language) {
            case "en" -> "en_US";
            case "ja" -> "ja_JP";
            case "ko" -> "ko_KR";
            case "de" -> "de_DE";
            case "fr" -> "fr_FR";
            case "es" -> "es_ES";
            case "ru" -> "ru_RU";
            default -> null;
        };
    }

    public Localized view(String requested) {
        String locale = supportedLocale(requested);
        Bundle bundle = this.prepared.getOrDefault(locale,
                new Bundle(locale, this.bundled.getOrDefault(locale, Map.of())));
        return new Localized(bundle.locale(), bundle.values(), this.integrations.view(locale));
    }

    public String integrationText(String source, String key, Object... arguments) {
        return this.view(this.serverLocale).integrationText(source, key, arguments);
    }

    public void setExtensionLanguageResources(
            BiFunction<String, String, InputStream> resources) {
        this.integrations.setExtensionResources(resources);
    }

    public void setIntegrationSources(java.util.Collection<String> sources) {
        this.integrations.setSources(sources);
    }

    public List<String> integrationList(String source, String key) {
        return this.view(this.serverLocale).integrationList(source, key);
    }

    public String text(String key, String... arguments) {
        return this.view(this.serverLocale).text(key, arguments);
    }

    /** 缺失翻译时使用调用方提供的可读兜底，避免把内部语言键直接展示给玩家。 */
    public String textOr(String key, String fallback, String... arguments) {
        return this.view(this.serverLocale).textOr(key, fallback, arguments);
    }

    public List<String> list(String key) {
        return this.view(this.serverLocale).list(key);
    }

    public Component component(String key, String... arguments) {
        return this.view(this.serverLocale).component(key, arguments);
    }

    public Component componentWith(String key, String argument, Component replacement,
                                   String... arguments) {
        return this.view(this.serverLocale).componentWith(key, argument, replacement, arguments);
    }

    public Component componentOr(String key, String fallback, String... arguments) {
        return this.view(this.serverLocale).componentOr(key, fallback, arguments);
    }

    public Component message(String key, String... arguments) {
        return this.view(this.serverLocale).message(key, arguments);
    }

    private static Component render(String template, String... arguments) {
        return render(template, Map.of(), arguments);
    }

    private static Component render(String template, Map<String, Component> componentArguments,
                                    String... arguments) {
        Map<String, String> replacements = arguments(arguments);
        return MINI.deserialize(template).replaceText(builder -> builder.match(ARGUMENT)
                .replacement((match, ignored) -> {
                    String name = match.group(1);
                    Component replacement = componentArguments.get(name);
                    return replacement != null ? replacement
                            : Component.text(replacements.getOrDefault(name, match.group()));
                }));
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

    public String uiText(FileConfiguration config, String path) {
        return this.view(this.serverLocale).uiText(config, path);
    }

    public List<String> uiList(FileConfiguration config, String path) {
        return this.view(this.serverLocale).uiList(config, path);
    }

    private static String error(Exception error) {
        return Objects.toString(error.getMessage(), error.getClass().getSimpleName());
    }

    private record Bundle(String locale, Map<String, Object> values) {}

    public static final class Localized {
        private final String locale;
        private final Map<String, Object> values;
        private final IntegrationLanguageManager.Localized integrations;

        private Localized(String locale, Map<String, Object> values,
                          IntegrationLanguageManager.Localized integrations) {
            this.locale = locale;
            this.values = values;
            this.integrations = integrations;
        }

        public String locale() {
            return this.locale;
        }

        public String integrationText(String source, String key, Object... arguments) {
            return this.integrations.text(source, key, arguments);
        }

        public List<String> integrationList(String source, String key) {
            return this.integrations.list(source, key);
        }

        public String text(String key, String... arguments) {
            Object value = this.values.get(key);
            return format(value instanceof String string ? string : key, arguments);
        }

        public String textOr(String key, String fallback, String... arguments) {
            Object value = this.values.get(key);
            return format(value instanceof String string ? string : fallback, arguments);
        }

        public List<String> list(String key) {
            Object value = this.values.get(key);
            if (!(value instanceof List<?> list)) return List.of();
            return list.stream().map(String.class::cast).toList();
        }

        public Component component(String key, String... arguments) {
            return render(this.text(key), arguments);
        }

        public Component componentWith(String key, String argument, Component replacement,
                                       String... arguments) {
            return render(this.text(key), Map.of(argument, replacement), arguments);
        }

        public Component componentOr(String key, String fallback, String... arguments) {
            Object value = this.values.get(key);
            return render(value instanceof String string ? string : fallback, arguments);
        }

        public Component message(String key, String... arguments) {
            return render(this.text("prefix") + this.text(key), arguments);
        }

        public String uiText(FileConfiguration config, String path) {
            Object value = config.get(path);
            return value instanceof String text ? text : this.text("ui." + path);
        }

        public List<String> uiList(FileConfiguration config, String path) {
            Object value = config.get(path);
            if (value instanceof List<?> list && list.stream().allMatch(String.class::isInstance)) {
                return list.stream().map(String.class::cast).toList();
            }
            return this.list("ui." + path);
        }
    }
}
