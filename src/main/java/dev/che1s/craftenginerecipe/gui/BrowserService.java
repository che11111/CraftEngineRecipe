package dev.che1s.craftenginerecipe.gui;

import dev.che1s.craftenginerecipe.CraftEngineRecipePlugin;
import dev.che1s.craftenginerecipe.config.CerConfig;
import dev.che1s.craftenginerecipe.config.MenuView;
import dev.che1s.craftenginerecipe.category.CategoryNamespaces;
import dev.che1s.craftenginerecipe.category.CategoryItems;
import dev.che1s.craftenginerecipe.data.CraftEngineData;
import dev.che1s.craftenginerecipe.recipe.RecipeIndex;
import dev.che1s.craftenginerecipe.recipe.RecipeEntry;
import dev.che1s.craftenginerecipe.recipe.ExternalRecipeSnapshot;
import dev.che1s.craftenginerecipe.preferences.PlayerPreferences;
import dev.che1s.craftenginerecipe.recipe.access.RecipeAccessPolicy;
import dev.che1s.craftenginerecipe.recipe.query.CraftableRecipeService;
import dev.che1s.craftenginerecipe.debug.Diagnostics;
import dev.che1s.craftenginerecipe.util.DisplayNames;
import dev.che1s.craftenginerecipe.recipe.access.HeldRecipeScope;
import dev.che1s.craftenginerecipe.util.ItemBridge;
import net.kyori.adventure.text.Component;
import net.momirealms.craftengine.bukkit.api.CraftEngineBlocks;
import net.momirealms.craftengine.bukkit.api.CraftEngineItems;
import net.momirealms.craftengine.core.block.ImmutableBlockState;
import net.momirealms.craftengine.core.plugin.gui.category.Category;
import net.momirealms.craftengine.core.util.Key;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.block.Block;
import org.bukkit.entity.Player;
import org.bukkit.event.inventory.ClickType;
import org.bukkit.event.inventory.InventoryCloseEvent;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;
import xyz.xenondevs.invui.gui.Gui;
import xyz.xenondevs.invui.gui.Markers;
import xyz.xenondevs.invui.gui.PagedGui;
import xyz.xenondevs.invui.item.Item;
import xyz.xenondevs.invui.item.ItemBuilder;
import xyz.xenondevs.invui.item.ItemWrapper;
import xyz.xenondevs.invui.window.Window;
import xyz.xenondevs.invui.window.WindowManager;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.HashMap;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.LinkedHashSet;
import java.util.function.Predicate;
import java.util.function.BooleanSupplier;
import java.util.function.Supplier;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;

public final class BrowserService {

    private static final char EMPTY_CHAR = '#';
    static final char CONTENT_BLANK_CHAR = '\u0001';

    private final CraftEngineRecipePlugin plugin;
    private final Map<MenuView, CerConfig> configs;
    private final CerConfig config;
    private final Map<MenuView, BrowserActionItems> viewActionItems;
    private final CraftEngineData data;
    private final RecipeIndex recipeIndex;
    private final RecipeGuiService recipeGui;
    private final BrowserCategoryView categoryView;
    private final WorkstationBrowserService workstations;
    private final ManagementBrowserService management;
    private final BrowserActionItems actionItems;
    private final GuiSessionStore sessions;
    private final MenuLifecycle lifecycle = new MenuLifecycle();
    private final MenuActionGate<UUID> actions = new MenuActionGate<>();
    private final Map<UUID, Player> viewers = new ConcurrentHashMap<>();
    private final Map<UUID, GuiSession.Snapshot> craftableReturnViews = new ConcurrentHashMap<>();
    private final Object viewerLock = new Object();

    /** 同时登记正在构建的窗口，避免重载在构建途中改变配置。 */
    public boolean claim(Player player) {
        synchronized (this.viewerLock) {
            if (!this.data.isReady() || !player.isOnline()) return false;
            this.viewers.put(player.getUniqueId(), player);
            return true;
        }
    }

    public BrowserService(CraftEngineRecipePlugin plugin, Map<MenuView, CerConfig> configs,
                          CraftEngineData data, RecipeIndex recipeIndex) {
        this.plugin = plugin;
        this.configs = Map.copyOf(configs);
        // NAMESPACES 是主浏览界面；其余视图缺少专属配置时回退到它，
        // 保证任何视图都能拿到一份完整可用的布局与按钮定义。
        this.config = this.configs.getOrDefault(MenuView.NAMESPACES, this.configs.values().iterator().next());
        this.data = data;
        this.recipeIndex = recipeIndex;
        this.sessions = plugin.sessions();
        this.recipeGui = new RecipeGuiService(plugin, this.config, data, recipeIndex, this);
        this.categoryView = new BrowserCategoryView(plugin, data, this);
        this.workstations = new WorkstationBrowserService(plugin, this,
                this.configs.getOrDefault(MenuView.WORKSTATIONS, this.config), data, recipeIndex, this.recipeGui);
        this.management = new ManagementBrowserService(plugin, this);
        Map<MenuView, BrowserActionItems> actions = new java.util.EnumMap<>(MenuView.class);
        this.configs.forEach((view, viewConfig) ->
                actions.put(view, new BrowserActionItems(plugin, viewConfig, this)));
        this.viewActionItems = Map.copyOf(actions);
        this.actionItems = this.viewActionItems.getOrDefault(MenuView.NAMESPACES,
                new BrowserActionItems(plugin, this.config, this));
    }

    CerConfig ui(MenuView view) {
        return this.configs.getOrDefault(view, this.config);
    }

    CerConfig config() {
        return this.config;
    }

    CerConfig managementUi() {
        return this.ui(MenuView.MANAGEMENT);
    }

    private BrowserActionItems actions(MenuView view) {
        return this.viewActionItems.getOrDefault(view, this.actionItems);
    }

    Item searchItem(MenuView view, java.util.function.Consumer<Player> action) {
        return this.actions(view).searchItem(action);
    }

    private MenuView browserView(Player player) {
        return GuiSession.of(player).unapplied() ? MenuView.UNAPPLIED : MenuView.NAMESPACES;
    }

    private MenuView currentView(Player player) {
        // 判定顺序即优先级：管理模式 > 可合成视图 > 合成器页 > 未应用命名空间 > 主浏览页。
        GuiSession session = GuiSession.of(player);
        if (session.managerMode()) return MenuView.MANAGEMENT;
        if (session.craftableMode()) return MenuView.CRAFTABLE;
        if (session.workstationId() != null) return MenuView.WORKSTATIONS;
        if (session.unapplied()) return MenuView.UNAPPLIED;
        return MenuView.NAMESPACES;
    }

    CerConfig currentUi(Player player) {
        return this.ui(this.currentView(player));
    }

    public CerConfig configFor(Player player) {
        return this.currentUi(player);
    }

    public int viewerCount() { return this.viewers.size(); }
    public int pendingActionCount() { return this.actions.pendingCount(); }

    /** 导航前先离开 InvUI 的入站封包循环，并合并积压点击。 */
    public void handleClick(Player player, Runnable action) {
        this.plugin.diagnostics().increment(Diagnostics.Counter.MENU_CLICKS);
        this.deferMenuAction(player, action);
    }

    public void deferMenuAction(Player player, Runnable action) {
        Window expected = WindowManager.getInstance().getOpenWindow(player);
        // 快捷键可能从 InvUI 之外触发，此时旧窗口和替代窗口都可能为 null。
        // 不得覆盖玩家刚打开的 Bukkit 菜单。
        Inventory expectedInventory = player.getOpenInventory().getTopInventory();
        if (!this.actions.offer(player.getUniqueId(), expected, this.recipeIndex.generation(), () -> {
            try (var timing = this.plugin.diagnostics().measure(Diagnostics.Operation.MENU_ACTION)) {
                action.run();
            }
        }, task -> this.plugin.tasks().playerLater(player, task, 1) != null,
                () -> WindowManager.getInstance().getOpenWindow(player), this.recipeIndex::generation,
                () -> this.plugin.isEnabled() && player.isOnline() && this.data.isReady()
                        && player.getOpenInventory().getTopInventory() == expectedInventory)) {
            this.plugin.diagnostics().increment(Diagnostics.Counter.CLICK_THROTTLED);
        }
    }

    public RecipeGuiService recipes() {
        return this.recipeGui;
    }

    public void openDefault(Player player) {
        this.clearCraftableMode(player);
        if (!this.claim(player)) return;
        GuiSession session = GuiSession.of(player);
        session.managerMode(false);
        session.leaveWorkstations();
        for (BrowserCategoryView.BarEntry entry : this.categoryView.barEntries(player)) {
            if (entry instanceof BrowserCategoryView.VanillaItemsEntry
                    && this.data.namespaces().canView(player, "minecraft")) {
                this.openVanillaItems(player);
                return;
            }
            if (entry instanceof BrowserCategoryView.CategoryEntry category
                    && this.data.namespaces().canView(player, category.category().id().namespace())) {
                break;
            }
        }
        List<Category> tops = this.categoryView.visibleCategories(player);
        if (tops.isEmpty()) {
            if (this.data.categoriesById().isEmpty()) {
                this.openItemList(player, null, this.plugin.lang(player).text("ui.common.all-items"), 0, null);
            } else {
                this.openEmptyContent(player);
            }
            return;
        }
        this.openCategory(player, tops.get(0).id(), 0);
    }

    public void openApplied(Player player) {
        GuiSession session = GuiSession.of(player);
        session.unapplied(false);
        session.craftableMode(false);
        session.itemSearchKeyword(null);
        this.craftableReturnViews.remove(player.getUniqueId());
        if (this.rememberLastPage(player) && this.openRemembered(player)) return;
        this.openEntry(player);
    }

    public void openUnapplied(Player player) {
        GuiSession session = GuiSession.of(player);
        session.unapplied(true);
        session.craftableMode(false);
        session.itemSearchKeyword(null);
        this.craftableReturnViews.remove(player.getUniqueId());
        this.openEntry(player);
    }

    /** 指令入口：忽略个人主页偏好，直接打开按物品种类分类。 */
    public void openNamespaceBrowser(Player player) {
        GuiSession session = GuiSession.of(player);
        session.unapplied(false);
        session.managerMode(false);
        session.itemSearchKeyword(null);
        this.craftableReturnViews.remove(player.getUniqueId());
        this.openDefault(player);
    }

    /** 指令入口：忽略个人主页偏好，直接打开按合成器分类。 */
    public void openWorkstationBrowser(Player player) {
        GuiSession session = GuiSession.of(player);
        session.unapplied(false);
        session.managerMode(false);
        session.craftableMode(false);
        session.leaveWorkstations();
        this.craftableReturnViews.remove(player.getUniqueId());
        this.workstations.open(player);
    }

    public void toggleCraftableView(Player player) {
        GuiSession session = GuiSession.of(player);
        if (!session.craftableMode()) {
            // 进入临时视图前保存当前浏览位置，再次右键时原样恢复。
            this.craftableReturnViews.put(player.getUniqueId(), session.snapshot());
            this.openCraftableView(player);
            return;
        }
        session.craftableMode(false);
        GuiSession.Snapshot previous = this.craftableReturnViews.remove(player.getUniqueId());
        if (previous == null || !this.openSnapshot(player, previous)) this.openDefault(player);
    }

    private void openCraftableView(Player player) {
        if (!this.claim(player)) return;
        CerConfig ui = this.ui(MenuView.CRAFTABLE);
        GuiSession session = GuiSession.of(player);
        this.craftableReturnViews.putIfAbsent(player.getUniqueId(), session.snapshot());
        // 可合成列表不属于分类导航，统一使用空分类上下文。
        session.enter(null, 0, null, 0);
        session.craftableMode(true);
        List<Key> craftable = CraftableRecipeService.find(player,
                this.plugin.externalRecipes().snapshot(), this.visibility(player)::canView,
                this::inventoryItemKey);
        List<Item> content = new LazyItems<>(craftable,
                id -> lazyItem(() -> this.itemEntry(player, id, null)));
        if (content.isEmpty()) {
            content = List.of(Item.simple(ItemBridge.button(ui.empty().material(),
                    ui.mm(ui.empty().name()), this.buttonLore(ui, ui.empty()))));
        }
        Gui gui = this.buildContentOnlyLayout(player, content, MenuView.CRAFTABLE, this.craftableBackItem(ui));
        this.openWindow(Window.builder().setViewer(player).setUpperGui(gui)
                .setTitle(ui.titleWithName(ui.browserTitle(), ui.styledName(
                        ui.categoryTitleNamePrefix(), this.plugin.lang(player).text("ui.craftable.name")))).build());
    }

    public void openNamespaceManager(Player player) {
        if (!player.hasPermission("craftenginerecipe.admin")) return;
        this.management.openHome(player);
    }

    public void reopenManagement(Player player) {
        if (!player.hasPermission("craftenginerecipe.admin")) return;
        this.management.reopen(player);
    }

    public void openWorkstation(Player player, String workstationId) {
        GuiSession.of(player).managerMode(false);
        this.workstations.openById(player, workstationId);
    }

    /** 查询产出主手物品的配方，绝不打开材料用途视图。 */
    public void openHeldRecipe(Player player) {
        this.runClaimedQuery(player, () -> this.openHeldRecipeClaimed(player));
    }

    /** 查询产出玩家准星所指方块的配方。 */
    public void openTargetBlockRecipe(Player player) {
        this.runClaimedQuery(player, () -> this.openTargetBlockRecipeClaimed(player));
    }

    private void runClaimedQuery(Player player, BooleanSupplier query) {
        boolean alreadyTracked = this.viewers.containsKey(player.getUniqueId());
        if (!this.claim(player)) {
            if (player.isOnline() && !this.data.isReady()) {
                player.sendMessage(this.plugin.lang(player).message("messages.data-not-ready",
                        "reason", this.plugin.lang(player).text("messages.not-ready-reason")));
                Sounds.play(player, this.config.sounds().queryFailure());
            }
            return;
        }
        try {
            boolean opened = query.getAsBoolean();
            Sounds.play(player, opened ? this.config.sounds().querySuccess() : this.config.sounds().queryFailure());
        } finally {
            // 查询失败时没有需要由后续重载关闭的背包或对话框。
            if (!alreadyTracked && !this.lifecycle.contains(player.getUniqueId())) {
                this.viewers.remove(player.getUniqueId(), player);
            }
        }
    }

    private boolean openHeldRecipeClaimed(Player player) {
        ItemStack held = player.getInventory().getItemInMainHand();
        if (held.getType().isAir() || held.getAmount() <= 0) {
            player.sendMessage(this.plugin.lang(player).message("messages.hand-empty"));
            return false;
        }
        Key id;
        try {
            id = this.inventoryItemKey(held);
        } catch (RuntimeException | LinkageError error) {
            this.plugin.getLogger().warning(this.plugin.lang().text("logs.hand-resolve-failed", "error", error.toString()));
            player.sendMessage(this.plugin.lang(player).message("messages.hand-resolve-failed"));
            return false;
        }
        return this.openProducedRecipe(player, id, "messages.hand-item-unavailable", "messages.hand-no-recipe");
    }

    private boolean openTargetBlockRecipeClaimed(Player player) {
        Key id;
        try {
            Block target = TargetBlockQuery.trace(player);
            if (target == null) {
                player.sendMessage(this.plugin.lang(player).message("messages.target-block-empty"));
                return false;
            }
            ImmutableBlockState customState = CraftEngineBlocks.getCustomBlockState(target);
            id = customState == null ? null : customState.settings().itemId();
            if (id == null) {
                Material item = target.getBlockData().getPlacementMaterial();
                if (item == null || item.isAir() || !item.isItem()) {
                    player.sendMessage(this.plugin.lang(player).message("messages.target-block-empty"));
                    return false;
                }
                id = Key.of(item.getKey().toString());
            }
        } catch (RuntimeException | LinkageError error) {
            this.plugin.getLogger().warning(this.plugin.lang().text("logs.target-block-resolve-failed", "error", error.toString()));
            player.sendMessage(this.plugin.lang(player).message("messages.target-block-resolve-failed"));
            return false;
        }
        return this.openProducedRecipe(player, id, "messages.target-block-unavailable", "messages.target-block-no-recipe");
    }

    private boolean openProducedRecipe(Player player, Key id, String unavailableMessage, String noRecipeMessage) {
        var visibility = this.visibility(player);
        if (!visibility.canQuery(id)) {
            player.sendMessage(this.plugin.lang(player).message(unavailableMessage));
            return false;
        }
        if (!this.recipeGui.hasContent(id, false, visibility)) {
            player.sendMessage(this.plugin.lang(player).message(noRecipeMessage, "id", id.toString()));
            return false;
        }
        GuiSession session = GuiSession.of(player);
        Key category = session.currentCategory();
        if (session.unapplied() || category != null && (this.data.category(category).isEmpty()
                || this.data.namespaces().isCategoryHidden(category.toString())
                || !this.data.namespaces().canView(player, category.namespace()))) {
            session.enter(null, 0, null);
        }
        session.unapplied(false);
        // 查询反馈音效会替代进入音效；正常窗口跟踪仍负责后续关闭处理。
        boolean opened = this.recipeGui.open(player, id, false, 0, visibility);
        if (!opened) {
            player.sendMessage(this.plugin.lang(player).message(noRecipeMessage, "id", id.toString()));
        }
        return opened;
    }

    private void openEntry(Player player) {
        this.lifecycle.requestEntry(player.getUniqueId());
        try {
            if (this.plugin.playerPreferences() != null
                    && this.plugin.playerPreferences().landingPage(player.getUniqueId())
                    == PlayerPreferences.LandingPage.WORKSTATIONS) {
                GuiSession.of(player).managerMode(false);
                this.workstations.open(player);
            } else {
                this.openDefault(player);
            }
        } finally {
            this.lifecycle.cancelEntry(player.getUniqueId());
        }
    }

    private boolean openRemembered(Player player) {
        long lifetime = java.util.concurrent.TimeUnit.MINUTES.toMillis(
                this.config.rememberLastPageMinutes());
        GuiSession.Snapshot saved = this.sessions.get(player.getUniqueId(), lifetime);
        return this.openSnapshot(player, saved);
    }

    public boolean openSnapshot(Player player, GuiSession.Snapshot saved) {
        if (saved == null) return false;
        GuiSession session = GuiSession.of(player);
        try {
            Map<String, RecipeEntry.Output> workstationSelections = new HashMap<>();
            if (saved.workstationId() != null) {
                for (RecipeEntry.Output output : this.recipeIndex.outputs(saved.workstationId(), player)) {
                    workstationSelections.put(output.result().toString(), output);
                }
            }
            session.restore(saved, workstationSelections::get, this.recipeIndex.generation());
        } catch (RuntimeException error) {
            this.plugin.getLogger().warning(this.plugin.lang().text("logs.session-load-failed",
                    "error", "invalid saved menu position"));
            return false;
        }
        if (session.recipeItem() != null) {
            boolean opened = session.entrySelection() != null
                    ? this.recipeGui.openWorkstation(player, session.entrySelection(), session.recipeIndex(),
                            session.recipeGeneration())
                    : this.recipeGui.hasContent(player, session.recipeItem(), session.recipeUsageMode())
                            && this.recipeGui.open(player, session.recipeItem(), session.recipeUsageMode(),
                                    session.recipeIndex());
            if (opened) return true;
        }
        if (session.itemSearchKeyword() != null) {
            this.openItemList(player, null, session.itemSearchKeyword(), session.contentPage(),
                    session.itemSearchKeyword());
            return true;
        }
        if (session.workstationId() != null) {
            this.workstations.reopen(player);
            return true;
        }
        Key category = session.currentCategory();
        if (category != null && this.data.category(category).isPresent()
                && !this.data.namespaces().isCategoryHidden(category.toString())
                && this.data.namespaces().canView(player, category.namespace())) {
            this.openCategory(player, category, session.categoryPage(), session.contentPage());
            return true;
        }
        if (category == null && this.isVanillaHome(player)) {
            this.openVanillaItems(player, session.contentPage());
            return true;
        }
        return false;
    }

    /** 替代窗口属于同一次菜单访问；旧窗口的关闭回调不得关闭新的访问。 */
    public void openWindow(Window window) {
        Player player = window.getViewer();
        if (!this.claim(player)) return;
        CerConfig ui = this.currentUi(player);
        Object token = new Object();
        window.addOpenHandler(() -> {
            if (this.lifecycle.opened(player.getUniqueId(), token)) {
                for (var sound : ui.sounds().openMenu()) {
                    Sounds.play(player, sound.spec(), sound.defaultVolume());
                }
            }
        });
        window.addCloseHandler(reason -> {
            if (!this.lifecycle.isCurrent(player.getUniqueId(), token)) return;
            if (reason == InventoryCloseEvent.Reason.DISCONNECT || !this.plugin.isEnabled()) {
                this.forgetPlayer(player);
                return;
            }
            // InvUI 会先关闭旧窗口再打开替代窗口。等待一个 tick，
            // 用于区分内部导航与真正离开本插件菜单。
            this.plugin.tasks().playerLater(player, () -> {
                if (this.lifecycle.closed(player.getUniqueId(), token) && player.isOnline()) {
                    for (var sound : ui.sounds().closeMenu()) {
                        Sounds.play(player, sound.spec(), sound.defaultVolume());
                    }
                    if (reason == InventoryCloseEvent.Reason.PLAYER) this.rememberPlayer(player);
                    this.viewers.remove(player.getUniqueId(), player);
                }
            }, 1);
        });
        window.open();
    }

    public void forgetPlayer(Player player) {
        this.actions.cancel(player.getUniqueId());
        this.lifecycle.forget(player.getUniqueId());
        this.viewers.remove(player.getUniqueId(), player);
        this.craftableReturnViews.remove(player.getUniqueId());
        this.management.forget(player);
    }

    public void rememberPlayer(Player player) {
        if (GuiSession.of(player).craftableMode()) {
            this.craftableReturnViews.remove(player.getUniqueId());
            this.sessions.clear(player.getUniqueId());
            return;
        }
        if (!this.rememberLastPage(player)) return;
        this.sessions.remember(player.getUniqueId(), GuiSession.of(player).snapshot());
    }

    /** 搜索对话框拥有独立生命周期；从背包切换到对话框时不播放关闭音效。 */
    public void leaveForSearch(Player player) {
        this.leaveForDialog(player);
    }

    /** Dialog 窗口拥有独立生命周期；从背包切换到对话框时不播放关闭音效。 */
    public void leaveForDialog(Player player) {
        this.lifecycle.forget(player.getUniqueId());
        this.viewers.put(player.getUniqueId(), player);
        player.closeInventory();
    }

    private boolean rememberLastPage(Player player) {
        return this.plugin.playerPreferences() != null && this.plugin.playerPreferences().enabled()
                ? this.plugin.playerPreferences().rememberLastPage(player.getUniqueId())
                : this.config.rememberLastPage();
    }

    private void clearCraftableMode(Player player) {
        // 离开临时视图时同步清理模式标记和待恢复快照，避免泄漏到下一次打开。
        GuiSession.of(player).craftableMode(false);
        this.craftableReturnViews.remove(player.getUniqueId());
    }

    private void openEmptyContent(Player player) {
        try (var timing = this.plugin.diagnostics().measure(Diagnostics.Operation.BROWSER_OPEN)) {
            MenuView view = this.browserView(player);
            CerConfig ui = this.ui(view);
            List<Item> content = List.of(Item.simple(ItemBridge.button(ui.empty().material(),
                    ui.mm(ui.empty().name()), this.buttonLore(ui, ui.empty()))));
            Gui gui = this.buildLayout(player, content, null, 0, 0, false, view);
            this.openWindow(Window.builder().setViewer(player)
                    .setTitle(ui.titleWithName(ui.browserTitle(), ui.styledName(
                            ui.categoryTitleNamePrefix(), this.plugin.lang(player).text("ui.common.all-items"))))
                    .setUpperGui(gui)
                    .build());
        }
    }

    public void openCategory(Player player, Key categoryId, int categoryPage) {
        this.openCategory(player, categoryId, categoryPage, 0);
    }

    private void openCategory(Player player, Key categoryId, int categoryPage, int contentPage) {
        this.clearCraftableMode(player);
        try (var timing = this.plugin.diagnostics().measure(Diagnostics.Operation.BROWSER_OPEN)) {
            if (!this.claim(player)) return;
            Category category = this.data.category(categoryId).orElse(null);
            if (category == null || this.data.namespaces().isCategoryHidden(categoryId.toString())) {
                player.sendMessage(this.plugin.lang(player).message(
                        "messages.category-not-found", "id", categoryId.toString()));
                return;
            }
            if (!this.data.namespaces().canView(player, category.id().namespace())) {
                player.sendMessage(this.categoryView.accessDeniedComponent(
                        player, category.id().namespace(), this.currentUi(player)));
                return;
            }
            List<ContentEntry> entries = this.collectEntries(category, player);
            List<Item> contentItems = new LazyItems<>(entries,
                    entry -> lazyItem(() -> entry.toItem(this, player, category)));
            boolean showBack = !this.isTopLevel(categoryId);
            GuiSession session = GuiSession.of(player);
            session.enter(categoryId, categoryPage, showBack ? this.findParentOf(categoryId) : null, contentPage);
            MenuView view = this.browserView(player);
            CerConfig ui = this.ui(view);
            Gui gui = this.buildLayout(player, contentItems, categoryId, categoryPage, contentPage, showBack, view);
            this.openWindow(Window.builder().setViewer(player)
                    .setTitle(ui.titleWithName(ui.browserTitle(),
                            ui.styledName(ui.categoryTitleNamePrefix(), this.plainName(player, category, ui))))
                    .setUpperGui(gui)
                    .build());
        }
    }

    public void openItemList(Player player, Key parentCategory, String title, int categoryPage, String keyword) {
        this.openItemList(player, parentCategory, title, categoryPage, keyword, null);
    }

    private void openItemList(Player player, Key parentCategory, String title, int categoryPage, String keyword,
                              Set<Key> restrictedItems) {
        this.clearCraftableMode(player);
        try (var timing = this.plugin.diagnostics().measure(Diagnostics.Operation.BROWSER_OPEN)) {
            if (!this.claim(player)) return;
            GuiSession session = GuiSession.of(player);
            session.leaveWorkstations();
            boolean searching = keyword != null && !keyword.isBlank();
            session.itemSearchKeyword(searching ? keyword : null);
            List<ItemKeyEntry> entries = new ArrayList<>();
            if (searching) {
                Set<Key> scope = this.visibleItemIds(player);
                int max = this.config.searchMaxResults();
                for (Key id : this.searchItems(keyword, max,
                        key -> scope.contains(key) && (restrictedItems == null || restrictedItems.contains(key)))) {
                    entries.add(new ItemKeyEntry(id));
                }
            } else if (restrictedItems != null) {
                for (Key id : restrictedItems) entries.add(new ItemKeyEntry(id));
            } else {
                BrowserVisibility visibility = this.visibility(player);
                for (Key id : this.data.items().keySet()) {
                    if (this.hidesWithoutRecipes(player, id, visibility)) continue;
                    entries.add(new ItemKeyEntry(id));
                }
                for (Key id : visibility.externalItems()) {
                    if (!this.data.items().containsKey(id)
                            && !this.hidesWithoutRecipes(player, id, visibility)) {
                        entries.add(new ItemKeyEntry(id));
                    }
                }
            }
            Category owner = parentCategory == null ? null : this.data.category(parentCategory).orElse(null);
            List<Item> contentItems = new LazyItems<>(entries,
                    entry -> lazyItem(() -> entry.toItem(this, player, owner)));
            if (contentItems.isEmpty()) {
                MenuView view = this.browserView(player);
                CerConfig ui = this.ui(view);
                contentItems = List.of(Item.simple(ItemBridge.button(ui.empty().material(),
                        ui.mm(ui.empty().name()), this.buttonLore(ui, ui.empty()))));
            }
            MenuView view = this.browserView(player);
            CerConfig ui = this.ui(view);
            Gui gui = this.buildLayout(player, contentItems, parentCategory,
                    GuiSession.of(player).categoryPage(), categoryPage, parentCategory != null, view);
            Component titleComponent = searching
                    ? ui.mmEscaped(ui.searchResultTitle(), "keyword", keyword)
                    : ui.titleWithName(ui.browserTitle(),
                            ui.styledName(ui.categoryTitleNamePrefix(), title));
            this.openWindow(Window.builder().setViewer(player)
                    .setTitle(titleComponent)
                    .setUpperGui(gui)
                    .build());
        }
    }

    void openVanillaItems(Player player) {
        this.openVanillaItems(player, 0);
    }

    private void openVanillaItems(Player player, int contentPage) {
        CategoryNamespaces.NamespaceEntry namespace = this.data.namespaces().findNamespace("minecraft");
        Set<Key> items = this.visibleVanillaItems(player, namespace);
        GuiSession.of(player).enter(null, 0, null, contentPage);
        String configured = LocalizedConfigText.resolve(this.plugin.lang(player),
                this.data.namespaces().iconNameOf("minecraft").resolve(this.plugin.lang(player)));
        String name = configured == null || configured.isBlank()
                ? this.plugin.lang(player).text("ui.vanilla-items.name") : configured;
        name = name.replace("<name>", this.plugin.lang(player).text("ui.vanilla-items.name"));
        this.openItemList(player, null, name, contentPage, null, items);
    }

    Set<Key> visibleVanillaItems(Player player, CategoryNamespaces.NamespaceEntry namespace) {
        BrowserVisibility visibility = this.visibility(player);
        return prepareVanillaItems(vanillaItemKeys(this.data.items().keySet()),
                namespace != null && namespace.sortByNamespace(),
                namespace != null && namespace.hideItemsWithoutRecipes(),
                id -> this.recipeGui.hasAnyContent(id, visibility));
    }

    static Set<Key> vanillaItemKeys(Collection<Key> itemIds) {
        return vanillaItemKeys(itemIds, List.of(Material.values()));
    }

    static Set<Key> vanillaItemKeys(Collection<Key> itemIds, Collection<Material> materials) {
        LinkedHashSet<Key> keys = materials.stream()
                .filter(material -> !material.isLegacy() && material.isItem() && !material.isAir())
                .map(material -> Key.of(material.getKey().toString()))
                .filter(id -> "minecraft".equals(id.namespace()))
                .collect(java.util.stream.Collectors.toCollection(LinkedHashSet::new));
        itemIds.stream()
                .filter(id -> "minecraft".equals(id.namespace()))
                .forEach(keys::add);
        return java.util.Collections.unmodifiableSet(keys);
    }

    static Set<Key> prepareVanillaItems(Collection<Key> itemIds, boolean sortByNamespace,
                                        boolean hideWithoutRecipes, Predicate<Key> hasRecipe) {
        java.util.stream.Stream<Key> stream = itemIds.stream();
        if (sortByNamespace) {
            stream = stream.sorted(Comparator.comparing(Key::namespace).thenComparing(Key::value));
        }
        if (hideWithoutRecipes) stream = stream.filter(hasRecipe);
        return stream.collect(java.util.stream.Collectors.toCollection(LinkedHashSet::new));
    }

    /** 每个窗口拥有自己的懒加载物品；依赖玩家的物品堆绝不会在玩家之间共享。 */
    static Item lazyItem(Supplier<Item> factory) {
        Supplier<Item> memoized = new Supplier<>() {
            private Item value;
            @Override
            public Item get() {
                if (this.value == null) this.value = factory.get();
                return this.value;
            }
        };
        return Item.builder()
                .setItemProvider(viewer -> memoized.get().getItemProvider(viewer))
                .addClickHandler((item, click) -> memoized.get().handleClick(click.clickType(), click.player(), click))
                .build();
    }

    /** 重载会等待实体清理完成，但绝不同步等待其他区域线程。 */
    public CompletableFuture<Void> closeAll() {
        this.actions.clear();
        List<CompletableFuture<Void>> pending = new ArrayList<>();
        List<Player> viewers;
        synchronized (this.viewerLock) {
            viewers = List.copyOf(this.viewers.values());
        }
        for (Player player : viewers) {
            pending.add(this.plugin.tasks().player(player, () -> {
                boolean inventory = this.lifecycle.contains(player.getUniqueId());
                this.forgetPlayer(player); // 关闭前先静默相关回调
                this.recipeGui.cancelRotation(player);
                GuiSession.clear(player);
                if (inventory) {
                    if (WindowManager.getInstance().getOpenWindow(player) != null) player.closeInventory();
                } else {
                    DialogCompatibility.closeDialog(player);
                }
            }).whenComplete((ignored, error) -> {
                this.viewers.remove(player.getUniqueId(), player);
                this.lifecycle.forget(player.getUniqueId());
                GuiSession.clear(player);
            }));
        }
        return CompletableFuture.allOf(pending.toArray(CompletableFuture[]::new));
    }

    /** 插件禁用后无法再提交由该插件拥有的任务。 */
    public void shutdown() {
        this.actions.clear();
        this.recipeGui.shutdown();
        for (Player player : List.copyOf(this.viewers.values())) {
            boolean inventory = this.lifecycle.contains(player.getUniqueId());
            boolean ownedRegion = Bukkit.isOwnedByCurrentRegion(player);
            this.forgetPlayer(player);
            if (ownedRegion) {
                if (inventory) {
                    if (WindowManager.getInstance().getOpenWindow(player) != null) player.closeInventory();
                }
                else DialogCompatibility.closeDialog(player);
            }
        }
        this.viewers.clear();
        this.lifecycle.clear();
        GuiSession.clearAll();
    }

    private Gui buildLayout(Player player, List<Item> contentItems, Key activeCategory,
                            int categoryPage, int contentPage, boolean showBack, MenuView view) {
        /**
         * 组装常规浏览布局：顶部为分类栏（可翻页），底部为可配置操作栏。
         * contentPage 会被夹紧到合法页码范围，避免历史会话越界。
         */
        CerConfig ui = this.ui(view);
        String[] structure = this.normalizeLayout(ui);
        List<Integer> catSlots = slotsOf(structure, ui.categoryIconChar());
        int perPage = Math.max(1, catSlots.size());
        PagedGui.Builder<Item> builder = this.contentBuilder(contentItems, structure,
                showBack ? this.backItem(view) : null, view);
        builder.addIngredient(ui.categoryIconChar(), Item.EMPTY);
        this.bind(builder, ui, ui.prevCategory(),
                this.categoryView.categoryNavItem(false, perPage, activeCategory, view));
        this.bind(builder, ui, ui.nextCategory(),
                this.categoryView.categoryNavItem(true, perPage, activeCategory, view));
        int page = categoryPage;
        Key active = activeCategory;
        List<Integer> slots = catSlots;
        builder.addModifier(gui ->
                this.categoryView.fillCategoryBar(gui, player, active, page, slots, view));
        builder.addPageChangeHandler((previous, current) -> GuiSession.of(player).contentPage(current));
        PagedGui<Item> gui = builder.build();
        gui.setPage(Math.max(0, Math.min(contentPage, gui.getPageCount() - 1)));
        return gui;
    }

    private Gui buildContentOnlyLayout(Player player, List<Item> contentItems, MenuView view) {
        return this.buildContentOnlyLayout(player, contentItems, view, null);
    }

    private Gui buildContentOnlyLayout(Player player, List<Item> contentItems, MenuView view, Item back) {
        CerConfig ui = this.ui(view);
        // 顶部分类和目录翻页区域清空，保持底部操作栏不变。
        String[] structure = contentOnlyStructure(ui);
        PagedGui.Builder<Item> builder = this.contentBuilder(contentItems, structure, back, view);
        builder.addIngredient(CONTENT_BLANK_CHAR, Item.EMPTY);
        builder.addPageChangeHandler((previous, current) -> GuiSession.of(player).contentPage(current));
        PagedGui<Item> gui = builder.build();
        gui.setPage(Math.max(0, Math.min(GuiSession.of(player).contentPage(), gui.getPageCount() - 1)));
        return gui;
    }

    static String[] contentOnlyStructure(CerConfig ui) {
        // 把分类图标与分类翻页符号替换为空白占位，只保留内容区与底部操作栏。
        String[] structure = BrowserLayout.normalize(ui).clone();
        for (int row = 0; row < structure.length; row++) {
            StringBuilder line = new StringBuilder(structure[row]);
            for (int column = 0; column < line.length(); column++) {
                char symbol = line.charAt(column);
                if (symbol == ui.categoryIconChar()
                        || symbol == ui.prevCategory().symbol()
                        || symbol == ui.nextCategory().symbol()) {
                    line.setCharAt(column, CONTENT_BLANK_CHAR);
                }
            }
            structure[row] = line.toString();
        }
        return structure;
    }

    /** 两种浏览模式共用内容分页和同一套可配置底栏按钮。 */
    PagedGui.Builder<Item> contentBuilder(List<Item> contentItems, String[] structure, Item back, MenuView view) {
        return this.contentBuilder(contentItems, structure, back, this.actions(view).searchItem(), view);
    }

    PagedGui.Builder<Item> contentBuilder(List<Item> contentItems, String[] structure, Item back, Item search,
                                          MenuView view) {
        CerConfig ui = this.ui(view);
        BrowserActionItems items = this.actions(view);
        PagedGui.Builder<Item> builder = PagedGui.itemsBuilder()
                .setStructure(structure)
                .setContent(contentItems)
                .setBackground(new ItemWrapper(ItemBridge.emptySlot()));
        builder.addIngredient(EMPTY_CHAR, this.reservedItem(ui));
        builder.addIngredient(ui.contentAreaChar(), Markers.CONTENT_LIST_SLOT_HORIZONTAL);
        this.bind(builder, ui, ui.prevPage(), items.pageNavItem(false));
        this.bind(builder, ui, ui.nextPage(), items.pageNavItem(true));
        this.bind(builder, ui, ui.search(), search);
        this.bind(builder, ui, ui.hand(), items.shortcutItem(ui.hand(), this::openHeldRecipe));
        this.bind(builder, ui, ui.query(), items.queryShortcutItem(ui.query()));
        this.bind(builder, ui, ui.close(), items.shortcutItem(ui.close(), Player::closeInventory));
        this.bind(builder, ui, ui.info(), items.shortcutItem(ui.info(), null));
        this.bind(builder, ui, ui.workstations(), items.workstationsShortcutItem(ui.workstations()));
        this.bind(builder, ui, ui.glasses(), items.shortcutItem(ui.glasses(), this::openTargetBlockRecipe));
        if (back != null) this.bind(builder, ui, ui.back(), back);
        return builder;
    }

    private Item reservedItem(CerConfig ui) {
        if (ui.reservedMaterial() == Material.AIR
                && (ui.reservedItemModel() == null || ui.reservedItemModel().isBlank())) {
            return Item.EMPTY;
        }
        return Item.simple(ItemBridge.icon(ui.reservedItemModel(), ui.reservedMaterial(),
                Component.empty(), List.of()));
    }

    private String[] normalizeLayout(CerConfig ui) {
        return BrowserLayout.normalize(ui);
    }

    private static List<Integer> slotsOf(String[] structure, char symbol) {
        return BrowserLayout.slotsOf(structure, symbol);
    }

    void bind(PagedGui.Builder<Item> builder, CerConfig ui, CerConfig.ButtonDef def, Item item) {
        // 按钮符号与布局冲突时只警告不绑定，避免覆盖内容区或分类栏。
        char symbol = def.symbol();
        switch (BrowserLayout.binding(symbol, ui.categoryIconChar(), ui.contentAreaChar())) {
            case DISABLED -> { }
            case RESERVED -> this.plugin.getLogger().warning(this.plugin.lang().text("logs.layout-reserved"));
            case CONFLICT -> this.plugin.getLogger().warning(this.plugin.lang().text("logs.layout-conflict",
                    "symbol", String.valueOf(symbol)));
            case ALLOWED -> builder.addIngredient(symbol, item);
        }
    }

    public Set<Key> visibleItemIds(Player player) {
        return this.visibleItemIds(player, this.visibility(player));
    }

    public Set<Key> visibleItemIds(Player player, BrowserVisibility visibility) {
        Set<Key> visible = new LinkedHashSet<>();
        if (this.data.categoriesById().isEmpty()) visible.addAll(this.data.items().keySet());
        else visible.addAll(this.data.categoryGraph().visible(this.categoryView.visibleCategories(player),
                category -> !this.data.namespaces().isCategoryHidden(category.id().toString())
                        && this.data.namespaces().canView(player, category.id().namespace()),
                category -> this.data.namespaces().hiddenItems(category.id().toString())));
        visible.addAll(visibility.externalItems());
        visible.removeIf(id -> this.hidesWithoutRecipes(player, id, visibility));
        return Set.copyOf(visible);
    }

    private boolean hidesWithoutRecipes(Player player, Key id, BrowserVisibility visibility) {
        boolean configured = this.data.namespaces().hidesItemsWithoutRecipes(id.namespace());
        if (!configured) return false;
        return shouldHideWithoutRecipes(true,
                this.recipeGui.hasContent(id, false, visibility),
                this.recipeGui.hasContent(id, true, visibility));
    }

    static boolean shouldHideWithoutRecipes(boolean configured, boolean hasResultRecipe,
                                            boolean participatesInRecipe) {
        return configured && !hasResultRecipe && !participatesInRecipe;
    }

    static boolean nativeItemVisible(Key id, boolean catalogVisible, boolean registered,
                                     boolean referenced, boolean namespaceConfigured,
                                     boolean namespaceVisible) {
        return BrowserVisibility.nativeItemVisible(id, catalogVisible, registered, referenced,
                namespaceConfigured, namespaceVisible);
    }

    /** 每次动作都会重新创建：权限和来源检查绝不跨点击缓存。 */
    public BrowserVisibility visibility(Player player) {
        return new BrowserVisibility(this.plugin, this.data, this, player);
    }

    public Predicate<RecipeEntry> recipeVisibility(Player player) { return this.visibility(player)::canView; }

    public boolean canViewRecipe(RecipeEntry entry, Player player) { return this.visibility(player).canView(entry); }

    public Predicate<RecipeEntry> workstationRecipeVisibility(Player player) {
        return this.visibility(player)::canViewWorkstation;
    }

    public boolean canQueryItem(Key id, Player player) { return this.visibility(player).canQuery(id); }

    static boolean isStandaloneMinecraftRecipe(String id) {
        // 煎锅按原版营火规则生成；营火源配方可独立展示，煎锅必须采用相同规则，
        // 否则未进入 CE 分类图的原版熟食会被工作站可见性过滤。
        return id.startsWith("minecraft_cultivation:")
                || id.startsWith("cultivation_")
                || id.startsWith("minecraft_data:")
                || id.startsWith("farmersdelight:skillet/");
    }

    public List<Key> searchItems(String keyword, int limit, Predicate<Key> allowed) {
        var result = new LinkedHashSet<>(this.data.searchIndex().search(keyword, limit, allowed));
        result.addAll(this.plugin.externalRecipes().snapshot().search().search(keyword, limit, allowed));
        return result.stream().limit(limit).toList();
    }

    private Key externalItemId(ItemStack stack) {
        for (var item : this.plugin.externalRecipes().snapshot().items().entrySet()) {
            if (!"cer_external".equals(item.getKey().namespace())) continue;
            ItemStack exemplar = item.getValue().icon();
            if (exemplar != null && exemplar.isSimilar(stack)) return item.getKey();
        }
        return null;
    }

    private Key inventoryItemKey(ItemStack stack) {
        // 解析顺序：外部配方物品 -> CE 自定义物品 -> 原版物品。
        if (stack == null || stack.getType().isAir()) return null;
        Key id = this.externalItemId(stack);
        if (id == null) id = CraftEngineItems.getCustomItemId(stack);
        if (id == null) id = Key.of(stack.getType().getKey().toString());
        return id;
    }

    List<Category> allTopCategories() {
        return this.categoryView.allTopCategories();
    }

    Item buttonEntryItem(Player player, CategoryNamespaces.ButtonEntry button) {
        return this.buttonEntryItem(player, button, this.config.categoryBarNamePrefix());
    }

    Item buttonEntryItem(Player player, CategoryNamespaces.ButtonEntry button, String prefix) {
        return this.categoryView.buttonEntryItem(player, button, prefix);
    }

    /** 目录区名称统一应用配置的颜色前缀，避免不同来源自带颜色造成跳色。 */
    static Component directoryName(Component source, CerConfig config, String prefix) {
        return BrowserCategoryView.directoryName(source, config, prefix);
    }

    /** 目录区 Lore 统一为正文灰，同时保留原有可见文本和行顺序。 */
    static List<Component> directoryLore(List<Component> source) {
        return BrowserCategoryView.directoryLore(source);
    }

    private Item itemEntry(Player player, Key id, Category owner) {
        CerConfig ui = this.currentUi(player);
        ItemStack icon = ItemBridge.build(id, player);
        List<Component> lore = new ArrayList<>();
        for (String line : this.itemLoreFor(player, owner, ui)) {
            lore.add(ui.mm(line));
        }
        ItemStack decorated = ItemBridge.appendLore(icon, lore);
        return Item.builder().setItemProvider(decorated).addClickHandler(click -> this.handleClick(click.player(), () -> {
            Player p = click.player();
            boolean usage = click.clickType() == ClickType.RIGHT || click.clickType() == ClickType.SHIFT_RIGHT;
            boolean opened = this.recipeGui.open(p, id, usage, 0);
            Sounds.play(p, opened ? ui.sounds().click() : ui.sounds().queryFailure());
        })).build();
    }

    private List<String> itemLoreFor(Player player, Category owner, CerConfig ui) {
        if (owner != null) {
            var custom = this.data.namespaces().itemLoreOf(owner.id().namespace());
            if (!custom.isBlank()) {
                return LocalizedConfigText.resolve(
                        this.plugin.lang(player), custom.resolveLines(this.plugin.lang(player)));
            }
        }
        return ui.itemLore();
    }

    List<String> iconLoreFor(Player player, Category category, CerConfig ui) {
        var custom = this.data.namespaces().iconLoreOf(category.id().namespace());
        if (!custom.isBlank()) {
            return LocalizedConfigText.resolve(
                    this.plugin.lang(player), custom.resolveLines(this.plugin.lang(player)));
        }
        return ui.categoryIconLore();
    }

    List<String> subIconLoreFor(Player player, Category category, CerConfig ui) {
        var custom = this.data.namespaces().subIconLoreOf(category.id().namespace());
        if (!custom.isBlank()) {
            return LocalizedConfigText.resolve(
                    this.plugin.lang(player), custom.resolveLines(this.plugin.lang(player)));
        }
        return ui.subIconLore();
    }

    private List<Component> buttonLore(CerConfig ui, CerConfig.ButtonDef def) {
        return def.lore().stream().map(ui::mm).toList();
    }

    /** 配方窗口返回打开它的合成器列表或 CraftEngine 分类。 */
    void returnToBrowser(Player player) {
        GuiSession session = GuiSession.of(player);
        session.clearRecipeContext();
        if (session.workstationId() != null) this.workstations.reopen(player);
        else this.returnToCategories(player);
    }

    void returnToCategories(Player player) {
        GuiSession session = GuiSession.of(player);
        if (session.craftableMode()) {
            this.openCraftableView(player);
            return;
        }
        if (session.itemSearchKeyword() != null) {
            this.openItemList(player, null, session.itemSearchKeyword(), session.contentPage(),
                    session.itemSearchKeyword());
            return;
        }
        if (session.managerMode()) {
            session.managerMode(false);
            this.openDefault(player);
            return;
        }
        session.leaveWorkstations();
        Key category = session.currentCategory();
        if (category != null && this.data.category(category).isPresent()) {
            this.openCategory(player, category, session.categoryPage(), session.contentPage());
        } else if (category == null && this.isVanillaHome(player)) {
            this.openVanillaItems(player, session.contentPage());
        } else {
            this.openDefault(player);
        }
    }

    private boolean isVanillaHome(Player player) {
        for (BrowserCategoryView.BarEntry entry : this.categoryView.barEntries(player)) {
            if (entry instanceof BrowserCategoryView.VanillaItemsEntry) {
                return this.data.namespaces().canView(player, "minecraft");
            }
            if (entry instanceof BrowserCategoryView.CategoryEntry category
                    && this.data.namespaces().canView(player, category.category().id().namespace())) {
                return false;
            }
        }
        return false;
    }

    /** 在配方外右键会返回打开该配方时的主视图。 */
    void returnToOriginHome(Player player) {
        GuiSession session = GuiSession.of(player);
        session.clearRecipeContext();
        if (session.craftableMode()) {
            this.openCraftableView(player);
            return;
        }
        if (session.workstationId() != null) {
            this.workstations.reopen(player);
        } else {
            this.returnToCategories(player);
        }
    }

    private Item backItem(MenuView view) {
        return this.actions(view).backItem();
    }

    private Item craftableBackItem(CerConfig ui) {
        CerConfig.ButtonDef def = ui.back();
        ItemStack icon = ItemBridge.icon(def.itemModel(), def.material(),
                ui.mm(def.name()), this.buttonLore(ui, def));
        return Item.builder().setItemProvider(icon).addClickHandler(click -> this.handleClick(click.player(), () -> {
            Sounds.play(click.player(), ui.sounds().back());
            this.toggleCraftableView(click.player());
        })).build();
    }

    void openOrToggleWorkstations(Player player) {
        this.clearCraftableMode(player);
        if (this.config.workstationsToggle() && GuiSession.of(player).workstationId() != null) {
            this.returnToCategories(player);
        } else {
            this.workstations.open(player);
        }
    }

    private List<ContentEntry> collectEntries(Category category, Player player) {
        List<ContentEntry> entries = new ArrayList<>();
        Set<String> hidden = this.data.namespaces().hiddenItems(category.id().toString());
        BrowserVisibility visibility = this.visibility(player);
        for (var member : this.data.categoryGraph().members(category.id())) {
            if (member.category()) {
                String subId = member.reference();
                if (this.data.namespaces().isCategoryHidden(subId)) continue;
                Category sub = this.data.category(member.key()).orElse(null);
                if (sub != null) {
                    entries.add(new SubCategoryEntry(sub));
                } else {
                    entries.add(new MissingCategoryEntry(subId));
                }
                continue;
            }
            Key itemKey = member.key();
            if (CategoryItems.isHidden(hidden, itemKey)) continue;
            if (this.hidesWithoutRecipes(player, itemKey, visibility)) continue;
            entries.add(new ItemKeyEntry(itemKey));
        }
        return entries;
    }

    private boolean isTopLevel(Key categoryId) {
        for (Category c : this.categoryView.allTopCategories()) {
            if (c.id().equals(categoryId)) {
                return true;
            }
        }
        return false;
    }

    private Key findParentOf(Key categoryId) {
        return this.data.parentOf(categoryId);
    }

    int countMembers(Category category, Player player) {
        BrowserVisibility visibility = this.visibility(player);
        return (int) this.data.categoryGraph().members(category.id()).stream()
                .filter(member -> member.category()
                        || !this.hidesWithoutRecipes(player, member.key(), visibility))
                .count();
    }

    String plainName(Player player, Category category, CerConfig ui) {
        String n = DisplayNames.of(category, this.data.resourcePackTranslations(player));
        if (n != null && !n.isBlank()) {
            return n;
        }
        return ui.categoryIconName().replace("<name>", category.id().value());
    }

    private sealed interface ContentEntry permits ItemKeyEntry, SubCategoryEntry, MissingCategoryEntry {
        Item toItem(BrowserService service, Player player, Category owner);
    }

    private record ItemKeyEntry(Key key) implements ContentEntry {
        @Override
        public Item toItem(BrowserService service, Player player, Category owner) {
            return service.itemEntry(player, this.key, owner);
        }
    }

    private record SubCategoryEntry(Category category) implements ContentEntry {
        @Override
        public Item toItem(BrowserService service, Player player, Category owner) {
            return service.data.namespaces().canView(player, this.category.id().namespace())
                    ? service.categoryView.subCategoryIcon(player, this.category)
                    : Item.simple(ItemBridge.button(Material.BARRIER,
                    service.categoryView.accessDeniedComponent(
                            player, this.category.id().namespace(), service.currentUi(player)),
                    List.of()));
        }
    }

    private record MissingCategoryEntry(String id) implements ContentEntry {
        @Override
        public Item toItem(BrowserService service, Player player, Category owner) {
            ItemStack stack = ItemBridge.button(Material.BARRIER,
                    service.currentUi(player).mm("<#E58B8B>" + this.id + "</#E58B8B>"), List.of());
            return Item.simple(stack);
        }
    }
}
