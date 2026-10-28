package dev.che1s.craftenginerecipe.integration;

import dev.che1s.craftenginerecipe.CraftEngineRecipePlugin;
import dev.che1s.craftenginerecipe.config.IntegrationSettings;
import dev.che1s.craftenginerecipe.integration.common.PluginFingerprints;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.event.ClickCallback;
import net.kyori.adventure.text.event.ClickEvent;
import net.kyori.adventure.text.event.HoverEvent;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.server.PluginEnableEvent;

import java.time.Duration;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * 数据就绪后询问管理员要启用哪些可选配方来源。
 *
 * <p>询问使用 Adventure 点击回调，不依赖把命令复制到聊天框。选择只处理当前
 * 在线的管理员；没有管理员时保留待询问状态，管理员上线后继续显示。</p>
 */
public final class IntegrationPromptService implements Listener {
    private static final String ADMIN_PERMISSION = "craftenginerecipe.admin";
    private final CraftEngineRecipePlugin plugin;
    private final Set<String> resolvedThisSession = ConcurrentHashMap.newKeySet();
    private volatile PromptBatch active;
    private volatile boolean waitingLogged;

    public IntegrationPromptService(CraftEngineRecipePlugin plugin) {
        this.plugin = plugin;
    }

    public void start() {
        Bukkit.getPluginManager().registerEvents(this, this.plugin);
    }

    /** 可在任意调度线程调用；实际检测和发送会切回全局线程。 */
    public void requestPrompt() {
        if (!this.plugin.isEnabled()
                || !this.plugin.getConfig().getBoolean("indexing.notify-admins-on-startup", true)
                || !this.plugin.data().isReady()) return;
        this.plugin.tasks().global(this::presentPending);
    }

    /** 管理员显式打开询问；同时允许本次启动中曾选择“暂不处理”的来源再次显示。 */
    public void openPrompt() {
        this.resolvedThisSession.clear();
        this.active = null;
        this.waitingLogged = false;
        if (this.plugin.isEnabled() && this.plugin.data().isReady()) {
            this.plugin.tasks().global(this::presentPending);
        }
    }

    @EventHandler
    public void onPlayerJoin(PlayerJoinEvent event) {
        this.requestPrompt();
    }

    @EventHandler
    public void onPluginEnable(PluginEnableEvent event) {
        if (event.getPlugin() == this.plugin) return;
        PluginFingerprints.identify(event.getPlugin())
                .filter(fingerprint -> IntegrationSettings.PROMPTABLE_SOURCES.contains(fingerprint.sourceId()))
                .ifPresent(ignored -> this.requestPrompt());
    }

    private void presentPending() {
        if (!this.plugin.isEnabled() || !this.plugin.data().isReady()) return;
        PromptBatch active = this.active;
        if (active != null && !active.closed.get()) {
            if (!this.sendToAdministrators(active) && !this.waitingLogged) this.logWaiting(active.sources.values());
            return;
        }
        LinkedHashMap<String, String> pending = new LinkedHashMap<>();
        for (var installed : Bukkit.getPluginManager().getPlugins()) {
            if (!installed.isEnabled()) continue;
            PluginFingerprints.identify(installed)
                    .filter(fingerprint -> IntegrationSettings.PROMPTABLE_SOURCES.contains(fingerprint.sourceId()))
                    .filter(fingerprint -> this.plugin.integrations().mode(fingerprint.sourceId())
                            == IntegrationSettings.Mode.ASK)
                    .filter(fingerprint -> !this.resolvedThisSession.contains(fingerprint.sourceId()))
                    .ifPresent(fingerprint -> pending.put(fingerprint.sourceId(), fingerprint.displayName()));
        }
        if (pending.isEmpty()) {
            this.active = null;
            return;
        }

        PromptBatch batch = new PromptBatch(Collections.unmodifiableMap(new LinkedHashMap<>(pending)));
        this.active = batch;
        if (!this.sendToAdministrators(batch) && !this.waitingLogged) this.logWaiting(pending.values());
    }

    private void logWaiting(Iterable<String> plugins) {
        this.waitingLogged = true;
        this.plugin.getLogger().info(this.plugin.lang().text(
                "logs.integration-setup-waiting",
                "plugins", String.join(", ", plugins)));
    }

    private boolean sendToAdministrators(PromptBatch batch) {
        boolean found = false;
        for (Player player : Bukkit.getOnlinePlayers()) {
            if (!player.hasPermission(ADMIN_PERMISSION) || !batch.sent.add(player.getUniqueId())) continue;
            found = true;
            this.plugin.tasks().player(player, () -> {
                if (!player.isOnline() || !player.hasPermission(ADMIN_PERMISSION)
                        || this.active != batch || batch.closed.get()) {
                    batch.sent.remove(player.getUniqueId());
                    return;
                }
                this.sendPrompt(player, batch);
            });
        }
        return found;
    }

    private void sendPrompt(Player player, PromptBatch batch) {
        player.sendMessage(this.plugin.lang().message("messages.integration-detected"));
        player.sendMessage(this.plugin.lang().component("messages.integration-detected-plugins",
                "plugins", String.join(", ", batch.sources.values())));
        player.sendMessage(Component.empty());
        player.sendMessage(this.plugin.lang().component("messages.integration-choose"));
        player.sendMessage(Component.empty());

        List<Component> choices = new ArrayList<>();
        choices.add(this.button(player, batch, "messages.integration-support-all",
                Choice.SUPPORT_ALL, List.copyOf(batch.sources.keySet())));
        if (batch.sources.size() > 1) {
            for (var entry : batch.sources.entrySet()) {
                choices.add(this.button(player, batch, "messages.integration-support-only",
                        Choice.SUPPORT_ONE, List.of(entry.getKey()), "plugin", entry.getValue()));
            }
        }
        choices.add(this.button(player, batch, "messages.integration-defer", Choice.DEFER, List.of()));
        choices.add(this.button(player, batch, "messages.integration-disable", Choice.DISABLE, List.of()));
        for (int index = 0; index < choices.size(); index += 3) {
            Component line = Component.empty();
            for (int offset = index; offset < Math.min(index + 3, choices.size()); offset++) {
                if (offset > index) line = line.append(Component.text("  "));
                line = line.append(choices.get(offset));
            }
            player.sendMessage(line);
        }
    }

    private Component button(Player player, PromptBatch batch, String key, Choice choice,
                             List<String> sources, String... arguments) {
        UUID playerId = player.getUniqueId();
        ClickCallback<net.kyori.adventure.audience.Audience> callback = audience -> {
            UUID clicker = audience instanceof Player clicked ? clicked.getUniqueId() : playerId;
            this.handleChoice(clicker, batch, choice, sources);
        };
        ClickEvent event = ClickEvent.callback(callback,
                ClickCallback.Options.builder().uses(1)
                        .lifetime(Duration.ofMinutes(10L)).build());
        return this.plugin.lang().component(key, arguments)
                .clickEvent(event)
                .hoverEvent(HoverEvent.showText(this.plugin.lang().component(
                        "messages.integration-choice-hover")));
    }

    private void handleChoice(UUID playerId, PromptBatch batch, Choice choice, List<String> sources) {
        Player player = Bukkit.getPlayer(playerId);
        if (player == null || !player.isOnline()) return;
        this.plugin.tasks().player(player, () -> {
            if (!player.isOnline()) return;
            if (!player.hasPermission(ADMIN_PERMISSION)) {
                player.sendMessage(this.plugin.lang().message("messages.no-permission"));
                return;
            }
            this.plugin.tasks().global(() -> this.applyChoice(player, batch, choice, sources));
        });
    }

    private void applyChoice(Player player, PromptBatch batch, Choice choice, List<String> requested) {
        if (this.active != batch || batch.closed.get()) {
            this.send(player, "messages.integration-choice-handled");
            return;
        }
        if (!batch.busy.compareAndSet(false, true)) {
            this.send(player, "messages.integration-choice-busy");
            return;
        }
        switch (choice) {
            case DEFER -> {
                if (!batch.closed.compareAndSet(false, true)) {
                    batch.busy.set(false);
                    this.send(player, "messages.integration-choice-handled");
                    return;
                }
                this.active = null;
                this.resolvedThisSession.addAll(batch.sources.keySet());
                this.send(player, "messages.integration-choice-deferred");
                batch.busy.set(false);
            }
            case DISABLE -> {
                if (!batch.closed.compareAndSet(false, true)) {
                    batch.busy.set(false);
                    this.send(player, "messages.integration-choice-handled");
                    return;
                }
                this.active = null;
                this.resolvedThisSession.addAll(batch.sources.keySet());
                this.plugin.integrations().disable(batch.sources.keySet());
                this.plugin.externalRecipes().reloadIndex();
                this.send(player, "messages.integration-choice-disabled",
                        "plugins", String.join(", ", batch.sources.values()));
                batch.busy.set(false);
            }
            case SUPPORT_ALL -> {
                if (!batch.closed.compareAndSet(false, true)) {
                    batch.busy.set(false);
                    this.send(player, "messages.integration-choice-handled");
                    return;
                }
                List<String> remaining = batch.sources.keySet().stream()
                        .filter(source -> this.plugin.integrations().mode(source) == IntegrationSettings.Mode.ASK)
                        .filter(batch.claimed::add).toList();
                if (remaining.isEmpty()) {
                    this.active = null;
                    this.resolvedThisSession.addAll(batch.sources.keySet());
                    batch.busy.set(false);
                    this.send(player, "messages.integration-choice-handled");
                    return;
                }
                this.active = null;
                this.resolvedThisSession.addAll(remaining);
                this.enableAndExport(player, remaining, batch);
            }
            case SUPPORT_ONE -> {
                String source = requested.isEmpty() ? "" : requested.getFirst();
                if (!batch.sources.containsKey(source)
                        || this.plugin.integrations().mode(source) != IntegrationSettings.Mode.ASK
                        || !batch.claimed.add(source)) {
                    batch.busy.set(false);
                    this.send(player, "messages.integration-choice-handled");
                    return;
                }
                this.resolvedThisSession.add(source);
                this.enableAndExport(player, List.of(source), batch);
            }
        }
    }

    private void enableAndExport(Player player, List<String> sources, PromptBatch batch) {
        LinkedHashSet<String> valid = new LinkedHashSet<>();
        for (String source : sources) {
            if (IntegrationSettings.PROMPTABLE_SOURCES.contains(source)) valid.add(source);
        }
        if (valid.isEmpty()) return;
        this.plugin.integrations().enable(valid);
        List<String> names = valid.stream().map(IntegrationPromptService::displayName).toList();
        this.send(player, "messages.integration-export-started",
                "plugins", String.join(", ", names));
        this.plugin.externalRecipes().reexport(valid, report -> {
            batch.busy.set(false);
            if (!player.isOnline()) return;
            this.plugin.tasks().player(player, () -> {
                if (!player.isOnline()) return;
                for (String source : valid) {
                    String name = displayName(source);
                    var sourceReport = report.bySource().get(source);
                    if (sourceReport != null) {
                        player.sendMessage(this.plugin.lang().component(
                                "messages.integration-index-ready",
                                "plugin", name,
                                "recipes", String.valueOf(sourceReport.recipes()),
                                "workstations", String.valueOf(sourceReport.workstations())));
                    } else {
                        String error = report.failures().entrySet().stream()
                                .filter(entry -> entry.getKey().equals(source)
                                        || entry.getKey().startsWith(source + "-"))
                                .map(Map.Entry::getValue).findFirst()
                                .orElse(this.plugin.lang().text("messages.integration-unknown-error"));
                        player.sendMessage(this.plugin.lang().component(
                                "messages.integration-index-failed", "plugin", name, "error", error));
                    }
                }
            });
        });
    }

    private void send(Player player, String key, String... arguments) {
        this.plugin.tasks().player(player, () -> {
            if (player.isOnline()) player.sendMessage(this.plugin.lang().message(key, arguments));
        });
    }

    static String displayName(String sourceId) {
        return PluginFingerprints.all().stream()
                .filter(fingerprint -> fingerprint.sourceId().equals(sourceId))
                .map(PluginFingerprints.Fingerprint::displayName)
                .findFirst().orElse(sourceId);
    }

    private enum Choice {
        SUPPORT_ALL,
        SUPPORT_ONE,
        DEFER,
        DISABLE
    }

    private static final class PromptBatch {
        private final Map<String, String> sources;
        private final Set<UUID> sent = ConcurrentHashMap.newKeySet();
        private final Set<String> claimed = ConcurrentHashMap.newKeySet();
        private final AtomicBoolean closed = new AtomicBoolean();
        private final AtomicBoolean busy = new AtomicBoolean();

        private PromptBatch(Map<String, String> sources) {
            this.sources = sources;
        }
    }
}
