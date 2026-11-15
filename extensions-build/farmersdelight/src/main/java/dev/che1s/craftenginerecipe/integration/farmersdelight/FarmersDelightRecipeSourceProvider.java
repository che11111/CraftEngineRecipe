package dev.che1s.craftenginerecipe.integration.farmersdelight;

import dev.che1s.craftenginerecipe.api.extension.RecipeSource;
import dev.che1s.craftenginerecipe.api.extension.RecipeSourceProvider;
import dev.che1s.craftenginerecipe.api.extension.SourceContext;

/** Extension entry point for the Farmer's Delight recipe importer. */
public final class FarmersDelightRecipeSourceProvider implements RecipeSourceProvider {
    @Override
    public String id() {
        return "farmersdelight";
    }

    @Override
    public String displayName() {
        return "Farmer's Delight";
    }

    @Override
    public String requiredPluginName() {
        return FarmersDelightRecipeSource.PLUGIN_NAME;
    }

    @Override
    public String requiredPluginMainClass() {
        return "com.huidu.farmersdelight.FarmersDelightPlugin";
    }

    @Override
    public RecipeSource create(SourceContext context) {
        return new FarmersDelightRecipeSource(context);
    }
}
