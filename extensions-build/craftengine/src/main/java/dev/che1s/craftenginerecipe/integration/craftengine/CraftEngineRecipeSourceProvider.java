package dev.che1s.craftenginerecipe.integration.craftengine;

import dev.che1s.craftenginerecipe.api.extension.RecipeSource;
import dev.che1s.craftenginerecipe.api.extension.RecipeSourceProvider;
import dev.che1s.craftenginerecipe.api.extension.SourceContext;

/** Core-default extension for CraftEngine's registered recipes. */
public final class CraftEngineRecipeSourceProvider implements RecipeSourceProvider {
    @Override
    public String id() {
        return CraftEngineRecipeSource.SOURCE_ID;
    }

    @Override
    public String displayName() {
        return "CraftEngine recipes";
    }

    @Override
    public String requiredPluginName() {
        return "CraftEngine";
    }

    @Override
    public String requiredPluginMainClass() {
        return "net.momirealms.craftengine.bukkit.plugin.PaperCraftEnginePlugin,"
                + "net.momirealms.craftengine.bukkit.plugin.BukkitCraftEnginePlugin";
    }

    @Override
    public String defaultMode() {
        return "enabled";
    }

    @Override
    public RecipeSource create(SourceContext context) {
        return new CraftEngineRecipeSource(context);
    }
}
