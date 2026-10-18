package dev.leon.cer.dialog;

import io.papermc.paper.dialog.Dialog;
import io.papermc.paper.dialog.DialogResponseView;
import io.papermc.paper.registry.data.dialog.ActionButton;
import io.papermc.paper.registry.data.dialog.DialogBase;
import io.papermc.paper.registry.data.dialog.action.DialogAction;
import io.papermc.paper.registry.data.dialog.input.DialogInput;
import io.papermc.paper.registry.data.dialog.input.TextDialogInput;
import io.papermc.paper.registry.data.dialog.type.DialogType;
import io.papermc.paper.registry.data.dialog.body.DialogBody;
import dev.leon.cer.CraftEngineRecipePlugin;
import dev.leon.cer.config.CerConfig;
import dev.leon.cer.gui.Sounds;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.event.ClickCallback;
import org.bukkit.entity.Player;

import java.time.Duration;
import java.util.List;

public final class SearchDialogService {

    private static final String INPUT_KEY = "cer_search_keyword";

    private final CraftEngineRecipePlugin plugin;
    private final CerConfig config;

    public SearchDialogService(CraftEngineRecipePlugin plugin) {
        this.plugin = plugin;
        this.config = plugin.config();
    }

    public void open(Player player) {
        if (!this.plugin.browser().claim(player)) return;
        long generation = this.plugin.data().generation();
        CerConfig c = this.plugin.config();
        TextDialogInput input = DialogInput.text(INPUT_KEY, c.mm(c.searchDialogLabel()))
                .width(300)
                .labelVisible(true)
                .initial("")
                .maxLength(c.searchMaxLength())
                .build();
        ActionButton confirm = ActionButton.create(
                this.plugin.lang().component("ui.search.confirm"),
                this.plugin.lang().component("ui.search.confirm-tooltip"),
                100,
                DialogAction.customClick(
                        (view, audience) -> this.handleResult(player, view, generation),
                        ClickCallback.Options.builder().uses(1).lifetime(Duration.ofMinutes(5L)).build()
                )
        );
        ActionButton cancel = ActionButton.create(
                this.plugin.lang().component("ui.search.cancel"),
                this.plugin.lang().component("ui.search.cancel-tooltip"),
                100,
                DialogAction.customClick(
                        (view, audience) -> this.schedule(player, generation, () -> this.plugin.browser().openDefault(player)),
                        ClickCallback.Options.builder().uses(1).lifetime(Duration.ofMinutes(5L)).build()
                )
        );
        Dialog dialog = Dialog.create(factory -> factory.empty()
                .base(DialogBase.builder(c.mm(c.searchDialogTitle()))
                        .canCloseWithEscape(true)
                        .pause(false)
                        .body(c.searchDialogHint().isBlank() ? List.of()
                                : List.of(DialogBody.plainMessage(c.mm(c.searchDialogHint()))))
                        .inputs(List.of(input))
                        .build())
                .type(DialogType.confirmation(confirm, cancel)));
        this.plugin.browser().leaveForSearch(player);
        player.showDialog(dialog);
        Sounds.play(player, this.config.sounds().search());
    }

    private void handleResult(Player player, DialogResponseView view, long generation) {
        String keyword;
        try {
            keyword = view.getText(INPUT_KEY);
        } catch (Throwable t) {
            keyword = null;
        }
        String kw = keyword == null ? "" : keyword.trim();
        if (kw.isEmpty()) {
            this.schedule(player, generation, () -> this.plugin.browser().openDefault(player));
            return;
        }
        String finalKw = kw;
        this.schedule(player, generation, () -> this.plugin.browser().openItemList(player, null, finalKw, 0, finalKw));
    }

    private void schedule(Player player, long generation, Runnable task) {
        this.plugin.tasks().player(player, () -> {
            if (this.plugin.data().isReady() && this.plugin.data().generation() == generation) task.run();
        });
    }
}
