package dev.che1s.craftenginerecipe.update;

import dev.che1s.craftenginerecipe.CraftEngineRecipePlugin;
import io.papermc.paper.threadedregions.scheduler.ScheduledTask;
import net.kyori.adventure.text.event.ClickEvent;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.plugin.IllegalPluginAccessException;

import java.io.IOException;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

/** Network work stays on the async scheduler; player notices follow their entity scheduler. */
public final class UpdateChecker implements Listener {
    private final CraftEngineRecipePlugin plugin;
    private final GitHubReleaseClient client = new GitHubReleaseClient();
    private final String installedVersion;
    private volatile CheckRun active;

    public UpdateChecker(CraftEngineRecipePlugin plugin) {
        this.plugin = plugin;
        this.installedVersion = plugin.getPluginMeta().getVersion();
    }

    /** Reloading an enabled checker keeps its timer and cached result without extra requests. */
    public synchronized void reload() {
        if (!this.plugin.getConfig().getBoolean("check-for-updates", true)) {
            this.shutdown();
            return;
        }
        if (this.active != null) return;
        CheckRun run = new CheckRun();
        this.active = run;
        try {
            run.timer = this.plugin.tasks().asyncTimer(() -> this.check(run), 5, 6 * 60 * 60, TimeUnit.SECONDS);
        } catch (RuntimeException error) {
            this.active = null;
            throw error;
        }
        this.plugin.getLogger().info(this.plugin.lang().text("logs.update-check-enabled"));
    }

    public synchronized void shutdown() {
        CheckRun run = this.active;
        this.active = null;
        if (run != null && run.timer != null) run.timer.cancel();
    }

    private void check(CheckRun run) {
        if (this.active != run || !run.checking.compareAndSet(false, true)) return;
        try {
            GitHubReleaseClient.Release release = this.client.fetchLatest()
                    .filter(latest -> ReleaseVersion.isNewer(latest.version(), this.installedVersion))
                    .orElse(null);
            this.complete(run, () -> this.accept(run, release));
        } catch (IOException | RuntimeException error) {
            this.complete(run, () -> {
                // Warn once per failure streak; a successful check permits a future warning.
                if (!run.failureReported) {
                    run.failureReported = true;
                    this.plugin.getLogger().warning(this.plugin.lang().text("logs.update-check-failed",
                            "error", error.toString()));
                }
            });
        } finally {
            run.checking.set(false);
        }
    }

    private void complete(CheckRun run, Runnable action) {
        if (this.active != run) return;
        try {
            this.plugin.tasks().global(() -> {
                // A request finishing after disable/re-enable must not publish an old result.
                if (this.active == run) action.run();
            });
        } catch (IllegalPluginAccessException ignored) {
            // Plugin shutdown may have stopped the dispatcher after the active-run check.
        }
    }

    /** Called on the global scheduler; only newly discovered releases notify online admins. */
    private void accept(CheckRun run, GitHubReleaseClient.Release release) {
        run.failureReported = false;
        GitHubReleaseClient.Release previous = run.available;
        run.available = release;
        if (release == null || (previous != null && previous.version().equals(release.version()))) return;
        this.plugin.getLogger().info(this.plugin.lang().text("logs.update-available",
                "latest", release.version(), "current", this.installedVersion, "url", release.url()));
        for (Player player : Bukkit.getOnlinePlayers()) {
            this.plugin.tasks().player(player, () -> this.notifyPlayer(player, run, release));
        }
    }

    private void notifyPlayer(Player player, CheckRun run, GitHubReleaseClient.Release release) {
        if (this.active != run || run.available != release || !player.hasPermission("craftenginerecipe.admin")) return;
        if (release.version().equals(run.notified.put(player.getUniqueId(), release.version()))) return;
        player.sendMessage(this.plugin.lang().message("messages.update-available", "latest", release.version(),
                "current", this.installedVersion, "url", release.url()).clickEvent(ClickEvent.openUrl(release.url())));
    }

    @EventHandler
    public void onJoin(PlayerJoinEvent event) {
        CheckRun run = this.active;
        if (run == null || run.available == null) return;
        Player player = event.getPlayer();
        // Joining only reads the cached result; it never causes a network request.
        this.plugin.tasks().playerLater(player, () -> {
            GitHubReleaseClient.Release release = run.available;
            if (release != null) this.notifyPlayer(player, run, release);
        }, 60);
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent event) {
        CheckRun run = this.active;
        if (run != null) run.notified.remove(event.getPlayer().getUniqueId());
    }

    private static final class CheckRun {
        private final AtomicBoolean checking = new AtomicBoolean();
        private final Map<UUID, String> notified = new ConcurrentHashMap<>();
        private volatile GitHubReleaseClient.Release available;
        private ScheduledTask timer;
        private boolean failureReported;
    }
}
