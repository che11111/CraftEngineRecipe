package dev.che1s.craftenginerecipe.recipe.access;

import dev.che1s.craftenginerecipe.recipe.RecipeEntry;
import net.momirealms.craftengine.core.util.Key;

/** 单次玩家操作使用的配方和物品访问策略。 */
public interface RecipeAccessPolicy {
    boolean canView(RecipeEntry entry);

    boolean canViewWorkstation(RecipeEntry entry);

    boolean canQuery(Key item);
}
