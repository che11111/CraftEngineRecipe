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
import java.util.Collection;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Function;

/**
 * 通过聊天中的一个入口按钮，引导管理员打开适配插件与分类预设的统一 Dialog。
 */
public final class IntegrationPromptService implements Listener {
    private static final String ADMIN_PERMISSION = "craftenginerecipe.admin";

    private final CraftEngineRecipePlugin plugin;
    private final Set<UUID> notified = ConcurrentHashMap.newKeySet();
    private String lastPendingSignature = "";
    private boolean waitingLogged;

    public IntegrationPromptService(CraftEngineRecipePlugin plugin) {
        this.plugin = plugin;
    }

    public void start() {
        Bukkit.getPluginManager().registerEvents(this, this.plugin);
    }

    public void requestPrompt() {
        if (!this.plugin.isEnabled()
                || !this.plugin.getConfig().getBoolean("indexing.notify-admins-on-startup", true)
                || !this.plugin.data().isReady()) return;
        this.plugin.tasks().global(this::presentPending);
    }

    public void openPrompt() {
        this.notified.clear();
        this.lastPendingSignature = "";
        this.waitingLogged = false;
        this.plugin.tasks().global(this::presentPending);
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

    public Map<String, String> pendingSources() {
        LinkedHashMap<String, String> pending = new LinkedHashMap<>();
        for (var source : this.configuredSources().entrySet()) {
            if (this.isInstalled(source.getKey())
                    && this.plugin.integrations().mode(source.getKey()) == IntegrationSettings.Mode.ASK) {
                pending.put(source.getKey(), source.getValue());
            }
        }
        return Collections.unmodifiableMap(pending);
    }

    public Map<String, String> configuredSources() {
        LinkedHashMap<String, String> sources = new LinkedHashMap<>();
        for (String source : IntegrationSettings.PROMPTABLE_SOURCES) {
            sources.put(source, displayName(source));
        }
        return Collections.unmodifiableMap(sources);
    }

    public boolean isInstalled(String sourceId) {
        for (var installed : Bukkit.getPluginManager().getPlugins()) {
            if (installed.isEnabled() && PluginFingerprints.matches(installed, sourceId)) return true;
        }
        return false;
    }

    public boolean hasPendingChoices() {
        return !this.pendingSources().isEmpty() || !this.plugin.categoryPrompts().pendingPresets().isEmpty();
    }

    private void presentPending() {
        if (!this.plugin.isEnabled() || !this.plugin.data().isReady()) return;
        Map<String, String> sources = this.pendingSources();
        var presets = this.plugin.categoryPrompts().pendingPresets();
        if (sources.isEmpty() && presets.isEmpty()) {
            this.notified.clear();
            this.lastPendingSignature = "";
            return;
        }
        String signature = signature(sources, presets);
        if (!signature.equals(this.lastPendingSignature)) {
            this.lastPendingSignature = signature;
            this.notified.clear();
        }
        boolean found = false;
        for (Player player : Bukkit.getOnlinePlayers()) {
            if (!player.hasPermission(ADMIN_PERMISSION) || !this.notified.add(player.getUniqueId())) continue;
            found = true;
            UUID id = player.getUniqueId();
            this.plugin.tasks().player(player, () -> {
                Player online = Bukkit.getPlayer(id);
                if (online != null && online.isOnline()) this.sendEntry(online, sources.size(), presets.size());
            });
        }
        if (!found && !this.waitingLogged) {
            this.waitingLogged = true;
            this.plugin.getLogger().info(this.plugin.lang().text(
                    "logs.integration-config-waiting",
                    "plugins", String.valueOf(sources.size()),
                    "presets", String.valueOf(presets.size())));
        }
    }

    private void sendEntry(Player player, int pluginCount, int presetCount) {
        player.sendMessage(this.plugin.lang().message("messages.integration-config-detected",
                "plugins", String.valueOf(pluginCount),
                "presets", String.valueOf(presetCount)));
        UUID playerId = player.getUniqueId();
        ClickCallback<net.kyori.adventure.audience.Audience> callback = audience -> {
            Player clicker = audience instanceof Player clicked ? clicked
                    : Bukkit.getPlayer(playerId);
            if (clicker != null && clicker.isOnline()) {
                this.plugin.integrationConfigDialog().open(clicker);
            }
        };
        Component button = this.plugin.lang().component("messages.integration-config-open")
                .clickEvent(ClickEvent.callback(callback,
                        ClickCallback.Options.builder().uses(1)
                                .lifetime(Duration.ofMinutes(10L)).build()))
                .hoverEvent(HoverEvent.showText(this.plugin.lang().component(
                        "messages.integration-config-open-hover")));
        player.sendMessage(button);
    }

    /** 保存统一 Dialog 中的适配插件选择，并执行必要的导出和索引刷新。 */
    public void applyDialogChoices(Player player, Map<String, IntegrationSettings.Mode> choices) {
        if (!this.plugin.data().isReady() || choices == null) return;
        SourceTransition transition = planTransitions(choices, this.plugin.integrations()::mode);
        if (transition.accepted().isEmpty()) {
            player.sendMessage(this.plugin.lang().message("messages.integration-config-saved"));
            return;
        }
        if (!this.plugin.integrations().setModes(transition.accepted())) {
            player.sendMessage(this.plugin.lang().message("messages.integration-config-save-failed"));
            return;
        }
        if (!transition.newlyDisabled().isEmpty()) {
            this.plugin.externalRecipes().reloadIndex();
            player.sendMessage(this.plugin.lang().message("messages.integration-choice-disabled",
                    "plugins", names(transition.newlyDisabled())));
        }
        if (!transition.newlyAsk().isEmpty()) {
            this.plugin.externalRecipes().reloadIndex();
            player.sendMessage(this.plugin.lang().message(
                    "messages.integration-choice-ask", "plugins", names(transition.newlyAsk())));
        }
        if (!transition.newlyEnabled().isEmpty()) {
            List<String> applied = this.plugin.categoryPrompts()
                    .applyLinkedPresets(transition.newlyEnabled());
            if (!applied.isEmpty()) {
                player.sendMessage(this.plugin.lang().message("messages.category-preset-linked-applied",
                        "presets", String.join(", ", applied)));
            }
            List<String> exportable = transition.newlyEnabled().stream()
                    .filter(this::isInstalled)
                    .toList();
            if (!exportable.isEmpty()) this.enableAndExport(player, exportable);
            else player.sendMessage(this.plugin.lang().message("messages.integration-config-saved"));
        } else {
            player.sendMessage(this.plugin.lang().message("messages.integration-config-saved"));
        }
        this.notified.clear();
        this.lastPendingSignature = "";
    }

    private void enableAndExport(Player player, List<String> sources) {
        List<String> names = sources.stream().map(IntegrationPromptService::displayName).toList();
        this.plugin.tasks().player(player, () -> {
            if (player.isOnline()) {
                player.sendMessage(this.plugin.lang().message("messages.integration-export-started",
                        "plugins", String.join(", ", names)));
            }
        });
        this.plugin.externalRecipes().reexport(sources, report -> {
            this.plugin.tasks().player(player, () -> {
                if (!player.isOnline()) return;
                for (String source : sources) {
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

    static String displayName(String sourceId) {
        return PluginFingerprints.all().stream()
                .filter(fingerprint -> fingerprint.sourceId().equals(sourceId))
                .map(PluginFingerprints.Fingerprint::displayName)
                .findFirst().orElse(sourceId);
    }

    private static String names(Collection<String> sources) {
        return String.join(", ", sources.stream().map(IntegrationPromptService::displayName).toList());
    }

    static SourceTransition planTransitions(
            Map<String, IntegrationSettings.Mode> choices,
            Function<String, IntegrationSettings.Mode> currentMode) {
        Map<String, IntegrationSettings.Mode> accepted = new LinkedHashMap<>();
        List<String> newlyEnabled = new ArrayList<>();
        List<String> newlyDisabled = new ArrayList<>();
        List<String> newlyAsk = new ArrayList<>();
        choices.forEach((source, mode) -> {
            if (!IntegrationSettings.PROMPTABLE_SOURCES.contains(source) || mode == null) return;
            accepted.put(source, mode);
            IntegrationSettings.Mode previous = currentMode.apply(source);
            if (previous == mode) return;
            if (mode == IntegrationSettings.Mode.ENABLED) {
                newlyEnabled.add(source);
            } else if (previous == IntegrationSettings.Mode.ENABLED) {
                if (mode == IntegrationSettings.Mode.DISABLED) newlyDisabled.add(source);
                else newlyAsk.add(source);
            }
        });
        return new SourceTransition(Map.copyOf(accepted), List.copyOf(newlyEnabled),
                List.copyOf(newlyDisabled), List.copyOf(newlyAsk));
    }

    record SourceTransition(Map<String, IntegrationSettings.Mode> accepted,
                            List<String> newlyEnabled,
                            List<String> newlyDisabled,
                            List<String> newlyAsk) {
    }

    private static String signature(Map<String, String> sources,
                                    List<dev.che1s.craftenginerecipe.category.CategoryApplyPromptService.PendingPreset> presets) {
        List<String> values = new ArrayList<>(sources.keySet());
        presets.forEach(preset -> values.add("preset:" + preset.namespace() + ":" + preset.authorMismatch()));
        return String.join("|", values);
    }
}
