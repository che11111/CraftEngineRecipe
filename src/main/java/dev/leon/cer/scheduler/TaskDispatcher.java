package dev.leon.cer.scheduler;

import io.papermc.paper.threadedregions.scheduler.AsyncScheduler;
import io.papermc.paper.threadedregions.scheduler.GlobalRegionScheduler;
import io.papermc.paper.threadedregions.scheduler.ScheduledTask;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.plugin.IllegalPluginAccessException;
import org.bukkit.plugin.Plugin;

import java.util.List;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;

/** Paper implements these schedulers too; GUI work always follows its viewer across regions. */
public final class TaskDispatcher {
    private final Plugin plugin;
    private final GlobalRegionScheduler global;
    private final AsyncScheduler async;
    private final Consumer<Throwable> errors;
    private final Object lifecycleLock = new Object();
    private final Set<EntityWork> entityTasks = ConcurrentHashMap.newKeySet();
    private volatile boolean stopped;

    public TaskDispatcher(Plugin plugin, Consumer<Throwable> errors) {
        this(plugin, Bukkit.getGlobalRegionScheduler(), Bukkit.getAsyncScheduler(), errors);
    }

    TaskDispatcher(Plugin plugin, GlobalRegionScheduler global, AsyncScheduler async) {
        this(plugin, global, async, ignored -> {});
    }

    private TaskDispatcher(Plugin plugin, GlobalRegionScheduler global, AsyncScheduler async,
                           Consumer<Throwable> errors) {
        this.plugin = plugin;
        this.global = global;
        this.async = async;
        this.errors = errors;
    }

    public ScheduledTask global(Runnable action) {
        synchronized (this.lifecycleLock) {
            this.requireRunning();
            return this.global.run(this.plugin, ignored -> {
                if (!this.stopped) action.run();
            });
        }
    }

    public ScheduledTask globalLater(Runnable action, long ticks) {
        synchronized (this.lifecycleLock) {
            this.requireRunning();
            return this.global.runDelayed(this.plugin, ignored -> {
                if (!this.stopped) action.run();
            }, Math.max(1, ticks));
        }
    }

    public ScheduledTask async(Runnable action) {
        synchronized (this.lifecycleLock) {
            this.requireRunning();
            return this.async.runNow(this.plugin, ignored -> {
                if (!this.stopped) action.run();
            });
        }
    }

    public ScheduledTask playerLater(Player player, Runnable action, long ticks) {
        synchronized (this.lifecycleLock) {
            this.requireRunning();
            EntityWork work = new EntityWork(null);
            this.entityTasks.add(work);
            try {
                return work.bind(player.getScheduler().runDelayed(this.plugin,
                        ignored -> work.execute(player, action), work::retired, Math.max(1, ticks)));
            } catch (RuntimeException | LinkageError error) {
                work.retired();
                throw error;
            } catch (Error error) {
                work.retired();
                throw error;
            }
        }
    }

    /** Completes even when a viewer disconnects before their cleanup can run. Never blocks a tick. */
    public CompletableFuture<Void> player(Player player, Runnable action) {
        CompletableFuture<Void> result = new CompletableFuture<>();
        synchronized (this.lifecycleLock) {
            if (this.stopped) return CompletableFuture.completedFuture(null);
            EntityWork work = new EntityWork(result);
            this.entityTasks.add(work);
            try {
                work.bind(player.getScheduler().run(this.plugin,
                        ignored -> work.execute(player, action), work::retired));
            } catch (RuntimeException | LinkageError error) {
                result.completeExceptionally(error);
                work.retired();
                this.errors.accept(error);
            } catch (Error error) {
                result.completeExceptionally(error);
                work.retired();
                throw error;
            }
        }
        return result;
    }

    /** Cancels pending entity work too; scheduler cancellation alone does not invoke retired callbacks. */
    public void shutdown() {
        List<EntityWork> pending;
        synchronized (this.lifecycleLock) {
            if (this.stopped) return;
            this.stopped = true;
            pending = List.copyOf(this.entityTasks);
        }
        // Complete every barrier before calling platform cancellation code. A platform error
        // must not strand another viewer's not-yet-started cleanup future.
        pending.forEach(EntityWork::retired);
        for (EntityWork work : pending) {
            try {
                work.cancel();
            } catch (RuntimeException | LinkageError error) {
                this.errors.accept(error);
            }
        }
        try {
            this.global.cancelTasks(this.plugin);
        } finally {
            this.async.cancelTasks(this.plugin);
        }
    }

    private void requireRunning() {
        if (this.stopped) throw new IllegalPluginAccessException("CraftEngineRecipe task dispatcher is stopped");
    }

    private final class EntityWork implements ScheduledTask {
        private final CompletableFuture<Void> completion;
        private final AtomicBoolean claimed = new AtomicBoolean();
        private ScheduledTask delegate;

        private EntityWork(CompletableFuture<Void> completion) {
            this.completion = completion;
        }

        private ScheduledTask bind(ScheduledTask task) {
            this.delegate = task;
            if (task == null) {
                this.retired();
                return null;
            }
            return this;
        }

        private void execute(Player player, Runnable action) {
            if (!this.claimed.compareAndSet(false, true)) return;
            entityTasks.remove(this);
            try {
                if (!stopped && player.isOnline()) action.run();
                if (this.completion != null) this.completion.complete(null);
            } catch (RuntimeException | LinkageError error) {
                if (this.completion != null) this.completion.completeExceptionally(error);
                errors.accept(error);
            } catch (Error error) {
                if (this.completion != null) this.completion.completeExceptionally(error);
                throw error;
            }
        }

        private void retired() {
            if (!this.claimed.compareAndSet(false, true)) return;
            entityTasks.remove(this);
            if (this.completion != null) this.completion.complete(null);
        }

        @Override
        public CancelledState cancel() {
            try {
                return this.delegate.cancel();
            } finally {
                this.retired();
            }
        }

        @Override
        public Plugin getOwningPlugin() { return plugin; }

        @Override
        public boolean isRepeatingTask() { return false; }

        @Override
        public ExecutionState getExecutionState() { return this.delegate.getExecutionState(); }
    }

    public static boolean isFolia() {
        return FoliaHolder.PRESENT;
    }

    private static final class FoliaHolder {
        private static final boolean PRESENT = detect();

        private static boolean detect() {
            try {
                Class.forName("io.papermc.paper.threadedregions.RegionizedServer");
                return true;
            } catch (ClassNotFoundException ignored) {
                return false;
            }
        }
    }
}
