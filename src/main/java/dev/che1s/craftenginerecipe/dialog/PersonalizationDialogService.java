package dev.che1s.craftenginerecipe.dialog;

import dev.che1s.craftenginerecipe.CraftEngineRecipePlugin;
import dev.che1s.craftenginerecipe.gui.BrowserService;
import dev.che1s.craftenginerecipe.gui.GuiSession;
import dev.che1s.craftenginerecipe.i18n.LanguageManager;
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
    private static final String MAIN_PAGE_KEY = "cer_personalization_main_page";
    private static final String LANGUAGE_KEY = "cer_personalization_language";
    private static final String HOTKEY_ENABLED_KEY = "cer_personalization_hotkey_enabled";
    private static final String BOOK_LEFT_KEY = "cer_personalization_book_left";
    private static final String BOOK_RIGHT_KEY = "cer_personalization_book_right";
    private static final String BOOK_SNEAK_LEFT_KEY = "cer_personalization_book_sneak_left";
    private static final String BOOK_SNEAK_RIGHT_KEY = "cer_personalization_book_sneak_right";

    private final CraftEngineRecipePlugin plugin;

    public PersonalizationDialogService(CraftEngineRecipePlugin plugin) {
        this.plugin = plugin;
    }

    public boolean open(Player player) {
        PlayerPreferences preferences = this.plugin.playerPreferences();
        if (preferences == null || !preferences.enabled()) return false;
        LanguageManager.Localized language = this.plugin.lang(player);
        List<String> packs = preferences.menuPacks().stream()
                .filter(pack -> this.plugin.browser(pack) != null)
                .toList();
        if (packs.isEmpty()) return false;

        String selected = preferences.menuPack(player.getUniqueId());
        if (!packs.contains(selected)) selected = packs.getFirst();
        PlayerPreferences.LandingPage landingPage = preferences.landingPage(player.getUniqueId());
        GuiSession.Snapshot snapshot = GuiSession.of(player).snapshot();
        BrowserService source = this.plugin.browser(player);
        long generation = this.plugin.data().generation();

        List<SingleOptionDialogInput.OptionEntry> entries = new ArrayList<>();
        for (String pack : packs) {
            entries.add(SingleOptionDialogInput.OptionEntry.create(pack,
                    net.kyori.adventure.text.Component.text(
                            plainMenuName(this.plugin.menuPackDisplayName(pack, language.locale()), pack)),
                    pack.equals(selected)));
        }
        SingleOptionDialogInput menuInput = DialogInput.singleOption(MENU_PACK_KEY,
                        language.component("ui.personalization.menu-pack-label"), entries)
                .width(300)
                .labelVisible(true)
                .build();
        SingleOptionDialogInput mainPageInput = DialogInput.singleOption(MAIN_PAGE_KEY,
                        language.component("ui.personalization.main-page-label"), List.of(
                                SingleOptionDialogInput.OptionEntry.create("namespaces",
                                        language.component("ui.personalization.main-page-namespaces"),
                                        landingPage == PlayerPreferences.LandingPage.NAMESPACES),
                                SingleOptionDialogInput.OptionEntry.create("workstations",
                                        language.component("ui.personalization.main-page-workstations"),
                                        landingPage == PlayerPreferences.LandingPage.WORKSTATIONS)))
                .width(300)
                .labelVisible(true)
                .build();
        String selectedLanguage = preferences.language(
                player.getUniqueId(), this.plugin.lang().locale());
        SingleOptionDialogInput languageInput = DialogInput.singleOption(LANGUAGE_KEY,
                        language.component("ui.personalization.language-label"),
                        languageOptions(language, selectedLanguage))
                .width(300)
                .labelVisible(true)
                .build();
        var rememberInput = DialogInput.bool(REMEMBER_KEY,
                        language.component("ui.personalization.remember-label"))
                .initial(preferences.rememberLastPage(player.getUniqueId()))
                .onTrue(this.booleanText(language, true))
                .onFalse(this.booleanText(language, false))
                .build();
        boolean globalHotkeyEnabled = this.plugin.getConfig().getBoolean("hotkey.enabled", false);
        boolean bookEnabled = this.plugin.recipeBook().enabled();
        List<DialogInput> inputs = new ArrayList<>(List.of(
                menuInput, mainPageInput, languageInput, rememberInput));
        if (globalHotkeyEnabled) {
            inputs.add(DialogInput.bool(HOTKEY_ENABLED_KEY,
                            language.component("ui.personalization.hotkey-enabled"))
                    .initial(preferences.hotkeyEnabled(player.getUniqueId()))
                    .onTrue(this.booleanText(language, true))
                    .onFalse(this.booleanText(language, false))
                    .build());
        }
        if (bookEnabled) {
            PlayerPreferences.BookBindings bindings = preferences.bookBindings(player.getUniqueId());
            inputs.add(this.bookActionInput(language, BOOK_LEFT_KEY,
                    "ui.personalization.book-left-label", bindings.left()));
            inputs.add(this.bookActionInput(language, BOOK_RIGHT_KEY,
                    "ui.personalization.book-right-label", bindings.right()));
            inputs.add(this.bookActionInput(language, BOOK_SNEAK_LEFT_KEY,
                    "ui.personalization.book-sneak-left-label", bindings.sneakLeft()));
            inputs.add(this.bookActionInput(language, BOOK_SNEAK_RIGHT_KEY,
                    "ui.personalization.book-sneak-right-label", bindings.sneakRight()));
        }

        ActionButton save = ActionButton.create(
                language.component("ui.personalization.save"),
                language.component("ui.personalization.save-tooltip"),
                100,
                DialogAction.customClick((view, audience) ->
                                this.save(player, view, generation, snapshot, source, packs),
                        ClickCallback.Options.builder().uses(1)
                                .lifetime(Duration.ofMinutes(5L)).build()));
        ActionButton cancel = ActionButton.create(
                language.component("ui.personalization.cancel"),
                language.component("ui.personalization.cancel-tooltip"),
                100,
                DialogAction.customClick((view, audience) ->
                                this.schedule(player, generation, () -> this.reopen(player, source, snapshot)),
                        ClickCallback.Options.builder().uses(1)
                                .lifetime(Duration.ofMinutes(5L)).build()));
        ActionButton restoreDefaults = ActionButton.create(
                language.component("ui.personalization.restore-defaults"),
                language.component("ui.personalization.restore-defaults-tooltip"),
                100,
                DialogAction.customClick((view, audience) -> this.schedule(player, generation, () -> {
                    PlayerPreferences currentPreferences = this.plugin.playerPreferences();
                    if (currentPreferences == null) return;
                    currentPreferences.reset(player.getUniqueId());
                    this.plugin.sessions().clear(player.getUniqueId());
                    GuiSession.clear(player);
                    player.sendMessage(this.plugin.lang(player).message("messages.personalization-reset"));
                    this.plugin.tasks().playerLater(player, () -> this.open(player), 1);
                }), ClickCallback.Options.builder().uses(1)
                        .lifetime(Duration.ofMinutes(5L)).build()));
        // exitAction 会单独渲染为取消按钮，不能再次放入普通动作列表。
        List<ActionButton> actions = new ArrayList<>(List.of(save));
        if (bookEnabled) {
            ActionButton getBook = ActionButton.create(
                    language.component("ui.personalization.get-book"),
                    language.component("ui.personalization.get-book-tooltip"),
                    100,
                    DialogAction.customClick((view, audience) -> this.schedule(player, generation, () -> {
                        if (this.plugin.recipeBook().give(player)) {
                            player.sendMessage(language.message("messages.recipe-book-given"));
                        } else {
                            player.sendMessage(language.message("messages.recipe-book-not-ready"));
                        }
                    }), ClickCallback.Options.builder().uses(5)
                            .lifetime(Duration.ofMinutes(5L)).build()));
            actions.add(getBook);
        }
        actions.add(restoreDefaults);
        DialogType dialogType = DialogType.multiAction(actions)
                .exitAction(cancel)
                .columns(actions.size())
                .build();
        Dialog dialog = Dialog.create(factory -> factory.empty()
                .base(DialogBase.builder(language.component("ui.personalization.title"))
                        .canCloseWithEscape(true)
                        .pause(false)
                        .body(List.of(DialogBody.plainMessage(
                                language.component("ui.personalization.description"))))
                        .inputs(inputs)
                        .build())
                .type(dialogType));
        this.plugin.browser(player).leaveForDialog(player);
        player.showDialog(dialog);
        return true;
    }

    private void save(Player player, DialogResponseView view, long generation,
                      GuiSession.Snapshot snapshot, BrowserService source, List<String> packs) {
        String menuPack = view.getText(MENU_PACK_KEY);
        String language = language(view.getText(LANGUAGE_KEY));
        PlayerPreferences.LandingPage landingPage = landingPage(view.getText(MAIN_PAGE_KEY));
        Boolean remember = view.getBoolean(REMEMBER_KEY);
        boolean globalHotkeyEnabled = this.plugin.getConfig().getBoolean("hotkey.enabled", false);
        Boolean hotkeyEnabled = globalHotkeyEnabled ? view.getBoolean(HOTKEY_ENABLED_KEY) : null;
        PlayerPreferences.BookBindings bookBindings = null;
        if (this.plugin.recipeBook().enabled()) {
            PlayerPreferences.BookAction left = PlayerPreferences.BookAction.parse(
                    view.getText(BOOK_LEFT_KEY));
            PlayerPreferences.BookAction right = PlayerPreferences.BookAction.parse(
                    view.getText(BOOK_RIGHT_KEY));
            PlayerPreferences.BookAction sneakLeft = PlayerPreferences.BookAction.parse(
                    view.getText(BOOK_SNEAK_LEFT_KEY));
            PlayerPreferences.BookAction sneakRight = PlayerPreferences.BookAction.parse(
                    view.getText(BOOK_SNEAK_RIGHT_KEY));
            if (left == null || right == null || sneakLeft == null || sneakRight == null) return;
            bookBindings = new PlayerPreferences.BookBindings(left, right, sneakLeft, sneakRight);
        }
        if (menuPack == null || !packs.contains(menuPack) || landingPage == null
                || remember == null || language == null) {
            return;
        }
        if (globalHotkeyEnabled && hotkeyEnabled == null) return;
        PlayerPreferences.BookBindings selectedBookBindings = bookBindings;
        Boolean selectedHotkeyEnabled = hotkeyEnabled;
        this.schedule(player, generation, () -> {
            PlayerPreferences preferences = this.plugin.playerPreferences();
            PlayerPreferences.LandingPage previousLanding = preferences.landingPage(player.getUniqueId());
            String previousLanguage = preferences.language(
                    player.getUniqueId(), this.plugin.lang().locale());
            if (!preferences.setMenuPack(player.getUniqueId(), menuPack)) return;
            preferences.setRememberLastPage(player.getUniqueId(), remember);
            preferences.setLandingPage(player.getUniqueId(), landingPage);
            preferences.setLanguage(player.getUniqueId(), language);
            if (selectedHotkeyEnabled != null) {
                preferences.setHotkeyEnabled(player.getUniqueId(), selectedHotkeyEnabled);
            }
            if (selectedBookBindings != null) {
                preferences.setBookBindings(player.getUniqueId(), selectedBookBindings);
            }
            boolean landingChanged = previousLanding != landingPage;
            boolean languageChanged = !previousLanguage.equals(language);
            if (!remember || landingChanged || languageChanged) {
                this.plugin.sessions().clear(player.getUniqueId());
            }
            LanguageManager.Localized localized = this.plugin.lang(player);
            player.sendMessage(localized.message("messages.personalization-saved",
                    "menu", plainMenuName(
                            this.plugin.menuPackDisplayName(menuPack, language), menuPack),
                    "main", localized.text(landingPage == PlayerPreferences.LandingPage.WORKSTATIONS
                            ? "ui.personalization.main-page-workstations"
                            : "ui.personalization.main-page-namespaces"),
                    "remember", this.booleanText(localized, remember)));
            this.switchAndReopen(player, menuPack, snapshot, landingChanged || languageChanged);
        });
    }

    private void switchAndReopen(Player player, String menuPack, GuiSession.Snapshot snapshot,
                                 boolean landingChanged) {
        for (BrowserService browser : this.plugin.browsers().values()) {
            browser.forgetPlayer(player);
            browser.recipes().cancelRotation(player);
        }
        GuiSession.clear(player);
        this.plugin.tasks().playerLater(player, () -> {
            BrowserService selected = this.plugin.browser(
                    menuPack, this.plugin.lang(player).locale());
            boolean opened = selected != null && (landingChanged
                    ? this.openResetLanding(selected, player)
                    : selected.openSnapshot(player, snapshot));
            if (!opened) {
                BrowserService fallback = this.plugin.browser(player);
                if (fallback != null) fallback.openApplied(player);
            }
        }, 1);
    }

    private boolean openResetLanding(BrowserService browser, Player player) {
        browser.openApplied(player);
        return true;
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

    private String booleanText(LanguageManager.Localized language, boolean value) {
        boolean chinese = language.locale().toLowerCase(java.util.Locale.ROOT).startsWith("zh");
        return language.textOr(
                value ? "ui.personalization.on" : "ui.personalization.off",
                value ? (chinese ? "开启" : "Enabled") : (chinese ? "关闭" : "Disabled"));
    }

    static String plainMenuName(String displayName, String fallback) {
        String plain = displayName == null ? "" : displayName
                .replaceAll("<[^>]+>", "")
                .replaceAll("(?i)&[0-9a-fk-orx]", "")
                .trim();
        return plain.isEmpty() ? fallback : plain;
    }

    static PlayerPreferences.LandingPage landingPage(String value) {
        if (value == null) return null;
        return switch (value) {
            case "namespaces" -> PlayerPreferences.LandingPage.NAMESPACES;
            case "workstations" -> PlayerPreferences.LandingPage.WORKSTATIONS;
            default -> null;
        };
    }

    static String language(String value) {
        return LanguageManager.normalizeSupportedLocale(value);
    }

    private static List<SingleOptionDialogInput.OptionEntry> languageOptions(
            LanguageManager.Localized language, String selected) {
        List<String[]> choices = List.of(
                new String[]{"en_US", "language-en"},
                new String[]{"zh_CN", "language-zh"},
                new String[]{"ja_JP", "language-ja"},
                new String[]{"ko_KR", "language-ko"},
                new String[]{"zh_TW", "language-zh-tw"},
                new String[]{"de_DE", "language-de"},
                new String[]{"fr_FR", "language-fr"},
                new String[]{"es_ES", "language-es"},
                new String[]{"ru_RU", "language-ru"});
        return choices.stream().map(choice -> SingleOptionDialogInput.OptionEntry.create(
                choice[0], language.component("ui.personalization." + choice[1]),
                selected.equals(choice[0]))).toList();
    }

    private SingleOptionDialogInput bookActionInput(
            LanguageManager.Localized language, String key, String labelKey,
            PlayerPreferences.BookAction selected) {
        List<SingleOptionDialogInput.OptionEntry> entries = new ArrayList<>();
        for (PlayerPreferences.BookAction action : PlayerPreferences.BookAction.values()) {
            entries.add(SingleOptionDialogInput.OptionEntry.create(action.name().toLowerCase(java.util.Locale.ROOT),
                    language.component(dev.che1s.craftenginerecipe.book.RecipeBookService.actionKey(action)),
                    action == selected));
        }
        return DialogInput.singleOption(key, language.component(labelKey), entries)
                .width(300)
                .labelVisible(true)
                .build();
    }
}
