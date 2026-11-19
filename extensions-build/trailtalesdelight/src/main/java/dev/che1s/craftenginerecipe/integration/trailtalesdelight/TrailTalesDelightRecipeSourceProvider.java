package dev.che1s.craftenginerecipe.integration.trailtalesdelight;

import dev.che1s.craftenginerecipe.api.extension.RecipeSource;
import dev.che1s.craftenginerecipe.api.extension.RecipeSourceProvider;
import dev.che1s.craftenginerecipe.api.extension.SourceContext;

/** Extension entry point for TrailTalesDelight's dedicated pottery cooking pot recipes. */
public final class TrailTalesDelightRecipeSourceProvider implements RecipeSourceProvider {
    @Override
    public String id() {
        return "trailandtalesdelight";
    }

    @Override
    public String displayName() {
        return "Trail & Tales Delight";
    }

    @Override
    public String requiredPluginName() {
        return TrailTalesDelightRecipeSource.PLUGIN_NAME;
    }

    @Override
    public String requiredPluginMainClass() {
        return TrailTalesDelightRecipeSource.PLUGIN_MAIN;
    }

    @Override
    public String defaultMode() {
        return "enabled";
    }

    @Override
    public RecipeSource create(SourceContext context) {
        return new TrailTalesDelightRecipeSource(context);
    }
}
