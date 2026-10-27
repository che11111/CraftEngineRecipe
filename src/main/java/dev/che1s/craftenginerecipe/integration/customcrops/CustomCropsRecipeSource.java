package dev.che1s.craftenginerecipe.integration.customcrops;

import dev.che1s.craftenginerecipe.api.DisplayRecipe;
import dev.che1s.craftenginerecipe.api.RecipeIngredient;
import dev.che1s.craftenginerecipe.api.RecipeItem;
import dev.che1s.craftenginerecipe.api.RecipeLayout;
import dev.che1s.craftenginerecipe.api.RecipeResult;
import dev.che1s.craftenginerecipe.api.RecipeWorkstation;
import dev.che1s.craftenginerecipe.integration.common.RecipeSource;
import dev.che1s.craftenginerecipe.i18n.IntegrationText;
import org.bukkit.plugin.Plugin;
import org.yaml.snakeyaml.LoaderOptions;
import org.yaml.snakeyaml.Yaml;
import org.yaml.snakeyaml.constructor.SafeConstructor;

import java.io.IOException;
import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.function.BooleanSupplier;
import java.util.function.Function;

/**
 * 读取 CustomCrops 的公开数据文件，并将每种种植盆转换为 CER 合成器。
 * 仅在首次一次性导出时读取这些文件；常规运行只读取 recipes/customcrops/ 下生成的文档。
 */
public final class CustomCropsRecipeSource implements RecipeSource {
    public static final String PLUGIN_NAME = "CustomCrops";
    public static final String SOURCE_ID = "customcrops";

    private static final int SEED_SLOT = 10;
    private static final int PLANT_BLOCK_SLOT = 12;
    private static final List<Integer> RESULT_SLOTS = List.of(14, 15, 23, 24);
    private static final int WATER_SLOT = 36;
    private static final int SPRINKLER_SLOT = 38;
    private static final int FERTILIZER_SLOT = 40;
    private static final int GREENHOUSE_SLOT = 42;
    private static final int SCARECROW_SLOT = 44;
    private static final Set<String> FERTILIZER_TYPES = Set.of(
            "QUALITY", "SPEED_GROW", "YIELD_INCREASE", "VARIATION", "SOIL_RETAIN");
    private static final Map<String, String> SEASONS = Map.of(
            "spring", "春",
            "summer", "夏",
            "autumn", "秋",
            "winter", "冬");
    private static final Map<String, String> ENVIRONMENTS = Map.of(
            "normal", "主世界",
            "world", "主世界",
            "the_nether", "下界",
            "nether", "下界",
            "the_end", "末地",
            "end", "末地");
    private static final Map<String, String> POT_NAMES = Map.of(
            "default", "种植盆",
            "aquatic_crops", "水生盆栽",
            "nether", "下界种植盆",
            "end", "末地种植盆");
    private static final Map<String, String> POT_NAMES_EN = Map.of(
            "default", "Pot",
            "aquatic_crops", "Hydroponic Pot",
            "nether", "Nether Pot",
            "end", "End Pot");
    private static final Map<String, String> FERTILIZER_NAMES = Map.of(
            "QUALITY", "品质",
            "SPEED_GROW", "生长加速",
            "YIELD_INCREASE", "增产",
            "VARIATION", "变异",
            "SOIL_RETAIN", "保湿");
    private static final Map<String, String> VANILLA_NAMES_ZH = Map.of(
            "minecraft:water_bucket", "水桶",
            "minecraft:lava_bucket", "熔岩桶",
            "minecraft:dragon_breath", "龙息",
            "minecraft:glass_bottle", "玻璃瓶",
            "minecraft:bucket", "空桶",
            "minecraft:potion", "药水",
            "minecraft:stick", "木棍",
            "minecraft:dark_oak_log", "深色橡木原木");

    private final Path dataFolder;
    private final BooleanSupplier enabled;
    private final Function<String, String> translations;
    private final Function<String, String> itemNames;
    private final BooleanSupplier chinese;
    private final IntegrationText text;

    public CustomCropsRecipeSource(Plugin source, Function<String, String> translations) {
        this(source, translations, ignored -> null, () -> true, IntegrationText.empty());
    }

    public CustomCropsRecipeSource(Plugin source, Function<String, String> translations,
                                   Function<String, String> itemNames) {
        this(source, translations, itemNames, () -> true, IntegrationText.empty());
    }

    public CustomCropsRecipeSource(Plugin source, Function<String, String> translations,
                                   Function<String, String> itemNames, BooleanSupplier chinese) {
        this(source, translations, itemNames, chinese, IntegrationText.empty());
    }

    public CustomCropsRecipeSource(Plugin source, Function<String, String> translations,
                                   Function<String, String> itemNames, BooleanSupplier chinese,
                                   IntegrationText text) {
        this(source.getDataFolder().toPath(), source::isEnabled, translations, itemNames, chinese, text);
    }

    CustomCropsRecipeSource(Path dataFolder, BooleanSupplier enabled,
                            Function<String, String> translations) {
        this(dataFolder, enabled, translations, ignored -> null, () -> true, IntegrationText.empty());
    }

    CustomCropsRecipeSource(Path dataFolder, BooleanSupplier enabled,
                            Function<String, String> translations,
                            Function<String, String> itemNames) {
        this(dataFolder, enabled, translations, itemNames, () -> true, IntegrationText.empty());
    }

    CustomCropsRecipeSource(Path dataFolder, BooleanSupplier enabled,
                            Function<String, String> translations,
                            Function<String, String> itemNames, BooleanSupplier chinese) {
        this(dataFolder, enabled, translations, itemNames, chinese, IntegrationText.empty());
    }

    CustomCropsRecipeSource(Path dataFolder, BooleanSupplier enabled,
                            Function<String, String> translations,
                            Function<String, String> itemNames, BooleanSupplier chinese,
                            IntegrationText text) {
        this.dataFolder = dataFolder.toAbsolutePath().normalize();
        this.enabled = enabled;
        this.translations = translations == null ? ignored -> null : translations;
        this.itemNames = itemNames == null ? ignored -> null : itemNames;
        this.chinese = chinese == null ? () -> true : chinese;
        this.text = text == null ? IntegrationText.empty() : text;
    }

    @Override
    public String id() {
        return SOURCE_ID;
    }

    @Override
    public boolean ready() {
        return this.enabled.getAsBoolean()
                && Files.isDirectory(this.dataFolder.resolve("contents/crops"), LinkOption.NOFOLLOW_LINKS)
                && Files.isDirectory(this.dataFolder.resolve("contents/pots"), LinkOption.NOFOLLOW_LINKS);
    }

    @Override
    public long revision() {
        long hash = 1125899906842597L;
        for (Path directory : List.of(this.dataFolder.resolve("config.yml"),
                this.dataFolder.resolve("contents/crops"),
                this.dataFolder.resolve("contents/pots"),
                this.dataFolder.resolve("contents/fertilizers"),
                this.dataFolder.resolve("contents/watering-cans"))) {
            hash = this.fingerprint(directory, hash);
        }
        return hash;
    }

    @Override
    public List<DisplayRecipe> capture() {
        if (!ready()) throw new IllegalStateException("CustomCrops crop or pot configuration is unavailable");
        MachineSettings mechanics = this.mechanics();
        WateringTiming wateringTiming = this.wateringTiming();
        List<FertilizerData> fertilizers = this.fertilizers();
        List<WateringCanData> wateringCans = this.wateringCans();
        List<SprinklerData> sprinklers = this.sprinklers();
        List<PotData> pots = this.pots();
        List<CropData> crops = this.crops();

        List<DisplayRecipe> recipes = new ArrayList<>();
        for (PotData pot : pots) {
            for (CropData crop : crops) {
                if (!accepts(crop.potWhitelist(), pot.id())) continue;
                OutputData output = this.output(crop);
                if (output.results().isEmpty()) continue;
                recipes.add(this.recipe(pot, crop, output, mechanics, wateringTiming,
                        compatibleFertilizers(fertilizers, pot.id()),
                        compatibleWateringCans(wateringCans, pot.id()),
                        compatibleSprinklers(sprinklers, pot.id())));
            }
        }
        recipes.sort(Comparator.comparing(DisplayRecipe::id));
        return List.copyOf(recipes);
    }

    private DisplayRecipe recipe(PotData pot, CropData crop, OutputData output,
                                 MachineSettings mechanics, WateringTiming wateringTiming,
                                 List<FertilizerData> fertilizers,
                                 List<WateringCanData> wateringCans,
                                 List<SprinklerData> sprinklers) {
        String potName = this.potName(pot);
        String cropName = this.displayName(output.results().getFirst().item().id());
        String stationName = "<white>" + plain(this.stationName(pot)) + "</white>";
        String title = this.recipeTitle(pot, cropName);
        RecipeLayout layout = new RecipeLayout(6, title, -1,
                -1, -1, -1, -1);

        List<RecipeIngredient> ingredients = new ArrayList<>();
        List<String> seedLore = seedLore(crop, mechanics);
        List<RecipeItem> seeds = crop.seeds().stream().map(id -> item(id, seedLore)).toList();
        ingredients.add(new RecipeIngredient(seeds, 1, SEED_SLOT, RecipeIngredient.Role.INGREDIENT));

        // CustomCrops 没有独立的土壤条件；种植盆本身就是作物所在的“种植方块”。
        List<String> plantBlockLore = new ArrayList<>();
        plantBlockLore.add(this.t("lore.plant_block",
                "<#B8C0CC>种植方块：</#B8C0CC><white>{pot}</white>", "pot", potName));
        plantBlockLore.addAll(potWaterLore(pot, wateringTiming));
        ingredients.add(new RecipeIngredient(
                List.of(item(potIcon(pot), plantBlockLore)), 1, PLANT_BLOCK_SLOT,
                RecipeIngredient.Role.INFO));

        if (pot.maxFertilizers() > 0 && !fertilizers.isEmpty()) {
            List<RecipeItem> choices = fertilizers.stream()
                    .map(value -> item(value.itemId(), fertilizerLore(value, pot)))
                    .toList();
            ingredients.add(new RecipeIngredient(choices, 1, FERTILIZER_SLOT, RecipeIngredient.Role.INFO));
        }

        List<RecipeItem> waterChoices = wateringCans.stream()
                .map(value -> item(value.itemId(), wateringCanLore(value, pot, wateringTiming)))
                .collect(java.util.stream.Collectors.toCollection(ArrayList::new));
        for (FillMethodData fillMethod : pot.fillMethods()) {
            if (waterChoices.stream().anyMatch(value -> value.id().equals(fillMethod.itemId()))) continue;
            waterChoices.add(item(fillMethod.itemId(),
                    fillMethodLore(fillMethod, pot, wateringTiming)));
        }
        if (waterChoices.isEmpty()) {
            waterChoices.add(item("minecraft:water_bucket", potWaterLore(pot, wateringTiming)));
        }
        ingredients.add(new RecipeIngredient(waterChoices, 1, WATER_SLOT, RecipeIngredient.Role.INFO));

        if (!sprinklers.isEmpty()) {
            List<RecipeItem> choices = sprinklers.stream()
                    .map(value -> item(value.itemId(), sprinklerLore(value, pot, wateringTiming)))
                    .toList();
            ingredients.add(new RecipeIngredient(choices, 1, SPRINKLER_SLOT, RecipeIngredient.Role.INFO));
        }

        if (mechanics.greenhouse().enabled() && crop.seasonalDeath()) {
            ingredients.add(decoration(mechanics.greenhouse().id(), GREENHOUSE_SLOT,
                    greenhouseLore(mechanics.greenhouse())));
        }
        if (mechanics.scarecrow().enabled() && crop.crowAttack()) {
            ingredients.add(decoration(mechanics.scarecrow().id(), SCARECROW_SLOT,
                    scarecrowLore(mechanics.scarecrow())));
        }

        RecipeWorkstation station = new RecipeWorkstation("customcrops:pot/" + pot.id(),
                this.item(potIcon(pot), potWaterLore(pot, wateringTiming)), stationName);
        String recipeId = "customcrops:pot/" + pot.id() + "/" + crop.id();
        return new DisplayRecipe(recipeId, station, layout, ingredients, output.results(),
                List.of(), null, player -> true);
    }

    private RecipeIngredient decoration(String id, int slot, List<String> lore) {
        return new RecipeIngredient(List.of(item(id, lore)), 1, slot, RecipeIngredient.Role.INFO);
    }

    private OutputData output(CropData crop) {
        Map<String, Object> points = map(crop.raw().get("points"));
        List<String> pointKeys = points.keySet().stream()
                .filter(CustomCropsRecipeSource::isInteger)
                .sorted(Comparator.comparingInt(CustomCropsRecipeSource::integer).reversed())
                .toList();
        for (String point : pointKeys) {
            int value = integer(point);
            if (value > crop.maxPoints()) continue;
            Map<String, Object> stage = map(points.get(point));
            Object breakActions = path(stage, "events", "break");
            List<Drop> drops = drops(breakActions);
            if (!drops.isEmpty()) return output(crop, drops);
        }
        // 自定义作物可以省略满点数事件，并依赖继承来的破坏动作。
        List<Drop> drops = drops(crop.raw().get("events"));
        return drops.isEmpty() ? OutputData.EMPTY : output(crop, drops);
    }

    private OutputData output(CropData crop, List<Drop> drops) {
        List<Drop> quality = drops.stream().filter(Drop::quality).toList();
        List<Drop> ordinary = drops.stream().filter(drop -> !drop.quality())
                .filter(drop -> crop.seeds().stream().noneMatch(drop.itemId()::equals))
                .toList();
        List<Drop> selected = new ArrayList<>();
        selected.addAll(quality.stream().limit(1).toList());
        for (Drop drop : ordinary) {
            if (selected.stream().noneMatch(existing -> existing.itemId().equals(drop.itemId()))) selected.add(drop);
            if (selected.size() >= 5) break;
        }
        if (selected.isEmpty()) selected.addAll(quality);
        if (selected.isEmpty()) return OutputData.EMPTY;

        List<String> qualityNames = quality.stream()
                .map(drop -> plain(this.displayName(drop.itemId()))).toList();
        List<RecipeResult> results = new ArrayList<>();
        for (int index = 0; index < selected.size(); index++) {
            Drop drop = selected.get(index);
            String note = this.t("note.mature", "成熟产物");
            if (quality.contains(drop) && quality.size() > 1) {
                note += this.t("note.quality", " · 品质：{qualities}",
                        "qualities", String.join(" / ", qualityNames));
            } else if (index > 0) {
                note += this.t("note.byproduct", " · 副产物");
            }
            if (drop.minimum() == drop.maximum()) {
                note += this.t("note.amount_exact", " · 数量 {amount}", "amount", drop.minimum());
            } else {
                note += this.t("note.amount_range", " · 数量 {min}～{max}",
                        "min", drop.minimum(), "max", drop.maximum());
            }
            results.add(new RecipeResult(item(drop.itemId(), resultLore(crop, drop, qualityNames)),
                    Math.min(64, Math.max(1, drop.minimum())),
                    results.size() < RESULT_SLOTS.size() ? RESULT_SLOTS.get(results.size()) : -1, note));
        }
        return new OutputData(List.copyOf(results));
    }

    private List<Drop> drops(Object node) {
        List<Drop> out = new ArrayList<>();
        collectDrops(node, out);
        return List.copyOf(out);
    }

    private void collectDrops(Object node, List<Drop> out) {
        if (node instanceof Collection<?> collection) {
            for (Object value : collection) collectDrops(value, out);
            return;
        }
        if (!(node instanceof Map<?, ?> raw)) return;
        Map<String, Object> action = map(raw);
        String type = string(action.get("type"));
        Map<String, Object> value = map(action.get("value"));
        if ("quality-crops".equalsIgnoreCase(type)) {
            int minimum = number(value.get("min"), 1);
            int maximum = number(value.get("max"), minimum);
            Map<String, Object> products = map(value.get("items"));
            products.keySet().stream().filter(CustomCropsRecipeSource::isInteger)
                    .sorted(Comparator.comparingInt(CustomCropsRecipeSource::integer))
                    .forEach(key -> {
                        String id = normalizeItemId(products.get(key));
                        if (id != null) out.add(new Drop(id, minimum, maximum, true));
                    });
        } else if ("drop-item".equalsIgnoreCase(type)) {
            String id = normalizeItemId(value.get("item"));
            int minimum = number(value.get("min"), 1);
            int maximum = number(value.get("max"), minimum);
            if (id != null) out.add(new Drop(id, minimum, maximum, false));
        }
        for (Object child : action.values()) {
            if (child != action && (child instanceof Map<?, ?> || child instanceof Collection<?>)) {
                collectDrops(child, out);
            }
        }
    }

    private MachineSettings mechanics() {
        Path file = this.dataFolder.resolve("config.yml");
        Map<String, Object> root = Files.isRegularFile(file) ? readYaml(file) : Map.of();
        Map<String, Object> mechanics = map(root.get("mechanics"));
        BlockRule scarecrow = blockRule(mechanics.get("scarecrow"), "customcrops:scarecrow");
        BlockRule greenhouse = blockRule(mechanics.get("greenhouse"), "customcrops:greenhouse_glass");
        return new MachineSettings(scarecrow, greenhouse);
    }

    private WateringTiming wateringTiming() {
        Path file = this.dataFolder.resolve("config.yml");
        Map<String, Object> root = Files.isRegularFile(file) ? readYaml(file) : Map.of();
        Map<String, Object> setting = map(path(root, "worlds", "settings", "_DEFAULT_"));
        int minimumUnit = number(setting.get("min-tick-unit"), 300);
        String potMode = string(path(setting, "pot", "mode"));
        int potInterval = Math.max(1, number(path(setting, "pot", "tick-interval"), 2));
        int sprinklerInterval = Math.max(1, number(path(setting, "sprinkler", "tick-interval"), 2));
        return new WateringTiming(minimumUnit, potMode, potInterval, sprinklerInterval);
    }

    private BlockRule blockRule(Object raw, String fallback) {
        Map<String, Object> map = map(raw);
        boolean enabled = bool(map.get("enable"), true);
        String id = firstItem(map.get("id"), fallback);
        int range = number(map.get("range"), 0);
        boolean protectChunk = bool(map.get("protect-chunk"), false);
        return new BlockRule(enabled, id, range, protectChunk);
    }

    private List<PotData> pots() {
        List<PotData> out = new ArrayList<>();
        definitions(this.dataFolder.resolve("contents/pots"), (id, raw, file) -> {
            String dryIcon = firstItem(path(raw, "base", "dry"), "minecraft:decorated_pot");
            String wetIcon = firstItem(path(raw, "base", "wet"), dryIcon);
            out.add(new PotData(id, dryIcon, wetIcon, number(raw.get("max-fertilizers"), 0),
                    number(raw.get("max-water-storage"), 0),
                    bool(raw.get("absorb-rainwater"), false),
                    bool(raw.get("absorb-nearby-water"), false),
                    fillMethods(raw.get("fill-method"))));
        });
        return List.copyOf(out);
    }

    private static List<FillMethodData> fillMethods(Object raw) {
        List<FillMethodData> out = new ArrayList<>();
        for (Object value : map(raw).values()) {
            Map<String, Object> method = map(value);
            String item = normalizeItemId(method.get("item"));
            if (item == null) continue;
            String returned = firstItem(method.get("return"), null);
            int returnedAmount = Math.max(1, number(method.get("return-amount"), 1));
            out.add(new FillMethodData(item, Math.max(1, number(method.get("amount"), 1)),
                    returned, returnedAmount));
        }
        return List.copyOf(out);
    }

    private List<CropData> crops() {
        List<CropData> out = new ArrayList<>();
        definitions(this.dataFolder.resolve("contents/crops"), (id, raw, file) -> {
            List<String> seeds = itemIds(raw.get("seed"));
            if (seeds.isEmpty()) return;
            Set<String> pots = new LinkedHashSet<>(strings(raw.get("pot-whitelist")));
            Set<String> seasons = new LinkedHashSet<>();
            Set<String> environments = new LinkedHashSet<>();
            collectConditionValues(raw.get("requirements"), "season", seasons);
            collectConditionValues(raw.get("grow-conditions"), "suitable-season", seasons);
            collectConditionValues(raw.get("requirements"), "environment", environments);
            collectConditionValues(raw.get("grow-conditions"), "environment", environments);
            boolean seasonalDeath = containsCondition(raw.get("death-conditions"), "unsuitable-season");
            boolean crowAttack = containsCondition(raw.get("death-conditions"), "crow-attack");
            out.add(new CropData(id, List.copyOf(seeds), Set.copyOf(pots), List.copyOf(seasons),
                    List.copyOf(environments), number(raw.get("max-points"), 0), seasonalDeath, crowAttack, raw));
        });
        return List.copyOf(out);
    }

    private List<FertilizerData> fertilizers() {
        List<FertilizerData> out = new ArrayList<>();
        for (Path file : yamlFiles(this.dataFolder.resolve("contents/fertilizers"))) {
            Map<String, Object> root = readYaml(file);
            for (var entry : root.entrySet()) {
                Map<String, Object> raw = map(entry.getValue());
                String type = string(raw.get("type")).toUpperCase(Locale.ROOT);
                String item = normalizeItemId(raw.get("item"));
                if (!FERTILIZER_TYPES.contains(type) || item == null) continue;
                out.add(new FertilizerData(item, type, number(raw.get("times"), 0),
                        bool(raw.get("before-plant"), false),
                        Set.copyOf(strings(raw.get("pot-whitelist")))));
            }
        }
        return List.copyOf(out);
    }

    private List<WateringCanData> wateringCans() {
        List<WateringCanData> out = new ArrayList<>();
        for (Path file : yamlFiles(this.dataFolder.resolve("contents/watering-cans"))) {
            Map<String, Object> root = readYaml(file);
            for (var entry : root.entrySet()) {
                Map<String, Object> raw = map(entry.getValue());
                String item = normalizeItemId(raw.get("item"));
                if (item == null) continue;
                out.add(new WateringCanData(item, number(raw.get("capacity"), 0),
                        number(raw.get("water"), 1),
                        Set.copyOf(strings(raw.get("pot-whitelist")))));
            }
        }
        return List.copyOf(out);
    }

    private List<SprinklerData> sprinklers() {
        List<SprinklerData> out = new ArrayList<>();
        for (Path file : yamlFiles(this.dataFolder.resolve("contents/sprinklers"))) {
            Map<String, Object> root = readYaml(file);
            for (var entry : root.entrySet()) {
                Map<String, Object> raw = map(entry.getValue());
                String item = firstItem(raw.get("3D-item-with-water"),
                        firstItem(raw.get("3D-item"), firstItem(raw.get("2D-item"), null)));
                if (item == null) continue;
                out.add(new SprinklerData(item,
                        number(raw.get("range"), 0),
                        number(raw.get("storage"), 0),
                        number(raw.get("water"), 1),
                        bool(raw.get("infinite"), false),
                        Set.copyOf(strings(raw.get("pot-whitelist")))));
            }
        }
        return List.copyOf(out);
    }

    private static List<FertilizerData> compatibleFertilizers(List<FertilizerData> values, String pot) {
        return values.stream().filter(value -> accepts(value.potWhitelist(), pot)).toList();
    }

    private static List<WateringCanData> compatibleWateringCans(List<WateringCanData> values, String pot) {
        return values.stream().filter(value -> accepts(value.potWhitelist(), pot)).toList();
    }

    private static List<SprinklerData> compatibleSprinklers(List<SprinklerData> values, String pot) {
        return values.stream().filter(value -> accepts(value.potWhitelist(), pot)).toList();
    }

    private static boolean accepts(Set<String> whitelist, String id) {
        return whitelist.isEmpty() || whitelist.contains("*") || whitelist.contains(id);
    }

    private static boolean containsCondition(Object node, String wantedType) {
        if (node instanceof Collection<?> collection) {
            return collection.stream().anyMatch(value -> containsCondition(value, wantedType));
        }
        if (!(node instanceof Map<?, ?> raw)) return false;
        Map<String, Object> map = map(raw);
        if (wantedType.equalsIgnoreCase(string(map.get("type")))) return true;
        return map.values().stream().anyMatch(value -> containsCondition(value, wantedType));
    }

    private void collectConditionValues(Object node, String wantedType, Set<String> values) {
        if (node instanceof Collection<?> collection) {
            for (Object value : collection) collectConditionValues(value, wantedType, values);
            return;
        }
        if (!(node instanceof Map<?, ?> raw)) return;
        Map<String, Object> map = map(raw);
        String type = string(map.get("type"));
        if (wantedType.equalsIgnoreCase(type)) {
            strings(map.get("value")).stream().map(value -> value.toLowerCase(Locale.ROOT)).forEach(values::add);
        }
        for (Object value : map.values()) {
            if (value instanceof Map<?, ?> || value instanceof Collection<?>) {
                collectConditionValues(value, wantedType, values);
            }
        }
    }

    private void definitions(Path directory, DefinitionConsumer consumer) {
        for (Path file : yamlFiles(directory)) {
            Map<String, Object> root = readYaml(file);
            for (var entry : root.entrySet()) {
                if (!(entry.getValue() instanceof Map<?, ?>)) continue;
                consumer.accept(entry.getKey(), map(entry.getValue()), file);
            }
        }
    }

    private static List<Path> yamlFiles(Path directory) {
        if (!Files.isDirectory(directory)) return List.of();
        try (var paths = Files.walk(directory)) {
            return paths.filter(path -> Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS))
                    .filter(CustomCropsRecipeSource::isYaml)
                    .sorted().toList();
        } catch (IOException error) {
            throw new IllegalStateException("Failed to enumerate CustomCrops files in " + directory, error);
        }
    }

    private static Map<String, Object> readYaml(Path file) {
        LoaderOptions options = new LoaderOptions();
        options.setAllowDuplicateKeys(false);
        try (Reader reader = Files.newBufferedReader(file, StandardCharsets.UTF_8)) {
            Object loaded = new Yaml(new SafeConstructor(options)).load(reader);
            return loaded == null ? Map.of() : map(loaded);
        } catch (IOException | RuntimeException error) {
            throw new IllegalStateException("Failed to read CustomCrops file " + file + ": " + error.getMessage(), error);
        }
    }

    private String displayName(String itemId) {
        String id = normalizeItemId(itemId);
        if (id == null) return itemId;
        String direct = this.translations.apply("item." + id.replace(':', '.'));
        if (direct != null && !direct.isBlank()) return direct;
        String namespaced = this.translations.apply("item." + id);
        if (namespaced != null && !namespaced.isBlank()) return namespaced;
        String engineName = this.itemNames.apply(id);
        if (engineName != null && !engineName.isBlank()) return engineName;
        String configured = this.text.text("vanilla." + id.replace(':', '.'),
                this.chinese.getAsBoolean() ? VANILLA_NAMES_ZH.getOrDefault(id, "") : "");
        if (!configured.isBlank()) return configured;
        int separator = id.indexOf(':');
        String path = separator >= 0 ? id.substring(separator + 1) : id;
        return path.replace('_', ' ');
    }

    private String potName(PotData pot) {
        String known = (this.chinese.getAsBoolean() ? POT_NAMES : POT_NAMES_EN)
                .get(pot.id().toLowerCase(Locale.ROOT));
        String fallback = known == null ? this.displayName(pot.dryIcon()) : known;
        return this.text.text("pot." + pot.id().toLowerCase(Locale.ROOT), fallback);
    }

    private String stationName(PotData pot) {
        String name = this.potName(pot);
        String fallback = this.chinese.getAsBoolean() ? "星露谷·" + name : "CustomCrops·" + name;
        return this.text.text("title.station", fallback, "pot", name);
    }

    private String recipeTitle(PotData pot, String cropName) {
        String plainCrop = plain(cropName);
        String suffix = switch (pot.id().toLowerCase(Locale.ROOT)) {
            case "default" -> this.text.text("title.suffix.default",
                    this.chinese.getAsBoolean() ? "种植盆" : "");
            case "aquatic_crops" -> this.text.text("title.suffix.aquatic_crops",
                    this.chinese.getAsBoolean() ? "水生盆栽" : " Hydroponic Pot");
            case "nether" -> this.text.text("title.suffix.nether",
                    this.chinese.getAsBoolean() ? "下界种植盆" : " Nether Pot");
            case "end" -> this.text.text("title.suffix.end",
                    this.chinese.getAsBoolean() ? "末地种植盆" : " End Pot");
            default -> this.potName(pot);
        };
        String fallback = this.chinese.getAsBoolean()
                ? "星露谷·" + plainCrop + suffix
                : "CustomCrops·" + plainCrop + suffix;
        String title = this.text.text("title.recipe", fallback,
                "crop", plainCrop, "suffix", suffix);
        return "<#F4F6F8><bold>" + title + "</bold></#F4F6F8>";
    }

    private String returnItemName(String itemId) {
        if ("minecraft:bucket".equals(itemId)) {
            return this.text.text("vanilla.minecraft.bucket",
                    this.chinese.getAsBoolean() ? "空桶" : "Empty Bucket");
        }
        return this.displayName(itemId);
    }

    private static String potIcon(PotData pot) {
        return pot.id().equalsIgnoreCase("aquatic_crops") ? pot.wetIcon() : pot.dryIcon();
    }

    private List<String> seedLore(CropData crop, MachineSettings mechanics) {
        List<String> lore = new ArrayList<>();
        if (!crop.seasons().isEmpty()) {
            lore.add(this.t("lore.season", "<#98C69A>适宜季节：</#98C69A><white>{seasons}</white>",
                    "seasons", this.labels(crop.seasons(), SEASONS, "season.")));
        } else if (crop.environments().isEmpty()) {
            lore.add(this.t("lore.season_none",
                    "<#98C69A>季节限制：</#98C69A><white>无特殊季节要求</white>"));
        }
        if (!crop.environments().isEmpty()) {
            lore.add(this.t("lore.environment", "<#98C69A>适宜环境：</#98C69A><white>{environments}</white>",
                    "environments", this.labels(crop.environments(), ENVIRONMENTS, "environment.")));
        }
        if (crop.seasonalDeath() && mechanics.greenhouse().enabled()) {
            lore.add(this.t("lore.greenhouse",
                    "<#D8B278>非适宜季节：</#D8B278><white>在作物顶部放置{item}，可避免季节对作物的影响。</white>",
                    "item", plain(this.displayName(mechanics.greenhouse().id()))));
        }
        lore.add(this.t("lore.mature_stage",
                "<#B8C0CC>成熟阶段：</#B8C0CC><white>{points} 点</white>",
                "points", crop.maxPoints()));
        return List.copyOf(lore);
    }

    private List<String> potWaterLore(PotData pot, WateringTiming timing) {
        List<String> lore = new ArrayList<>();
        lore.add(this.t("lore.water_storage",
                "<#B8C0CC>种植盆储水量：</#B8C0CC><white>{points} 点</white>",
                "points", pot.storage()));
        if (pot.storage() <= 0) {
            lore.add(this.t("lore.water_disabled",
                    "<#B8C0CC>浇水：</#B8C0CC><white>此种植盆不启用插件储水机制</white>"));
            return List.copyOf(lore);
        }
        lore.add(this.t("lore.water_consumption",
                "<#B8C0CC>自然消耗：</#B8C0CC><white>每 {updates} 次种植盆更新消耗 1 点水</white>",
                "updates", timing.potInterval()));
        if (timing.hasScheduledPotUpdate()) {
            long secondsPerLoss = (long) timing.minimumUnit() * timing.potInterval();
            long fullSeconds = secondsPerLoss * pot.storage();
            String prefix = this.t(timing.potMode().equalsIgnoreCase("ALL")
                            ? "duration.scheduled" : "duration.default",
                    timing.potMode().equalsIgnoreCase("ALL") ? "按计划更新至少" : "默认约");
            lore.add(this.t("lore.water_interval",
                    "<#B8C0CC>浇水间隔：</#B8C0CC><white>{prefix} {duration}消耗 1 点水</white>",
                    "prefix", prefix, "duration", formatDuration(secondsPerLoss)));
            lore.add(this.t("lore.water_full",
                    "<#B8C0CC>满水持续时间：</#B8C0CC><white>{prefix} {duration}</white>",
                    "prefix", prefix, "duration", formatDuration(fullSeconds)));
        } else {
            lore.add(this.t("lore.water_random",
                    "<#B8C0CC>浇水间隔：</#B8C0CC><white>由随机 tick 决定，无固定秒数</white>"));
        }
        if (pot.absorbRain()) {
            lore.add(this.t("lore.rain",
                    "<#98C69A>降雨：</#98C69A><white>下雨时停止消耗并自动补水</white>"));
        }
        if (pot.absorbNearbyWater()) {
            lore.add(this.t("lore.nearby_water",
                    "<#98C69A>近水：</#98C69A><white>附近 4 格内有水时停止消耗并自动补水</white>"));
        }
        lore.add(this.t("lore.settings_note", "<#8793A5>实际周期以相关设置为准</#8793A5>"));
        return List.copyOf(lore);
    }

    private List<String> wateringCanLore(WateringCanData can, PotData pot, WateringTiming timing) {
        List<String> lore = new ArrayList<>();
        lore.add(this.t("lore.can_capacity",
                "<#B8C0CC>水壶容量：</#B8C0CC><white>{amount} 点水</white>", "amount", can.capacity()));
        lore.add(this.t("lore.can_refill",
                "<#B8C0CC>每次补水：</#B8C0CC><white>{amount} 点</white>", "amount", can.water()));
        lore.add(this.t("lore.compatible_pot",
                "<#B8C0CC>兼容种植盆：</#B8C0CC><white>{pot}</white>", "pot", this.potName(pot)));
        lore.add(this.t("lore.can_refill_source", "<#8793A5>右键水源可为水壶补水</#8793A5>"));
        lore.addAll(potWaterLore(pot, timing));
        return List.copyOf(lore);
    }

    private List<String> fillMethodLore(FillMethodData fill, PotData pot, WateringTiming timing) {
        List<String> lore = new ArrayList<>();
        lore.add(this.t("lore.fill_usage",
                "<#B8C0CC>使用方式：</#B8C0CC><white>手持{item}右键{pot}，每次补充 {amount} 点</white>",
                "item", plain(this.displayName(fill.itemId())),
                "pot", this.potName(pot), "amount", fill.amount()));
        if (fill.returnedItemId() != null) {
            String amount = fill.returnedAmount() > 1
                    ? this.t("lore.return_item_count", "{amount} 个 ", "amount", fill.returnedAmount())
                    : "";
            lore.add(this.t("lore.return_item",
                    "<#B8C0CC>返还物品：</#B8C0CC><white>{amount}{item}</white>",
                    "amount", amount, "item", plain(this.returnItemName(fill.returnedItemId()))));
        }
        lore.addAll(potWaterLore(pot, timing));
        return List.copyOf(lore);
    }

    private List<String> fertilizerLore(FertilizerData fertilizer, PotData pot) {
        List<String> lore = new ArrayList<>();
        String type = this.text.text("fertilizer." + fertilizer.type().toLowerCase(Locale.ROOT),
                FERTILIZER_NAMES.getOrDefault(fertilizer.type(), fertilizer.type()));
        lore.add(this.t("lore.fertilizer_optional",
                "<#D8B278>可选肥料 / 激素：</#D8B278><white>{type}</white>", "type", type));
        if (fertilizer.times() > 0) {
            lore.add(this.t("lore.fertilizer_duration",
                    "<#B8C0CC>有效期：</#B8C0CC><white>{times} 次作物更新</white>",
                    "times", fertilizer.times()));
        }
        if (fertilizer.beforePlant()) {
            lore.add(this.t("lore.fertilizer_before",
                    "<#D8B278>使用时机：</#D8B278><white>需要在播种前使用</white>"));
        }
        lore.add(this.t("lore.fertilizer_limit",
                "<#B8C0CC>使用上限：</#B8C0CC><white>每盆最多 {amount} 个</white>",
                "amount", pot.maxFertilizers()));
        return List.copyOf(lore);
    }

    private List<String> sprinklerLore(SprinklerData sprinkler, PotData pot, WateringTiming timing) {
        List<String> lore = new ArrayList<>();
        lore.add(this.t("lore.sprinkler_auto",
                "<#98C69A>自动灌溉：</#98C69A><white>为范围内兼容种植盆补水</white>"));
        lore.add(this.t("lore.compatible_pot",
                "<#B8C0CC>兼容种植盆：</#B8C0CC><white>{pot}</white>", "pot", this.potName(pot)));
        lore.add(this.t("lore.sprinkler_range",
                "<#B8C0CC>工作范围：</#B8C0CC><white>{range} 格</white>", "range", sprinkler.range()));
        lore.add(this.t("lore.sprinkler_refill",
                "<#B8C0CC>每次补水：</#B8C0CC><white>{amount} 点</white>", "amount", sprinkler.water()));
        String storage = sprinkler.infinite()
                ? this.t("lore.sprinkler_infinite", "无限")
                : this.t("lore.points", "{points} 点", "points", sprinkler.storage());
        lore.add(this.t("lore.sprinkler_storage",
                "<#B8C0CC>储水量：</#B8C0CC><white>{storage}</white>", "storage", storage));
        lore.add(this.t("lore.sprinkler_cycle",
                "<#8793A5>工作周期：每 {updates} 次灌溉器更新</#8793A5>",
                "updates", timing.sprinklerInterval()));
        return List.copyOf(lore);
    }

    private List<String> greenhouseLore(BlockRule rule) {
        List<String> lore = new ArrayList<>();
        lore.add(this.t("lore.greenhouse_protect",
                "<#98C69A>季节保护：</#98C69A><white>防止非适宜季节导致作物枯萎</white>"));
        lore.add(this.t("lore.greenhouse_place",
                "<#B8C0CC>放置位置：</#B8C0CC><white>作物顶部或有效范围内</white>"));
        lore.add(this.t("lore.scope", "<#B8C0CC>生效范围：</#B8C0CC><white>{scope}</white>",
                "scope", this.scope(rule)));
        return List.copyOf(lore);
    }

    private List<String> scarecrowLore(BlockRule rule) {
        List<String> lore = new ArrayList<>();
        lore.add(this.t("lore.scarecrow_protect",
                "<#98C69A>乌鸦防护：</#98C69A><white>防止附近作物受到乌鸦破坏</white>"));
        lore.add(this.t("lore.scarecrow_place",
                "<#B8C0CC>放置位置：</#B8C0CC><white>种植盆附近</white>"));
        lore.add(this.t("lore.scope", "<#B8C0CC>生效范围：</#B8C0CC><white>{scope}</white>",
                "scope", this.scope(rule)));
        return List.copyOf(lore);
    }

    private List<String> resultLore(CropData crop, Drop drop, List<String> qualityNames) {
        List<String> lore = new ArrayList<>();
        if (!crop.seasons().isEmpty()) {
            lore.add(this.t("lore.season", "<#98C69A>适宜季节：</#98C69A><white>{seasons}</white>",
                    "seasons", this.labels(crop.seasons(), SEASONS, "season.")));
        }
        if (!crop.environments().isEmpty()) {
            lore.add(this.t("lore.environment", "<#98C69A>适宜环境：</#98C69A><white>{environments}</white>",
                    "environments", this.labels(crop.environments(), ENVIRONMENTS, "environment.")));
        }
        lore.add(this.t("lore.mature_stage",
                "<#B8C0CC>成熟阶段：</#B8C0CC><white>{points} 点</white>", "points", crop.maxPoints()));
        String amount = drop.minimum() == drop.maximum()
                ? String.valueOf(drop.minimum())
                : this.t("amount.range", "{min}～{max}", "min", drop.minimum(), "max", drop.maximum());
        lore.add(this.t("lore.harvest_amount",
                "<#B8C0CC>收获数量：</#B8C0CC><white>{amount}</white>", "amount", amount));
        if (qualityNames.size() > 1) {
            lore.add(this.t("lore.quality_candidates",
                    "<#B8C0CC>品质候选：</#B8C0CC><white>{qualities}</white>",
                    "qualities", String.join(this.t("separator", "、"), qualityNames)));
            lore.add(this.t("lore.quality_chance",
                    "<#B8C0CC>品质概率：</#B8C0CC><white>受 CustomCrops 品质比例和肥料影响</white>"));
        }
        return List.copyOf(lore);
    }

    private RecipeItem item(String id, List<String> lore) {
        String normalized = normalizeItemId(id);
        if (normalized == null) throw new IllegalArgumentException("Invalid CustomCrops item id: " + id);
        String provider = normalized.startsWith("minecraft:") ? "minecraft" : "craftengine";
        return new RecipeItem(provider, normalized, null, null, lore);
    }

    private String scope(BlockRule rule) {
        return rule.protectChunk()
                ? this.t("lore.whole_chunk", "整个区块")
                : this.t("lore.blocks", "{range} 格", "range", rule.range());
    }

    private String formatDuration(long seconds) {
        if (seconds < 60) return this.t("time.seconds", "{value} 秒", "value", seconds);
        if (seconds % 60 == 0) {
            long minutes = seconds / 60;
            if (minutes < 60) return this.t("time.minutes", "{value} 分钟", "value", minutes);
            if (minutes % 60 == 0) {
                return this.t("time.hours", "{value} 小时", "value", minutes / 60);
            }
            return this.t("time.hours_minutes", "{hours} 小时 {minutes} 分钟",
                    "hours", minutes / 60, "minutes", minutes % 60);
        }
        return this.t("time.seconds", "{value} 秒", "value", seconds);
    }

    private long fingerprint(Path path, long seed) {
        long hash = seed;
        if (!Files.exists(path, LinkOption.NOFOLLOW_LINKS)) return hash * 31;
        try (var paths = Files.walk(path)) {
            for (Path value : paths.filter(item -> Files.isRegularFile(item, LinkOption.NOFOLLOW_LINKS))
                    .sorted().toList()) {
                hash = hash * 31 + this.dataFolder.relativize(value).toString().hashCode();
                hash = hash * 31 + Files.getLastModifiedTime(value, LinkOption.NOFOLLOW_LINKS).toMillis();
                hash = hash * 31 + Files.size(value);
            }
            return hash;
        } catch (IOException error) {
            return hash * 31;
        }
    }

    private String t(String key, String fallback, Object... arguments) {
        return this.text.text(key, fallback, arguments);
    }

    private String labels(List<String> values, Map<String, String> known, String keyPrefix) {
        return values.stream().map(value -> {
                    String lower = value.toLowerCase(Locale.ROOT);
                    return this.text.text(keyPrefix + lower, known.getOrDefault(lower, value));
                }).collect(java.util.stream.Collectors.joining(this.t("separator", "、")));
    }

    private static String plain(String value) {
        return value == null ? "" : value.replaceAll("<[^>]+>", "").replace("\\<", "<").trim();
    }

    private static String firstItem(Object raw, String fallback) {
        List<String> values = itemIds(raw);
        return values.isEmpty() ? normalizeItemId(fallback) : values.getFirst();
    }

    private static List<String> itemIds(Object raw) {
        List<String> values = strings(raw).stream().map(CustomCropsRecipeSource::normalizeItemId)
                .filter(java.util.Objects::nonNull).toList();
        return values.isEmpty() ? List.of() : values;
    }

    private static String normalizeItemId(Object value) {
        if (value == null) return null;
        String raw = String.valueOf(value).trim();
        if (raw.isEmpty()) return null;
        String id = raw.toLowerCase(Locale.ROOT);
        if (id.contains(":")) return id;
        // CustomCrops 配置接受 WATER_BUCKET 等 Bukkit Material 常量。
        if (raw.matches("[A-Z0-9_]+")) return "minecraft:" + id;
        return id.matches("[a-z0-9_./-]+") ? "customcrops:" + id : null;
    }

    private static List<String> strings(Object raw) {
        if (raw == null) return List.of();
        if (raw instanceof Collection<?> collection) {
            List<String> values = new ArrayList<>();
            for (Object value : collection) values.addAll(strings(value));
            return List.copyOf(values);
        }
        if (raw instanceof Map<?, ?> map) {
            List<String> values = new ArrayList<>();
            for (Object value : map.values()) values.addAll(strings(value));
            return List.copyOf(values);
        }
        String value = String.valueOf(raw).trim();
        return value.isEmpty() || "null".equalsIgnoreCase(value) ? List.of() : List.of(value);
    }

    private static Map<String, Object> map(Object raw) {
        if (!(raw instanceof Map<?, ?> values)) return Map.of();
        Map<String, Object> result = new LinkedHashMap<>();
        values.forEach((key, value) -> result.put(String.valueOf(key), value));
        return result;
    }

    private static Object path(Map<String, Object> root, String... keys) {
        Object current = root;
        for (String key : keys) {
            current = map(current).get(key);
            if (current == null) return Map.of();
        }
        return current;
    }

    private static String string(Object value) {
        return value == null ? "" : String.valueOf(value);
    }

    private static int number(Object value, int fallback) {
        if (value instanceof Number number) return number.intValue();
        try {
            return value == null ? fallback : Integer.parseInt(String.valueOf(value).trim());
        } catch (NumberFormatException ignored) {
            return fallback;
        }
    }

    private static boolean bool(Object value, boolean fallback) {
        if (value instanceof Boolean bool) return bool;
        if (value == null) return fallback;
        String text = String.valueOf(value).trim();
        return text.equalsIgnoreCase("true") ? true : text.equalsIgnoreCase("false") ? false : fallback;
    }

    private static boolean isInteger(String value) {
        try {
            Integer.parseInt(value);
            return true;
        } catch (NumberFormatException ignored) {
            return false;
        }
    }

    private static int integer(String value) {
        return Integer.parseInt(value);
    }

    private static boolean isYaml(Path file) {
        String name = file.getFileName().toString().toLowerCase(Locale.ROOT);
        return name.endsWith(".yml") || name.endsWith(".yaml");
    }

    private record PotData(String id, String dryIcon, String wetIcon, int maxFertilizers,
                           int storage, boolean absorbRain, boolean absorbNearbyWater,
                           List<FillMethodData> fillMethods) {}

    private record FillMethodData(String itemId, int amount, String returnedItemId, int returnedAmount) {}

    private record CropData(String id, List<String> seeds, Set<String> potWhitelist,
                            List<String> seasons, List<String> environments, int maxPoints,
                            boolean seasonalDeath, boolean crowAttack, Map<String, Object> raw) {}

    private record FertilizerData(String itemId, String type, int times, boolean beforePlant,
                                  Set<String> potWhitelist) {}

    private record WateringCanData(String itemId, int capacity, int water, Set<String> potWhitelist) {}

    private record SprinklerData(String itemId, int range, int storage, int water, boolean infinite,
                                 Set<String> potWhitelist) {}

    private record Drop(String itemId, int minimum, int maximum, boolean quality) {}

    private record OutputData(List<RecipeResult> results) {
        private static final OutputData EMPTY = new OutputData(List.of());
    }

    private record BlockRule(boolean enabled, String id, int range, boolean protectChunk) {}

    private record MachineSettings(BlockRule scarecrow, BlockRule greenhouse) {}

    private record WateringTiming(int minimumUnit, String potMode, int potInterval, int sprinklerInterval) {
        boolean hasScheduledPotUpdate() {
            return this.minimumUnit > 0 && this.potInterval > 0
                    && (this.potMode.equalsIgnoreCase("ALL") || this.potMode.contains("SCHEDULED"));
        }
    }

    @FunctionalInterface
    private interface DefinitionConsumer {
        void accept(String id, Map<String, Object> raw, Path file);
    }
}
