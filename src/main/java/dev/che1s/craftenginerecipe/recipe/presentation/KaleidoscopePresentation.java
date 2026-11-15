package dev.che1s.craftenginerecipe.recipe.presentation;

import dev.che1s.craftenginerecipe.api.RecipeIngredient;
import dev.che1s.craftenginerecipe.api.RecipeItem;

/** Core rendering rules shared by the optional Kaleidoscope source extension. */
public final class KaleidoscopePresentation {
    public static final String INFORMATION_PROVIDER = "cer";
    public static final String INFORMATION_ITEM_MODEL = "cer_kc_gui:note";
    public static final int INFORMATION_SLOT_LEFT = 33;
    public static final int INFORMATION_SLOT_RIGHT = 34;

    private KaleidoscopePresentation() {
    }

    public static boolean isInformationIngredient(String recipeId, RecipeIngredient ingredient) {
        if (recipeId == null || !recipeId.startsWith("kc:")
                || ingredient == null || ingredient.role() != RecipeIngredient.Role.INFO
                || ingredient.slot() != INFORMATION_SLOT_LEFT
                && ingredient.slot() != INFORMATION_SLOT_RIGHT
                || ingredient.choices().size() != 1) return false;
        RecipeItem item = ingredient.choices().getFirst();
        return item.provider().equals(INFORMATION_PROVIDER) && item.id().equals("minecraft:paper");
    }

    public static String itemNoteKey(String recipeId, RecipeIngredient.Role role) {
        if (recipeId == null || !recipeId.startsWith("kc:")) return null;
        return switch (role) {
            case CONTAINER -> "flex.container";
            case TOOL -> recipeId.startsWith("kc:chopping/") ? "chopping.knife_note" : null;
            case INFO -> {
                if (recipeId.startsWith("kc:flex/")) yield "flex.liquid_item";
                if (recipeId.startsWith("kc:teapot/")) yield "teapot.liquid";
                yield null;
            }
            default -> null;
        };
    }
}
