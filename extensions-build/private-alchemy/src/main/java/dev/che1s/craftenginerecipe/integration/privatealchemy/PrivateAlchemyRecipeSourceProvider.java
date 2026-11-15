package dev.che1s.craftenginerecipe.integration.privatealchemy;

import dev.che1s.craftenginerecipe.api.extension.RecipeSource;
import dev.che1s.craftenginerecipe.api.extension.RecipeSourceProvider;
import dev.che1s.craftenginerecipe.api.extension.SourceContext;

/**
 * Minimal private-source extension template.
 *
 * <p>It remains dormant unless the deliberately private plugin name and main class below are
 * present. Replace this provider and source with the real private data adapter.</p>
 */
public final class PrivateAlchemyRecipeSourceProvider implements RecipeSourceProvider {
    @Override
    public String id() {
        return "private_alchemy";
    }

    @Override
    public String displayName() {
        return "Private Alchemy";
    }

    @Override
    public String requiredPluginName() {
        return "PrivateAlchemy";
    }

    @Override
    public String requiredPluginMainClass() {
        return "com.example.privatealchemy.PrivateAlchemyPlugin";
    }

    @Override
    public RecipeSource create(SourceContext context) {
        return new PrivateAlchemyRecipeSource();
    }
}
