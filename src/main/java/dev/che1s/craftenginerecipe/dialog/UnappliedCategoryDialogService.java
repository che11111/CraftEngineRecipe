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
import io.papermc.paper.registry.data.dialog.type.DialogType;
import net.kyori.adventure.text.event.ClickCallback;
import org.bukkit.entity.Player;

import java.time.Duration;
import java.util.List;

/** 在菜单中编辑 categories-unapplied.yml 的管理员条目。 */
public final class UnappliedCategoryDialogService {
    private static final String VISIBILITY = "cer_unapplied_visibility";
    private static final String PERMISSION = "cer_unapplied_permission";
    private static final String ICON_NAME = "cer_unapplied_icon_name";
    private static final String CATEGORY_ENTRY = "cer_unapplied_category_entry";
    private static final String SORT_NAMESPACE = "cer_unapplied_sort_namespace";
    private static final String HIDE_WITHOUT_RECIPES = "cer_unapplied_hide_without_recipes";
    private static final String WORKSTATION_HIDDEN = "cer_unapplied_workstation_hidden";

    private final CraftEngineRecipePlugin plugin;

    public UnappliedCategoryDialogService(CraftEngineRecipePlugin plugin) {
        this.plugin = plugin;
    }

    public boolean open(Player player, String namespace) {
        CategoryNamespaces.NamespaceEntry entry = this.plugin.data().namespaces().unappliedEntry(namespace);
        if (entry == null) return false;
        long generation = this.plugin.data().generation();
        List<SingleOptionDialogInput.OptionEntry> visibility = List.of(
                option("admin", "visibility.admin", entry.visibility()),
                option("player", "visibility.player", entry.visibility()),
                option("permission", "visibility.permission", entry.visibility()));
        Dialog dialog = Dialog.create(factory -> factory.empty()
                .base(DialogBase.builder(this.plugin.lang().component("ui.unapplied-editor.title",
                                "namespace", namespace))
                        .canCloseWithEscape(true)
                        .pause(false)
                        .body(List.of(DialogBody.plainMessage(
                                this.plugin.lang().component("ui.unapplied-editor.description"))))
                        .inputs(List.of(
                                DialogInput.singleOption(VISIBILITY,
                                                this.plugin.lang().component("ui.unapplied-editor.visibility"),
                                                visibility)
                                        .width(300).labelVisible(true).build(),
                                DialogInput.text(PERMISSION,
                                                this.plugin.lang().component("ui.unapplied-editor.permission"))
                                        .width(300).labelVisible(true).initial(safe(entry.permission()))
                                        .maxLength(128).build(),
                                DialogInput.text(ICON_NAME,
                                                this.plugin.lang().component("ui.unapplied-editor.icon-name"))
                                        .width(300).labelVisible(true).initial(safe(entry.iconName()))
                                        .maxLength(128).build(),
                                bool(CATEGORY_ENTRY, "category-entry", entry.categoryEntry()),
                                bool(SORT_NAMESPACE, "sort-by-namespace", entry.sortByNamespace()),
                                bool(HIDE_WITHOUT_RECIPES, "hide-items-without-recipes", entry.hideItemsWithoutRecipes()),
                                bool(WORKSTATION_HIDDEN, "hidden-rules-affect-workstations",
                                        entry.hiddenRulesAffectWorkstations())))
                        .build())
                .type(DialogType.confirmation(saveButton(player, namespace, generation),
                        cancelButton(player, generation))));
        this.plugin.browser(player).leaveForDialog(player);
        player.showDialog(dialog);
        return true;
    }

    private ActionButton saveButton(Player player, String namespace, long generation) {
        return ActionButton.create(
                this.plugin.lang().component("ui.unapplied-editor.save"),
                this.plugin.lang().component("ui.unapplied-editor.save-tooltip"),
                100,
                DialogAction.customClick((view, audience) -> this.save(player, namespace, view, generation),
                        ClickCallback.Options.builder().uses(1)
                                .lifetime(Duration.ofMinutes(10L)).build()));
    }

    private ActionButton cancelButton(Player player, long generation) {
        return ActionButton.create(
                this.plugin.lang().component("ui.unapplied-editor.cancel"),
                this.plugin.lang().component("ui.unapplied-editor.cancel-tooltip"),
                100,
                DialogAction.customClick((view, audience) -> this.schedule(player, generation,
                                () -> this.plugin.browser(player).openNamespaceManager(player)),
                        ClickCallback.Options.builder().uses(1)
                                .lifetime(Duration.ofMinutes(10L)).build()));
    }

    private void save(Player player, String namespace, DialogResponseView view, long generation) {
        CategoryNamespaces.Visibility visibility = visibility(view.getText(VISIBILITY));
        String permission = safe(view.getText(PERMISSION)).trim();
        String iconName = safe(view.getText(ICON_NAME)).trim();
        Boolean categoryEntry = view.getBoolean(CATEGORY_ENTRY);
        Boolean sort = view.getBoolean(SORT_NAMESPACE);
        Boolean hideWithoutRecipes = view.getBoolean(HIDE_WITHOUT_RECIPES);
        Boolean workstationHidden = view.getBoolean(WORKSTATION_HIDDEN);
        if (visibility == null || categoryEntry == null || sort == null
                || hideWithoutRecipes == null || workstationHidden == null) return;
        if (visibility == CategoryNamespaces.Visibility.PERMISSION && permission.isBlank()) {
            player.sendMessage(this.plugin.lang().message("messages.unapplied-permission-required"));
            this.plugin.tasks().playerLater(player, () -> this.open(player, namespace), 1);
            return;
        }
        this.schedule(player, generation, () -> {
            boolean updated = this.plugin.data().namespaces().updateUnapplied(namespace, visibility,
                    permission, categoryEntry, iconName, sort, hideWithoutRecipes, workstationHidden);
            player.sendMessage(this.plugin.lang().message(
                    updated ? "messages.unapplied-updated" : "messages.unapplied-update-failed",
                    "namespace", namespace));
            if (updated) this.plugin.browser(player).openNamespaceManager(player);
        });
    }

    private DialogInput bool(String key, String labelPath, boolean initial) {
        return DialogInput.bool(key, this.plugin.lang().component("ui.unapplied-editor." + labelPath))
                .initial(initial)
                .onTrue(this.plugin.lang().text("ui.personalization.on"))
                .onFalse(this.plugin.lang().text("ui.personalization.off"))
                .build();
    }

    private SingleOptionDialogInput.OptionEntry option(String id, String labelPath,
                                                       CategoryNamespaces.Visibility current) {
        return SingleOptionDialogInput.OptionEntry.create(id,
                this.plugin.lang().component("ui.unapplied-editor." + labelPath),
                current.name().equalsIgnoreCase(id));
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
}
