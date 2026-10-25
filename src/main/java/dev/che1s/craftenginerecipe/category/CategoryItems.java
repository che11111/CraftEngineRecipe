package dev.che1s.craftenginerecipe.category;

import net.momirealms.craftengine.core.plugin.gui.category.Category;
import net.momirealms.craftengine.core.util.Key;
import java.util.Collection;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;
import java.util.function.Predicate;

/** 通过允许访问的路径收集搜索范围，包括跨命名空间的子分类。 */
public final class CategoryItems {
    private CategoryItems() {}

    /** 单独的 '*' 会隐藏直接物品成员，不影响子分类引用。 */
    public static boolean isHidden(Set<String> hidden, Key item) {
        return hidden != null && (hidden.contains("*") || hidden.contains(item.toString()));
    }

    public static Set<Key> visible(Collection<Category> roots, Map<Key, Category> categories,
                                   Predicate<Category> permitted, Function<Category, Set<String>> hiddenItems) {
        return CategoryGraph.compile(categories).visible(roots, permitted, hiddenItems);
    }
}
