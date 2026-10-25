package dev.che1s.craftenginerecipe.recipe;

import dev.che1s.craftenginerecipe.api.DisplayRecipe;
import net.momirealms.craftengine.core.item.recipe.Recipe;
import net.momirealms.craftengine.core.util.Key;
import org.bukkit.entity.Player;
import java.util.List;
import java.util.function.BooleanSupplier;

/** 原生 CE 配方或来源插件提供的箱子展示；保留 CE 查询中的成员关系。 */
public record RecipeEntry(String source, Recipe nativeRecipe, DisplayRecipe display, BooleanSupplier active) {
    public static RecipeEntry nativeEntry(Recipe recipe) {
        return new RecipeEntry("craftengine", recipe, null, () -> true);
    }

    public boolean canView(Player player) {
        return this.active.getAsBoolean() && (this.display == null || this.display.canView(player));
    }

    public record Output(Key result, List<RecipeEntry> recipes) {
        public Output { recipes = List.copyOf(recipes); }
    }
}
