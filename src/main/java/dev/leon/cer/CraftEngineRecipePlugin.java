package dev.leon.cer;

import dev.leon.cer.command.CerCommand;
import dev.leon.cer.config.CerConfig;
import dev.leon.cer.data.CraftEngineData;
import dev.leon.cer.data.RecipeIndex;
import dev.leon.cer.dialog.SearchDialogService;
import dev.leon.cer.gui.BrowserService;
import dev.leon.cer.listener.HotkeyListener;
import dev.leon.cer.gui.GuiSession;
import dev.leon.cer.metrics.MetricsService;
import dev.leon.cer.scheduler.TaskDispatcher;
import dev.leon.cer.i18n.LanguageManager;
import dev.leon.cer.resource.ResourcePackInstaller;
import net.kyori.adventure.text.minimessage.MiniMessage;
import org.bukkit.Bukkit;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.event.Listener;
import org.bukkit.event.EventHandler;
import org.bukkit.event.player.PlayerQuitEvent;

import java.io.File;
import java.io.IOException;
import java.util.function.Consumer;

public final class CraftEngineRecipePlugin extends JavaPlugin implements Listener {

    private static volatile CraftEngineRecipePlugin instance;

    private TaskDispatcher tasks;
    private CerConfig config;
    private LanguageManager language;
    private CraftEngineData data;
    private RecipeIndex recipeIndex;
    private BrowserService browserService;
    private SearchDialogService dialogService;
    private MetricsService metrics;

    public static CraftEngineRecipePlugin get() {
        return instance;
    }

    @Override
    public void onLoad() {
        this.language = new LanguageManager(this.getDataFolder().toPath(), this::getResource, this.getLogger());
        this.saveDefaultConfig();
        var settings = this.getConfig();
        this.language.load(settings.contains("language", true)
                ? settings.getString("language") : settings.getString("locale", "zh_CN"));
        this.installResourcePack();
    }

    @Override
    public void onEnable() {
        instance = this;
        this.tasks = new TaskDispatcher(this, error -> this.getLogger().log(java.util.logging.Level.SEVERE,
                this.language.text("logs.player-task-failed"), error));
        this.config = new CerConfig(this);
        this.config.load();
        this.saveExampleFile();
        this.data = new CraftEngineData(this);
        this.recipeIndex = new RecipeIndex(this);
        this.data.attachListener();
        this.dialogService = new SearchDialogService(this);
        this.browserService = new BrowserService(this, this.config, this.data, this.recipeIndex);
        new CerCommand(this).register();
        Bukkit.getPluginManager().registerEvents(new HotkeyListener(this), this);
        Bukkit.getPluginManager().registerEvents(this, this);
        this.metrics = new MetricsService(this);
        this.metrics.reload();
        // Also supports enabling this plugin after CraftEngine has already loaded.
        this.tasks.global(() -> this.data.rebuild(ok -> {}));
        this.getLogger().info(this.language.text("logs.enabled"));
    }

    private void installResourcePack() {
        var local = this.getDataFolder().toPath().resolve(ResourcePackInstaller.PACK_DIRECTORY);
        var target = this.getDataFolder().toPath().resolveSibling("CraftEngine")
                .resolve("resources").resolve(ResourcePackInstaller.PACK_DIRECTORY);
        try {
            var result = ResourcePackInstaller.install(this.getFile().toPath(), local, target);
            this.logResourceCopy(local, result.localFiles());
            if (result.installed()) {
                this.getLogger().info(this.language.text("logs.pack-first-installed", "path", target.toString(),
                        "count", String.valueOf(result.craftEngineFiles())));
            }
        } catch (IOException e) {
            this.getLogger().severe(this.language.text("logs.pack-install-failed", "error", e.getMessage()));
        }
    }

    private void replenishResourcePack() {
        var local = this.getDataFolder().toPath().resolve(ResourcePackInstaller.PACK_DIRECTORY);
        try {
            this.logResourceCopy(local, ResourcePackInstaller.replenish(this.getFile().toPath(), local));
        } catch (IOException e) {
            this.getLogger().severe(this.language.text("logs.pack-install-failed", "error", e.getMessage()));
        }
    }

    private void logResourceCopy(java.nio.file.Path local, int count) {
        this.getLogger().info(this.language.text("logs.pack-copy-ready", "path", local.toString(),
                "count", String.valueOf(count)));
    }

    private void saveExampleFile() {
        File example = new File(this.getDataFolder(), "categories-example.yml");
        if (!example.exists()) {
            this.saveResource("categories-example.yml", false);
        }
    }

    @Override
    public void onDisable() {
        if (this.data != null) {
            this.data.detachListener();
        }
        if (this.browserService != null) this.browserService.shutdown();
        if (this.metrics != null) this.metrics.shutdown();
        GuiSession.clearAll();
        if (this.tasks != null) this.tasks.cancelGlobalAndAsync();
        instance = null;
        if (this.language != null) this.getLogger().info(this.language.text("logs.disabled"));
    }

    public void reloadSelf(Consumer<Boolean> completion) {
        this.tasks.global(() -> this.data.rebuild(true, completion));
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent event) {
        this.browserService.forgetPlayer(event.getPlayer());
        GuiSession.clear(event.getPlayer());
        this.browserService.recipes().cancelRotation(event.getPlayer());
    }

    /** Called globally only after every viewer has finished closing their old menu. */
    public void reloadSettings() {
        this.config.load();
        this.replenishResourcePack();
        this.metrics.reload();
        new CerCommand(this).register();
    }

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

    public BrowserService browser() {
        return this.browserService;
    }

    public SearchDialogService searchDialog() {
        return this.dialogService;
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
