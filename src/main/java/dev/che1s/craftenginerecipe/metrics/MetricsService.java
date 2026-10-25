package dev.che1s.craftenginerecipe.metrics;

import dev.che1s.craftenginerecipe.CraftEngineRecipePlugin;
import java.io.IOException;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;


public final class MetricsService {
    // 本项目统计页：https://bstats.org/plugin/bukkit/CraftEngineRecipe/34772
    private static final int CRAFT_ENGINE_RECIPE_SERVICE_ID = 34772;
    // 原项目统计页：https://bstats.org/plugin/bukkit/CEGUI/34637
    private static final int CEGUI_SERVICE_ID = 34637;

    private final CraftEngineRecipePlugin plugin;
    private final List<RegionMetrics> reporters = new ArrayList<>();
    private Set<Integer> activeIds = Set.of();

    public MetricsService(CraftEngineRecipePlugin plugin) {
        this.plugin = plugin;
    }

    public void reload() {
        Set<Integer> ids = this.plugin.getConfig().getBoolean("bstats.enabled", true)
                ? builtInServiceIds()
                : Set.of();
        if (ids.equals(this.activeIds)) return;
        this.shutdown();
        try {
            for (int id : ids) {
                this.reporters.add(new RegionMetrics(this.plugin, id));
            }
            this.activeIds = ids;
            if (!ids.isEmpty()) this.plugin.getLogger().info("bStats 已初始化。");
        } catch (RuntimeException | IOException e) {
            this.shutdown();
            this.plugin.getLogger().warning(this.plugin.lang().text("logs.metrics-failed", "error", e.getMessage()));
        }
    }

    static Set<Integer> builtInServiceIds() {
        return serviceIds(CRAFT_ENGINE_RECIPE_SERVICE_ID, CEGUI_SERVICE_ID);
    }

    public static Set<Integer> serviceIds(int primary, int secondary) {
        Set<Integer> ids = new LinkedHashSet<>();
        if (primary > 0) ids.add(primary);
        if (secondary > 0) ids.add(secondary);
        return java.util.Collections.unmodifiableSet(ids);
    }

    public void shutdown() {
        this.reporters.forEach(RegionMetrics::shutdown);
        this.reporters.clear();
        this.activeIds = Set.of();
    }
}
