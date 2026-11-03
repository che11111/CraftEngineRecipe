package dev.che1s.craftenginerecipe.update;

import dev.che1s.craftenginerecipe.CraftEngineRecipePlugin;
import io.papermc.paper.threadedregions.scheduler.ScheduledTask;
import org.bukkit.plugin.IllegalPluginAccessException;

import java.io.IOException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

/** 每个启动或重载周期最多自动检查一次；自动检查只在控制台提示。 */
public final class UpdateChecker {
    private static final long AUTOMATIC_CHECK_DELAY_SECONDS = 5;
    private final CraftEngineRecipePlugin plugin;
    private final GitHubReleaseClient client = new GitHubReleaseClient();
    private final String installedVersion;
    private volatile CheckRun active;

    public UpdateChecker(CraftEngineRecipePlugin plugin) {
        this.plugin = plugin;
        this.installedVersion = plugin.getPluginMeta().getVersion();
    }

    /** 启动或重载后安排一次异步检查，并取消上一周期尚未执行的检查。 */
    public synchronized void reload() {
        this.shutdown();
        if (!this.plugin.getConfig().getBoolean("check-for-updates", true)) {
            return;
        }
        CheckRun run = new CheckRun();
        this.active = run;
        try {
            run.pending = this.plugin.tasks().asyncLater(
                    () -> this.check(run), AUTOMATIC_CHECK_DELAY_SECONDS, TimeUnit.SECONDS);
        } catch (RuntimeException error) {
            this.active = null;
            throw error;
        }
    }

    public synchronized void shutdown() {
        CheckRun run = this.active;
        this.active = null;
        if (run != null) this.cancelPending(run);
    }

    private void cancelPending(CheckRun run) {
        ScheduledTask pending = run.pending;
        run.pending = null;
        if (pending != null) pending.cancel();
    }

    private void check(CheckRun run) {
        run.pending = null;
        if (this.active != run || !run.checking.compareAndSet(false, true)) return;
        try {
            GitHubReleaseClient.Release release = this.client.fetchLatest()
                    .filter(latest -> ReleaseVersion.isNewer(latest.version(), this.installedVersion))
                    .orElse(null);
            this.complete(run, () -> this.accept(run, release));
        } catch (IOException | RuntimeException error) {
            this.complete(run, () -> {
                this.plugin.getLogger().warning(this.plugin.lang().text(
                        "logs.update-check-failed", "error", error.toString()));
            });
        } finally {
            run.checking.set(false);
        }
    }

    private void complete(CheckRun run, Runnable action) {
        if (this.active != run) return;
        try {
            this.plugin.tasks().global(() -> {
                // 在禁用或重新启用后结束的旧请求不得发布过期结果。
                if (this.active == run) action.run();
            });
        } catch (IllegalPluginAccessException ignored) {
            // 插件关闭可能已在活动请求检查之后停止调度器。
        }
    }

    /** 在全局调度器上调用；自动检查只写控制台。 */
    private void accept(CheckRun run, GitHubReleaseClient.Release release) {
        if (release == null) return;
        this.plugin.getLogger().info(this.plugin.lang().text("logs.update-available",
                "latest", release.version(), "current", this.installedVersion, "url", release.url()));
    }

    private static final class CheckRun {
        private final AtomicBoolean checking = new AtomicBoolean();
        private volatile ScheduledTask pending;
    }
}
