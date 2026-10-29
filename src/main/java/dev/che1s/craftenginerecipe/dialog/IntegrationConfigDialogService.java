package dev.che1s.craftenginerecipe.dialog;

import dev.che1s.craftenginerecipe.CraftEngineRecipePlugin;
import dev.che1s.craftenginerecipe.category.CategoryApplyPromptService;
import dev.che1s.craftenginerecipe.config.IntegrationSettings;
import io.papermc.paper.dialog.Dialog;
import io.papermc.paper.registry.data.dialog.ActionButton;
import io.papermc.paper.registry.data.dialog.DialogBase;
import io.papermc.paper.registry.data.dialog.action.DialogAction;
import io.papermc.paper.registry.data.dialog.body.DialogBody;
import io.papermc.paper.registry.data.dialog.type.DialogType;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.event.ClickCallback;
import net.kyori.adventure.text.format.TextColor;
import org.bukkit.entity.Player;

import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** 适配插件与分类预设的统一 Dialog 配置入口。 */
public final class IntegrationConfigDialogService {
    private enum Page {
        PLUGINS,
        RESOURCES
    }

    private final CraftEngineRecipePlugin plugin;

    public IntegrationConfigDialogService(CraftEngineRecipePlugin plugin) {
        this.plugin = plugin;
    }

    public boolean open(Player player) {
        return this.open(player, Page.PLUGINS);
    }

    private boolean open(Player player, Page requestedPage) {
        if (player == null || !player.isOnline() || !player.hasPermission("craftenginerecipe.admin")
                || !this.plugin.data().isReady()) return false;
        Map<String, String> sources = this.plugin.integrationPrompts().configuredSources();
        List<CategoryApplyPromptService.DialogPreset> presets =
                this.plugin.categoryPrompts().dialogPresets();
        Map<String, String> detectedSources = new LinkedHashMap<>();
        sources.forEach((sourceId, displayName) -> {
            if (this.plugin.integrationPrompts().isInstalled(sourceId)) {
                detectedSources.put(sourceId, displayName);
            }
        });
        List<CategoryApplyPromptService.DialogPreset> detectedPresets = presets.stream()
                .filter(CategoryApplyPromptService.DialogPreset::detected)
                .toList();
        if (detectedSources.isEmpty() && detectedPresets.isEmpty()) {
            player.sendMessage(this.plugin.lang().message("messages.integration-config-no-detected"));
            return false;
        }
        Page selectedPage = requestedPage;
        if (selectedPage == Page.PLUGINS && detectedSources.isEmpty()) selectedPage = Page.RESOURCES;
        if (selectedPage == Page.RESOURCES && detectedPresets.isEmpty()) selectedPage = Page.PLUGINS;
        final Page page = selectedPage;

        long generation = this.plugin.data().generation();
        Map<String, String> sourceChoices = new LinkedHashMap<>(detectedSources);
        Map<String, CategoryApplyPromptService.DialogPreset> presetChoices = new LinkedHashMap<>();
        detectedPresets.forEach(preset -> presetChoices.put(preset.namespace(), preset));
        List<ActionButton> actions = new ArrayList<>();
        ActionButton enableAll = ActionButton.create(
                this.plugin.lang().component("ui.integration-config.enable-all"),
                this.plugin.lang().component("ui.integration-config.enable-all-tooltip"),
                100,
                DialogAction.customClick((view, audience) ->
                                this.enableAll(player, generation, page, sourceChoices, presetChoices),
                        ClickCallback.Options.builder().uses(1)
                                .lifetime(Duration.ofMinutes(10L)).build()));
        actions.add(enableAll);
        if (page == Page.PLUGINS) {
            for (var source : detectedSources.entrySet()) {
                IntegrationSettings.Mode mode = this.plugin.integrations().mode(source.getKey());
                actions.add(this.itemAction(
                        this.plugin.lang().component("ui.integration-config.source-label",
                                "plugin", source.getValue()),
                        mode, player, generation, page,
                        Map.of(source.getKey(), toggledMode(mode)), Map.of()));
            }
            if (!detectedPresets.isEmpty()) {
                actions.add(this.pageAction("ui.integration-config.open-resources",
                        player, Page.RESOURCES, generation));
            }
        } else {
            for (var preset : detectedPresets) {
                actions.add(this.itemAction(
                        this.plugin.lang().component("ui.integration-config.preset-label",
                                "namespace", preset.namespace()),
                        preset.mode(), player, generation, page, Map.of(),
                        Map.of(preset.namespace(), toggledMode(preset.mode()))));
            }
            if (!detectedSources.isEmpty()) {
                actions.add(this.pageAction("ui.integration-config.open-plugins",
                        player, Page.PLUGINS, generation));
            }
        }
        ActionButton cancel = ActionButton.create(
                this.plugin.lang().component("ui.integration-config.cancel"),
                this.plugin.lang().component("ui.integration-config.cancel-tooltip"),
                100,
                DialogAction.customClick((view, audience) -> { },
                        ClickCallback.Options.builder().uses(1)
                                .lifetime(Duration.ofMinutes(10L)).build()));
        String titleKey = page == Page.PLUGINS
                ? "ui.integration-config.plugins-title"
                : "ui.integration-config.resources-title";
        String headingKey = page == Page.PLUGINS
                ? "ui.integration-config.plugin-heading"
                : "ui.integration-config.resource-heading";
        Dialog dialog = Dialog.create(factory -> factory.empty()
                .base(DialogBase.builder(this.plugin.lang().component(titleKey))
                        .canCloseWithEscape(true)
                        .pause(false)
                        .body(List.of(
                                DialogBody.plainMessage(this.plugin.lang().component(
                                        "ui.integration-config.description")),
                                DialogBody.plainMessage(this.plugin.lang().component(headingKey))))
                        .build())
                .type(DialogType.multiAction(actions)
                        .exitAction(cancel)
                        .columns(3)
                        .build()));
        this.plugin.browser(player).leaveForDialog(player);
        player.showDialog(dialog);
        return true;
    }

    private ActionButton itemAction(Component label, IntegrationSettings.Mode mode,
                                    Player player, long generation, Page page,
                                    Map<String, IntegrationSettings.Mode> sources,
                                    Map<String, IntegrationSettings.Mode> presets) {
        return ActionButton.create(
                label.color(statusColor(mode)),
                this.plugin.lang().component(mode == IntegrationSettings.Mode.ENABLED
                        ? "ui.integration-config.item-disable-tooltip"
                        : "ui.integration-config.item-enable-tooltip"),
                100,
                DialogAction.customClick((view, audience) ->
                                this.executeChoices(player, generation, page, sources, presets),
                        ClickCallback.Options.builder().uses(1)
                                .lifetime(Duration.ofMinutes(10L)).build()));
    }

    private static TextColor statusColor(IntegrationSettings.Mode mode) {
        return switch (mode) {
            case ENABLED -> TextColor.color(0x98C69A);
            case ASK -> TextColor.color(0xE4D397);
            case DISABLED -> TextColor.color(0xE58B8B);
        };
    }

    static IntegrationSettings.Mode toggledMode(IntegrationSettings.Mode mode) {
        return mode == IntegrationSettings.Mode.ENABLED
                ? IntegrationSettings.Mode.DISABLED
                : IntegrationSettings.Mode.ENABLED;
    }

    private ActionButton pageAction(String key, Player player, Page target, long generation) {
        return ActionButton.create(
                this.plugin.lang().component(key),
                Component.empty(),
                100,
                DialogAction.customClick((view, audience) -> {
                    if (this.plugin.data().isReady()
                            && this.plugin.data().generation() == generation) {
                        this.open(player, target);
                    }
                }, ClickCallback.Options.builder().uses(1)
                        .lifetime(Duration.ofMinutes(10L)).build()));
    }

    private void enableAll(Player player, long generation, Page page,
                           Map<String, String> sources,
                           Map<String, CategoryApplyPromptService.DialogPreset> presets) {
        Map<String, IntegrationSettings.Mode> sourceChoices = new LinkedHashMap<>();
        sources.forEach((source, ignored) -> sourceChoices.put(source,
                this.plugin.integrationPrompts().isInstalled(source)
                        ? IntegrationSettings.Mode.ENABLED
                        : this.plugin.integrations().mode(source)));
        Map<String, IntegrationSettings.Mode> presetChoices = new LinkedHashMap<>();
        presets.forEach((namespace, preset) -> {
            presetChoices.put(namespace, preset != null && preset.detected()
                    ? IntegrationSettings.Mode.ENABLED
                    : this.plugin.integrations().categoryPresetMode(namespace));
        });
        this.executeChoices(player, generation, page, sourceChoices, presetChoices);
    }

    private void executeChoices(Player player, long generation, Page page,
                                Map<String, IntegrationSettings.Mode> sourceChoices,
                                Map<String, IntegrationSettings.Mode> presetChoices) {
        this.plugin.tasks().player(player, () -> {
            if (!player.isOnline() || !this.plugin.data().isReady()
                    || this.plugin.data().generation() != generation) return;
            this.plugin.tasks().global(() -> {
                List<String> applied = this.plugin.categoryPrompts().applyDialogModes(presetChoices);
                if (!applied.isEmpty()) {
                    player.sendMessage(this.plugin.lang().message(
                            "messages.category-preset-linked-applied",
                            "presets", String.join(", ", applied)));
                }
                this.plugin.integrationPrompts().applyDialogChoices(player, sourceChoices);
                this.open(player, page);
            });
        });
    }

}
