package dev.che1s.craftenginerecipe.metrics;

import dev.che1s.craftenginerecipe.CraftEngineRecipePlugin;
import java.io.IOException;

public final class MetricsService {
    // 本项目统计页：https://bstats.org/plugin/bukkit/CraftEngineRecipe/34772
    private static final int CRAFT_ENGINE_RECIPE_SERVICE_ID = 34772;

    private final CraftEngineRecipePlugin plugin;
    private FoliaCompatibleMetrics reporter;

    public MetricsService(CraftEngineRecipePlugin plugin) {
        this.plugin = plugin;
    }

    public void reload() {
        boolean enabled = this.plugin.getConfig().getBoolean("bstats.enabled", true);
        if (enabled == (this.reporter != null)) return;
        this.shutdown();
        if (!enabled) return;
        try {
            this.reporter = new FoliaCompatibleMetrics(
                    this.plugin, CRAFT_ENGINE_RECIPE_SERVICE_ID);
            this.plugin.getLogger().info("bStats 已初始化。");
        } catch (RuntimeException | IOException e) {
            this.shutdown();
            this.plugin.getLogger().warning(this.plugin.lang().text("logs.metrics-failed", "error", e.getMessage()));
        }
    }

    public void shutdown() {
        if (this.reporter != null) this.reporter.shutdown();
        this.reporter = null;
    }
}
