package dev.che1s.craftenginerecipe.api;

import org.bukkit.entity.Player;

import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.function.Predicate;

/** 配方书条目。注册它只会产生展示，不会注册可执行的合成行为。 */
public record DisplayRecipe(String id, RecipeWorkstation workstation, RecipeLayout layout,
                            List<RecipeIngredient> ingredients, List<RecipeResult> results,
                            List<String> description, String permission, Predicate<Player> visibility) {
    public DisplayRecipe {
        id = RecipeValidation.identifier(id, "recipe id");
        Objects.requireNonNull(workstation, "workstation");
        layout = layout == null ? RecipeLayout.defaults() : layout;
        ingredients = ingredients == null ? List.of() : List.copyOf(ingredients);
        results = results == null ? List.of() : List.copyOf(results);
        description = description == null ? List.of() : List.copyOf(description);
        permission = permission == null || permission.isBlank() ? null : permission;
        Set<Integer> occupied = new HashSet<>();
        for (int slot : new int[]{ layout.workstationSlot(), layout.informationSlot(), layout.previousSlot(), layout.backSlot(), layout.nextSlot() }) {
            if (slot >= 0) occupied.add(slot);
        }
        for (RecipeIngredient ingredient : ingredients) validateContentSlot(ingredient.slot(), layout, occupied, "ingredient slot");
        for (RecipeResult result : results) validateContentSlot(result.slot(), layout, occupied, "result slot");
    }

    private static void validateContentSlot(int slot, RecipeLayout layout, Set<Integer> occupied, String label) {
        RecipeValidation.slot(slot, layout.rows() * 9, label);
        if (slot >= 0 && !occupied.add(slot)) throw new IllegalArgumentException(label + " overlaps another item or layout control at " + slot);
    }

    /** 在查看者所属的实体调度器上调用。来源插件提供的判断函数必须遵守该调度器的线程规则。 */
    public boolean canView(Player player) {
        return player != null && (this.permission == null || player.hasPermission(this.permission))
                && (this.visibility == null || this.visibility.test(player));
    }
}
