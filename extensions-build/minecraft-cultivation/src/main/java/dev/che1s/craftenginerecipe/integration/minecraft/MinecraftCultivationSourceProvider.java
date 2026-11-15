package dev.che1s.craftenginerecipe.integration.minecraft;

import dev.che1s.craftenginerecipe.api.extension.RecipeSource;
import dev.che1s.craftenginerecipe.api.extension.RecipeSourceProvider;
import dev.che1s.craftenginerecipe.api.extension.SourceContext;

/** Core-default extension for vanilla cultivation data. */
public final class MinecraftCultivationSourceProvider implements RecipeSourceProvider {
    @Override
    public String id() {
        return MinecraftCultivationSource.SOURCE_ID;
    }

    @Override
    public String displayName() {
        return "Minecraft cultivation";
    }

    @Override
    public String defaultMode() {
        return "enabled";
    }

    @Override
    public String outputDirectory() {
        return "minecraft-cultivation";
    }

    @Override
    public RecipeSource create(SourceContext context) {
        return new MinecraftCultivationSource(context);
    }
}
