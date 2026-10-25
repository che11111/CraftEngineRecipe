package dev.che1s.craftenginerecipe.dialog;

import dev.che1s.craftenginerecipe.CraftEngineRecipePlugin;
import dev.che1s.craftenginerecipe.gui.BrowserService;
import dev.che1s.craftenginerecipe.gui.GuiSession;
import dev.che1s.craftenginerecipe.preferences.PlayerPreferences;
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
import java.util.List;

/** 玩家级的菜单主题与浏览位置恢复偏好。 */
public final class PersonalizationDialogService {
    private static final String MENU_PACK_KEY = "cer_personalization_menu_pack";
    private static final String REMEMBER_KEY = "cer_personalization_remember";

    private final CraftEngineRecipePlugin plugin;

    public PersonalizationDialogService(CraftEngineRecipePlugin plugin) {
        this.plugin = plugin;
    }

    public boolean open(Player player) {
        PlayerPreferences preferences = this.plugin.playerPreferences();
        if (preferences == null || !preferences.enabled()) return false;
        List<String> packs = preferences.menuPacks().stream()
                .filter(pack -> this.plugin.browser(pack) != null)
                .toList();
        if (packs.isEmpty()) return false;

        String selected = preferences.menuPack(player.getUniqueId());
        if (!packs.contains(selected)) selected = packs.getFirst();
        GuiSession.Snapshot snapshot = GuiSession.of(player).snapshot();
        BrowserService source = this.plugin.browser(player);
        long generation = this.plugin.data().generation();

        List<SingleOptionDialogInput.OptionEntry> entries = new ArrayList<>();
        for (String pack : packs) {
            entries.add(SingleOptionDialogInput.OptionEntry.create(pack,
                    net.kyori.adventure.text.Component.text(
                            plainMenuName(this.plugin.menuPackDisplayName(pack), pack)),
                    pack.equals(selected)));
        }
        SingleOptionDialogInput menuInput = DialogInput.singleOption(MENU_PACK_KEY,
                        this.plugin.lang().component("ui.personalization.menu-pack-label"), entries)
                .width(300)
                .labelVisible(true)
                .build();
        var rememberInput = DialogInput.bool(REMEMBER_KEY,
                        this.plugin.lang().component("ui.personalization.remember-label"))
                .initial(preferences.rememberLastPage(player.getUniqueId()))
                .onTrue(this.plugin.lang().text("ui.personalization.on"))
                .onFalse(this.plugin.lang().text("ui.personalization.off"))
                .build();

        ActionButton save = ActionButton.create(
                this.plugin.lang().component("ui.personalization.save"),
                this.plugin.lang().component("ui.personalization.save-tooltip"),
                100,
                DialogAction.customClick((view, audience) ->
                                this.save(player, view, generation, snapshot, source, packs),
                        ClickCallback.Options.builder().uses(1)
                                .lifetime(Duration.ofMinutes(5L)).build()));
        ActionButton cancel = ActionButton.create(
                this.plugin.lang().component("ui.personalization.cancel"),
                this.plugin.lang().component("ui.personalization.cancel-tooltip"),
                100,
                DialogAction.customClick((view, audience) ->
                                this.schedule(player, generation, () -> this.reopen(player, source, snapshot)),
                        ClickCallback.Options.builder().uses(1)
                                .lifetime(Duration.ofMinutes(5L)).build()));
        Dialog dialog = Dialog.create(factory -> factory.empty()
                .base(DialogBase.builder(this.plugin.lang().component("ui.personalization.title"))
                        .canCloseWithEscape(true)
                        .pause(false)
                        .body(List.of(DialogBody.plainMessage(
                                this.plugin.lang().component("ui.personalization.description"))))
                        .inputs(List.of(menuInput, rememberInput))
                        .build())
                .type(DialogType.confirmation(save, cancel)));
        this.plugin.browser(player).leaveForDialog(player);
        player.showDialog(dialog);
        return true;
    }

    private void save(Player player, DialogResponseView view, long generation,
                      GuiSession.Snapshot snapshot, BrowserService source, List<String> packs) {
        String menuPack = view.getText(MENU_PACK_KEY);
        Boolean remember = view.getBoolean(REMEMBER_KEY);
        if (menuPack == null || !packs.contains(menuPack) || remember == null) return;
        this.schedule(player, generation, () -> {
            PlayerPreferences preferences = this.plugin.playerPreferences();
            if (!preferences.setMenuPack(player.getUniqueId(), menuPack)) return;
            preferences.setRememberLastPage(player.getUniqueId(), remember);
            if (!remember) this.plugin.sessions().clear(player.getUniqueId());
            player.sendMessage(this.plugin.lang().message("messages.personalization-saved",
                    "menu", plainMenuName(this.plugin.menuPackDisplayName(menuPack), menuPack),
                    "remember", this.plugin.lang().text(
                            remember ? "ui.personalization.on" : "ui.personalization.off")));
            this.switchAndReopen(player, menuPack, snapshot);
        });
    }

    private void switchAndReopen(Player player, String menuPack, GuiSession.Snapshot snapshot) {
        for (BrowserService browser : this.plugin.browsers().values()) {
            browser.forgetPlayer(player);
            browser.recipes().cancelRotation(player);
        }
        GuiSession.clear(player);
        this.plugin.tasks().playerLater(player, () -> {
            BrowserService selected = this.plugin.browser(menuPack);
            if (selected == null || !selected.openSnapshot(player, snapshot)) {
                BrowserService fallback = this.plugin.browser(player);
                if (fallback != null) fallback.openApplied(player);
            }
        }, 1);
    }

    private void reopen(Player player, BrowserService source, GuiSession.Snapshot snapshot) {
        BrowserService current = source == null ? this.plugin.browser(player) : source;
        if (current == null || !current.openSnapshot(player, snapshot)) {
            BrowserService fallback = this.plugin.browser(player);
            if (fallback != null) fallback.openApplied(player);
        }
    }

    private void schedule(Player player, long generation, Runnable task) {
        this.plugin.tasks().player(player, () -> {
            if (player.isOnline() && this.plugin.data().isReady()
                    && this.plugin.data().generation() == generation) {
                task.run();
            }
        });
    }

    static String plainMenuName(String displayName, String fallback) {
        String plain = displayName == null ? "" : displayName
                .replaceAll("<[^>]+>", "")
                .replaceAll("(?i)&[0-9a-fk-orx]", "")
                .trim();
        return plain.isEmpty() ? fallback : plain;
    }
}
