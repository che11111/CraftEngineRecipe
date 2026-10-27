package dev.che1s.craftenginerecipe.integration.minecraft;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import dev.che1s.craftenginerecipe.CraftEngineRecipePlugin;
import dev.che1s.craftenginerecipe.api.DisplayRecipe;
import dev.che1s.craftenginerecipe.api.RecipeIngredient;
import dev.che1s.craftenginerecipe.api.RecipeItem;
import dev.che1s.craftenginerecipe.api.RecipeLayout;
import dev.che1s.craftenginerecipe.api.RecipeResult;
import dev.che1s.craftenginerecipe.api.RecipeWorkstation;
import dev.che1s.craftenginerecipe.i18n.IntegrationText;
import dev.che1s.craftenginerecipe.util.VanillaTranslations;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;
import java.util.function.Predicate;
import java.util.jar.JarEntry;
import java.util.jar.JarFile;

/** 加载构建期间打包的按版本原版配方与栽培数据文件。 */
public final class BundledMinecraftRecipeSource {
    private static final String CULTIVATION_STATION = "minecraft:cultivation";
    private static final List<Integer> CRAFTING_SLOTS =
            List.of(10, 11, 12, 19, 20, 21, 28, 29, 30);
    private static final List<Integer> INPUT_SLOTS = List.of(19, 20, 21, 22, 28, 29, 30);
    private final Path jarPath;
    private final Path dataFolder;
    private final Function<String, String> translations;
    private final IntegrationText minecraftText;
    private final IntegrationText cultivationText;
    private final Predicate<String> sourceEnabled;
    private final boolean chinese;
    private Map<String, String> vanillaNames = Map.of();
    private Map<String, List<String>> itemTags = Map.of();

    public BundledMinecraftRecipeSource(CraftEngineRecipePlugin plugin) {
        this(plugin.jarPath(), plugin.getDataFolder().toPath(), plugin.lang()::text,
                IntegrationText.of("minecraft", plugin.lang()),
                IntegrationText.of("cultivation", plugin.lang()),
                source -> plugin.getConfig().getBoolean("sources." + source, true),
                plugin.lang().locale().toLowerCase(Locale.ROOT).startsWith("zh"));
    }

    /** 安装生成的 CER YAML，使常规可编辑配方加载器负责运行时加载。 */
    public static void installRecipeFiles(CraftEngineRecipePlugin plugin) throws IOException {
        try (JarFile jar = new JarFile(plugin.jarPath().toFile())) {
            String root = root(jar, "recipes/minecraft-");
            if (root == null) return;
            Path targetRoot = plugin.getDataFolder().toPath().resolve("recipes")
                    .resolve(root.substring("recipes/".length(), root.length() - 1));
            Files.createDirectories(targetRoot);
            for (JarEntry entry : jar.stream()
                    .filter(value -> !value.isDirectory() && value.getName().startsWith(root)
                            && value.getName().endsWith(".yml"))
                    .toList()) {
                Path target = targetRoot.resolve(entry.getName().substring(root.length()));
                Files.createDirectories(target.getParent());
                byte[] bytes = jar.getInputStream(entry).readAllBytes();
                if (Files.isRegularFile(target) && java.util.Arrays.equals(Files.readAllBytes(target), bytes)) {
                    continue;
                }
                Files.write(target, bytes);
            }
        }
    }

    BundledMinecraftRecipeSource(Path jarPath, Function<String, String> translations) {
        this(jarPath, Path.of("."), translations, IntegrationText.empty(), IntegrationText.empty(),
                ignored -> true, false);
    }

    BundledMinecraftRecipeSource(Path jarPath, Path dataFolder, Function<String, String> translations,
                                 Predicate<String> sourceEnabled) {
        this(jarPath, dataFolder, translations, IntegrationText.empty(), IntegrationText.empty(),
                sourceEnabled, false);
    }

    BundledMinecraftRecipeSource(Path jarPath, Path dataFolder, Function<String, String> translations,
                                 Predicate<String> sourceEnabled, boolean chinese) {
        this(jarPath, dataFolder, translations, IntegrationText.empty(), IntegrationText.empty(),
                sourceEnabled, chinese);
    }

    BundledMinecraftRecipeSource(Path jarPath, Path dataFolder, Function<String, String> translations,
                                 IntegrationText minecraftText, IntegrationText cultivationText,
                                 Predicate<String> sourceEnabled, boolean chinese) {
        this.jarPath = jarPath;
        this.dataFolder = dataFolder;
        this.translations = translations;
        this.minecraftText = minecraftText == null ? IntegrationText.empty() : minecraftText;
        this.cultivationText = cultivationText == null ? IntegrationText.empty() : cultivationText;
        this.sourceEnabled = sourceEnabled;
        this.chinese = chinese;
    }

    public List<DisplayRecipe> load() throws IOException {
        try (JarFile jar = new JarFile(this.jarPath.toFile())) {
            this.vanillaNames = this.chinese ? VanillaTranslations.read(this.jarPath, true) : Map.of();
            this.itemTags = loadItemTags(jar);
            List<DisplayRecipe> recipes = new ArrayList<>();
            if (this.sourceEnabled.test("minecraft")) {
                recipes.addAll(this.recipeFiles(jar));
            }
            if (this.sourceEnabled.test("minecraft-cultivation")) {
                recipes.addAll(this.cultivationRecipes(jar, "minecraft"));
            }
            Path craftEngineDirectory = this.dataFolder.resolve("recipes/cultivation/craftengine");
            if (Files.isDirectory(craftEngineDirectory, LinkOption.NOFOLLOW_LINKS)) {
                try (var files = Files.list(craftEngineDirectory)) {
                    for (Path file : files.filter(path -> Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS))
                            .filter(path -> path.getFileName().toString().endsWith(".json"))
                            .sorted().toList()) {
                        JsonObject root = JsonParser.parseString(Files.readString(file, StandardCharsets.UTF_8))
                                .getAsJsonObject();
                        String namespace = string(root, "namespace");
                        if (namespace.isBlank()) {
                            String name = file.getFileName().toString();
                            namespace = name.substring(0, name.length() - 5);
                        }
                        if (!craftEngineCultivationEnabled(namespace)) continue;
                        recipes.addAll(this.cultivationRecipes(root, namespace));
                    }
                }
            }
            Path customCropsFile = this.dataFolder.resolve("recipes/cultivation/customcrops/vanilla.json");
            if (this.sourceEnabled.test("customcrops-cultivation")
                    && Files.isRegularFile(customCropsFile, LinkOption.NOFOLLOW_LINKS)) {
                JsonObject root = JsonParser.parseString(Files.readString(customCropsFile, StandardCharsets.UTF_8))
                        .getAsJsonObject();
                recipes.addAll(this.cultivationRecipes(root, "customcrops"));
            }
            return List.copyOf(recipes);
        }
    }

    private List<DisplayRecipe> recipeFiles(JarFile jar) throws IOException {
        String root = root(jar, "recipes/minecraft-");
        if (root == null) return List.of();
        List<DisplayRecipe> recipes = new ArrayList<>();
        for (JarEntry entry : jar.stream()
                .filter(value -> !value.isDirectory() && value.getName().startsWith(root)
                        && value.getName().endsWith(".json"))
                .sorted(Comparator.comparing(JarEntry::getName)).toList()) {
            String json = new String(jar.getInputStream(entry).readAllBytes(), StandardCharsets.UTF_8);
            try {
                recipes.addAll(this.parseRecipeVariants(entry.getName().substring(root.length()), json));
            } catch (RuntimeException ignored) {
                // 单个不受支持的原版配方不得导致其余兜底数据被丢弃。
            }
        }
        return List.copyOf(recipes);
    }

    DisplayRecipe parseRecipe(String fileName, String json) {
        List<DisplayRecipe> variants = this.parseRecipeVariants(fileName, json);
        return variants.isEmpty() ? null : variants.getFirst();
    }

    private List<DisplayRecipe> parseRecipeVariants(String fileName, String json) {
        JsonObject root = JsonParser.parseString(json).getAsJsonObject();
        String type = string(root, "type");
        RecipeTypeInfo info = typeInfo(type);
        if (info == null) return List.of();
        ItemAmount result = result(root.get("result"));
        if (result == null) return List.of();

        List<IngredientData> ingredients = this.ingredients(root, type);
        if (ingredients.isEmpty()) return List.of();
        List<Integer> slots = type.contains("crafting_") ? CRAFTING_SLOTS : INPUT_SLOTS;
        if (ingredients.stream().anyMatch(ingredient -> ingredient.choices().isEmpty())) return List.of();
        List<RecipeIngredient> parsedIngredients = new ArrayList<>();
        for (int index = 0; index < ingredients.size() && index < slots.size(); index++) {
            IngredientData ingredient = ingredients.get(index);
            parsedIngredients.add(new RecipeIngredient(ingredient.choices().stream().map(this::named).toList(),
                    Math.max(1, ingredient.amount()), slots.get(index),
                    RecipeIngredient.Role.INGREDIENT));
        }
        if (parsedIngredients.isEmpty()) return List.of();

        String fallbackStation = this.translations.apply("ui.workstations.categories." + info.langKey());
        String stationName = this.minecraftText.text("station." + info.langKey(), fallbackStation);
        RecipeWorkstation workstation = new RecipeWorkstation(info.id(),
                this.named(RecipeItem.of("minecraft:" + info.icon())), stationName);
        RecipeLayout layout = new RecipeLayout(6, stationName, 4, 49, 45, 48, 53);
        String baseId = "minecraft_data:" + sanitize(fileName.substring(0, fileName.length() - 5));
        RecipeResult parsedResult = new RecipeResult(this.named(RecipeItem.of(result.id())),
                Math.max(1, result.count()), 24, null);
        String outputType = info.id();
        if (!"minecraft:crafting".equals(outputType)) {
            return List.of(new DisplayRecipe(baseId, workstation, layout, parsedIngredients,
                    List.of(parsedResult), List.of(), null, player -> true));
        }
        List<Integer> indices = new ArrayList<>();
        int variants = 1;
        for (RecipeIngredient ingredient : parsedIngredients) {
            indices.add(0);
            variants = (int) Math.min(64L, (long) variants * Math.max(1, ingredient.choices().size()));
        }
        List<DisplayRecipe> out = new ArrayList<>(variants);
        for (int variant = 0; variant < variants; variant++) {
            List<RecipeIngredient> concrete = new ArrayList<>(parsedIngredients.size());
            for (int index = 0; index < parsedIngredients.size(); index++) {
                RecipeIngredient ingredient = parsedIngredients.get(index);
                int choice = ingredient.choices().size() <= 1 ? 0 : indices.get(index);
                concrete.add(new RecipeIngredient(List.of(ingredient.choices().get(choice)),
                        ingredient.amount(), ingredient.slot(), ingredient.role()));
            }
            String id = variant == 0 ? baseId : baseId + "-variant-" + String.format("%02d", variant + 1);
            out.add(new DisplayRecipe(id, workstation, layout, concrete,
                    List.of(parsedResult), List.of(), null, player -> true));
            for (int index = parsedIngredients.size() - 1; index >= 0; index--) {
                int size = parsedIngredients.get(index).choices().size();
                if (size <= 1) continue;
                int next = indices.get(index) + 1;
                indices.set(index, next % size);
                if (next < size) break;
            }
        }
        return List.copyOf(out);
    }

    private List<DisplayRecipe> cultivationRecipes(JarFile jar, String source) throws IOException {
        String root = root(jar, "recipes/cultivation/minecraft-");
        if (root == null) return List.of();
        JarEntry data = jar.stream()
                .filter(value -> !value.isDirectory() && value.getName().equals(root + "vanilla.json"))
                .findFirst().orElse(null);
        if (data == null) return List.of();
        JsonObject rootJson = JsonParser.parseString(
                new String(jar.getInputStream(data).readAllBytes(), StandardCharsets.UTF_8)).getAsJsonObject();
        return this.cultivationRecipes(rootJson, source);
    }

    private List<DisplayRecipe> cultivationRecipes(JsonObject rootJson, String source) {
        String localizedName = this.translations.apply("ui.workstations.categories.cultivation");
        String stationName = this.cultivationText.text("station",
                (this.chinese ? "原版·" : "Vanilla · ") + localizedName,
                "name", localizedName);
        RecipeWorkstation station = new RecipeWorkstation(CULTIVATION_STATION,
                this.named(RecipeItem.of("minecraft:farmland")), stationName);
        JsonArray crops = rootJson.getAsJsonArray("crops");
        if (crops == null) return List.of();
        List<DisplayRecipe> recipes = new ArrayList<>();
        for (JsonElement value : crops) {
            if (!value.isJsonObject()) continue;
            JsonObject crop = value.getAsJsonObject();
            List<RecipeItem> seeds = this.items(crop.getAsJsonArray("seeds"));
            List<RecipeItem> products = this.items(crop.getAsJsonArray("products"));
            if (seeds.isEmpty() || products.isEmpty()) continue;
            String id = string(crop, "id");
            String path = id.contains(":") ? id.substring(id.indexOf(':') + 1) : id;
            String note = string(crop, "note");
            note = this.cultivationText.text("note." + sanitize(path), note);
            List<String> seedLore = List.of(this.cultivationText.text("seed.lore",
                    "<#98C69A>种植基质：</#98C69A><white>见旁边的基质图标</white>"));
            RecipeIngredient seed = new RecipeIngredient(seeds.stream()
                    .map(item -> new RecipeItem(item.provider(), item.id(), null, item.name(), seedLore)).toList(),
                    1, 19, RecipeIngredient.Role.INGREDIENT);
            List<RecipeIngredient> ingredients = new ArrayList<>();
            ingredients.add(seed);
            List<RecipeItem> substrates = this.items(crop.getAsJsonArray("substrates")).stream()
                    .map(BundledMinecraftRecipeSource::displayableSubstrate)
                    .map(this::named).toList();
            if (!substrates.isEmpty()) {
                ingredients.add(new RecipeIngredient(substrates, 1, 21, RecipeIngredient.Role.INFO));
            }
            List<RecipeResult> results = new ArrayList<>();
            for (int index = 0; index < products.size(); index++) {
                RecipeItem product = products.get(index);
                List<String> lore = note.isBlank() ? List.of() : List.of("<#B8C0CC>" + note + "</#B8C0CC>");
                RecipeItem decorated = new RecipeItem(product.provider(), product.id(), null, product.name(), lore);
                int slot = switch (index) {
                    case 0 -> 23;
                    case 1 -> 24;
                    case 2 -> 32;
                    case 3 -> 33;
                    default -> -1;
                };
                results.add(new RecipeResult(decorated, 1, slot, null));
            }
            String prefix = source.equals("minecraft") ? "minecraft_cultivation" : "cultivation_" + source;
            recipes.add(new DisplayRecipe(prefix + ":" + sanitize(path),
                    station, new RecipeLayout(6, stationName, -1, 49, 45, 48, 53),
                    ingredients, results, List.of(), null, player -> true));
        }
        return List.copyOf(recipes);
    }

    private List<IngredientData> ingredients(JsonObject root, String type) {
        List<IngredientData> out = new ArrayList<>();
        if (type.endsWith("crafting_shaped") && root.has("pattern") && root.has("key")) {
            JsonArray pattern = root.getAsJsonArray("pattern");
            JsonObject key = root.getAsJsonObject("key");
            Map<String, Integer> counts = new LinkedHashMap<>();
            for (JsonElement row : pattern) {
                for (char symbol : row.getAsString().toCharArray()) {
                    String value = String.valueOf(symbol);
                    if (value.isBlank()) continue;
                    counts.merge(value, 1, Integer::sum);
                }
            }
            counts.forEach((symbol, count) -> {
                if (key.has(symbol)) out.add(this.ingredient(key.get(symbol), count));
            });
            return out;
        }
        JsonArray array = root.has("ingredients") ? root.getAsJsonArray("ingredients") : null;
        if (array != null) {
            for (JsonElement value : array) out.add(this.ingredient(value, 1));
            return out;
        }
        List<String> keys = switch (type) {
            case "minecraft:crafting_transmute" -> List.of("input", "material");
            case "minecraft:crafting_dye" -> List.of("dye", "target");
            case "minecraft:crafting_special_bannerduplicate" -> List.of("banner");
            case "minecraft:crafting_special_shielddecoration" -> List.of("target", "banner");
            case "minecraft:crafting_imbue" -> List.of("source", "material");
            case "minecraft:crafting_decorated_pot" -> List.of("back", "front", "left", "right");
            default -> List.of("ingredient", "template", "base", "addition");
        };
        for (String key : keys) {
            if (root.has(key)) out.add(this.ingredient(root.get(key), 1));
        }
        return out;
    }

    private IngredientData ingredient(JsonElement raw, int amount) {
        if (raw == null || raw.isJsonNull()) return new IngredientData(1, List.of());
        if (raw.isJsonPrimitive()) return new IngredientData(amount, this.choices(raw.getAsString()));
        if (raw.isJsonArray()) {
            List<RecipeItem> values = new ArrayList<>();
            for (JsonElement child : raw.getAsJsonArray()) values.addAll(this.ingredient(child, amount).choices());
            return new IngredientData(amount, values);
        }
        JsonObject object = raw.getAsJsonObject();
        String item = object.has("item") ? string(object, "item") : object.has("id") ? string(object, "id") : null;
        if (item != null && !item.isBlank()) return new IngredientData(amount, this.choices(item));
        String tag = string(object, "tag");
        if (!tag.isBlank()) {
            return new IngredientData(amount, this.choices("#" + tag));
        }
        return new IngredientData(amount, List.of());
    }

    private List<RecipeItem> choices(String id) {
        if (id.startsWith("#")) {
            List<String> expanded = resolveTag(id.substring(1), new LinkedHashSet<>());
            if (!expanded.isEmpty()) return expanded.stream().map(RecipeItem::of).map(this::named).toList();
            return List.of();
        }
        return List.of(this.named(RecipeItem.of(id)));
    }

    private static ItemAmount result(JsonElement raw) {
        if (raw == null || raw.isJsonNull()) return null;
        if (raw.isJsonPrimitive()) return new ItemAmount(raw.getAsString(), 1);
        if (!raw.isJsonObject()) return null;
        JsonObject object = raw.getAsJsonObject();
        String id = string(object, "id");
        if (id.isBlank()) id = string(object, "item");
        if (id.isBlank()) return null;
        int count = object.has("count") ? object.get("count").getAsInt() : 1;
        return new ItemAmount(id, Math.max(1, count));
    }

    private List<RecipeItem> items(JsonArray array) {
        if (array == null) return List.of();
        List<RecipeItem> out = new ArrayList<>();
        for (JsonElement value : array) {
            if (!value.isJsonPrimitive()) continue;
            out.add(this.named(RecipeItem.of(value.getAsString())));
        }
        return List.copyOf(out);
    }

    private static RecipeItem displayableSubstrate(RecipeItem item) {
        if (item.id().equals("minecraft:lava")) return RecipeItem.of("minecraft:lava_bucket");
        if (item.id().equals("minecraft:water")) return RecipeItem.of("minecraft:water_bucket");
        return item;
    }

    private RecipeItem named(RecipeItem item) {
        String name = VanillaTranslations.itemName(this.vanillaNames, item.id());
        return name == null || name.isBlank() ? item
                : new RecipeItem(item.provider(), item.id(), null, name, item.lore());
    }

    private List<String> resolveTag(String id, Set<String> seen) {
        if (!seen.add(id)) return List.of();
        List<String> values = this.itemTags.getOrDefault(id, List.of());
        LinkedHashSet<String> result = new LinkedHashSet<>();
        for (String value : values) {
            if (value.startsWith("#")) result.addAll(resolveTag(value.substring(1), seen));
            else result.add(value);
        }
        return List.copyOf(result);
    }

    private static Map<String, List<String>> loadItemTags(JarFile jar) throws IOException {
        Map<String, List<String>> tags = new LinkedHashMap<>();
        for (JarEntry entry : jar.stream()
                .filter(value -> !value.isDirectory()
                        && value.getName().startsWith("data/minecraft/tags/item/")
                        && value.getName().endsWith(".json"))
                .toList()) {
            String id = entry.getName().substring("data/minecraft/tags/item/".length(),
                    entry.getName().length() - 5);
            JsonArray values = JsonParser.parseString(
                    new String(jar.getInputStream(entry).readAllBytes(), StandardCharsets.UTF_8))
                    .getAsJsonObject().getAsJsonArray("values");
            if (values == null) continue;
            List<String> resolved = new ArrayList<>();
            for (JsonElement value : values) {
                if (value.isJsonPrimitive()) resolved.add(value.getAsString());
                else if (value.isJsonObject()) {
                    String item = string(value.getAsJsonObject(), "id");
                    if (!item.isBlank()) resolved.add(item);
                }
            }
            tags.put("minecraft:" + id, List.copyOf(resolved));
        }
        return Map.copyOf(tags);
    }

    private static RecipeTypeInfo typeInfo(String type) {
        return switch (type) {
            case "minecraft:crafting_shaped", "minecraft:crafting_shapeless",
                    "minecraft:crafting_transmute", "minecraft:crafting_dye",
                    "minecraft:crafting_special_bannerduplicate", "minecraft:crafting_special_shielddecoration",
                    "minecraft:crafting_imbue", "minecraft:crafting_decorated_pot" ->
                    new RecipeTypeInfo("minecraft:crafting", "crafting_table", "crafting");
            case "minecraft:smelting" -> new RecipeTypeInfo("minecraft:smelting", "furnace", "smelting");
            case "minecraft:blasting" -> new RecipeTypeInfo("minecraft:blasting", "blast_furnace", "blasting");
            case "minecraft:smoking" -> new RecipeTypeInfo("minecraft:smoking", "smoker", "smoking");
            case "minecraft:campfire_cooking" ->
                    new RecipeTypeInfo("minecraft:campfire_cooking", "campfire", "campfire_cooking");
            case "minecraft:stonecutting" ->
                    new RecipeTypeInfo("minecraft:stonecutting", "stonecutter", "stonecutting");
            case "minecraft:smithing_transform" ->
                    new RecipeTypeInfo("minecraft:smithing", "smithing_table", "smithing");
            default -> null;
        };
    }

    private static String root(JarFile jar, String prefix) {
        Set<String> roots = jar.stream().map(JarEntry::getName)
                .filter(name -> name.startsWith(prefix)).map(name -> {
                    int end = name.indexOf('/', prefix.length());
                    return end < 0 ? null : name.substring(0, end + 1);
                }).filter(java.util.Objects::nonNull).collect(java.util.stream.Collectors.toSet());
        return roots.size() == 1 ? roots.iterator().next() : null;
    }

    private static String string(JsonObject object, String key) {
        return object.has(key) && object.get(key).isJsonPrimitive() ? object.get(key).getAsString() : "";
    }

    private static String sanitize(String value) {
        return value.toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9/._-]+", "_");
    }

    private boolean craftEngineCultivationEnabled(String namespace) {
        if (!this.sourceEnabled.test("craftengine-cultivation")) return false;
        String source = switch (namespace) {
            case "farmersdelight" -> "farmersdelight-cultivation";
            case "kaleidoscopecookery" -> "kaleidoscope-cultivation";
            case "customcrops" -> "customcrops-cultivation";
            default -> null;
        };
        return source == null || this.sourceEnabled.test(source);
    }

    private record ItemAmount(String id, int count) {}
    private record IngredientData(int amount, List<RecipeItem> choices) {}
    private record RecipeTypeInfo(String id, String icon, String langKey) {}
}
