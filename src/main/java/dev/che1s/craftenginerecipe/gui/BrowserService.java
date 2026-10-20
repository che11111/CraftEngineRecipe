package dev.che1s.craftenginerecipe.gui;

import dev.che1s.craftenginerecipe.CraftEngineRecipePlugin;
import dev.che1s.craftenginerecipe.config.CerConfig;
import dev.che1s.craftenginerecipe.data.CategoryNamespaces;
import dev.che1s.craftenginerecipe.data.CategoryItems;
import dev.che1s.craftenginerecipe.data.CraftEngineData;
import dev.che1s.craftenginerecipe.data.RecipeIndex;
import dev.che1s.craftenginerecipe.util.ButtonActions;
import dev.che1s.craftenginerecipe.util.CompatibilityWarnings;
import dev.che1s.craftenginerecipe.util.DisplayNames;
import dev.che1s.craftenginerecipe.util.HeldRecipeScope;
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
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
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
    private final MenuLifecycle lifecycle = new MenuLifecycle();
    private final Map<UUID, Player> viewers = new ConcurrentHashMap<>();
    private final Object viewerLock = new Object();

    /** Registers in-flight builds too, so reload cannot change settings underneath them. */
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
        this.recipeGui = new RecipeGuiService(plugin, config, data, recipeIndex, this);
        this.workstations = new WorkstationBrowserService(plugin, this, config, data, recipeIndex, this.recipeGui);
    }

    public RecipeGuiService recipes() {
        return this.recipeGui;
    }

    public void openDefault(Player player) {
        if (!this.claim(player)) return;
        GuiSession.of(player).leaveWorkstations();
        List<Category> tops = this.visibleCategories(player);
        if (tops.isEmpty()) {
            if (this.data.categoriesById().isEmpty()) {
                this.openItemList(player, null, this.plugin.lang().text("ui.common.all-items"), 0, null);
            } else {
                this.openEmptyContent(player);
            }
            return;
        }
        this.openCategory(player, tops.get(0).id(), 0);
    }

    public void openApplied(Player player) {
        GuiSession.of(player).unapplied(false);
        this.openEntry(player);
    }

    public void openUnapplied(Player player) {
        GuiSession.of(player).unapplied(true);
        this.openEntry(player);
    }

    /** Looks up recipes producing the main-hand item; it never opens the ingredient-use view. */
    public void openHeldRecipe(Player player) {
        this.runClaimedQuery(player, () -> this.openHeldRecipeClaimed(player));
    }

    /** Looks up recipes producing the block currently under the player's crosshair. */
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
            // Failed queries do not own an inventory or dialog to close during a later reload.
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
            id = CraftEngineItems.getCustomItemId(held);
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
        if (!HeldRecipeScope.canQuery(id, this.allTopCategories(), this.data.categoriesById(),
                this.data.categoryGraph(), this.data.items().keySet(), this.data.namespaces(), player)) {
            player.sendMessage(this.plugin.lang().message(unavailableMessage));
            return false;
        }
        if (!this.recipeGui.hasContent(id, false)) {
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
        // Query feedback replaces the entry sound; normal window tracking still handles a later close.
        boolean opened = this.recipeGui.open(player, id, false, 0);
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

    /** Replacement windows are one menu visit; old close callbacks must not close the new visit. */
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
            // InvUI closes the old window before opening its replacement. Wait one tick
            // to distinguish internal navigation from leaving this plugin's menus.
            this.plugin.tasks().playerLater(player, () -> {
                if (this.lifecycle.closed(player.getUniqueId(), token) && player.isOnline()) {
                    for (var sound : this.config.sounds().closeMenu()) {
                        Sounds.play(player, sound.spec(), sound.defaultVolume());
                    }
                    this.viewers.remove(player.getUniqueId(), player);
                }
            }, 1);
        });
        window.open();
    }

    public void forgetPlayer(Player player) {
        this.lifecycle.forget(player.getUniqueId());
        this.viewers.remove(player.getUniqueId(), player);
    }

    /** Search dialogs have their own lifecycle; the inventory-to-dialog transfer is silent. */
    public void leaveForSearch(Player player) {
        this.lifecycle.forget(player.getUniqueId());
        this.viewers.put(player.getUniqueId(), player);
        player.closeInventory();
    }

    private void openEmptyContent(Player player) {
        List<Item> content = List.of(Item.simple(ItemBridge.button(this.config.empty().material(), this.config.mm(this.config.empty().name()), this.buttonLore(this.config.empty()))));
        Gui gui = this.buildLayout(player, content, null, 0, false);
        this.openWindow(Window.builder().setViewer(player)
                .setTitle(this.config.mm(this.config.browserTitle(), "category", this.plugin.lang().text("ui.common.all-items")))
                .setUpperGui(gui)
                .build());
    }

    public void openCategory(Player player, Key categoryId, int categoryPage) {
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
        session.enter(categoryId, categoryPage, showBack ? this.findParentOf(categoryId) : null);
        Gui gui = this.buildLayout(player, contentItems, categoryId, categoryPage, showBack);
        this.openWindow(Window.builder().setViewer(player)
                .setTitle(this.config.mm(this.config.browserTitle(), "category", this.plainName(category)))
                .setUpperGui(gui)
                .build());
    }

    public void openItemList(Player player, Key parentCategory, String title, int categoryPage, String keyword) {
        if (!this.claim(player)) return;
        GuiSession.of(player).leaveWorkstations();
        boolean searching = keyword != null && !keyword.isBlank();
        List<ItemKeyEntry> entries = new ArrayList<>();
        if (searching) {
            Set<Key> scope = this.visibleItemIds(player);
            int max = this.config.searchMaxResults();
            for (Key id : this.data.searchIndex().search(keyword, max, scope::contains)) {
                entries.add(new ItemKeyEntry(id));
            }
        } else {
            for (Key id : this.data.items().keySet()) {
                entries.add(new ItemKeyEntry(id));
            }
        }
        Category owner = parentCategory == null ? null : this.data.category(parentCategory).orElse(null);
        List<Item> contentItems = new LazyItems<>(entries,
                entry -> lazyItem(() -> entry.toItem(this, player, owner)));
        if (contentItems.isEmpty()) {
            contentItems = List.of(Item.simple(ItemBridge.button(this.config.empty().material(), this.config.mm(this.config.empty().name()), this.buttonLore(this.config.empty()))));
        }
        Gui gui = this.buildLayout(player, contentItems, parentCategory, categoryPage, parentCategory != null);
        Component titleComponent = searching
                ? this.config.mmEscaped(this.config.searchResultTitle(), "keyword", keyword)
                : this.config.mm(this.config.browserTitle(), "category", title);
        this.openWindow(Window.builder().setViewer(player)
                .setTitle(titleComponent)
                .setUpperGui(gui)
                .build());
    }

    /** Each window owns its lazy items; player-dependent stacks are never shared between players. */
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

    /** Reload waits for entity cleanup without ever waiting synchronously on another region. */
    public CompletableFuture<Void> closeAll() {
        List<CompletableFuture<Void>> pending = new ArrayList<>();
        List<Player> viewers;
        synchronized (this.viewerLock) {
            viewers = List.copyOf(this.viewers.values());
        }
        for (Player player : viewers) {
            pending.add(this.plugin.tasks().player(player, () -> {
                boolean inventory = this.lifecycle.contains(player.getUniqueId());
                this.forgetPlayer(player); // silence callbacks before closing
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

    /** Plugin disable cannot enqueue work owned by an already-disabled plugin. */
    public void shutdown() {
        this.recipeGui.shutdown();
        for (Player player : List.copyOf(this.viewers.values())) {
            boolean inventory = this.lifecycle.contains(player.getUniqueId());
            this.forgetPlayer(player);
            if (Bukkit.isOwnedByCurrentRegion(player)) {
                if (inventory) player.closeInventory();
                else player.closeDialog();
            }
        }
        this.viewers.clear();
        this.lifecycle.clear();
        GuiSession.clearAll();
    }

    private Gui buildLayout(Player player, List<Item> contentItems, Key activeCategory, int categoryPage, boolean showBack) {
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
        return builder.build();
    }

    /** Both browser modes share content paging and the same configurable bottom-bar buttons. */
    PagedGui.Builder<Item> contentBuilder(List<Item> contentItems, String[] structure, Item back, Item search) {
        PagedGui.Builder<Item> builder = PagedGui.itemsBuilder()
                .setStructure(structure)
                .setContent(contentItems)
                .setBackground(new ItemWrapper(ItemBridge.emptySlot()));
        builder.addIngredient(EMPTY_CHAR, Item.EMPTY);
        builder.addIngredient(this.config.contentAreaChar(), Markers.CONTENT_LIST_SLOT_HORIZONTAL);
        this.bind(builder, this.config.prevPage(), this.pageNavItem(false));
        this.bind(builder, this.config.nextPage(), this.pageNavItem(true));
        this.bind(builder, this.config.search(), search);
        this.bind(builder, this.config.hand(), this.shortcutItem(this.config.hand(), this::openHeldRecipe));
        this.bind(builder, this.config.info(), this.shortcutItem(this.config.info(), null));
        this.bind(builder, this.config.workstations(), this.shortcutItem(this.config.workstations(), player -> {
            Sounds.play(player, this.config.sounds().click());
            this.workstations.open(player);
        }));
        this.bind(builder, this.config.glasses(), this.shortcutItem(this.config.glasses(), this::openTargetBlockRecipe));
        if (back != null) this.bind(builder, this.config.back(), back);
        return builder;
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

    Set<Key> visibleItemIds(Player player) {
        if (this.data.categoriesById().isEmpty()) return this.data.items().keySet();
        return this.data.categoryGraph().visible(this.visibleCategories(player),
                category -> !this.data.namespaces().isCategoryHidden(category.id().toString())
                        && this.data.namespaces().canView(player, category.id().namespace()),
                category -> this.data.namespaces().hiddenItems(category.id().toString()));
    }

    /**
     * 全部待显示的顶层分类（含无权限时的占位分类），按 CraftEngine 自然顺序。
     */
    private List<Category> allTopCategories() {
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
    private List<BarEntry> barEntries(Player player) {
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
                List<Category> cats = byNamespace.get(ns.id());
                if (cats != null) {
                    for (Category c : cats) {
                        out.add(new CategoryEntry(c));
                    }
                }
            } else if (entry instanceof CategoryNamespaces.ButtonEntry button) {
                out.add(new ButtonEntry(button));
            }
        }
        return out;
    }

    /**
     * 当前玩家有权查看的顶层分类（不含自定义按钮）。
     */
    private List<Category> visibleCategories(Player player) {
        List<Category> out = new ArrayList<>();
        for (BarEntry entry : this.barEntries(player)) {
            if (entry instanceof CategoryEntry ce) {
                if (this.data.namespaces().canView(player, ce.category().id().namespace())) {
                    out.add(ce.category());
                }
            }
        }
        return out;
    }

    private void fillCategoryBar(Gui gui, Player player, Key activeCategory, int categoryPage, List<Integer> slots) {
        List<BarEntry> entries = this.barEntries(player);
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
            }
        }
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
        return Item.builder().setItemProvider(decorated).addClickHandler(click -> {
            Player p = click.player();
            Sounds.play(p, this.config.sounds().click());
            this.openCategory(p, category.id(), GuiSession.of(p).categoryPage());
        }).build();
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
        return Item.builder().setItemProvider(decorated).addClickHandler(click -> {
            Player p = click.player();
            Sounds.play(p, this.config.sounds().click());
            this.openCategory(p, sub.id(), GuiSession.of(p).categoryPage());
        }).build();
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
        return Item.builder().setItemProvider(decorated).addClickHandler(click ->
                ButtonActions.run(click.player(), button, click.clickType())).build();
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
        ItemStack decorated = ItemBridge.decorate(icon, null, lore);
        return Item.builder().setItemProvider(decorated).addClickHandler(click -> {
            Player p = click.player();
            boolean usage = click.clickType() == ClickType.RIGHT || click.clickType() == ClickType.SHIFT_RIGHT;
            boolean opened = this.recipeGui.open(p, id, usage, 0);
            Sounds.play(p, opened ? this.config.sounds().click() : this.config.sounds().queryFailure());
        }).build();
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
        return BoundItem.pagedBuilder().setItemProvider(stack).addClickHandler((item, gui, click) -> {
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
            this.fillCategoryBar(gui, p, activeCategory, target, slots);
            Sounds.play(p, this.config.sounds().changeCategory());
        }).build();
    }

    private Item pageNavItem(boolean next) {
        CerConfig.ButtonDef def = next ? this.config.nextPage() : this.config.prevPage();
        return BoundItem.pagedBuilder().setItemProvider((player, gui) -> {
            boolean available = next ? gui.getPage() + 1 < gui.getPageCount() : gui.getPage() > 0;
            Component name = available
                    ? this.config.mm(def.name())
                    : this.plugin.lang().component(next ? "ui.common.last-page" : "ui.common.first-page");
            String model = available ? def.itemModel() : CerConfig.DISABLED_ITEM_MODEL;
            ItemStack stack = ItemBridge.icon(model, def.material(), name, this.buttonLore(def));
            return new ItemWrapper(stack);
        }).addClickHandler((item, gui, click) -> {
            int target = gui.getPage() + (next ? 1 : -1);
            if (target < 0 || target >= gui.getPageCount()) {
                Sounds.play(click.player(), this.config.sounds().queryFailure());
                return;
            }
            gui.setPage(target);
            Sounds.play(click.player(), this.config.sounds().changePage());
        }).build();
    }

    private Item searchItem() {
        return this.searchItem(player -> this.plugin.searchDialog().open(player));
    }

    Item searchItem(Consumer<Player> action) {
        return this.shortcutItem(this.config.search(), action);
    }

    private Item shortcutItem(CerConfig.ButtonDef def, Consumer<Player> action) {
        List<Component> lore = this.buttonLore(def);
        ItemStack stack = ItemBridge.icon(def.itemModel(), def.material(), this.config.mm(def.name()), lore);
        if (action == null) return Item.simple(stack);
        return Item.builder().setItemProvider(stack).addClickHandler(click -> {
            action.accept(click.player());
        }).build();
    }

    /** Recipe windows return to their originating workstation list or CraftEngine category. */
    void returnToBrowser(Player player) {
        if (GuiSession.of(player).workstation() != null) this.workstations.reopen(player);
        else this.returnToCategories(player);
    }

    void returnToCategories(Player player) {
        GuiSession session = GuiSession.of(player);
        session.leaveWorkstations();
        Key category = session.currentCategory();
        if (category != null && this.data.category(category).isPresent()) {
            this.openCategory(player, category, session.categoryPage());
        } else {
            this.openDefault(player);
        }
    }

    private Item backItem() {
        CerConfig.ButtonDef def = this.config.back();
        ItemStack stack = ItemBridge.icon(def.itemModel(), def.material(), this.config.mm(def.name()), this.buttonLore(def));
        return Item.builder().setItemProvider(stack).addClickHandler(click -> {
            Player p = click.player();
            GuiSession session = GuiSession.of(p);
            Key parent = session.exit();
            Sounds.play(p, this.config.sounds().back());
            if (parent != null) {
                this.openCategory(p, parent, session.categoryPage());
            } else {
                this.openDefault(p);
            }
        }).build();
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

    private sealed interface BarEntry permits CategoryEntry, ButtonEntry {
    }

    private record CategoryEntry(Category category) implements BarEntry {
    }

    private record ButtonEntry(CategoryNamespaces.ButtonEntry button) implements BarEntry {
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
