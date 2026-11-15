package dev.che1s.craftenginerecipe.gui;

import org.junit.jupiter.api.Test;

import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class MenuLifecycleTest {
    @Test
    void directQueryDoesNotRequestEntrySoundButStillOwnsItsEventualClose() {
        MenuLifecycle lifecycle = new MenuLifecycle();
        UUID player = UUID.randomUUID();
        Object recipe = new Object();

        assertFalse(lifecycle.opened(player, recipe));
        assertTrue(lifecycle.contains(player));
        assertTrue(lifecycle.closed(player, recipe));
        assertFalse(lifecycle.closed(player, recipe));
    }

    @Test
    void delayedOldCloseCannotCloseAQueriedRecipeOrItsRotationReplacement() {
        MenuLifecycle lifecycle = new MenuLifecycle();
        UUID player = UUID.randomUUID();
        Object browser = new Object();
        lifecycle.requestEntry(player);
        assertTrue(lifecycle.opened(player, browser));

        Object queriedRecipe = new Object();
        assertFalse(lifecycle.opened(player, queriedRecipe));
        assertFalse(lifecycle.closed(player, browser));
        assertTrue(lifecycle.isCurrent(player, queriedRecipe));

        Object rotatedRecipe = new Object();
        assertFalse(lifecycle.opened(player, rotatedRecipe));
        assertFalse(lifecycle.closed(player, queriedRecipe));
        assertTrue(lifecycle.isCurrent(player, rotatedRecipe));
        assertTrue(lifecycle.closed(player, rotatedRecipe));
    }

    @Test
    void abandonedEntryRequestCannotLeakAnOpenSoundIntoALaterQuery() {
        MenuLifecycle lifecycle = new MenuLifecycle();
        UUID player = UUID.randomUUID();
        lifecycle.requestEntry(player);
        lifecycle.cancelEntry(player);

        assertFalse(lifecycle.opened(player, new Object()));
    }

    @Test
    void nativeRecipeHandoffAndReturnStayInsideOneVisit() {
        MenuLifecycle lifecycle = new MenuLifecycle();
        UUID player = UUID.randomUUID();
        Object browser = new Object();
        Object nativeRecipe = new Object();
        Object returnedBrowser = new Object();
        lifecycle.requestEntry(player);
        assertTrue(lifecycle.opened(player, browser));

        assertFalse(lifecycle.opened(player, nativeRecipe));
        assertFalse(lifecycle.closed(player, browser));
        assertTrue(lifecycle.isCurrent(player, nativeRecipe));

        assertFalse(lifecycle.opened(player, returnedBrowser));
        assertFalse(lifecycle.closed(player, nativeRecipe));
        assertFalse(lifecycle.isCurrent(player, nativeRecipe));
        assertTrue(lifecycle.closed(player, returnedBrowser));
    }

    @Test
    void reloadOrQuitRevokesNativeReturnCallbacksEvenAfterANewVisitStarts() {
        MenuLifecycle lifecycle = new MenuLifecycle();
        UUID player = UUID.randomUUID();
        Object nativeRecipe = new Object();
        lifecycle.opened(player, nativeRecipe);
        lifecycle.forget(player);

        assertFalse(lifecycle.isCurrent(player, nativeRecipe));
        Object replacement = new Object();
        lifecycle.opened(player, replacement);
        assertFalse(lifecycle.isCurrent(player, nativeRecipe));
        assertFalse(lifecycle.closed(player, nativeRecipe));
        assertTrue(lifecycle.isCurrent(player, replacement));
    }

    @Test
    void nativeRecipeClosingDoesNotRequestAnotherEntryWhenAnExitCallbackArrivesLate() {
        MenuLifecycle lifecycle = new MenuLifecycle();
        UUID player = UUID.randomUUID();
        Object nativeRecipe = new Object();
        lifecycle.opened(player, nativeRecipe);

        assertTrue(lifecycle.closed(player, nativeRecipe));
        assertFalse(lifecycle.isCurrent(player, nativeRecipe));
        assertFalse(lifecycle.closed(player, nativeRecipe));
        assertFalse(lifecycle.contains(player));
    }

    @Test
    void disconnectForgetsPendingEntryAndExitWithoutAffectingOtherViewers() {
        MenuLifecycle lifecycle = new MenuLifecycle();
        UUID player = UUID.randomUUID();
        UUID otherPlayer = UUID.randomUUID();
        Object recipe = new Object();
        Object otherRecipe = new Object();
        lifecycle.opened(player, recipe);
        lifecycle.opened(otherPlayer, otherRecipe);
        lifecycle.requestEntry(player);

        lifecycle.forget(player);

        assertFalse(lifecycle.closed(player, recipe));
        assertFalse(lifecycle.opened(player, new Object()));
        assertTrue(lifecycle.closed(otherPlayer, otherRecipe));
    }
}
