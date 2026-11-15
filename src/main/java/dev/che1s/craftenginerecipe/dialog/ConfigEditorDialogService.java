package dev.che1s.craftenginerecipe.dialog;

import dev.che1s.craftenginerecipe.CraftEngineRecipePlugin;
import dev.che1s.craftenginerecipe.config.PluginConfigEditor;
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

/** In-game editor for the commonly managed values in the primary config.yml. */
public final class ConfigEditorDialogService {
    private enum Page {
        GENERAL,
        DATA
    }

    private static final String LANDING_PAGE = "cer_config_landing_page";
    private static final String PERSONALIZATION = "cer_config_personalization";
    private static final String REMEMBER_PAGE = "cer_config_remember_page";
    private static final String HOTKEY = "cer_config_hotkey";
    private static final String HOTKEY_SNEAK = "cer_config_hotkey_sneak";
    private static final String UPDATES = "cer_config_updates";
    private static final String CRAFTENGINE = "cer_config_craftengine";
    private static final String MINECRAFT = "cer_config_minecraft";
    private static final String MINECRAFT_CULTIVATION = "cer_config_minecraft_cultivation";
    private static final String CRAFTENGINE_CULTIVATION = "cer_config_craftengine_cultivation";
    private static final String AUTO_RELOAD = "cer_config_auto_reload";
    private static final String POLL_INTERVAL = "cer_config_poll_interval";
    private static final String ROTATE_INTERVAL = "cer_config_rotate_interval";
    private static final String SEARCH_MAX_LENGTH = "cer_config_search_max_length";
    private static final String SEARCH_MAX_RESULTS = "cer_config_search_max_results";

    private final CraftEngineRecipePlugin plugin;

    public ConfigEditorDialogService(CraftEngineRecipePlugin plugin) {
        this.plugin = plugin;
    }

    public boolean open(Player player) {
        return this.open(player, Page.GENERAL);
    }

    private boolean open(Player player, Page page) {
        if (player == null || !player.isOnline()
                || !player.hasPermission("craftenginerecipe.admin")
                || !this.plugin.data().isReady()) return false;
        var language = this.plugin.lang(player);
        long generation = this.plugin.data().generation();
        List<ActionButton> actions = new ArrayList<>();
        actions.add(ActionButton.create(
                language.component("ui.config-editor.save"),
                language.component("ui.config-editor.save-tooltip"),
                100,
                DialogAction.customClick((view, audience) -> this.save(player, page, view, generation),
                        ClickCallback.Options.builder().uses(1)
                                .lifetime(Duration.ofMinutes(10L)).build())));
        Page target = page == Page.GENERAL ? Page.DATA : Page.GENERAL;
        actions.add(ActionButton.create(
                language.component(target == Page.DATA
                                ? "ui.config-editor.open-data" : "ui.config-editor.open-general"),
                language.component("ui.config-editor.open-page-tooltip"),
                100,
                DialogAction.customClick((view, audience) -> this.schedule(
                                player, generation, () -> this.open(player, target)),
                        ClickCallback.Options.builder().uses(1)
                                .lifetime(Duration.ofMinutes(10L)).build())));
        ActionButton cancel = ActionButton.create(
                language.component("ui.config-editor.cancel"),
                language.component("ui.config-editor.cancel-tooltip"),
                100,
                DialogAction.customClick((view, audience) -> this.schedule(
                                player, generation, () -> this.plugin.browser(player).reopenManagement(player)),
                        ClickCallback.Options.builder().uses(1)
                                .lifetime(Duration.ofMinutes(10L)).build()));
        Dialog dialog = Dialog.create(factory -> factory.empty()
                .base(DialogBase.builder(language.component(
                                page == Page.GENERAL
                                        ? "ui.config-editor.general-title"
                                        : "ui.config-editor.data-title"))
                        .canCloseWithEscape(true)
                        .pause(false)
                        .body(List.of(DialogBody.plainMessage(
                                language.component("ui.config-editor.description"))))
                        .inputs(page == Page.GENERAL
                                ? this.generalInputs(player) : this.dataInputs(player))
                        .build())
                .type(DialogType.multiAction(actions)
                        .exitAction(cancel)
                        .columns(3)
                        .build()));
        this.plugin.browser(player).leaveForDialog(player);
        player.showDialog(dialog);
        return true;
    }

    private List<DialogInput> generalInputs(Player player) {
        var config = this.plugin.getConfig();
        var language = this.plugin.lang(player);
        return List.of(
                DialogInput.singleOption(LANDING_PAGE,
                                language.component("ui.config-editor.landing-page"),
                                List.of(
                                        option(player, "namespaces", "ui.config-editor.landing-namespaces",
                                                "namespaces".equalsIgnoreCase(config.getString(
                                                        "browser.default-main-page", "namespaces"))),
                                        option(player, "workstations", "ui.config-editor.landing-workstations",
                                                "workstations".equalsIgnoreCase(config.getString(
                                                        "browser.default-main-page", "namespaces")))))
                        .width(300).labelVisible(true).build(),
                this.boolInput(player, PERSONALIZATION, "ui.config-editor.personalization",
                        config.getBoolean("personalization.enabled", true)),
                this.boolInput(player, REMEMBER_PAGE, "ui.config-editor.remember-page",
                        config.getBoolean("browser.remember-last-page", true)),
                this.boolInput(player, HOTKEY, "ui.config-editor.hotkey",
                        config.getBoolean("hotkey.enabled", true)),
                this.boolInput(player, HOTKEY_SNEAK, "ui.config-editor.hotkey-sneak",
                        config.getBoolean("hotkey.require-sneak", true)),
                this.boolInput(player, UPDATES, "ui.config-editor.updates",
                        config.getBoolean("check-for-updates", true)));
    }

    private List<DialogInput> dataInputs(Player player) {
        var config = this.plugin.getConfig();
        return List.of(
                this.boolInput(player, CRAFTENGINE, "ui.config-editor.source-craftengine",
                        config.getBoolean("sources.craftengine", true)),
                this.boolInput(player, MINECRAFT, "ui.config-editor.source-minecraft",
                        config.getBoolean("sources.minecraft", true)),
                this.boolInput(player, MINECRAFT_CULTIVATION, "ui.config-editor.source-minecraft-cultivation",
                        config.getBoolean("sources.minecraft-cultivation", true)),
                this.boolInput(player, CRAFTENGINE_CULTIVATION, "ui.config-editor.source-craftengine-cultivation",
                        config.getBoolean("sources.craftengine-cultivation", true)),
                this.boolInput(player, AUTO_RELOAD, "ui.config-editor.auto-reload",
                        config.getBoolean("recipes.auto-reload", true)),
                this.numberInput(player, POLL_INTERVAL, "ui.config-editor.poll-interval",
                        config.getInt("recipes.poll-interval", 20)),
                this.numberInput(player, ROTATE_INTERVAL, "ui.config-editor.rotate-interval",
                        config.getInt("recipe.rotate-interval", 30)),
                this.numberInput(player, SEARCH_MAX_LENGTH, "ui.config-editor.search-max-length",
                        config.getInt("search.max-length", 32)),
                this.numberInput(player, SEARCH_MAX_RESULTS, "ui.config-editor.search-max-results",
                        config.getInt("search.max-results", 200)));
    }

    private void save(Player player, Page page, DialogResponseView view, long generation) {
        boolean generalSaved = page != Page.GENERAL || this.saveGeneral(view);
        boolean dataSaved = page != Page.DATA || this.saveData(view);
        if (!generalSaved || !dataSaved) {
            player.sendMessage(this.plugin.lang(player).message("messages.config-invalid"));
            this.schedule(player, generation, () -> this.open(player, page));
            return;
        }
        this.plugin.saveConfig();
        player.sendMessage(this.plugin.lang(player).message("messages.config-save-started"));
        this.plugin.reloadSelf(ok -> this.plugin.tasks().player(player, () -> {
            player.sendMessage(this.plugin.lang(player).message(
                    ok ? "messages.config-saved" : "messages.config-save-failed"));
            if (ok) this.plugin.browser(player).reopenManagement(player);
        }));
    }

    private boolean saveGeneral(DialogResponseView view) {
        Boolean personalization = view.getBoolean(PERSONALIZATION);
        Boolean remember = view.getBoolean(REMEMBER_PAGE);
        Boolean hotkey = view.getBoolean(HOTKEY);
        Boolean sneak = view.getBoolean(HOTKEY_SNEAK);
        Boolean updates = view.getBoolean(UPDATES);
        if (personalization == null || remember == null || hotkey == null
                || sneak == null || updates == null) return false;
        return PluginConfigEditor.apply(this.plugin.getConfig(),
                new PluginConfigEditor.GeneralSettings(
                        view.getText(LANDING_PAGE), personalization, remember,
                        hotkey, sneak, updates));
    }

    private boolean saveData(DialogResponseView view) {
        Boolean craftEngine = view.getBoolean(CRAFTENGINE);
        Boolean minecraft = view.getBoolean(MINECRAFT);
        Boolean minecraftCultivation = view.getBoolean(MINECRAFT_CULTIVATION);
        Boolean craftEngineCultivation = view.getBoolean(CRAFTENGINE_CULTIVATION);
        Boolean autoReload = view.getBoolean(AUTO_RELOAD);
        Integer poll = PluginConfigEditor.parseWithinRange(view.getText(POLL_INTERVAL), 10, 200);
        Integer rotate = PluginConfigEditor.parseWithinRange(view.getText(ROTATE_INTERVAL), 5, 200);
        Integer length = PluginConfigEditor.parseWithinRange(view.getText(SEARCH_MAX_LENGTH), 1, 64);
        Integer results = PluginConfigEditor.parseWithinRange(view.getText(SEARCH_MAX_RESULTS), 1, 1000);
        if (craftEngine == null || minecraft == null || minecraftCultivation == null
                || craftEngineCultivation == null || autoReload == null
                || poll == null || rotate == null || length == null || results == null) {
            return false;
        }
        return PluginConfigEditor.apply(this.plugin.getConfig(),
                new PluginConfigEditor.DataSettings(
                        craftEngine, minecraft, minecraftCultivation, craftEngineCultivation,
                        autoReload, poll, rotate, length, results));
    }

    private DialogInput boolInput(Player player, String key, String label, boolean initial) {
        var language = this.plugin.lang(player);
        return DialogInput.bool(key, language.component(label))
                .initial(initial)
                .onTrue(language.text("ui.personalization.on"))
                .onFalse(language.text("ui.personalization.off"))
                .build();
    }

    private DialogInput numberInput(Player player, String key, String label, int initial) {
        return DialogInput.text(key, this.plugin.lang(player).component(label))
                .width(120)
                .labelVisible(true)
                .initial(String.valueOf(initial))
                .maxLength(4)
                .build();
    }

    private SingleOptionDialogInput.OptionEntry option(
            Player player, String id, String label, boolean selected) {
        return SingleOptionDialogInput.OptionEntry.create(
                id, this.plugin.lang(player).component(label), selected);
    }

    private void schedule(Player player, long generation, Runnable action) {
        this.plugin.tasks().player(player, () -> {
            if (player.isOnline() && this.plugin.data().isReady()
                    && this.plugin.data().generation() == generation) action.run();
        });
    }
}
