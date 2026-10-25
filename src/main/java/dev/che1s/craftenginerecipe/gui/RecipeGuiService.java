package dev.che1s.craftenginerecipe.gui;

import dev.che1s.craftenginerecipe.CraftEngineRecipePlugin;
import dev.che1s.craftenginerecipe.api.DisplayRecipe;
import dev.che1s.craftenginerecipe.api.RecipeIngredient;
import dev.che1s.craftenginerecipe.api.RecipeItem;
import dev.che1s.craftenginerecipe.api.RecipeResult;
import dev.che1s.craftenginerecipe.config.CerConfig;
import dev.che1s.craftenginerecipe.data.CraftEngineData;
import dev.che1s.craftenginerecipe.recipe.ExternalRecipeSnapshot;
import dev.che1s.craftenginerecipe.recipe.RecipeIndex;
import dev.che1s.craftenginerecipe.recipe.WorkstationRecipes;
import dev.che1s.craftenginerecipe.recipe.RecipeEntry;
import dev.che1s.craftenginerecipe.recipe.access.RecipeAccessPolicy;
import dev.che1s.craftenginerecipe.debug.Diagnostics;
import dev.che1s.craftenginerecipe.util.ItemBridge;
import dev.che1s.craftenginerecipe.util.CompatibilityWarnings;
import net.kyori.adventure.text.Component;
import net.momirealms.craftengine.core.item.recipe.CustomBrewingRecipe;
import net.momirealms.craftengine.core.item.recipe.CustomCookingRecipe;
import net.momirealms.craftengine.core.item.recipe.CustomShapedRecipe;
import net.momirealms.craftengine.core.item.recipe.CustomShapelessRecipe;
import net.momirealms.craftengine.core.item.recipe.CustomSmithingTransformRecipe;
import net.momirealms.craftengine.core.item.recipe.CustomSmithingTrimRecipe;
import net.momirealms.craftengine.core.item.recipe.CustomStoneCuttingRecipe;
import net.momirealms.craftengine.core.item.recipe.FixedResultRecipe;
import net.momirealms.craftengine.core.item.recipe.Ingredient;
import net.momirealms.craftengine.core.item.recipe.Recipe;
import net.momirealms.craftengine.core.item.recipe.result.CustomRecipeResult;
import net.momirealms.craftengine.core.util.Key;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.event.inventory.ClickType;
import org.bukkit.inventory.ItemStack;
import io.papermc.paper.threadedregions.scheduler.ScheduledTask;
import xyz.xenondevs.invui.ClickEvent;
import xyz.xenondevs.invui.gui.Gui;
import xyz.xenondevs.invui.gui.SlotElement;
import xyz.xenondevs.invui.item.Item;
import xyz.xenondevs.invui.window.BrewingWindow;
import xyz.xenondevs.invui.window.CraftingWindow;
import xyz.xenondevs.invui.window.FurnaceWindow;
import xyz.xenondevs.invui.window.SmithingWindow;
import xyz.xenondevs.invui.window.StonecutterWindow;
import xyz.xenondevs.invui.window.Window;
import xyz.xenondevs.invui.window.WindowManager;

import java.util.ArrayList;
import java.util.concurrent.ConcurrentHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.IntConsumer;

public final class RecipeGuiService {

    private final CraftEngineRecipePlugin plugin;
    private final CerConfig config;
    private final CraftEngineData data;
    private final RecipeIndex recipeIndex;
    private final BrowserService browser;
    private final ChestRecipeRenderer chestRenderer;

    private final Map<UUID, ScheduledTask> rotateTasks = new ConcurrentHashMap<>();

    public RecipeGuiService(CraftEngineRecipePlugin plugin, CerConfig config, CraftEngineData data, RecipeIndex recipeIndex, BrowserService browser) {
        this.plugin = plugin;
        this.config = config;
        this.data = data;
        this.recipeIndex = recipeIndex;
        this.browser = browser;
        this.chestRenderer = new ChestRecipeRenderer(plugin, browser, config);
    }

    public boolean open(Player player, Key itemId, boolean usageMode, int index) {
        return this.open(player, itemId, usageMode, index, this.browser.visibility(player));
    }

    /** 仅在当前同步动作内共享，绝不跨后续点击复用。 */
    boolean open(Player player, Key itemId, boolean usageMode, int index, RecipeAccessPolicy visibility) {
        try (var timing = this.plugin.diagnostics().measure(Diagnostics.Operation.RECIPE_OPEN)) {
            if (!this.browser.claim(player)) return false;
            if (!visibility.canQuery(itemId)) return false;
            List<RecipeEntry> recipes = this.recipeIndex.entries(itemId, usageMode, visibility);
            if (recipes.isEmpty()) {
                return false;
            }
            return this.showRecipe(player, itemId, usageMode, index, recipes,
                    next -> this.open(player, itemId, usageMode, next));
        }
    }

    /** 合成器选择只轮播该可见产物在所选类型中的配方。 */
    public boolean openWorkstation(Player player, RecipeEntry.Output output, int index, long generation) {
        try (var timing = this.plugin.diagnostics().measure(Diagnostics.Operation.RECIPE_OPEN)) {
            if (!this.browser.claim(player) || this.recipeIndex.generation() != generation || output.recipes().isEmpty()) return false;
            var visibility = this.browser.visibility(player);
            if (!visibility.canQuery(output.result())) return false;
            List<RecipeEntry> allowed = output.recipes().stream().filter(visibility::canView).toList();
            if (allowed.isEmpty()) return false;
            boolean opened = this.showRecipe(player, output.result(), false, index, allowed,
                    next -> {
                        if (!this.openWorkstation(player, output, next, generation)) this.browser.returnToBrowser(player);
                    });
            if (opened) GuiSession.of(player).entrySelection(output, generation);
            return opened;
        }
    }

    private boolean showRecipe(Player player, Key itemId, boolean usageMode, int index, List<RecipeEntry> recipes,
                               IntConsumer openIndex) {
        this.cancelRotation(player);
        int safeIndex = Math.max(0, Math.min(index, recipes.size() - 1));
        RecipeEntry entry = recipes.get(safeIndex);
        GuiSession.of(player).recipeContext(itemId, safeIndex, usageMode);
        if (entry.nativeRecipe() != null) {
            this.openRecipe(player, entry.nativeRecipe(), List.of(entry.nativeRecipe()), safeIndex, usageMode);
        } else if (entry.display().layout().page() != null) {
            this.openCerRecipePage(player, entry.display(), entry.display().layout().page());
        } else if (isBundledMinecraft(entry.display())) {
            this.openBundledMinecraft(player, entry.display());
        } else {
            // 来源配方会被读入 CER 的不可变快照，并由 CER 渲染。
            this.chestRenderer.open(player, entry, safeIndex, recipes.size(), openIndex, this::clickable);
        }
        this.scheduleRotation(player, safeIndex, recipes, openIndex);
        return true;
    }

    private static boolean isBundledMinecraft(DisplayRecipe recipe) {
        return recipe != null && recipe.id().startsWith("minecraft_data:");
    }

    private void openBundledMinecraft(Player player, DisplayRecipe recipe) {
        String station = recipe.workstation().id();
        switch (station) {
            case "minecraft:crafting" -> this.openBundledCrafting(player, recipe);
            case "minecraft:smelting", "minecraft:blasting", "minecraft:smoking" ->
                    this.openBundledFurnace(player, recipe, station.substring("minecraft:".length()).toUpperCase());
            case "minecraft:campfire_cooking" -> this.openBundledCampfire(player, recipe);
            case "minecraft:stonecutting" -> this.openBundledStonecutting(player, recipe);
            case "minecraft:smithing" -> this.openBundledSmithing(player, recipe);
            default -> this.openNoRecipe(player,
                    ExternalRecipeSnapshot.key(recipe.results().getFirst().item()), false);
        }
    }

    private void openCerRecipePage(Player player, DisplayRecipe recipe, String page) {
        switch (page) {
            case "crafting" -> this.openBundledCrafting(player, recipe);
            case "smelting" -> this.openBundledFurnace(player, recipe, "SMELTING");
            case "blasting" -> this.openBundledFurnace(player, recipe, "BLASTING");
            case "smoking" -> this.openBundledFurnace(player, recipe, "SMOKING");
            case "campfire" -> this.openBundledCampfire(player, recipe);
            case "stonecutting" -> this.openBundledStonecutting(player, recipe);
            case "smithing" -> this.openBundledSmithing(player, recipe);
            case "brewing" -> this.openBundledBrewing(player, recipe);
            default -> this.openNoRecipe(player,
                    recipe.results().isEmpty() ? null
                            : ExternalRecipeSnapshot.key(recipe.results().getFirst().item()), false);
        }
    }

    private void openBundledCrafting(Player player, DisplayRecipe recipe) {
        Gui grid = Gui.builder().setStructure("###", "###", "###")
                .addIngredient('#', Item.EMPTY).build();
        for (RecipeIngredient ingredient : recipe.ingredients()) {
            int slot = craftingGridSlot(ingredient.slot());
            if (slot < 0) continue;
            grid.setSlotElement(slot, new SlotElement.Item(this.clickable(firstKey(ingredient),
                    this.displayIngredientItem(player, ingredient))));
        }
        RecipeResult result = recipe.results().getFirst();
        Key resultId = ExternalRecipeSnapshot.key(result.item());
        Gui resultGui = Gui.builder().setStructure("#")
                .addIngredient('#', this.clickable(resultId,
                        ItemBridge.build(result.item(), player, result.amount()))).build();
        this.browser.openWindow(CraftingWindow.builder().setViewer(player)
                .setTitle(this.config.mm(this.config.recipeTitleFor("CRAFTING")))
                .setCraftingGui(grid)
                .setResultGui(resultGui)
                .addOutsideClickHandler(this::onOutsideClick)
                .addCloseHandler(reason -> this.cancelRotation(player))
                .build());
    }

    private void openBundledFurnace(Player player, DisplayRecipe recipe, String type) {
        RecipeIngredient ingredient = recipe.ingredients().getFirst();
        Gui input = Gui.builder().setStructure("#", "f")
                .addIngredient('#', this.clickable(firstKey(ingredient),
                        this.displayIngredientItem(player, ingredient)))
                .addIngredient('f', Item.simple(this.fuelIcon(player))).build();
        RecipeResult result = recipe.results().getFirst();
        Key resultId = ExternalRecipeSnapshot.key(result.item());
        Gui resultGui = Gui.builder().setStructure("r")
                .addIngredient('r', this.clickable(resultId,
                        ItemBridge.build(result.item(), player, result.amount()))).build();
        this.browser.openWindow(FurnaceWindow.builder().setViewer(player)
                .setTitle(this.config.mm(this.config.recipeTitleFor(type)))
                .setInputGui(input).setResultGui(resultGui)
                .setCookProgress(0.5).setBurnProgress(1.0)
                .addOutsideClickHandler(this::onOutsideClick)
                .addCloseHandler(reason -> this.cancelRotation(player)).build());
    }

    private void openBundledCampfire(Player player, DisplayRecipe recipe) {
        RecipeIngredient ingredient = recipe.ingredients().getFirst();
        Gui input = Gui.builder().setStructure("#", "f")
                .addIngredient('#', this.clickable(firstKey(ingredient),
                        this.displayIngredientItem(player, ingredient)))
                .addIngredient('f', Item.EMPTY).build();
        RecipeResult result = recipe.results().getFirst();
        Key resultId = ExternalRecipeSnapshot.key(result.item());
        Gui resultGui = Gui.builder().setStructure("r")
                .addIngredient('r', this.clickable(resultId,
                        ItemBridge.build(result.item(), player, result.amount()))).build();
        this.browser.openWindow(FurnaceWindow.builder().setViewer(player)
                .setTitle(this.config.mm(this.config.recipeTitleFor("CAMPFIRE_COOKING")))
                .setInputGui(input).setResultGui(resultGui)
                .setCookProgress(0.6).setBurnProgress(1.0)
                .addOutsideClickHandler(this::onOutsideClick)
                .addCloseHandler(reason -> this.cancelRotation(player)).build());
    }

    private void openBundledStonecutting(Player player, DisplayRecipe recipe) {
        RecipeIngredient ingredient = recipe.ingredients().getFirst();
        Gui upper = Gui.builder().setStructure("i#")
                .addIngredient('i', this.clickable(firstKey(ingredient),
                        this.displayIngredientItem(player, ingredient)))
                .addIngredient('#', Item.EMPTY).build();
        RecipeResult result = recipe.results().getFirst();
        Key resultId = ExternalRecipeSnapshot.key(result.item());
        Gui buttons = Gui.builder().setStructure("r###")
                .addIngredient('r', this.clickable(resultId,
                        ItemBridge.build(result.item(), player, result.amount())))
                .addIngredient('#', Item.EMPTY).build();
        this.browser.openWindow(StonecutterWindow.builder().setViewer(player)
                .setTitle(this.config.mm(this.config.recipeTitleFor("STONECUTTING")))
                .setUpperGui(upper).setButtonsGui(buttons)
                .addOutsideClickHandler(this::onOutsideClick)
                .addCloseHandler(reason -> this.cancelRotation(player)).build());
    }

    private void openBundledSmithing(Player player, DisplayRecipe recipe) {
        List<RecipeIngredient> ingredients = recipe.ingredients();
        if (ingredients.size() < 3) {
            this.openNoRecipe(player, ExternalRecipeSnapshot.key(recipe.results().getFirst().item()), false);
            return;
        }
        RecipeResult result = recipe.results().getFirst();
        Key resultId = ExternalRecipeSnapshot.key(result.item());
        Gui upper = Gui.builder().setStructure("tbar")
                .addIngredient('t', this.clickable(firstKey(ingredients.get(0)),
                        this.displayIngredientItem(player, ingredients.get(0))))
                .addIngredient('b', this.clickable(firstKey(ingredients.get(1)),
                        this.displayIngredientItem(player, ingredients.get(1))))
                .addIngredient('a', this.clickable(firstKey(ingredients.get(2)),
                        this.displayIngredientItem(player, ingredients.get(2))))
                .addIngredient('r', this.clickable(resultId,
                        ItemBridge.build(result.item(), player, result.amount()))).build();
        this.browser.openWindow(SmithingWindow.builder().setViewer(player)
                .setTitle(this.config.mm(this.config.recipeTitleFor("SMITHING")))
                .setUpperGui(upper)
                .addOutsideClickHandler(this::onOutsideClick)
                .addCloseHandler(reason -> this.cancelRotation(player)).build());
    }

    private void openBundledBrewing(Player player, DisplayRecipe recipe) {
        if (recipe.ingredients().isEmpty() || recipe.results().isEmpty()) {
            this.openNoRecipe(player, null, false);
            return;
        }
        RecipeIngredient ingredient = recipe.ingredients().getFirst();
        Gui input = Gui.builder().setStructure("i")
                .addIngredient('i', this.clickable(firstKey(ingredient),
                        this.displayIngredientItem(player, ingredient))).build();
        Gui fuel = Gui.builder().setStructure("f")
                .addIngredient('f', Item.simple(new ItemStack(Material.BLAZE_POWDER))).build();
        RecipeResult result = recipe.results().getFirst();
        Key resultId = ExternalRecipeSnapshot.key(result.item());
        Gui resultGui = Gui.builder().setStructure("cde")
                .addIngredient('c', Item.EMPTY)
                .addIngredient('d', Item.EMPTY)
                .addIngredient('e', this.clickable(resultId,
                        ItemBridge.build(result.item(), player, result.amount()))).build();
        this.browser.openWindow(BrewingWindow.builder().setViewer(player)
                .setTitle(this.config.mm(this.config.recipeTitleFor("BREWING")))
                .setInputGui(input).setFuelGui(fuel).setResultGui(resultGui)
                .setBrewProgress(0.5).setFuelProgress(1.0)
                .addOutsideClickHandler(this::onOutsideClick)
                .addCloseHandler(reason -> this.cancelRotation(player)).build());
    }

    private ItemStack displayIngredientItem(Player player, RecipeIngredient ingredient) {
        if (ingredient.choices().isEmpty()) return ItemBridge.emptySlot();
        RecipeItem choice = ingredient.choices().getFirst();
        return ItemBridge.build(choice, player, ingredient.amount());
    }

    private static Key firstKey(RecipeIngredient ingredient) {
        return ingredient.choices().isEmpty() ? null
                : ExternalRecipeSnapshot.key(ingredient.choices().getFirst());
    }

    private static int craftingGridSlot(int recipeSlot) {
        int row = recipeSlot / 9;
        int column = recipeSlot % 9;
        if (row < 1 || row > 3 || column < 1 || column > 3) return -1;
        return (row - 1) * 3 + (column - 1);
    }

    private void scheduleRotation(Player player, int index, List<RecipeEntry> recipes, IntConsumer openIndex) {
        if (recipes.size() <= 1) {
            return;
        }
        Window window = WindowManager.getInstance().getOpenWindow(player);
        if (window == null) {
            return;
        }
        int next = (index + 1) % recipes.size();
        long interval = this.config.recipeRotateInterval();
        ScheduledTask task = this.plugin.tasks().playerLater(player, () -> {
            if (WindowManager.getInstance().getOpenWindow(player) != window) {
                this.cancelRotation(player);
                return;
            }
            openIndex.accept(next);
        }, interval);
        if (task != null) this.rotateTasks.put(player.getUniqueId(), task);
    }

    public void cancelRotation(Player player) {
        // 替代窗口打开期间会执行原生关闭处理器。若在此取消箱子视图，
        // 会删除刚打开的来源页面并破坏其外部点击处理器；
        // 箱子窗口应使用自己的关闭处理器完成清理。
        ScheduledTask task = this.rotateTasks.remove(player.getUniqueId());
        if (task != null) {
            task.cancel();
        }
    }

    public void shutdown() {
        this.chestRenderer.shutdown();
        this.rotateTasks.values().forEach(ScheduledTask::cancel);
        this.rotateTasks.clear();
    }

    private Item ingredientItem(Player player, Ingredient ing, int count) {
        return this.clickable(ItemBridge.firstIngredientKey(ing).orElse(null), ItemBridge.buildIngredient(ing, player, count));
    }

    public boolean hasContent(Key itemId, boolean usageMode) {
        if (!this.data.isReady()) return false;
        var snapshot = this.plugin.externalRecipes().snapshot();
        return !(usageMode ? snapshot.usages() : snapshot.results()).getOrDefault(itemId, List.of()).isEmpty();
    }

    public boolean hasContent(Player player, Key itemId, boolean usageMode) {
        return this.hasContent(itemId, usageMode, this.browser.visibility(player));
    }

    boolean hasContent(Key itemId, boolean usageMode, RecipeAccessPolicy visibility) {
        if (!this.data.isReady()) return false;
        return visibility.canQuery(itemId) && !this.recipeIndex.entries(itemId, usageMode, visibility).isEmpty();
    }

    private Item clickable(Key target, ItemStack icon) {
        List<Component> lore = new ArrayList<>();
        for (String line : this.config.itemLore()) {
            lore.add(this.config.mm(line));
        }
        ItemStack decorated = ItemBridge.appendLore(icon, lore);
        if (target == null) {
            return Item.simple(decorated);
        }
        return Item.builder().setItemProvider(decorated).addClickHandler(click -> this.browser.handleClick(click.player(), () -> {
            Player p = click.player();
            boolean usage = click.clickType() == ClickType.RIGHT || click.clickType() == ClickType.SHIFT_RIGHT;
            GuiSession session = GuiSession.of(p);
            GuiSession.RecipePage previous = session.recipePage();
            RecipeEntry.Output selection = session.entrySelection();
            if (!usage && selection != null && target.equals(selection.result())) {
                if (this.openWorkstation(p, selection, 0, session.recipeGeneration())) {
                    session.pushRecipePage(previous);
                    Sounds.play(p, this.config.sounds().click());
                }
                return;
            }
            if (this.open(p, target, usage, 0)) {
                session.pushRecipePage(previous);
                Sounds.play(p, this.config.sounds().click());
            }
        })).build();
    }

    public void openNoRecipe(Player player, Key itemId, boolean usageMode) {
        ItemStack icon = itemId == null ? new ItemStack(Material.BARRIER) : ItemBridge.build(itemId, player);
        Component name = this.config.mm(this.config.recipeTitleFor("NONE"));
        ItemStack decorated = ItemBridge.decorate(icon, name, List.of(this.plugin.lang().component(usageMode ? "ui.common.no-usage" : "ui.common.no-recipe")));
        Gui gui = Gui.builder()
                .setStructure("#########", "#########", "####x####", "#########", "#########", "#########")
                .addIngredient('#', Item.EMPTY)
                .addIngredient('x', Item.simple(decorated))
                .build();
        this.browser.openWindow(Window.builder().setViewer(player)
                .setTitle(name)
                .setUpperGui(gui)
                .addOutsideClickHandler(this::onOutsideClick)
                .addCloseHandler(reason -> this.cancelRotation(player))
                .build());
    }

    private void openRecipe(Player player, Recipe recipe, List<Recipe> recipes, int index, boolean usageMode) {
        RecipeIndex.RecipeUiKind kind = RecipeIndex.uiKindOf(recipe);
        switch (kind) {
            case CRAFTING -> this.openCrafting(player, recipe, recipes, index, usageMode);
            case SMELTING, BLASTING, SMOKING -> this.openFurnace(player, (CustomCookingRecipe) recipe, recipes, index, usageMode);
            case CAMPFIRE -> this.openCampfire(player, (CustomCookingRecipe) recipe, recipes, index, usageMode);
            case SMITHING -> this.openSmithing(player, recipe, recipes, index, usageMode);
            case BREWING -> this.openBrewing(player, (CustomBrewingRecipe) recipe, recipes, index, usageMode);
            case STONECUTTING -> this.openStonecutting(player, (CustomStoneCuttingRecipe) recipe, recipes, index, usageMode);
            default -> this.openNoRecipe(player, this.resultKey(recipe), usageMode);
        }
    }

    private void openCrafting(Player player, Recipe recipe, List<Recipe> recipes, int index, boolean usageMode) {
        Gui craftingGui = Gui.builder()
                .setStructure("###", "###", "###")
                .addIngredient('#', Item.EMPTY)
                .build();
        this.fillCraftingGrid(player, recipe, craftingGui);
        Key resultId = this.resultKey(recipe);
        ItemStack resultIcon = resultId != null ? ItemBridge.build(resultId, player, this.resultCount(recipe)) : ItemBridge.emptySlot();
        Gui resultGui = Gui.builder()
                .setStructure("#")
                .addIngredient('#', this.clickable(resultId, resultIcon))
                .build();
        Component title = this.config.mm(this.config.recipeTitleFor("CRAFTING"));
        this.browser.openWindow(CraftingWindow.builder().setViewer(player)
                .setTitle(title)
                .setCraftingGui(craftingGui)
                .setResultGui(resultGui)
                .addOutsideClickHandler(this::onOutsideClick)
                .addCloseHandler(reason -> this.cancelRotation(player))
                .build());
    }

    private void fillCraftingGrid(Player player, Recipe recipe, Gui grid) {
        if (recipe instanceof CustomShapedRecipe shaped) {
            String[] pattern = shaped.pattern().pattern();
            Map<Character, Ingredient> map = shaped.pattern().ingredients();
            int offsetY = (3 - pattern.length) / 2;
            for (int r = 0; r < pattern.length && r < 3; r++) {
                String row = pattern[r];
                int offsetX = (3 - row.length()) / 2;
                for (int c = 0; c < row.length() && c < 3; c++) {
                    char ch = row.charAt(c);
                    if (ch == ' ') continue;
                    Ingredient ing = map.get(ch);
                    if (ing == null) continue;
                    int slot = (r + offsetY) * 3 + (c + offsetX);
                    if (slot < 0 || slot >= 9) continue;
                    grid.setSlotElement(slot, new SlotElement.Item(this.ingredientItem(player, ing, ing.count())));
                }
            }
        } else if (recipe instanceof CustomShapelessRecipe) {
            List<Ingredient> ings = recipe.ingredientsInUse();
            int slot = 0;
            for (Ingredient ing : ings) {
                if (slot < 9) {
                    grid.setSlotElement(slot++, new SlotElement.Item(this.ingredientItem(player, ing, ing.count())));
                } else {
                    break;
                }
            }
        } else {
            List<Ingredient> ings = recipe.ingredientsInUse();
            int slot = 0;
            for (Ingredient ing : ings) {
                if (slot < 9) {
                    grid.setSlotElement(slot++, new SlotElement.Item(this.ingredientItem(player, ing, ing.count())));
                } else {
                    break;
                }
            }
        }
    }

    private void openFurnace(Player player, CustomCookingRecipe recipe, List<Recipe> recipes, int index, boolean usageMode) {
        Ingredient ing = recipe.ingredient();
        ItemStack input = ItemBridge.buildIngredient(ing, player, Math.max(1, ing == null ? 1 : ing.count()));
        Gui inputGui = Gui.builder()
                .setStructure("#", "f")
                .addIngredient('#', this.clickable(ItemBridge.firstIngredientKey(ing).orElse(null), input))
                .addIngredient('f', Item.simple(this.fuelIcon(player)))
                .build();
        Key resultId = this.resultKey(recipe);
        ItemStack resultIcon = resultId != null ? ItemBridge.build(resultId, player, this.resultCount(recipe)) : ItemBridge.emptySlot();
        Gui resultGui = Gui.builder()
                .setStructure("r")
                .addIngredient('r', this.clickable(resultId, resultIcon))
                .build();
        String typeKey = recipe.type().name();
        Component title = this.config.mm(this.config.recipeTitleFor(typeKey));
        this.browser.openWindow(FurnaceWindow.builder().setViewer(player)
                .setTitle(title)
                .setInputGui(inputGui)
                .setResultGui(resultGui)
                .setCookProgress(0.5)
                .setBurnProgress(1.0)
                .addOutsideClickHandler(this::onOutsideClick)
                .addCloseHandler(reason -> this.cancelRotation(player))
                .build());
    }

    private void openCampfire(Player player, CustomCookingRecipe recipe, List<Recipe> recipes, int index, boolean usageMode) {
        Ingredient ing = recipe.ingredient();
        ItemStack input = ItemBridge.buildIngredient(ing, player, Math.max(1, ing == null ? 1 : ing.count()));
        Gui inputGui = Gui.builder()
                .setStructure("#", "f")
                .addIngredient('#', this.clickable(ItemBridge.firstIngredientKey(ing).orElse(null), input))
                .addIngredient('f', Item.EMPTY)
                .build();
        Key resultId = this.resultKey(recipe);
        Gui resultGui = Gui.builder()
                .setStructure("r")
                .addIngredient('r', this.clickable(resultId, resultId != null ? ItemBridge.build(resultId, player, this.resultCount(recipe)) : ItemBridge.emptySlot()))
                .build();
        this.browser.openWindow(FurnaceWindow.builder().setViewer(player)
                .setTitle(this.config.mm(this.config.recipeTitleFor("CAMPFIRE_COOKING")))
                .setInputGui(inputGui)
                .setResultGui(resultGui)
                .setCookProgress(0.6)
                .setBurnProgress(1.0)
                .addOutsideClickHandler(this::onOutsideClick)
                .addCloseHandler(reason -> this.cancelRotation(player))
                .build());
    }

    private void openSmithing(Player player, Recipe recipe, List<Recipe> recipes, int index, boolean usageMode) {
        Ingredient template;
        Ingredient base;
        Ingredient addition;
        if (recipe instanceof CustomSmithingTransformRecipe transform) {
            template = transform.template();
            base = transform.base();
            addition = transform.addition();
        } else if (recipe instanceof CustomSmithingTrimRecipe trim) {
            template = trim.template();
            base = trim.base();
            addition = trim.addition();
        } else {
            this.openNoRecipe(player, this.resultKey(recipe), usageMode);
            return;
        }
        ItemStack templateIcon = ItemBridge.buildIngredient(template, player, 1);
        ItemStack baseIcon = ItemBridge.buildIngredient(base, player, 1);
        ItemStack additionIcon = ItemBridge.buildIngredient(addition, player, 1);
        Key result = this.resultKey(recipe);
        ItemStack smithResultIcon = result != null ? ItemBridge.build(result, player, this.resultCount(recipe))
                : ItemBridge.barrier(this.plugin.lang().component("ui.common.dynamic-result"));
        Gui upper = Gui.builder()
                .setStructure("tbar")
                .addIngredient('t', this.clickable(ItemBridge.firstIngredientKey(template).orElse(null), templateIcon))
                .addIngredient('b', this.clickable(ItemBridge.firstIngredientKey(base).orElse(null), baseIcon))
                .addIngredient('a', this.clickable(ItemBridge.firstIngredientKey(addition).orElse(null), additionIcon))
                .addIngredient('r', this.clickable(this.resultKey(recipe), smithResultIcon))
                .build();
        this.browser.openWindow(SmithingWindow.builder().setViewer(player)
                .setTitle(this.config.mm(this.config.recipeTitleFor("SMITHING")))
                .setUpperGui(upper)
                .addOutsideClickHandler(this::onOutsideClick)
                .addCloseHandler(reason -> this.cancelRotation(player))
                .build());
    }

    private void openBrewing(Player player, CustomBrewingRecipe recipe, List<Recipe> recipes, int index, boolean usageMode) {
        Gui inputGui = Gui.builder()
                .setStructure("i")
                .addIngredient('i', this.ingredientItem(player, recipe.ingredient(), 1))
                .build();
        Gui fuelGui = Gui.builder()
                .setStructure("f")
                .addIngredient('f', Item.simple(new ItemStack(Material.BLAZE_POWDER)))
                .build();
        ItemStack container = ItemBridge.buildIngredient(recipe.container(), player, 1);
        Key resultId = this.resultKey(recipe);
        Gui resultGui = Gui.builder()
                .setStructure("cde")
                .addIngredient('c', this.clickable(ItemBridge.firstIngredientKey(recipe.container()).orElse(null), container))
                .addIngredient('d', Item.EMPTY)
                .addIngredient('e', this.clickable(resultId, resultId == null ? ItemBridge.emptySlot()
                        : ItemBridge.build(resultId, player, this.resultCount(recipe))))
                .build();
        this.browser.openWindow(BrewingWindow.builder().setViewer(player)
                .setTitle(this.config.mm(this.config.recipeTitleFor("BREWING")))
                .setInputGui(inputGui)
                .setFuelGui(fuelGui)
                .setResultGui(resultGui)
                .setBrewProgress(0.5)
                .setFuelProgress(1.0)
                .addOutsideClickHandler(this::onOutsideClick)
                .addCloseHandler(reason -> this.cancelRotation(player))
                .build());
    }

    private void openStonecutting(Player player, CustomStoneCuttingRecipe recipe, List<Recipe> recipes, int index, boolean usageMode) {
        Ingredient ing = recipe.ingredient();
        Gui upperGui = Gui.builder()
                .setStructure("i#")
                .addIngredient('i', this.ingredientItem(player, ing, 1))
                .addIngredient('#', Item.EMPTY)
                .build();
        Key resultId = this.resultKey(recipe);
        ItemStack resultIcon = resultId != null ? ItemBridge.build(resultId, player, this.resultCount(recipe)) : ItemBridge.emptySlot();
        Gui buttonsGui = Gui.builder()
                .setStructure("r###")
                .addIngredient('r', this.clickable(resultId, resultIcon))
                .addIngredient('#', Item.EMPTY)
                .build();
        this.browser.openWindow(StonecutterWindow.builder().setViewer(player)
                .setTitle(this.config.mm(this.config.recipeTitleFor("STONECUTTING")))
                .setUpperGui(upperGui)
                .setButtonsGui(buttonsGui)
                .addOutsideClickHandler(this::onOutsideClick)
                .addCloseHandler(reason -> this.cancelRotation(player))
                .build());
    }

    private ItemStack fuelIcon(Player player) {
        return new ItemStack(Material.COAL);
    }

    void navigateBack(Player player, boolean home) {
        Sounds.play(player, this.config.sounds().back());
        this.cancelRotation(player);
        GuiSession session = GuiSession.of(player);
        if (home) {
            session.clearRecipeHistory();
            this.browser.returnToOriginHome(player);
            return;
        }
        GuiSession.RecipePage previous;
        while ((previous = session.popRecipePage()) != null) {
            boolean opened = previous.selection() == null
                    ? this.open(player, previous.item(), previous.usageMode(), previous.index())
                    : this.openWorkstation(player, previous.selection(), previous.index(), previous.generation());
            if (opened) return;
        }
        this.browser.returnToBrowser(player);
    }

    private void onOutsideClick(ClickEvent event) {
        event.setCancelled(true);
        Player player = event.getPlayer();
        RecipeNavigation navigation = RecipeNavigation.from(event.getClickType());
        if (navigation != null) this.browser.handleClick(player,
                () -> this.navigateBack(player, navigation == RecipeNavigation.HOME));
    }

    private Key resultKey(Recipe recipe) {
        if (recipe instanceof FixedResultRecipe fixed) {
            try {
                CustomRecipeResult r = fixed.result();
                if (r != null && r.item() != null) {
                    return r.item().id();
                }
            } catch (RuntimeException | LinkageError error) {
                CompatibilityWarnings.warn("RecipeGui.resultKey", error);
            }
        }
        return null;
    }

    private int resultCount(Recipe recipe) {
        if (recipe instanceof FixedResultRecipe fixed) {
            try {
                CustomRecipeResult r = fixed.result();
                if (r != null) {
                    return Math.max(1, r.count());
                }
            } catch (RuntimeException | LinkageError error) {
                CompatibilityWarnings.warn("RecipeGui.resultCount", error);
            }
        }
        return 1;
    }
}
