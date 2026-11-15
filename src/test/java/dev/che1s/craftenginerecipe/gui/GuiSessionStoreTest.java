package dev.che1s.craftenginerecipe.gui;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class GuiSessionStoreTest {
    @Test
    void threeMinuteRememberLifetimeExpiresStrictlyAfterTheConfiguredWindow() {
        long lifetime = TimeUnit.MINUTES.toMillis(3);
        long savedAt = 1_000_000L;

        assertFalse(GuiSessionStore.expired(savedAt, lifetime, savedAt + lifetime));
        assertTrue(GuiSessionStore.expired(savedAt, lifetime, savedAt + lifetime + 1));
        assertTrue(GuiSessionStore.expired(0L, lifetime, savedAt));
    }

    @Test
    void sessionSnapshotsRoundTripThroughTheDatabasePayload() {
        GuiSession.Snapshot snapshot = new GuiSession.Snapshot(
                "test:category", 3, "test:parent", true,
                "farmersdelight:cooking", 2, "soup",
                "test:result", 1, false, true,
                List.of(new GuiSession.HistoryPage("test:previous", true, 2, false)),
                4, "鳕鱼");

        GuiSession.Snapshot restored = GuiSessionStore.parseSnapshot(
                GuiSessionStore.snapshotYaml(snapshot));

        assertEquals(snapshot, restored);
    }
}
