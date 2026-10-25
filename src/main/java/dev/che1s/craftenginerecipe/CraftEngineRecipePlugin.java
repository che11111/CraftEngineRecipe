package dev.che1s.craftenginerecipe;

import dev.che1s.craftenginerecipe.command.CerCommand;
import dev.che1s.craftenginerecipe.debug.Diagnostics;
import dev.che1s.craftenginerecipe.debug.DebugService;
import dev.che1s.craftenginerecipe.listener.MenuItemSafetyListener;
import dev.che1s.craftenginerecipe.config.CerConfig;
import dev.che1s.craftenginerecipe.data.CraftEngineData;
import dev.che1s.craftenginerecipe.recipe.RecipeIndex;
import dev.che1s.craftenginerecipe.recipe.ExternalRecipeService;
import dev.che1s.craftenginerecipe.dialog.PersonalizationDialogService;
import dev.che1s.craftenginerecipe.dialog.SearchDialogService;
import dev.che1s.craftenginerecipe.gui.BrowserService;
import dev.che1s.craftenginerecipe.gui.GuiSessionStore;
import dev.che1s.craftenginerecipe.integration.common.PluginFingerprints;
import dev.che1s.craftenginerecipe.listener.HotkeyListener;
import dev.che1s.craftenginerecipe.gui.GuiSession;
import dev.che1s.craftenginerecipe.metrics.MetricsService;
import dev.che1s.craftenginerecipe.preferences.PlayerPreferences;
import dev.che1s.craftenginerecipe.scheduler.TaskDispatcher;
import dev.che1s.craftenginerecipe.i18n.LanguageManager;
import dev.che1s.craftenginerecipe.resource.ResourcePackInstaller;
import dev.che1s.craftenginerecipe.resource.MenuPackManager;
import dev.che1s.craftenginerecipe.update.UpdateChecker;
import dev.che1s.craftenginerecipe.util.CompatibilityWarnings;
import net.kyori.adventure.text.minimessage.MiniMessage;
import org.bukkit.Bukkit;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.entity.Player;
import org.bukkit.event.Listener;
import org.bukkit.event.EventHandler;
import org.bukkit.event.player.PlayerQuitEvent;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.function.Consumer;

public final class CraftEngineRecipePlugin extends JavaPlugin implements Listener {

    private static volatile CraftEngineRecipePlugin instance;

    private final Diagnostics diagnostics = new Diagnostics();
    private DebugService debug;
    private TaskDispatcher tasks;
    private CerConfig config;
    private LanguageManager language;
    private CraftEngineData data;
    private RecipeIndex recipeIndex;
    private ExternalRecipeService externalRecipes;
    private MenuPackManager menuPacks;
    private BrowserService browserService;
    private GuiSessionStore sessions;
    private PlayerPreferences playerPreferences;
    private PersonalizationDialogService personalizationDialog;
    private final Map<String, CerConfig> menuConfigs = new LinkedHashMap<>();
    private final Map<String, BrowserService> menuBrowsers = new LinkedHashMap<>();
    private SearchDialogService dialogService;
    private MetricsService metrics;
    private UpdateChecker updates;

    public static CraftEngineRecipePlugin get() {
        return instance;
    }

    @Override
    public void onLoad() {
        this.language = new LanguageManager(this.getDataFolder().toPath(), this::getResource, this.getLogger());
        this.saveDefaultConfig();
        this.menuPacks = new MenuPackManager(this);
        var settings = this.getConfig();
        this.language.load(settings.getString("language", "zh_CN"));
        this.installResourcePacks();
    }

    @Override
    public void onEnable() {
        instance = this;
        this.tasks = new TaskDispatcher(this, error -> this.getLogger().log(java.util.logging.Level.SEVERE,
                this.language.text("logs.player-task-failed"), error));
        this.loadMenuConfigs();
        this.sessions = new GuiSessionStore(this);
        this.playerPreferences = new PlayerPreferences(this.getDataFolder().toPath(), this.getLogger());
        this.reloadPlayerPreferences();
        this.saveExampleFile();
        this.data = new CraftEngineData(this);
        this.externalRecipes = new ExternalRecipeService(this);
        CompatibilityWarnings.install(this.getLogger(), this.language, this.data::generation);
        this.recipeIndex = new RecipeIndex(this);
        this.createBrowsers();
        this.data.attachListener();
        this.dialogService = new SearchDialogService(this);
        this.personalizationDialog = new PersonalizationDialogService(this);
        this.externalRecipes.start();
        this.debug = new DebugService(this);
        this.debug.registerGauge("menu.viewers", this::viewerCount);
        this.debug.registerGauge("menu.pending-actions", this::pendingActionCount);
        new CerCommand(this).register();
        Bukkit.getPluginManager().registerEvents(new HotkeyListener(this), this);
        Bukkit.getPluginManager().registerEvents(new MenuItemSafetyListener(this), this);
        Bukkit.getPluginManager().registerEvents(this, this);
        this.metrics = new MetricsService(this);
        this.metrics.reload();
        this.updates = new UpdateChecker(this);
        Bukkit.getPluginManager().registerEvents(this.updates, this);
        this.updates.reload();
        // 同时支持在 CraftEngine 已加载完成后启用本插件。
        this.tasks.global(() -> {
            this.notifyAdminsIndexBuildStarted();
            this.data.rebuild(ok -> {
                if (ok && this.externalRecipes.reexportOnReload()) {
                    this.externalRecipes.reexportConfigured(ignored -> {});
                }
            });
        });
        this.getLogger().info(this.language.text("logs.enabled"));
    }

    private void installResourcePacks() {
        Path localRoot = this.getDataFolder().toPath().resolve(ResourcePackInstaller.RESOURCES_DIRECTORY);
        try {
            populateBundled(localRoot, ResourcePackInstaller.FD_PACK_DIRECTORY);
            populateBundled(localRoot, ResourcePackInstaller.GUI_PACK_DIRECTORY);
            for (String menuPack : ResourcePackInstaller.bundledMenuPacks(this.jarPath())) {
                populateBundled(localRoot, menuPack);
            }
            this.menuPacks.installEnabled();
        } catch (IOException e) {
            this.getLogger().severe(this.language.text("logs.pack-install-failed", "error", e.getMessage()));
        }
    }

    private void replenishResourcePacks() {
        Path localRoot = this.getDataFolder().toPath().resolve(ResourcePackInstaller.RESOURCES_DIRECTORY);
        List<String> packs = new ArrayList<>();
        packs.add(ResourcePackInstaller.GUI_PACK_DIRECTORY);
        packs.add(ResourcePackInstaller.FD_PACK_DIRECTORY);
        try {
            packs.addAll(ResourcePackInstaller.bundledMenuPacks(this.jarPath()));
        } catch (IOException error) {
            this.getLogger().warning("Cannot list bundled menu packs: " + error.getMessage());
        }
        for (String pack : packs) {
            Path local = localRoot.resolve(pack);
            try {
                this.logResourceCopy(local,
                        ResourcePackInstaller.replenishBundled(this.getFile().toPath(), local, pack));
            } catch (IOException e) {
                this.getLogger().severe(this.language.text("logs.pack-install-failed", "error", e.getMessage()));
            }
        }
    }

    private void populateBundled(Path localRoot, String pack) throws IOException {
        Path local = localRoot.resolve(pack);
        int count = ResourcePackInstaller.replenishBundled(this.getFile().toPath(), local, pack);
        this.logResourceCopy(local, count);
    }

    private void logResourceCopy(java.nio.file.Path local, int count) {
        this.getLogger().info(this.language.text("logs.pack-copy-ready", "path", local.toString(),
                "count", String.valueOf(count)));
    }

    private void saveExampleFile() {
        File categories = new File(this.getDataFolder(), "categories.yml");
        if (!categories.exists()) {
            this.saveResource("categories.yml", false);
        }
        File example = new File(this.getDataFolder(), "categories-example.yml");
        if (!example.exists()) {
            this.saveResource("categories-example.yml", false);
        }
        Path recipes = this.getDataFolder().toPath().resolve("recipes");
        Path recipeExample = recipes.resolve("example.yml");
        try {
            Files.createDirectories(recipes);
            if (Files.notExists(recipeExample)) {
                try (InputStream input = this.getResource("recipes-example.yml")) {
                    if (input == null) throw new IOException("Missing bundled recipes-example.yml");
                    Files.copy(input, recipeExample);
                }
            }
        } catch (IOException e) {
            this.getLogger().warning(this.language.text("logs.config-update-failed", "error", e.getMessage()));
        }
    }

    @Override
    public void onDisable() {
        if (this.debug != null) this.debug.shutdown();
        if (this.externalRecipes != null) this.externalRecipes.shutdown();
        if (this.updates != null) this.updates.shutdown();
        // 先停止回调再清理回调可访问的状态。即使插件已禁用、无法再调度实体任务，
        // 尚未完成的清理屏障仍必须结束。
        if (this.tasks != null) this.tasks.shutdown();
        if (this.data != null) {
            this.data.detachListener();
        }
        this.shutdownBrowsers();
        if (this.playerPreferences != null) this.playerPreferences.close();
        if (this.metrics != null) this.metrics.shutdown();
        GuiSession.clearAll();
        instance = null;
        CompatibilityWarnings.clear();
        if (this.language != null) this.getLogger().info(this.language.text("logs.disabled"));
    }

    public void reloadSelf(Consumer<Boolean> completion) {
        this.tasks.global(() -> this.data.rebuild(true, ok -> {
            if (ok && this.externalRecipes.reexportOnReload()) {
                this.externalRecipes.reexportConfigured(report -> completion.accept(true));
            } else {
                completion.accept(ok);
            }
        }));
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent event) {
        for (BrowserService browser : this.menuBrowsers.values()) {
            browser.rememberPlayer(event.getPlayer());
            browser.forgetPlayer(event.getPlayer());
            browser.recipes().cancelRotation(event.getPlayer());
        }
        GuiSession.clear(event.getPlayer());
    }

    /** 仅在所有查看者都关闭旧菜单后，于全局调度器调用。 */
    public void reloadSettings() {
        this.shutdownBrowsers();
        this.loadMenuConfigs();
        this.reloadPlayerPreferences();
        this.createBrowsers();
        if (this.externalRecipes != null) this.externalRecipes.reload();
        this.replenishResourcePacks();
        try {
            this.menuPacks.installEnabled();
        } catch (IOException error) {
            this.getLogger().severe(this.language.text("logs.pack-install-failed", "error", error.getMessage()));
        }
        this.metrics.reload();
        this.updates.reload();
        new CerCommand(this).register();
    }

    public Diagnostics diagnostics() { return this.diagnostics; }

    public DebugService debug() { return this.debug; }

    public TaskDispatcher tasks() { return this.tasks; }

    public LanguageManager lang() {
        return this.language;
    }

    public CerConfig config() {
        return this.config;
    }

    public CraftEngineData data() {
        return this.data;
    }

    public RecipeIndex recipeIndex() {
        return this.recipeIndex;
    }

    public ExternalRecipeService externalRecipes() { return this.externalRecipes; }

    public MenuPackManager menuPacks() { return this.menuPacks; }

    public Path jarPath() { return this.getFile().toPath(); }

    public BrowserService browser() {
        return this.browserService;
    }

    public BrowserService browser(Player player) {
        if (player == null || this.playerPreferences == null || !this.playerPreferences.enabled()) {
            return this.browserService;
        }
        BrowserService selected = this.menuBrowsers.get(this.playerPreferences.menuPack(player.getUniqueId()));
        return selected == null ? this.browserService : selected;
    }

    public BrowserService browser(String menuPack) {
        return this.menuBrowsers.get(menuPack);
    }

    public Map<String, BrowserService> browsers() {
        return Map.copyOf(this.menuBrowsers);
    }

    public GuiSessionStore sessions() {
        return this.sessions;
    }

    public PlayerPreferences playerPreferences() {
        return this.playerPreferences;
    }

    public String menuPackDisplayName(String pack) {
        CerConfig menu = this.menuConfigs.get(pack);
        return menu == null ? pack : menu.menuDisplayName();
    }

    public CompletableFuture<Void> closeAllBrowsers() {
        List<CompletableFuture<Void>> pending = new ArrayList<>();
        for (BrowserService browser : this.menuBrowsers.values()) pending.add(browser.closeAll());
        return CompletableFuture.allOf(pending.toArray(CompletableFuture[]::new));
    }

    private int viewerCount() {
        return this.menuBrowsers.values().stream().mapToInt(BrowserService::viewerCount).sum();
    }

    private int pendingActionCount() {
        return this.menuBrowsers.values().stream().mapToInt(BrowserService::pendingActionCount).sum();
    }

    private void loadMenuConfigs() {
        Map<String, CerConfig> configs = new LinkedHashMap<>();
        for (String pack : this.menuPacks.enabled()) {
            try {
                CerConfig menuConfig = new CerConfig(this, pack);
                menuConfig.load();
                configs.put(pack, menuConfig);
            } catch (RuntimeException | LinkageError error) {
                this.getLogger().warning("Cannot load menu pack " + pack + ": " + error.getMessage());
            }
        }
        if (configs.isEmpty()) {
            CerConfig fallback = new CerConfig(this, ResourcePackInstaller.MENU_CUTE_DIRECTORY);
            fallback.load();
            configs.put(ResourcePackInstaller.MENU_CUTE_DIRECTORY, fallback);
        }
        this.menuConfigs.clear();
        this.menuConfigs.putAll(configs);
        String defaultPack = this.menuPacks.defaultPack();
        if (!configs.containsKey(defaultPack)) defaultPack = configs.keySet().iterator().next();
        this.config = configs.get(defaultPack);
    }

    private void reloadPlayerPreferences() {
        if (this.playerPreferences != null) {
            this.playerPreferences.reload(this.getConfig(), this.menuPacks.enabled());
        }
    }

    private void createBrowsers() {
        Map<String, BrowserService> browsers = new LinkedHashMap<>();
        for (var entry : this.menuConfigs.entrySet()) {
            try {
                browsers.put(entry.getKey(), new BrowserService(this, entry.getValue(), this.data, this.recipeIndex));
            } catch (RuntimeException | LinkageError error) {
                this.getLogger().warning("Cannot create menu browser " + entry.getKey() + ": " + error.getMessage());
            }
        }
        if (browsers.isEmpty()) {
            CerConfig fallback = this.menuConfigs.values().iterator().next();
            browsers.put(ResourcePackInstaller.MENU_CUTE_DIRECTORY,
                    new BrowserService(this, fallback, this.data, this.recipeIndex));
        }
        String defaultPack = this.menuPacks.defaultPack();
        if (!browsers.containsKey(defaultPack)) defaultPack = browsers.keySet().iterator().next();
        this.menuBrowsers.clear();
        this.menuBrowsers.putAll(browsers);
        this.config = this.menuConfigs.get(defaultPack);
        this.browserService = browsers.get(defaultPack);
    }

    private void shutdownBrowsers() {
        for (BrowserService browser : this.menuBrowsers.values()) browser.shutdown();
        this.menuBrowsers.clear();
        this.menuConfigs.clear();
        this.browserService = null;
        this.config = null;
    }

    public SearchDialogService searchDialog() {
        return this.dialogService;
    }

    public PersonalizationDialogService personalizationDialog() {
        return this.personalizationDialog;
    }

    private void notifyAdminsIndexBuildStarted() {
        if (!this.getConfig().getBoolean("indexing.notify-admins-on-startup", true)) return;
        LinkedHashSet<String> detected = new LinkedHashSet<>();
        for (var plugin : Bukkit.getPluginManager().getPlugins()) {
            if (!plugin.isEnabled()) continue;
            PluginFingerprints.identify(plugin)
                    .ifPresent(fingerprint -> detected.add(fingerprint.displayName()));
        }
        if (detected.isEmpty()) return;
        var message = this.lang().message("messages.index-build-started",
                "plugins", String.join(", ", detected));
        for (Player player : Bukkit.getOnlinePlayers()) {
            if (player.hasPermission("craftenginerecipe.admin")) player.sendMessage(message);
        }
    }

    public static MiniMessage mini() {
        return MiniMessageHolder.INSTANCE;
    }

    private static final class MiniMessageHolder {
        private static final MiniMessage INSTANCE = MiniMessage.builder().build();

        private MiniMessageHolder() {
        }
    }
}
