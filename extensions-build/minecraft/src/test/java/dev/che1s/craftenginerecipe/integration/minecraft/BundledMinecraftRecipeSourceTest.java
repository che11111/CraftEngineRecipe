package dev.che1s.craftenginerecipe.integration.minecraft;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import dev.che1s.craftenginerecipe.api.DisplayRecipe;
import dev.che1s.craftenginerecipe.api.RecipeIngredient;
import dev.che1s.craftenginerecipe.api.extension.SourceText;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.jar.JarEntry;
import java.util.jar.JarOutputStream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

class BundledMinecraftRecipeSourceTest {
    @TempDir Path root;

    @Test
    void bundlesEverySupportedMinecraftRecipeAndCultivationVersion() {
        for (String version : List.of("1.21.7", "1.21.8", "1.21.9",
                "1.21.10", "1.21.11", "26.1", "26.2", "26.3")) {
            Path recipes = Path.of("../minecraft/src/main/resources/recipes/minecraft-" + version);
            assertTrue(Files.isDirectory(recipes), recipes.toString());
            try (var files = Files.list(recipes)) {
                assertEquals(7, files.filter(path -> path.toString().endsWith(".yml")).count(),
                        version + " recipe station files");
            } catch (Exception error) {
                throw new AssertionError(error);
            }
            Path cultivation = Path.of("../minecraft-cultivation/src/main/resources/cultivation/minecraft-"
                    + version + "/vanilla.json");
            assertTrue(Files.isRegularFile(cultivation), cultivation.toString());
        }
    }

    @Test
    void parsesShapedAndCookingRecipesFromVanillaJson() {
        BundledMinecraftRecipeSource source = source();
        DisplayRecipe shaped = source.parseRecipe("oak_planks.json", """
                {
                  "type": "minecraft:crafting_shaped",
                  "pattern": ["#"],
                  "key": {"#": "minecraft:oak_log"},
                  "result": {"id": "minecraft:oak_planks", "count": 4}
                }
                """);
        DisplayRecipe cooking = source.parseRecipe("baked_potato.json", """
                {
                  "type": "minecraft:smelting",
                  "ingredient": "minecraft:potato",
                  "result": {"id": "minecraft:baked_potato"},
                  "cookingtime": 200
                }
                """);

        assertNotNull(shaped);
        assertEquals("minecraft:crafting", shaped.workstation().id());
        assertEquals("minecraft:oak_log", shaped.ingredients().getFirst().choices().getFirst().id());
        assertEquals(4, shaped.results().getFirst().amount());
        assertNotNull(cooking);
        assertEquals("minecraft:smelting", cooking.workstation().id());
        assertEquals("minecraft:potato", cooking.ingredients().getFirst().choices().getFirst().id());
        assertEquals("minecraft:baked_potato", cooking.results().getFirst().item().id());
    }

    @Test
    void loadsTheSeparateCultivationDatasetAndKeepsSeedsAndProducts() throws Exception {
        Path jar = this.root.resolve("bundled.jar");
        String cultivation = """
                {
                  "schema": 1,
                  "version": "minecraft-26.2",
                  "crops": [
                    {
                      "id": "minecraft:sugar_cane",
                      "seeds": ["minecraft:sugar_cane"],
                      "products": ["minecraft:sugar_cane"],
                      "substrates": ["minecraft:sand", "minecraft:dirt"],
                      "note": "紧邻水"
                    }
                  ]
                }
                """;
        try (JarOutputStream output = new JarOutputStream(Files.newOutputStream(jar))) {
            output.putNextEntry(new JarEntry("recipes/cultivation/minecraft-26.2/vanilla.json"));
            output.write(cultivation.getBytes(StandardCharsets.UTF_8));
            output.closeEntry();
        }

        List<DisplayRecipe> recipes = new BundledMinecraftRecipeSource(
                jar, Path.of("."), key -> switch (key) {
                    case "ui.workstations.categories.cultivation" -> "耕地";
                    default -> key;
                }, SourceText.fallbacks(), SourceText.fallbacks(), source -> true, true).load();

        assertEquals(1, recipes.size());
        DisplayRecipe sugarCane = recipes.getFirst();
        assertEquals("minecraft:cultivation", sugarCane.workstation().id());
        assertEquals("原版·耕地", sugarCane.workstation().name());
        assertEquals("minecraft:farmland", sugarCane.workstation().icon().id());
        assertEquals(-1, sugarCane.layout().workstationSlot());
        assertEquals(RecipeIngredient.Role.INGREDIENT, sugarCane.ingredients().getFirst().role());
        assertEquals(19, sugarCane.ingredients().getFirst().slot());
        assertEquals("minecraft:sugar_cane", sugarCane.ingredients().getFirst().choices().getFirst().id());
        assertEquals(21, sugarCane.ingredients().get(1).slot());
        assertEquals("minecraft:sand", sugarCane.ingredients().get(1).choices().getFirst().id());
        assertEquals("minecraft:sugar_cane", sugarCane.results().getFirst().item().id());
        assertEquals(23, sugarCane.results().getFirst().slot());
        assertTrue(sugarCane.ingredients().getFirst().choices().getFirst().lore().getFirst().contains("基质"));
        assertTrue(sugarCane.results().getFirst().item().lore().getFirst().contains("紧邻水"));
    }

    @Test
    void loadsPerSourceCultivationFilesOnlyWhenTheirSourceIsEnabled() throws Exception {
        Path jar = this.root.resolve("empty.jar");
        try (JarOutputStream ignored = new JarOutputStream(Files.newOutputStream(jar))) {
        }
        Path file = this.root.resolve("recipes/cultivation/craftengine/farmersdelight.json");
        Files.createDirectories(file.getParent());
        Files.writeString(file, """
                {
                  "schema": 1,
                  "source": "craftengine",
                  "namespace": "farmersdelight",
                  "crops": [{
                    "id": "farmersdelight:cabbages",
                    "seeds": ["farmersdelight:cabbage_seeds"],
                    "products": ["farmersdelight:cabbage"],
                    "substrates": ["minecraft:farmland"],
                    "note": "耕地"
                  }]
                }
                """);

        BundledMinecraftRecipeSource enabled = new BundledMinecraftRecipeSource(
                jar, this.root, key -> key,
                source -> source.equals("farmersdelight-cultivation")
                        || source.equals("craftengine-cultivation"));
        BundledMinecraftRecipeSource disabled = new BundledMinecraftRecipeSource(
                jar, this.root, key -> key, source -> false);

        assertEquals(1, enabled.load().size());
        assertEquals("cultivation_farmersdelight:cabbages", enabled.load().getFirst().id());
        assertTrue(disabled.load().isEmpty());
    }

    @Test
    void loadsCustomCropsVanillaDataFromItsOwnCultivationFolder() throws Exception {
        Path jar = this.root.resolve("empty-customcrops.jar");
        try (JarOutputStream ignored = new JarOutputStream(Files.newOutputStream(jar))) {
        }
        Path file = this.root.resolve("recipes/cultivation/customcrops/vanilla.json");
        Files.createDirectories(file.getParent());
        Files.writeString(file, """
                {
                  "schema": 1,
                  "source": "customcrops",
                  "namespace": "customcrops",
                  "crops": [{
                    "id": "customcrops:cabbage",
                    "seeds": ["customcrops:cabbage_seeds"],
                    "products": ["customcrops:cabbage"],
                    "substrates": ["minecraft:farmland"],
                    "note": "原版耕地"
                  }]
                }
                """);

        List<DisplayRecipe> recipes = new BundledMinecraftRecipeSource(
                jar, this.root, key -> key,
                source -> source.equals("customcrops-cultivation")).load();

        assertEquals(1, recipes.size());
        assertEquals("cultivation_customcrops:cabbage", recipes.getFirst().id());
    }

    @Test
    void generatedPotatoProductsUseTheRealPoisonousPotatoId() throws Exception {
        Path data = Path.of("../minecraft-cultivation/src/main/resources/cultivation/minecraft-26.2/vanilla.json");
        assumeTrue(Files.isRegularFile(data));
        String json = Files.readString(data);

        assertTrue(json.contains("minecraft:poisonous_potato"));
        assertFalse(json.contains("minecraft:poisoned_potato"));
    }

    @Test
    void generatedVanillaRecipeFilesDoNotFreezeWorkstationHeadings() throws Exception {
        Path recipes = Path.of("src/main/resources/recipes");
        assumeTrue(Files.isDirectory(recipes));
        try (var files = Files.walk(recipes)) {
            for (Path file : files.filter(path -> path.toString().endsWith(".yml")).toList()) {
                String yaml = Files.readString(file);
                assertFalse(yaml.matches("(?s).*Title:\\s*原版·.*"), file.toString());
                assertFalse(yaml.matches("(?s).*name:\\s*原版·.*"), file.toString());
            }
        }
    }

    @Test
    void caveVinesUseGlowBerriesBecauseTheBlockItselfIsNotAnItem() throws Exception {
        Path data = Path.of("../minecraft-cultivation/src/main/resources/cultivation/minecraft-26.2/vanilla.json");
        assumeTrue(Files.isRegularFile(data));
        JsonObject root = JsonParser.parseString(Files.readString(data)).getAsJsonObject();
        JsonObject caveVines = root.getAsJsonArray("crops").asList().stream()
                .map(JsonElement::getAsJsonObject)
                .filter(crop -> crop.get("id").getAsString().equals("minecraft:cave_vines"))
                .findFirst().orElseThrow();

        assertEquals("[\"minecraft:glow_berries\"]",
                caveVines.getAsJsonArray("products").toString());
    }

    @Test
    void appliesChineseVanillaNamesForSearchAndDisplay() throws Exception {
        Path jar = this.root.resolve("translated-vanilla.jar");
        try (JarOutputStream output = new JarOutputStream(Files.newOutputStream(jar))) {
            output.putNextEntry(new JarEntry("recipes/minecraft-26.2/wooden_sword.json"));
            output.write("""
                    {
                      "type": "minecraft:crafting_shaped",
                      "pattern": ["X"],
                      "key": {"X": "minecraft:oak_planks"},
                      "result": {"id": "minecraft:wooden_sword"}
                    }
                    """.getBytes(StandardCharsets.UTF_8));
            output.closeEntry();
            output.putNextEntry(new JarEntry("vanilla-lang/minecraft-26.2/zh_cn.json"));
            output.write("""
                    {
                      "item.minecraft.oak_planks": "橡木木板",
                      "item.minecraft.wooden_sword": "木剑"
                    }
                    """.getBytes(StandardCharsets.UTF_8));
            output.closeEntry();
        }

        DisplayRecipe recipe = new BundledMinecraftRecipeSource(
                jar, this.root, key -> key, source -> true, true).load().getFirst();

        assertEquals("橡木木板", recipe.ingredients().getFirst().choices().getFirst().name());
        assertEquals("木剑", recipe.results().getFirst().item().name());
    }

    @Test
    void appliesEnglishVanillaNamesForSearchAndDisplay() throws Exception {
        Path jar = this.root.resolve("english-vanilla.jar");
        try (JarOutputStream output = new JarOutputStream(Files.newOutputStream(jar))) {
            output.putNextEntry(new JarEntry("recipes/minecraft-26.3/wooden_sword.json"));
            output.write("""
                    {
                      "type": "minecraft:crafting_shaped",
                      "pattern": ["X"],
                      "key": {"X": "minecraft:oak_planks"},
                      "result": {"id": "minecraft:wooden_sword"}
                    }
                    """.getBytes(StandardCharsets.UTF_8));
            output.closeEntry();
            output.putNextEntry(new JarEntry("vanilla-lang/minecraft-26.3/en_us.json"));
            output.write("""
                    {
                      "block.minecraft.oak_planks": "Oak Planks",
                      "item.minecraft.wooden_sword": "Wooden Sword"
                    }
                    """.getBytes(StandardCharsets.UTF_8));
            output.closeEntry();
        }

        DisplayRecipe recipe = new BundledMinecraftRecipeSource(
                jar, this.root, key -> key, source -> true, false).load().getFirst();

        assertEquals("Oak Planks", recipe.ingredients().getFirst().choices().getFirst().name());
        assertEquals("Wooden Sword", recipe.results().getFirst().item().name());
    }

    @Test
    void expandsNestedVanillaItemTagsIntoRecipeChoices() throws Exception {
        Path jar = this.root.resolve("tagged-recipe.jar");
        try (JarOutputStream output = new JarOutputStream(Files.newOutputStream(jar))) {
            output.putNextEntry(new JarEntry("recipes/minecraft-26.2/test_sword.json"));
            output.write("""
                    {
                      "type": "minecraft:crafting_shaped",
                      "pattern": ["X"],
                      "key": {"X": "#minecraft:wooden_tool_materials"},
                      "result": {"id": "minecraft:wooden_sword"}
                    }
                    """.getBytes(StandardCharsets.UTF_8));
            output.closeEntry();
            output.putNextEntry(new JarEntry("data/minecraft/tags/item/wooden_tool_materials.json"));
            output.write("""
                    {"values": ["#minecraft:planks"]}
                    """.getBytes(StandardCharsets.UTF_8));
            output.closeEntry();
            output.putNextEntry(new JarEntry("data/minecraft/tags/item/planks.json"));
            output.write("""
                    {"values": ["minecraft:oak_planks", "minecraft:crimson_planks"]}
                    """.getBytes(StandardCharsets.UTF_8));
            output.closeEntry();
        }

        List<DisplayRecipe> recipes = new BundledMinecraftRecipeSource(
                jar, this.root, key -> key, source -> true, false).load();

        assertEquals(2, recipes.size());
        assertEquals("minecraft:oak_planks",
                recipes.getFirst().ingredients().getFirst().choices().getFirst().id());
        assertEquals("minecraft:crimson_planks",
                recipes.getLast().ingredients().getFirst().choices().getFirst().id());
        assertTrue(recipes.stream().flatMap(recipe -> recipe.ingredients().stream())
                .flatMap(ingredient -> ingredient.choices().stream())
                .noneMatch(item -> item.id().equals("minecraft:knowledge_book")));
    }

    @Test
    void skipsRecipesWhoseItemTagCannotBeResolved() throws Exception {
        Path jar = this.root.resolve("unresolved-tag.jar");
        try (JarOutputStream output = new JarOutputStream(Files.newOutputStream(jar))) {
            output.putNextEntry(new JarEntry("recipes/minecraft-26.2/unresolved.json"));
            output.write("""
                    {
                      "type": "minecraft:crafting_shaped",
                      "pattern": ["X"],
                      "key": {"X": "#minecraft:missing_tag"},
                      "result": {"id": "minecraft:wooden_sword"}
                    }
                    """.getBytes(StandardCharsets.UTF_8));
            output.closeEntry();
        }

        assertTrue(new BundledMinecraftRecipeSource(
                jar, this.root, key -> key, source -> true, false).load().isEmpty());
    }

    @Test
    void liquidSubstratesUseDisplayableBucketItems() throws Exception {
        Path jar = this.root.resolve("lava-cultivation.jar");
        try (JarOutputStream output = new JarOutputStream(Files.newOutputStream(jar))) {
            output.putNextEntry(new JarEntry("recipes/cultivation/minecraft-26.2/vanilla.json"));
            output.write("""
                    {
                      "crops": [{
                        "id": "default:magma_plant",
                        "seeds": ["default:magma_fruit"],
                        "products": ["default:magma_fruit"],
                        "substrates": ["minecraft:lava"],
                        "note": "岩浆"
                      }]
                    }
                    """.getBytes(StandardCharsets.UTF_8));
            output.closeEntry();
        }

        DisplayRecipe recipe = new BundledMinecraftRecipeSource(
                jar, this.root, key -> key, source -> true).load().getFirst();

        assertEquals("minecraft:lava_bucket", recipe.ingredients().get(1).choices().getFirst().id());
    }

    private BundledMinecraftRecipeSource source() {
        return new BundledMinecraftRecipeSource(this.root.resolve("missing.jar"), key -> switch (key) {
            case "ui.workstations.categories.crafting" -> "工作台";
            case "ui.workstations.categories.smelting" -> "熔炉";
            default -> key;
        });
    }
}
