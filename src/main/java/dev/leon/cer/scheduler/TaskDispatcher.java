package dev.leon.cer.scheduler;

import io.papermc.paper.threadedregions.scheduler.AsyncScheduler;
import io.papermc.paper.threadedregions.scheduler.GlobalRegionScheduler;
import io.papermc.paper.threadedregions.scheduler.ScheduledTask;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.plugin.Plugin;

import java.util.concurrent.CompletableFuture;
import java.util.function.Consumer;

/** Paper implements these schedulers too; GUI work always follows its viewer across regions. */
public final class TaskDispatcher {
    private final Plugin plugin;
    private final GlobalRegionScheduler global;
    private final AsyncScheduler async;
    private final Consumer<Throwable> errors;

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
        return this.global.run(this.plugin, ignored -> action.run());
    }

    public ScheduledTask async(Runnable action) {
        return this.async.runNow(this.plugin, ignored -> action.run());
    }

    public ScheduledTask playerLater(Player player, Runnable action, long ticks) {
        return player.getScheduler().runDelayed(this.plugin, ignored -> {
            if (player.isOnline()) action.run();
        }, null, Math.max(1, ticks));
    }

    /** Completes even when a viewer disconnects before their cleanup can run. Never blocks a tick. */
    public CompletableFuture<Void> player(Player player, Runnable action) {
        CompletableFuture<Void> result = new CompletableFuture<>();
        try {
            ScheduledTask task = player.getScheduler().run(this.plugin, ignored -> {
                try {
                    if (player.isOnline()) action.run();
                    result.complete(null);
                } catch (Throwable error) {
                    result.completeExceptionally(error);
                    this.errors.accept(error);
                }
            }, () -> result.complete(null));
            if (task == null) result.complete(null);
        } catch (RuntimeException error) {
            result.completeExceptionally(error);
            this.errors.accept(error);
        }
        return result;
    }

    public void cancelGlobalAndAsync() {
        this.global.cancelTasks(this.plugin);
        this.async.cancelTasks(this.plugin);
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
