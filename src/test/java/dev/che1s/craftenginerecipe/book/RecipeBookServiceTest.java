package dev.che1s.craftenginerecipe.book;

import dev.che1s.craftenginerecipe.preferences.PlayerPreferences;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class RecipeBookServiceTest {
    @Test
    void automaticNonSneakActionsFollowOffhandState() {
        assertEquals(PlayerPreferences.BookAction.ITEM_PAGE,
                RecipeBookService.resolveAutomaticAction(
                        PlayerPreferences.BookAction.AUTO_OFFHAND_LEFT, true));
        assertEquals(PlayerPreferences.BookAction.OFFHAND_RECIPE,
                RecipeBookService.resolveAutomaticAction(
                        PlayerPreferences.BookAction.AUTO_OFFHAND_LEFT, false));
        assertEquals(PlayerPreferences.BookAction.TABLE_PAGE,
                RecipeBookService.resolveAutomaticAction(
                        PlayerPreferences.BookAction.AUTO_OFFHAND_RIGHT, true));
        assertEquals(PlayerPreferences.BookAction.OFFHAND_USAGE,
                RecipeBookService.resolveAutomaticAction(
                        PlayerPreferences.BookAction.AUTO_OFFHAND_RIGHT, false));
    }

    @Test
    void explicitBindingsRemainUnchanged() {
        assertEquals(PlayerPreferences.BookAction.SEARCH_PAGE,
                RecipeBookService.resolveAutomaticAction(
                        PlayerPreferences.BookAction.SEARCH_PAGE, true));
        assertEquals(PlayerPreferences.BookAction.PERSONALIZATION,
                RecipeBookService.resolveAutomaticAction(
                        PlayerPreferences.BookAction.PERSONALIZATION, false));
    }
}
