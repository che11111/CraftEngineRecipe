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
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/** 在菜单中编辑正式或未应用分类命名空间的管理员条目。 */
public final class UnappliedCategoryDialogService {
    private static final String VISIBILITY = "cer_unapplied_visibility";
    private static final String PERMISSION = "cer_unapplied_permission";
    private static final String ICON_NAME = "cer_unapplied_icon_name";
    private static final String ITEM_LORE = "cer_unapplied_item_lore";
    private static final String ICON_LORE = "cer_unapplied_icon_lore";
    private static final String SUB_ICON_LORE = "cer_unapplied_sub_icon_lore";
    private static final String CATEGORY_ENTRY = "cer_unapplied_category_entry";
    private static final String SORT_NAMESPACE = "cer_unapplied_sort_namespace";
    private static final String HIDE_WITHOUT_RECIPES = "cer_unapplied_hide_without_recipes";
    private static final String WORKSTATION_HIDDEN = "cer_unapplied_workstation_hidden";
    private static final String HIDE_INACCESSIBLE = "cer_unapplied_hide_inaccessible";

    private final CraftEngineRecipePlugin plugin;

    public UnappliedCategoryDialogService(CraftEngineRecipePlugin plugin) {
        this.plugin = plugin;
    }

    public boolean open(Player player, String namespace) {
        CategoryNamespaces.NamespaceEntry entry = this.plugin.data().namespaces().findNamespace(namespace);
        if (entry == null) return false;
        boolean applied = this.plugin.data().namespaces().isApplied(namespace);
        String source = applied ? "categories.yml" : "categories-unapplied.yml";
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
                                this.plugin.lang().component("ui.unapplied-editor.description", "file", source))))
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
                                multiline(ITEM_LORE, this.plugin.lang().component("ui.unapplied-editor.item-lore"),
                                        entry.itemLore()),
                                multiline(ICON_LORE, this.plugin.lang().component("ui.unapplied-editor.icon-lore"),
                                        entry.iconLore()),
                                multiline(SUB_ICON_LORE,
                                        this.plugin.lang().component("ui.unapplied-editor.sub-icon-lore"),
                                        entry.subIconLore()),
                                bool(CATEGORY_ENTRY, "category-entry", entry.categoryEntry()),
                                bool(SORT_NAMESPACE, "sort-by-namespace", entry.sortByNamespace()),
                                bool(HIDE_WITHOUT_RECIPES, "hide-items-without-recipes", entry.hideItemsWithoutRecipes()),
                                bool(WORKSTATION_HIDDEN, "hidden-rules-affect-workstations",
                                        entry.hiddenRulesAffectWorkstations()),
                                bool(HIDE_INACCESSIBLE, "hide-inaccessible", entry.hideInaccessible())))
                        .build())
                .type(DialogType.confirmation(saveButton(player, namespace, applied, generation),
                        cancelButton(player, generation))));
        this.plugin.browser(player).leaveForDialog(player);
        player.showDialog(dialog);
        return true;
    }

    private ActionButton saveButton(Player player, String namespace, boolean applied, long generation) {
        return ActionButton.create(
                this.plugin.lang().component("ui.unapplied-editor.save"),
                this.plugin.lang().component("ui.unapplied-editor.save-tooltip"),
                100,
                DialogAction.customClick((view, audience) -> this.save(player, namespace, applied, view, generation),
                        ClickCallback.Options.builder().uses(1)
                                .lifetime(Duration.ofMinutes(10L)).build()));
    }

    private ActionButton cancelButton(Player player, long generation) {
        return ActionButton.create(
                this.plugin.lang().component("ui.unapplied-editor.cancel"),
                this.plugin.lang().component("ui.unapplied-editor.cancel-tooltip"),
                100,
                DialogAction.customClick((view, audience) -> this.schedule(player, generation,
                                () -> this.plugin.browser(player).reopenManagement(player)),
                        ClickCallback.Options.builder().uses(1)
                                .lifetime(Duration.ofMinutes(10L)).build()));
    }

    private void save(Player player, String namespace, boolean applied, DialogResponseView view, long generation) {
        CategoryNamespaces.Visibility visibility = visibility(view.getText(VISIBILITY));
        String permission = safe(view.getText(PERMISSION)).trim();
        String iconName = safe(view.getText(ICON_NAME)).trim();
        List<String> itemLore = lines(view.getText(ITEM_LORE));
        List<String> iconLore = lines(view.getText(ICON_LORE));
        List<String> subIconLore = lines(view.getText(SUB_ICON_LORE));
        Boolean categoryEntry = view.getBoolean(CATEGORY_ENTRY);
        Boolean sort = view.getBoolean(SORT_NAMESPACE);
        Boolean hideWithoutRecipes = view.getBoolean(HIDE_WITHOUT_RECIPES);
        Boolean workstationHidden = view.getBoolean(WORKSTATION_HIDDEN);
        Boolean hideInaccessible = view.getBoolean(HIDE_INACCESSIBLE);
        if (visibility == null || categoryEntry == null || sort == null
                || hideWithoutRecipes == null || workstationHidden == null || hideInaccessible == null) return;
        if (visibility == CategoryNamespaces.Visibility.PERMISSION && permission.isBlank()) {
            player.sendMessage(this.plugin.lang().message("messages.unapplied-permission-required"));
            this.plugin.tasks().playerLater(player, () -> this.open(player, namespace), 1);
            return;
        }
        this.schedule(player, generation, () -> {
            CategoryNamespaces.NamespaceEntry current = this.plugin.data().namespaces().findNamespace(namespace);
            if (current == null) return;
            CategoryNamespaces.NamespaceEntry updatedEntry = new CategoryNamespaces.NamespaceEntry(
                    namespace, visibility, permission, categoryEntry, iconName, itemLore, iconLore,
                    subIconLore, sort, hideWithoutRecipes, current.hiddenItems(), current.hiddenCategories(),
                    workstationHidden, hideInaccessible);
            boolean updated = this.plugin.data().namespaces().updateNamespace(namespace, updatedEntry);
            player.sendMessage(this.plugin.lang().message(
                    updated ? "messages.unapplied-updated" : "messages.unapplied-update-failed",
                    "namespace", namespace, "file", applied ? "categories.yml" : "categories-unapplied.yml"));
            if (updated) this.plugin.browser(player).reopenManagement(player);
        });
    }

    private DialogInput bool(String key, String labelPath, boolean initial) {
        return DialogInput.bool(key, this.plugin.lang().component("ui.unapplied-editor." + labelPath))
                .initial(initial)
                .onTrue(this.plugin.lang().text("ui.personalization.on"))
                .onFalse(this.plugin.lang().text("ui.personalization.off"))
                .build();
    }

    private DialogInput multiline(String key, net.kyori.adventure.text.Component label, List<String> initial) {
        return DialogInput.text(key, label)
                .width(300)
                .labelVisible(true)
                .initial(String.join("\n", initial))
                .maxLength(4096)
                .multiline(io.papermc.paper.registry.data.dialog.input.TextDialogInput
                        .MultilineOptions.create(8, 120))
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

    private static List<String> lines(String value) {
        if (value == null || value.isBlank()) return List.of();
        String[] lines = value.replace("\r\n", "\n").replace('\r', '\n').split("\n", -1);
        List<String> result = new ArrayList<>(Arrays.asList(lines));
        while (!result.isEmpty() && result.getLast().isBlank()) result.removeLast();
        return List.copyOf(result);
    }
}
