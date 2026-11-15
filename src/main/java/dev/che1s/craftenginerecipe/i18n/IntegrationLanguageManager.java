package dev.che1s.craftenginerecipe.i18n;

import dev.che1s.craftenginerecipe.config.YamlDefaultsUpdater;
import org.bukkit.configuration.InvalidConfigurationException;
import org.bukkit.configuration.file.YamlConfiguration;

import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.BiFunction;
import java.util.function.Function;
import java.util.logging.Logger;

/**
 * 加载各配方导出器自己的本地化文本，并与主界面语言保持一致。
 *
 * <p>每个来源都以自己的英文文本兜底，再用当前语言覆盖缺失项；来源不再共享
 * 核心维护的 {@code common} 文案。</p>
 */
public final class IntegrationLanguageManager {
    private static final List<String> LOCALES = LanguageManager.supportedLocales();
    private static final List<String> SOURCES = List.of(
            "craftengine", "farmersdelight", "kaleidoscope",
            "customcrops", "cultivation", "minecraft", "private_alchemy");
    private final Path directory;
    private final Function<String, InputStream> resources;
    private final Logger logger;
    private volatile BiFunction<String, String, InputStream> extensionResources = (source, locale) -> null;
    private volatile Map<String, Map<String, Map<String, Object>>> activeByLocale = Map.of();
    private volatile String locale = "en_US";
    private volatile List<String> sources = SOURCES;

    public IntegrationLanguageManager(Path dataFolder, Function<String, InputStream> resources, Logger logger) {
        this.directory = dataFolder.resolve("lang/integrations");
        this.resources = resources;
        this.logger = logger;
    }

    public void load(String requestedLocale) {
        String selected = LanguageManager.supportedLocale(requestedLocale);
        Map<String, Map<String, Map<String, Object>>> all = new LinkedHashMap<>();
        for (String candidate : LOCALES) {
            Map<String, Map<String, Object>> loaded = new LinkedHashMap<>();
            for (String source : this.sources) {
                Map<String, Object> values = new LinkedHashMap<>(this.readSource(source, "en_US"));
                if (!candidate.equals("en_US")) {
                    values.putAll(this.readSource(source, candidate));
                }
                loaded.put(source, Map.copyOf(values));
            }
            all.put(candidate, Map.copyOf(loaded));
        }
        this.locale = selected;
        this.activeByLocale = Map.copyOf(all);
    }

    public void setExtensionResources(
            BiFunction<String, String, InputStream> extensionResources) {
        this.extensionResources = extensionResources == null
                ? (source, locale) -> null : extensionResources;
        this.load(this.locale);
    }

    public void setSources(java.util.Collection<String> additionalSources) {
        LinkedHashMap<String, Boolean> merged = new LinkedHashMap<>();
        SOURCES.forEach(source -> merged.put(source, Boolean.TRUE));
        if (additionalSources != null) {
            for (String source : additionalSources) {
                if (source == null || !source.matches("[a-z0-9_.-]+")) continue;
                merged.put(source, Boolean.TRUE);
            }
        }
        this.sources = List.copyOf(merged.keySet());
        this.load(this.locale);
    }

    private Map<String, Object> readSource(String source, String locale) {
        Path file = this.prepare(source, locale);
        if (file == null) return Map.of();
        try (var reader = Files.newBufferedReader(file, StandardCharsets.UTF_8)) {
            return read(reader);
        } catch (IOException | InvalidConfigurationException | RuntimeException error) {
            this.logger.warning("Cannot read integration language " + file + ": " + error.getMessage());
            return Map.of();
        }
    }

    public String text(String source, String key, Object... arguments) {
        return this.view(this.locale).text(source, key, arguments);
    }

    public List<String> list(String source, String key) {
        return this.view(this.locale).list(source, key);
    }

    public String locale() {
        return this.locale;
    }

    public Localized view(String requestedLocale) {
        String selected = LanguageManager.supportedLocale(requestedLocale);
        return new Localized(selected,
                this.activeByLocale.getOrDefault(selected, Map.of()));
    }

    private Path prepare(String source, String locale) {
        String relative = "lang/integrations/" + source + "/" + locale + ".yml";
        Path file = this.directory.resolve(source).resolve(locale + ".yml");
        InputStream bundled = this.resources.apply(relative);
        if (bundled == null) bundled = this.extensionResources.apply(source, locale);
        try (InputStream input = bundled) {
            if (input == null) return null;
            Files.createDirectories(file.getParent());
            // 与主语言文件相同：升级时补齐新键，但保留服主已经修改的文本。
            YamlDefaultsUpdater.update(file, input);
            return file;
        } catch (IOException | InvalidConfigurationException | RuntimeException error) {
            this.logger.warning("Cannot prepare integration language " + file + ": " + error.getMessage());
            return null;
        }
    }

    private static Map<String, Object> read(java.io.Reader reader)
            throws IOException, InvalidConfigurationException {
        YamlConfiguration yaml = new YamlConfiguration();
        yaml.load(reader);
        Map<String, Object> values = new LinkedHashMap<>();
        for (var entry : yaml.getValues(true).entrySet()) {
            // 只缓存字符串和字符串列表；其他类型不参与文案格式化。
            if (entry.getValue() instanceof String value) {
                values.put(entry.getKey(), value);
            } else if (entry.getValue() instanceof List<?> list
                    && list.stream().allMatch(String.class::isInstance)) {
                values.put(entry.getKey(), List.copyOf(list));
            }
        }
        return Map.copyOf(values);
    }

    private static String[] stringArguments(Object... arguments) {
        String[] values = new String[arguments.length];
        for (int index = 0; index < arguments.length; index++) {
            values[index] = arguments[index] == null ? "" : String.valueOf(arguments[index]);
        }
        return values;
    }

    public static final class Localized {
        private final String locale;
        private final Map<String, Map<String, Object>> sources;

        private Localized(String locale, Map<String, Map<String, Object>> sources) {
            this.locale = locale;
            this.sources = sources;
        }

        public String locale() {
            return this.locale;
        }

        public String text(String source, String key, Object... arguments) {
            Object value = this.sources.getOrDefault(source, Map.of()).get(key);
            return LanguageManager.format(value instanceof String string ? string : key,
                    stringArguments(arguments));
        }

        public List<String> list(String source, String key) {
            Object value = this.sources.getOrDefault(source, Map.of()).get(key);
            if (!(value instanceof List<?> list)) return List.of();
            return list.stream().map(String.class::cast).toList();
        }
    }
}
