package dev.leon.cer.gui;

import dev.leon.cer.CraftEngineRecipePlugin;
import dev.leon.cer.config.CerConfig;
import dev.leon.cer.data.CraftEngineData;
import dev.leon.cer.data.RecipeIndex;
import dev.leon.cer.data.WorkstationRecipes;
import dev.leon.cer.util.ItemBridge;
import net.kyori.adventure.text.Component;
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

/** A second view over the same CE snapshot and per-player category visibility rules. */
final class WorkstationBrowserService {
    private static final List<Station> STATIONS = List.of(
            new Station(RecipeType.CRAFTING, Material.CRAFTING_TABLE),
            new Station(RecipeType.SMELTING, Material.FURNACE),
            new Station(RecipeType.BLASTING, Material.BLAST_FURNACE),
            new Station(RecipeType.SMOKING, Material.SMOKER),
            new Station(RecipeType.CAMPFIRE_COOKING, Material.CAMPFIRE),
            new Station(RecipeType.STONECUTTING, Material.STONECUTTER),
            new Station(RecipeType.SMITHING, Material.SMITHING_TABLE),
            new Station(RecipeType.BREWING, Material.BREWING_STAND));

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
        this.open(player, RecipeType.CRAFTING, 0, null);
    }

    void reopen(Player player) {
        GuiSession session = GuiSession.of(player);
        this.open(player, session.workstation() == null ? RecipeType.CRAFTING : session.workstation(),
                session.workstationPage(), session.workstationKeyword());
    }

    private void open(Player player, RecipeType type, int page, String keyword) {
        if (!this.browser.claim(player)) return;
        long generation = this.data.generation();
        Set<Key> scope = this.browser.visibleItemIds(player);
        List<WorkstationRecipes.Output> outputs = WorkstationRecipes.outputs(this.recipes.recipesByType(type), scope);
        if (keyword != null && !keyword.isBlank()) {
            // Search only this workstation's visible outputs, so other types cannot consume the limit.
            Map<Key, WorkstationRecipes.Output> byResult = new LinkedHashMap<>();
            outputs.forEach(output -> byResult.put(output.result(), output));
            outputs = this.data.searchIndex().search(keyword, this.config.searchMaxResults(), byResult::containsKey)
                    .stream().map(byResult::get).toList();
        }
        List<Item> content = new LazyItems<>(outputs, output -> BrowserService.lazyItem(() -> {
            ItemStack icon = ItemBridge.build(output.result(), player);
            List<Component> lore = this.config.itemLore().stream().map(this.config::mm).toList();
            return Item.builder().setItemProvider(ItemBridge.decorate(icon, null, lore)).addClickHandler(click -> {
                Player viewer = click.player();
                boolean usage = click.clickType() == ClickType.RIGHT || click.clickType() == ClickType.SHIFT_RIGHT;
                boolean opened = usage
                        ? this.data.isReady() && this.data.generation() == generation
                            && this.browser.visibleItemIds(viewer).contains(output.result())
                            && this.recipeGui.open(viewer, output.result(), true, 0)
                        : this.recipeGui.openWorkstation(viewer, output, 0, generation);
                Sounds.play(viewer, opened ? this.config.sounds().click() : this.config.sounds().queryFailure());
            }).build();
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
                this.backItem(), this.browser.searchItem(viewer -> this.plugin.searchDialog().open(viewer,
                        query -> this.open(viewer, type, 0, query), () -> this.reopen(viewer))));
        builder.addIngredient(this.config.categoryIconChar(), Item.EMPTY);
        this.browser.bind(builder, this.config.prevCategory(), this.categoryNavItem(false, type, categorySlots));
        this.browser.bind(builder, this.config.nextCategory(), this.categoryNavItem(true, type, categorySlots));
        builder.addPageChangeHandler((previous, current) -> session.workstationPage(current));
        builder.addModifier(gui -> this.fillCategoryBar(gui, player, type, categorySlots));
        PagedGui<Item> gui = builder.build();
        gui.setPage(Math.max(0, Math.min(page, gui.getPageCount() - 1)));
        this.browser.openWindow(Window.builder().setViewer(player)
                .setTitle(this.config.mm(this.plugin.lang().text("ui.workstations.title"), "category", this.name(type)))
                .setUpperGui(gui).build());
    }

    private void fillCategoryBar(Gui gui, Player player, RecipeType type, List<Integer> slots) {
        GuiSession session = GuiSession.of(player);
        int offset = BrowserLayout.categoryOffset(session.workstationCategoryOffset(), STATIONS.size(), slots.size());
        session.workstationCategoryOffset(offset);
        for (int index = 0; index < slots.size(); index++) {
            int stationIndex = offset + index;
            gui.setSlotElement(slots.get(index), stationIndex < STATIONS.size()
                    ? new SlotElement.Item(this.stationItem(STATIONS.get(stationIndex), type)) : null);
        }
    }

    private Item stationItem(Station station, RecipeType active) {
        List<Component> lore = this.plugin.lang().list("ui.workstations.category-lore")
                .stream().map(this.config::mm).toList();
        ItemStack icon = new ItemBuilder(station.material()).setName(this.config.mm(this.name(station.type())))
                .setLore(lore).setGlint(station.type() == active ? Boolean.TRUE : null).build();
        return Item.builder().setItemProvider(icon).addClickHandler(click -> {
            Sounds.play(click.player(), this.config.sounds().click());
            this.open(click.player(), station.type(), 0, null);
        }).build();
    }

    private Item categoryNavItem(boolean next, RecipeType active, List<Integer> slots) {
        CerConfig.ButtonDef def = next ? this.config.nextCategory() : this.config.prevCategory();
        ItemStack icon = ItemBridge.icon(def.itemModel(), def.material(), this.config.mm(def.name()),
                def.lore().stream().map(this.config::mm).toList());
        return BoundItem.pagedBuilder().setItemProvider(icon).addClickHandler((item, gui, click) -> {
            GuiSession session = GuiSession.of(click.player());
            int current = session.workstationCategoryOffset();
            int target = BrowserLayout.categoryOffset(current + (next ? 1 : -1), STATIONS.size(), slots.size());
            if (target == current) {
                Sounds.play(click.player(), this.config.sounds().queryFailure());
                return;
            }
            session.workstationCategoryOffset(target);
            this.fillCategoryBar(gui, click.player(), active, slots);
            Sounds.play(click.player(), this.config.sounds().changeCategory());
        }).build();
    }

    private Item backItem() {
        CerConfig.ButtonDef def = this.config.back();
        ItemStack icon = ItemBridge.icon(def.itemModel(), def.material(),
                this.config.mm(this.plugin.lang().text("ui.workstations.back-name")),
                this.plugin.lang().list("ui.workstations.back-lore").stream().map(this.config::mm).toList());
        return Item.builder().setItemProvider(icon).addClickHandler(click -> {
            Sounds.play(click.player(), this.config.sounds().back());
            this.browser.returnToCategories(click.player());
        }).build();
    }

    private String name(RecipeType type) {
        return this.plugin.lang().text("ui.workstations.categories." + type.name().toLowerCase(Locale.ROOT));
    }

    private record Station(RecipeType type, Material material) {}
}
