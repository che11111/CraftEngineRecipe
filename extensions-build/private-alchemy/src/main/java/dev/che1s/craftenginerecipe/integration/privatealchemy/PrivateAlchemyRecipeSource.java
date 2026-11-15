package dev.che1s.craftenginerecipe.integration.privatealchemy;

import dev.che1s.craftenginerecipe.api.DisplayRecipe;
import dev.che1s.craftenginerecipe.api.extension.RecipeSource;

import java.util.List;

/** Template source used by the example private-alchemy extension. */
final class PrivateAlchemyRecipeSource implements RecipeSource {
    @Override
    public String id() {
        return "private_alchemy";
    }

    @Override
    public boolean ready() {
        return false;
    }

    @Override
    public long revision() {
        return 0L;
    }

    @Override
    public List<DisplayRecipe> capture() {
        return List.of();
    }
}
