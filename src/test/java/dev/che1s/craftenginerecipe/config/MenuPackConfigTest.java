package dev.che1s.craftenginerecipe.config;

import org.bukkit.configuration.file.YamlConfiguration;
import org.junit.jupiter.api.Test;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

class MenuPackConfigTest {
    @Test
    void bundledMenuPacksAreEnabledByDefault() throws Exception {
        YamlConfiguration config = this.yaml("config.yml");

        assertEquals("cer_menu_cute", config.getString("menu-packs.default"));
        assertEquals(List.of("cer_menu_cute", "cer_menu_rough", "cer_menu_vanilla", "cer_menu_map", "cer_menu_wood"),
                config.getStringList("menu-packs.enabled"));
        assertTrue(config.getBoolean("personalization.enabled"));
        assertEquals(List.of("cer_menu_cute", "cer_menu_rough", "cer_menu_vanilla", "cer_menu_map", "cer_menu_wood"),
                config.getStringList("personalization.menu-packs"));
        assertEquals("cer_menu_cute", config.getString("personalization.default-menu-pack"));
        assertTrue(config.getBoolean("personalization.default-remember-last-page"));
        assertEquals("namespaces", config.getString("browser.default-main-page"));
        assertFalse(config.isSet("personalization.default-main-page"));
        assertTrue(config.getBoolean("sources.reexport-craftengine-on-ce-reload"));
        assertTrue(config.getBoolean("indexing.notify-admins-on-startup"));
        assertTrue(config.getBoolean("indexing.notify-admins-on-api-register"));
        assertEquals("<white>", config.getString("name-prefixes.category-bar"));
        assertEquals("<black>", config.getString("name-prefixes.category-title"));
        assertEquals("<white>", config.getString("name-prefixes.workstation-bar"));
        assertEquals("<black>", config.getString("name-prefixes.workstation-title"));
        assertEquals("<black>", config.getString("name-prefixes.recipe-title"));
        assertTrue(config.isSet("sources.reexport-on-reload"));
        assertFalse(config.getBoolean("sources.reexport-on-reload"));
        assertFalse(config.isSet("sources.farmersdelight"));
        assertFalse(config.isSet("sources.kaleidoscope"));
        assertFalse(config.isSet("sources.customcrops"));
        assertFalse(config.isSet("integrations"));
        for (String path : List.of("browser.layout", "category-bar", "content", "sounds")) {
            assertFalse(config.isSet(path), path);
        }
    }

    @Test
    void bundledMenuPacksUseIndependentNamespaces() throws Exception {
        List<String> namespaces = List.of("cer_menu_cute", "cer_menu_rough", "cer_menu_vanilla",
                        "cer_menu_map", "cer_menu_wood").stream()
                .map(pack -> {
                    try {
                        return this.yaml("resources/" + pack + "/pack.yml").getString("namespace");
                    } catch (Exception error) {
                        throw new IllegalStateException(error);
                    }
                }).toList();

        assertEquals(5, Set.copyOf(namespaces).size());
    }

    @Test
    void cuteMenuUsesDrawnButtonsAndToggleBehavior() throws Exception {
        YamlConfiguration menu = this.menu("cer_menu_cute");
        YamlConfiguration gui = this.yaml("resources/cer_menu_cute/configuration/gui.yml");
        YamlConfiguration pack = this.yaml("resources/cer_menu_cute/pack.yml");

        assertEquals("CraftEngineRecipe,HuiDu_OwO", pack.getString("author"));
        assertEquals("cer_cute", pack.getString("namespace"));
        assertEquals("<#F48FB1>CER 可爱风格界面", menu.getString("display-name"));
        assertEquals("TBLHSGRIZ", menu.getStringList("browser.layout").getLast());
        assertEquals("Z", menu.getString("content.close.char"));
        assertEquals("minecraft:air", menu.getString("content.close.item-model"));
        assertEquals("minecraft:barrier", menu.getString("content.close.material"));
        assertTrue(menu.getBoolean("behavior.workstations-toggle"));
        assertTrue(menu.getBoolean("behavior.disable-back-button"));
        assertEquals(-163, menu.getInt("browser.title-shift"));
        assertEquals(-2, menu.getInt("browser.title-offset"));
        assertEquals("minecraft:air", menu.getString("browser.disabled-item-model"));
        assertMenuTitleTemplate(menu, "cer_cute:main");
        for (String path : List.of(
                "category-bar.prev-category", "category-bar.next-category",
                "content.prev-page", "content.next-page", "content.back",
                "content.search", "content.hand", "content.info",
                "content.workstations", "content.glasses")) {
            assertEquals("minecraft:air", menu.getString(path + ".item-model"), path);
        }
        assertEquals(110, gui.getInt("images.cer_cute:main.height"));
        assertEquals(-3, gui.getInt("images.cer_cute:main.ascent"));
        assertNotNull(getClass().getClassLoader().getResource(
                "resources/cer_menu_cute/resourcepack/assets/cer_cute/textures/font/gui/cer_main.png"));
    }

    @Test
    void roughMenuKeepsItsOriginalBehavior() throws Exception {
        YamlConfiguration menu = this.menu("cer_menu_rough");
        YamlConfiguration pack = this.yaml("resources/cer_menu_rough/pack.yml");
        assertEquals("cer_rough", pack.getString("namespace"));
        assertEquals("BTLHSGRI#", menu.getStringList("browser.layout").getLast());
        assertFalse(menu.getBoolean("behavior.workstations-toggle"));
        assertFalse(menu.getBoolean("behavior.disable-back-button"));
        assertEquals(-168, menu.getInt("browser.title-shift"));
        assertEquals(-8, menu.getInt("browser.title-offset"));
        assertEquals("cer_rough:blank_items", menu.getString("browser.disabled-item-model"));
        assertMenuTitleTemplate(menu, "cer_rough:main");
        assertEquals("cer_rough:crafting_table", menu.getString("content.workstations.item-model"));
    }

    @Test
    void mapMenuUsesItsOwnThreeRowMapLayoutAndTexture() throws Exception {
        YamlConfiguration menu = this.menu("cer_menu_map");
        YamlConfiguration gui = this.yaml("resources/cer_menu_map/configuration/gui.yml");
        YamlConfiguration pack = this.yaml("resources/cer_menu_map/pack.yml");

        assertEquals("cer_map", pack.getString("namespace"));
        assertEquals(List.of(
                "<CCCCCCC>",
                "XXXXXXXXX",
                "XXXXXXXXX",
                "XXXXXXXXX",
                "XXXXXXXXX",
                "QBL#S#RZT"), menu.getStringList("browser.layout"));
        assertEquals("Q", menu.getString("content.query.char"));
        assertEquals("B", menu.getString("content.back.char"));
        assertEquals("L", menu.getString("content.prev-page.char"));
        assertEquals("S", menu.getString("content.search.char"));
        assertEquals("R", menu.getString("content.next-page.char"));
        assertEquals("Z", menu.getString("content.close.char"));
        assertEquals("T", menu.getString("content.workstations.char"));
        assertEquals(-163, menu.getInt("browser.title-shift"));
        assertEquals(-8, menu.getInt("browser.title-offset"));
        assertEquals("minecraft:air", menu.getString("browser.disabled-item-model"));
        for (String path : List.of(
                "category-bar.prev-category", "category-bar.next-category",
                "content.prev-page", "content.next-page", "content.back",
                "content.search", "content.query", "content.close",
                "content.hand", "content.info", "content.workstations", "content.glasses")) {
            assertEquals("minecraft:air", menu.getString(path + ".item-model"), path);
        }
        assertMenuTitleTemplate(menu, "cer_map:main");
        assertEquals(256, gui.getInt("images.cer_map:main.height"));
        assertEquals(13, gui.getInt("images.cer_map:main.ascent"));
        assertEquals("cer_map:font/gui/cer_menu_map_main.png", gui.getString("images.cer_map:main.file"));
        assertNotNull(getClass().getClassLoader().getResource(
                "resources/cer_menu_map/resourcepack/assets/cer_map/textures/font/gui/cer_menu_map_main.png"));
        try (var imageStream = getClass().getClassLoader().getResourceAsStream(
                "resources/cer_menu_map/resourcepack/assets/cer_map/textures/font/gui/cer_menu_map_main.png")) {
            BufferedImage image = ImageIO.read(imageStream);
            assertNotNull(image);
            assertEquals(256, image.getWidth());
            assertEquals(256, image.getHeight());
        }
    }

    @Test
    void vanillaMenuUsesOnlyVanillaMaterialsAndStyledReservedSlots() throws Exception {
        YamlConfiguration menu = this.menu("cer_menu_vanilla");

        assertEquals(List.of(
                "<#BBBBBBB",
                "A#BBBBBBB",
                "A#BBBBBBB",
                "A#BBBBBBB",
                "A########",
                ">#LER#SHT"), menu.getStringList("browser.layout"));
        assertEquals("A", menu.getString("category-bar.icon-char"));
        assertEquals("B", menu.getString("content.area-char"));
        assertEquals("minecraft:black_stained_glass_pane",
                menu.getString("browser.reserved.material"));
        assertEquals("", menu.getString("browser.reserved.item-model"));
        assertFalse(menu.getBoolean("behavior.disable-back-button"));
        assertTrue(menu.getBoolean("behavior.workstations-toggle"));
        for (String path : List.of(
                "category-bar.prev-category", "category-bar.next-category",
                "content.prev-page", "content.next-page", "content.back",
                "content.search", "content.hand", "content.info",
                "content.workstations", "content.glasses")) {
            assertEquals("", menu.getString(path + ".item-model"), path);
        }
    }

    @Test
    void woodMenuUsesCompactDrawnButtonLayoutWithTransparentControls() throws Exception {
        YamlConfiguration menu = this.menu("cer_menu_wood");
        YamlConfiguration utility = this.yaml("resources/cer_menu_wood/menu/utility.yml");
        YamlConfiguration gui = this.yaml("resources/cer_menu_wood/configuration/gui.yml");
        YamlConfiguration pack = this.yaml("resources/cer_menu_wood/pack.yml");

        assertEquals("cer_wood", pack.getString("namespace"));
        assertEquals("<#9A6A38>CER 木板风格界面", menu.getString("display-name"));
        assertEquals(List.of(
                "<#BBBBBB#",
                "A#BBBBBB#",
                "A#BBBBBB#",
                "A#BBBBBB#",
                "A#BBBBBB#",
                ">LER##SHT"), menu.getStringList("browser.layout"));
        assertEquals(menu.getStringList("browser.layout"), utility.getStringList("browser.layout"));
        assertEquals(-18, menu.getInt("browser.title-offset"));
        assertEquals(-153, menu.getInt("browser.title-shift"));
        assertEquals("minecraft:air", menu.getString("browser.disabled-item-model"));
        assertEquals("minecraft:air", menu.getString("browser.reserved.item-model"));
        for (String path : List.of(
                "category-bar.prev-category", "category-bar.next-category",
                "content.prev-page", "content.next-page", "content.back",
                "content.search", "content.hand", "content.workstations",
                "content.empty")) {
            assertEquals("minecraft:air", menu.getString(path + ".item-model"), path);
            assertEquals("minecraft:air", utility.getString(path + ".item-model"), path);
        }
        assertTrue(menu.getBoolean("behavior.workstations-toggle"));
        assertFalse(menu.getBoolean("behavior.disable-back-button"));
        assertMenuTitleTemplate(menu, "cer_wood:main");
        assertEquals(256, gui.getInt("images.cer_wood:main.height"));
        assertEquals(13, gui.getInt("images.cer_wood:main.ascent"));
        assertEquals("cer_wood:font/gui/cer_main.png", gui.getString("images.cer_wood:main.file"));
        try (var imageStream = getClass().getClassLoader().getResourceAsStream(
                "resources/cer_menu_wood/resourcepack/assets/cer_wood/textures/font/gui/cer_main.png")) {
            BufferedImage image = ImageIO.read(imageStream);
            assertNotNull(image);
            assertEquals(256, image.getWidth());
            assertEquals(256, image.getHeight());
        }
    }

    @Test
    void languageTitlesContainTextButNoMenuPackAssetsOrShifts() throws Exception {
        for (String locale : new String[]{"zh_CN", "en_US"}) {
            YamlConfiguration language = this.yaml("lang/" + locale + ".yml");
            assertTrue(language.isSet("ui.content.item-lore"));
            assertTrue(language.isSet("ui.content.empty.name"));
            assertFalse(language.isSet("ui.personalization.item-lore"));
            assertFalse(language.isSet("ui.personalization.empty.name"));
            for (String path : new String[]{"ui.browser.title", "ui.workstations.title", "ui.search.result-title"}) {
                String title = language.getString(path);
                assertNotNull(title);
                assertFalse(title.contains("<image:"));
                assertFalse(title.contains("<shift:"));
            }
        }
    }

    @Test
    void compactLanguageTitlesDoNotReplaceMenuPackImageTemplates() throws Exception {
        for (String pack : List.of("cer_menu_cute", "cer_menu_rough",
                "cer_menu_map", "cer_menu_wood")) {
            YamlConfiguration menu = this.menu(pack);
            String template = CerConfig.titleTemplate(
                    menu, "browser.title-compact", "browser.title");
            String rendered = CerConfig.resolveMenuTitle(template, "物品分类 » 食物",
                    menu.getInt("browser.title-offset"), menu.getInt("browser.title-shift"));

            assertTrue(rendered.contains("<image:"), pack);
            assertTrue(rendered.contains("物品分类 » 食物"), pack);
            assertFalse(CerConfig.titleTemplate(
                    this.menu("cer_menu_vanilla"), "browser.title-compact",
                    "browser.title").contains("<image:"), "cer_menu_vanilla");
        }
    }

    @Test
    void everyMenuPackDeclaresEveryPageInACompleteLayoutFile() throws Exception {
        for (String pack : List.of("cer_menu_cute", "cer_menu_rough", "cer_menu_vanilla",
                "cer_menu_map", "cer_menu_wood")) {
            YamlConfiguration browse = this.yaml("resources/" + pack + "/menu/browse.yml");
            YamlConfiguration utility = this.yaml("resources/" + pack + "/menu/utility.yml");
            Set<String> declared = Set.copyOf(java.util.stream.Stream.concat(
                    browse.getStringList("pages").stream(),
                    utility.getStringList("pages").stream()).toList());
            for (MenuView view : MenuView.values()) {
                assertTrue(declared.contains(view.name().toLowerCase(java.util.Locale.ROOT)),
                        pack + "/" + view.name());
            }
            assertFalse(browse.getStringList("browser.layout").isEmpty(), pack + "/browse layout");
            assertFalse(utility.getStringList("browser.layout").isEmpty(), pack + "/utility layout");
        }
    }

    @Test
    void everyMenuPackOwnsChineseAndEnglishDisplayNames() throws Exception {
        for (String pack : List.of("cer_menu_cute", "cer_menu_rough", "cer_menu_vanilla",
                "cer_menu_map", "cer_menu_wood")) {
            YamlConfiguration chinese = this.yaml("resources/" + pack + "/lang/zh_CN.yml");
            YamlConfiguration english = this.yaml("resources/" + pack + "/lang/en_US.yml");
            assertNotNull(chinese.getString("display-name"), pack + "/zh_CN");
            assertNotNull(english.getString("display-name"), pack + "/en_US");
            assertFalse(english.getString("display-name").matches(".*[\\p{IsHan}].*"),
                    pack + "/en_US must not fall back to Chinese");
        }
    }

    private static void assertMenuTitleTemplate(YamlConfiguration menu, String image) {
        for (String path : new String[]{"browser.title", "browser.workstations-title", "browser.search-title"}) {
            String title = menu.getString(path);
            assertNotNull(title);
            assertTrue(title.contains("<title>"));
            assertTrue(title.contains("<shift:<offset>>"));
            assertTrue(title.contains("<image:" + image + ">"));
            assertTrue(title.contains("<shift:<shift>>"));
        }
    }

    private YamlConfiguration menu(String pack) throws Exception {
        return this.yaml("resources/" + pack + "/menu/browse.yml");
    }

    private YamlConfiguration yaml(String resource) throws Exception {
        try (var input = getClass().getClassLoader().getResourceAsStream(resource)) {
            assertNotNull(input);
            YamlConfiguration yaml = new YamlConfiguration();
            yaml.load(new InputStreamReader(input, StandardCharsets.UTF_8));
            return yaml;
        }
    }
}
