package dev.che1s.craftenginerecipe.recipe.io;

import java.util.List;
import java.util.Set;

/** Shared recipe-document keys and slot rules used by parsing and validation. */
final class RecipeDocumentSchema {
    static final List<Integer> KALEIDOSCOPE_INGREDIENT_SLOTS =
            List.of(5, 6, 7, 8, 14, 15, 16, 17, 23, 24, 25, 26);
    static final List<Integer> KALEIDOSCOPE_RESULT_SLOTS =
            List.of(41, 42, 43, 44, 50, 51, 52, 53);
    static final Set<String> LAYOUT_KEYS = Set.of("template", "type", "rows", "title", "workstation-slot",
            "information-slot", "previous-slot", "back-slot", "next-slot");
    static final Set<String> ITEM_KEYS = Set.of("provider", "id", "name", "lore");
    static final Set<String> INGREDIENT_KEYS =
            Set.of("provider", "id", "name", "lore", "item", "choices", "amount", "slot", "role");
    static final Set<String> RESULT_KEYS =
            Set.of("provider", "id", "name", "lore", "item", "amount", "slot", "note");

    private RecipeDocumentSchema() {
    }
}
