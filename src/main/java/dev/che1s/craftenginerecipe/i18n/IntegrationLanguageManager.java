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
import java.util.function.Function;
import java.util.logging.Logger;

/**
 * 加载各配方导出器自己的本地化文本，并与主界面语言保持一致。
 *
 * <p>每个来源都会先继承 {@code common}，再以英文文本兜底，最后覆盖当前语言；
 * 因此来源文案只需声明自己的差异项，不需要重复维护公共提示。</p>
 */
public final class IntegrationLanguageManager {
    private static final List<String> LOCALES = List.of("en_US", "zh_CN");
    private static final List<String> SOURCES = List.of(
            "common", "craftengine", "farmersdelight", "kaleidoscope",
            "customcrops", "cultivation", "minecraft");
    private final Path directory;
    private final Function<String, InputStream> resources;
    private final Logger logger;
    private volatile Map<String, Map<String, Object>> active = Map.of();
    private volatile String locale = "zh_CN";

    public IntegrationLanguageManager(Path dataFolder, Function<String, InputStream> resources, Logger logger) {
        this.directory = dataFolder.resolve("lang/integrations");
        this.resources = resources;
        this.logger = logger;
    }

    public void load(String requestedLocale) {
        String selected = LanguageManager.normalizeLocale(requestedLocale);
        if (!LOCALES.contains(selected)) selected = "zh_CN";
        Map<String, Map<String, Object>> loaded = new LinkedHashMap<>();
        // common 是各来源共享的提示文本；英文先铺底，当前语言只覆盖已有键。
        Map<String, Object> common = new LinkedHashMap<>(this.readSource("common", "en_US"));
        if (!selected.equals("en_US")) common.putAll(this.readSource("common", selected));
        for (String source : SOURCES) {
            Map<String, Object> values = new LinkedHashMap<>(common);
            // 同一叠加顺序也适用于来源文件，缺失的本地化键会自然回退到英文。
            values.putAll(this.readSource(source, "en_US"));
            if (!selected.equals("en_US")) values.putAll(this.readSource(source, selected));
            loaded.put(source, Map.copyOf(values));
        }
        this.locale = selected;
        this.active = Map.copyOf(loaded);
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
        Object value = this.active.getOrDefault(source, Map.of()).get(key);
        return LanguageManager.format(value instanceof String string ? string : key, stringArguments(arguments));
    }

    public List<String> list(String source, String key) {
        Object value = this.active.getOrDefault(source, Map.of()).get(key);
        if (!(value instanceof List<?> list)) return List.of();
        return list.stream().map(String.class::cast).toList();
    }

    public String locale() {
        return this.locale;
    }

    private Path prepare(String source, String locale) {
        String relative = "lang/integrations/" + source + "/" + locale + ".yml";
        Path file = this.directory.resolve(source).resolve(locale + ".yml");
        try (InputStream bundled = this.resources.apply(relative)) {
            if (bundled == null) return null;
            Files.createDirectories(file.getParent());
            // 与主语言文件相同：升级时补齐新键，但保留服主已经修改的文本。
            YamlDefaultsUpdater.update(file, bundled);
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
}
