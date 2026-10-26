package dev.che1s.craftenginerecipe.gui;

import dev.che1s.craftenginerecipe.CraftEngineRecipePlugin;
import dev.che1s.craftenginerecipe.category.CategoryNamespaces;
import dev.che1s.craftenginerecipe.config.WorkstationConfig;
import dev.che1s.craftenginerecipe.config.MenuView;
import dev.che1s.craftenginerecipe.debug.Diagnostics;
import dev.che1s.craftenginerecipe.config.CerConfig;
import dev.che1s.craftenginerecipe.data.CraftEngineData;
import dev.che1s.craftenginerecipe.recipe.RecipeIndex;
import dev.che1s.craftenginerecipe.recipe.RecipeEntry;
import dev.che1s.craftenginerecipe.api.RecipeWorkstation;
import dev.che1s.craftenginerecipe.util.ItemBridge;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
import net.momirealms.craftengine.core.item.recipe.RecipeType;
import net.momirealms.craftengine.core.util.Key;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.event.inventory.ClickType;
import org.bukkit.inventory.ItemStack;
import xyz.xenondevs.invui.gui.Gui;
import xyz.xenondevs.invui.gui.PagedGui;
import xyz.xenondevs.invui.gui.SlotElement;
import xyz.xenondevs.invui.item.BoundItem;
import xyz.xenondevs.invui.item.Item;
import xyz.xenondevs.invui.item.ItemBuilder;
import xyz.xenondevs.invui.window.Window;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/** 基于同一 CE 快照和玩家级分类可见性规则的第二浏览视图。 */
final class WorkstationBrowserService {
    private final CraftEngineRecipePlugin plugin;
    private final BrowserService browser;
    private final CerConfig config;
    private final CraftEngineData data;
    private final RecipeIndex recipes;
    private final RecipeGuiService recipeGui;

    WorkstationBrowserService(CraftEngineRecipePlugin plugin, BrowserService browser, CerConfig config,
                              CraftEngineData data, RecipeIndex recipes, RecipeGuiService recipeGui) {
        this.plugin = plugin;
        this.browser = browser;
        this.config = config;
        this.data = data;
        this.recipes = recipes;
        this.recipeGui = recipeGui;
    }

    void open(Player player) {
        GuiSession.of(player).workstationCategoryOffset(0);
        this.open(player, RecipeIndex.stationId(RecipeType.CRAFTING), 0, null);
    }

    void reopen(Player player) {
        GuiSession session = GuiSession.of(player);
        this.open(player, session.workstationId() == null ? RecipeIndex.stationId(RecipeType.CRAFTING) : session.workstationId(),
                session.workstationPage(), session.workstationKeyword());
    }

    private void open(Player player, String type, int page, String keyword) {
        try (var timing = this.plugin.diagnostics().measure(Diagnostics.Operation.WORKSTATION_OPEN)) {
            if (!this.browser.claim(player)) return;
            long generation = this.recipes.generation();
            List<RecipeEntry.Output> outputs = this.recipes.outputs(type, player);
            if (keyword != null && !keyword.isBlank()) {
                // 仅搜索当前合成器的可见产物，避免其他合成器占用结果上限。
                Map<Key, RecipeEntry.Output> byResult = new LinkedHashMap<>();
                outputs.forEach(output -> byResult.put(output.result(), output));
                outputs = this.browser.searchItems(keyword, this.config.searchMaxResults(), byResult::containsKey)
                        .stream().map(byResult::get).toList();
            }
            List<Item> content = new LazyItems<>(outputs, output -> BrowserService.lazyItem(() -> {
                ItemStack icon = ItemBridge.build(output.result(), player);
                List<Component> lore = this.config.itemLore().stream().map(this.config::mm).toList();
                return Item.builder().setItemProvider(ItemBridge.appendLore(icon, lore)).addClickHandler(click -> this.browser.handleClick(click.player(), () -> {
                    Player viewer = click.player();
                    boolean usage = click.clickType() == ClickType.RIGHT || click.clickType() == ClickType.SHIFT_RIGHT;
                    boolean opened = usage
                            ? this.data.isReady() && this.recipes.generation() == generation
                                && this.browser.visibleItemIds(viewer).contains(output.result())
                                && this.recipeGui.open(viewer, output.result(), true, 0)
                            : this.recipeGui.openWorkstation(viewer, output, 0, generation);
                    Sounds.play(viewer, opened ? this.config.sounds().click() : this.config.sounds().queryFailure());
                })).build();
            }));
            if (content.isEmpty()) {
                content = List.of(Item.simple(ItemBridge.button(this.config.empty().material(),
                        this.config.mm(this.config.empty().name()),
                        this.config.empty().lore().stream().map(this.config::mm).toList())));
            }
            GuiSession session = GuiSession.of(player);
            session.workstation(type, page, keyword);
            String[] structure = BrowserLayout.workstations(this.config);
            List<Integer> categorySlots = BrowserLayout.slotsOf(structure, this.config.categoryIconChar());
            PagedGui.Builder<Item> builder = this.browser.contentBuilder(content, structure,
                    this.backItem(), this.browser.searchItem(MenuView.WORKSTATIONS,
                            viewer -> this.plugin.searchDialog().open(viewer,
                            query -> this.browser.openItemList(viewer, null, query, 0, query),
                            () -> this.reopen(viewer))), MenuView.WORKSTATIONS);
            builder.addIngredient(this.config.categoryIconChar(), Item.EMPTY);
            this.browser.bind(builder, this.config, this.config.prevCategory(),
                    this.categoryNavItem(false, type, categorySlots));
            this.browser.bind(builder, this.config, this.config.nextCategory(),
                    this.categoryNavItem(true, type, categorySlots));
            builder.addPageChangeHandler((previous, current) -> session.workstationPage(current));
            builder.addModifier(gui -> this.fillCategoryBar(gui, player, type, categorySlots));
            PagedGui<Item> gui = builder.build();
            gui.setPage(Math.max(0, Math.min(page, gui.getPageCount() - 1)));
            this.browser.openWindow(Window.builder().setViewer(player)
                    .setTitle(this.config.mm(this.config.workstationTitle(), "category", this.name(type, player)))
                    .setUpperGui(gui)
                    .addOutsideClickHandler(click -> {
                        click.setCancelled(true);
                        RecipeNavigation navigation = RecipeNavigation.from(click.getClickType());
                        if (navigation == null) return;
                        Player viewer = click.getPlayer();
                        this.browser.handleClick(viewer, () -> {
                            Sounds.play(viewer, this.config.sounds().back());
                            if (navigation == RecipeNavigation.HOME) {
                                GuiSession.of(viewer).leaveWorkstations();
                                this.browser.openDefault(viewer);
                            } else {
                                this.browser.returnToCategories(viewer);
                            }
                        });
                    })
                    .build());
        }
    }

    private void fillCategoryBar(Gui gui, Player player, String type, List<Integer> slots) {
        List<WorkstationBarEntry> entries = this.barEntries(player);
        GuiSession session = GuiSession.of(player);
        int offset = BrowserLayout.categoryOffset(session.workstationCategoryOffset(), entries.size(), slots.size());
        session.workstationCategoryOffset(offset);
        for (int index = 0; index < slots.size(); index++) {
            int entryIndex = offset + index;
            if (entryIndex >= entries.size()) {
                gui.setSlotElement(slots.get(index), null);
                continue;
            }
            WorkstationBarEntry entry = entries.get(entryIndex);
            if (entry instanceof StationBarEntry station) {
                gui.setSlotElement(slots.get(index), new SlotElement.Item(
                        this.stationItem(station.station(), type, player)));
            } else if (entry instanceof ButtonBarEntry button) {
                if (!this.data.namespaces().canViewButton(player, button.button())) {
                    gui.setSlotElement(slots.get(index), null);
                    continue;
                }
                gui.setSlotElement(slots.get(index), new SlotElement.Item(
                        this.browser.buttonEntryItem(player, button.button())));
            }
        }
    }

    private List<WorkstationBarEntry> barEntries(Player player) {
        Map<String, RecipeWorkstation> stations = new LinkedHashMap<>();
        for (RecipeWorkstation station : this.recipes.workstations(player)) {
            stations.put(station.id(), station);
        }
        Set<String> consumed = new java.util.LinkedHashSet<>();
        List<WorkstationBarEntry> entries = new java.util.ArrayList<>();
        for (WorkstationConfig.Entry entry : this.plugin.workstationConfig().entries()) {
            if (entry instanceof WorkstationConfig.StationEntry station) {
                RecipeWorkstation definition = stations.get(station.id());
                if (definition == null || !consumed.add(station.id())) continue;
                entries.add(new StationBarEntry(definition));
            } else if (entry instanceof WorkstationConfig.ButtonEntry button) {
                CategoryNamespaces.ButtonEntry definition = button.button();
                if (definition.hideInaccessible()
                        && !this.data.namespaces().canViewButton(player, definition)) continue;
                entries.add(new ButtonBarEntry(definition));
            }
        }
        stations.forEach((id, station) -> {
            if (consumed.add(id)) entries.add(new StationBarEntry(station));
        });
        return List.copyOf(entries);
    }

    private Item stationItem(RecipeWorkstation station, String active, Player player) {
        List<Component> lore = this.plugin.lang().list("ui.workstations.category-lore")
                .stream().map(this.config::mm).toList();
        ItemStack icon = ItemBridge.build(station.icon(), player, 1);
        String name = station.name() == null ? station.id() : station.name();
        // 合成器卡片统一使用白色标题，即使来源配方标题自带深色 GUI 颜色，
        // 例如 Farmer's Delight 的特殊配方页面。
        Component whiteName = whiteStationName(this.config.mm(name));
        icon = ItemBridge.decorate(icon, whiteName, lore);
        var meta = icon.getItemMeta();
        if (meta != null) {
            meta.setEnchantmentGlintOverride(station.id().equals(active) ? Boolean.TRUE : null);
            icon.setItemMeta(meta);
        }
        return Item.builder().setItemProvider(icon).addClickHandler(click -> this.browser.handleClick(click.player(), () -> {
            Sounds.play(click.player(), this.config.sounds().click());
            this.open(click.player(), station.id(), 0, null);
        })).build();
    }

    static Component whiteStationName(Component source) {
        String visibleName = PlainTextComponentSerializer.plainText().serialize(source);
        return CerConfig.noItalic(Component.text(visibleName, NamedTextColor.WHITE));
    }

    private Item categoryNavItem(boolean next, String active, List<Integer> slots) {
        CerConfig.ButtonDef def = next ? this.config.nextCategory() : this.config.prevCategory();
        ItemStack icon = ItemBridge.icon(def.itemModel(), def.material(), this.config.mm(def.name()),
                def.lore().stream().map(this.config::mm).toList());
        return BoundItem.pagedBuilder().setItemProvider(icon).addClickHandler((item, gui, click) -> this.browser.handleClick(click.player(), () -> {
            GuiSession session = GuiSession.of(click.player());
            List<WorkstationBarEntry> entries = this.barEntries(click.player());
            int current = session.workstationCategoryOffset();
            int target = BrowserLayout.categoryOffset(current + (next ? 1 : -1), entries.size(), slots.size());
            if (target == current) {
                Sounds.play(click.player(), this.config.sounds().queryFailure());
                return;
            }
            session.workstationCategoryOffset(target);
            this.fillCategoryBar(gui, click.player(), active, slots);
            Sounds.play(click.player(), this.config.sounds().changeCategory());
        })).build();
    }

    private Item backItem() {
        CerConfig.ButtonDef def = this.config.back();
        ItemStack icon = ItemBridge.icon(def.itemModel(), def.material(),
                this.config.mm(this.plugin.lang().text("ui.workstations.back-name")),
                this.plugin.lang().list("ui.workstations.back-lore").stream().map(this.config::mm).toList());
        if (this.config.disableBackButton()) return Item.simple(icon);
        return Item.builder().setItemProvider(icon).addClickHandler(click -> this.browser.handleClick(click.player(), () -> {
            Sounds.play(click.player(), this.config.sounds().back());
            this.browser.returnToCategories(click.player());
        })).build();
    }

    private String name(String id, Player player) {
        return this.recipes.workstations(player).stream().filter(station -> station.id().equals(id))
                .map(station -> station.name() == null ? station.id() : station.name()).findFirst().orElse(id);
    }

    private sealed interface WorkstationBarEntry permits StationBarEntry, ButtonBarEntry {
    }

    private record StationBarEntry(RecipeWorkstation station) implements WorkstationBarEntry {
    }

    private record ButtonBarEntry(CategoryNamespaces.ButtonEntry button) implements WorkstationBarEntry {
    }
}
