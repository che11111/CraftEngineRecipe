package dev.che1s.craftenginerecipe.integration.customcrops;

import dev.che1s.craftenginerecipe.api.DisplayRecipe;
import dev.che1s.craftenginerecipe.api.RecipeIngredient;
import dev.che1s.craftenginerecipe.api.RecipeResult;
import dev.che1s.craftenginerecipe.recipe.io.RecipeFileExporter;
import dev.che1s.craftenginerecipe.util.ResourcePackTranslations;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

class CustomCropsRecipeSourceTest {
    @TempDir Path root;

    @Test
    void exportsEachPotAsAWorkstationWithRealConditionsDropsAndCompatibility() throws Exception {
        fixture();
        CustomCropsRecipeSource source = source(true);

        assertTrue(source.ready());
        List<DisplayRecipe> recipes = source.capture();

        assertEquals(List.of(
                "customcrops:pot/aquatic_crops/bubblebulb",
                "customcrops:pot/default/cabbage",
                "customcrops:pot/end/virelia"), recipes.stream().map(DisplayRecipe::id).toList());
        DisplayRecipe aquatic = recipes.getFirst();
        assertEquals("elitefantasy:aquatic_pot", aquatic.workstation().icon().id());
        assertEquals("<white>星露谷·水生盆栽</white>", aquatic.workstation().name());
        assertEquals("<#292D35><bold>星露谷·水生盆栽</bold></#292D35>", aquatic.layout().title());

        DisplayRecipe cabbage = recipes.get(1);
        assertEquals("customcrops:pot/default", cabbage.workstation().id());
        assertEquals("customcrops:dry_pot_1", cabbage.workstation().icon().id());
        assertEquals("<white>星露谷·种植盆</white>", cabbage.workstation().name());
        assertTrue(cabbage.workstation().icon().lore().stream().anyMatch(line -> line.contains("30 分钟")));
        assertEquals("<#292D35><bold>星露谷·种植盆</bold></#292D35>", cabbage.layout().title());
        assertEquals(6, cabbage.layout().rows());
        assertEquals(-1, cabbage.layout().workstationSlot());
        assertEquals(-1, cabbage.layout().informationSlot());
        assertEquals(-1, cabbage.layout().previousSlot());
        assertEquals(-1, cabbage.layout().backSlot());
        assertEquals(-1, cabbage.layout().nextSlot());
        assertTrue(cabbage.description().isEmpty());

        RecipeIngredient seed = cabbage.ingredients().stream()
                .filter(value -> value.role() == RecipeIngredient.Role.INGREDIENT).findFirst().orElseThrow();
        assertEquals(10, seed.slot());
        assertEquals(List.of("customcrops:cabbage_seeds", "customcrops:cabbage_seed_bag"),
                seed.choices().stream().map(item -> item.id()).toList());
        assertTrue(seed.choices().getFirst().lore().stream().anyMatch(line -> line.contains("春、夏、秋")));
        assertTrue(seed.choices().getFirst().lore().stream().anyMatch(line -> line.contains("作物顶部")));

        RecipeIngredient plantBlock = ingredientAt(cabbage, 12);
        assertEquals(RecipeIngredient.Role.INFO, plantBlock.role());
        assertEquals("customcrops:dry_pot_1", plantBlock.choices().getFirst().id());
        assertTrue(plantBlock.choices().getFirst().lore().stream()
                .anyMatch(line -> line.contains("种植方块") && line.contains("种植盆")));

        RecipeIngredient fertilizer = ingredientAt(cabbage, 40);
        assertEquals(RecipeIngredient.Role.INFO, fertilizer.role());
        assertEquals(List.of("customcrops:quality_1"), fertilizer.choices().stream().map(item -> item.id()).toList());
        assertTrue(fertilizer.choices().getFirst().lore().stream().anyMatch(line -> line.contains("播种前")));
        RecipeIngredient wateringCan = ingredientAt(cabbage, 36);
        assertEquals(RecipeIngredient.Role.INFO, wateringCan.role());
        assertEquals(List.of("customcrops:watering_can_1", "minecraft:water_bucket", "minecraft:potion"),
                wateringCan.choices().stream().map(item -> item.id()).toList());
        assertTrue(wateringCan.choices().getFirst().lore().stream().anyMatch(line -> line.contains("每 3 次")));
        assertTrue(wateringCan.choices().getFirst().lore().stream().anyMatch(line -> line.contains("30 分钟")));
        assertEquals("customcrops:sprinkler_1", ingredientAt(cabbage, 38).choices().getFirst().id());
        assertEquals(RecipeIngredient.Role.INFO, ingredientAt(cabbage, 38).role());
        assertTrue(ingredientAt(cabbage, 38).choices().getFirst().lore().stream()
                .anyMatch(line -> line.contains("自动灌溉")));
        assertEquals("customcrops:greenhouse_glass", ingredientAt(cabbage, 42).choices().getFirst().id());
        assertEquals("customcrops:scarecrow", ingredientAt(cabbage, 44).choices().getFirst().id());
        assertTrue(ingredientAt(cabbage, 42).choices().getFirst().lore().stream()
                .anyMatch(line -> line.contains("作物顶部")));

        RecipeResult result = cabbage.results().getFirst();
        assertEquals("customcrops:cabbage", result.item().id());
        assertEquals(1, result.amount());
        assertEquals(14, result.slot());
        assertTrue(result.note().contains("数量 1～2"));
        assertTrue(result.note().contains("品质"));
        assertTrue(result.item().lore().stream().anyMatch(line -> line.contains("春、夏、秋")));
        assertEquals("minecraft:stick", cabbage.results().get(1).item().id());
        assertEquals(15, cabbage.results().get(1).slot());
        assertTrue(ingredientAt(cabbage, 44).choices().getFirst().lore().stream()
                .anyMatch(line -> line.contains("乌鸦")));

        DisplayRecipe end = recipes.getLast();
        assertEquals("<#292D35><bold>星露谷·末地种植盆</bold></#292D35>", end.layout().title());
        assertFalse(end.ingredients().stream()
                .anyMatch(value -> value.slot() == 40), "end pot has no compatible fertilizer");
        assertEquals("minecraft:dragon_breath", ingredientAt(end, 36).choices().getFirst().id());
        assertTrue(ingredientAt(end, 36).choices().getFirst().lore().stream()
                .anyMatch(line -> line.contains("每次") && line.contains("1 点")));
        assertTrue(ingredientAt(end, 36).choices().getFirst().lore().stream()
                .anyMatch(line -> line.contains("手持龙息右键末地种植盆")));
        assertTrue(ingredientAt(end, 36).choices().getFirst().lore().stream()
                .anyMatch(line -> line.contains("玻璃瓶")));
        assertTrue(seedLoreContains(end, "末地"));
    }

    @Test
    void revisionChangesWithSourceFilesAndUnreadySourcesCannotCapture() throws Exception {
        fixture();
        CustomCropsRecipeSource source = source(true);
        long first = source.revision();
        Files.writeString(this.root.resolve("CustomCrops/contents/pots/default.yml"),
                Files.readString(this.root.resolve("CustomCrops/contents/pots/default.yml")) + "\n");
        assertTrue(source.revision() != first);

        CustomCropsRecipeSource disabled = source(false);
        assertFalse(disabled.ready());
        assertThrows(IllegalStateException.class, disabled::capture);
    }

    @Test
    void usesTheRequestedEnglishStationBrand() throws Exception {
        fixture();
        CustomCropsRecipeSource source = new CustomCropsRecipeSource(
                this.root.resolve("CustomCrops"), () -> true,
                key -> null, key -> null, () -> false);

        List<DisplayRecipe> recipes = source.capture();

        assertTrue(recipes.stream().anyMatch(recipe ->
                recipe.workstation().name().equals("<white>CustomCrops·Pot</white>")));
        assertTrue(recipes.stream().anyMatch(recipe ->
                recipe.workstation().name().equals("<white>CustomCrops·Hydroponic Pot</white>")));
        assertTrue(recipes.stream().anyMatch(recipe ->
                recipe.layout().title().equals("<#292D35><bold>CustomCrops·Pot</bold></#292D35>")));
    }

    @Test
    void exportedTrMenuStyleFilesUseThePotNameAndLoadableRecipeFields() throws Exception {
        fixture();
        List<DisplayRecipe> captured = source(true).capture();
        Path recipes = this.root.resolve("recipes");

        RecipeFileExporter.Result result = RecipeFileExporter.export(recipes, "customcrops", captured);

        assertTrue(result.installed());
        assertEquals(3, result.files());
        assertEquals(3, result.recipes());
        assertTrue(Files.isRegularFile(recipes.resolve("customcrops/pot_default.yml")));
        assertTrue(Files.isRegularFile(recipes.resolve("customcrops/pot_aquatic_crops.yml")));
        assertTrue(Files.isRegularFile(recipes.resolve("customcrops/pot_end.yml")));
        String yaml = Files.readString(recipes.resolve("customcrops/pot_default.yml"));
        assertTrue(yaml.contains("id: customcrops:pot/default"));
        assertTrue(yaml.contains("星露谷·种植盆"));
        assertFalse(yaml.contains("星露谷·卷心菜种植盆"));
        assertTrue(yaml.contains("Usage: ingredient"));
        assertTrue(yaml.contains("Usage: result"));
        assertTrue(yaml.contains("Usage: decoration"));
        assertFalse(yaml.contains("Role: TOOL"));
        assertFalse(yaml.contains("Usage: tool"));
        assertFalse(yaml.contains("Button: information"));
        assertFalse(yaml.contains("Button: previous"));
        assertFalse(yaml.contains("Button: next"));
        assertFalse(yaml.contains("Button: back"));
    }

    @Test
    void parsesTheWorkspaceProductionConfigurationWhenPresent() throws Exception {
        Path production = Path.of("../../其他/我的生产环境配置/CustomCrops");
        assumeTrue(Files.isDirectory(production));
        Path generatedPack = production.resolveSibling("CraftEngine/generated/resource_pack.zip");
        assumeTrue(Files.isRegularFile(generatedPack));
        Map<String, String> translations = ResourcePackTranslations.read(
                generatedPack, List.of("zh_cn", "en_us")).translations();

        CustomCropsRecipeSource source = new CustomCropsRecipeSource(
                production, () -> true, translations::get);
        List<DisplayRecipe> recipes = source.capture();

        assertFalse(recipes.isEmpty());
        assertEquals(4, recipes.stream().map(recipe -> recipe.workstation().id()).distinct().count());
        assertTrue(recipes.stream().allMatch(recipe -> !recipe.results().isEmpty()));
        assertTrue(recipes.stream().anyMatch(recipe -> recipe.id().endsWith("/tomato")));
        assertTrue(recipes.stream().anyMatch(recipe -> recipe.id().endsWith("/virelia")));
        DisplayRecipe cabbage = recipes.stream().filter(recipe -> recipe.id().endsWith("/cabbage"))
                .findFirst().orElseThrow();
        assertEquals("<#292D35><bold>星露谷·种植盆</bold></#292D35>",
                cabbage.layout().title());
        assertFalse(cabbage.layout().title().contains("cabbage"));
        assertTrue(recipes.stream().anyMatch(recipe -> recipe.workstation().name().contains("水生盆栽")));
        assertTrue(recipes.stream().allMatch(recipe -> recipe.workstation().name().startsWith("<white>星露谷·")));
        assertTrue(cabbage.workstation().icon().lore().stream().anyMatch(line -> line.contains("10 分钟")));
        assertTrue(cabbage.workstation().icon().lore().stream().anyMatch(line -> line.contains("50 分钟")));
        DisplayRecipe nether = recipes.stream()
                .filter(recipe -> recipe.workstation().id().equals("customcrops:pot/nether"))
                .findFirst().orElseThrow();
        assertEquals("<#292D35><bold>星露谷·下界种植盆</bold></#292D35>", nether.layout().title());
        assertTrue(ingredientAt(nether, 36).choices().stream()
                .anyMatch(item -> item.id().equals("minecraft:lava_bucket")));
        assertTrue(ingredientAt(nether, 36).choices().stream()
                .filter(item -> item.id().equals("minecraft:lava_bucket"))
                .flatMap(item -> item.lore().stream())
                .anyMatch(line -> line.contains("手持熔岩桶右键下界种植盆")),
                () -> ingredientAt(nether, 36).choices().stream()
                        .filter(item -> item.id().equals("minecraft:lava_bucket"))
                        .flatMap(item -> item.lore().stream()).toList().toString());
        assertTrue(ingredientAt(nether, 36).choices().stream()
                .filter(item -> item.id().equals("minecraft:lava_bucket"))
                .flatMap(item -> item.lore().stream())
                .anyMatch(line -> line.contains("空桶")));
        RecipeFileExporter.Result exported = RecipeFileExporter.export(
                this.root.resolve("production-recipe-preview"), "customcrops", recipes);
        assertEquals(4, exported.files());
        assertEquals(recipes.size(), exported.recipes());
        assertTrue(Files.isRegularFile(this.root.resolve(
                "production-recipe-preview/customcrops/pot_aquatic_crops.yml")));
    }

    private RecipeIngredient ingredientAt(DisplayRecipe recipe, int slot) {
        return recipe.ingredients().stream().filter(value -> value.slot() == slot).findFirst().orElseThrow();
    }

    private boolean seedLoreContains(DisplayRecipe recipe, String text) {
        return ingredientAt(recipe, 10).choices().stream()
                .flatMap(item -> item.lore().stream()).anyMatch(line -> line.contains(text));
    }

    private CustomCropsRecipeSource source(boolean enabled) {
        Map<String, String> translations = Map.ofEntries(
                Map.entry("item.customcrops.dry_pot_1", "种植盆"),
                Map.entry("item.customcrops.cabbage", "卷心菜"),
                Map.entry("item.customcrops.cabbage_seeds", "卷心菜种子"),
                Map.entry("item.customcrops.cabbage_seed_bag", "卷心菜种子袋"),
                Map.entry("item.customcrops.cabbage_silver_star", "卷心菜（银星）"),
                Map.entry("item.customcrops.watering_can_1", "铜制浇水壶"),
                Map.entry("item.customcrops.quality_1", "基础肥料"),
                Map.entry("item.customcrops.greenhouse_glass", "温室玻璃"),
                Map.entry("item.customcrops.scarecrow", "稻草人"),
                Map.entry("item.customcrops.sprinkler_1", "初级洒水器"),
                Map.entry("item.minecraft.dragon_breath", "龙息"),
                Map.entry("item.minecraft.glass_bottle", "玻璃瓶"));
        return new CustomCropsRecipeSource(this.root.resolve("CustomCrops"), () -> enabled, translations::get);
    }

    private void fixture() throws Exception {
        write("config.yml", """
                mechanics:
                  scarecrow:
                    enable: true
                    id: customcrops:scarecrow
                    type: BLOCK
                    range: 7
                  greenhouse:
                    enable: true
                    id: customcrops:greenhouse_glass
                    type: BLOCK
                    range: 5
                worlds:
                  settings:
                    _DEFAULT_:
                      min-tick-unit: 120
                      pot:
                        mode: SCHEDULED_TICK
                        tick-interval: 3
                      sprinkler:
                        mode: SCHEDULED_TICK
                        tick-interval: 2
                """);
        write("contents/pots/default.yml", """
                default:
                  max-water-storage: 5
                  absorb-rainwater: true
                  absorb-nearby-water: false
                  max-fertilizers: 1
                  base:
                    dry: customcrops:dry_pot_1
                    wet: customcrops:wet_pot_1
                  fill-method:
                    water:
                      item: WATER_BUCKET
                      amount: 3
                      return: BUCKET
                    bottle:
                      item: POTION
                      amount: 1
                      return: GLASS_BOTTLE
                """);
        write("contents/pots/aquatic_crops.yml", """
                aquatic_crops:
                  max-water-storage: 5
                  absorb-rainwater: true
                  absorb-nearby-water: false
                  max-fertilizers: 0
                  base:
                    dry: elitefantasy:aquatic_pot_empty
                    wet: elitefantasy:aquatic_pot
                """);
        write("contents/pots/end.yml", """
                end:
                  max-water-storage: 5
                  max-fertilizers: 0
                  base:
                    dry: customcrops:dry_pot_3
                    wet: customcrops:wet_pot_3
                  fill-method:
                    breath:
                      item: DRAGON_BREATH
                      amount: 1
                      return: GLASS_BOTTLE
                """);
        write("contents/crops/cabbage.yml", """
                cabbage:
                  pot-whitelist: [default]
                  seed:
                    - customcrops:cabbage_seeds
                    - customcrops:cabbage_seed_bag
                  requirements:
                    plant:
                      season:
                        type: season
                        value: [Spring, Summer, Autumn]
                  max-points: 2
                  points:
                    "2":
                      events:
                        break:
                          crop:
                            type: quality-crops
                            value:
                              min: 1
                              max: 2
                              items:
                                "1": customcrops:cabbage
                                "2": customcrops:cabbage_silver_star
                          seeds:
                            type: drop-item
                            value:
                              item: customcrops:cabbage_seeds
                              min: 1
                              max: 1
                          byproduct:
                            type: drop-item
                            value:
                              item: STICK
                              min: 1
                              max: 1
                  grow-conditions:
                    water:
                      conditions:
                        season:
                          type: suitable-season
                          value: [Spring, Summer, Autumn]
                  death-conditions:
                    winter:
                      conditions:
                        season:
                          type: unsuitable-season
                          value: [Winter]
                    crows:
                      conditions:
                        attack:
                          type: crow-attack
                """);
        write("contents/crops/virelia.yml", """
                virelia:
                  pot-whitelist: [end]
                  seed: customcrops:virelia_seeds
                  requirements:
                    plant:
                      environment:
                        type: environment
                        value: [the_end]
                  max-points: 1
                  points:
                    "1":
                      events:
                        break:
                          crop:
                            type: quality-crops
                            value:
                              min: 1
                              max: 1
                              items:
                                "1": customcrops:virelia
                """);
        write("contents/fertilizers/default.yml", """
                quality_1:
                  type: QUALITY
                  item: customcrops:quality_1
                  times: 28
                  before-plant: true
                  pot-whitelist: [default]
                end_food:
                  type: QUALITY
                  item: customcrops:end_food
                  pot-whitelist: [end]
                """);
        write("contents/watering-cans/default.yml", """
                watering_can_1:
                  item: customcrops:watering_can_1
                  capacity: 3
                  water: 1
                  pot-whitelist: [default]
                """);
        write("contents/sprinklers/default.yml", """
                sprinkler_1:
                  3D-item-with-water: customcrops:sprinkler_1
                  range: 1
                  storage: 4
                  water: 1
                  infinite: false
                  pot-whitelist: [default]
                """);
        write("contents/crops/bubblebulb.yml", """
                bubblebulb:
                  pot-whitelist: [aquatic_crops]
                  seed: elitefantasy:bubblebulb
                  max-points: 1
                  points:
                    "1":
                      events:
                        break:
                          crop:
                            type: quality-crops
                            value:
                              min: 1
                              max: 1
                              items:
                                "1": elitefantasy:bubblebulb_crop
                """);
    }

    private void write(String path, String content) throws Exception {
        Path target = this.root.resolve("CustomCrops").resolve(path);
        Files.createDirectories(target.getParent());
        Files.writeString(target, content);
    }
}
