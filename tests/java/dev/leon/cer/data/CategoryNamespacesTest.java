package dev.leon.cer.data;

import dev.leon.cer.i18n.LanguageManager;
import net.momirealms.craftengine.core.plugin.gui.category.Category;
import net.momirealms.craftengine.core.util.Key;
import org.bukkit.configuration.file.YamlConfiguration;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.logging.Handler;
import java.util.logging.LogRecord;
import java.util.logging.Logger;

import static org.junit.jupiter.api.Assertions.*;

class CategoryNamespacesTest {
    @TempDir Path folder;
    private static final String NESTED = """
            entries:
              - id: farmersdelight
                visibility: player
                permission: ''
                hidden-items:
                  - category: farmersdelight:gui
                    items: ['*']
                  - category: farmersdelight:item_display
                    items: ['*']
            """;
    private static final String HIDDEN_CATEGORIES = """
            entries:
              - id: farmersdelight
                visibility: player
                permission: ''
                hidden-categories:
                  - farmersdelight:gui
                  - farmersdelight:item_display
            """;

    private CategoryNamespaces manager() {
        return manager(Logger.getLogger("CategoryNamespacesTest"));
    }

    private CategoryNamespaces manager(Logger logger) {
        LanguageManager language = new LanguageManager(folder,
                path -> getClass().getClassLoader().getResourceAsStream(path), logger);
        return new CategoryNamespaces(folder.toFile(), language, logger);
    }

    private void write(String name, String yaml) throws Exception {
        Files.writeString(folder.resolve(name), yaml);
    }

    @Test
    void suppliedFarmersDelightCategoriesHideOnlyThe88RequestedItems() throws Exception {
        write("categories.yml", NESTED);
        CategoryNamespaces namespaces = manager();
        namespaces.load();
        namespaces.sync(List.of("farmersdelight"));
        Map<Key, Category> categories = fixtureCategories();
        Category root = categories.get(Key.of("farmersdelight:categories"));
        Category gui = categories.get(Key.of("farmersdelight:gui"));
        Category display = categories.get(Key.of("farmersdelight:item_display"));
        assertEquals(28, gui.members().size());
        assertEquals(60, display.members().size());
        Set<Key> visible = CategoryItems.visible(List.of(root), categories, category -> true,
                category -> namespaces.hiddenItems(category.id().toString()));
        assertEquals(145, visible.size()); // 42 blocks + 7 items + 91 foods + 5 weapons
        for (String item : gui.members()) assertFalse(visible.contains(Key.of(item)), item);
        for (String item : display.members()) assertFalse(visible.contains(Key.of(item)), item);
        for (String id : List.of("block", "item", "food", "weapon")) {
            Category category = categories.get(Key.of("farmersdelight:" + id));
            for (String item : category.members()) assertTrue(visible.contains(Key.of(item)), item);
        }
        assertEquals(6, root.members().size()); // Category references themselves are retained.
    }

    private Map<Key, Category> fixtureCategories() throws Exception {
        YamlConfiguration fixture = new YamlConfiguration();
        try (var stream = getClass().getClassLoader().getResourceAsStream("farmersdelight-categories.yml");
             var reader = new InputStreamReader(stream, StandardCharsets.UTF_8)) {
            fixture.load(reader);
        }
        Map<Key, Category> categories = new LinkedHashMap<>();
        var section = fixture.getConfigurationSection("categories");
        assertNotNull(section);
        for (String id : section.getKeys(false)) {
            categories.put(Key.of(id), new Category(Key.of(id), id, List.of(), Key.of(section.getString(id + ".icon")),
                    section.getStringList(id + ".list"), 0, section.getBoolean(id + ".hidden"), context -> true));
        }
        return categories;
    }

    @Test
    void legacyTopLevelRulesRemainSupported() throws Exception {
        write("categories.yml", """
                entries:
                  - id: farmersdelight
                    visibility: player
                hidden-items:
                  - category: farmersdelight:gui
                    items: ['*']
                """);
        CategoryNamespaces namespaces = manager();
        namespaces.load();
        assertEquals(Set.of("*"), namespaces.hiddenItems("farmersdelight:gui"));
    }

    @Test
    void nestedLegacyAndRepeatedCategoryRulesAreMerged() throws Exception {
        write("categories.yml", """
                entries:
                  - id: farmersdelight
                    hidden-items:
                      - category: farmersdelight:gui
                        items: [farmersdelight:recipe_book]
                      - category: farmersdelight:gui
                        items: [farmersdelight:slot]
                  - id: other
                    hidden-items:
                      - category: other:gui
                        items: ['*']
                hidden-items:
                  - category: farmersdelight:gui
                    items: [farmersdelight:0]
                """);
        CategoryNamespaces namespaces = manager();
        namespaces.load();
        assertEquals(Set.of("farmersdelight:recipe_book", "farmersdelight:slot", "farmersdelight:0"),
                namespaces.hiddenItems("farmersdelight:gui"));
        assertEquals(Set.of("*"), namespaces.hiddenItems("other:gui"));
        assertNull(namespaces.hiddenItems("farmersdelight:food"));
    }

    @Test
    void removingRulesOnReloadClearsOldFilters() throws Exception {
        write("categories.yml", NESTED);
        CategoryNamespaces namespaces = manager();
        namespaces.load();
        assertEquals(Set.of("*"), namespaces.hiddenItems("farmersdelight:gui"));
        write("categories.yml", "entries:\n  - id: farmersdelight\n    visibility: player\n");
        namespaces.load();
        namespaces.sync(List.of("farmersdelight"));
        assertNull(namespaces.hiddenItems("farmersdelight:gui"));
        assertNull(namespaces.hiddenItems("farmersdelight:item_display"));
    }

    @Test
    void rewritingUnappliedFilePreservesNestedRulesAndRemovesStaleOnes() throws Exception {
        write("categories-unapplied.yml", NESTED);
        CategoryNamespaces namespaces = manager();
        namespaces.load();
        namespaces.sync(List.of("farmersdelight", "other")); // Adds a namespace and rewrites the file.
        YamlConfiguration saved = new YamlConfiguration();
        saved.load(folder.resolve("categories-unapplied.yml").toFile());
        List<?> entries = saved.getList("entries");
        assertNotNull(entries);
        assertEquals(2, ((List<?>) ((Map<?, ?>) entries.getFirst()).get("hidden-items")).size());
        assertFalse(saved.contains("hidden-items")); // Keep nested rules nested.
        namespaces.load();
        assertEquals(Set.of("*"), namespaces.hiddenItems("farmersdelight:gui"));
        namespaces.sync(List.of("other"));
        assertNull(namespaces.hiddenItems("farmersdelight:gui"));
    }

    @Test
    void savingFormalEntriesKeepsNestedAndLegacyRulesInTheirOriginalLocations() throws Exception {
        write("categories.yml", NESTED + """
                hidden-items:
                  - category: farmersdelight:food
                    items: [farmersdelight:tomato]
                """);
        CategoryNamespaces namespaces = manager();
        namespaces.load();
        Files.delete(folder.resolve("categories.yml")); // Exercise the initial-file save path.
        namespaces.sync(List.of("farmersdelight"));
        YamlConfiguration saved = new YamlConfiguration();
        saved.load(folder.resolve("categories.yml").toFile());
        assertEquals(1, saved.getMapList("hidden-items").size());
        assertEquals("farmersdelight:food", saved.getMapList("hidden-items").getFirst().get("category"));
        assertEquals(2, ((List<?>) saved.getMapList("entries").getFirst().get("hidden-items")).size());
        namespaces.load();
        assertEquals(Set.of("*"), namespaces.hiddenItems("farmersdelight:gui"));
        assertEquals(Set.of("farmersdelight:tomato"), namespaces.hiddenItems("farmersdelight:food"));
    }

    @Test
    void appliedEntryWinsOverStaleUnappliedEntryAndRulesSurviveMovingToFormalFile() throws Exception {
        write("categories-unapplied.yml", NESTED);
        write("categories.yml", """
                entries:
                  - id: farmersdelight
                    visibility: player
                    hidden-items:
                      - category: farmersdelight:gui
                        items: [farmersdelight:slot]
                """);
        CategoryNamespaces namespaces = manager();
        namespaces.load();
        assertEquals(Set.of("farmersdelight:slot"), namespaces.hiddenItems("farmersdelight:gui"));
        assertNull(namespaces.hiddenItems("farmersdelight:item_display"));
        namespaces.sync(List.of("farmersdelight"));
        assertTrue(namespaces.unappliedEntries().isEmpty());
        namespaces.load();
        assertEquals(Set.of("farmersdelight:slot"), namespaces.hiddenItems("farmersdelight:gui"));
    }

    @Test
    void categoryRulesHideOnlyTheTwoSuppliedCategoriesAndTheirSearchPaths() throws Exception {
        write("categories.yml", HIDDEN_CATEGORIES);
        CategoryNamespaces namespaces = manager();
        namespaces.load();
        namespaces.sync(List.of("farmersdelight"));
        Map<Key, Category> categories = fixtureCategories();
        Category root = categories.get(Key.of("farmersdelight:categories"));
        assertTrue(namespaces.isCategoryHidden("farmersdelight:gui"));
        assertTrue(namespaces.isCategoryHidden("farmersdelight:item_display"));
        assertFalse(namespaces.isCategoryHidden(root.id().toString()));
        assertEquals(List.of("#farmersdelight:block", "#farmersdelight:item", "#farmersdelight:food", "#farmersdelight:weapon"),
                root.members().stream().filter(ref -> !namespaces.isCategoryHidden(ref.substring(1))).toList());
        Set<Key> visible = CategoryItems.visible(List.of(root), categories,
                category -> !namespaces.isCategoryHidden(category.id().toString()),
                category -> namespaces.hiddenItems(category.id().toString()));
        assertEquals(145, visible.size());
        for (String id : List.of("gui", "item_display")) {
            Category category = categories.get(Key.of("farmersdelight:" + id));
            for (String item : category.members()) assertFalse(visible.contains(Key.of(item)), item);
        }
        assertTrue(visible.contains(Key.of("farmersdelight:tomato")));
    }

    @Test
    void categoryRulesSurviveFormalAndUnappliedSavesAndClearWhenRemoved() throws Exception {
        write("categories-unapplied.yml", HIDDEN_CATEGORIES);
        CategoryNamespaces namespaces = manager();
        namespaces.load();
        namespaces.sync(List.of("farmersdelight", "other"));
        namespaces.load();
        assertTrue(namespaces.isCategoryHidden("farmersdelight:gui"));
        YamlConfiguration extra = new YamlConfiguration();
        extra.load(folder.resolve("categories-unapplied.yml").toFile());
        assertEquals(List.of("farmersdelight:gui", "farmersdelight:item_display"),
                extra.getMapList("entries").getFirst().get("hidden-categories"));
        write("categories.yml", HIDDEN_CATEGORIES);
        namespaces.load();
        Files.delete(folder.resolve("categories.yml"));
        namespaces.sync(List.of("farmersdelight", "other"));
        YamlConfiguration formal = new YamlConfiguration();
        formal.load(folder.resolve("categories.yml").toFile());
        assertEquals(List.of("farmersdelight:gui", "farmersdelight:item_display"),
                formal.getMapList("entries").getFirst().get("hidden-categories"));
        namespaces.load();
        assertTrue(namespaces.isCategoryHidden("farmersdelight:item_display"));
        write("categories.yml", "entries:\n  - id: farmersdelight\n    visibility: player\n");
        namespaces.load();
        assertFalse(namespaces.isCategoryHidden("farmersdelight:gui"));
        assertFalse(namespaces.isCategoryHidden("farmersdelight:item_display"));
        write("categories-unapplied.yml", HIDDEN_CATEGORIES);
        write("categories.yml", "entries: []\n");
        namespaces.load();
        assertTrue(namespaces.isCategoryHidden("farmersdelight:gui"));
        namespaces.sync(List.of("other"));
        assertFalse(namespaces.isCategoryHidden("farmersdelight:gui"));
    }

    @Test
    void formalCategoryRulesOverrideStaleUnappliedRulesAndKeepOtherNamespacesIndependent() throws Exception {
        write("categories-unapplied.yml", HIDDEN_CATEGORIES);
        write("categories.yml", """
                entries:
                  - id: farmersdelight
                    visibility: player
                    hidden-categories: [farmersdelight:gui]
                    hidden-items:
                      - category: farmersdelight:food
                        items: [farmersdelight:tomato]
                  - id: other
                    hidden-categories: [other:gui]
                """);
        CategoryNamespaces namespaces = manager();
        namespaces.load();
        namespaces.sync(List.of("farmersdelight", "other"));
        assertTrue(namespaces.isCategoryHidden("farmersdelight:gui"));
        assertFalse(namespaces.isCategoryHidden("farmersdelight:item_display"));
        assertTrue(namespaces.isCategoryHidden("other:gui"));
        assertFalse(namespaces.isCategoryHidden("other:item_display"));
        assertEquals(Set.of("farmersdelight:tomato"), namespaces.hiddenItems("farmersdelight:food"));
    }

    @Test
    void namespaceRecordsDefensivelyCopyNestedCollectionsAndKeepOrder() {
        List<String> itemLore = new ArrayList<>(List.of("item"));
        List<String> iconLore = new ArrayList<>(List.of("icon"));
        List<String> subLore = new ArrayList<>(List.of("sub"));
        Set<String> items = new LinkedHashSet<>(List.of("test:second", "test:first"));
        Map<String, Set<String>> hidden = new LinkedHashMap<>();
        hidden.put("test:second", items);
        hidden.put("test:first", new LinkedHashSet<>(List.of("*")));
        Set<String> categories = new LinkedHashSet<>(List.of("test:z", "test:a"));
        var entry = new CategoryNamespaces.NamespaceEntry("test", CategoryNamespaces.Visibility.PLAYER, "", "",
                itemLore, iconLore, subLore, hidden, categories);
        itemLore.clear();
        iconLore.clear();
        subLore.clear();
        items.clear();
        hidden.clear();
        categories.clear();
        assertEquals(List.of("item"), entry.itemLore());
        assertEquals(List.of("icon"), entry.iconLore());
        assertEquals(List.of("sub"), entry.subIconLore());
        assertEquals(List.of("test:second", "test:first"), new ArrayList<>(entry.hiddenItems().keySet()));
        assertEquals(List.of("test:second", "test:first"), new ArrayList<>(entry.hiddenItems().get("test:second")));
        assertEquals(List.of("test:z", "test:a"), new ArrayList<>(entry.hiddenCategories()));
        assertThrows(UnsupportedOperationException.class, () -> entry.itemLore().clear());
        assertThrows(UnsupportedOperationException.class, () -> entry.iconLore().clear());
        assertThrows(UnsupportedOperationException.class, () -> entry.subIconLore().clear());
        assertThrows(UnsupportedOperationException.class, () -> entry.hiddenItems().clear());
        assertThrows(UnsupportedOperationException.class, () -> entry.hiddenItems().get("test:second").clear());
        assertThrows(UnsupportedOperationException.class, () -> entry.hiddenCategories().clear());
    }

    @Test
    void buttonRecordsDefensivelyCopyDisplayAndActionsWithoutDroppingNullActions() {
        List<String> lore = new ArrayList<>(List.of("hint"));
        var display = new CategoryNamespaces.ButtonDisplay("stone", "name", lore);
        List<String> lines = new ArrayList<>(Arrays.asList("sound: click", null, "console: reward"));
        Map<String, List<String>> actions = new LinkedHashMap<>();
        actions.put("right", lines);
        actions.put("all", new ArrayList<>(List.of("close")));
        var button = new CategoryNamespaces.ButtonEntry("button", display, actions,
                CategoryNamespaces.Visibility.PERMISSION, "test.button");
        lore.clear();
        lines.clear();
        actions.clear();
        assertEquals(List.of("hint"), button.display().lore());
        assertEquals(List.of("right", "all"), new ArrayList<>(button.actions().keySet()));
        assertEquals(Arrays.asList("sound: click", null, "console: reward"), button.actions().get("right"));
        assertEquals(List.of("close"), button.actionsFor(null));
        assertThrows(UnsupportedOperationException.class, () -> button.display().lore().clear());
        assertThrows(UnsupportedOperationException.class, () -> button.actions().clear());
        assertThrows(UnsupportedOperationException.class, () -> button.actions().get("right").clear());
        assertThrows(UnsupportedOperationException.class, () -> button.actionsFor(null).clear());
    }

    @Test
    void publishedEntryListsAreReadOnlySnapshotsThatCannotChangeTheManager() throws Exception {
        write("categories.yml", "entries:\n  - id: first\n");
        CategoryNamespaces namespaces = manager();
        namespaces.load();
        namespaces.sync(List.of("first", "extra"));
        List<CategoryNamespaces.Entry> formal = namespaces.entries();
        List<CategoryNamespaces.Entry> all = namespaces.allEntries();
        assertThrows(UnsupportedOperationException.class, formal::clear);
        assertThrows(UnsupportedOperationException.class, all::clear);
        write("categories.yml", "entries:\n  - id: replacement\n");
        namespaces.load();
        assertEquals(List.of("first"), formal.stream().map(CategoryNamespaces.Entry::id).toList());
        assertEquals(List.of("first", "extra"), all.stream().map(CategoryNamespaces.Entry::id).toList());
        assertEquals(List.of("replacement"), namespaces.entries().stream().map(CategoryNamespaces.Entry::id).toList());
    }

    @Test
    void invalidConfigurationOnlyWarnsAndPreservesExistingValuesAndFallbacks() throws Exception {
        String config = """
                entries:
                  - id: Invalid Namespace
                    visibility: unexpected
                    hidden-items:
                      - category: 'bad category'
                        items: ['*', 'bad item', test:valid]
                    hidden-categories: [test:missing, 'bad category']
                  - display:
                      material: 'source:CraftEngine:bad ID'
                      name: Button
                    actions: ['console: original']
                """;
        write("categories.yml", config);
        List<String> messages = new ArrayList<>();
        CategoryNamespaces namespaces = manager(capturingLogger(messages));
        namespaces.load();
        assertEquals(config, Files.readString(folder.resolve("categories.yml")));
        assertEquals(2, namespaces.entries().size());
        var namespace = (CategoryNamespaces.NamespaceEntry) namespaces.entries().getFirst();
        assertEquals("Invalid Namespace", namespace.id());
        assertEquals(CategoryNamespaces.Visibility.ADMIN, namespace.visibility());
        assertEquals(Set.of("*", "bad item", "test:valid"), namespace.hiddenItems().get("bad category"));
        assertEquals(Set.of("test:missing", "bad category"), namespace.hiddenCategories());
        var button = (CategoryNamespaces.ButtonEntry) namespaces.entries().get(1);
        assertEquals(CategoryNamespaces.Visibility.PLAYER, button.visibility());
        assertEquals("source:CraftEngine:bad ID", button.display().material());
        assertEquals(List.of("console: original"), button.actionsFor(null));
        assertEquals(5, messages.size());
        for (String path : List.of("entries[0].id", "entries[0].hidden-items[0].category",
                "entries[0].hidden-items[0].items[1]", "entries[0].hidden-categories[1]", "entries[1].display.material")) {
            assertTrue(messages.stream().anyMatch(message -> message.contains("categories.yml:" + path)), path);
        }
    }

    @Test
    void referenceValidationUsesLoadedPathsWithoutReadingFilesAgainOrRemovingRules() throws Exception {
        write("categories.yml", """
                namespaces:
                  - id: test
                    hidden-categories: [test:present, test:missing]
                hidden-items:
                  - category: test:legacy
                    items: ['*']
                """);
        write("categories-unapplied.yml", """
                entries:
                  - id: other
                    hidden-items:
                      - category: other:missing
                        items: ['*']
                """);
        List<String> messages = new ArrayList<>();
        CategoryNamespaces namespaces = manager(capturingLogger(messages));
        namespaces.load();
        assertTrue(messages.isEmpty());
        Files.delete(folder.resolve("categories.yml"));
        Files.delete(folder.resolve("categories-unapplied.yml"));
        namespaces.validateReferences(Set.of(Key.of("test:present")));
        assertEquals(3, messages.size());
        assertTrue(messages.stream().anyMatch(message -> message.contains("categories.yml:namespaces[0].hidden-categories[1]")));
        assertTrue(messages.stream().anyMatch(message -> message.contains("categories.yml:hidden-items[0].category")));
        assertTrue(messages.stream().anyMatch(message -> message.contains("categories-unapplied.yml:entries[0].hidden-items[0].category")));
        assertTrue(namespaces.isCategoryHidden("test:missing"));
        assertEquals(Set.of("*"), namespaces.hiddenItems("test:legacy"));
        assertEquals(Set.of("*"), namespaces.hiddenItems("other:missing"));
    }

    private static Logger capturingLogger(List<String> messages) {
        Logger logger = Logger.getAnonymousLogger();
        logger.setUseParentHandlers(false);
        logger.addHandler(new Handler() {
            @Override public void publish(LogRecord record) { messages.add(record.getMessage()); }
            @Override public void flush() {}
            @Override public void close() {}
        });
        return logger;
    }
}
