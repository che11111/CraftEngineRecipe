package dev.che1s.craftenginerecipe.gui;

import dev.che1s.craftenginerecipe.CraftEngineRecipePlugin;
import dev.che1s.craftenginerecipe.api.RecipeWorkstation;
import dev.che1s.craftenginerecipe.category.CategoryGraph;
import dev.che1s.craftenginerecipe.category.CategoryNamespaces;
import dev.che1s.craftenginerecipe.config.CerConfig;
import dev.che1s.craftenginerecipe.config.WorkstationConfig;
import dev.che1s.craftenginerecipe.dialog.ButtonEditorDialogService;
import dev.che1s.craftenginerecipe.util.ButtonActions;
import dev.che1s.craftenginerecipe.util.DisplayNames;
import dev.che1s.craftenginerecipe.util.ItemBridge;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
import net.momirealms.craftengine.core.plugin.gui.category.Category;
import net.momirealms.craftengine.core.util.Key;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.event.inventory.ClickType;
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

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/** 管理员配置中心：浏览和修改 categories*.yml 与 workstations.yml 的实际快照。 */
final class ManagementBrowserService {
    private static final char DIRECTORY_SLOT = 'C';
    private static final char CONTENT_SLOT = 'X';
    private static final char BACK_SLOT = 'B';
    private static final char PREVIOUS_SLOT = 'L';
    private static final char NEXT_SLOT = 'R';
    private static final char CLOSE_SLOT = 'J';
    private static final char DIRECTORY_PREVIOUS_SLOT = '<';
    private static final char DIRECTORY_NEXT_SLOT = '>';
    private static final List<Integer> DIRECTORY_SLOTS = List.of(1, 2, 3, 4, 5, 6, 7);
    private static final List<String> MANAGEMENT_STRUCTURE = List.of(
            "<CCCCCCC>",
            "XXXXXXXXX",
            "XXXXXXXXX",
            "XXXXXXXXX",
            "XXXXXXXXX",
            "BLR#####J");
    /** 管理页始终使用与可爱版相同的标题偏移，不受当前菜单风格影响。 */
    private static final String MANAGEMENT_TITLE_PREFIX =
            "<#FFFFFF><shift:-2><image:cer_manage:main><shift:-163>";

    private enum Page {
        HOME,
        CATEGORIES,
        UNAPPLIED,
        HIDDEN_CATEGORIES,
        CATEGORY_ITEMS,
        WORKSTATIONS
    }

    private record View(Page page, String namespace, String category, int contentPage) {
        static View home() {
            return new View(Page.HOME, null, null, 0);
        }

        View withPage(int page) {
            return new View(this.page, this.namespace, this.category, Math.max(0, page));
        }
    }

    private final CraftEngineRecipePlugin plugin;
    private final BrowserService browser;
    private final Map<UUID, View> views = new ConcurrentHashMap<>();
    private final Map<UUID, Integer> directoryOffsets = new ConcurrentHashMap<>();

    ManagementBrowserService(CraftEngineRecipePlugin plugin, BrowserService browser) {
        this.plugin = plugin;
        this.browser = browser;
    }

    void openHome(Player player) {
        this.navigate(player, View.home());
    }

    void reopen(Player player) {
        this.render(player, this.views.getOrDefault(player.getUniqueId(), View.home()));
    }

    void forget(Player player) {
        this.views.remove(player.getUniqueId());
        this.directoryOffsets.remove(player.getUniqueId());
    }

    static String[] managementStructure() {
        return MANAGEMENT_STRUCTURE.toArray(String[]::new);
    }

    private void navigate(Player player, View view) {
        this.views.put(player.getUniqueId(), view);
        this.render(player, view);
    }

    private void render(Player player, View view) {
        if (!player.hasPermission("craftenginerecipe.admin") || !this.plugin.data().isReady()) return;
        CerConfig ui = this.browser.managementUi();
        if (view.page() == Page.HOME) {
            this.renderHome(player, ui);
            return;
        }
        List<Item> content = switch (view.page()) {
            case CATEGORIES -> this.categoryEntryItems(player, true);
            case UNAPPLIED -> this.categoryEntryItems(player, false);
            case HIDDEN_CATEGORIES -> this.hiddenCategoryItems(player, view.namespace());
            case CATEGORY_ITEMS -> this.categoryItemItems(player, view.namespace(), view.category());
            case WORKSTATIONS -> this.workstationItems(player);
            case HOME -> List.of();
        };
        if (content.isEmpty()) content = List.of(this.emptyItem(ui));

        GuiSession session = GuiSession.of(player);
        session.enter(null, 0, null, 0);
        session.managerMode(true);
        session.contentPage(view.contentPage());

        String[] structure = managementStructure();
        PagedGui.Builder<Item> builder = PagedGui.itemsBuilder()
                .setStructure(structure)
                .setContent(content)
                .setBackground(new ItemWrapper(ItemBridge.emptySlot()));
        this.bindManagementLayout(builder, player, view, ui, structure);
        builder.addPageChangeHandler((previous, current) -> {
            View currentView = this.views.getOrDefault(player.getUniqueId(), view);
            this.views.put(player.getUniqueId(), currentView.withPage(current));
        });
        PagedGui<Item> gui = builder.build();
        gui.setPage(Math.max(0, Math.min(view.contentPage(), gui.getPageCount() - 1)));
        this.populateManagementDirectory(gui, player, this.directoryPage(view));
        String title = this.pageTitle(view);
        this.browser.openWindow(Window.builder().setViewer(player)
                .setUpperGui(gui)
                .setTitle(ui.mm(MANAGEMENT_TITLE_PREFIX
                        + this.plugin.lang().text("ui.management.title", "category", title)))
                .build());
    }

    /**
     * 首页把三个配置入口直接放进第一行目录槽，内容区和底部工作台快捷按钮保持空白。
     */
    private void renderHome(Player player, CerConfig ui) {
        GuiSession session = GuiSession.of(player);
        session.enter(null, 0, null, 0);
        session.managerMode(true);
        session.contentPage(0);

        String[] structure = managementStructure();
        Gui.Builder builder = Gui.builder().setStructure(structure);
        Set<Character> bound = new LinkedHashSet<>();
        for (String row : structure) {
            for (int index = 0; index < row.length(); index++) {
                char symbol = row.charAt(index);
                if (symbol == '\u0000' || !bound.add(symbol)) continue;
                if (symbol == BACK_SLOT) {
                    builder.addIngredient(symbol, this.backItem(player, View.home(), ui));
                } else if (symbol == CLOSE_SLOT) {
                    builder.addIngredient(symbol, this.closeItem(ui));
                } else if (symbol == DIRECTORY_PREVIOUS_SLOT) {
                    builder.addIngredient(symbol, this.directoryNavItem(ui, false));
                } else if (symbol == DIRECTORY_NEXT_SLOT) {
                    builder.addIngredient(symbol, this.directoryNavItem(ui, true));
                } else {
                    builder.addIngredient(symbol, Item.EMPTY);
                }
            }
        }
        Gui gui = builder.build();
        this.populateManagementDirectory(gui, player, Page.HOME);
        this.browser.openWindow(Window.builder().setViewer(player)
                .setUpperGui(gui)
                .setTitle(ui.mm(MANAGEMENT_TITLE_PREFIX
                        + this.plugin.lang().text("ui.management.title")))
                .build());
    }

    /** 三个配置入口在所有管理页面中都固定在首行目录槽。 */
    private void populateManagementDirectory(Gui gui, Player player, Page activePage) {
        List<Item> entries = this.homeItems(player, activePage);
        int offset = BrowserLayout.categoryOffset(
                this.directoryOffsets.getOrDefault(player.getUniqueId(), 0),
                entries.size(), DIRECTORY_SLOTS.size());
        this.directoryOffsets.put(player.getUniqueId(), offset);
        for (int slot : DIRECTORY_SLOTS) gui.setSlotElement(slot, null);
        for (int index = 0; index < Math.min(DIRECTORY_SLOTS.size(), entries.size() - offset); index++) {
            gui.setSlotElement(DIRECTORY_SLOTS.get(index),
                    new SlotElement.Item(entries.get(offset + index)));
        }
    }

    private Item directoryNavItem(CerConfig ui, boolean next) {
        CerConfig.ButtonDef def = next ? ui.nextCategory() : ui.prevCategory();
        ItemStack stack = this.managementButton(ui.mm(def.name()),
                def.lore().stream().map(ui::mm).toList());
        return BoundItem.builder().setItemProvider(stack).addClickHandler((item, gui, click) -> {
            Player player = click.player();
            List<Item> entries = this.homeItems(player, Page.HOME);
            int current = this.directoryOffsets.getOrDefault(player.getUniqueId(), 0);
            int target = BrowserLayout.categoryOffset(current + (next ? 1 : -1),
                    entries.size(), DIRECTORY_SLOTS.size());
            if (target == current) {
                Sounds.play(player, ui.sounds().queryFailure());
                return;
            }
            this.directoryOffsets.put(player.getUniqueId(), target);
            View view = this.views.getOrDefault(player.getUniqueId(), View.home());
            this.populateManagementDirectory(gui, player, this.directoryPage(view));
            Sounds.play(player, ui.sounds().changeCategory());
        }).build();
    }

    private Page directoryPage(View view) {
        if (view.page() == Page.HIDDEN_CATEGORIES || view.page() == Page.CATEGORY_ITEMS) {
            return this.plugin.data().namespaces().isApplied(view.namespace())
                    ? Page.CATEGORIES : Page.UNAPPLIED;
        }
        return view.page();
    }

    private List<Item> homeItems(Player player, Page activePage) {
        CategoryNamespaces namespaces = this.plugin.data().namespaces();
        int appliedNamespaces = (int) namespaces.entries().stream()
                .filter(CategoryNamespaces.NamespaceEntry.class::isInstance).count();
        int appliedButtons = namespaces.entries().size() - appliedNamespaces;
        List<Item> items = new ArrayList<>();
        items.add(this.actionItem(Material.WRITABLE_BOOK, "ui.management.categories-name",
                List.of("ui.management.categories-lore",
                        "ui.management.categories-summary"),
                "entries", String.valueOf(namespaces.entries().size()),
                "namespaces", String.valueOf(appliedNamespaces),
                "buttons", String.valueOf(appliedButtons),
                activePage == Page.CATEGORIES,
                () -> this.navigate(player, new View(Page.CATEGORIES, null, null, 0))));
        items.add(this.actionItem(Material.BOOK, "ui.management.unapplied-name",
                List.of("ui.management.unapplied-nav-lore", "ui.management.unapplied-summary"),
                "count", String.valueOf(namespaces.unappliedAllEntries().size()),
                activePage == Page.UNAPPLIED,
                () -> this.navigate(player, new View(Page.UNAPPLIED, null, null, 0))));
        items.add(this.actionItem(Material.CRAFTING_TABLE, "ui.management.workstations-name",
                List.of("ui.management.workstations-lore", "ui.management.workstations-summary"),
                "count", String.valueOf(this.plugin.workstationConfig().entries().size()),
                activePage == Page.WORKSTATIONS,
                () -> this.navigate(player, new View(Page.WORKSTATIONS, null, null, 0))));
        return items;
    }

    private List<Item> categoryEntryItems(Player player, boolean applied) {
        CategoryNamespaces namespaces = this.plugin.data().namespaces();
        List<CategoryNamespaces.Entry> entries = applied
                ? namespaces.entries() : namespaces.unappliedAllEntries();
        List<Item> items = new ArrayList<>(entries.size());
        for (int index = 0; index < entries.size(); index++) {
            CategoryNamespaces.Entry entry = entries.get(index);
            int entryIndex = index;
            if (entry instanceof CategoryNamespaces.NamespaceEntry namespace) {
                items.add(this.namespaceItem(player, namespace, applied, entryIndex));
            } else if (entry instanceof CategoryNamespaces.ButtonEntry button) {
                items.add(this.categoryButtonItem(player, button, applied, entryIndex));
            }
        }
        return items;
    }

    private Item namespaceItem(Player player, CategoryNamespaces.NamespaceEntry namespace,
                               boolean applied, int index) {
        Category category = this.firstCategory(namespace.id());
        ItemStack stack = category == null ? new ItemStack(Material.COMPASS)
                : ItemBridge.build(category.icon(), player);
        List<Component> lore = new ArrayList<>();
        lore.add(this.plugin.lang().component(applied
                ? "ui.management.applied-lore" : "ui.management.unapplied-lore"));
        lore.add(this.plugin.lang().component("ui.management.visibility-value",
                "visibility", this.visibilityName(namespace.visibility()),
                "permission", namespace.permission().isBlank() ? "-" : namespace.permission()));
        lore.add(this.plugin.lang().component("ui.management.category-state",
                "entry", this.onOff(namespace.categoryEntry()),
                "sort", this.onOff(namespace.sortByNamespace()),
                "hide", String.valueOf(namespace.hiddenItems().values().stream().mapToInt(Set::size).sum()
                        + namespace.hiddenCategories().size())));
        lore.add(this.plugin.lang().component("ui.management.namespace-browse"));
        lore.add(this.plugin.lang().component("ui.management.namespace-edit"));
        lore.add(this.plugin.lang().component(applied
                ? "ui.management.namespace-hide" : "ui.management.namespace-hide-unapplied"));
        if (applied) {
            lore.add(this.plugin.lang().component("ui.management.namespace-order"));
            lore.add(this.plugin.lang().component("ui.management.namespace-unapply"));
        } else {
            lore.add(this.plugin.lang().component("ui.management.namespace-apply"));
        }
        stack = new ItemBuilder(stack)
                .setName(this.plugin.lang().component("ui.management.entry-name", "namespace", namespace.id()))
                .setLore(lore)
                .setGlint(applied ? Boolean.TRUE : null)
                .build();
        return Item.builder().setItemProvider(stack).addClickHandler(click -> this.browser.handleClick(
                click.player(), () -> this.handleNamespaceClick(click.player(), namespace, applied, index, click.clickType())))
                .build();
    }

    private void handleNamespaceClick(Player player, CategoryNamespaces.NamespaceEntry namespace,
                                      boolean applied, int index, ClickType click) {
        if (!applied && click == ClickType.SHIFT_LEFT) {
            this.navigate(player, new View(Page.HIDDEN_CATEGORIES, namespace.id(), null, 0));
            return;
        }
        if (click == ClickType.RIGHT || click == ClickType.SHIFT_RIGHT && !applied) {
            if (this.plugin.unappliedCategoryDialog() != null) {
                this.plugin.unappliedCategoryDialog().open(player, namespace.id());
            }
            return;
        }
        if (click == ClickType.MIDDLE || click == ClickType.CREATIVE) {
            if (applied) this.navigate(player, new View(Page.HIDDEN_CATEGORIES, namespace.id(), null, 0));
            else this.apply(player, namespace.id());
            return;
        }
        if (applied && click == ClickType.DROP) {
            this.unapplyEntry(player, index, namespace.id());
            return;
        }
        if (applied && click == ClickType.SHIFT_LEFT) {
            this.moveCategoryEntry(player, index, -1, true);
            return;
        }
        if (applied && click == ClickType.SHIFT_RIGHT) {
            this.moveCategoryEntry(player, index, 1, true);
            return;
        }
        Category category = this.firstCategory(namespace.id());
        if (category == null) {
            player.sendMessage(this.plugin.lang().message("messages.management-no-category-page",
                    "namespace", namespace.id()));
            Sounds.play(player, this.browser.managementUi().sounds().queryFailure());
            return;
        }
        Sounds.play(player, this.browser.managementUi().sounds().click());
        this.navigate(player, new View(Page.HIDDEN_CATEGORIES, namespace.id(), null, 0));
    }

    private Item categoryButtonItem(Player player, CategoryNamespaces.ButtonEntry button,
                                    boolean applied, int index) {
        ItemStack stack = this.buttonIcon(button, player);
        List<Component> lore = new ArrayList<>();
        lore.add(this.plugin.lang().component(applied
                ? "ui.management.applied-lore" : "ui.management.unapplied-lore"));
        lore.add(this.plugin.lang().component("ui.management.button-type"));
        lore.add(this.plugin.lang().component("ui.management.button-use"));
        lore.add(this.plugin.lang().component("ui.management.button-edit"));
        if (applied) {
            lore.add(this.plugin.lang().component("ui.management.namespace-order"));
            lore.add(this.plugin.lang().component("ui.management.button-unapply"));
        } else {
            lore.add(this.plugin.lang().component("ui.management.button-apply"));
        }
        stack = new ItemBuilder(stack)
                .setName(this.plainOr(button.display().name(), this.plugin.lang().text("ui.management.button-name")))
                .setLore(lore)
                .build();
        return Item.builder().setItemProvider(stack).addClickHandler(click -> this.browser.handleClick(
                click.player(), () -> {
                    Player viewer = click.player();
                    ClickType type = click.clickType();
                    if (!applied && (type == ClickType.MIDDLE || type == ClickType.CREATIVE)) {
                        String name = PlainTextComponentSerializer.plainText().serialize(this.plainOr(
                                button.display().name(), this.plugin.lang().text("ui.management.button-name")));
                        this.applyUnappliedEntry(viewer, index, name);
                    } else if (type == ClickType.RIGHT || type == ClickType.SHIFT_RIGHT && !applied) {
                        this.plugin.buttonEditorDialog().open(viewer,
                                applied ? ButtonEditorDialogService.Source.CATEGORIES
                                        : ButtonEditorDialogService.Source.CATEGORIES_UNAPPLIED,
                                index);
                    } else if (applied && type == ClickType.DROP) {
                        String name = PlainTextComponentSerializer.plainText().serialize(this.plainOr(
                                button.display().name(), this.plugin.lang().text("ui.management.button-name")));
                        this.unapplyEntry(viewer, index, name);
                    } else if (applied && type == ClickType.SHIFT_LEFT) {
                        this.moveCategoryEntry(viewer, index, -1, true);
                    } else if (applied && type == ClickType.SHIFT_RIGHT) {
                        this.moveCategoryEntry(viewer, index, 1, true);
                    } else {
                        ButtonActions.run(viewer, button, type);
                    }
                })).build();
    }

    private List<Item> hiddenCategoryItems(Player player, String namespace) {
        CategoryNamespaces.NamespaceEntry entry = this.plugin.data().namespaces().findNamespace(namespace);
        if (entry == null) return List.of();
        Set<Key> included = new LinkedHashSet<>();
        this.plugin.data().categoriesById().values().stream()
                .filter(category -> category.id().namespace().equals(namespace))
                .map(Category::id)
                .forEach(included::add);
        entry.hiddenCategories().forEach(id -> addCategoryReference(included, id));
        entry.hiddenItems().keySet().forEach(id -> addCategoryReference(included, id));
        List<Category> categories = included.stream()
                .map(id -> this.plugin.data().categoriesById().get(id))
                .filter(java.util.Objects::nonNull)
                .sorted(Comparator.comparing(category -> category.id().toString()))
                .toList();
        List<Item> items = new ArrayList<>(categories.size());
        for (Category category : categories) {
            String id = category.id().toString();
            boolean categoryHidden = this.plugin.data().namespaces().isCategoryHidden(id);
            Set<String> hidden = this.plugin.data().namespaces().hiddenItems(id);
            boolean allHidden = hidden != null && hidden.contains("*");
            List<CategoryGraph.Member> members = this.plugin.data().categoryGraph().members(category.id());
            int directItems = (int) members.stream().filter(member -> !member.category()).count();
            int children = (int) members.stream().filter(CategoryGraph.Member::category).count();
            List<Component> lore = List.of(
                    this.plugin.lang().component(categoryHidden
                            ? "ui.management.category-hidden" : "ui.management.category-visible"),
                    this.plugin.lang().component("ui.management.category-count",
                            "items", String.valueOf(directItems), "children", String.valueOf(children)),
                    this.plugin.lang().component("ui.management.category-open-items"),
                    this.plugin.lang().component("ui.management.category-toggle"),
                    this.plugin.lang().component(allHidden
                            ? "ui.management.items-all-hidden" : "ui.management.items-toggle-all"));
            ItemStack stack = new ItemBuilder(ItemBridge.build(category.icon(), player))
                    .setName(this.plugin.config().mm(DisplayNames.of(category)))
                    .setLore(lore)
                    .setGlint(categoryHidden || allHidden ? Boolean.TRUE : null)
                    .build();
            items.add(Item.builder().setItemProvider(stack).addClickHandler(click -> this.browser.handleClick(
                    click.player(), () -> this.handleCategoryClick(click.player(), namespace, category,
                            click.clickType()))).build());
        }
        return items;
    }

    private void handleCategoryClick(Player player, String namespace, Category category, ClickType click) {
        String id = category.id().toString();
        if (click == ClickType.RIGHT) {
            this.mutate(player, () -> this.plugin.data().namespaces().setCategoryHidden(namespace, id,
                    !this.plugin.data().namespaces().isCategoryHidden(id)), "messages.management-saved");
            return;
        }
        if (click == ClickType.SHIFT_LEFT || click == ClickType.SHIFT_RIGHT) {
            Set<String> hidden = this.plugin.data().namespaces().hiddenItems(id);
            boolean allHidden = hidden != null && hidden.contains("*");
            this.mutate(player, () -> this.plugin.data().namespaces().setAllItemsHidden(namespace, id,
                    !allHidden), "messages.management-saved");
            return;
        }
        this.navigate(player, new View(Page.CATEGORY_ITEMS, namespace, id, 0));
    }

    private List<Item> categoryItemItems(Player player, String namespace, String categoryId) {
        Category category = this.plugin.data().category(Key.of(categoryId)).orElse(null);
        if (category == null) return List.of();
        Set<String> hidden = this.plugin.data().namespaces().hiddenItems(categoryId);
        List<String> direct = this.plugin.data().categoryGraph().members(category.id()).stream()
                .filter(member -> !member.category())
                .map(member -> member.key().toString())
                .distinct()
                .toList();
        List<Item> items = new ArrayList<>(direct.size());
        for (String itemId : direct) {
            boolean isHidden = hidden != null && (hidden.contains("*") || hidden.contains(itemId));
            Key key = Key.of(itemId);
            String displayName = this.plugin.data().itemDisplayName(itemId);
            ItemStack stack = new ItemBuilder(ItemBridge.build(key, player))
                    .setName(Component.text(displayName == null || displayName.isBlank()
                            ? itemId : displayName))
                    .setLore(List.of(
                            this.plugin.lang().component(isHidden
                                    ? "ui.management.item-hidden" : "ui.management.item-visible"),
                            this.plugin.lang().component("ui.management.item-id", "id", itemId),
                            this.plugin.lang().component("ui.management.item-toggle")))
                    .setGlint(isHidden ? Boolean.TRUE : null)
                    .build();
            items.add(Item.builder().setItemProvider(stack).addClickHandler(click ->
                    this.toggleItem(click.player(), namespace, categoryId, itemId, isHidden, direct)).build());
        }
        return items;
    }

    private void toggleItem(Player player, String namespace, String categoryId, String itemId,
                            boolean hidden, List<String> direct) {
        this.mutate(player, () -> this.plugin.data().namespaces().setItemHidden(namespace, categoryId,
                itemId, !hidden, direct), "messages.management-saved");
    }

    private List<Item> workstationItems(Player player) {
        Map<String, RecipeWorkstation> stations = new LinkedHashMap<>();
        for (RecipeWorkstation station : this.plugin.recipeIndex().workstations(player)) {
            stations.put(station.id(), station);
        }
        List<WorkstationConfig.Entry> entries = this.plugin.workstationConfig().entries();
        List<Item> items = new ArrayList<>(entries.size());
        for (int index = 0; index < entries.size(); index++) {
            WorkstationConfig.Entry entry = entries.get(index);
            int entryIndex = index;
            if (entry instanceof WorkstationConfig.StationEntry station) {
                RecipeWorkstation definition = stations.get(station.id());
                ItemStack stack = definition == null
                        ? new ItemStack(Material.CRAFTING_TABLE)
                        : ItemBridge.build(definition.icon(), player, 1);
                String name = definition == null ? station.id() : definition.name();
                stack = new ItemBuilder(stack)
                        .setName(this.plugin.config().mm(name == null || name.isBlank() ? station.id() : name))
                        .setLore(List.of(
                                this.plugin.lang().component("ui.management.workstation-id", "id", station.id()),
                                this.plugin.lang().component("ui.management.workstation-use"),
                                this.plugin.lang().component("ui.management.workstation-order")))
                        .build();
                items.add(Item.builder().setItemProvider(stack).addClickHandler(click ->
                        this.browser.handleClick(click.player(), () -> {
                            if (click.clickType() == ClickType.SHIFT_LEFT) {
                                this.moveWorkstation(click.player(), entryIndex, -1);
                            } else if (click.clickType() == ClickType.SHIFT_RIGHT) {
                                this.moveWorkstation(click.player(), entryIndex, 1);
                            } else {
                                this.browser.openWorkstation(click.player(), station.id());
                            }
                        })).build());
            } else if (entry instanceof WorkstationConfig.ButtonEntry button) {
                ItemStack stack = new ItemBuilder(this.buttonIcon(button.button(), player))
                        .setName(this.plainOr(button.button().display().name(),
                                this.plugin.lang().text("ui.management.button-name")))
                        .setLore(List.of(
                                this.plugin.lang().component("ui.management.button-type"),
                                this.plugin.lang().component("ui.management.button-edit"),
                                this.plugin.lang().component("ui.management.workstation-order")))
                        .build();
                items.add(Item.builder().setItemProvider(stack).addClickHandler(click ->
                        this.browser.handleClick(click.player(), () -> {
                            ClickType type = click.clickType();
                            if (type == ClickType.RIGHT) {
                                this.plugin.buttonEditorDialog().open(click.player(),
                                        ButtonEditorDialogService.Source.WORKSTATIONS, entryIndex);
                            } else if (type == ClickType.SHIFT_LEFT) {
                                this.moveWorkstation(click.player(), entryIndex, -1);
                            } else if (type == ClickType.SHIFT_RIGHT) {
                                this.moveWorkstation(click.player(), entryIndex, 1);
                            } else {
                                ButtonActions.run(click.player(), button.button(), type);
                            }
                        })).build());
            }
        }
        return items;
    }

    private void apply(Player player, String namespace) {
        boolean applied = this.plugin.data().namespaces().applyUnapplied(namespace);
        player.sendMessage(this.plugin.lang().message(applied
                ? "messages.management-applied" : "messages.management-save-failed", "namespace", namespace));
        if (applied) this.navigate(player, new View(Page.CATEGORIES, null, null, 0));
    }

    private void applyUnappliedEntry(Player player, int index, String label) {
        boolean applied = this.plugin.data().namespaces().applyUnappliedEntry(index);
        player.sendMessage(this.plugin.lang().message(applied
                ? "messages.management-applied-entry" : "messages.management-save-failed",
                "entry", label));
        Sounds.play(player, applied ? this.browser.managementUi().sounds().click()
                : this.browser.managementUi().sounds().queryFailure());
        if (applied) this.navigate(player, new View(Page.CATEGORIES, null, null, 0));
    }

    private void unapplyEntry(Player player, int index, String label) {
        boolean moved = this.plugin.data().namespaces().unapplyEntry(index);
        player.sendMessage(this.plugin.lang().message(moved
                ? "messages.management-unapplied" : "messages.management-save-failed", "entry", label));
        Sounds.play(player, moved ? this.browser.managementUi().sounds().click()
                : this.browser.managementUi().sounds().queryFailure());
        if (moved) this.navigate(player, new View(Page.CATEGORIES, null, null, 0));
    }

    private void moveCategoryEntry(Player player, int index, int offset, boolean applied) {
        boolean moved = this.plugin.data().namespaces().moveEntry(index, offset);
        this.afterMove(player, moved, applied ? Page.CATEGORIES : Page.UNAPPLIED);
    }

    private void moveWorkstation(Player player, int index, int offset) {
        boolean moved = this.plugin.workstationConfig().moveEntry(index, offset);
        this.afterMove(player, moved, Page.WORKSTATIONS);
    }

    private void afterMove(Player player, boolean moved, Page page) {
        player.sendMessage(this.plugin.lang().message(moved
                ? "messages.management-moved" : "messages.management-edge"));
        Sounds.play(player, moved ? this.browser.managementUi().sounds().click()
                : this.browser.managementUi().sounds().queryFailure());
        if (moved) {
            View current = this.views.getOrDefault(player.getUniqueId(), new View(page, null, null, 0));
            this.navigate(player, new View(page, current.namespace(), current.category(), current.contentPage()));
        }
    }

    private void mutate(Player player, java.util.function.BooleanSupplier action, String messageKey) {
        boolean changed = action.getAsBoolean();
        player.sendMessage(this.plugin.lang().message(changed ? messageKey : "messages.management-save-failed"));
        Sounds.play(player, changed ? this.browser.managementUi().sounds().click()
                : this.browser.managementUi().sounds().queryFailure());
        if (changed) this.reopen(player);
    }

    private void bindManagementLayout(PagedGui.Builder<Item> builder, Player player, View view,
                                      CerConfig ui, String[] structure) {
        Set<Character> bound = new LinkedHashSet<>();
        for (String row : structure) {
            for (int index = 0; index < row.length(); index++) {
                char symbol = row.charAt(index);
                if (!bound.add(symbol)) continue;
                switch (symbol) {
                    case DIRECTORY_PREVIOUS_SLOT -> builder.addIngredient(symbol, this.directoryNavItem(ui, false));
                    case DIRECTORY_NEXT_SLOT -> builder.addIngredient(symbol, this.directoryNavItem(ui, true));
                    case CONTENT_SLOT -> builder.addIngredient(symbol, Markers.CONTENT_LIST_SLOT_HORIZONTAL);
                    case PREVIOUS_SLOT -> builder.addIngredient(symbol, this.pageButton(ui, false));
                    case NEXT_SLOT -> builder.addIngredient(symbol, this.pageButton(ui, true));
                    case BACK_SLOT -> builder.addIngredient(symbol, this.backItem(player, view, ui));
                    case CLOSE_SLOT -> builder.addIngredient(symbol, this.closeItem(ui));
                    default -> builder.addIngredient(symbol, Item.EMPTY);
                }
            }
        }
    }

    private Item pageButton(CerConfig ui, boolean next) {
        CerConfig.ButtonDef def = next ? ui.nextPage() : ui.prevPage();
        return BoundItem.pagedBuilder().setItemProvider((player, gui) -> {
            boolean available = next ? gui.getPage() + 1 < gui.getPageCount() : gui.getPage() > 0;
            Component name = available ? ui.mm(def.name())
                    : this.plugin.lang().component(next ? "ui.common.last-page" : "ui.common.first-page");
            return new ItemWrapper(this.managementButton(name, List.of()));
        }).addClickHandler((item, gui, click) -> this.browser.handleClick(click.player(), () -> {
            int target = gui.getPage() + (next ? 1 : -1);
            if (target < 0 || target >= gui.getPageCount()) return;
            gui.setPage(target);
            Sounds.play(click.player(), ui.sounds().changePage());
        })).build();
    }

    private Item backItem(Player player, View view, CerConfig ui) {
        CerConfig.ButtonDef def = ui.back();
        ItemStack stack = this.managementButton(ui.mm(def.name()),
                def.lore().stream().map(ui::mm).toList());
        return Item.builder().setItemProvider(stack).addClickHandler(click ->
                this.browser.handleClick(click.player(), () -> {
                    Sounds.play(click.player(), ui.sounds().back());
                    switch (view.page()) {
                        case HOME -> this.browser.openDefault(click.player());
                        case CATEGORIES, UNAPPLIED, WORKSTATIONS -> this.navigate(click.player(), View.home());
                        case HIDDEN_CATEGORIES -> this.navigate(click.player(),
                                new View(this.plugin.data().namespaces().isApplied(view.namespace())
                                        ? Page.CATEGORIES : Page.UNAPPLIED, null, null, 0));
                        case CATEGORY_ITEMS -> this.navigate(click.player(),
                                new View(Page.HIDDEN_CATEGORIES, view.namespace(), null, 0));
                    }
                })).build();
    }

    private Item closeItem(CerConfig ui) {
        ItemStack stack = this.managementButton(
                ui.mm(this.plugin.lang().text("ui.management.return-name")),
                List.of(ui.mm(this.plugin.lang().text("ui.management.return-lore"))));
        return Item.builder().setItemProvider(stack).addClickHandler(click ->
                this.browser.handleClick(click.player(),
                        () -> this.browser.openDefault(click.player()))).build();
    }

    /** 管理页视觉由固定背景贴图提供，交互物品使用透明模型，仅保留悬浮说明。 */
    private ItemStack managementButton(Component name, List<Component> lore) {
        return ItemBridge.icon("minecraft:air", Material.PAPER, name, lore);
    }

    private Item actionItem(Material material, String nameKey, List<String> loreKeys,
                            String key1, String value1, boolean active, Runnable action) {
        return this.actionItem(material, nameKey, loreKeys, key1, value1,
                null, null, null, null, active, action);
    }

    private Item actionItem(Material material, String nameKey, List<String> loreKeys,
                            String key1, String value1, String key2, String value2,
                            String key3, String value3, boolean active, Runnable action) {
        List<Component> lore = new ArrayList<>();
        for (String key : loreKeys) {
            if (key.endsWith("-summary")) {
                lore.add(this.plugin.lang().component(key, key1, value1,
                        key2 == null ? key1 : key2, key2 == null ? value1 : value2,
                        key3 == null ? key1 : key3, key3 == null ? value1 : value3));
            } else {
                lore.add(this.plugin.lang().component(key));
            }
        }
        ItemStack stack = this.actionStack(material,
                this.plugin.lang().component(nameKey), lore, active);
        return Item.builder().setItemProvider(stack).addClickHandler(click ->
                this.browser.handleClick(click.player(), action)).build();
    }

    private ItemStack actionStack(Material material, Component name, List<Component> lore, boolean active) {
        return new ItemBuilder(material).setName(name).setLore(lore)
                .setGlint(active ? Boolean.TRUE : null).build();
    }

    private ItemStack buttonIcon(CategoryNamespaces.ButtonEntry button, Player player) {
        String material = button.display().material();
        ItemStack stack;
        try {
            if (material.startsWith("source:CraftEngine:")) {
                stack = ItemBridge.build(Key.of(material.substring("source:CraftEngine:".length())), player);
            } else if (material.matches("[a-z0-9_.-]+:[a-z0-9_./-]+")) {
                stack = ItemBridge.build(Key.of(material), player);
            } else {
                Material vanilla = Material.matchMaterial(material);
                stack = new ItemStack(vanilla == null || !vanilla.isItem() ? Material.PAPER : vanilla);
            }
        } catch (RuntimeException ignored) {
            stack = new ItemStack(Material.PAPER);
        }
        return stack;
    }

    private Category firstCategory(String namespace) {
        List<Category> categories = this.plugin.data().categoriesById().values().stream()
                .filter(category -> category.id().namespace().equals(namespace))
                .sorted(Comparator.comparing(category -> category.id().toString()))
                .toList();
        return categories.stream()
                .filter(category -> !this.plugin.data().namespaces().isCategoryHidden(category.id().toString()))
                .findFirst()
                .orElse(categories.isEmpty() ? null : categories.getFirst());
    }

    private static void addCategoryReference(Set<Key> target, String id) {
        try {
            target.add(Key.of(id));
        } catch (IllegalArgumentException ignored) {
            // 手工配置中的无效 ID 已由加载器报告，不应阻断其他分类的管理。
        }
    }

    private Item emptyItem(CerConfig ui) {
        return Item.simple(ItemBridge.button(ui.empty().material(), ui.mm(ui.empty().name()),
                ui.empty().lore().stream().map(ui::mm).toList()));
    }

    private String visibilityName(CategoryNamespaces.Visibility visibility) {
        return this.plugin.lang().text("ui.management.visibility-" + visibility.name().toLowerCase(Locale.ROOT));
    }

    private String onOff(boolean value) {
        boolean chinese = this.plugin.lang().locale().toLowerCase(Locale.ROOT).startsWith("zh");
        return this.plugin.lang().textOr(
                value ? "ui.personalization.on" : "ui.personalization.off",
                value ? (chinese ? "开启" : "Enabled") : (chinese ? "关闭" : "Disabled"));
    }

    private Component plainOr(String value, String fallback) {
        return this.plugin.config().mm(value == null || value.isBlank() ? fallback : value);
    }

    private String pageTitle(View view) {
        String key = switch (view.page()) {
            case HOME -> "ui.management.name";
            case CATEGORIES -> "ui.management.categories-name";
            case UNAPPLIED -> "ui.management.unapplied-name";
            case WORKSTATIONS -> "ui.management.workstations-name";
            case HIDDEN_CATEGORIES -> "ui.management.hidden-name";
            case CATEGORY_ITEMS -> "ui.management.items-name";
        };
        return this.plugin.lang().text(key);
    }
}
