package dev.che1s.craftenginerecipe.integration.craftengine;

import com.google.gson.JsonParser;
import com.google.gson.JsonObject;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

class CultivationDataExporterTest {
    private static final Path RESOURCES = Path.of("../../其他/我的生产环境配置/CraftEngine/resources");
    private static final Path CUSTOM_CROPS = Path.of("../../其他/我的生产环境配置/CustomCrops");

    @TempDir
    Path temporaryDirectory;

    @Test
    void extractsCurrentFarmersDelightAndKaleidoscopeCultivationData() throws Exception {
        assumeTrue(Files.isDirectory(RESOURCES));

        List<JsonObject> farmersDelight = CultivationDataExporter.namespaceCrops(
                RESOURCES.resolve("farmersdelight"), "farmersdelight");
        List<JsonObject> kaleidoscope = CultivationDataExporter.namespaceCrops(
                RESOURCES.resolve("kaleidoscopecookery森罗物语"), "kaleidoscopecookery");

        assertFalse(farmersDelight.isEmpty());
        assertTrue(farmersDelight.stream().anyMatch(crop ->
                crop.get("id").getAsString().equals("farmersdelight:cabbages")));
        assertTrue(farmersDelight.stream().anyMatch(crop ->
                crop.get("id").getAsString().equals("farmersdelight:cabbages")
                        && crop.get("substrates").getAsJsonArray().toString().contains("minecraft:farmland")));
        assertTrue(farmersDelight.stream().anyMatch(crop ->
                crop.get("seeds").getAsJsonArray().toString().contains("farmersdelight:tomato_seeds")));
        assertFalse(kaleidoscope.isEmpty());
        assertTrue(kaleidoscope.stream().anyMatch(crop ->
                crop.get("id").getAsString().equals("kaleidoscopecookery:tomato_crop")));
        assertTrue(kaleidoscope.stream().anyMatch(crop ->
                crop.get("id").getAsString().equals("kaleidoscopecookery:lettuce_crop")
                        && crop.get("substrates").getAsJsonArray().toString().contains("minecraft:farmland")));
        assertTrue(kaleidoscope.stream().anyMatch(crop ->
                crop.get("id").getAsString().equals("kaleidoscopecookery:rice_crop")));
    }

    @Test
    void extractsDefaultCraftEngineCropsIncludingLiquidAndEndStonePlants() throws Exception {
        assumeTrue(Files.isDirectory(RESOURCES));

        List<JsonObject> crops = CultivationDataExporter.namespaceCrops(
                RESOURCES.resolve("default_assets"), "default");

        assertTrue(crops.stream().anyMatch(crop ->
                crop.get("id").getAsString().equals("default:hami_melon_stem")));
        assertTrue(crops.stream().anyMatch(crop ->
                crop.get("id").getAsString().equals("default:ender_pearl_flower")));
        assertTrue(crops.stream().anyMatch(crop ->
                crop.get("id").getAsString().equals("default:magma_plant")));
        assertTrue(crops.stream().anyMatch(crop ->
                crop.get("id").getAsString().equals("default:flame_cane")));
        assertTrue(crops.stream().filter(crop ->
                        crop.get("id").getAsString().equals("default:magma_plant"))
                .flatMap(crop -> crop.getAsJsonArray("substrates").asList().stream())
                .anyMatch(value -> value.getAsString().equals("minecraft:lava")));
    }

    @Test
    void currentCustomCropsSetupHasNoVanillaFarmlandPot() throws Exception {
        assumeTrue(Files.isDirectory(CUSTOM_CROPS));

        assertEquals(List.of(), CultivationDataExporter.customCrops(CUSTOM_CROPS));
    }

    @Test
    void derivesSeedsFromBlockItemBindingsAndPropagatesThemToMatureCrops() throws Exception {
        Path configuration = this.temporaryDirectory.resolve("configuration");
        Files.createDirectories(configuration);
        Files.writeString(configuration.resolve("blocks.yml"), """
                blocks:
                  example:budding_lantern_fruit:
                    behavior:
                      - type: crop_block
                      - type: custom_transition
                        mature-block: example:lantern_fruits
                    loot:
                      pools:
                        - entries:
                            - type: item
                              item: example:lantern_fruit_seeds
                  example:lantern_fruits:
                    behavior:
                      - type: crop_block
                    loot:
                      pools:
                        - entries:
                            - type: item
                              item: example:lantern_fruit_seeds
                            - type: item
                              item: example:lantern_fruit
                """);
        Files.writeString(configuration.resolve("items.yml"), """
                items:
                  example:lantern_fruit_seeds:
                    material: wheat_seeds
                    behavior:
                      type: block_item
                      block: example:budding_lantern_fruit
                  example:lantern_fruit:
                    material: apple
                """);

        List<JsonObject> crops = CultivationDataExporter.namespaceCrops(
                this.temporaryDirectory, "example");

        assertEquals(2, crops.size());
        for (String id : List.of("example:budding_lantern_fruit", "example:lantern_fruits")) {
            JsonObject crop = crops.stream()
                    .filter(value -> value.get("id").getAsString().equals(id))
                    .findFirst()
                    .orElseThrow();
            assertEquals(List.of("example:lantern_fruit_seeds"),
                    crop.getAsJsonArray("seeds").asList().stream()
                            .map(value -> value.getAsString()).toList());
        }
    }

    @Test
    void derivesSeedsFromTemplatedBehaviorLists() throws Exception {
        Path configuration = this.temporaryDirectory.resolve("configuration");
        Files.createDirectories(configuration);
        Files.writeString(configuration.resolve("blocks.yml"), """
                blocks:
                  example:onion:
                    behavior:
                      type: crop_block
                """);
        Files.writeString(configuration.resolve("items.yml"), """
                templates:
                  example:seed_item:
                    behaviors:
                      - type: block_item
                        block: example:${__ID__}
                items:
                  example:onion:
                    template: example:seed_item
                """);

        List<JsonObject> crops = CultivationDataExporter.namespaceCrops(
                this.temporaryDirectory, "example");

        assertEquals(1, crops.size());
        assertEquals(List.of("example:onion"),
                crops.getFirst().getAsJsonArray("seeds").asList().stream()
                        .map(value -> value.getAsString()).toList());
    }

    @Test
    void repairsLegacyBlockIdSeedsWithoutDiscardingOtherData() throws Exception {
        Path target = this.temporaryDirectory.resolve("existing.json");
        Files.writeString(target, """
                {
                  "schema": 1,
                  "source": "craftengine",
                  "namespace": "example",
                  "custom": "keep",
                  "crops": [
                    {
                      "id": "example:lantern_fruits",
                      "seeds": ["example:lantern_fruits"],
                      "products": ["example:lantern_fruit"]
                    }
                  ]
                }
                """);
        JsonObject generated = new JsonObject();
        generated.addProperty("id", "example:lantern_fruits");
        generated.add("seeds", JsonParser.parseString(
                "[\"example:lantern_fruit_seeds\"]").getAsJsonArray());

        CultivationDataExporter.repairSeedFallbacks(target, List.of(generated));

        JsonObject repaired = JsonParser.parseString(Files.readString(target)).getAsJsonObject();
        assertEquals("keep", repaired.get("custom").getAsString());
        assertEquals(List.of("example:lantern_fruit_seeds"),
                repaired.getAsJsonArray("crops").get(0).getAsJsonObject()
                        .getAsJsonArray("seeds").asList().stream()
                        .map(value -> value.getAsString()).toList());
    }

    @Test
    void ignoresItemsWhoseCustomBehaviorOnlyMentionsTheCropBlock() throws Exception {
        Path configuration = this.temporaryDirectory.resolve("configuration");
        Files.createDirectories(configuration);
        Files.writeString(configuration.resolve("blocks.yml"), """
                blocks:
                  example:custom_crop:
                    behavior:
                      type: crop_block
                """);
        Files.writeString(configuration.resolve("items.yml"), """
                items:
                  example:decorative_item:
                    behavior:
                      type: custom_display_behavior
                      block: example:custom_crop
                """);

        List<JsonObject> crops = CultivationDataExporter.namespaceCrops(
                this.temporaryDirectory, "example");

        assertEquals(List.of("example:custom_crop"),
                crops.getFirst().getAsJsonArray("seeds").asList().stream()
                        .map(value -> value.getAsString()).toList());
    }
}
