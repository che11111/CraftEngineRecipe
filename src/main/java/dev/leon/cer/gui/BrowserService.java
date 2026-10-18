package dev.leon.cer.gui;

import dev.leon.cer.CraftEngineRecipePlugin;
import dev.leon.cer.config.CerConfig;
import dev.leon.cer.data.CategoryNamespaces;
import dev.leon.cer.data.CategoryItems;
import dev.leon.cer.data.CraftEngineData;
import dev.leon.cer.data.RecipeIndex;
import dev.leon.cer.util.ButtonActions;
import dev.leon.cer.util.DisplayNames;
import dev.leon.cer.util.HeldRecipeScope;
import dev.leon.cer.util.ItemBridge;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer;
import net.momirealms.craftengine.bukkit.api.CraftEngineItems;
import net.momirealms.craftengine.core.plugin.gui.category.Category;
import net.momirealms.craftengine.core.util.Key;
import org.bukkit.Bukkit;
import org.bukkit.Material;
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
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Supplier;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;

public final class BrowserService {

    private static final int WIDTH = 9;
    private static final char EMPTY_CHAR = '#';

    private final CraftEngineRecipePlugin plugin;
    private final CerConfig config;
    private final CraftEngineData data;
    private final RecipeIndex recipeIndex;
    private final RecipeGuiService recipeGui;
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
    }

    public RecipeGuiService recipes() {
        return this.recipeGui;
    }

    public void openDefault(Player player) {
        if (!this.claim(player)) return;
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
        boolean alreadyTracked = this.viewers.containsKey(player.getUniqueId());
        if (!this.claim(player)) return;
        try {
            this.openHeldRecipeClaimed(player);
        } finally {
            // Failed queries do not own an inventory or dialog to close during a later reload.
            if (!alreadyTracked && !this.lifecycle.contains(player.getUniqueId())) {
                this.viewers.remove(player.getUniqueId(), player);
            }
        }
    }

    private void openHeldRecipeClaimed(Player player) {
        ItemStack held = player.getInventory().getItemInMainHand();
        if (held.getType().isAir() || held.getAmount() <= 0) {
            player.sendMessage(this.plugin.lang().message("messages.hand-empty"));
            return;
        }
        Key id;
        try {
            id = CraftEngineItems.getCustomItemId(held);
            if (id == null) id = Key.of(held.getType().getKey().toString());
        } catch (RuntimeException | LinkageError error) {
            this.plugin.getLogger().warning(this.plugin.lang().text("logs.hand-resolve-failed", "error", error.toString()));
            player.sendMessage(this.plugin.lang().message("messages.hand-resolve-failed"));
            return;
        }
        Set<Key> visible = HeldRecipeScope.visibleItems(this.allTopCategories(), this.data.categoriesById(),
                this.data.items().keySet(), this.data.namespaces(), player);
        if (!visible.contains(id)) {
            player.sendMessage(this.plugin.lang().message("messages.hand-item-unavailable"));
            return;
        }
        if (!this.recipeGui.hasContent(id, false)) {
            player.sendMessage(this.plugin.lang().message("messages.hand-no-recipe", "id", id.toString()));
            return;
        }
        GuiSession session = GuiSession.of(player);
        Key category = session.currentCategory();
        if (session.unapplied() || category != null && (this.data.category(category).isEmpty()
                || this.data.namespaces().isCategoryHidden(category.toString())
                || !this.data.namespaces().canView(player, category.namespace()))) {
            session.enter(null, 0, null);
        }
        session.unapplied(false);
        this.lifecycle.requestEntry(player.getUniqueId());
        try {
            this.recipeGui.open(player, id, false, 0);
        } finally {
            this.lifecycle.cancelEntry(player.getUniqueId());
        }
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
        List<Item> contentItems = new ArrayList<>(entries.size());
        for (ContentEntry entry : entries) {
            contentItems.add(lazyItem(() -> entry.toItem(this, player, category)));
        }
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
        List<Item> contentItems = new ArrayList<>(entries.size());
        Category owner = parentCategory == null ? null : this.data.category(parentCategory).orElse(null);
        for (ContentEntry entry : entries) {
            contentItems.add(lazyItem(() -> entry.toItem(this, player, owner)));
        }
        if (contentItems.isEmpty()) {
            contentItems.add(Item.simple(ItemBridge.button(this.config.empty().material(), this.config.mm(this.config.empty().name()), this.buttonLore(this.config.empty()))));
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
        PagedGui.Builder<Item> builder = PagedGui.itemsBuilder()
                .setStructure(structure)
                .setContent(contentItems)
                .setBackground(new ItemWrapper(ItemBridge.emptySlot()));
        builder.addIngredient(EMPTY_CHAR, Item.EMPTY);
        builder.addIngredient(this.config.contentAreaChar(), Markers.CONTENT_LIST_SLOT_HORIZONTAL);
        builder.addIngredient(this.config.categoryIconChar(), Item.EMPTY);
        this.bind(builder, this.config.prevCategory(), this.categoryNavItem(false, perPage, activeCategory));
        this.bind(builder, this.config.nextCategory(), this.categoryNavItem(true, perPage, activeCategory));
        this.bind(builder, this.config.prevPage(), this.pageNavItem(false));
        this.bind(builder, this.config.nextPage(), this.pageNavItem(true));
        this.bind(builder, this.config.search(), this.searchItem());
        if (showBack) {
            this.bind(builder, this.config.back(), this.backItem());
        }
        int page = categoryPage;
        Key active = activeCategory;
        List<Integer> slots = catSlots;
        builder.addModifier(gui -> this.fillCategoryBar(gui, player, active, page, slots));
        return builder.build();
    }

    private String[] normalizeLayout() {
        Set<Character> known = this.knownChars();
        List<String> raw = this.config.layoutRows();
        String[] out = new String[CerConfig.ROWS];
        for (int r = 0; r < CerConfig.ROWS; r++) {
            String line = r < raw.size() ? raw.get(r) : "";
            StringBuilder sb = new StringBuilder(WIDTH);
            for (int i = 0; i < line.length() && sb.length() < WIDTH; i++) {
                char ch = line.charAt(i);
                if (ch == ' ' || ch == '\t') continue;
                sb.append(known.contains(ch) ? ch : EMPTY_CHAR);
            }
            while (sb.length() < WIDTH) {
                sb.append(EMPTY_CHAR);
            }
            out[r] = sb.toString();
        }
        return out;
    }

    private Set<Character> knownChars() {
        Set<Character> set = new HashSet<>();
        set.add(this.config.categoryIconChar());
        set.add(this.config.contentAreaChar());
        for (CerConfig.ButtonDef def : List.of(
                this.config.prevCategory(), this.config.nextCategory(),
                this.config.prevPage(), this.config.nextPage(),
                this.config.search(), this.config.back())) {
            if (def.symbol() == '\u0000') continue;
            set.add(def.symbol());
        }
        return set;
    }

    private static List<Integer> slotsOf(String[] structure, char symbol) {
        List<Integer> slots = new ArrayList<>();
        if (symbol == '\u0000') {
            return slots;
        }
        for (int r = 0; r < structure.length; r++) {
            String line = structure[r];
            for (int c = 0; c < line.length(); c++) {
                if (line.charAt(c) != symbol) continue;
                slots.add(r * WIDTH + c);
            }
        }
        return slots;
    }

    private void bind(PagedGui.Builder<Item> builder, CerConfig.ButtonDef def, Item item) {
        char symbol = def.symbol();
        if (symbol == '\u0000') {
            return;
        }
        if (symbol == EMPTY_CHAR) {
            this.plugin.getLogger().warning(this.plugin.lang().text("logs.layout-reserved"));
            return;
        }
        if (symbol == this.config.categoryIconChar() || symbol == this.config.contentAreaChar()) {
            this.plugin.getLogger().warning(this.plugin.lang().text("logs.layout-conflict", "symbol", String.valueOf(symbol)));
            return;
        }
        builder.addIngredient(symbol, item);
    }

    private Set<Key> visibleItemIds(Player player) {
        if (this.data.categoriesById().isEmpty()) return this.data.items().keySet();
        return CategoryItems.visible(this.visibleCategories(player), this.data.categoriesById(),
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
        } catch (Throwable t) {
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
                ButtonActions.run(click.player(), button.actionsFor(click.clickType()))).build();
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
            if (!this.recipeGui.hasContent(id, usage)) {
                return;
            }
            Sounds.play(p, this.config.sounds().click());
            this.recipeGui.open(p, id, usage, 0);
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
            if (target < 0 || target > maxOffset) {
                return;
            }
            session.categoryPage(target);
            this.fillCategoryBar(gui, p, activeCategory, target,
                    slotsOf(this.normalizeLayout(), this.config.categoryIconChar()));
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
            int page = gui.getPage();
            if (next) {
                if (page + 1 < gui.getPageCount()) {
                    gui.setPage(page + 1);
                    Sounds.play(click.player(), this.config.sounds().changePage());
                }
            } else if (page > 0) {
                gui.setPage(page - 1);
                Sounds.play(click.player(), this.config.sounds().changePage());
            }
        }).build();
    }

    private Item searchItem() {
        CerConfig.ButtonDef def = this.config.search();
        List<Component> lore = this.buttonLore(def);
        ItemStack stack = ItemBridge.icon(def.itemModel(), def.material(), this.config.mm(def.name()), lore);
        return Item.builder().setItemProvider(stack).addClickHandler(click -> {
            Player p = click.player();
            this.plugin.searchDialog().open(p);
        }).build();
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
        if (category.members() == null) {
            return entries;
        }
        Set<String> hidden = this.data.namespaces().hiddenItems(category.id().toString());
        for (String member : category.members()) {
            if (member == null || member.isBlank()) continue;
            if (member.charAt(0) == '#') {
                String subId = member.substring(1);
                if (this.data.namespaces().isCategoryHidden(subId)) continue;
                Category sub = this.data.category(Key.of(subId)).orElse(null);
                if (sub != null) {
                    entries.add(new SubCategoryEntry(sub));
                } else {
                    entries.add(new MissingCategoryEntry(subId));
                }
                continue;
            }
            Key itemKey = Key.of(member);
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
        if (category.members() == null) {
            return 0;
        }
        int n = 0;
        for (String m : category.members()) {
            if (m == null || m.isBlank()) continue;
            ++n;
        }
        return n;
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
            ItemStack stack = ItemBridge.button(Material.BARRIER, service.config.mm("<red>" + this.id + "</red>"), List.of());
            return Item.simple(stack);
        }
    }
}
