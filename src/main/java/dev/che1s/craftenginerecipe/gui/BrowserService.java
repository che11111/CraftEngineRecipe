package dev.che1s.craftenginerecipe.gui;

import dev.che1s.craftenginerecipe.CraftEngineRecipePlugin;
import dev.che1s.craftenginerecipe.config.CerConfig;
import dev.che1s.craftenginerecipe.category.CategoryNamespaces;
import dev.che1s.craftenginerecipe.category.CategoryItems;
import dev.che1s.craftenginerecipe.data.CraftEngineData;
import dev.che1s.craftenginerecipe.recipe.RecipeIndex;
import dev.che1s.craftenginerecipe.recipe.RecipeEntry;
import dev.che1s.craftenginerecipe.recipe.ExternalRecipeSnapshot;
import dev.che1s.craftenginerecipe.recipe.access.RecipeAccessPolicy;
import dev.che1s.craftenginerecipe.debug.Diagnostics;
import dev.che1s.craftenginerecipe.util.ButtonActions;
import dev.che1s.craftenginerecipe.util.CompatibilityWarnings;
import dev.che1s.craftenginerecipe.util.DisplayNames;
import dev.che1s.craftenginerecipe.recipe.access.HeldRecipeScope;
import dev.che1s.craftenginerecipe.util.ItemBridge;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer;
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
import xyz.xenondevs.invui.gui.SlotElement;
import xyz.xenondevs.invui.item.BoundItem;
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
import java.util.function.Consumer;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;

public final class BrowserService {

    private static final char EMPTY_CHAR = '#';

    private final CraftEngineRecipePlugin plugin;
    private final CerConfig config;
    private final CraftEngineData data;
    private final RecipeIndex recipeIndex;
    private final RecipeGuiService recipeGui;
    private final WorkstationBrowserService workstations;
    private final BrowserActionItems actionItems;
    private final GuiSessionStore sessions;
    private final MenuLifecycle lifecycle = new MenuLifecycle();
    private final MenuActionGate<UUID> actions = new MenuActionGate<>();
    private final Map<UUID, Player> viewers = new ConcurrentHashMap<>();
    private final Object viewerLock = new Object();

    /** 同时登记正在构建的窗口，避免重载在构建途中改变配置。 */
    public boolean claim(Player player) {
        synchronized (this.viewerLock) {
            if (!this.data.isReady() || !player.isOnline()) return false;
            this.viewers.put(player.getUniqueId(), player);
            return true;
        }
    }

    public BrowserService(CraftEngineRecipePlugin plugin, CerConfig config, CraftEngineData data, RecipeIndex recipeIndex) {
        this.plugin = plugin;
        this.config = config;
        this.data = data;
        this.recipeIndex = recipeIndex;
        this.sessions = plugin.sessions();
        this.recipeGui = new RecipeGuiService(plugin, config, data, recipeIndex, this);
        this.workstations = new WorkstationBrowserService(plugin, this, config, data, recipeIndex, this.recipeGui);
        this.actionItems = new BrowserActionItems(plugin, config, this);
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
        if (!this.claim(player)) return;
        GuiSession.of(player).leaveWorkstations();
        for (BarEntry entry : this.barEntries(player, false)) {
            if (entry instanceof VanillaItemsEntry && this.data.namespaces().canView(player, "minecraft")) {
                this.openVanillaItems(player);
                return;
            }
            if (entry instanceof CategoryEntry category
                    && this.data.namespaces().canView(player, category.category().id().namespace())) {
                break;
            }
        }
        List<Category> tops = this.visibleCategories(player);
        if (tops.isEmpty()) {
            if (this.data.categoriesById().isEmpty()) {
                this.openItemList(player, null, this.plugin.lang().text("ui.common.all-items"), 0, null);
            } else {
                if (this.hasAdditionalRecipes(player)) this.openAdditionalRecipes(player);
                else this.openEmptyContent(player);
            }
            return;
        }
        this.openCategory(player, tops.get(0).id(), 0);
    }

    public void openApplied(Player player) {
        GuiSession.of(player).unapplied(false);
        if (this.rememberLastPage(player) && this.openRemembered(player)) return;
        this.openEntry(player);
    }

    public void openUnapplied(Player player) {
        GuiSession.of(player).unapplied(true);
        this.openEntry(player);
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
                player.sendMessage(this.plugin.lang().message("messages.data-not-ready",
                        "reason", this.data.notReadyReason()));
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
            player.sendMessage(this.plugin.lang().message("messages.hand-empty"));
            return false;
        }
        Key id;
        try {
            id = this.externalItemId(held);
            if (id == null) id = CraftEngineItems.getCustomItemId(held);
            if (id == null) id = Key.of(held.getType().getKey().toString());
        } catch (RuntimeException | LinkageError error) {
            this.plugin.getLogger().warning(this.plugin.lang().text("logs.hand-resolve-failed", "error", error.toString()));
            player.sendMessage(this.plugin.lang().message("messages.hand-resolve-failed"));
            return false;
        }
        return this.openProducedRecipe(player, id, "messages.hand-item-unavailable", "messages.hand-no-recipe");
    }

    private boolean openTargetBlockRecipeClaimed(Player player) {
        Key id;
        try {
            Block target = TargetBlockQuery.trace(player);
            if (target == null) {
                player.sendMessage(this.plugin.lang().message("messages.target-block-empty"));
                return false;
            }
            ImmutableBlockState customState = CraftEngineBlocks.getCustomBlockState(target);
            id = customState == null ? null : customState.settings().itemId();
            if (id == null) {
                Material item = target.getBlockData().getPlacementMaterial();
                if (item == null || item.isAir() || !item.isItem()) {
                    player.sendMessage(this.plugin.lang().message("messages.target-block-empty"));
                    return false;
                }
                id = Key.of(item.getKey().toString());
            }
        } catch (RuntimeException | LinkageError error) {
            this.plugin.getLogger().warning(this.plugin.lang().text("logs.target-block-resolve-failed", "error", error.toString()));
            player.sendMessage(this.plugin.lang().message("messages.target-block-resolve-failed"));
            return false;
        }
        return this.openProducedRecipe(player, id, "messages.target-block-unavailable", "messages.target-block-no-recipe");
    }

    private boolean openProducedRecipe(Player player, Key id, String unavailableMessage, String noRecipeMessage) {
        var visibility = this.visibility(player);
        if (!visibility.canQuery(id)) {
            player.sendMessage(this.plugin.lang().message(unavailableMessage));
            return false;
        }
        if (!this.recipeGui.hasContent(id, false, visibility)) {
            player.sendMessage(this.plugin.lang().message(noRecipeMessage, "id", id.toString()));
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
            player.sendMessage(this.plugin.lang().message(noRecipeMessage, "id", id.toString()));
        }
        return opened;
    }

    private void openEntry(Player player) {
        this.lifecycle.requestEntry(player.getUniqueId());
        try {
            this.openDefault(player);
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
        if (session.workstationId() != null) {
            this.workstations.reopen(player);
            return true;
        }
        if (session.externalCatalog()) {
            this.openAdditionalRecipes(player);
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
        Object token = new Object();
        window.addOpenHandler(() -> {
            if (this.lifecycle.opened(player.getUniqueId(), token)) {
                for (var sound : this.config.sounds().openMenu()) {
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
                    for (var sound : this.config.sounds().closeMenu()) {
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
    }

    public void rememberPlayer(Player player) {
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

    private void openEmptyContent(Player player) {
        try (var timing = this.plugin.diagnostics().measure(Diagnostics.Operation.BROWSER_OPEN)) {
            List<Item> content = List.of(Item.simple(ItemBridge.button(this.config.empty().material(), this.config.mm(this.config.empty().name()), this.buttonLore(this.config.empty()))));
            Gui gui = this.buildLayout(player, content, null, 0, 0, false);
            this.openWindow(Window.builder().setViewer(player)
                    .setTitle(this.config.mm(this.config.browserTitle(), "category", this.plugin.lang().text("ui.common.all-items")))
                    .setUpperGui(gui)
                    .build());
        }
    }

    public void openCategory(Player player, Key categoryId, int categoryPage) {
        this.openCategory(player, categoryId, categoryPage, 0);
    }

    private void openCategory(Player player, Key categoryId, int categoryPage, int contentPage) {
        try (var timing = this.plugin.diagnostics().measure(Diagnostics.Operation.BROWSER_OPEN)) {
            if (!this.claim(player)) return;
            Category category = this.data.category(categoryId).orElse(null);
            if (category == null || this.data.namespaces().isCategoryHidden(categoryId.toString())) {
                player.sendMessage(this.plugin.lang().message("messages.category-not-found", "id", categoryId.toString()));
                return;
            }
            if (!this.data.namespaces().canView(player, category.id().namespace())) {
                player.sendMessage(this.accessDeniedComponent(player, category.id().namespace()));
                return;
            }
            List<ContentEntry> entries = this.collectEntries(category, player);
            List<Item> contentItems = new LazyItems<>(entries,
                    entry -> lazyItem(() -> entry.toItem(this, player, category)));
            boolean showBack = !this.isTopLevel(categoryId);
            GuiSession session = GuiSession.of(player);
            session.enter(categoryId, categoryPage, showBack ? this.findParentOf(categoryId) : null, contentPage);
            Gui gui = this.buildLayout(player, contentItems, categoryId, categoryPage, contentPage, showBack);
            this.openWindow(Window.builder().setViewer(player)
                    .setTitle(this.config.mm(this.config.browserTitle(), "category", this.plainName(category)))
                    .setUpperGui(gui)
                    .build());
        }
    }

    public void openItemList(Player player, Key parentCategory, String title, int categoryPage, String keyword) {
        this.openItemList(player, parentCategory, title, categoryPage, keyword, null);
    }

    private void openItemList(Player player, Key parentCategory, String title, int categoryPage, String keyword,
                              Set<Key> restrictedItems) {
        try (var timing = this.plugin.diagnostics().measure(Diagnostics.Operation.BROWSER_OPEN)) {
            if (!this.claim(player)) return;
            GuiSession.of(player).leaveWorkstations();
            boolean searching = keyword != null && !keyword.isBlank();
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
                for (Key id : this.data.items().keySet()) {
                    entries.add(new ItemKeyEntry(id));
                }
                for (Key id : this.additionalItemIds(player)) {
                    if (!this.data.items().containsKey(id)) entries.add(new ItemKeyEntry(id));
                }
            }
            Category owner = parentCategory == null ? null : this.data.category(parentCategory).orElse(null);
            List<Item> contentItems = new LazyItems<>(entries,
                    entry -> lazyItem(() -> entry.toItem(this, player, owner)));
            if (contentItems.isEmpty()) {
                contentItems = List.of(Item.simple(ItemBridge.button(this.config.empty().material(), this.config.mm(this.config.empty().name()), this.buttonLore(this.config.empty()))));
            }
            Gui gui = this.buildLayout(player, contentItems, parentCategory,
                    GuiSession.of(player).categoryPage(), categoryPage, parentCategory != null);
            Component titleComponent = searching
                    ? this.config.mmEscaped(this.config.searchResultTitle(), "keyword", keyword)
                    : this.config.mm(this.config.browserTitle(), "category", title);
            this.openWindow(Window.builder().setViewer(player)
                    .setTitle(titleComponent)
                    .setUpperGui(gui)
                    .build());
        }
    }

    private void openVanillaItems(Player player) {
        this.openVanillaItems(player, 0);
    }

    private void openVanillaItems(Player player, int contentPage) {
        CategoryNamespaces.NamespaceEntry namespace = this.data.namespaces().findNamespace("minecraft");
        Set<Key> items = prepareVanillaItems(vanillaItemKeys(this.data.items().keySet()),
                namespace != null && namespace.sortByNamespace(),
                namespace != null && namespace.hideItemsWithoutRecipes(),
                id -> this.recipeGui.hasContent(player, id, false));
        GuiSession.of(player).enter(null, 0, null, contentPage);
        String configured = this.data.namespaces().iconNameOf("minecraft");
        String name = configured == null || configured.isBlank()
                ? this.plugin.lang().text("ui.vanilla-items.name") : configured;
        name = name.replace("<name>", this.plugin.lang().text("ui.vanilla-items.name"));
        this.openItemList(player, null, name, contentPage, null, items);
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
                    player.closeDialog();
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
                else player.closeDialog();
            }
        }
        this.viewers.clear();
        this.lifecycle.clear();
        GuiSession.clearAll();
    }

    private Gui buildLayout(Player player, List<Item> contentItems, Key activeCategory,
                            int categoryPage, int contentPage, boolean showBack) {
        String[] structure = this.normalizeLayout();
        List<Integer> catSlots = slotsOf(structure, this.config.categoryIconChar());
        int perPage = Math.max(1, catSlots.size());
        PagedGui.Builder<Item> builder = this.contentBuilder(contentItems, structure,
                showBack ? this.backItem() : null, this.searchItem());
        builder.addIngredient(this.config.categoryIconChar(), Item.EMPTY);
        this.bind(builder, this.config.prevCategory(), this.categoryNavItem(false, perPage, activeCategory));
        this.bind(builder, this.config.nextCategory(), this.categoryNavItem(true, perPage, activeCategory));
        int page = categoryPage;
        Key active = activeCategory;
        List<Integer> slots = catSlots;
        builder.addModifier(gui -> this.fillCategoryBar(gui, player, active, page, slots));
        builder.addPageChangeHandler((previous, current) -> GuiSession.of(player).contentPage(current));
        PagedGui<Item> gui = builder.build();
        gui.setPage(Math.max(0, Math.min(contentPage, gui.getPageCount() - 1)));
        return gui;
    }

    /** 两种浏览模式共用内容分页和同一套可配置底栏按钮。 */
    PagedGui.Builder<Item> contentBuilder(List<Item> contentItems, String[] structure, Item back, Item search) {
        PagedGui.Builder<Item> builder = PagedGui.itemsBuilder()
                .setStructure(structure)
                .setContent(contentItems)
                .setBackground(new ItemWrapper(ItemBridge.emptySlot()));
        builder.addIngredient(EMPTY_CHAR, this.reservedItem());
        builder.addIngredient(this.config.contentAreaChar(), Markers.CONTENT_LIST_SLOT_HORIZONTAL);
        this.bind(builder, this.config.prevPage(), this.pageNavItem(false));
        this.bind(builder, this.config.nextPage(), this.pageNavItem(true));
        this.bind(builder, this.config.search(), search);
        this.bind(builder, this.config.hand(), this.shortcutItem(this.config.hand(), this::openHeldRecipe));
        this.bind(builder, this.config.query(), this.queryShortcutItem(this.config.query()));
        this.bind(builder, this.config.close(), this.shortcutItem(this.config.close(), Player::closeInventory));
        this.bind(builder, this.config.info(), this.shortcutItem(this.config.info(), null));
        this.bind(builder, this.config.workstations(), this.workstationsShortcutItem(this.config.workstations()));
        this.bind(builder, this.config.glasses(), this.shortcutItem(this.config.glasses(), this::openTargetBlockRecipe));
        if (back != null) this.bind(builder, this.config.back(), back);
        return builder;
    }

    private Item reservedItem() {
        if (this.config.reservedMaterial() == Material.AIR
                && (this.config.reservedItemModel() == null || this.config.reservedItemModel().isBlank())) {
            return Item.EMPTY;
        }
        return Item.simple(ItemBridge.icon(this.config.reservedItemModel(), this.config.reservedMaterial(),
                Component.empty(), List.of()));
    }

    private String[] normalizeLayout() {
        return BrowserLayout.normalize(this.config);
    }

    private static List<Integer> slotsOf(String[] structure, char symbol) {
        return BrowserLayout.slotsOf(structure, symbol);
    }

    void bind(PagedGui.Builder<Item> builder, CerConfig.ButtonDef def, Item item) {
        char symbol = def.symbol();
        switch (BrowserLayout.binding(symbol, this.config.categoryIconChar(), this.config.contentAreaChar())) {
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
        else visible.addAll(this.data.categoryGraph().visible(this.visibleCategories(player),
                category -> !this.data.namespaces().isCategoryHidden(category.id().toString())
                        && this.data.namespaces().canView(player, category.id().namespace()),
                category -> this.data.namespaces().hiddenItems(category.id().toString())));
        visible.addAll(visibility.additionalItems());
        return Set.copyOf(visible);
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

    private Set<Key> additionalItemIds(Player player) { return this.visibility(player).additionalItems(); }

    private boolean hasAdditionalRecipes(Player player) { return this.visibility(player).hasAdditional(); }

    static boolean isStandaloneMinecraftRecipe(String id) {
        return id.startsWith("minecraft_cultivation:")
                || id.startsWith("cultivation_")
                || id.startsWith("minecraft_data:");
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

    void openAdditionalRecipes(Player player) {
        try (var timing = this.plugin.diagnostics().measure(Diagnostics.Operation.BROWSER_OPEN)) {
            if (!this.claim(player)) return;
            GuiSession session = GuiSession.of(player);
            session.leaveWorkstations();
            session.externalCatalog(true);
            List<Item> content = new LazyItems<>(List.copyOf(this.additionalItemIds(player)),
                    id -> lazyItem(() -> this.itemEntry(player, id, null)));
            if (content.isEmpty()) {
                content = List.of(Item.simple(ItemBridge.button(this.config.empty().material(),
                        this.config.mm(this.config.empty().name()), this.buttonLore(this.config.empty()))));
            }
            Gui gui = this.buildLayout(player, content, null, session.categoryPage(), session.contentPage(), false);
            this.openWindow(Window.builder().setViewer(player)
                    .setTitle(this.plugin.lang().component("ui.additional-recipes.title")).setUpperGui(gui).build());
        }
    }

    /**
     * 全部待显示的顶层分类（含无权限时的占位分类），按 CraftEngine 自然顺序。
     */
    List<Category> allTopCategories() {
        List<Category> out = new ArrayList<>();
        if (this.config.categoryFollowCe()) {
            for (Category c : this.data.topCategories()) {
                if (CraftEngineData.ALL_ITEMS_CATEGORY.equals(c.id())) continue;
                if (this.data.namespaces().isCategoryHidden(c.id().toString())) continue;
                out.add(c);
            }
        } else {
            for (String id : this.config.categoryList()) {
                Key key = Key.of(id);
                if (CraftEngineData.ALL_ITEMS_CATEGORY.equals(key)) continue;
                if (this.data.namespaces().isCategoryHidden(key.toString())) continue;
                this.data.category(key).ifPresent(out::add);
            }
        }
        return out;
    }

    /**
     * 分类栏条目（分类 + 自定义按钮），按当前来源（categories.yml 或 categories-unapplied.yml）排列。
     */
    private List<BarEntry> barEntries(Player player) { return this.barEntries(player, true); }

    private List<BarEntry> barEntries(Player player, boolean includeAdditional) {
        Map<String, List<Category>> byNamespace = new LinkedHashMap<>();
        for (Category c : this.allTopCategories()) {
            byNamespace.computeIfAbsent(c.id().namespace(), k -> new ArrayList<>()).add(c);
        }
        List<CategoryNamespaces.Entry> sourceEntries;
        if (GuiSession.of(player).unapplied()) {
            sourceEntries = new ArrayList<>(this.data.namespaces().unappliedEntries());
        } else {
            sourceEntries = this.data.namespaces().entries();
        }
        List<BarEntry> out = new ArrayList<>();
        for (CategoryNamespaces.Entry entry : sourceEntries) {
            if (entry instanceof CategoryNamespaces.NamespaceEntry ns) {
                if (!ns.categoryEntry()) continue;
                List<Category> cats = byNamespace.get(ns.id());
                if ("minecraft".equals(ns.id()) && (cats == null || cats.isEmpty())) {
                    out.add(new VanillaItemsEntry(ns));
                } else if (cats != null) {
                    for (Category c : cats) {
                        out.add(new CategoryEntry(c));
                    }
                }
            } else if (entry instanceof CategoryNamespaces.ButtonEntry button) {
                out.add(new ButtonEntry(button));
            }
        }
        if (includeAdditional && this.hasAdditionalRecipes(player)) out.add(new AdditionalRecipesEntry());
        return out;
    }

    /**
     * 当前玩家有权查看的顶层分类（不含自定义按钮）。
     */
    private List<Category> visibleCategories(Player player) {
        List<Category> out = new ArrayList<>();
        for (BarEntry entry : this.barEntries(player, false)) {
            if (entry instanceof CategoryEntry ce) {
                if (this.data.namespaces().canView(player, ce.category().id().namespace())) {
                    out.add(ce.category());
                }
            }
        }
        return out;
    }

    private void fillCategoryBar(Gui gui, Player player, Key activeCategory, int categoryPage, List<Integer> slots) {
        this.fillCategoryBar(gui, player, activeCategory, categoryPage, slots, this.barEntries(player));
    }

    private void fillCategoryBar(Gui gui, Player player, Key activeCategory, int categoryPage,
                                 List<Integer> slots, List<BarEntry> entries) {
        int perPage = Math.max(1, slots.size());
        int maxOffset = Math.max(0, entries.size() - perPage);
        int offset = Math.max(0, Math.min(categoryPage, maxOffset));
        GuiSession.of(player).categoryPage(offset);
        for (int i = 0; i < slots.size(); i++) {
            int slot = slots.get(i);
            int idx = offset + i;
            if (idx >= entries.size()) {
                gui.setSlotElement(slot, null);
                continue;
            }
            BarEntry entry = entries.get(idx);
            if (entry instanceof CategoryEntry ce) {
                Category cat = ce.category();
                gui.setSlotElement(slot, new SlotElement.Item(this.categoryIcon(player, cat, cat.id().equals(activeCategory))));
            } else if (entry instanceof ButtonEntry be) {
                if (!this.data.namespaces().canViewButton(player, be.button())) {
                    gui.setSlotElement(slot, null);
                    continue;
                }
                gui.setSlotElement(slot, new SlotElement.Item(this.buttonEntryItem(player, be.button())));
            } else if (entry instanceof VanillaItemsEntry vanilla) {
                if (this.data.namespaces().canView(player, "minecraft")) {
                    gui.setSlotElement(slot, new SlotElement.Item(this.vanillaItemsIcon(player, vanilla.namespace())));
                } else {
                    gui.setSlotElement(slot, new SlotElement.Item(Item.simple(ItemBridge.button(
                            Material.BARRIER, this.accessDeniedComponent(player, "minecraft"), List.of()))));
                }
            } else if (entry instanceof AdditionalRecipesEntry) {
                ItemStack icon = ItemBridge.button(Material.KNOWLEDGE_BOOK,
                        this.plugin.lang().component("ui.additional-recipes.name"), List.of());
                gui.setSlotElement(slot, new SlotElement.Item(Item.builder().setItemProvider(icon)
                        .addClickHandler(click -> this.handleClick(click.player(), () -> {
                            Sounds.play(click.player(), this.config.sounds().click());
                            this.openAdditionalRecipes(click.player());
                        })).build()));
            }
        }
    }

    private Item vanillaItemsIcon(Player player, CategoryNamespaces.NamespaceEntry namespace) {
        String raw = namespace.iconName();
        String fallback = this.plugin.lang().text("ui.vanilla-items.name");
        Component name = this.config.mm((raw == null || raw.isBlank() ? fallback : raw).replace("<name>", fallback));
        int count = (int) this.data.items().keySet().stream()
                .filter(id -> "minecraft".equals(id.namespace())).count();
        List<Component> lore = namespace.iconLore().stream()
                .map(line -> this.config.mm(line, "count", String.valueOf(count))).toList();
        ItemStack stack = ItemBridge.button(Material.GRASS_BLOCK, name, lore);
        return Item.builder().setItemProvider(stack).addClickHandler(click -> this.handleClick(click.player(), () -> {
            Sounds.play(click.player(), this.config.sounds().click());
            this.openVanillaItems(click.player());
        })).build();
    }

    private Item categoryIcon(Player player, Category category, boolean active) {
        if (!this.data.namespaces().canView(player, category.id().namespace())) {
            return this.categoryPlaceholder(player, category);
        }
        ItemStack icon;
        try {
            icon = ItemBridge.build(category.icon(), player);
        } catch (RuntimeException | LinkageError t) {
            CompatibilityWarnings.warn("Browser.categoryIcon", t);
            icon = new ItemStack(this.config.empty().material());
        }
        Component name = this.categoryName(category);
        List<Component> lore = new ArrayList<>();
        int count = this.countMembers(category);
        for (String line : this.iconLoreFor(category)) {
            lore.add(this.config.mm(line, "count", String.valueOf(count)));
        }
        ItemStack decorated = new ItemBuilder(icon).setName(name).setLore(lore).setGlint(active ? Boolean.TRUE : null).build();
        return Item.builder().setItemProvider(decorated).addClickHandler(click -> this.handleClick(click.player(), () -> {
            Player p = click.player();
            Sounds.play(p, this.config.sounds().click());
            this.openCategory(p, category.id(), GuiSession.of(p).categoryPage());
        })).build();
    }

    private Item subCategoryIcon(Player player, Category sub) {
        ItemStack icon = ItemBridge.build(sub.icon(), player);
        Component name = this.categoryName(sub);
        List<Component> lore = new ArrayList<>();
        int count = this.countMembers(sub);
        for (String line : this.subIconLoreFor(sub)) {
            lore.add(this.config.mm(line, "count", String.valueOf(count)));
        }
        ItemStack decorated = ItemBridge.decorate(icon, name, lore);
        return Item.builder().setItemProvider(decorated).addClickHandler(click -> this.handleClick(click.player(), () -> {
            Player p = click.player();
            Sounds.play(p, this.config.sounds().click());
            this.openCategory(p, sub.id(), GuiSession.of(p).categoryPage());
        })).build();
    }

    private Item buttonEntryItem(Player player, CategoryNamespaces.ButtonEntry button) {
        String material = button.display().material();
        ItemStack icon;
        if (material == null || material.isBlank()) {
            icon = new ItemStack(Material.STONE);
        } else {
            String itemId = material.startsWith("source:CraftEngine:") ? material.substring("source:CraftEngine:".length()) : material;
            icon = ItemBridge.build(Key.of(itemId), player, 1);
        }
        Component name = CerConfig.noItalic(LegacyComponentSerializer.legacyAmpersand().deserialize(button.display().name()));
        List<Component> lore = new ArrayList<>();
        for (String line : button.display().lore()) {
            lore.add(CerConfig.noItalic(LegacyComponentSerializer.legacyAmpersand().deserialize(line)));
        }
        ItemStack decorated = ItemBridge.decorate(icon, name, lore);
        return Item.builder().setItemProvider(decorated).addClickHandler(click -> this.handleClick(click.player(), () ->
                ButtonActions.run(click.player(), button, click.clickType()))).build();
    }

    private Component categoryName(Category category) {
        String template = this.data.namespaces().iconNameOf(category.id().namespace());
        if (template == null || template.isBlank()) {
            return this.config.mm(DisplayNames.expandL10n(this.plainName(category)));
        }
        String name = DisplayNames.of(category);
        if (name == null || name.isBlank()) {
            name = category.id().value();
        }
        return this.config.mm(DisplayNames.expandL10n(template).replace("<name>", name));
    }

    private Item categoryPlaceholder(Player player, Category category) {
        ItemStack stack = ItemBridge.button(Material.BARRIER, this.accessDeniedComponent(player, category.id().namespace()), List.of());
        return Item.simple(stack);
    }

    private Component accessDeniedComponent(Player player, String namespace) {
        if (this.data.namespaces().visibilityOf(namespace) == CategoryNamespaces.Visibility.PERMISSION) {
            String permission = this.data.namespaces().permissionOf(namespace);
            return this.config.mm(this.config.noPermissionHint(), "permission", permission == null ? "" : permission);
        }
        return this.config.mm(this.config.adminOnlyHint());
    }

    private Item itemEntry(Player player, Key id, Category owner) {
        ItemStack icon = ItemBridge.build(id, player);
        List<Component> lore = new ArrayList<>();
        for (String line : this.itemLoreFor(owner)) {
            lore.add(this.config.mm(line));
        }
        ItemStack decorated = ItemBridge.appendLore(icon, lore);
        return Item.builder().setItemProvider(decorated).addClickHandler(click -> this.handleClick(click.player(), () -> {
            Player p = click.player();
            boolean usage = click.clickType() == ClickType.RIGHT || click.clickType() == ClickType.SHIFT_RIGHT;
            boolean opened = this.recipeGui.open(p, id, usage, 0);
            Sounds.play(p, opened ? this.config.sounds().click() : this.config.sounds().queryFailure());
        })).build();
    }

    private List<String> itemLoreFor(Category owner) {
        if (owner != null) {
            List<String> custom = this.data.namespaces().itemLoreOf(owner.id().namespace());
            if (custom != null && !custom.isEmpty()) {
                return custom;
            }
        }
        return this.config.itemLore();
    }

    private List<String> iconLoreFor(Category category) {
        List<String> custom = this.data.namespaces().iconLoreOf(category.id().namespace());
        if (custom != null && !custom.isEmpty()) {
            return custom;
        }
        return this.config.categoryIconLore();
    }

    private List<String> subIconLoreFor(Category category) {
        List<String> custom = this.data.namespaces().subIconLoreOf(category.id().namespace());
        if (custom != null && !custom.isEmpty()) {
            return custom;
        }
        return this.config.subIconLore();
    }

    private List<Component> buttonLore(CerConfig.ButtonDef def) {
        return def.lore().stream().map(this.config::mm).toList();
    }

    private Item categoryNavItem(boolean next, int perPage, Key activeCategory) {
        CerConfig.ButtonDef def = next ? this.config.nextCategory() : this.config.prevCategory();
        ItemStack stack = ItemBridge.icon(def.itemModel(), def.material(), this.config.mm(def.name()), this.buttonLore(def));
        return BoundItem.pagedBuilder().setItemProvider(stack).addClickHandler((item, gui, click) -> this.handleClick(click.player(), () -> {
            Player p = click.player();
            GuiSession session = GuiSession.of(p);
            int curOffset = session.categoryPage();
            List<BarEntry> entries = this.barEntries(p);
            int maxOffset = Math.max(0, entries.size() - perPage);
            int target = next ? curOffset + 1 : curOffset - 1;
            List<Integer> slots = slotsOf(this.normalizeLayout(), this.config.categoryIconChar());
            if (slots.isEmpty() || target < 0 || target > maxOffset) {
                Sounds.play(p, this.config.sounds().queryFailure());
                return;
            }
            session.categoryPage(target);
            this.fillCategoryBar(gui, p, activeCategory, target, slots, entries);
            Sounds.play(p, this.config.sounds().changeCategory());
        })).build();
    }

    private Item pageNavItem(boolean next) {
        return this.actionItems.pageNavItem(next);
    }

    private Item searchItem() {
        return this.actionItems.searchItem();
    }

    Item searchItem(Consumer<Player> action) {
        return this.actionItems.searchItem(action);
    }

    private Item shortcutItem(CerConfig.ButtonDef def, Consumer<Player> action) {
        return this.actionItems.shortcutItem(def, action);
    }

    private Item queryShortcutItem(CerConfig.ButtonDef def) {
        return this.actionItems.queryShortcutItem(def);
    }

    private Item workstationsShortcutItem(CerConfig.ButtonDef def) {
        return this.actionItems.workstationsShortcutItem(def);
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
        session.leaveWorkstations();
        if (session.externalCatalog()) {
            this.openAdditionalRecipes(player);
            return;
        }
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
        for (BarEntry entry : this.barEntries(player, false)) {
            if (entry instanceof VanillaItemsEntry) {
                return this.data.namespaces().canView(player, "minecraft");
            }
            if (entry instanceof CategoryEntry category
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
        if (session.workstationId() != null) {
            this.workstations.reopen(player);
        } else {
            this.returnToCategories(player);
        }
    }

    private Item backItem() {
        return this.actionItems.backItem();
    }

    void openOrToggleWorkstations(Player player) {
        if (this.config.workstationsToggle() && GuiSession.of(player).workstationId() != null) {
            this.returnToCategories(player);
        } else {
            this.workstations.open(player);
        }
    }

    private List<ContentEntry> collectEntries(Category category, Player player) {
        List<ContentEntry> entries = new ArrayList<>();
        Set<String> hidden = this.data.namespaces().hiddenItems(category.id().toString());
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
            entries.add(new ItemKeyEntry(itemKey));
        }
        return entries;
    }

    private boolean isTopLevel(Key categoryId) {
        for (Category c : this.allTopCategories()) {
            if (c.id().equals(categoryId)) {
                return true;
            }
        }
        return false;
    }

    private Key findParentOf(Key categoryId) {
        return this.data.parentOf(categoryId);
    }

    private int countMembers(Category category) {
        return this.data.categoryGraph().members(category.id()).size();
    }

    private String plainName(Category category) {
        String n = category.displayName();
        if (n != null && !n.isBlank()) {
            return n;
        }
        return this.config.categoryIconName().replace("<name>", category.id().value());
    }

    private sealed interface BarEntry permits CategoryEntry, ButtonEntry, VanillaItemsEntry, AdditionalRecipesEntry {
    }

    private record AdditionalRecipesEntry() implements BarEntry {}

    private record CategoryEntry(Category category) implements BarEntry {
    }

    private record ButtonEntry(CategoryNamespaces.ButtonEntry button) implements BarEntry {
    }

    private record VanillaItemsEntry(CategoryNamespaces.NamespaceEntry namespace) implements BarEntry {
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
                    ? service.subCategoryIcon(player, this.category)
                    : service.categoryPlaceholder(player, this.category);
        }
    }

    private record MissingCategoryEntry(String id) implements ContentEntry {
        @Override
        public Item toItem(BrowserService service, Player player, Category owner) {
            ItemStack stack = ItemBridge.button(Material.BARRIER, service.config.mm("<#E58B8B>" + this.id + "</#E58B8B>"), List.of());
            return Item.simple(stack);
        }
    }
}
