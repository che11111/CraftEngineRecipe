package dev.che1s.craftenginerecipe.debug;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonNull;
import com.google.gson.JsonObject;
import dev.che1s.craftenginerecipe.api.RecipeItem;
import dev.che1s.craftenginerecipe.recipe.RecipeEntry;
import net.momirealms.craftengine.core.item.recipe.*;
import net.momirealms.craftengine.core.item.recipe.result.CustomRecipeResult;
import org.bukkit.Keyed;
import org.bukkit.inventory.*;

import java.util.Base64;

/** 在全局调度器上将 API 对象转换为 JSON，不执行任何配方逻辑。 */
final class RecipeDump {
    static final Gson JSON = new GsonBuilder().setPrettyPrinting().disableHtmlEscaping().create();

    private RecipeDump() {}

    static JsonObject bukkit(org.bukkit.inventory.Recipe recipe) {
        JsonObject out = new JsonObject();
        out.addProperty("class", recipe.getClass().getName());
        if (recipe instanceof Keyed keyed) out.addProperty("id", keyed.getKey().toString());
        try { out.add("result", stack(recipe.getResult())); }
        catch (RuntimeException | LinkageError error) { out.addProperty("resultError", error(error)); }
        if (recipe instanceof ShapedRecipe shaped) {
            out.add("shape", JSON.toJsonTree(shaped.getShape()));
            JsonObject choices = new JsonObject();
            shaped.getChoiceMap().forEach((key, value) -> choices.add(key.toString(), choice(value)));
            out.add("ingredients", choices);
        } else if (recipe instanceof ShapelessRecipe shapeless) {
            JsonArray choices = new JsonArray();
            shapeless.getChoiceList().forEach(value -> choices.add(choice(value)));
            out.add("ingredients", choices);
        } else if (recipe instanceof CookingRecipe<?> cooking) {
            out.add("input", choice(cooking.getInputChoice()));
            out.addProperty("cookingTime", cooking.getCookingTime());
            out.addProperty("experience", cooking.getExperience());
            out.addProperty("group", cooking.getGroup());
            out.addProperty("category", cooking.getCategory().name());
        } else if (recipe instanceof StonecuttingRecipe stonecutting) {
            out.add("input", choice(stonecutting.getInputChoice()));
            out.addProperty("group", stonecutting.getGroup());
        } else if (recipe instanceof SmithingRecipe smithing) {
            out.add("base", choice(smithing.getBase()));
            out.add("addition", choice(smithing.getAddition()));
            out.addProperty("copyDataComponents", smithing.willCopyDataComponents());
            if (recipe instanceof SmithingTransformRecipe transform) out.add("template", choice(transform.getTemplate()));
            if (recipe instanceof SmithingTrimRecipe trim) {
                out.add("template", choice(trim.getTemplate()));
                if (trim.getTrimPattern() != null) out.addProperty("pattern", trim.getTrimPattern().getKey().toString());
            }
        } else if (recipe instanceof TransmuteRecipe transmute) {
            out.add("input", choice(transmute.getInput()));
            out.add("material", choice(transmute.getMaterial()));
        } else {
            out.addProperty("details", "Dynamic or third-party recipe: Bukkit does not expose its full ingredient/behavior data through the standard API.");
        }
        if (recipe instanceof CraftingRecipe crafting) {
            out.addProperty("group", crafting.getGroup());
            out.addProperty("category", crafting.getCategory().name());
        }
        return out;
    }

    static com.google.gson.JsonElement choice(RecipeChoice choice) {
        if (choice == null) return JsonNull.INSTANCE;
        JsonObject out = new JsonObject();
        out.addProperty("class", choice.getClass().getName());
        JsonArray alternatives = new JsonArray();
        if (choice instanceof RecipeChoice.MaterialChoice materials) {
            materials.getChoices().forEach(material -> alternatives.add(material.getKey().toString()));
            out.add("materials", alternatives);
        } else if (choice instanceof RecipeChoice.ExactChoice exact) {
            exact.getChoices().forEach(item -> alternatives.add(stack(item)));
            out.add("exactItems", alternatives);
        } else out.addProperty("details", "Custom choice implementation; alternatives are not exposed by Bukkit.");
        return out;
    }

    static JsonObject nativeRecipe(net.momirealms.craftengine.core.item.recipe.Recipe recipe) {
        JsonObject out = new JsonObject();
        out.addProperty("id", recipe.id().toString());
        out.addProperty("class", recipe.getClass().getName());
        out.addProperty("type", recipe.type() == null ? null : recipe.type().name());
        out.addProperty("serializer", recipe.serializerType() == null ? null : recipe.serializerType().toString());
        out.addProperty("canBeSearched", recipe.canBeSearched());
        out.addProperty("showNotification", recipe.showNotification());
        JsonArray ingredients = new JsonArray();
        recipe.ingredientsInUse().forEach(value -> ingredients.add(ingredient(value)));
        out.add("ingredients", ingredients);
        if (recipe instanceof FixedResultRecipe fixed) out.add("result", result(fixed.result()));
        if (recipe instanceof VisualResultRecipe visual) out.add("visualResult", result(visual.visualResult()));
        if (recipe instanceof AbstractGroupedRecipe grouped) out.addProperty("group", grouped.group());
        if (recipe instanceof ConditionalRecipe conditional) out.addProperty("hasCondition", conditional.hasCondition());
        if (recipe instanceof FunctionalRecipe functional) {
            JsonArray functions = new JsonArray();
            var configured = functional.functions();
            if (configured != null) {
                for (var function : configured) functions.add(function.getClass().getName());
            }
            out.add("functionClasses", functions);
        }
        if (recipe instanceof CustomShapedRecipe shaped) {
            out.add("shape", JSON.toJsonTree(shaped.pattern().pattern()));
            JsonObject choices = new JsonObject();
            shaped.pattern().ingredients().forEach((key, value) -> choices.add(key.toString(), ingredient(value)));
            out.add("shapeIngredients", choices);
            out.addProperty("ingredientCountSupport", shaped.ingredientCountSupport());
        }
        if (recipe instanceof CustomCookingRecipe cooking) {
            out.addProperty("cookingTime", cooking.cookingTime());
            out.addProperty("experience", cooking.experience());
            out.addProperty("category", cooking.category().name());
        }
        if (recipe instanceof CustomBrewingRecipe brewing) {
            out.add("container", ingredient(brewing.container()));
            out.add("brewingIngredient", ingredient(brewing.ingredient()));
        }
        if (recipe instanceof CustomSmithingTransformRecipe smithing) {
            out.add("template", ingredient(smithing.template()));
            out.add("base", ingredient(smithing.base()));
            out.add("addition", ingredient(smithing.addition()));
            out.addProperty("mergeComponents", smithing.mergeComponents());
        }
        if (recipe instanceof CustomSmithingTrimRecipe smithing) {
            out.add("template", ingredient(smithing.template()));
            out.add("base", ingredient(smithing.base()));
            out.add("addition", ingredient(smithing.addition()));
            out.addProperty("pattern", smithing.pattern().toString());
        }
        return out;
    }

    private static com.google.gson.JsonElement result(CustomRecipeResult result) {
        if (result == null) return JsonNull.INSTANCE;
        JsonObject out = new JsonObject();
        out.addProperty("item", result.item() == null ? null : result.item().id().toString());
        out.addProperty("amount", result.count());
        JsonArray processors = new JsonArray();
        var configured = result.postProcessors();
        if (configured != null) {
            for (var processor : configured) processors.add(processor.getClass().getName());
        }
        out.add("postProcessorClasses", processors);
        return out;
    }

    static JsonObject ingredient(Ingredient ingredient) {
        JsonObject out = new JsonObject();
        out.addProperty("amount", ingredient.count());
        JsonArray elements = new JsonArray();
        for (var element : ingredient.elements()) {
            if (element instanceof IngredientElement.Item item) elements.add(item.id().toString());
            else if (element instanceof IngredientElement.Tag tag) elements.add("#" + tag.tag());
            else elements.add(element.toString());
        }
        out.add("elements", elements);
        JsonArray items = new JsonArray();
        ingredient.items().forEach(item -> items.add(item.key().toString()));
        out.add("items", items);
        JsonArray vanilla = new JsonArray();
        ingredient.minecraftItems().forEach(item -> vanilla.add(item.key().toString()));
        out.add("minecraftItems", vanilla);
        if (ingredient.predicate() != null) out.addProperty("predicateClass", ingredient.predicate().getClass().getName());
        return out;
    }

    static JsonObject external(RecipeEntry entry) {
        var recipe = entry.display();
        JsonObject out = new JsonObject();
        out.addProperty("source", entry.source());
        out.addProperty("id", recipe.id());
        out.addProperty("permission", recipe.permission());
        out.addProperty("hasVisibilityPredicate", recipe.visibility() != null);
        JsonObject station = new JsonObject();
        station.addProperty("id", recipe.workstation().id());
        station.addProperty("name", recipe.workstation().name());
        station.add("icon", item(recipe.workstation().icon()));
        out.add("workstation", station);
        out.add("layout", JSON.toJsonTree(recipe.layout()));
        out.add("description", JSON.toJsonTree(recipe.description()));
        JsonArray ingredients = new JsonArray();
        recipe.ingredients().forEach(value -> {
            JsonObject ingredient = new JsonObject();
            ingredient.addProperty("amount", value.amount());
            ingredient.addProperty("slot", value.slot());
            ingredient.addProperty("role", value.role().name());
            JsonArray choices = new JsonArray();
            value.choices().forEach(choice -> choices.add(item(choice)));
            ingredient.add("choices", choices);
            ingredients.add(ingredient);
        });
        out.add("ingredients", ingredients);
        JsonArray results = new JsonArray();
        recipe.results().forEach(value -> {
            JsonObject result = new JsonObject();
            result.add("item", item(value.item()));
            result.addProperty("amount", value.amount());
            result.addProperty("slot", value.slot());
            result.addProperty("note", value.note());
            results.add(result);
        });
        out.add("results", results);
        return out;
    }

    private static JsonObject item(RecipeItem item) {
        JsonObject out = new JsonObject();
        out.addProperty("provider", item.provider());
        out.addProperty("id", item.id());
        out.addProperty("name", item.name());
        out.add("lore", JSON.toJsonTree(item.lore()));
        var icon = item.icon();
        if (icon != null) out.add("icon", stack(icon));
        return out;
    }

    private static com.google.gson.JsonElement stack(ItemStack item) {
        if (item == null) return JsonNull.INSTANCE;
        JsonObject out = new JsonObject();
        out.addProperty("material", item.getType().getKey().toString());
        out.addProperty("amount", item.getAmount());
        // 动态 Bukkit 配方在实际合成确定产物前会返回 AIR。
        // 空物品堆没有二进制表示形式，但仍是有效的诊断值。
        if (item.isEmpty()) return out;
        // Bukkit 的二进制编码会保留精确的元数据和组件，包括自定义物品身份。
        try { out.addProperty("nbtBase64", Base64.getEncoder().encodeToString(item.serializeAsBytes())); }
        catch (RuntimeException | LinkageError error) { out.addProperty("serializationError", error(error)); }
        return out;
    }

    static String error(Throwable error) {
        return error.getClass().getSimpleName() + ": " + java.util.Objects.toString(error.getMessage(), "");
    }
}
