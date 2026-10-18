package dev.leon.cer.data;

import net.momirealms.craftengine.core.plugin.gui.category.Category;
import net.momirealms.craftengine.core.util.Key;
import java.util.ArrayDeque;
import java.util.Collection;
import java.util.HashSet;
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
        Set<Key> items = new HashSet<>();
        Set<Key> visited = new HashSet<>();
        ArrayDeque<Category> queue = new ArrayDeque<>(roots);
        while (!queue.isEmpty()) {
            Category category = queue.remove();
            if (!visited.add(category.id()) || !permitted.test(category)) continue;
            if (category.members() == null) continue;
            Set<String> hidden = hiddenItems.apply(category);
            for (String member : category.members()) {
                if (member == null || member.isBlank()) continue;
                try {
                    if (member.charAt(0) == '#') {
                        Category child = categories.get(Key.of(member.substring(1)));
                        if (child != null) queue.add(child);
                    } else {
                        Key item = Key.of(member);
                        if (!isHidden(hidden, item)) items.add(item);
                    }
                } catch (IllegalArgumentException ignored) {
                    // A malformed member must not make the entire search unusable.
                }
            }
        }
        return Set.copyOf(items);
    }
}
