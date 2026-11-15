package dev.che1s.craftenginerecipe.gui;

import dev.che1s.craftenginerecipe.config.CerConfig;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.TextColor;
import net.kyori.adventure.text.format.TextDecoration;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class WorkstationBrowserServiceTest {
    @Test
    void directoryNamesAndLoreUseConfiguredUnifiedColors() {
        CerConfig config = new CerConfig(null);
        Component dark = Component.text("农夫乐事·特殊配方", TextColor.color(0x292D35));

        Component name = BrowserService.directoryName(dark, config, config.workstationBarNamePrefix());
        List<Component> lore = BrowserService.directoryLore(List.of(
                Component.text("第一行", TextColor.color(0xFFFFFF)),
                Component.text("第二行", TextColor.color(0x00FF00))));

        assertEquals("农夫乐事·特殊配方", PlainTextComponentSerializer.plainText().serialize(name));
        assertEquals(TextColor.color(0xFFFFFF), name.color());
        assertEquals(TextDecoration.State.FALSE, name.decoration(TextDecoration.ITALIC));
        assertEquals(List.of("第一行", "第二行"), lore.stream()
                .map(PlainTextComponentSerializer.plainText()::serialize).toList());
        assertEquals(List.of(TextColor.color(0xB8C0CC), TextColor.color(0xB8C0CC)),
                lore.stream().map(Component::color).toList());
    }

    @Test
    void titleNamesUseTheirConfiguredPrefixInsteadOfTemplateInheritance() {
        CerConfig config = new CerConfig(null);
        Component name = config.styledName(config.workstationTitleNamePrefix(), "森罗物语·炒锅");
        Component title = config.titleWithName(
                "<#FFFFFF><bold>合成器 » <category></bold>", name);

        assertEquals("合成器 » 森罗物语·炒锅",
                PlainTextComponentSerializer.plainText().serialize(title));
        assertEquals(TextColor.color(0x000000), name.color());
        assertEquals(TextDecoration.State.NOT_SET, name.decoration(TextDecoration.BOLD));
        assertFalse(name.hasDecoration(TextDecoration.ITALIC));
    }

    @Test
    void titleStylingDoesNotInsertComponentsOrChangeVisibleText() {
        CerConfig config = new CerConfig(null);
        Component source = Component.text()
                .append(Component.text("\uE001", TextColor.color(0xFFFFFF)))
                .append(Component.text("农夫乐事·烹饪锅", TextColor.color(0x292D35)))
                .append(Component.text("\uE002", TextColor.color(0xFFFFFF)))
                .build();

        Component styled = config.applyNameStyle(config.recipeTitleNamePrefix(), source, "农夫乐事·烹饪锅");

        assertEquals(PlainTextComponentSerializer.plainText().serialize(source),
                PlainTextComponentSerializer.plainText().serialize(styled));
        assertEquals(source.children().size(), styled.children().size());
        assertEquals(TextColor.color(0xFFFFFF), styled.children().getFirst().color());
        assertEquals(TextColor.color(0x000000), styled.children().get(1).color());
        assertEquals(TextDecoration.State.NOT_SET,
                styled.children().get(1).decoration(TextDecoration.BOLD));
        assertEquals(TextColor.color(0xFFFFFF), styled.children().getLast().color());
        assertEquals(TextDecoration.State.NOT_SET,
                styled.children().getFirst().decoration(TextDecoration.BOLD));
    }

    @Test
    void presentationDataControlsTitleBoldIndependentlyOfTheCore() {
        CerConfig config = new CerConfig(null);
        Component englishName = config.styledName(
                config.workstationTitleNamePrefix(), "Farmer's Delight·Cooking Pot", false);
        assertEquals(TextDecoration.State.FALSE,
                englishName.decoration(TextDecoration.BOLD));

        Component chineseName = config.styledName(
                config.workstationTitleNamePrefix(), "农夫乐事·烹饪锅", true);
        assertEquals(TextDecoration.State.TRUE,
                chineseName.decoration(TextDecoration.BOLD));
    }
}
