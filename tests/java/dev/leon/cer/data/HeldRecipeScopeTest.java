package dev.leon.cer.data;

import dev.leon.cer.gui.GuiSession;
import dev.leon.cer.i18n.LanguageManager;
import dev.leon.cer.util.HeldRecipeScope;
import net.momirealms.craftengine.core.plugin.gui.category.Category;
import net.momirealms.craftengine.core.util.Key;
import org.bukkit.entity.Player;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.lang.reflect.Proxy;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.logging.Logger;

import static org.junit.jupiter.api.Assertions.*;

class HeldRecipeScopeTest {
    @TempDir Path folder;

    private CategoryNamespaces namespaces(String applied) throws Exception {
        Files.writeString(folder.resolve("categories.yml"), applied);
        Logger logger = Logger.getLogger("HeldRecipeScopeTest");
        LanguageManager language = new LanguageManager(folder,
                path -> getClass().getClassLoader().getResourceAsStream(path), logger);
        CategoryNamespaces namespaces = new CategoryNamespaces(folder.toFile(), language, logger);
        namespaces.load();
        namespaces.sync(List.of("public", "private", "preview"));
        return namespaces;
    }

    private static Category category(String id, String... members) {
        return new Category(Key.of(id), id, List.of(), Key.of("minecraft:chest"), List.of(members),
                0, false, context -> true);
    }

    private static Player player(boolean admin, String... permissions) {
        Set<String> granted = Set.of(permissions);
        UUID uuid = UUID.randomUUID();
        return (Player) Proxy.newProxyInstance(Player.class.getClassLoader(), new Class<?>[]{Player.class},
                (proxy, method, args) -> switch (method.getName()) {
                    case "isOp" -> admin;
                    case "hasPermission" -> granted.contains(args[0]);
                    case "getUniqueId" -> uuid;
                    case "hashCode" -> uuid.hashCode();
                    case "equals" -> proxy == args[0];
                    case "toString" -> uuid.toString();
                    default -> throw new UnsupportedOperationException(method.getName());
                });
    }

    @Test
    void previousAdminPreviewCannotExposeUnappliedItems() throws Exception {
        CategoryNamespaces namespaces = namespaces("""
                entries:
                  - id: public
                    visibility: player
                """);
        Category applied = category("public:root", "public:apple");
        Category preview = category("preview:root", "preview:secret");
        Player admin = player(true);
        GuiSession.of(admin).unapplied(true);
        try {
            assertTrue(namespaces.canView(admin, "preview")); // Admin preview itself is allowed.
            assertEquals(Set.of(Key.of("public:apple")), HeldRecipeScope.visibleItems(List.of(applied, preview),
                    Map.of(applied.id(), applied, preview.id(), preview), List.of(), namespaces, admin));
            assertTrue(GuiSession.of(admin).unapplied()); // Looking up access does not mutate the existing menu.
        } finally {
            GuiSession.clear(admin);
        }
    }

    @Test
    void permissionsAndHiddenCategoryPathsAreEnforced() throws Exception {
        CategoryNamespaces namespaces = namespaces("""
                entries:
                  - id: public
                    visibility: player
                    hidden-items:
                      - category: public:root
                        items: [public:hidden_item]
                    hidden-categories: [public:hidden_category]
                  - id: private
                    visibility: permission
                    permission: recipes.private
                """);
        Category root = category("public:root", "public:apple", "public:hidden_item", "#private:child", "#public:hidden_category");
        Category child = category("private:child", "private:secret");
        Category hidden = category("public:hidden_category", "public:hidden_child");
        Map<Key, Category> all = Map.of(root.id(), root, child.id(), child, hidden.id(), hidden);
        assertEquals(Set.of(Key.of("public:apple")),
                HeldRecipeScope.visibleItems(List.of(root), all, List.of(), namespaces, player(false)));
        assertEquals(Set.of(Key.of("public:apple"), Key.of("private:secret")),
                HeldRecipeScope.visibleItems(List.of(root), all, List.of(), namespaces, player(false, "recipes.private")));
    }

    @Test
    void noCategoryFallbackStillRequiresAppliedNamespaceAndPermission() throws Exception {
        CategoryNamespaces namespaces = namespaces("""
                entries:
                  - id: public
                    visibility: player
                  - id: private
                    visibility: permission
                    permission: recipes.private
                """);
        List<Key> items = List.of(Key.of("public:apple"), Key.of("private:secret"), Key.of("preview:secret"));
        assertEquals(Set.of(Key.of("public:apple")),
                HeldRecipeScope.visibleItems(List.of(), Map.of(), items, namespaces, player(false)));
        assertEquals(Set.of(Key.of("public:apple"), Key.of("private:secret")),
                HeldRecipeScope.visibleItems(List.of(), Map.of(), items, namespaces, player(true)));
    }

    @Test
    void vanillaMembersFollowTheSameHiddenItemRules() throws Exception {
        CategoryNamespaces namespaces = namespaces("""
                entries:
                  - id: public
                    visibility: player
                    hidden-items:
                      - category: public:root
                        items: [minecraft:barrier]
                """);
        Category root = category("public:root", "minecraft:stick", "minecraft:barrier");
        assertEquals(Set.of(Key.of("minecraft:stick")), HeldRecipeScope.visibleItems(List.of(root),
                Map.of(root.id(), root), List.of(), namespaces, player(false)));
    }
}
