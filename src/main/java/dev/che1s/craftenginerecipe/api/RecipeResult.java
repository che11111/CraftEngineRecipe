package dev.che1s.craftenginerecipe.api;

import net.kyori.adventure.text.Component;
import org.bukkit.entity.Player;

import java.util.List;
import java.util.Objects;
import java.util.function.Function;

public record RecipeResult(RecipeItem item, int amount, int slot, String note,
                           Function<Player, List<Component>> viewerLore) {
    public RecipeResult(RecipeItem item, int amount, int slot, String note) {
        this(item, amount, slot, note, null);
    }

    public RecipeResult {
        Objects.requireNonNull(item, "result item");
        RecipeValidation.amount(amount);
        RecipeValidation.slot(slot, 54, "result slot");
    }
}
