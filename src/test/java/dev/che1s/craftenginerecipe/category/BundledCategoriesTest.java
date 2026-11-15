package dev.che1s.craftenginerecipe.category;

import dev.che1s.craftenginerecipe.i18n.LanguageManager;
import org.bukkit.configuration.file.YamlConfiguration;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.logging.Logger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class BundledCategoriesTest {
    @TempDir Path directory;

    @Test
    void bundledCategoriesStartWithTheVirtualVanillaItemsEntry() throws Exception {
        Path navigation = this.directory.resolve("navigation");
        Files.createDirectories(navigation);
        try (InputStream input = getClass().getClassLoader()
                .getResourceAsStream("navigation/categories.yml")) {
            assertNotNull(input);
            Files.copy(input, navigation.resolve("categories.yml"));
        }
        LanguageManager language = new LanguageManager(this.directory,
                resource -> getClass().getClassLoader().getResourceAsStream(resource),
                Logger.getAnonymousLogger());
        language.load("zh_CN");
        CategoryNamespaces namespaces = new CategoryNamespaces(this.directory.toFile(), language,
                Logger.getAnonymousLogger());

        namespaces.load();
        namespaces.sync(Set.of("minecraft"));
        var chinese = language.view("zh_CN");
        var english = language.view("en_US");

        CategoryNamespaces.NamespaceEntry first = namespaces.entries().stream()
                .filter(CategoryNamespaces.NamespaceEntry.class::isInstance)
                .map(CategoryNamespaces.NamespaceEntry.class::cast)
                .findFirst().orElseThrow();
        assertEquals("minecraft", first.id());
        assertEquals("<white>原版物品</white>", first.iconName().resolve(chinese));
        assertEquals("<white>Vanilla Items</white>", first.iconName().resolve(english));
        assertTrue(first.sortByNamespace());
        assertTrue(first.hideItemsWithoutRecipes());
        assertTrue(first.iconLore().resolveLines(chinese).stream().anyMatch(line -> line.contains("<count>")));
        assertTrue(first.iconLore().resolveLines(english).stream().anyMatch(line -> line.contains("<count>")));

        CategoryNamespaces.ButtonEntry management = namespaces.entries().stream()
                .filter(CategoryNamespaces.ButtonEntry.class::isInstance)
                .map(CategoryNamespaces.ButtonEntry.class::cast)
                .filter(button -> button.actionsFor(null).contains("command: cer manage"))
                .findFirst().orElseThrow();
        assertEquals(CategoryNamespaces.Visibility.ADMIN, management.visibility());
        assertEquals("minecraft:compass", management.display().material());
        assertEquals("&#D8B278打开「管理页面」", management.display().name().resolve(chinese));
        assertEquals("&#D8B278Open Management", management.display().name().resolve(english));
        assertTrue(management.hideInaccessible());
        assertTrue(management.actionsFor(null).contains("close"));
        assertTrue(management.actionsFor(null).contains("delay: 0.1"));
    }

    @Test
    void categoryTextFieldsSupportInlineLocaleMapsAndRoundTripThroughTheCodec() throws Exception {
        Path navigation = this.directory.resolve("navigation");
        Files.createDirectories(navigation);
        Files.writeString(navigation.resolve("categories.yml"), """
                entries:
                - display:
                    material: minecraft:compass
                    name:
                      zh_CN: '中文按钮'
                      en_US: 'English button'
                    lore:
                      zh_CN: ['中文第一行', '中文第二行']
                      en_US: ['English line 1', 'English line 2']
                  actions:
                    all: []
                """);
        LanguageManager language = new LanguageManager(this.directory,
                resource -> getClass().getClassLoader().getResourceAsStream(resource),
                Logger.getAnonymousLogger());
        language.load("zh_CN");
        CategoryNamespaces namespaces = new CategoryNamespaces(this.directory.toFile(), language,
                Logger.getAnonymousLogger());

        namespaces.load();

        CategoryNamespaces.ButtonEntry button = namespaces.entries().stream()
                .filter(CategoryNamespaces.ButtonEntry.class::isInstance)
                .map(CategoryNamespaces.ButtonEntry.class::cast)
                .findFirst().orElseThrow();
        assertEquals("中文按钮", button.display().name().resolve(language.view("zh_CN")));
        assertEquals("English button", button.display().name().resolve(language.view("en_US")));
        assertEquals(List.of("中文第一行", "中文第二行"),
                button.display().lore().resolveLines(language.view("zh_CN")));
        assertEquals(List.of("English line 1", "English line 2"),
                button.display().lore().resolveLines(language.view("en_US")));

        Map<String, Object> serialized = CategoryNamespaces.buttonMap(button);
        Map<?, ?> display = (Map<?, ?>) serialized.get("display");
        assertTrue(display.get("name") instanceof Map<?, ?>);
        assertTrue(display.get("lore") instanceof Map<?, ?>);
    }

    @Test
    void entriesCanOptIntoHidingWithoutReservingSlots() throws Exception {
        Path navigation = this.directory.resolve("navigation");
        Files.createDirectories(navigation);
        Files.writeString(navigation.resolve("categories.yml"), """
                entries:
                - id: example_namespace
                  visibility: admin
                  permission: ''
                  hide-inaccessible: true
                - display:
                    material: minecraft:barrier
                    name: 'Hidden button'
                  visibility: admin
                  permission: ''
                  hide-inaccessible: true
                  actions:
                    all: []
                """);
        LanguageManager language = new LanguageManager(this.directory,
                resource -> getClass().getClassLoader().getResourceAsStream(resource),
                Logger.getAnonymousLogger());
        language.load("zh_CN");
        CategoryNamespaces namespaces = new CategoryNamespaces(this.directory.toFile(), language,
                Logger.getAnonymousLogger());

        namespaces.load();

        CategoryNamespaces.NamespaceEntry namespace = namespaces.entries().stream()
                .filter(CategoryNamespaces.NamespaceEntry.class::isInstance)
                .map(CategoryNamespaces.NamespaceEntry.class::cast)
                .findFirst().orElseThrow();
        CategoryNamespaces.ButtonEntry button = namespaces.entries().stream()
                .filter(CategoryNamespaces.ButtonEntry.class::isInstance)
                .map(CategoryNamespaces.ButtonEntry.class::cast)
                .findFirst().orElseThrow();
        assertTrue(namespace.hideInaccessible());
        assertTrue(button.hideInaccessible());
    }

    @Test
    void extensionCategoryPresetsKeepPluginSpecificHidingRules() throws Exception {
        List<Map<?, ?>> entries = new java.util.ArrayList<>();
        for (String module : List.of("craftengine", "farmersdelight",
                "kaleidoscope", "customcrops")) {
            YamlConfiguration yaml = YamlConfiguration.loadConfiguration(
                    Path.of("extensions-build", module,
                            "src/main/resources/category-presets.yml").toFile());
            entries.addAll(yaml.getMapList("presets"));
        }
        assertEquals(11, entries.size());
        Map<String, CategoryNamespaces.NamespaceEntry> presets = new java.util.LinkedHashMap<>();
        for (Map<?, ?> value : entries) {
            CategoryNamespaces.NamespaceEntry preset = CategoryNamespaces.parseNamespace(value);
            assertNotNull(preset);
            presets.put(preset.id(), preset);
        }
        assertEquals(Set.of("farmersdelight:gui", "farmersdelight:item_display"),
                presets.get("farmersdelight").hiddenCategories());
        assertEquals(Set.of(), presets.get("customcrops").hiddenCategories());
        assertEquals(Set.of("endsdelight:item_display"),
                presets.get("endsdelight").hiddenCategories());
        assertTrue(presets.get("ultramarine").hideItemsWithoutRecipes());
        assertTrue(presets.get("ultramarine").hiddenItems().isEmpty());
        Map<?, ?> farmersDelight = (Map<?, ?>) entries.stream()
                .filter(value -> "farmersdelight".equals(((Map<?, ?>) value).get("id")))
                .findFirst().orElseThrow();
        assertTrue(((List<?>) farmersDelight.get("authors")).contains("HuiDu_OwO"));
        assertTrue(presets.get("ultramarine").hiddenItems().isEmpty());
    }

    @Test
    void managedCategoryOwnershipRoundTripsAndOnlyInactiveJarsAreRemoved() throws Exception {
        Path navigation = this.directory.resolve("navigation");
        Files.createDirectories(navigation);
        Files.writeString(navigation.resolve("categories.yml"), """
                entries:
                - id: active_namespace
                  cer-managed-by: cer-ext-active.jar
                - id: inactive_namespace
                  cer-managed-by: cer-ext-inactive.jar
                - id: local_namespace
                """);
        LanguageManager language = new LanguageManager(this.directory,
                resource -> getClass().getClassLoader().getResourceAsStream(resource),
                Logger.getAnonymousLogger());
        language.load("zh_CN");
        CategoryNamespaces namespaces = new CategoryNamespaces(this.directory.toFile(), language,
                Logger.getAnonymousLogger());
        namespaces.load();

        assertEquals(1, namespaces.cleanupManagedBy(Set.of("cer-ext-active.jar")));
        assertEquals(List.of("active_namespace", "local_namespace"),
                namespaces.orderedNamespaces());
        assertNotNull(namespaces.appliedEntry("active_namespace"));
        assertEquals("cer-ext-active.jar", namespaces.appliedEntry("active_namespace").managedBy());
        assertTrue(Files.readString(navigation.resolve("categories.yml"))
                .contains("cer-managed-by: cer-ext-active.jar"));
        assertTrue(Files.readString(navigation.resolve("categories.yml"))
                .contains("id: local_namespace"));
    }
}
