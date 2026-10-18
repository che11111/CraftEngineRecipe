package dev.leon.cer.gui;

import dev.leon.cer.CraftEngineRecipePlugin;
import dev.leon.cer.config.CerConfig;
import dev.leon.cer.data.CraftEngineData;
import dev.leon.cer.data.RecipeIndex;
import dev.leon.cer.util.ItemBridge;
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

public final class RecipeGuiService {

    private final CraftEngineRecipePlugin plugin;
    private final CerConfig config;
    private final CraftEngineData data;
    private final RecipeIndex recipeIndex;
    private final BrowserService browser;

    private final Map<UUID, ScheduledTask> rotateTasks = new ConcurrentHashMap<>();

    public RecipeGuiService(CraftEngineRecipePlugin plugin, CerConfig config, CraftEngineData data, RecipeIndex recipeIndex, BrowserService browser) {
        this.plugin = plugin;
        this.config = config;
        this.data = data;
        this.recipeIndex = recipeIndex;
        this.browser = browser;
    }

    public boolean open(Player player, Key itemId, boolean usageMode, int index) {
        if (!this.browser.claim(player)) return false;
        List<Recipe> recipes = usageMode ? this.recipeIndex.recipesUsingIngredient(itemId) : this.recipeIndex.recipesForResult(itemId);
        if (recipes.isEmpty()) {
            return false;
        }
        int safeIndex = Math.max(0, Math.min(index, recipes.size() - 1));
        Recipe recipe = recipes.get(safeIndex);
        GuiSession.of(player).recipeContext(itemId, safeIndex, usageMode);
        this.openRecipe(player, recipe, recipes, safeIndex, usageMode);
        this.scheduleRotation(player, itemId, usageMode, safeIndex, recipes);
        return true;
    }

    private void scheduleRotation(Player player, Key itemId, boolean usageMode, int index, List<Recipe> recipes) {
        this.cancelRotation(player);
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
            this.open(player, itemId, usageMode, next);
        }, interval);
        if (task != null) this.rotateTasks.put(player.getUniqueId(), task);
    }

    public void cancelRotation(Player player) {
        ScheduledTask task = this.rotateTasks.remove(player.getUniqueId());
        if (task != null) {
            task.cancel();
        }
    }

    public void shutdown() {
        this.rotateTasks.values().forEach(ScheduledTask::cancel);
        this.rotateTasks.clear();
    }

    private Item ingredientItem(Player player, Ingredient ing, int count) {
        return this.clickable(ItemBridge.firstIngredientKey(ing).orElse(null), ItemBridge.buildIngredient(ing, player, count));
    }

    public boolean hasContent(Key itemId, boolean usageMode) {
        return this.data.isReady() && !(usageMode ? this.recipeIndex.recipesUsingIngredient(itemId) : this.recipeIndex.recipesForResult(itemId)).isEmpty();
    }

    private Item clickable(Key target, ItemStack icon) {
        List<Component> lore = new ArrayList<>();
        for (String line : this.config.itemLore()) {
            lore.add(this.config.mm(line));
        }
        ItemStack decorated = ItemBridge.decorate(icon, null, lore);
        if (target == null) {
            return Item.simple(decorated);
        }
        return Item.builder().setItemProvider(decorated).addClickHandler(click -> {
            Player p = click.player();
            boolean usage = click.clickType() == ClickType.RIGHT || click.clickType() == ClickType.SHIFT_RIGHT;
            if (!this.hasContent(target, usage)) {
                return;
            }
            Sounds.play(p, this.config.sounds().click());
            this.open(p, target, usage, 0);
        }).build();
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

    private void backToBrowser(Player player) {
        GuiSession session = GuiSession.of(player);
        Key category = session.currentCategory();
        Sounds.play(player, this.config.sounds().back());
        if (category != null && this.data.category(category).isPresent()) {
            this.browser.openCategory(player, category, session.categoryPage());
        } else {
            this.browser.openDefault(player);
        }
    }

    private void onOutsideClick(ClickEvent event) {
        event.setCancelled(true);
        this.backToBrowser(event.getPlayer());
    }

    private Key resultKey(Recipe recipe) {
        if (recipe instanceof FixedResultRecipe fixed) {
            try {
                CustomRecipeResult r = fixed.result();
                if (r != null && r.item() != null) {
                    return r.item().id();
                }
            } catch (Throwable ignored) {
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
            } catch (Throwable ignored) {
            }
        }
        return 1;
    }
}
