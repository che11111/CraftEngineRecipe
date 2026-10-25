package dev.che1s.craftenginerecipe.api;

import java.util.List;

/** 同一项中的所有候选互为替代；不同项则是彼此独立的需求。 */
public record RecipeIngredient(List<RecipeItem> choices, int amount, int slot, Role role) {
    public enum Role { INGREDIENT, TOOL, CONTAINER, FUEL, INFO }

    public RecipeIngredient {
        choices = choices == null ? List.of() : List.copyOf(choices);
        RecipeValidation.amount(amount);
        RecipeValidation.slot(slot, 54, "ingredient slot");
        if (role == null) role = Role.INGREDIENT;
    }
}
