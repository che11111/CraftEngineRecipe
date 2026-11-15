package dev.che1s.craftenginerecipe.integration.minecraft;

import dev.che1s.craftenginerecipe.api.extension.RecipeSource;
import dev.che1s.craftenginerecipe.api.extension.RecipeSourceProvider;
import dev.che1s.craftenginerecipe.api.extension.SourceContext;
import org.bukkit.Bukkit;

/** Core-default extension for vanilla recipe data. */
public final class MinecraftRecipeSourceProvider implements RecipeSourceProvider {
    @Override
    public String id() {
        return MinecraftRecipeSource.SOURCE_ID;
    }

    @Override
    public String displayName() {
        return "Minecraft recipes";
    }

    @Override
    public String defaultMode() {
        return "enabled";
    }

    @Override
    public String outputDirectory() {
        String version = Bukkit.getMinecraftVersion();
        return "minecraft-" + (version == null || version.isBlank() ? "unknown" : version);
    }

    @Override
    public RecipeSource create(SourceContext context) {
        return new MinecraftRecipeSource(context);
    }
}
