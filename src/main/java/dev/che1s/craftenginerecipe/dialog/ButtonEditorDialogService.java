package dev.che1s.craftenginerecipe.dialog;

import dev.che1s.craftenginerecipe.CraftEngineRecipePlugin;
import dev.che1s.craftenginerecipe.category.CategoryNamespaces;
import io.papermc.paper.dialog.Dialog;
import io.papermc.paper.dialog.DialogResponseView;
import io.papermc.paper.registry.data.dialog.ActionButton;
import io.papermc.paper.registry.data.dialog.DialogBase;
import io.papermc.paper.registry.data.dialog.action.DialogAction;
import io.papermc.paper.registry.data.dialog.body.DialogBody;
import io.papermc.paper.registry.data.dialog.input.DialogInput;
import io.papermc.paper.registry.data.dialog.input.SingleOptionDialogInput;
import io.papermc.paper.registry.data.dialog.input.TextDialogInput;
import io.papermc.paper.registry.data.dialog.type.DialogType;
import net.kyori.adventure.text.event.ClickCallback;
import org.bukkit.entity.Player;

import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/** 使用游戏内对话框编辑 categories.yml 与 workstations.yml 的自定义按钮。 */
public final class ButtonEditorDialogService {
    private static final String MATERIAL = "cer_button_material";
    private static final String NAME = "cer_button_name";
    private static final String LORE = "cer_button_lore";
    private static final String VISIBILITY = "cer_button_visibility";
    private static final String PERMISSION = "cer_button_permission";
    private static final String HIDE_INACCESSIBLE = "cer_button_hide_inaccessible";
    private static final String ACTIONS = "cer_button_actions";

    public enum Source {
        CATEGORIES,
        CATEGORIES_UNAPPLIED,
        WORKSTATIONS
    }

    private final CraftEngineRecipePlugin plugin;

    public ButtonEditorDialogService(CraftEngineRecipePlugin plugin) {
        this.plugin = plugin;
    }

    public boolean open(Player player, Source source, int index) {
        CategoryNamespaces.ButtonEntry button = this.button(source, index);
        if (button == null) return false;
        long generation = this.plugin.data().generation();
        List<SingleOptionDialogInput.OptionEntry> visibility = List.of(
                option("player", "visibility.player", button.visibility()),
                option("permission", "visibility.permission", button.visibility()),
                option("admin", "visibility.admin", button.visibility()));
        Dialog dialog = Dialog.create(factory -> factory.empty()
                .base(DialogBase.builder(this.plugin.lang().component("ui.button-editor.title"))
                        .canCloseWithEscape(true)
                        .pause(false)
                        .body(List.of(DialogBody.plainMessage(
                                this.plugin.lang().component("ui.button-editor.description",
                                        "file", this.fileName(source)))))
                        .inputs(List.of(
                                DialogInput.text(MATERIAL,
                                                this.plugin.lang().component("ui.button-editor.material"))
                                        .width(300).labelVisible(true)
                                        .initial(button.display().material()).maxLength(256).build(),
                                DialogInput.text(NAME, this.plugin.lang().component("ui.button-editor.name"))
                                        .width(300).labelVisible(true)
                                        .initial(button.display().name()).maxLength(512).build(),
                                multiline(LORE, this.plugin.lang().component("ui.button-editor.lore"),
                                        String.join("\n", button.display().lore()), 1024),
                                DialogInput.singleOption(VISIBILITY,
                                                this.plugin.lang().component("ui.button-editor.visibility"),
                                                visibility)
                                        .width(300).labelVisible(true).build(),
                                DialogInput.text(PERMISSION,
                                                this.plugin.lang().component("ui.button-editor.permission"))
                                        .width(300).labelVisible(true)
                                        .initial(button.permission()).maxLength(128).build(),
                                DialogInput.bool(HIDE_INACCESSIBLE,
                                                this.plugin.lang().component("ui.button-editor.hide-inaccessible"))
                                        .initial(button.hideInaccessible())
                                        .onTrue(this.booleanText(true))
                                        .onFalse(this.booleanText(false))
                                        .build(),
                                multiline(ACTIONS, this.plugin.lang().component("ui.button-editor.actions"),
                                        actionLines(button.actions()), 8192)))
                        .build())
                .type(DialogType.confirmation(
                        saveButton(player, source, index, generation),
                        cancelButton(player, generation))));
        this.plugin.browser(player).leaveForDialog(player);
        player.showDialog(dialog);
        return true;
    }

    private CategoryNamespaces.ButtonEntry button(Source source, int index) {
        if (source == Source.CATEGORIES) {
            List<CategoryNamespaces.Entry> entries = this.plugin.data().namespaces().entries();
            return index >= 0 && index < entries.size()
                    && entries.get(index) instanceof CategoryNamespaces.ButtonEntry button ? button : null;
        }
        if (source == Source.CATEGORIES_UNAPPLIED) {
            List<CategoryNamespaces.Entry> entries = this.plugin.data().namespaces().unappliedAllEntries();
            return index >= 0 && index < entries.size()
                    && entries.get(index) instanceof CategoryNamespaces.ButtonEntry button ? button : null;
        }
        List<dev.che1s.craftenginerecipe.config.WorkstationConfig.Entry> entries =
                this.plugin.workstationConfig().entries();
        return index >= 0 && index < entries.size()
                && entries.get(index) instanceof dev.che1s.craftenginerecipe.config.WorkstationConfig.ButtonEntry button
                ? button.button() : null;
    }

    private ActionButton saveButton(Player player, Source source, int index, long generation) {
        return ActionButton.create(
                this.plugin.lang().component("ui.button-editor.save"),
                this.plugin.lang().component("ui.button-editor.save-tooltip"),
                100,
                DialogAction.customClick((view, audience) ->
                                this.save(player, source, index, view, generation),
                        ClickCallback.Options.builder().uses(1)
                                .lifetime(Duration.ofMinutes(10L)).build()));
    }

    private ActionButton cancelButton(Player player, long generation) {
        return ActionButton.create(
                this.plugin.lang().component("ui.button-editor.cancel"),
                this.plugin.lang().component("ui.button-editor.cancel-tooltip"),
                100,
                DialogAction.customClick((view, audience) ->
                                this.schedule(player, generation, () ->
                                        this.plugin.browser(player).reopenManagement(player)),
                        ClickCallback.Options.builder().uses(1)
                                .lifetime(Duration.ofMinutes(10L)).build()));
    }

    private void save(Player player, Source source, int index, DialogResponseView view, long generation) {
        CategoryNamespaces.Visibility visibility = visibility(view.getText(VISIBILITY));
        Boolean hideInaccessible = view.getBoolean(HIDE_INACCESSIBLE);
        if (visibility == null || hideInaccessible == null) return;
        String permission = safe(view.getText(PERMISSION)).trim();
        if (visibility == CategoryNamespaces.Visibility.PERMISSION && permission.isBlank()) {
            player.sendMessage(this.plugin.lang().message("messages.button-permission-required"));
            this.plugin.tasks().playerLater(player, () ->
                    this.open(player, source, index), 1);
            return;
        }
        CategoryNamespaces.ButtonEntry updated = new CategoryNamespaces.ButtonEntry(
                new CategoryNamespaces.ButtonDisplay(safe(view.getText(MATERIAL)).trim(),
                        safe(view.getText(NAME)), lines(view.getText(LORE))),
                parseActions(view.getText(ACTIONS)), visibility, permission, hideInaccessible);
        this.schedule(player, generation, () -> {
            boolean saved = switch (source) {
                case CATEGORIES -> this.plugin.data().namespaces().updateButton(index, updated);
                case CATEGORIES_UNAPPLIED ->
                        this.plugin.data().namespaces().updateUnappliedButton(index, updated);
                case WORKSTATIONS -> this.plugin.workstationConfig().updateButton(index, updated);
            };
            player.sendMessage(this.plugin.lang().message(
                    saved ? "messages.button-updated" : "messages.button-update-failed",
                    "file", this.fileName(source)));
            if (saved) this.plugin.browser(player).reopenManagement(player);
        });
    }

    private String fileName(Source source) {
        return switch (source) {
            case CATEGORIES -> "categories.yml";
            case CATEGORIES_UNAPPLIED -> "categories-unapplied.yml";
            case WORKSTATIONS -> "workstations.yml";
        };
    }

    private DialogInput multiline(String key, net.kyori.adventure.text.Component label,
                                  String initial, int maxLength) {
        return DialogInput.text(key, label)
                .width(300)
                .labelVisible(true)
                .initial(initial)
                .maxLength(maxLength)
                .multiline(TextDialogInput.MultilineOptions.create(10, 140))
                .build();
    }

    private SingleOptionDialogInput.OptionEntry option(String id, String labelPath,
                                                       CategoryNamespaces.Visibility current) {
        return SingleOptionDialogInput.OptionEntry.create(id,
                this.plugin.lang().componentOr(optionKey(labelPath), this.visibilityFallback(id)),
                current.name().equalsIgnoreCase(id));
    }

    static String optionKey(String labelPath) {
        return "ui.button-editor." + labelPath.replace('.', '-');
    }

    private String visibilityFallback(String id) {
        boolean chinese = this.plugin.lang().locale().toLowerCase(Locale.ROOT).startsWith("zh");
        return switch (id) {
            case "admin" -> chinese ? "仅管理员" : "Administrators only";
            case "permission" -> chinese ? "按权限" : "Permission-based";
            default -> chinese ? "所有玩家" : "All players";
        };
    }

    private String booleanText(boolean value) {
        boolean chinese = this.plugin.lang().locale().toLowerCase(Locale.ROOT).startsWith("zh");
        return this.plugin.lang().textOr(
                value ? "ui.personalization.on" : "ui.personalization.off",
                value ? (chinese ? "开启" : "Enabled") : (chinese ? "关闭" : "Disabled"));
    }

    private static String actionLines(Map<String, List<String>> actions) {
        List<String> lines = new ArrayList<>();
        actions.forEach((click, values) -> {
            for (String value : values) lines.add(click + " | " + value);
        });
        return String.join("\n", lines);
    }

    private static Map<String, List<String>> parseActions(String input) {
        Map<String, List<String>> actions = new LinkedHashMap<>();
        for (String raw : lines(input)) {
            String click = "all";
            String action = raw;
            int separator = raw.indexOf('|');
            if (separator >= 0) {
                String candidate = raw.substring(0, separator).trim().toLowerCase(Locale.ROOT);
                if (candidate.matches("all|left|right|shift_left|shift_right|middle")) {
                    click = candidate;
                    action = raw.substring(separator + 1).trim();
                }
            }
            if (!action.isBlank()) {
                actions.computeIfAbsent(click, ignored -> new ArrayList<>()).add(action);
            }
        }
        return actions;
    }

    private static CategoryNamespaces.Visibility visibility(String value) {
        if (value == null) return null;
        return switch (value) {
            case "admin" -> CategoryNamespaces.Visibility.ADMIN;
            case "player" -> CategoryNamespaces.Visibility.PLAYER;
            case "permission" -> CategoryNamespaces.Visibility.PERMISSION;
            default -> null;
        };
    }

    private void schedule(Player player, long generation, Runnable action) {
        this.plugin.tasks().player(player, () -> {
            if (player.isOnline() && this.plugin.data().isReady()
                    && this.plugin.data().generation() == generation) action.run();
        });
    }

    private static String safe(String value) {
        return value == null ? "" : value;
    }

    private static List<String> lines(String value) {
        if (value == null || value.isBlank()) return List.of();
        String[] lines = value.replace("\r\n", "\n").replace('\r', '\n').split("\n", -1);
        List<String> result = new ArrayList<>(List.of(lines));
        while (!result.isEmpty() && result.getLast().isBlank()) result.removeLast();
        return List.copyOf(result);
    }
}
