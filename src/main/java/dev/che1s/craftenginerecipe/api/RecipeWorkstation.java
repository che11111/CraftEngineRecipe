package dev.che1s.craftenginerecipe.api;

import java.util.Objects;

public record RecipeWorkstation(String id, RecipeItem icon, String name) {
    public RecipeWorkstation {
        id = RecipeValidation.identifier(id, "workstation id");
        Objects.requireNonNull(icon, "workstation icon");
    }
}
