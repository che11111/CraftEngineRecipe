package dev.che1s.craftenginerecipe.integration.customcrops;

import dev.che1s.craftenginerecipe.api.extension.RecipeSource;
import dev.che1s.craftenginerecipe.api.extension.RecipeSourceProvider;
import dev.che1s.craftenginerecipe.api.extension.SourceContext;
import org.bukkit.plugin.Plugin;

/** Extension entry point for the CustomCrops recipe importer. */
public final class CustomCropsRecipeSourceProvider implements RecipeSourceProvider {
    @Override
    public String id() {
        return CustomCropsRecipeSource.SOURCE_ID;
    }

    @Override
    public String displayName() {
        return "CustomCrops";
    }

    @Override
    public String requiredPluginName() {
        return CustomCropsRecipeSource.PLUGIN_NAME;
    }

    @Override
    public String requiredPluginMainClass() {
        return "net.momirealms.customcrops.bukkit.BukkitBootstrap";
    }

    @Override
    public RecipeSource create(SourceContext context) {
        Plugin source = context.sourcePlugin();
        if (source == null) throw new IllegalStateException("CustomCrops is unavailable");
        return new CustomCropsRecipeSource(source, context.resourcePackTranslation(),
                context.itemDisplayName(), context::chinese, context.text(id()));
    }
}
