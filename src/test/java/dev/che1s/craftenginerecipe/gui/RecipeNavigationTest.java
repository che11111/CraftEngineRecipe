package dev.che1s.craftenginerecipe.gui;

import org.bukkit.event.inventory.ClickType;
import org.junit.jupiter.api.Test;

import java.util.EnumSet;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

class RecipeNavigationTest {
    @Test
    void leftClicksReturnOneLevelAndRightClicksReturnHome() {
        assertEquals(RecipeNavigation.BACK, RecipeNavigation.from(ClickType.LEFT));
        assertEquals(RecipeNavigation.BACK, RecipeNavigation.from(ClickType.SHIFT_LEFT));
        assertEquals(RecipeNavigation.BACK, RecipeNavigation.from(ClickType.WINDOW_BORDER_LEFT));
        assertEquals(RecipeNavigation.HOME, RecipeNavigation.from(ClickType.RIGHT));
        assertEquals(RecipeNavigation.HOME, RecipeNavigation.from(ClickType.SHIFT_RIGHT));
        assertEquals(RecipeNavigation.HOME, RecipeNavigation.from(ClickType.WINDOW_BORDER_RIGHT));
    }

    @Test
    void otherInventoryActionsDoNotTriggerNavigation() {
        var navigationClicks = EnumSet.of(ClickType.LEFT, ClickType.SHIFT_LEFT, ClickType.WINDOW_BORDER_LEFT,
                ClickType.RIGHT, ClickType.SHIFT_RIGHT, ClickType.WINDOW_BORDER_RIGHT);
        for (ClickType click : ClickType.values()) {
            if (!navigationClicks.contains(click)) assertNull(RecipeNavigation.from(click), click.name());
        }
    }
}
