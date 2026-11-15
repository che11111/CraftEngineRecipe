package dev.che1s.craftenginerecipe.integration.craftengine;

import dev.che1s.craftenginerecipe.api.DisplayRecipe;
import dev.che1s.craftenginerecipe.api.extension.RecipeSource;
import dev.che1s.craftenginerecipe.api.extension.SourceContext;
import dev.che1s.craftenginerecipe.api.extension.CultivationRecipeConverter;

import java.util.ArrayList;
import java.util.List;

/** Independent source for cultivation metadata found in CraftEngine packs. */
public final class CraftEngineCultivationSource implements RecipeSource {
    public static final String SOURCE_ID = "craftengine_cultivation";
    private final SourceContext context;

    public CraftEngineCultivationSource(SourceContext context) {
        this.context = context;
    }

    @Override
    public String id() {
        return SOURCE_ID;
    }

    @Override
    public String outputDirectory() {
        return "craftengine-cultivation";
    }

    @Override
    public boolean ready() {
        return this.context.craftEngineDataReady();
    }

    @Override
    public long revision() {
        return this.context.craftEngineDataGeneration() ^ this.context.translationRevision();
    }

    @Override
    public List<DisplayRecipe> capture() {
        List<DisplayRecipe> recipes = new ArrayList<>();
        CultivationDataExporter.captureCraftEngineNamespaces(this.context)
                .forEach((namespace, root) -> recipes.addAll(
                        CultivationRecipeConverter.convert(root, namespace, this.context)));
        return List.copyOf(recipes);
    }
}
