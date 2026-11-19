package dev.che1s.craftenginerecipe.api.extension;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import dev.che1s.craftenginerecipe.api.DisplayRecipe;
import dev.che1s.craftenginerecipe.api.RecipeIngredient;
import dev.che1s.craftenginerecipe.api.RecipeItem;
import dev.che1s.craftenginerecipe.api.RecipeLayout;
import dev.che1s.craftenginerecipe.api.RecipeResult;
import dev.che1s.craftenginerecipe.api.RecipeWorkstation;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/** Converts normalized cultivation documents into the shared display-recipe model. */
public final class CultivationRecipeConverter {
    public static final String STATION_ID = "minecraft:cultivation";

    private CultivationRecipeConverter() {
    }

    public static List<DisplayRecipe> convert(JsonObject root, String source, SourceContext context) {
        JsonArray crops = root.getAsJsonArray("crops");
        if (crops == null || crops.isEmpty()) return List.of();
        SourceText text = context.text("cultivation");
        String localizedName = context.coreText().apply("ui.workstations.categories.cultivation");
        String stationName = text.text("station",
                (context.chinese() ? "原版·" : "Vanilla·") + localizedName,
                "name", localizedName);
        RecipeWorkstation station = new RecipeWorkstation(STATION_ID,
                named(RecipeItem.of("minecraft:farmland"), context), stationName);
        List<DisplayRecipe> recipes = new ArrayList<>();
        for (JsonElement element : crops) {
            if (!element.isJsonObject()) continue;
            JsonObject crop = element.getAsJsonObject();
            List<RecipeItem> seeds = items(crop.getAsJsonArray("seeds"), context);
            List<RecipeItem> products = items(crop.getAsJsonArray("products"), context);
            if (seeds.isEmpty() || products.isEmpty()) continue;
            String id = string(crop, "id");
            String path = id.contains(":") ? id.substring(id.indexOf(':') + 1) : id;
            String noteKey = "note." + sanitize(path);
            String note = text.has(noteKey)
                    ? marker("cultivation", noteKey) : string(crop, "note");
            String seedLoreText = text.has("seed.lore")
                    ? marker("cultivation", "seed.lore")
                    : text.text("seed.lore",
                    "<#98C69A>种植基质：</#98C69A><white>见旁边的基质图标</white>");
            List<String> seedLore = List.of(seedLoreText);
            List<RecipeIngredient> ingredients = new ArrayList<>();
            ingredients.add(new RecipeIngredient(seeds.stream()
                    .map(item -> named(item, context, seedLore))
                    .toList(), 1, 19, RecipeIngredient.Role.INGREDIENT));
            List<RecipeItem> substrates = items(crop.getAsJsonArray("substrates"), context).stream()
                    .map(CultivationRecipeConverter::displayableSubstrate)
                    .map(item -> named(item, context))
                    .toList();
            if (!substrates.isEmpty()) {
                ingredients.add(new RecipeIngredient(substrates, 1, 21, RecipeIngredient.Role.INFO));
            }
            List<RecipeResult> results = new ArrayList<>();
            for (int index = 0; index < products.size(); index++) {
                RecipeItem product = products.get(index);
                List<String> lore = note.isBlank() ? List.of() : List.of("<#B8C0CC>" + note + "</#B8C0CC>");
                RecipeItem decorated = named(product, context, lore);
                int slot = switch (index) {
                    case 0 -> 23;
                    case 1 -> 24;
                    case 2 -> 32;
                    case 3 -> 33;
                    default -> -1;
                };
                results.add(new RecipeResult(decorated, 1, slot, null));
            }
            String prefix = source.equals("minecraft")
                    ? "minecraft_cultivation" : "cultivation_" + sanitize(source);
            recipes.add(new DisplayRecipe(prefix + ":" + sanitize(path), station,
                    new RecipeLayout(6, stationName, -1, 49, 45, 48, 53),
                    ingredients, results, List.of(), null, player -> true));
        }
        return List.copyOf(recipes);
    }

    private static List<RecipeItem> items(JsonArray array, SourceContext context) {
        if (array == null) return List.of();
        List<RecipeItem> out = new ArrayList<>();
        for (JsonElement value : array) {
            if (value.isJsonPrimitive()) out.add(named(RecipeItem.of(value.getAsString()), context));
        }
        return List.copyOf(out);
    }

    private static RecipeItem named(RecipeItem item, SourceContext context) {
        return named(item, context, item.lore());
    }

    private static RecipeItem named(RecipeItem item, SourceContext context, List<String> lore) {
        Map<String, String> names = context.localizedItemNames(item.id());
        String name = names.getOrDefault("en_US", context.itemDisplayName().apply(item.id()));
        return new RecipeItem(item.provider(), item.id(), null, name, lore, names);
    }

    private static RecipeItem displayableSubstrate(RecipeItem item) {
        if (item.id().equals("minecraft:lava")) return RecipeItem.of("minecraft:lava_bucket");
        if (item.id().equals("minecraft:water")) return RecipeItem.of("minecraft:water_bucket");
        return item;
    }

    private static String string(JsonObject object, String key) {
        if (!object.has(key) || !object.get(key).isJsonPrimitive()) return "";
        return object.get(key).getAsString();
    }

    private static String sanitize(String value) {
        return value.toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9/._-]+", "_");
    }

    private static String marker(String source, String key) {
        return "<cer-lang:" + source + ":" + key + ">";
    }
}
