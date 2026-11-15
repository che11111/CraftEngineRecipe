package dev.che1s.craftenginerecipe.config;

import dev.che1s.craftenginerecipe.CraftEngineRecipePlugin;
import dev.che1s.craftenginerecipe.i18n.LanguageManager;
import org.bukkit.Material;
import org.bukkit.configuration.file.YamlConfiguration;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.Arrays;
import java.util.List;
import java.util.logging.Logger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

class ButtonDefTest {
    private static final char DISABLED = '\u0000';
    private static final List<Shortcut> SHORTCUTS = List.of(
            new Shortcut("content.hand", Material.PAPER, "cer:palm_blue_cuff", 'H'),
            new Shortcut("content.info", Material.PAPER, "cer:info_icon", 'I'),
            new Shortcut("content.workstations", Material.CRAFTING_TABLE, "cer:crafting_table", 'T'));

    @TempDir
    Path dataFolder;

    private LanguageManager language;

    @BeforeEach
    void loadBundledLanguageWithoutAServer() {
        this.language = new LanguageManager(this.dataFolder,
                resource -> ButtonDefTest.class.getClassLoader().getResourceAsStream(resource),
                Logger.getLogger(ButtonDefTest.class.getName()));
        this.language.load("zh_CN");
    }

    @Test
    void contentEmptyWithoutACharDoesNotTryToIndexTheNulSentinel() {
        YamlConfiguration config = new YamlConfiguration();
        config.set("content.empty.material", "minecraft:barrier");

        CerConfig.ButtonDef button = CerConfig.ButtonDef.of(config, this.language,
                "content.empty", Material.BARRIER);

        assertEquals(DISABLED, button.symbol());
        assertEquals(Material.BARRIER, button.material());
        assertEquals(this.language.text("ui.content.empty.name"), button.name());
    }

    @Test
    void existingButtonsWithMissingCharsKeepTheirDisabledSentinel() {
        YamlConfiguration config = new YamlConfiguration();
        for (String path : List.of("category-bar.prev-category", "category-bar.next-category",
                "content.prev-page", "content.next-page", "content.back", "content.search")) {
            CerConfig.ButtonDef button = CerConfig.ButtonDef.of(config, this.language, path, Material.ARROW);

            assertEquals(DISABLED, button.symbol(), path);
            assertEquals(Material.ARROW, button.material(), path);
        }
    }

    @Test
    void menuPackLanguageOverridesSharedLabelsWhileLayoutOnlySuppliesAppearance() {
        YamlConfiguration layout = new YamlConfiguration();
        layout.set("content.search.name", "<gray>搜索");
        layout.set("content.search.lore", List.of("中文提示"));
        YamlConfiguration text = new YamlConfiguration();
        text.set("content.search.name", "<gray>Find");
        text.set("content.search.lore", List.of("English menu-pack tooltip"));
        this.language.load("en_US");

        CerConfig.ButtonDef button = CerConfig.ButtonDef.of(
                layout, text, this.language.view("en_US"), "content.search", Material.COMPASS);

        assertEquals("Find", strip(button.name()));
        assertFalse(button.name().contains("搜索"));
        assertEquals(List.of("English menu-pack tooltip"), button.lore());
    }

    @Test
    void missingShortcutKeysUseTheirDeclaredDefaults() {
        YamlConfiguration oldConfig = new YamlConfiguration();
        for (Shortcut shortcut : SHORTCUTS) {
            CerConfig.ButtonDef button = shortcut.parse(oldConfig, this.language);

            assertEquals(shortcut.symbol(), button.symbol(), shortcut.path());
            assertEquals(shortcut.material(), button.material(), shortcut.path());
            assertEquals(shortcut.model(), button.itemModel(), shortcut.path());
            assertEquals(this.language.text("ui." + shortcut.path() + ".name"), button.name());
            assertFalse(button.lore().isEmpty(), shortcut.path());
        }
    }

    @Test
    void explicitlyEmptyShortcutCharsDisableTheButtons() {
        YamlConfiguration config = new YamlConfiguration();
        for (Shortcut shortcut : SHORTCUTS) {
            config.set(shortcut.path() + ".char", "");

            assertEquals(DISABLED, shortcut.parse(config, this.language).symbol(), shortcut.path());
        }
    }

    @Test
    void nullWhitespaceAndControlOnlyCharsDoNotCrashEitherOverload() {
        for (String value : Arrays.asList(null, "", " ", "\t\r\n", "\u0000", "\u0001\u001f", "\u2003")) {
            YamlConfiguration config = new YamlConfiguration();
            config.set("content.empty.char", value);
            assertEquals(DISABLED, CerConfig.ButtonDef.of(config, this.language,
                    "content.empty", Material.BARRIER).symbol());

            for (Shortcut shortcut : SHORTCUTS) {
                config.set(shortcut.path() + ".char", value);
                // YAML 中的 null 值会移除对应键，因此保留快捷按钮的默认值。
                char expected = value == null ? shortcut.symbol() : DISABLED;
                assertEquals(expected, shortcut.parse(config, this.language).symbol(), shortcut.path());
            }
        }
    }

    @Test
    void surroundingWhitespaceAndControlCharsStillAllowARealSymbol() {
        YamlConfiguration config = new YamlConfiguration();
        config.set("content.search.char", " \u0000S\u001f ");

        assertEquals('S', CerConfig.ButtonDef.of(config, this.language,
                "content.search", Material.COMPASS).symbol());
    }

    private record Shortcut(String path, Material material, String model, char symbol) {
        CerConfig.ButtonDef parse(YamlConfiguration config, LanguageManager language) {
            return CerConfig.ButtonDef.of(config, language, this.path, this.material, this.model, this.symbol);
        }
    }

    private static String strip(String value) {
        return net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer
                .plainText().serialize(CraftEngineRecipePlugin.mini().deserialize(value));
    }
}
