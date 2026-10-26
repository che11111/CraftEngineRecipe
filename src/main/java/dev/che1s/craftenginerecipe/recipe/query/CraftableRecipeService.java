package dev.che1s.craftenginerecipe.recipe.query;

import dev.che1s.craftenginerecipe.api.RecipeIngredient;
import dev.che1s.craftenginerecipe.recipe.ExternalRecipeSnapshot;
import dev.che1s.craftenginerecipe.recipe.RecipeEntry;
import net.momirealms.craftengine.core.util.Key;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;
import java.util.function.Predicate;

/** 根据玩家当前背包计算至少有一条完整配方可满足的产物。 */
public final class CraftableRecipeService {
    private CraftableRecipeService() {
    }

    public static List<Key> find(Player player, ExternalRecipeSnapshot snapshot,
                                 Predicate<RecipeEntry> visible,
                                 Function<ItemStack, Key> inventoryKey) {
        return find(snapshot, inventoryCounts(player, inventoryKey), visible);
    }

    static List<Key> find(ExternalRecipeSnapshot snapshot, Map<Key, Integer> available,
                          Predicate<RecipeEntry> visible) {
        Set<Key> results = new LinkedHashSet<>();
        for (RecipeEntry entry : snapshot.entries()) {
            if (entry.display() == null || !visible.test(entry)) continue;
            if (!canCraft(entry.display().ingredients(), available)) continue;
            for (var result : entry.display().results()) {
                results.add(ExternalRecipeSnapshot.key(result.item()));
            }
        }
        return List.copyOf(results);
    }

    private static Map<Key, Integer> inventoryCounts(Player player, Function<ItemStack, Key> inventoryKey) {
        Map<Key, Integer> counts = new HashMap<>();
        List<ItemStack> stacks = new ArrayList<>();
        for (ItemStack stack : player.getInventory().getStorageContents()) {
            if (stack != null) stacks.add(stack);
        }
        ItemStack offhand = player.getInventory().getItemInOffHand();
        if (offhand != null) stacks.add(offhand);
        for (ItemStack stack : stacks) {
            if (stack == null || stack.getType().isAir() || stack.getAmount() <= 0) continue;
            Key key = inventoryKey.apply(stack);
            if (key != null) counts.merge(key, stack.getAmount(), Integer::sum);
        }
        return counts;
    }

    private static boolean canCraft(List<RecipeIngredient> ingredients, Map<Key, Integer> available) {
        List<RecipeIngredient> required = ingredients.stream()
                .filter(ingredient -> ingredient.role() != RecipeIngredient.Role.INFO)
                .toList();
        if (required.isEmpty()) return false;
        return satisfy(required, 0, available);
    }

    private static boolean satisfy(List<RecipeIngredient> ingredients, int index, Map<Key, Integer> available) {
        if (index >= ingredients.size()) return true;
        RecipeIngredient ingredient = ingredients.get(index);
        Set<Key> choices = new LinkedHashSet<>();
        for (var choice : ingredient.choices()) {
            choices.add(ExternalRecipeSnapshot.key(choice));
        }
        for (Key choice : choices) {
            int count = available.getOrDefault(choice, 0);
            if (count < ingredient.amount()) continue;
            // 同一个材料项允许多种替代品；使用副本回溯，避免占用后续材料需要的物品。
            Map<Key, Integer> remaining = new HashMap<>(available);
            remaining.put(choice, count - ingredient.amount());
            if (satisfy(ingredients, index + 1, remaining)) return true;
        }
        return false;
    }
}
