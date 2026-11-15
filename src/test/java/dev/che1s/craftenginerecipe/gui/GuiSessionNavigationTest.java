package dev.che1s.craftenginerecipe.gui;

import dev.che1s.craftenginerecipe.recipe.WorkstationRecipes;
import dev.che1s.craftenginerecipe.recipe.RecipeEntry;
import net.momirealms.craftengine.core.item.recipe.RecipeType;
import net.momirealms.craftengine.core.util.Key;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

class GuiSessionNavigationTest {
    private static final Key CATEGORY = Key.of("test:child");
    private static final Key PARENT = Key.of("test:parent");
    private static final Key ITEM = Key.of("test:result");

    @Test
    void enteringWorkstationPreservesCraftEngineNavigation() throws ReflectiveOperationException {
        GuiSession session = newSession();
        session.enter(CATEGORY, 3, PARENT);
        session.unapplied(true);

        session.workstation(RecipeType.SMELTING, 2, "iron");

        assertCategoryNavigation(session, CATEGORY, 3, PARENT);
        assertEquals(RecipeType.SMELTING, session.workstation());
        assertEquals(2, session.workstationPage());
        assertEquals("iron", session.workstationKeyword());
        assertTrue(session.unapplied());
    }

    @Test
    void recipeSelectionPreservesWorkstationPageAndSearch() throws ReflectiveOperationException {
        GuiSession session = newSession();
        session.enter(CATEGORY, 3, PARENT);
        session.workstation(RecipeType.STONECUTTING, 2, "stone");
        session.workstationPage(4);
        session.recipeContext(ITEM, 1, false);
        WorkstationRecipes.Output selection = new WorkstationRecipes.Output(ITEM, List.of());

        session.recipeSelection(selection, 42L);

        assertSame(selection, session.recipeSelection());
        assertEquals(42L, session.recipeGeneration());
        assertEquals(RecipeType.STONECUTTING, session.workstation());
        assertEquals(4, session.workstationPage());
        assertEquals("stone", session.workstationKeyword());
        assertCategoryNavigation(session, CATEGORY, 3, PARENT);
        assertEquals(ITEM, session.recipeItem());
        assertEquals(1, session.recipeIndex());
        assertFalse(session.recipeUsageMode());
    }

    @Test
    void enteringCategoryClearsWorkstationAndSelection() throws ReflectiveOperationException {
        GuiSession session = workstationSession();
        Key nextCategory = Key.of("test:next");

        session.enter(nextCategory, 5, CATEGORY);

        assertWorkstationCleared(session);
        assertNull(session.recipeItem());
        assertNull(session.popRecipePage());
        assertCategoryNavigation(session, nextCategory, 5, CATEGORY);
    }

    @Test
    void generalRecipeQueryClearsSelectionAndRetainsTheBrowserReturnContext()
            throws ReflectiveOperationException {
        GuiSession session = workstationSession();
        Key otherItem = Key.of("test:ingredient");

        session.recipeContext(otherItem, 7, true);

        assertNull(session.recipeSelection());
        assertEquals(otherItem, session.recipeItem());
        assertEquals(7, session.recipeIndex());
        assertTrue(session.recipeUsageMode());
        assertEquals(RecipeType.SMITHING, session.workstation());
        assertEquals(2, session.workstationPage());
        assertEquals("armor", session.workstationKeyword());
        assertCategoryNavigation(session, CATEGORY, 3, PARENT);
    }

    @Test
    void leavingWorkstationsRestoresTheOriginalCategoryNavigation()
            throws ReflectiveOperationException {
        GuiSession session = workstationSession();

        session.leaveWorkstations();

        assertWorkstationCleared(session);
        assertNull(session.recipeItem());
        assertNull(session.popRecipePage());
        assertCategoryNavigation(session, CATEGORY, 3, PARENT);
        assertEquals(PARENT, session.exit());
        assertEquals(PARENT, session.currentCategory());
        assertNull(session.parentCategory());
    }

    @Test
    void recipeHistoryReturnsThroughEachIngredientQueryInReverseOrder()
            throws ReflectiveOperationException {
        GuiSession session = newSession();
        Key ingredient = Key.of("test:ingredient");
        session.recipeContext(ITEM, 2, false);
        session.pushRecipePage();
        session.recipeContext(ingredient, 4, true);
        session.pushRecipePage();
        session.recipeContext(Key.of("test:nested_ingredient"), 0, false);

        GuiSession.RecipePage previous = session.popRecipePage();
        assertEquals(ingredient, previous.item());
        assertEquals(4, previous.index());
        assertTrue(previous.usageMode());
        previous = session.popRecipePage();
        assertEquals(ITEM, previous.item());
        assertEquals(2, previous.index());
        assertFalse(previous.usageMode());
        assertNull(session.popRecipePage());
    }

    @Test
    void itemSearchSurvivesRecipeNavigationAndSessionSnapshot()
            throws ReflectiveOperationException {
        GuiSession session = newSession();
        session.itemSearchKeyword("鳕鱼");
        session.recipeContext(ITEM, 1, false);

        assertEquals("鳕鱼", session.snapshot().itemSearchKeyword());

        GuiSession restored = newSession();
        restored.restore(session.snapshot(), item -> null, 1L);

        assertEquals("鳕鱼", restored.itemSearchKeyword());
        assertEquals(ITEM, restored.recipeItem());
        assertEquals(1, restored.recipeIndex());
    }

    @Test
    void openingAWorkstationClearsTheGlobalItemSearchContext()
            throws ReflectiveOperationException {
        GuiSession session = newSession();
        session.itemSearchKeyword("fish");

        session.workstation(RecipeType.CAMPFIRE_COOKING, 0, null);

        assertNull(session.itemSearchKeyword());
        assertEquals("minecraft:campfire_cooking", session.workstationId());
    }

    @Test
    void capturedHistoryKeepsWorkstationSelectionAfterTheNextQueryClearsIt()
            throws ReflectiveOperationException {
        GuiSession session = newSession();
        RecipeEntry.Output selection = new RecipeEntry.Output(ITEM, List.of());
        session.workstation("farmersdelight:cooking", 3, "soup");
        session.recipeContext(ITEM, 2, false);
        session.entrySelection(selection, 52L);
        GuiSession.RecipePage previous = session.recipePage();

        session.recipeContext(Key.of("test:ingredient"), 0, true);
        session.pushRecipePage(previous);

        assertNull(session.entrySelection());
        GuiSession.RecipePage restored = session.popRecipePage();
        assertSame(selection, restored.selection());
        assertEquals(52L, restored.generation());
        assertEquals(ITEM, restored.item());
        assertEquals(2, restored.index());
        assertEquals("farmersdelight:cooking", session.workstationId());
        assertEquals(3, session.workstationPage());
        assertEquals("soup", session.workstationKeyword());
    }

    @Test
    void missingAndRepeatedHistoryPagesDoNotAddExtraBackSteps()
            throws ReflectiveOperationException {
        GuiSession session = newSession();
        session.pushRecipePage();
        session.pushRecipePage(null);
        assertNull(session.popRecipePage());

        session.recipeContext(ITEM, 1, false);
        session.pushRecipePage();
        session.pushRecipePage();

        assertEquals(ITEM, session.popRecipePage().item());
        assertNull(session.popRecipePage());
    }

    @Test
    void returningHomeOrOpeningAnotherCategoryRemovesStaleRecipeHistory()
            throws ReflectiveOperationException {
        GuiSession session = newSession();
        session.recipeContext(ITEM, 0, false);
        session.pushRecipePage();

        session.clearRecipeHistory();
        assertNull(session.popRecipePage());

        session.pushRecipePage();
        session.enter(CATEGORY, 0, PARENT);
        assertNull(session.popRecipePage());
    }

    @Test
    void longBrowsingSessionsRetainOnlyTheMostRecent128Pages()
            throws ReflectiveOperationException {
        GuiSession session = newSession();
        for (int i = 0; i < 140; i++) {
            session.recipeContext(Key.of("test:item_" + i), i, false);
            session.pushRecipePage();
        }

        for (int i = 139; i >= 12; i--) {
            GuiSession.RecipePage previous = session.popRecipePage();
            assertEquals(Key.of("test:item_" + i), previous.item());
            assertEquals(i, previous.index());
        }
        assertNull(session.popRecipePage());
    }

    @Test
    void snapshotRestoresCategoryWorkstationAndCurrentRecipePage()
            throws ReflectiveOperationException {
        GuiSession source = newSession();
        Key previousItem = Key.of("test:previous");
        RecipeEntry.Output previousSelection = new RecipeEntry.Output(previousItem, List.of());
        RecipeEntry.Output currentSelection = new RecipeEntry.Output(ITEM, List.of());
        source.enter(CATEGORY, 3, PARENT, 4);
        source.workstation("minecraft:smithing", 2, "armor");
        source.recipeContext(previousItem, 1, true);
        source.entrySelection(previousSelection, 41L);
        source.pushRecipePage();
        source.recipeContext(ITEM, 0, false);
        source.entrySelection(currentSelection, 42L);
        GuiSession.Snapshot snapshot = source.snapshot();
        GuiSession restored = newSession();

        restored.restore(snapshot, item -> item.equals(ITEM.toString()) ? currentSelection : previousSelection, 52L);

        assertEquals(CATEGORY, restored.currentCategory());
        assertEquals(3, restored.categoryPage());
        assertEquals(4, restored.contentPage());
        assertEquals(PARENT, restored.parentCategory());
        assertEquals("minecraft:smithing", restored.workstationId());
        assertEquals(2, restored.workstationPage());
        assertEquals("armor", restored.workstationKeyword());
        assertEquals(ITEM, restored.recipeItem());
        assertEquals(0, restored.recipeIndex());
        assertFalse(restored.recipeUsageMode());
        assertSame(currentSelection, restored.entrySelection());
        assertEquals(52L, restored.recipeGeneration());
        GuiSession.RecipePage previous = restored.popRecipePage();
        assertEquals(previousItem, previous.item());
        assertTrue(previous.usageMode());
        assertEquals(1, previous.index());
        assertSame(previousSelection, previous.selection());
        assertEquals(52L, previous.generation());
    }

    private static GuiSession workstationSession() throws ReflectiveOperationException {
        GuiSession session = newSession();
        session.enter(CATEGORY, 3, PARENT);
        session.workstation(RecipeType.SMITHING, 2, "armor");
        session.recipeContext(ITEM, 0, false);
        session.recipeSelection(new WorkstationRecipes.Output(ITEM, List.of()), 42L);
        return session;
    }

    private static GuiSession newSession() throws ReflectiveOperationException {
        var constructor = GuiSession.class.getDeclaredConstructor();
        constructor.setAccessible(true);
        return constructor.newInstance();
    }

    private static void assertCategoryNavigation(GuiSession session, Key category, int page, Key parent) {
        assertEquals(category, session.currentCategory());
        assertEquals(page, session.categoryPage());
        assertEquals(parent, session.parentCategory());
    }

    private static void assertWorkstationCleared(GuiSession session) {
        assertNull(session.workstation());
        assertEquals(0, session.workstationPage());
        assertNull(session.workstationKeyword());
        assertNull(session.recipeSelection());
    }
}
