package dev.che1s.craftenginerecipe.config;

import dev.che1s.craftenginerecipe.CraftEngineRecipePlugin;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.configuration.file.YamlConfiguration;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.util.Collection;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;

/** 外部适配插件的持久化启用策略。 */
public final class IntegrationSettings {
    public enum Mode {
        ASK,
        ENABLED,
        DISABLED
    }

    private final CraftEngineRecipePlugin plugin;
    private final File file;
    private final File legacyCategoryPresets;
    private final File legacyCategoryPresetState;
    private FileConfiguration config;

    public IntegrationSettings(CraftEngineRecipePlugin plugin) {
        this.plugin = Objects.requireNonNull(plugin, "plugin");
        this.file = new File(plugin.getDataFolder(), "integrations.yml");
        this.legacyCategoryPresets = new File(
                plugin.getDataFolder(), "navigation/category-presets.yml");
        this.legacyCategoryPresetState = new File(
                plugin.getDataFolder(), "navigation/category-presets-state.yml");
        this.reload();
    }

    /** 重载独立的 integrations.yml，并迁移旧版 config.yml 中的 integrations 配置。 */
    public void reload() {
        if (!this.file.exists()) this.plugin.saveResource("integrations.yml", false);
        this.config = YamlConfiguration.loadConfiguration(this.file);
        boolean migrateIntegrations = this.plugin.getConfig().isConfigurationSection("integrations");
        boolean changed = this.migrateLegacyConfig();
        boolean migratePresets = this.migrateLegacyCategoryPresets();
        boolean migratePresetState = this.migrateLegacyCategoryPresetState();
        changed |= migratePresets || migratePresetState;
        if (changed && this.save()) {
            if (migrateIntegrations) {
                this.plugin.getConfig().set("integrations", null);
                this.plugin.saveConfig();
            }
            if (migratePresets) this.archiveLegacyCategoryPresets();
            if (migratePresetState) this.archiveLegacyCategoryPresetState();
        }
    }

    public List<Map<?, ?>> categoryPresets() {
        return this.config.getMapList("category-presets");
    }

    public Mode mode(String sourceId) {
        return mode(sourceId, this.plugin.extensions().defaultMode(sourceId));
    }

    public Mode mode(String sourceId, Mode fallback) {
        return mode(this.config, sourceId, fallback);
    }

    public boolean isConfigured(String sourceId) {
        return this.config.isSet(path(sourceId) + ".mode");
    }

    public Mode categoryPresetMode(String namespace) {
        for (Map<?, ?> entry : this.categoryPresets()) {
            if (namespace.equals(String.valueOf(entry.get("id")))) {
                return parseMode(entry.get("mode"), Mode.ASK);
            }
        }
        return Mode.ASK;
    }

    public boolean setCategoryPresetMode(String namespace, Mode mode) {
        return setCategoryPresetMode(this.config, namespace, mode) && this.save();
    }

    static boolean setCategoryPresetMode(FileConfiguration config, String namespace, Mode mode) {
        List<Map<String, Object>> updated = new java.util.ArrayList<>();
        boolean found = false;
        for (Map<?, ?> entry : config.getMapList("category-presets")) {
            Map<String, Object> copied = new java.util.LinkedHashMap<>();
            entry.forEach((key, value) -> copied.put(String.valueOf(key), value));
            if (namespace.equals(String.valueOf(copied.get("id")))) {
                copied.put("mode", mode.name().toLowerCase(Locale.ROOT));
                found = true;
            }
            updated.add(copied);
        }
        if (!found && namespace != null && !namespace.isBlank() && mode != null) {
            Map<String, Object> state = new java.util.LinkedHashMap<>();
            state.put("id", namespace);
            state.put("mode", mode.name().toLowerCase(Locale.ROOT));
            updated.add(state);
            found = true;
        }
        if (found) config.set("category-presets", updated);
        return found;
    }

    static Mode mode(FileConfiguration config, String sourceId) {
        return mode(config, sourceId, Mode.ASK);
    }

    static Mode mode(FileConfiguration config, String sourceId, Mode fallback) {
        String path = path(sourceId) + ".mode";
        if (!config.isSet(path)) {
            return fallback;
        }
        return parseMode(config.getString(path, fallback.name().toLowerCase(Locale.ROOT)), fallback);
    }

    private static Mode parseMode(Object value, Mode fallback) {
        if (value == null) return fallback;
        return switch (String.valueOf(value).trim().toLowerCase(Locale.ROOT)) {
            case "enabled" -> Mode.ENABLED;
            case "disabled" -> Mode.DISABLED;
            case "ask" -> Mode.ASK;
            default -> fallback;
        };
    }

    public boolean enabled(String sourceId) {
        return this.mode(sourceId) == Mode.ENABLED;
    }

    public boolean enabled(String sourceId, Mode fallback) {
        return this.mode(sourceId, fallback) == Mode.ENABLED;
    }

    public boolean cultivation(String sourceId) {
        return this.enabled(sourceId)
                && this.option(sourceId, "cultivation", true);
    }

    public boolean exportMissing(String sourceId) {
        return this.option(sourceId, "export-missing", true);
    }

    public boolean reexportOnReload(String sourceId) {
        return this.option(sourceId, "reexport-on-reload", true);
    }

    public boolean reexportOnCeReload(String sourceId) {
        return this.option(sourceId, "reexport-craftengine-on-ce-reload", true);
    }

    public boolean option(String sourceId, String option, boolean fallback) {
        if (sourceId == null || sourceId.isBlank() || option == null || option.isBlank()) return fallback;
        String optionPath = path(sourceId) + "." + option;
        if (this.config.isSet(optionPath)) return this.config.getBoolean(optionPath, fallback);
        return this.plugin.extensions().integrationOption(sourceId, option, fallback);
    }

    public List<String> enabledSources() {
        return configuredSources().stream().filter(this::enabled).toList();
    }

    /** 启用该适配插件时要同步应用的内置分类预设。 */
    public List<String> categoryPresets(String sourceId) {
        return categoryPresets(this.config, sourceId);
    }

    static List<String> categoryPresets(FileConfiguration config, String sourceId) {
        return config.getStringList(path(sourceId) + ".category-presets").stream()
                .filter(value -> value != null && !value.isBlank())
                .map(String::trim)
                .distinct()
                .toList();
    }

    public java.util.Set<String> linkedCategoryPresets() {
        java.util.LinkedHashSet<String> linked = new java.util.LinkedHashSet<>();
        for (String source : this.configuredSources()) linked.addAll(this.categoryPresets(source));
        return java.util.Collections.unmodifiableSet(linked);
    }

    /** All source IDs currently declared under integrations, including extension-only IDs. */
    public List<String> configuredSources() {
        java.util.LinkedHashSet<String> sources =
                new java.util.LinkedHashSet<>(this.plugin.extensions().ids());
        var section = this.config.getConfigurationSection("integrations");
        if (section != null) sources.addAll(section.getKeys(false));
        return List.copyOf(sources);
    }

    /** 交互式启用会同时打开普通配方和对应栽培数据。 */
    public void enable(Collection<String> sources) {
        Map<String, Mode> modes = new java.util.LinkedHashMap<>();
        for (String source : sources) modes.put(source, Mode.ENABLED);
        this.setModes(modes);
    }

    /** 永久忽略只停止后续导出，不删除已经生成的 recipes/<source>/。 */
    public void disable(Collection<String> sources) {
        Map<String, Mode> modes = new java.util.LinkedHashMap<>();
        for (String source : sources) modes.put(source, Mode.DISABLED);
        this.setModes(modes);
    }

    /**
     * 逐项保存适配插件的显式选择。管理页中的 ask 也必须是持久化的真实状态，
     * 不能只处理 enabled/disabled，否则旧状态会继续生效。
     */
    public boolean setModes(Map<String, Mode> modes) {
        return this.setModes(modes, this.configuredSources());
    }

    public boolean setModes(Map<String, Mode> modes, Collection<String> knownSources) {
        if (modes == null || modes.isEmpty()) return true;
        return !setModes(this.config, modes, knownSources) || this.save();
    }

    static boolean setModes(FileConfiguration config, Map<String, Mode> modes) {
        return setModes(config, modes, modes == null ? List.of() : modes.keySet());
    }

    static boolean setModes(FileConfiguration config, Map<String, Mode> modes,
                            Collection<String> knownSources) {
        if (config == null || modes == null || modes.isEmpty()) return false;
        boolean changed = false;
        for (var entry : modes.entrySet()) {
            String source = entry.getKey();
            Mode mode = entry.getValue();
            if (source == null || knownSources == null || !knownSources.contains(source)
                    || !source.matches("[a-z0-9_.-]+") || mode == null) continue;
            String modePath = path(source) + ".mode";
            String cultivationPath = path(source) + ".cultivation";
            String value = mode.name().toLowerCase(Locale.ROOT);
            if (!value.equals(config.getString(modePath))) {
                config.set(modePath, value);
                changed = true;
            }
            if (mode == Mode.ENABLED
                    && (!config.isSet(cultivationPath) || !config.getBoolean(cultivationPath, true))) {
                config.set(cultivationPath, true);
                changed = true;
            } else if (mode == Mode.DISABLED
                    && (!config.isSet(cultivationPath) || config.getBoolean(cultivationPath, true))) {
                config.set(cultivationPath, false);
                changed = true;
            }
        }
        return changed;
    }

    private boolean migrateLegacyConfig() {
        var legacy = this.plugin.getConfig().getConfigurationSection("integrations");
        if (legacy == null) return false;
        for (String source : legacy.getKeys(false)) {
            var values = legacy.getConfigurationSection(source);
            if (values == null) continue;
            for (String key : values.getKeys(true)) {
                if (values.isConfigurationSection(key)) continue;
                this.config.set(path(source) + "." + key, values.get(key));
            }
        }
        return true;
    }

    /** 兼容拆分试验版留下的独立预设文件，将内容并回 integrations.yml。 */
    private boolean migrateLegacyCategoryPresets() {
        if (!this.legacyCategoryPresets.isFile()) return false;
        YamlConfiguration presets = YamlConfiguration.loadConfiguration(this.legacyCategoryPresets);
        List<Map<?, ?>> entries = presets.getMapList("presets");
        if (!entries.isEmpty()) this.config.set("category-presets", entries);
        return true;
    }

    private void archiveLegacyCategoryPresets() {
        try {
            Files.move(this.legacyCategoryPresets.toPath(),
                    this.legacyCategoryPresets.toPath().resolveSibling("category-presets.yml.migrated"),
                    StandardCopyOption.REPLACE_EXISTING);
        } catch (IOException error) {
            this.plugin.getLogger().warning("Cannot archive legacy category-presets.yml: " + error.getMessage());
        }
    }

    private boolean migrateLegacyCategoryPresetState() {
        if (!this.legacyCategoryPresetState.isFile()) return false;
        YamlConfiguration state = YamlConfiguration.loadConfiguration(this.legacyCategoryPresetState);
        for (String namespace : state.getStringList("declined")) {
            if (namespace != null && !namespace.isBlank()) {
                setCategoryPresetMode(this.config, namespace, Mode.DISABLED);
            }
        }
        return true;
    }

    private void archiveLegacyCategoryPresetState() {
        try {
            Files.move(this.legacyCategoryPresetState.toPath(),
                    this.legacyCategoryPresetState.toPath()
                            .resolveSibling("category-presets-state.yml.migrated"),
                    StandardCopyOption.REPLACE_EXISTING);
        } catch (IOException error) {
            this.plugin.getLogger().warning(
                    "Cannot archive legacy category-presets-state.yml: " + error.getMessage());
        }
    }

    private boolean save() {
        try {
            this.config.save(this.file);
            return true;
        } catch (IOException error) {
            this.plugin.getLogger().warning("Cannot save integrations.yml: " + error.getMessage());
            return false;
        }
    }

    private static String path(String sourceId) {
        return "integrations." + sourceId;
    }
}
