package dev.che1s.craftenginerecipe.data;

import net.momirealms.craftengine.core.plugin.gui.category.Category;
import net.momirealms.craftengine.core.util.Key;
import java.util.Collection;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;
import java.util.function.Predicate;

/** Collects the search scope through permitted paths, including cross-namespace children. */
public final class CategoryItems {
    private CategoryItems() {}

    /** A standalone '*' hides direct item members; child category references are unaffected. */
    public static boolean isHidden(Set<String> hidden, Key item) {
        return hidden != null && (hidden.contains("*") || hidden.contains(item.toString()));
    }

    public static Set<Key> visible(Collection<Category> roots, Map<Key, Category> categories,
                                   Predicate<Category> permitted, Function<Category, Set<String>> hiddenItems) {
        return CategoryGraph.compile(categories).visible(roots, permitted, hiddenItems);
    }
}
