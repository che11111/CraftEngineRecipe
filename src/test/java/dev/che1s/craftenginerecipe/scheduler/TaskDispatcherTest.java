package dev.che1s.craftenginerecipe.scheduler;

import io.papermc.paper.threadedregions.scheduler.AsyncScheduler;
import io.papermc.paper.threadedregions.scheduler.GlobalRegionScheduler;
import io.papermc.paper.threadedregions.scheduler.ScheduledTask;
import org.bukkit.plugin.IllegalPluginAccessException;
import org.bukkit.plugin.Plugin;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Proxy;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class TaskDispatcherTest {
    @Test
    void laterRunsOnceOffTheAsyncSchedulerWithoutCreatingARepeatingTimer() {
        Harness harness = new Harness();
        AtomicInteger executions = new AtomicInteger();

        ScheduledTask task = harness.dispatcher.asyncLater(executions::incrementAndGet, 0, TimeUnit.SECONDS);

        assertEquals(0, executions.get());
        assertEquals(1, harness.asyncWork.size());
        assertTrue(harness.globalWork.isEmpty());
        assertSame(harness.plugin, harness.laterOwner);
        assertEquals(1, harness.laterDelay);
        assertSame(TimeUnit.SECONDS, harness.laterUnit);
        assertFalse(task.isRepeatingTask());
        assertSame(harness.asyncWork.getFirst().task(), task);
        harness.asyncWork.getFirst().fire();
        assertEquals(1, executions.get());
    }

    @Test
    void timerUsesAsyncSchedulerAndDoesNotExecuteUntilTheScheduledCallback() {
        Harness harness = new Harness();
        AtomicInteger executions = new AtomicInteger();

        ScheduledTask task = harness.dispatcher.asyncTimer(executions::incrementAndGet, 5, 21_600, TimeUnit.SECONDS);

        assertEquals(0, executions.get());
        assertEquals(1, harness.asyncWork.size());
        assertTrue(harness.globalWork.isEmpty());
        assertSame(harness.plugin, harness.timerOwner);
        assertEquals(5, harness.timerInitialDelay);
        assertEquals(21_600, harness.timerPeriod);
        assertSame(TimeUnit.SECONDS, harness.timerUnit);
        assertSame(harness.asyncWork.getFirst().task(), task);
        harness.asyncWork.getFirst().fire();
        harness.asyncWork.getFirst().fire();
        assertEquals(2, executions.get());
    }

    @Test
    void timerClampsNonPositiveIntervalsAndReturnsItsPlatformCancellationHandle() {
        Harness harness = new Harness();
        ScheduledTask timer = harness.dispatcher.asyncTimer(() -> {}, 0, -3, TimeUnit.MINUTES);

        assertEquals(1, harness.timerInitialDelay);
        assertEquals(1, harness.timerPeriod);
        assertSame(TimeUnit.MINUTES, harness.timerUnit);
        assertFalse(harness.asyncWork.getFirst().task().cancelled);
        timer.cancel();
        assertTrue(harness.asyncWork.getFirst().task().cancelled);
    }

    @Test
    void shutdownCancelsBothSchedulersOnceAndIgnoresTheirLateCallbacks() {
        Harness harness = new Harness();
        AtomicInteger executions = new AtomicInteger();
        harness.dispatcher.asyncTimer(executions::incrementAndGet, 5, 21_600, TimeUnit.SECONDS);
        harness.dispatcher.async(executions::incrementAndGet);
        harness.dispatcher.global(executions::incrementAndGet);
        harness.dispatcher.globalLater(executions::incrementAndGet, 1);

        harness.dispatcher.shutdown();
        harness.dispatcher.shutdown();

        assertEquals(1, harness.asyncCancellations);
        assertEquals(1, harness.globalCancellations);
        assertTrue(harness.asyncWork.stream().allMatch(work -> work.task().cancelled));
        assertTrue(harness.globalWork.stream().allMatch(work -> work.task().cancelled));
        // 模拟取消生效前已经提交给平台的回调。
        harness.asyncWork.forEach(Work::fire);
        harness.globalWork.forEach(Work::fire);
        assertEquals(0, executions.get());
    }

    @Test
    void shutdownRejectsNewWorkAndCannotLeaveAnUpdateResultQueued() {
        Harness harness = new Harness();
        AtomicInteger results = new AtomicInteger();
        harness.dispatcher.asyncTimer(
                () -> harness.dispatcher.global(results::incrementAndGet), 5, 21_600, TimeUnit.SECONDS);
        harness.asyncWork.getFirst().fire();
        assertEquals(1, harness.globalWork.size());

        harness.dispatcher.shutdown();
        harness.globalWork.getFirst().fire();

        assertEquals(0, results.get());
        assertThrows(IllegalPluginAccessException.class,
                () -> harness.dispatcher.asyncTimer(() -> {}, 1, 1, TimeUnit.SECONDS));
        assertThrows(IllegalPluginAccessException.class,
                () -> harness.dispatcher.asyncLater(() -> {}, 1, TimeUnit.SECONDS));
        assertThrows(IllegalPluginAccessException.class, () -> harness.dispatcher.async(() -> {}));
        assertThrows(IllegalPluginAccessException.class, () -> harness.dispatcher.global(() -> {}));
        assertThrows(IllegalPluginAccessException.class, () -> harness.dispatcher.globalLater(() -> {}, 1));
        assertEquals(1, harness.asyncWork.size());
        assertEquals(1, harness.globalWork.size());
    }

    private static final class Harness {
        private final Plugin plugin = proxy(Plugin.class, (ignored, method, args) -> switch (method.getName()) {
            case "getName" -> "CraftEngineRecipe";
            case "isEnabled" -> true;
            case "toString" -> "CraftEngineRecipe test plugin";
            default -> throw new AssertionError("Unexpected plugin method: " + method.getName());
        });
        private final List<Work> asyncWork = new ArrayList<>();
        private final List<Work> globalWork = new ArrayList<>();
        private int asyncCancellations;
        private int globalCancellations;
        private Plugin timerOwner;
        private long timerInitialDelay;
        private long timerPeriod;
        private TimeUnit timerUnit;
        private Plugin laterOwner;
        private long laterDelay;
        private TimeUnit laterUnit;
        private final AsyncScheduler async = proxy(AsyncScheduler.class, (ignored, method, args) -> {
            if (method.getName().equals("cancelTasks")) {
                assertSame(plugin, args[0]);
                asyncCancellations++;
                asyncWork.forEach(work -> work.task().cancel());
                return null;
            }
            assertTrue(method.getName().equals("runNow")
                    || method.getName().equals("runDelayed")
                    || method.getName().equals("runAtFixedRate"));
            boolean repeating = method.getName().equals("runAtFixedRate");
            if (repeating) {
                timerOwner = (Plugin) args[0];
                timerInitialDelay = (long) args[2];
                timerPeriod = (long) args[3];
                timerUnit = (TimeUnit) args[4];
            } else if (method.getName().equals("runDelayed")) {
                laterOwner = (Plugin) args[0];
                laterDelay = (long) args[2];
                laterUnit = (TimeUnit) args[3];
            }
            return addWork(asyncWork, args, repeating);
        });
        private final GlobalRegionScheduler global = proxy(GlobalRegionScheduler.class, (ignored, method, args) -> {
            if (method.getName().equals("cancelTasks")) {
                assertSame(plugin, args[0]);
                globalCancellations++;
                globalWork.forEach(work -> work.task().cancel());
                return null;
            }
            assertTrue(method.getName().equals("run") || method.getName().equals("runDelayed"));
            return addWork(globalWork, args, false);
        });
        private final TaskDispatcher dispatcher = new TaskDispatcher(plugin, global, async);

        @SuppressWarnings("unchecked")
        private ScheduledTask addWork(List<Work> pending, Object[] args, boolean repeating) {
            assertSame(plugin, args[0]);
            FakeTask task = new FakeTask(plugin, repeating);
            pending.add(new Work((Consumer<ScheduledTask>) args[1], task));
            return task;
        }
    }

    private record Work(Consumer<ScheduledTask> callback, FakeTask task) {
        private void fire() {
            callback.accept(task);
        }
    }

    private static final class FakeTask implements ScheduledTask {
        private final Plugin plugin;
        private final boolean repeating;
        private boolean cancelled;

        private FakeTask(Plugin plugin, boolean repeating) {
            this.plugin = plugin;
            this.repeating = repeating;
        }

        @Override
        public Plugin getOwningPlugin() {
            return plugin;
        }

        @Override
        public boolean isRepeatingTask() {
            return repeating;
        }

        @Override
        public CancelledState cancel() {
            cancelled = true;
            return CancelledState.CANCELLED_BY_CALLER;
        }

        @Override
        public ExecutionState getExecutionState() {
            return cancelled ? ExecutionState.CANCELLED : ExecutionState.IDLE;
        }
    }

    private static <T> T proxy(Class<T> type, java.lang.reflect.InvocationHandler handler) {
        return type.cast(Proxy.newProxyInstance(type.getClassLoader(), new Class<?>[]{type}, handler));
    }
}
