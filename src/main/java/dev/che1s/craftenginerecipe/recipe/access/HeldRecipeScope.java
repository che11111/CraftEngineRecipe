package dev.che1s.craftenginerecipe.recipe.access;

import dev.che1s.craftenginerecipe.category.CategoryGraph;
import dev.che1s.craftenginerecipe.category.CategoryNamespaces;
import net.momirealms.craftengine.core.plugin.gui.category.Category;
import net.momirealms.craftengine.core.util.Key;
import org.bukkit.entity.Player;

import java.util.Collection;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

/** 直接配方查询沿用已应用分类规则，且不受上次访问菜单影响。 */
public final class HeldRecipeScope {
    private HeldRecipeScope() {}

    /** 未分类的原版物品仍可查询，但不会因此开放受限自定义物品的访问权限。 */
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
        return visibleItems(topCategories, categories, graph, loadedItems, namespaces, player, false);
    }

    /** 只有显式启用该行为的命名空间，其隐藏规则才会影响合成器页面。 */
    public static Set<Key> visibleItemsForWorkstations(Collection<Category> topCategories, Map<Key, Category> categories,
                                                       CategoryGraph graph, Collection<Key> loadedItems,
                                                       CategoryNamespaces namespaces, Player player) {
        return visibleItems(topCategories, categories, graph, loadedItems, namespaces, player, true);
    }

    private static Set<Key> visibleItems(Collection<Category> topCategories, Map<Key, Category> categories,
                                         CategoryGraph graph, Collection<Key> loadedItems,
                                         CategoryNamespaces namespaces, Player player, boolean workstations) {
        Set<String> applied = new HashSet<>();
        Set<String> recipeOnly = new HashSet<>();
        for (CategoryNamespaces.Entry entry : namespaces.entries()) {
            if (entry instanceof CategoryNamespaces.NamespaceEntry namespace) {
                applied.add(namespace.id());
                if (!namespace.categoryEntry()) recipeOnly.add(namespace.id());
            }
        }
        if (categories.isEmpty()) {
            Set<Key> visible = new HashSet<>();
            for (Key item : loadedItems) {
                if (applied.contains(item.namespace()) && namespaces.canView(player, item.namespace())) visible.add(item);
            }
            return Set.copyOf(visible);
        }
        var roots = topCategories.stream().filter(category -> applied.contains(category.id().namespace())).toList();
        Set<Key> visible = graph.visible(roots,
                category -> !(workstations
                        ? namespaces.isCategoryHiddenForWorkstations(category.id().toString())
                        : namespaces.isCategoryHidden(category.id().toString()))
                        && namespaces.canView(player, category.id().namespace()),
                category -> workstations
                        ? namespaces.hiddenItemsForWorkstations(category.id().toString())
                        : namespaces.hiddenItems(category.id().toString()));
        if (recipeOnly.isEmpty()) return visible;
        Set<Key> expanded = new HashSet<>(visible);
        for (Key item : loadedItems) {
            if (recipeOnly.contains(item.namespace()) && namespaces.canView(player, item.namespace())) {
                expanded.add(item);
            }
        }
        return Set.copyOf(expanded);
    }
}
