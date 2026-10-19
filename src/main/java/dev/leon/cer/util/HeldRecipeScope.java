package dev.leon.cer.util;

import dev.leon.cer.data.CategoryGraph;
import dev.leon.cer.data.CategoryNamespaces;
import net.momirealms.craftengine.core.plugin.gui.category.Category;
import net.momirealms.craftengine.core.util.Key;
import org.bukkit.entity.Player;

import java.util.Collection;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

/** Direct recipe queries share applied-category rules, independently of the last menu visited. */
public final class HeldRecipeScope {
    private HeldRecipeScope() {}

    /** Uncategorized vanilla items remain queryable without opening access to restricted custom items. */
    public static boolean canQuery(Key id, Collection<Category> topCategories, Map<Key, Category> categories,
                                   CategoryGraph graph, Collection<Key> loadedItems,
                                   CategoryNamespaces namespaces, Player player) {
        if (visibleItems(topCategories, categories, graph, loadedItems, namespaces, player).contains(id)) return true;
        if (!"minecraft".equals(id.namespace()) || loadedItems.contains(id) || graph.hasItemReference(id)) return false;
        if (namespaces.findNamespace("minecraft") == null) return true;
        return namespaces.entries().stream().anyMatch(entry -> entry instanceof CategoryNamespaces.NamespaceEntry namespace
                && namespace.id().equals("minecraft")) && namespaces.canView(player, "minecraft");
    }

    public static Set<Key> visibleItems(Collection<Category> topCategories, Map<Key, Category> categories,
                                        Collection<Key> loadedItems, CategoryNamespaces namespaces, Player player) {
        return visibleItems(topCategories, categories, CategoryGraph.compile(categories), loadedItems, namespaces, player);
    }

    public static Set<Key> visibleItems(Collection<Category> topCategories, Map<Key, Category> categories,
                                        CategoryGraph graph, Collection<Key> loadedItems,
                                        CategoryNamespaces namespaces, Player player) {
        Set<String> applied = new HashSet<>();
        for (CategoryNamespaces.Entry entry : namespaces.entries()) {
            if (entry instanceof CategoryNamespaces.NamespaceEntry namespace) applied.add(namespace.id());
        }
        if (categories.isEmpty()) {
            Set<Key> visible = new HashSet<>();
            for (Key item : loadedItems) {
                if (applied.contains(item.namespace()) && namespaces.canView(player, item.namespace())) visible.add(item);
            }
            return Set.copyOf(visible);
        }
        var roots = topCategories.stream().filter(category -> applied.contains(category.id().namespace())).toList();
        return graph.visible(roots,
                category -> !namespaces.isCategoryHidden(category.id().toString())
                        && namespaces.canView(player, category.id().namespace()),
                category -> namespaces.hiddenItems(category.id().toString()));
    }
}
