package dev.che1s.craftenginerecipe.gui;

import dev.che1s.craftenginerecipe.CraftEngineRecipePlugin;
import dev.che1s.craftenginerecipe.category.CategoryNamespaces;
import dev.che1s.craftenginerecipe.config.CerConfig;
import dev.che1s.craftenginerecipe.config.MenuView;
import dev.che1s.craftenginerecipe.data.CraftEngineData;
import dev.che1s.craftenginerecipe.util.ButtonActions;
import dev.che1s.craftenginerecipe.util.CompatibilityWarnings;
import dev.che1s.craftenginerecipe.util.DisplayNames;
import dev.che1s.craftenginerecipe.util.ItemBridge;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.TextColor;
import net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
import net.momirealms.craftengine.core.plugin.gui.category.Category;
import net.momirealms.craftengine.core.util.Key;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.event.inventory.ClickType;
import org.bukkit.inventory.ItemStack;
import xyz.xenondevs.invui.gui.Gui;
import xyz.xenondevs.invui.gui.SlotElement;
import xyz.xenondevs.invui.item.BoundItem;
import xyz.xenondevs.invui.item.Item;
import xyz.xenondevs.invui.item.ItemBuilder;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Category-bar entries and their item rendering, independent from page navigation. */
final class BrowserCategoryView {
    sealed interface BarEntry permits CategoryEntry, ButtonEntry, VanillaItemsEntry {
    }

    record CategoryEntry(Category category) implements BarEntry {
    }

    record ButtonEntry(CategoryNamespaces.ButtonEntry button) implements BarEntry {
    }

    record VanillaItemsEntry(CategoryNamespaces.NamespaceEntry namespace) implements BarEntry {
    }

    private final CraftEngineRecipePlugin plugin;
    private final CraftEngineData data;
    private final BrowserService browser;

    BrowserCategoryView(CraftEngineRecipePlugin plugin, CraftEngineData data, BrowserService browser) {
        this.plugin = plugin;
        this.data = data;
        this.browser = browser;
    }

    List<Category> allTopCategories() {
        List<Category> categories = new ArrayList<>();
        CerConfig config = this.browser.config();
        if (config.categoryFollowCe()) {
            for (Category category : this.data.topCategories()) {
                if (CraftEngineData.ALL_ITEMS_CATEGORY.equals(category.id())) continue;
                if (this.data.namespaces().isCategoryHidden(category.id().toString())) continue;
                categories.add(category);
            }
        } else {
            for (String id : config.categoryList()) {
                Key key = Key.of(id);
                if (CraftEngineData.ALL_ITEMS_CATEGORY.equals(key)) continue;
                if (this.data.namespaces().isCategoryHidden(key.toString())) continue;
                this.data.category(key).ifPresent(categories::add);
            }
        }
        return categories;
    }

    List<BarEntry> barEntries(Player player) {
        Map<String, List<Category>> byNamespace = new LinkedHashMap<>();
        for (Category category : this.allTopCategories()) {
            byNamespace.computeIfAbsent(category.id().namespace(), ignored -> new ArrayList<>()).add(category);
        }
        List<CategoryNamespaces.Entry> source = GuiSession.of(player).unapplied()
                ? new ArrayList<>(this.data.namespaces().unappliedEntries())
                : this.data.namespaces().entries();
        List<BarEntry> entries = new ArrayList<>();
        for (CategoryNamespaces.Entry entry : source) {
            if (entry instanceof CategoryNamespaces.NamespaceEntry namespace) {
                if (namespace.hideInaccessible()
                        && !this.data.namespaces().canView(player, namespace.id())) continue;
                if (!namespace.categoryEntry() && !GuiSession.of(player).unapplied()) continue;
                List<Category> categories = byNamespace.get(namespace.id());
                if ("minecraft".equals(namespace.id()) && (categories == null || categories.isEmpty())) {
                    entries.add(new VanillaItemsEntry(namespace));
                } else if (categories != null) {
                    categories.forEach(category -> entries.add(new CategoryEntry(category)));
                }
            } else if (entry instanceof CategoryNamespaces.ButtonEntry button) {
                if (button.hideInaccessible()
                        && !this.data.namespaces().canViewButton(player, button)) continue;
                entries.add(new ButtonEntry(button));
            }
        }
        return entries;
    }

    List<Category> visibleCategories(Player player) {
        List<Category> categories = new ArrayList<>();
        for (BarEntry entry : this.barEntries(player)) {
            if (entry instanceof CategoryEntry current
                    && this.data.namespaces().canView(player, current.category().id().namespace())) {
                categories.add(current.category());
            }
        }
        return categories;
    }

    void fillCategoryBar(Gui gui, Player player, Key activeCategory, int categoryPage,
                         List<Integer> slots, MenuView view) {
        this.fillCategoryBar(gui, player, activeCategory, categoryPage, slots,
                this.barEntries(player), view);
    }

    void fillCategoryBar(Gui gui, Player player, Key activeCategory, int categoryPage,
                         List<Integer> slots, List<BarEntry> entries, MenuView view) {
        CerConfig ui = this.browser.ui(view);
        int perPage = Math.max(1, slots.size());
        int maxOffset = Math.max(0, entries.size() - perPage);
        int offset = Math.max(0, Math.min(categoryPage, maxOffset));
        GuiSession.of(player).categoryPage(offset);
        for (int index = 0; index < slots.size(); index++) {
            int slot = slots.get(index);
            int entryIndex = offset + index;
            if (entryIndex >= entries.size()) {
                gui.setSlotElement(slot, null);
                continue;
            }
            BarEntry entry = entries.get(entryIndex);
            if (entry instanceof CategoryEntry current) {
                Category category = current.category();
                gui.setSlotElement(slot, new SlotElement.Item(this.categoryIcon(
                        player, category, category.id().equals(activeCategory), ui)));
            } else if (entry instanceof ButtonEntry current) {
                if (!this.data.namespaces().canViewButton(player, current.button())) {
                    gui.setSlotElement(slot, null);
                    continue;
                }
                gui.setSlotElement(slot, new SlotElement.Item(
                        this.buttonEntryItem(player, current.button(), ui.categoryBarNamePrefix())));
            } else if (entry instanceof VanillaItemsEntry current) {
                Item item = this.data.namespaces().canView(player, "minecraft")
                        ? this.vanillaItemsIcon(player, current.namespace(), ui)
                        : Item.simple(ItemBridge.button(Material.BARRIER,
                        this.accessDeniedComponent(player, "minecraft", ui), List.of()));
                gui.setSlotElement(slot, new SlotElement.Item(item));
            }
        }
    }

    Item categoryNavItem(boolean next, int perPage, Key activeCategory, MenuView view) {
        CerConfig ui = this.browser.ui(view);
        CerConfig.ButtonDef definition = next ? ui.nextCategory() : ui.prevCategory();
        ItemStack stack = ItemBridge.icon(definition.itemModel(), definition.material(),
                ui.mm(definition.name()), this.buttonLore(ui, definition));
        return BoundItem.pagedBuilder().setItemProvider(stack)
                .addClickHandler((item, gui, click) -> this.browser.handleClick(click.player(), () -> {
                    Player player = click.player();
                    GuiSession session = GuiSession.of(player);
                    int current = session.categoryPage();
                    List<BarEntry> entries = this.barEntries(player);
                    int maxOffset = Math.max(0, entries.size() - perPage);
                    int target = next ? current + 1 : current - 1;
                    List<Integer> slots = BrowserLayout.slotsOf(BrowserLayout.normalize(ui),
                            ui.categoryIconChar());
                    if (slots.isEmpty() || target < 0 || target > maxOffset) {
                        Sounds.play(player, ui.sounds().queryFailure());
                        return;
                    }
                    session.categoryPage(target);
                    this.fillCategoryBar(gui, player, activeCategory, target, slots, entries, view);
                    Sounds.play(player, ui.sounds().changeCategory());
                })).build();
    }

    Item subCategoryIcon(Player player, Category category) {
        CerConfig ui = this.browser.currentUi(player);
        ItemStack icon = ItemBridge.build(category.icon(), player);
        List<Component> lore = new ArrayList<>();
        int count = this.browser.countMembers(category, player);
        for (String line : this.browser.subIconLoreFor(player, category, ui)) {
            lore.add(ui.mm(line, "count", String.valueOf(count)));
        }
        ItemStack decorated = ItemBridge.decorate(icon,
                directoryName(this.categoryName(player, category, ui), ui, ui.categoryBarNamePrefix()),
                directoryLore(lore));
        return Item.builder().setItemProvider(decorated)
                .addClickHandler(click -> this.browser.handleClick(click.player(), () -> {
                    Player viewer = click.player();
                    Sounds.play(viewer, ui.sounds().click());
                    this.browser.openCategory(viewer, category.id(), GuiSession.of(viewer).categoryPage());
                })).build();
    }

    private Item vanillaItemsIcon(Player player, CategoryNamespaces.NamespaceEntry namespace, CerConfig ui) {
        var language = this.plugin.lang(player);
        String fallback = language.text("ui.vanilla-items.name");
        String raw = LocalizedConfigText.resolve(language, namespace.iconName().resolve(language));
        Component name = ui.mm((raw == null || raw.isBlank() ? fallback : raw)
                .replace("<name>", fallback));
        int count = this.browser.visibleVanillaItems(player, namespace).size();
        List<Component> lore = LocalizedConfigText.resolve(
                        language, namespace.iconLore().resolveLines(language)).stream()
                .map(line -> ui.mm(line, "count", String.valueOf(count))).toList();
        ItemStack stack = ItemBridge.button(Material.GRASS_BLOCK,
                directoryName(name, ui, ui.categoryBarNamePrefix()), directoryLore(lore));
        return Item.builder().setItemProvider(stack)
                .addClickHandler(click -> this.browser.handleClick(click.player(), () -> {
                    Sounds.play(click.player(), ui.sounds().click());
                    this.browser.openVanillaItems(click.player());
                })).build();
    }

    private Item categoryIcon(Player player, Category category, boolean active, CerConfig ui) {
        if (!this.data.namespaces().canView(player, category.id().namespace())) {
            return this.categoryPlaceholder(player, category, ui);
        }
        ItemStack icon;
        try {
            icon = ItemBridge.build(category.icon(), player);
        } catch (RuntimeException | LinkageError error) {
            CompatibilityWarnings.warn("Browser.categoryIcon", error);
            icon = new ItemStack(ui.empty().material());
        }
        List<Component> lore = new ArrayList<>();
        int count = this.browser.countMembers(category, player);
        for (String line : this.browser.iconLoreFor(player, category, ui)) {
            lore.add(ui.mm(line, "count", String.valueOf(count)));
        }
        if (GuiSession.of(player).unapplied()
                && player.hasPermission("craftenginerecipe.admin")) {
            lore.add(this.plugin.lang(player).component("ui.unapplied-editor.edit-lore"));
        }
        ItemStack decorated = new ItemBuilder(icon)
                .setName(directoryName(this.categoryName(player, category, ui), ui,
                        ui.categoryBarNamePrefix()))
                .setLore(directoryLore(lore))
                .setGlint(active ? Boolean.TRUE : null)
                .build();
        return Item.builder().setItemProvider(decorated)
                .addClickHandler(click -> this.browser.handleClick(click.player(), () -> {
                    Player viewer = click.player();
                    boolean edit = GuiSession.of(viewer).unapplied()
                            && (click.clickType() == ClickType.RIGHT
                            || click.clickType() == ClickType.SHIFT_RIGHT);
                    if (edit && this.plugin.dialogs().unapplied() != null
                            && this.plugin.dialogs().unapplied().open(
                            viewer, category.id().namespace())) {
                        Sounds.play(viewer, ui.sounds().click());
                        return;
                    }
                    Sounds.play(viewer, ui.sounds().click());
                    this.browser.openCategory(
                            viewer, category.id(), GuiSession.of(viewer).categoryPage());
                })).build();
    }

    Item buttonEntryItem(Player player, CategoryNamespaces.ButtonEntry button, String prefix) {
        String material = button.display().material();
        ItemStack icon;
        if (material == null || material.isBlank()) {
            icon = new ItemStack(Material.STONE);
        } else {
            String itemId = material.startsWith("source:CraftEngine:")
                    ? material.substring("source:CraftEngine:".length()) : material;
            icon = ItemBridge.build(Key.of(itemId), player, 1);
        }
        var language = this.plugin.lang(player);
        Component name = CerConfig.noItalic(
                LegacyComponentSerializer.legacyAmpersand().deserialize(
                        LocalizedConfigText.resolve(language, button.display().name().resolve(language))));
        List<Component> lore = LocalizedConfigText.resolve(
                        language, button.display().lore().resolveLines(language)).stream()
                .map(line -> CerConfig.noItalic(
                        LegacyComponentSerializer.legacyAmpersand().deserialize(line)))
                .toList();
        ItemStack decorated = ItemBridge.decorate(icon,
                directoryName(name, this.browser.currentUi(player), prefix), directoryLore(lore));
        return Item.builder().setItemProvider(decorated)
                .addClickHandler(click -> this.browser.handleClick(click.player(), () ->
                        ButtonActions.run(click.player(), button, click.clickType()))).build();
    }

    private Component categoryName(Player player, Category category, CerConfig ui) {
        String template = this.data.namespaces().iconNameOf(category.id().namespace())
                .resolve(this.plugin.lang(player));
        template = LocalizedConfigText.resolve(this.plugin.lang(player), template);
        if (template == null || template.isBlank()) {
            return ui.mm(this.browser.plainName(player, category, ui));
        }
        String name = DisplayNames.of(category, this.data.resourcePackTranslations(player));
        if (name == null || name.isBlank()) name = category.id().value();
        return ui.mm(DisplayNames.expandL10n(template).replace("<name>", name));
    }

    private Item categoryPlaceholder(Player player, Category category, CerConfig ui) {
        ItemStack stack = ItemBridge.button(Material.BARRIER,
                this.accessDeniedComponent(player, category.id().namespace(), ui), List.of());
        return Item.simple(stack);
    }

    Component accessDeniedComponent(Player player, String namespace, CerConfig ui) {
        if (this.data.namespaces().visibilityOf(namespace)
                == CategoryNamespaces.Visibility.PERMISSION) {
            String permission = this.data.namespaces().permissionOf(namespace);
            return ui.mm(ui.noPermissionHint(), "permission",
                    permission == null ? "" : permission);
        }
        return ui.mm(ui.adminOnlyHint());
    }

    private List<Component> buttonLore(CerConfig ui, CerConfig.ButtonDef definition) {
        return definition.lore().stream().map(ui::mm).toList();
    }

    static Component directoryName(Component source, CerConfig config, String prefix) {
        return config.styledName(prefix, source);
    }

    static List<Component> directoryLore(List<Component> source) {
        return source.stream()
                .map(line -> PlainTextComponentSerializer.plainText().serialize(line))
                .filter(line -> !line.isBlank())
                .map(line -> CerConfig.noItalic(
                        Component.text(line, TextColor.color(0xB8C0CC))))
                .toList();
    }
}
