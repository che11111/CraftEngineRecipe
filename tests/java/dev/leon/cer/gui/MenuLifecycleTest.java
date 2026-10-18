package dev.leon.cer.gui;

import org.junit.jupiter.api.Test;

import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executors;
import java.util.stream.IntStream;

import static org.junit.jupiter.api.Assertions.*;

class MenuLifecycleTest {
    private final MenuLifecycle lifecycle = new MenuLifecycle();
    private final UUID player = UUID.randomUUID();

    @Test
    void independentRegionThreadsCanReplaceAndCloseWindowsConcurrently() {
        var players = IntStream.range(0, 32).mapToObj(ignored -> UUID.randomUUID()).toList();
        try (var executor = Executors.newFixedThreadPool(8)) {
            var jobs = players.stream().map(id -> CompletableFuture.runAsync(() -> {
                for (int i = 0; i < 300; i++) {
                    Object oldWindow = new Object(), nextWindow = new Object();
                    lifecycle.requestEntry(id);
                    assertTrue(lifecycle.opened(id, oldWindow));
                    assertFalse(lifecycle.opened(id, nextWindow));
                    assertFalse(lifecycle.closed(id, oldWindow));
                    assertTrue(lifecycle.isCurrent(id, nextWindow));
                    assertTrue(lifecycle.closed(id, nextWindow));
                }
            }, executor)).toArray(CompletableFuture[]::new);
            CompletableFuture.allOf(jobs).join();
        }
        players.forEach(id -> assertFalse(lifecycle.contains(id)));
    }

    @Test
    void explicitEntryPlaysOpenOnceAndInternalWindowsStaySilent() {
        lifecycle.requestEntry(player);
        assertTrue(lifecycle.opened(player, new Object()));
        assertFalse(lifecycle.opened(player, new Object()));
        assertFalse(lifecycle.opened(player, new Object()));
    }

    @Test
    void replacementBeforeDeferredCloseKeepsNewWindowOpen() {
        Object browser = new Object(), recipe = new Object(), rotatedRecipe = new Object();
        lifecycle.opened(player, browser);
        assertTrue(lifecycle.isCurrent(player, browser));
        lifecycle.opened(player, recipe);
        assertFalse(lifecycle.closed(player, browser));
        lifecycle.opened(player, rotatedRecipe);
        assertFalse(lifecycle.closed(player, recipe));
        assertTrue(lifecycle.isCurrent(player, rotatedRecipe));
        assertTrue(lifecycle.closed(player, rotatedRecipe));
    }

    @Test
    void finalCloseIsConsumedOnlyOnce() {
        Object window = new Object();
        lifecycle.opened(player, window);
        assertTrue(lifecycle.closed(player, window));
        assertFalse(lifecycle.closed(player, window));
        assertFalse(lifecycle.contains(player));
    }

    @Test
    void rapidReentryIgnoresCloseFromPreviousVisit() {
        Object oldWindow = new Object(), newWindow = new Object();
        lifecycle.requestEntry(player);
        assertTrue(lifecycle.opened(player, oldWindow));
        lifecycle.requestEntry(player);
        assertTrue(lifecycle.opened(player, newWindow));
        assertFalse(lifecycle.closed(player, oldWindow));
        assertTrue(lifecycle.closed(player, newWindow));
    }

    @Test
    void failedEntryDoesNotLeaveOpenSoundPending() {
        lifecycle.requestEntry(player);
        lifecycle.cancelEntry(player);
        assertFalse(lifecycle.opened(player, new Object()));
    }

    @Test
    void playersHaveIndependentEntriesAndCloses() {
        UUID other = UUID.randomUUID();
        Object first = new Object(), second = new Object();
        lifecycle.requestEntry(player);
        assertFalse(lifecycle.opened(other, second));
        assertTrue(lifecycle.opened(player, first));
        assertFalse(lifecycle.closed(other, first));
        assertTrue(lifecycle.closed(player, first));
        assertTrue(lifecycle.contains(other));
    }

    @Test
    void disconnectSearchTransferAndShutdownDiscardCallbacksAndEntries() {
        Object window = new Object();
        lifecycle.opened(player, window);
        lifecycle.requestEntry(player);
        lifecycle.forget(player);
        assertFalse(lifecycle.closed(player, window));
        assertFalse(lifecycle.opened(player, window));
        lifecycle.requestEntry(player);
        lifecycle.clear();
        assertFalse(lifecycle.contains(player));
        assertFalse(lifecycle.closed(player, window));
        assertFalse(lifecycle.opened(player, new Object()));
    }
}
