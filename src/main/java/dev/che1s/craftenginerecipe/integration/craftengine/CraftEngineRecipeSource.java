package dev.che1s.craftenginerecipe.integration.craftengine;

import dev.che1s.craftenginerecipe.CraftEngineRecipePlugin;
import dev.che1s.craftenginerecipe.api.DisplayRecipe;
import dev.che1s.craftenginerecipe.api.RecipeIngredient;
import dev.che1s.craftenginerecipe.api.RecipeItem;
import dev.che1s.craftenginerecipe.api.RecipeLayout;
import dev.che1s.craftenginerecipe.api.RecipeResult;
import dev.che1s.craftenginerecipe.api.RecipeWorkstation;
import dev.che1s.craftenginerecipe.integration.common.RecipeSource;
import dev.che1s.craftenginerecipe.i18n.IntegrationText;
import net.momirealms.craftengine.core.item.recipe.CustomBrewingRecipe;
import net.momirealms.craftengine.core.item.recipe.CustomCookingRecipe;
import net.momirealms.craftengine.core.item.recipe.CustomShapedRecipe;
import net.momirealms.craftengine.core.item.recipe.CustomSmithingTransformRecipe;
import net.momirealms.craftengine.core.item.recipe.CustomStoneCuttingRecipe;
import net.momirealms.craftengine.core.item.recipe.FixedResultRecipe;
import net.momirealms.craftengine.core.item.recipe.Ingredient;
import net.momirealms.craftengine.core.item.recipe.Recipe;
import net.momirealms.craftengine.core.item.recipe.RecipeType;
import net.momirealms.craftengine.core.item.recipe.result.CustomRecipeResult;
import net.momirealms.craftengine.core.util.Key;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/** 将 CraftEngine 固定产物配方一次性转换为可编辑 CER YAML 的桥接器。 */
public final class CraftEngineRecipeSource implements RecipeSource {
    public static final String SOURCE_ID = "craftengine";
    private static final List<Integer> CRAFTING_SLOTS =
            List.of(10, 11, 12, 19, 20, 21, 28, 29, 30);
    private final CraftEngineRecipePlugin plugin;
    private final IntegrationText text;

    public CraftEngineRecipeSource(CraftEngineRecipePlugin plugin) {
        this.plugin = plugin;
        this.text = IntegrationText.of("craftengine", plugin.lang());
    }

    @Override public String id() { return SOURCE_ID; }
    @Override public boolean ready() { return this.plugin.data().isReady(); }
    @Override public long revision() { return this.plugin.data().generation(); }

    @Override
    public List<DisplayRecipe> capture() {
        List<DisplayRecipe> out = new ArrayList<>();
        for (RecipeType type : RecipeType.values()) {
            Page page = page(type);
            if (page == null) continue;
            for (Recipe recipe : this.plugin.data().recipeSnapshot().byType(type)) {
                try {
                    if (!recipe.canBeSearched() || !(recipe instanceof FixedResultRecipe fixed)) continue;
                    CustomRecipeResult result = fixed.result();
                    if (result == null || result.item() == null || result.item().id() == null) continue;
                    List<RecipeIngredient> ingredients = ingredients(recipe, type);
                    if (ingredients.isEmpty()) continue;
                    String stationName = this.text.text("station." + page.langKey(),
                            this.plugin.lang().text("ui.workstations.categories." + page.langKey()));
                    String recipeId = "craftengine:" + page.id() + "/"
                            + recipe.id().toString().replace(':', '/').toLowerCase(Locale.ROOT);
                    RecipeWorkstation station = new RecipeWorkstation(
                            "minecraft:" + page.id(),
                            RecipeItem.of("minecraft:" + page.icon()), stationName);
                    RecipeLayout layout = RecipeLayout.page(stationName, page.page());
                    out.add(new DisplayRecipe(recipeId, station, layout, ingredients,
                            List.of(new RecipeResult(item(result.item().id()), Math.max(1, result.count()), 24, null)),
                            List.of(), null, player -> true));
                } catch (RuntimeException | LinkageError ignored) {
                    // 含非标准字段的自定义序列化器无法安全地展平。
                }
            }
        }
        return List.copyOf(out);
    }

    private static List<RecipeIngredient> ingredients(Recipe recipe, RecipeType type) {
        if (recipe instanceof CustomShapedRecipe shaped) {
            String[] pattern = shaped.pattern().pattern();
            List<RecipeIngredient> out = new ArrayList<>();
            int offsetY = (3 - Math.min(3, pattern.length)) / 2;
            for (int row = 0; row < pattern.length && row < 3; row++) {
                String line = pattern[row];
                int offsetX = (3 - Math.min(3, line.length())) / 2;
                for (int col = 0; col < line.length() && col < 3; col++) {
                    Ingredient ingredient = shaped.pattern().ingredients().get(line.charAt(col));
                    if (ingredient == null) continue;
                    int slot = CRAFTING_SLOTS.get((row + offsetY) * 3 + col + offsetX);
                    add(out, ingredient, slot, RecipeIngredient.Role.INGREDIENT);
                }
            }
            return List.copyOf(out);
        }
        if (recipe instanceof CustomBrewingRecipe brewing) {
            List<RecipeIngredient> out = new ArrayList<>();
            add(out, brewing.container(), 19, RecipeIngredient.Role.CONTAINER);
            add(out, brewing.ingredient(), 20, RecipeIngredient.Role.INGREDIENT);
            return List.copyOf(out);
        }
        if (recipe instanceof CustomSmithingTransformRecipe smithing) {
            List<RecipeIngredient> out = new ArrayList<>();
            add(out, smithing.template(), 19, RecipeIngredient.Role.INGREDIENT);
            add(out, smithing.base(), 20, RecipeIngredient.Role.INGREDIENT);
            add(out, smithing.addition(), 21, RecipeIngredient.Role.INGREDIENT);
            return List.copyOf(out);
        }
        List<Ingredient> source = recipe.ingredientsInUse();
        List<RecipeIngredient> out = new ArrayList<>();
        for (int index = 0; index < source.size(); index++) {
            Ingredient ingredient = source.get(index);
            int slot = type == RecipeType.CRAFTING ? CRAFTING_SLOTS.get(index) : 19 + index;
            List<RecipeItem> choices = choices(ingredient);
            if (!choices.isEmpty()) {
                out.add(new RecipeIngredient(choices, Math.max(1, ingredient.count()), slot,
                        RecipeIngredient.Role.INGREDIENT));
            }
        }
        return List.copyOf(out);
    }

    private static void add(List<RecipeIngredient> out, Ingredient ingredient, int slot,
                            RecipeIngredient.Role role) {
        List<RecipeItem> choices = choices(ingredient);
        if (!choices.isEmpty()) out.add(new RecipeIngredient(choices, Math.max(1, ingredient.count()), slot, role));
    }

    private static List<RecipeItem> choices(Ingredient ingredient) {
        LinkedHashSet<String> ids = new LinkedHashSet<>();
        ingredient.items().forEach(key -> ids.add(key.key().toString()));
        ingredient.minecraftItems().forEach(key -> ids.add(key.key().toString()));
        return ids.stream().map(RecipeItem::of).toList();
    }

    private static RecipeItem item(Key id) {
        return RecipeItem.of(id.toString());
    }

    private static Page page(RecipeType type) {
        return switch (type) {
            case CRAFTING -> new Page("crafting", "crafting", "crafting_table", "crafting");
            case SMELTING -> new Page("smelting", "smelting", "furnace", "smelting");
            case BLASTING -> new Page("blasting", "blasting", "blast_furnace", "blasting");
            case SMOKING -> new Page("smoking", "smoking", "smoker", "smoking");
            case CAMPFIRE_COOKING -> new Page("campfire_cooking", "campfire", "campfire", "campfire_cooking");
            case STONECUTTING -> new Page("stonecutting", "stonecutting", "stonecutter", "stonecutting");
            case SMITHING -> new Page("smithing", "smithing", "smithing_table", "smithing");
            case BREWING -> new Page("brewing", "brewing", "brewing_stand", "brewing");
        };
    }

    private record Page(String id, String page, String icon, String langKey) {}
}
