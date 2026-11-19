package dev.che1s.craftenginerecipe.integration.minecraft;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class MinecraftCultivationResourceTest {
    @Test
    void vanillaCultivationContainsOnlyMinecraftNamespaces() throws Exception {
        List<Path> resources;
        try (var files = Files.list(Path.of("src/main/resources/cultivation"))) {
            resources = files.filter(Files::isDirectory).sorted().toList();
        }

        assertFalse(resources.isEmpty());
        for (Path directory : resources) {
            Path file = directory.resolve("vanilla.json");
            assertTrue(Files.isRegularFile(file), file.toString());
            JsonObject root = JsonParser.parseString(Files.readString(file)).getAsJsonObject();
            assertEquals("client-jar", root.get("source").getAsString(), file.toString());

            JsonArray crops = root.getAsJsonArray("crops");
            assertTrue(crops != null && !crops.isEmpty(), file.toString());
            for (JsonElement element : crops) {
                JsonObject crop = element.getAsJsonObject();
                assertMinecraftId(crop.get("id").getAsString(), file);
                for (String field : List.of("seeds", "products", "substrates")) {
                    JsonArray values = crop.getAsJsonArray(field);
                    if (values == null) continue;
                    for (JsonElement value : values) {
                        assertMinecraftId(value.getAsString(), file);
                    }
                }
            }
        }
    }

    private static void assertMinecraftId(String id, Path file) {
        assertTrue(id.startsWith("minecraft:"), file + " contains non-vanilla ID " + id);
    }
}
