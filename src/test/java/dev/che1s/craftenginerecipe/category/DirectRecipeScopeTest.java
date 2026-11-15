package dev.che1s.craftenginerecipe.category;

import dev.che1s.craftenginerecipe.i18n.LanguageManager;
import dev.che1s.craftenginerecipe.recipe.access.HeldRecipeScope;
import net.momirealms.craftengine.core.plugin.gui.category.Category;
import net.momirealms.craftengine.core.util.Key;
import org.bukkit.entity.Player;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.lang.reflect.Proxy;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.logging.Logger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class DirectRecipeScopeTest {
    private static final Key DIAMOND = Key.of("minecraft:diamond");
    private static final Key CUSTOM = Key.of("test:custom");
    private static final String PUBLIC_TEST = """
            entries:
              - id: test
                visibility: player
            """;

    @TempDir
    Path dataFolder;

    private LanguageManager language;

    @BeforeEach
    void loadBundledLanguageWithoutAServer() {
        this.language = new LanguageManager(this.dataFolder,
                resource -> DirectRecipeScopeTest.class.getClassLoader().getResourceAsStream(resource),
                Logger.getLogger(DirectRecipeScopeTest.class.getName()));
        this.language.load("zh_CN");
    }

    @Test
    void unclassifiedVanillaItemIsQueryableWithoutAnyCraftEngineCategories() throws IOException {
        Scope scope = this.scope(List.of(), List.of(), "entries: []", "entries: []");

        assertTrue(scope.allows(DIAMOND, Set.of(), player()));
    }

    @Test
    void unrelatedCraftEngineCategoriesDoNotRestrictUnclassifiedVanillaItems() throws IOException {
        Category category = category("test:main", "test:custom");
        Scope scope = this.scope(List.of(category), List.of(category), PUBLIC_TEST, "entries: []");

        assertTrue(scope.allows(DIAMOND, Set.of(CUSTOM), player()));
    }

    @Test
    void unclassifiedForeignIdsAndCraftEngineItemsUsingMinecraftNamespaceAreRejected() throws IOException {
        Scope scope = this.scope(List.of(), List.of(), "entries: []", "entries: []");

        assertFalse(scope.allows(CUSTOM, Set.of(), player()));
        assertFalse(scope.allows(DIAMOND, Set.of(DIAMOND), player()));
    }

    @Test
    void itemAndWildcardHidingCannotBeBypassedByVanillaDirectQueries() throws IOException {
        Category itemHidden = category("test:item_hidden", "minecraft:diamond");
        Category allHidden = category("test:all_hidden", "minecraft:emerald");
        Scope scope = this.scope(List.of(itemHidden, allHidden), List.of(itemHidden, allHidden), """
                entries:
                  - id: test
                    visibility: player
                    hidden-items:
                      - category: test:item_hidden
                        items: ['minecraft:diamond']
                      - category: test:all_hidden
                        items: ['*']
                """, "entries: []");

        assertFalse(scope.allows(DIAMOND, Set.of(), player()));
        assertFalse(scope.allows(Key.of("minecraft:emerald"), Set.of(), player()));
    }

    @Test
    void hiddenAndUnreachableCategoryMembersCannotUseTheVanillaFallback() throws IOException {
        Category hidden = category("test:hidden", "minecraft:diamond");
        Category unreachable = category("test:unreachable", "minecraft:emerald");
        Scope scope = this.scope(List.of(hidden), List.of(hidden, unreachable), """
                entries:
                  - id: test
                    visibility: player
                    hidden-categories: ['test:hidden']
                """, "entries: []");

        assertFalse(scope.allows(DIAMOND, Set.of(), player()));
        assertFalse(scope.allows(Key.of("minecraft:emerald"), Set.of(), player()));
    }

    @Test
    void categoryPermissionStillControlsReferencedVanillaItems() throws IOException {
        Category category = category("test:main", "minecraft:diamond");
        Scope scope = this.scope(List.of(category), List.of(category), """
                entries:
                  - id: test
                    visibility: permission
                    permission: test.private
                """, "entries: []");

        assertFalse(scope.allows(DIAMOND, Set.of(), player()));
        assertTrue(scope.allows(DIAMOND, Set.of(), player("test.private")));
    }

    @Test
    void unappliedCategoryMembersRemainUnavailableEvenWhenTheirNamespaceIsPublic() throws IOException {
        Category category = category("test:main", "minecraft:diamond");
        Scope scope = this.scope(List.of(category), List.of(category), "entries: []", PUBLIC_TEST);

        assertFalse(scope.allows(DIAMOND, Set.of(), player()));
    }

    @Test
    void anotherVisibleCategoryPathStillAllowsAnItemHiddenOnOnePath() throws IOException {
        Category hidden = category("test:hidden", "minecraft:diamond");
        Category visible = category("test:visible", "minecraft:diamond");
        Scope scope = this.scope(List.of(hidden, visible), List.of(hidden, visible), """
                entries:
                  - id: test
                    visibility: player
                    hidden-items:
                      - category: test:hidden
                        items: ['*']
                """, "entries: []");

        assertTrue(scope.allows(DIAMOND, Set.of(), player()));
    }

    @Test
    void hiddenRulesAffectWorkstationPagesByDefault() throws IOException {
        Scope scope = this.scope(List.of(), List.of(), """
                entries:
                  - id: test
                    visibility: player
                    hidden-items:
                      - category: test:items
                        items: ['minecraft:diamond']
                    hidden-categories: ['test:hidden']
                """, "entries: []");

        assertEquals(Set.of("minecraft:diamond"), scope.namespaces().hiddenItems("test:items"));
        assertTrue(scope.namespaces().isCategoryHidden("test:hidden"));
        assertEquals(Set.of("minecraft:diamond"), scope.namespaces().hiddenItemsForWorkstations("test:items"));
        assertTrue(scope.namespaces().isCategoryHiddenForWorkstations("test:hidden"));
    }

    @Test
    void hiddenRulesCanOptOutOfAffectingWorkstationPages() throws IOException {
        Scope scope = this.scope(List.of(), List.of(), """
                entries:
                  - id: test
                    visibility: player
                    hidden-rules-affect-workstations: false
                    hidden-items:
                      - category: test:items
                        items: ['minecraft:diamond']
                    hidden-categories: ['test:hidden']
                """, "entries: []");

        assertEquals(Set.of(), scope.namespaces().hiddenItemsForWorkstations("test:items"));
        assertFalse(scope.namespaces().isCategoryHiddenForWorkstations("test:hidden"));
    }

    @Test
    void explicitlyAppliedMinecraftNamespaceControlsUnclassifiedVanillaQueries() throws IOException {
        Scope scope = this.scope(List.of(), List.of(), """
                entries:
                  - id: minecraft
                    visibility: permission
                    permission: test.vanilla
                """, "entries: []");

        assertFalse(scope.allows(DIAMOND, Set.of(), player()));
        assertTrue(scope.allows(DIAMOND, Set.of(), player("test.vanilla")));
    }

    @Test
    void unappliedMinecraftNamespaceDoesNotPermitUnclassifiedVanillaQueries() throws IOException {
        Scope scope = this.scope(List.of(), List.of(), "entries: []", """
                entries:
                  - id: minecraft
                    visibility: player
                """);

        assertFalse(scope.allows(DIAMOND, Set.of(), player()));
    }

    @Test
    void visibleAppliedCraftEngineItemsRemainQueryable() throws IOException {
        Category category = category("test:main", "test:custom");
        Scope scope = this.scope(List.of(category), List.of(category), PUBLIC_TEST, "entries: []");

        assertTrue(scope.allows(CUSTOM, Set.of(CUSTOM), player()));
    }

    @Test
    void recipeOnlyNamespaceSkipsCategoryEntryButKeepsItemsQueryable() throws IOException {
        Category category = category("test:items", "test:custom");
        Scope scope = this.scope(List.of(), List.of(category), """
                entries:
                  - id: test
                    visibility: player
                    category-entry: false
                """, "entries: []");

        assertFalse(scope.namespaces().findNamespace("test").categoryEntry());
        assertTrue(scope.allows(CUSTOM, Set.of(CUSTOM), player()));
    }

    @Test
    void recipeOnlyNamespaceStillHonorsPermissions() throws IOException {
        Category category = category("test:items", "test:custom");
        Scope scope = this.scope(List.of(), List.of(category), """
                entries:
                  - id: test
                    visibility: permission
                    permission: test.recipe
                    category-entry: false
                """, "entries: []");

        assertFalse(scope.allows(CUSTOM, Set.of(CUSTOM), player()));
        assertTrue(scope.allows(CUSTOM, Set.of(CUSTOM), player("test.recipe")));
    }

    @Test
    void filteredCategoryIdsIncludeOnlyNamespaceScopedFilters() throws IOException {
        Scope scope = this.scope(List.of(), List.of(), """
                entries:
                  - id: test
                    visibility: player
                    hidden-items:
                      - category: test:items
                        items: ['minecraft:diamond']
                    hidden-categories: ['test:hidden']
                hidden-items:
                  - category: legacy:items
                    items: ['*']
                """, """
                entries:
                  - id: pending
                    visibility: player
                    hidden-items:
                      - category: pending:items
                        items: ['*']
                    hidden-categories: ['pending:hidden']
                """);

        Set<String> filtered = scope.namespaces().filteredCategoryIds();

        assertEquals(Set.of("test:items", "test:hidden", "pending:items", "pending:hidden"),
                filtered);
        assertThrows(UnsupportedOperationException.class, filtered::clear);
    }

    @Test
    void administratorCanUpdateAndPersistUnappliedNamespaceFields() throws IOException {
        Path navigation = this.dataFolder.resolve("navigation");
        Files.createDirectories(navigation);
        Files.writeString(navigation.resolve("categories.yml"), "entries: []");
        Files.writeString(navigation.resolve("categories-unapplied.yml"), """
                entries:
                  - id: pending
                    visibility: admin
                    permission: ''
                """);
        CategoryNamespaces namespaces = new CategoryNamespaces(this.dataFolder.toFile(), this.language,
                Logger.getLogger(DirectRecipeScopeTest.class.getName()));
        namespaces.load();
        namespaces.sync(Set.of("pending"));

        assertTrue(namespaces.updateUnapplied("pending", CategoryNamespaces.Visibility.PERMISSION,
                "example.recipe", false, "<white>Pending", true, true, false));
        CategoryNamespaces.NamespaceEntry updated = namespaces.unappliedEntry("pending");
        assertEquals(CategoryNamespaces.Visibility.PERMISSION, updated.visibility());
        assertEquals("example.recipe", updated.permission());
        assertFalse(updated.categoryEntry());
        assertTrue(updated.sortByNamespace());
        assertTrue(updated.hideItemsWithoutRecipes());
        assertFalse(updated.hiddenRulesAffectWorkstations());
        String saved = Files.readString(navigation.resolve("categories-unapplied.yml"));
        assertTrue(saved.contains("visibility: permission"));
        assertTrue(saved.contains("permission: example.recipe"));
        assertTrue(saved.contains("category-entry: false"));
    }

    @Test
    void administratorCanUpdateAppliedNamespaceAndKeepsHiddenRules() throws IOException {
        Path navigation = this.dataFolder.resolve("navigation");
        Files.createDirectories(navigation);
        Files.writeString(navigation.resolve("categories.yml"), """
                entries:
                  - id: test
                    visibility: player
                    hidden-items:
                      - category: test:items
                        items: ['test:secret']
                    hidden-categories: ['test:gui']
                """);
        Files.writeString(navigation.resolve("categories-unapplied.yml"), "entries: []");
        CategoryNamespaces namespaces = new CategoryNamespaces(this.dataFolder.toFile(), this.language,
                Logger.getLogger(DirectRecipeScopeTest.class.getName()));
        namespaces.load();
        CategoryNamespaces.NamespaceEntry current = namespaces.appliedEntry("test");
        CategoryNamespaces.NamespaceEntry updated = new CategoryNamespaces.NamespaceEntry(
                "test", CategoryNamespaces.Visibility.PERMISSION, "test.recipe", false,
                "<white>测试分类", List.of("<gray>物品提示"), List.of("<gray>分类提示"),
                List.of("<gray>子分类提示"), true, true, current.hiddenItems(), current.hiddenCategories(),
                false, true);

        assertTrue(namespaces.updateNamespace("test", updated));
        CategoryNamespaces.NamespaceEntry savedEntry = namespaces.appliedEntry("test");
        assertEquals("test.recipe", savedEntry.permission());
        assertEquals(Set.of("test:secret"), savedEntry.hiddenItems().get("test:items"));
        assertEquals(Set.of("test:gui"), savedEntry.hiddenCategories());
        String saved = Files.readString(navigation.resolve("categories.yml"));
        assertTrue(saved.contains("visibility: permission"));
        assertTrue(saved.contains("item-lore:"));
        assertTrue(saved.contains("hidden-rules-affect-workstations: false"));
        assertTrue(Files.isRegularFile(navigation.resolve("categories.yml.bak")));
    }

    @Test
    void unappliedNamespaceCanBeAppliedAndCategoryEntriesCanBeReordered() throws IOException {
        Path navigation = this.dataFolder.resolve("navigation");
        Files.createDirectories(navigation);
        Files.writeString(navigation.resolve("categories.yml"), """
                entries:
                  - id: first
                    visibility: player
                  - id: second
                    visibility: player
                """);
        Files.writeString(navigation.resolve("categories-unapplied.yml"), """
                entries:
                  - id: pending
                    visibility: admin
                """);
        CategoryNamespaces namespaces = new CategoryNamespaces(this.dataFolder.toFile(), this.language,
                Logger.getLogger(DirectRecipeScopeTest.class.getName()));
        namespaces.load();

        assertTrue(namespaces.applyUnapplied("pending"));
        assertTrue(namespaces.isApplied("pending"));
        assertTrue(namespaces.unappliedEntries().isEmpty());
        assertTrue(namespaces.moveEntry(2, -2));

        assertEquals(List.of("pending", "first", "second"),
                namespaces.orderedNamespaces());
        assertTrue(Files.readString(navigation.resolve("categories.yml")).contains("id: pending"));
        assertFalse(Files.readString(navigation.resolve("categories-unapplied.yml")).contains("id: pending"));
    }

    @Test
    void namespacePresetIsAppliedWithItsPluginSpecificHidingRules() throws IOException {
        Path navigation = this.dataFolder.resolve("navigation");
        Files.createDirectories(navigation);
        Files.writeString(navigation.resolve("categories.yml"), "entries: []");
        Files.writeString(navigation.resolve("categories-unapplied.yml"), """
                entries:
                  - id: farmersdelight
                    visibility: admin
                """);
        CategoryNamespaces namespaces = new CategoryNamespaces(this.dataFolder.toFile(), this.language,
                Logger.getLogger(DirectRecipeScopeTest.class.getName()));
        namespaces.load();
        CategoryNamespaces.NamespaceEntry preset = new CategoryNamespaces.NamespaceEntry(
                "farmersdelight", CategoryNamespaces.Visibility.PLAYER, "", true,
                "", List.of(), List.of(), List.of(), false, false, Map.of(),
                Set.of("farmersdelight:gui", "farmersdelight:item_display"), true, false);

        assertTrue(namespaces.applyPreset(preset));
        assertEquals(preset, namespaces.appliedEntry("farmersdelight"));
        assertTrue(namespaces.unappliedEntries().isEmpty());
        String saved = Files.readString(navigation.resolve("categories.yml"));
        assertTrue(saved.contains("id: farmersdelight"));
        assertTrue(saved.contains("farmersdelight:gui"));
        assertTrue(saved.contains("farmersdelight:item_display"));
    }

    @Test
    void appliedNamespacesCanBeMovedBackWithoutLosingConfiguration() throws IOException {
        Path navigation = this.dataFolder.resolve("navigation");
        Files.createDirectories(navigation);
        Files.writeString(navigation.resolve("categories.yml"), """
                entries:
                  - id: applied
                    visibility: permission
                    permission: test.view
                    category-entry: false
                    icon-name: '<gold>Applied'
                    icon-lore:
                      - '<gray>Custom lore'
                    hidden-rules-affect-workstations: false
                """);
        Files.writeString(navigation.resolve("categories-unapplied.yml"), "entries: []");
        CategoryNamespaces namespaces = new CategoryNamespaces(this.dataFolder.toFile(), this.language,
                Logger.getLogger(DirectRecipeScopeTest.class.getName()));
        namespaces.load();

        assertTrue(namespaces.unapplyEntry(0));
        assertFalse(namespaces.isApplied("applied"));
        CategoryNamespaces.NamespaceEntry moved = namespaces.unappliedEntry("applied");
        assertNotNull(moved);
        assertEquals(CategoryNamespaces.Visibility.PERMISSION, moved.visibility());
        assertEquals("test.view", moved.permission());
        assertFalse(moved.categoryEntry());
        assertEquals("<gold>Applied", moved.iconName().yamlValue());
        assertFalse(moved.hiddenRulesAffectWorkstations());
        assertFalse(Files.readString(navigation.resolve("categories.yml")).contains("id: applied"));
        assertTrue(Files.readString(navigation.resolve("categories-unapplied.yml")).contains("id: applied"));
    }

    @Test
    void appliedCustomButtonsCanBeMovedToUnappliedEntries() throws IOException {
        Path navigation = this.dataFolder.resolve("navigation");
        Files.createDirectories(navigation);
        Files.writeString(navigation.resolve("categories.yml"), """
                entries:
                  - display:
                      material: minecraft:compass
                      name: Navigation
                    visibility: admin
                    actions:
                      all:
                        - 'command: cer manage'
                """);
        Files.writeString(navigation.resolve("categories-unapplied.yml"), "entries: []");
        CategoryNamespaces namespaces = new CategoryNamespaces(this.dataFolder.toFile(), this.language,
                Logger.getLogger(DirectRecipeScopeTest.class.getName()));
        namespaces.load();

        assertTrue(namespaces.unapplyEntry(0));
        assertTrue(namespaces.entries().isEmpty());
        assertTrue(namespaces.unappliedAllEntries().getFirst() instanceof CategoryNamespaces.ButtonEntry button
                && button.display().name().yamlValue().equals("Navigation"));
        String saved = Files.readString(navigation.resolve("categories-unapplied.yml"));
        assertTrue(saved.contains("display:"));
        assertTrue(saved.contains("command: cer manage"));

        assertTrue(namespaces.applyUnappliedEntry(0));
        assertTrue(namespaces.unappliedAllEntries().isEmpty());
        assertTrue(namespaces.entries().getFirst() instanceof CategoryNamespaces.ButtonEntry applied
                && applied.display().name().yamlValue().equals("Navigation"));
    }

    @Test
    void itemAndCategoryVisibilityCanBeToggledInGame() throws IOException {
        Scope scope = this.scope(List.of(), List.of(), """
                entries:
                  - id: test
                    visibility: player
                """, "entries: []");

        assertTrue(scope.namespaces().setItemHidden("test", "test:items", "minecraft:diamond",
                true, List.of("minecraft:diamond", "minecraft:emerald")));
        assertEquals(Set.of("minecraft:diamond"),
                scope.namespaces().hiddenItems("test:items"));
        assertTrue(scope.namespaces().setAllItemsHidden("test", "test:items", true));
        assertTrue(scope.namespaces().hiddenItems("test:items").contains("*"));
        assertTrue(scope.namespaces().setItemHidden("test", "test:items", "minecraft:diamond",
                false, List.of("minecraft:diamond", "minecraft:emerald")));
        assertEquals(Set.of("minecraft:emerald"),
                scope.namespaces().hiddenItems("test:items"));
        assertTrue(scope.namespaces().setCategoryHidden("test", "test:gui", true));
        assertTrue(scope.namespaces().isCategoryHidden("test:gui"));
        assertTrue(scope.namespaces().setCategoryHidden("test", "test:gui", false));
        assertFalse(scope.namespaces().isCategoryHidden("test:gui"));
    }

    private Scope scope(List<Category> roots, List<Category> categoryList,
                        String applied, String unapplied) throws IOException {
        Path navigation = this.dataFolder.resolve("navigation");
        Files.createDirectories(navigation);
        Files.writeString(navigation.resolve("categories.yml"), applied);
        Files.writeString(navigation.resolve("categories-unapplied.yml"), unapplied);
        CategoryNamespaces namespaces = new CategoryNamespaces(this.dataFolder.toFile(), this.language,
                Logger.getLogger(DirectRecipeScopeTest.class.getName()));
        namespaces.load();
        // 将提供的未应用条目保留在生产同步使用的同一发现快照中。
        Set<String> discovered = new LinkedHashSet<>();
        categoryList.forEach(category -> discovered.add(category.id().namespace()));
        namespaces.unappliedEntries().forEach(entry -> discovered.add(entry.id()));
        namespaces.sync(discovered);
        Map<Key, Category> categories = new LinkedHashMap<>();
        categoryList.forEach(category -> categories.put(category.id(), category));
        return new Scope(roots, Map.copyOf(categories), CategoryGraph.compile(categories), namespaces);
    }

    private static Category category(String id, String... members) {
        return new Category(Key.of(id), id, List.of(), Key.of("minecraft:book"),
                List.of(members), 0, false, context -> true);
    }

    private static Player player(String... permissions) {
        Set<String> allowed = Set.of(permissions);
        return (Player) Proxy.newProxyInstance(Player.class.getClassLoader(), new Class<?>[]{Player.class},
                (proxy, method, args) -> switch (method.getName()) {
                    case "isOp" -> false;
                    case "hasPermission" -> allowed.contains(String.valueOf(args[0]));
                    case "equals" -> proxy == args[0];
                    case "hashCode" -> System.identityHashCode(proxy);
                    case "toString" -> "DirectRecipeScopeTest player";
                    default -> throw new AssertionError("Unexpected player access: " + method.getName());
                });
    }

    private record Scope(List<Category> roots, Map<Key, Category> categories, CategoryGraph graph,
                         CategoryNamespaces namespaces) {
        boolean allows(Key item, Collection<Key> loadedItems, Player player) {
            return HeldRecipeScope.canQuery(item, this.roots, this.categories, this.graph,
                    loadedItems, this.namespaces, player);
        }
    }
}
