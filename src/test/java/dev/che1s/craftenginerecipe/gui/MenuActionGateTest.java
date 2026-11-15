package dev.che1s.craftenginerecipe.gui;

import org.junit.jupiter.api.Test;

import java.util.ArrayDeque;
import java.util.Queue;
import java.util.UUID;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.BooleanSupplier;
import java.util.function.Predicate;

import static org.junit.jupiter.api.Assertions.*;

class MenuActionGateTest {
    private final MenuActionGate<UUID> gate = new MenuActionGate<>();
    private final UUID player = UUID.randomUUID();
    private final Object window = new Object();
    private final AtomicReference<Object> currentWindow = new AtomicReference<>(this.window);
    private final AtomicLong generation = new AtomicLong(7);
    private final AtomicInteger ran = new AtomicInteger();
    private final Queue<Runnable> scheduled = new ArrayDeque<>();
    private final Predicate<Runnable> scheduler = task -> this.scheduled.offer(task);

    private boolean offer(UUID viewer, Runnable action, BooleanSupplier ready) {
        return this.gate.offer(viewer, this.window, 7, action, this.scheduler,
                this.currentWindow::get, this.generation::get, ready);
    }

    private boolean offer() { return this.offer(this.player, this.ran::incrementAndGet, () -> true); }

    @Test
    void repeatedClicksCoalesceAndNextTickCanAcceptAnotherAction() {
        assertTrue(this.offer());
        assertFalse(this.offer());
        assertEquals(1, this.scheduled.size());
        assertEquals(1, this.gate.pendingCount());
        assertEquals(0, this.ran.get());

        this.scheduled.remove().run();
        assertEquals(1, this.ran.get());
        assertEquals(0, this.gate.pendingCount());
        assertTrue(this.offer());
        this.scheduled.remove().run();
        assertEquals(2, this.ran.get());
    }

    @Test
    void replacementWindowDiscardsOldAction() {
        assertTrue(this.offer());
        this.currentWindow.set(new Object());
        this.scheduled.remove().run();
        assertEquals(0, this.ran.get());
        assertEquals(0, this.gate.pendingCount());
    }

    @Test
    void windowComparisonUsesIdentityEvenWhenWindowsAreEqual() {
        Object expected = new String("window");
        Object replacement = new String("window");
        assertNotSame(expected, replacement);
        assertEquals(expected, replacement);
        assertTrue(this.gate.offer(this.player, expected, 7, this.ran::incrementAndGet,
                this.scheduler, () -> replacement, this.generation::get, () -> true));
        this.scheduled.remove().run();
        assertEquals(0, this.ran.get());
    }

    @Test
    void dataReloadDiscardsOldAction() {
        assertTrue(this.offer());
        this.generation.incrementAndGet();
        this.scheduled.remove().run();
        assertEquals(0, this.ran.get());
        assertEquals(0, this.gate.pendingCount());
    }

    @Test
    void readinessOrDisconnectDiscardsOldAction() {
        assertTrue(this.offer(this.player, this.ran::incrementAndGet, () -> false));
        this.scheduled.remove().run();
        assertEquals(0, this.ran.get());
        assertEquals(0, this.gate.pendingCount());
    }

    @Test
    void quitCancellationDoesNotAffectOtherPlayers() {
        UUID other = UUID.randomUUID();
        assertTrue(this.offer());
        assertTrue(this.offer(other, this.ran::incrementAndGet, () -> true));
        this.gate.cancel(this.player);
        this.scheduled.remove().run();
        assertEquals(0, this.ran.get());
        assertEquals(1, this.gate.pendingCount());
        this.scheduled.remove().run();
        assertEquals(1, this.ran.get());
        assertEquals(0, this.gate.pendingCount());
    }

    @Test
    void canceledCallbackCannotRemoveReplacementTicket() {
        assertTrue(this.offer());
        this.gate.cancel(this.player);
        assertTrue(this.offer());
        this.scheduled.remove().run();
        assertEquals(0, this.ran.get());
        assertEquals(1, this.gate.pendingCount());
        this.scheduled.remove().run();
        assertEquals(1, this.ran.get());
    }

    @Test
    void reloadClearInvalidatesAllPendingCallbacks() {
        assertTrue(this.offer());
        assertTrue(this.offer(UUID.randomUUID(), this.ran::incrementAndGet, () -> true));
        this.gate.clear();
        this.scheduled.forEach(Runnable::run);
        assertEquals(0, this.ran.get());
        assertEquals(0, this.gate.pendingCount());
        assertTrue(this.offer());
    }

    @Test
    void schedulerRejectionReleasesTicketAndInvalidatesRetainedCallback() {
        assertFalse(this.gate.offer(this.player, this.window, 7, this.ran::incrementAndGet,
                task -> { this.scheduled.offer(task); return false; },
                this.currentWindow::get, this.generation::get, () -> true));
        assertEquals(0, this.gate.pendingCount());
        assertTrue(this.offer());
        this.scheduled.remove().run();
        assertEquals(0, this.ran.get());
        assertEquals(1, this.gate.pendingCount());
        this.scheduled.remove().run();
        assertEquals(1, this.ran.get());
    }

    @Test
    void schedulerExceptionPropagatesWithoutLeavingPendingAction() {
        RejectedExecutionException error = new RejectedExecutionException("scheduler stopped");
        assertSame(error, assertThrows(RejectedExecutionException.class,
                () -> this.gate.offer(this.player, this.window, 7, this.ran::incrementAndGet,
                        task -> { throw error; }, this.currentWindow::get, this.generation::get, () -> true)));
        assertEquals(0, this.gate.pendingCount());
        assertTrue(this.offer());
    }

    @Test
    void actionExceptionPropagatesAfterTicketIsReleased() {
        IllegalStateException error = new IllegalStateException("action failed");
        assertTrue(this.offer(this.player, () -> { throw error; }, () -> true));
        assertSame(error, assertThrows(IllegalStateException.class, this.scheduled.remove()::run));
        assertEquals(0, this.gate.pendingCount());
        assertTrue(this.offer());
    }

    @Test
    void guardExceptionPropagatesAfterTicketIsReleased() {
        IllegalStateException error = new IllegalStateException("readiness failed");
        assertTrue(this.offer(this.player, this.ran::incrementAndGet, () -> { throw error; }));
        assertSame(error, assertThrows(IllegalStateException.class, this.scheduled.remove()::run));
        assertEquals(0, this.gate.pendingCount());
        assertTrue(this.offer());
    }

    @Test
    void runningActionCanScheduleAnotherActionForSamePlayer() {
        assertTrue(this.offer(this.player, () -> {
            assertEquals(0, this.gate.pendingCount());
            assertTrue(this.offer());
        }, () -> true));
        this.scheduled.remove().run();
        assertEquals(1, this.gate.pendingCount());
        this.scheduled.remove().run();
        assertEquals(1, this.ran.get());
    }
}
