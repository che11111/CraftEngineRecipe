package dev.che1s.craftenginerecipe.gui;

import dev.che1s.craftenginerecipe.config.CerConfig;
import org.bukkit.Material;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Field;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class BrowserLayoutTest {
    @Test
    void workstationLayoutKeepsCategoryArrowsAndUsesOnlyTheirSevenInnerSlots() throws Exception {
        CerConfig config = config(List.of("<CCCCCCC>", "XXXXXXXXX", "XXXXXXXXX", "XXXXXXXXX", "XXXXXXXXX", "BHLISGRT#"));

        String[] layout = BrowserLayout.workstations(config);

        assertArrayEquals(BrowserLayout.normalize(config), layout);
        assertEquals(List.of(0), BrowserLayout.slotsOf(layout, '<'));
        assertEquals(List.of(8), BrowserLayout.slotsOf(layout, '>'));
        List<Integer> categorySlots = BrowserLayout.slotsOf(layout, 'C');
        assertEquals(List.of(1, 2, 3, 4, 5, 6, 7), categorySlots);
        assertFalse(categorySlots.contains(0));
        assertFalse(categorySlots.contains(8));
        assertEquals("BHLISGRT#", layout[5]);
    }

    @Test
    void workstationLayoutPreservesCustomCategoryAndArrowPositions() throws Exception {
        CerConfig config = config(List.of("##C<C>C##", "XXXXXXXXX", "XXXXXXXXX", "XXXXXXXXX", "XXXXXXXXX", "TGBHISLR#"));

        String[] layout = BrowserLayout.workstations(config);

        assertEquals("##C<C>C##", layout[0]);
        assertEquals(List.of(2, 4, 6), BrowserLayout.slotsOf(layout, 'C'));
        assertEquals(List.of(3), BrowserLayout.slotsOf(layout, '<'));
        assertEquals(List.of(5), BrowserLayout.slotsOf(layout, '>'));
        assertEquals("TGBHISLR#", layout[5]);
    }

    @Test
    void eightStationsRemainReachableThroughSevenCategorySlots() {
        int initial = BrowserLayout.categoryOffset(0, 8, 7);
        int next = BrowserLayout.categoryOffset(initial + 1, 8, 7);

        assertEquals(0, initial);
        assertEquals(1, next);
        assertEquals(7, next + 7 - 1);
        assertEquals(next, BrowserLayout.categoryOffset(next + 1, 8, 7));
        assertEquals(initial, BrowserLayout.categoryOffset(next - 1, 8, 7));
    }

    @Test
    void customSmallCategoryBarsCanReachEveryStation() {
        int offset = BrowserLayout.categoryOffset(100, 8, 3);

        assertEquals(5, offset);
        assertEquals(7, offset + 3 - 1);
        assertEquals(0, BrowserLayout.categoryOffset(-1, 8, 3));
    }

    @Test
    void categoryScrollStaysAtZeroWhenEverythingFitsOrSlotsAreDisabled() {
        assertEquals(0, BrowserLayout.categoryOffset(1, 8, 8));
        assertEquals(0, BrowserLayout.categoryOffset(1, 8, 9));
        assertEquals(0, BrowserLayout.categoryOffset(1, 8, 0));
        assertEquals(0, BrowserLayout.categoryOffset(1, 0, 7));
    }

    @Test
    void decorativeGlassesSurvivesNormalizationWhileUnknownSymbolsBecomeEmpty() throws Exception {
        CerConfig config = config(List.of("<CCCCCCC>", "XXXXXXXXX", "XXXXXXXXX", "XXXXXXXXX", "XXXXXXXXX", "BHLISGRT?"));

        String[] layout = BrowserLayout.normalize(config);

        assertEquals("BHLISGRT#", layout[5]);
        assertEquals(List.of(50), BrowserLayout.slotsOf(layout, 'G'));
        assertTrue(BrowserLayout.slotsOf(layout, '?').isEmpty());
    }

    @Test
    void contentOnlyLayoutUsesSafeInvisibleMarkersInsteadOfWhitespace() throws Exception {
        CerConfig config = config(List.of("<CCCCCCC>", "XXXXXXXXX", "XXXXXXXXX", "XXXXXXXXX", "XXXXXXXXX", "BHLISGRT#"));

        String[] structure = BrowserService.contentOnlyStructure(config);

        assertEquals("         ", structure[0].replace(BrowserService.CONTENT_BLANK_CHAR, ' '));
        assertEquals(9, structure[0].length());
        assertEquals(9, BrowserLayout.slotsOf(structure, BrowserService.CONTENT_BLANK_CHAR).size());
        assertFalse(structure[0].contains(" "));
    }

    private static CerConfig config(List<String> rows) throws Exception {
        CerConfig config = new CerConfig(null);
        set(config, "layoutRows", rows);
        set(config, "categoryIconChar", 'C');
        set(config, "contentAreaChar", 'X');
        set(config, "prevCategory", button('<'));
        set(config, "nextCategory", button('>'));
        set(config, "prevPage", button('L'));
        set(config, "nextPage", button('R'));
        set(config, "search", button('S'));
        set(config, "back", button('B'));
        set(config, "hand", button('H'));
        set(config, "query", button('\u0000'));
        set(config, "close", button('\u0000'));
        set(config, "info", button('I'));
        set(config, "workstations", button('T'));
        set(config, "glasses", button('G'));
        return config;
    }

    private static CerConfig.ButtonDef button(char symbol) {
        return new CerConfig.ButtonDef(Material.PAPER, "", symbol, "", List.of());
    }

    private static void set(CerConfig config, String fieldName, Object value) throws Exception {
        Field field = CerConfig.class.getDeclaredField(fieldName);
        field.setAccessible(true);
        field.set(config, value);
    }
}
