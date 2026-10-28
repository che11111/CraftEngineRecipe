package dev.che1s.craftenginerecipe.config;

import dev.che1s.craftenginerecipe.CraftEngineRecipePlugin;
import org.bukkit.configuration.file.FileConfiguration;

import java.util.Collection;
import java.util.List;
import java.util.Locale;
import java.util.Objects;

/** 外部适配插件的持久化启用策略。 */
public final class IntegrationSettings {
    public static final List<String> PROMPTABLE_SOURCES =
            List.of("farmersdelight", "kaleidoscope", "customcrops");

    public enum Mode {
        ASK,
        ENABLED,
        DISABLED
    }

    private final CraftEngineRecipePlugin plugin;

    public IntegrationSettings(CraftEngineRecipePlugin plugin) {
        this.plugin = Objects.requireNonNull(plugin, "plugin");
    }

    public Mode mode(String sourceId) {
        return mode(this.plugin.getConfig(), sourceId);
    }

    static Mode mode(FileConfiguration config, String sourceId) {
        String path = path(sourceId) + ".mode";
        if (!config.isSet(path)) {
            return Mode.ASK;
        }
        return switch (String.valueOf(config.getString(path, "ask")).trim().toLowerCase(Locale.ROOT)) {
            case "enabled" -> Mode.ENABLED;
            case "disabled" -> Mode.DISABLED;
            default -> Mode.ASK;
        };
    }

    public boolean enabled(String sourceId) {
        return this.mode(sourceId) == Mode.ENABLED;
    }

    public boolean cultivation(String sourceId) {
        return this.enabled(sourceId)
                && this.plugin.getConfig().getBoolean(path(sourceId) + ".cultivation", true);
    }

    public boolean exportMissing(String sourceId) {
        return this.plugin.getConfig().getBoolean(path(sourceId) + ".export-missing", true);
    }

    public boolean reexportOnReload(String sourceId) {
        return this.plugin.getConfig().getBoolean(path(sourceId) + ".reexport-on-reload", true);
    }

    public boolean reexportOnCeReload(String sourceId) {
        return this.plugin.getConfig().getBoolean(path(sourceId) + ".reexport-craftengine-on-ce-reload", true);
    }

    public List<String> enabledSources() {
        return PROMPTABLE_SOURCES.stream().filter(this::enabled).toList();
    }

    /** 交互式启用会同时打开普通配方和对应栽培数据。 */
    public void enable(Collection<String> sources) {
        for (String source : sources) {
            if (!PROMPTABLE_SOURCES.contains(source)) continue;
            this.plugin.getConfig().set(path(source) + ".mode", "enabled");
            this.plugin.getConfig().set(path(source) + ".cultivation", true);
        }
        this.plugin.saveConfig();
    }

    /** 永久忽略只停止后续导出，不删除已经生成的 recipes/<source>/。 */
    public void disable(Collection<String> sources) {
        for (String source : sources) {
            if (!PROMPTABLE_SOURCES.contains(source)) continue;
            this.plugin.getConfig().set(path(source) + ".mode", "disabled");
            this.plugin.getConfig().set(path(source) + ".cultivation", false);
        }
        this.plugin.saveConfig();
    }

    private static String path(String sourceId) {
        return "integrations." + sourceId;
    }
}
