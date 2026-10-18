package dev.leon.cer.scheduler;

import io.papermc.paper.threadedregions.scheduler.AsyncScheduler;
import io.papermc.paper.threadedregions.scheduler.EntityScheduler;
import io.papermc.paper.threadedregions.scheduler.GlobalRegionScheduler;
import io.papermc.paper.threadedregions.scheduler.ScheduledTask;
import org.bukkit.entity.Player;
import org.bukkit.plugin.IllegalPluginAccessException;
import org.bukkit.plugin.Plugin;
import org.junit.jupiter.api.Test;

import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Proxy;
import java.util.ArrayDeque;
import java.util.List;
import java.util.Queue;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.function.Consumer;

import static org.junit.jupiter.api.Assertions.*;

class TaskDispatcherTest {
    private final Queue<Job> global = new ArrayDeque<>();
    private final Queue<Job> async = new ArrayDeque<>();
    private final Queue<Job> entity = new ArrayDeque<>();
    private boolean online = true;
    private boolean retired;
    private boolean reject;
    private boolean ran;
    private Error schedulingError;
    private boolean cancellationFails;
    private int entityCancellations;
    private int globalCancellations;
    private int asyncCancellations;
    private final Plugin plugin = proxy(Plugin.class, (p, m, a) -> {
        throw new AssertionError("Unexpected plugin call: " + m);
    });
    private final ScheduledTask handle = proxy(ScheduledTask.class, (p, m, a) -> {
        return switch (m.getName()) {
            case "cancel" -> {
                entityCancellations++;
                if (cancellationFails) throw new IllegalStateException("cancellation failed");
                yield ScheduledTask.CancelledState.CANCELLED_BY_CALLER;
            }
            case "getExecutionState" -> ScheduledTask.ExecutionState.IDLE;
            default -> throw new AssertionError("Unexpected task call: " + m);
        };
    });
    private final EntityScheduler entityScheduler = proxy(EntityScheduler.class, (p, m, a) -> {
        if (schedulingError != null) throw schedulingError;
        if (reject) throw new IllegalStateException("disabled");
        assertSame(plugin, a[0]);
        if (retired) return null;
        entity.add(new Job((Consumer<ScheduledTask>) a[1], (Runnable) a[2],
                m.getName().equals("runDelayed") ? (long) a[3] : 1));
        return handle;
    });
    private final Player player = proxy(Player.class, (p, m, a) -> switch (m.getName()) {
        case "getScheduler" -> entityScheduler;
        case "isOnline" -> online;
        default -> throw new AssertionError("Unexpected player call: " + m);
    });
    private final TaskDispatcher tasks = new TaskDispatcher(plugin,
            proxy(GlobalRegionScheduler.class, (p, m, a) -> {
                assertSame(plugin, a[0]);
                if (m.getName().equals("cancelTasks")) {
                    globalCancellations++;
                    return null;
                }
                assertTrue(m.getName().equals("run") || m.getName().equals("runDelayed"));
                global.add(new Job((Consumer<ScheduledTask>) a[1], null,
                        m.getName().equals("runDelayed") ? (long) a[2] : 1));
                return handle;
            }),
            proxy(AsyncScheduler.class, (p, m, a) -> {
                assertSame(plugin, a[0]);
                if (m.getName().equals("cancelTasks")) {
                    asyncCancellations++;
                    return null;
                }
                assertEquals("runNow", m.getName());
                async.add(new Job((Consumer<ScheduledTask>) a[1], null, 0));
                return handle;
            }));

    @Test
    void globalAndWorkerJobsUseSeparateSchedulersAndDoNotRunInline() {
        assertSame(handle, tasks.global(() -> ran = true));
        assertSame(handle, tasks.async(() -> ran = true));
        assertFalse(ran);
        assertEquals(1, global.size());
        assertEquals(1, async.size());
        global.remove().action.accept(handle);
        assertTrue(ran);
    }

    @Test
    void viewerWorkUsesEntitySchedulerAndCompletesAfterExecution() {
        var future = tasks.player(player, () -> ran = true);
        assertFalse(future.isDone());
        assertFalse(ran);
        assertTrue(global.isEmpty());
        entity.remove().action.accept(handle);
        future.join();
        assertTrue(ran);
    }

    @Test
    void delayedGlobalWorkKeepsTickDelayAndClampsZeroToOne() {
        assertSame(handle, tasks.globalLater(() -> ran = true, 30));
        Job job = global.remove();
        assertEquals(30, job.ticks);
        assertFalse(ran);
        job.action.accept(handle);
        assertTrue(ran);
        tasks.globalLater(() -> {}, 0);
        assertEquals(1, global.remove().ticks);
    }

    @Test
    void disconnectedViewerIsNotTouchedButCleanupCompletes() {
        var future = tasks.player(player, () -> ran = true);
        online = false;
        entity.remove().action.accept(handle);
        assertTrue(future.isDone());
        assertFalse(ran);
    }

    @Test
    void retirementCallbackReleasesCleanupBarrier() {
        var future = tasks.player(player, () -> ran = true);
        entity.remove().retired.run();
        assertTrue(future.isDone());
        assertFalse(ran);
    }

    @Test
    void alreadyRetiredSchedulerReleasesCleanupBarrier() {
        retired = true;
        assertTrue(tasks.player(player, () -> ran = true).isDone());
        assertFalse(ran);
    }

    @Test
    void entityExceptionsPropagateToTheReloadBarrier() {
        var future = tasks.player(player, () -> { throw new IllegalArgumentException("cleanup failed"); });
        entity.remove().action.accept(handle);
        assertInstanceOf(IllegalArgumentException.class,
                assertThrows(CompletionException.class, future::join).getCause());
    }

    @Test
    @SuppressWarnings("removal")
    void fatalTaskErrorsCompleteTheBarrierAndPropagateToTheScheduler() {
        for (Error fatal : List.of(new OutOfMemoryError("test only"), new ThreadDeath())) {
            var future = tasks.player(player, () -> { throw fatal; });
            assertSame(fatal, assertThrows(Error.class, () -> entity.remove().action.accept(handle)));
            assertSame(fatal, assertThrows(CompletionException.class, future::join).getCause());
        }
    }

    @Test
    void fatalSchedulingErrorIsNotSwallowedOrRetained() {
        schedulingError = new OutOfMemoryError("test only");
        assertSame(schedulingError, assertThrows(OutOfMemoryError.class, () -> tasks.player(player, () -> {})));
        assertSame(schedulingError, assertThrows(OutOfMemoryError.class, () -> tasks.playerLater(player, () -> {}, 1)));
        tasks.shutdown();
        assertEquals(0, entityCancellations);
    }

    @Test
    void schedulerRejectionDoesNotLeavePendingCleanup() {
        reject = true;
        var future = tasks.player(player, () -> ran = true);
        assertTrue(future.isCompletedExceptionally());
        assertFalse(ran);
    }

    @Test
    void twoViewerBarrierWaitsForBothWithoutBlockingCaller() {
        Queue<Job> otherRegion = new ArrayDeque<>();
        EntityScheduler otherScheduler = proxy(EntityScheduler.class, (p, m, a) -> {
            otherRegion.add(new Job((Consumer<ScheduledTask>) a[1], (Runnable) a[2], 1));
            return handle;
        });
        Player otherPlayer = proxy(Player.class, (p, m, a) -> switch (m.getName()) {
            case "getScheduler" -> otherScheduler;
            case "isOnline" -> true;
            default -> throw new AssertionError("Unexpected other-player call: " + m);
        });
        var first = tasks.player(player, () -> {});
        var second = tasks.player(otherPlayer, () -> {});
        var barrier = CompletableFuture.allOf(first, second);
        entity.remove().action.accept(handle);
        assertFalse(barrier.isDone());
        otherRegion.remove().retired.run();
        assertTrue(barrier.isDone());
    }

    @Test
    void delayedActionsKeepTickDelayAndSkipDisconnectedViewers() {
        ScheduledTask delayed = tasks.playerLater(player, () -> ran = true, 30);
        assertSame(plugin, delayed.getOwningPlugin());
        assertFalse(delayed.isRepeatingTask());
        assertEquals(ScheduledTask.ExecutionState.IDLE, delayed.getExecutionState());
        Job job = entity.remove();
        assertEquals(30, job.ticks);
        online = false;
        job.action.accept(handle);
        assertFalse(ran);
        tasks.playerLater(player, () -> ran = true, 0);
        assertEquals(1, entity.remove().ticks);
    }

    @Test
    void shutdownCancelsAllPendingWorkAndReleasesCleanupBarrier() {
        var future = tasks.player(player, () -> ran = true);
        tasks.playerLater(player, () -> ran = true, 30);
        tasks.global(() -> ran = true);
        tasks.globalLater(() -> ran = true, 20);
        tasks.async(() -> ran = true);

        tasks.shutdown();

        assertTrue(future.isDone());
        assertEquals(2, entityCancellations);
        assertEquals(1, globalCancellations);
        assertEquals(1, asyncCancellations);
        entity.forEach(job -> job.action.accept(handle));
        global.forEach(job -> job.action.accept(handle));
        async.forEach(job -> job.action.accept(handle));
        assertFalse(ran);
    }

    @Test
    void completedAndManuallyCancelledEntityWorkIsNotRetainedUntilShutdown() {
        tasks.player(player, () -> {});
        entity.remove().action.accept(handle);
        var delayed = tasks.playerLater(player, () -> ran = true, 30);
        delayed.cancel();
        entity.remove().action.accept(handle);

        tasks.shutdown();

        assertEquals(1, entityCancellations);
        assertFalse(ran);
    }

    @Test
    void cancellationFailureStillReleasesAllCleanupBarriersAndCancelsOtherSchedulers() {
        var first = tasks.player(player, () -> ran = true);
        var second = tasks.player(player, () -> ran = true);
        cancellationFails = true;

        tasks.shutdown();

        assertTrue(first.isDone());
        assertTrue(second.isDone());
        assertEquals(2, entityCancellations);
        assertEquals(1, globalCancellations);
        assertEquals(1, asyncCancellations);
        entity.forEach(job -> job.action.accept(handle));
        assertFalse(ran);
    }

    @Test
    void stoppedDispatcherRejectsNewJobsAndShutdownIsIdempotent() {
        tasks.shutdown();
        tasks.shutdown();

        assertThrows(IllegalPluginAccessException.class, () -> tasks.global(() -> ran = true));
        assertThrows(IllegalPluginAccessException.class, () -> tasks.globalLater(() -> ran = true, 1));
        assertThrows(IllegalPluginAccessException.class, () -> tasks.async(() -> ran = true));
        assertThrows(IllegalPluginAccessException.class, () -> tasks.playerLater(player, () -> ran = true, 1));
        assertTrue(tasks.player(player, () -> ran = true).isDone());
        assertTrue(entity.isEmpty());
        assertEquals(1, globalCancellations);
        assertEquals(1, asyncCancellations);
        assertFalse(ran);
    }

    @SuppressWarnings("unchecked")
    private static <T> T proxy(Class<T> type, InvocationHandler handler) {
        return (T) Proxy.newProxyInstance(type.getClassLoader(), new Class<?>[]{type}, handler);
    }

    private record Job(Consumer<ScheduledTask> action, Runnable retired, long ticks) {}
}
