package dev.che1s.craftenginerecipe.dialog;

import io.papermc.paper.dialog.Dialog;
import io.papermc.paper.dialog.DialogResponseView;
import io.papermc.paper.registry.data.dialog.ActionButton;
import io.papermc.paper.registry.data.dialog.DialogBase;
import io.papermc.paper.registry.data.dialog.action.DialogAction;
import io.papermc.paper.registry.data.dialog.input.DialogInput;
import io.papermc.paper.registry.data.dialog.input.TextDialogInput;
import io.papermc.paper.registry.data.dialog.type.DialogType;
import io.papermc.paper.registry.data.dialog.body.DialogBody;
import dev.che1s.craftenginerecipe.CraftEngineRecipePlugin;
import dev.che1s.craftenginerecipe.config.CerConfig;
import dev.che1s.craftenginerecipe.gui.Sounds;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.event.ClickCallback;
import org.bukkit.entity.Player;

import java.time.Duration;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;
import java.util.logging.Level;

public final class SearchDialogService {

    private static final String INPUT_KEY = "cer_search_keyword";

    private final CraftEngineRecipePlugin plugin;
    private final CerConfig config;
    private final AtomicBoolean inputFailureLogged = new AtomicBoolean();

    public SearchDialogService(CraftEngineRecipePlugin plugin) {
        this.plugin = plugin;
        this.config = plugin.config();
    }

    public void open(Player player) {
        this.open(player, keyword -> this.plugin.browser().openItemList(player, null, keyword, 0, keyword),
                () -> this.plugin.browser().openDefault(player));
    }

    /** The caller owns its destination, including workstation type and return-page context. */
    public void open(Player player, Consumer<String> results, Runnable cancel) {
        if (!this.plugin.browser().claim(player)) return;
        long generation = this.plugin.data().generation();
        CerConfig c = this.plugin.config();
        int maxLength = c.searchMaxLength();
        TextDialogInput input = DialogInput.text(INPUT_KEY, c.mm(c.searchDialogLabel()))
                .width(300)
                .labelVisible(true)
                .initial("")
                .maxLength(maxLength)
                .build();
        ActionButton confirm = ActionButton.create(
                this.plugin.lang().component("ui.search.confirm"),
                this.plugin.lang().component("ui.search.confirm-tooltip"),
                100,
                DialogAction.customClick(
                        (view, audience) -> this.handleResult(player, view, generation, maxLength, results, cancel),
                        ClickCallback.Options.builder().uses(1).lifetime(Duration.ofMinutes(5L)).build()
                )
        );
        ActionButton cancelButton = ActionButton.create(
                this.plugin.lang().component("ui.search.cancel"),
                this.plugin.lang().component("ui.search.cancel-tooltip"),
                100,
                DialogAction.customClick(
                        (view, audience) -> this.schedule(player, generation, cancel),
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
                .type(DialogType.confirmation(confirm, cancelButton)));
        this.plugin.browser().leaveForSearch(player);
        player.showDialog(dialog);
        Sounds.play(player, this.config.sounds().search());
    }

    private void handleResult(Player player, DialogResponseView view, long generation, int maxLength,
                              Consumer<String> results, Runnable cancel) {
        String keyword;
        try {
            keyword = view.getText(INPUT_KEY);
        } catch (RuntimeException | LinkageError error) {
            if (this.inputFailureLogged.compareAndSet(false, true)) {
                this.plugin.getLogger().log(Level.WARNING, this.plugin.lang().text("logs.search-input-failed"), error);
            }
            keyword = null;
        }
        String kw = validatedKeyword(keyword, maxLength);
        if (kw.isEmpty()) {
            this.schedule(player, generation, cancel);
            return;
        }
        String finalKw = kw;
        this.schedule(player, generation, () -> results.accept(finalKw));
    }

    /** Reject forged oversized responses instead of silently searching a truncated keyword. */
    static String validatedKeyword(String keyword, int maxLength) {
        return keyword == null || keyword.length() > maxLength ? "" : keyword.trim();
    }

    private void schedule(Player player, long generation, Runnable task) {
        this.plugin.tasks().player(player, () -> {
            if (this.plugin.data().isReady() && this.plugin.data().generation() == generation) task.run();
        });
    }
}
