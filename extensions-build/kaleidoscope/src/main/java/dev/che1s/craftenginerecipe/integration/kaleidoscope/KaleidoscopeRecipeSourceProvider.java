package dev.che1s.craftenginerecipe.integration.kaleidoscope;

import dev.che1s.craftenginerecipe.api.extension.RecipeSource;
import dev.che1s.craftenginerecipe.api.extension.RecipeSourceProvider;
import dev.che1s.craftenginerecipe.api.extension.SourceContext;
import org.bukkit.plugin.Plugin;

/** Extension entry point for the Kaleidoscope Cookery recipe importer. */
public final class KaleidoscopeRecipeSourceProvider implements RecipeSourceProvider {
    @Override
    public String id() {
        return "kaleidoscope";
    }

    @Override
    public String displayName() {
        return "Kaleidoscope Cookery";
    }

    @Override
    public String requiredPluginName() {
        return KaleidoscopeRecipeSource.PLUGIN_NAME;
    }

    @Override
    public String requiredPluginMainClass() {
        return "net.kaleidoscope.cookery.plugin.KaleidoscopeCookeryPlugin";
    }

    @Override
    public RecipeSource create(SourceContext context) {
        Plugin source = context.sourcePlugin();
        if (source == null) throw new IllegalStateException("KaleidoscopeCookeryPlugin is unavailable");
        return new KaleidoscopeRecipeSource(source, context.text(id()));
    }
}
