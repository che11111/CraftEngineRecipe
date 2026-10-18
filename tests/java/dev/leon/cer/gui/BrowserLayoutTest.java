package dev.leon.cer.gui;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class BrowserLayoutTest {
    @Test
    void keepsTheDefaultSixByNineLayout() {
        List<String> rows = List.of("<CCCCCCC>", "XXXXXXXXX", "XXXXXXXXX", "XXXXXXXXX", "XXXXXXXXX", "B#L#S#R##");
        assertArrayEquals(rows.toArray(String[]::new), BrowserLayout.normalize(rows, 'C', 'X',
                List.of('<', '>', 'L', 'R', 'S', 'B')));
    }

    @Test
    void stripsOnlySpacesAndTabsPadsMissingRowsAndTruncatesAfterNineSlots() {
        String[] layout = BrowserLayout.normalize(List.of(" C\tX ? B C X B C X B C", "\nX"),
                'C', 'X', List.of('B'));
        assertArrayEquals(new String[]{"CX#BCXBCX", "#X#######", "#########", "#########", "#########", "#########"}, layout);
        assertEquals(List.of(0, 4, 7), BrowserLayout.slotsOf(layout, 'C'));
        assertEquals(List.of(1, 5, 8, 10), BrowserLayout.slotsOf(layout, 'X'));
    }

    @Test
    void dropsExtraRowsAndDoesNotBindDisabledOrUnknownSymbols() {
        String[] layout = BrowserLayout.normalize(List.of("C", "X", "B", "?", "\u0000", "B", "CCCCCCCCC"),
                'C', 'X', List.of('B', '\u0000'));
        assertEquals(6, layout.length);
        assertEquals("#########", layout[3]);
        assertEquals("#########", layout[4]);
        assertEquals(List.of(0), BrowserLayout.slotsOf(layout, 'C'));
        assertTrue(BrowserLayout.slotsOf(layout, '\u0000').isEmpty());
    }

    @Test
    void preservesBindingWarningPrecedenceAndAllowsRepeatedButtonSymbols() {
        assertEquals(BrowserLayout.Binding.DISABLED, BrowserLayout.binding('\u0000', 'C', 'X'));
        assertEquals(BrowserLayout.Binding.RESERVED, BrowserLayout.binding('#', '#', 'X'));
        assertEquals(BrowserLayout.Binding.CONFLICT, BrowserLayout.binding('C', 'C', 'X'));
        assertEquals(BrowserLayout.Binding.CONFLICT, BrowserLayout.binding('X', 'C', 'X'));
        assertEquals(BrowserLayout.Binding.ALLOWED, BrowserLayout.binding('B', 'C', 'X'));
        assertArrayEquals(new String[]{"BB#######", "#########", "#########", "#########", "#########", "#########"},
                BrowserLayout.normalize(List.of("BB"), 'C', 'X', List.of('B', 'B')));
    }
}
