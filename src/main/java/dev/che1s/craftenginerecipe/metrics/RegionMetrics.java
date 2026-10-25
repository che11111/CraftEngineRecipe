package dev.che1s.craftenginerecipe.metrics;

import dev.che1s.craftenginerecipe.CraftEngineRecipePlugin;
import org.bstats.MetricsBase;
import org.bstats.json.JsonObjectBuilder;
import org.bukkit.Bukkit;
import org.bukkit.configuration.file.YamlConfiguration;

import java.io.File;
import java.io.IOException;
import java.util.List;
import java.util.UUID;
import java.util.logging.Level;

/** 使用全局区域数据收集器替代 BukkitScheduler 的官方 bStats 传输实现。 */
final class RegionMetrics {
    private final MetricsBase base;

    RegionMetrics(CraftEngineRecipePlugin plugin, int serviceId) throws IOException {
        File file = new File(plugin.getDataFolder().getParentFile(), "bStats/config.yml");
        YamlConfiguration config = YamlConfiguration.loadConfiguration(file);
        if (!config.isSet("serverUuid")) {
            config.addDefault("enabled", true);
            config.addDefault("serverUuid", UUID.randomUUID().toString());
            config.addDefault("logFailedRequests", false);
            config.addDefault("logSentData", false);
            config.addDefault("logResponseStatusText", false);
            config.options().copyDefaults(true).setHeader(List.of(
                    "bStats anonymous metrics / bStats 匿名统计",
                    "Set enabled to false to disable globally. / enabled: false 全局关闭统计。",
                    "https://bstats.org/"));
            config.save(file);
        }
        this.base = new MetricsBase("bukkit", config.getString("serverUuid"), serviceId,
                config.getBoolean("enabled", true), RegionMetrics::platformData,
                data -> serviceData(data, plugin.getPluginMeta().getVersion()),
                action -> {
                    if (plugin.isEnabled()) plugin.tasks().global(action);
                }, plugin::isEnabled,
                (message, error) -> plugin.getLogger().log(Level.WARNING, message, error),
                plugin.getLogger()::info, config.getBoolean("logFailedRequests", false),
                config.getBoolean("logSentData", false), config.getBoolean("logResponseStatusText", false));
    }

    private static void platformData(JsonObjectBuilder data) {
        // 由 MetricsBase 通过全局调度器调用；这里绝不会访问玩家实体。
        data.appendField("playerAmount", Bukkit.getOnlinePlayers().size())
                .appendField("onlineMode", Bukkit.getOnlineMode() ? 1 : 0)
                .appendField("bukkitVersion", Bukkit.getVersion())
                .appendField("bukkitName", Bukkit.getName())
                .appendField("javaVersion", System.getProperty("java.version"))
                .appendField("osName", System.getProperty("os.name"))
                .appendField("osArch", System.getProperty("os.arch"))
                .appendField("osVersion", System.getProperty("os.version"))
                .appendField("coreCount", Runtime.getRuntime().availableProcessors());
    }

    static void serviceData(JsonObjectBuilder data, String version) {
        data.appendField("pluginVersion", version);
    }

    void shutdown() { this.base.shutdown(); }
}
