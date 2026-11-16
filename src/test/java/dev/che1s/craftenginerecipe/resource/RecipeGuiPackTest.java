package dev.che1s.craftenginerecipe.resource;

import org.bukkit.configuration.file.YamlConfiguration;
import org.junit.jupiter.api.Test;

import javax.imageio.ImageIO;
import java.awt.Rectangle;
import java.awt.image.BufferedImage;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RecipeGuiPackTest {
    @Test
    void recipeGuiPacksUseTheSharedFontCanvasAndPreserveSourcePixels() throws Exception {
        List<Pack> packs = List.of(
                new Pack("cer_cultivation_gui", "cultivation_gui", new Rectangle(6, 17, 164, 108)),
                new Pack("cer_api_gui", "default_recipe_gui", new Rectangle(0, 0, 176, 134)));

        for (Pack pack : packs) {
            YamlConfiguration metadata = this.yaml("resources/" + pack.id() + "/pack.yml");
            YamlConfiguration gui = this.yaml("resources/" + pack.id() + "/configuration/gui.yml");
            String imageId = pack.id() + ":" + pack.image();
            String texture = "resources/" + pack.id() + "/resourcepack/assets/" + pack.id()
                    + "/textures/gui/" + pack.image() + ".png";

            assertEquals(pack.id(), metadata.getString("namespace"));
            assertEquals(256, gui.getInt("images." + imageId + ".height"));
            assertEquals(13, gui.getInt("images." + imageId + ".ascent"));
            assertEquals(pack.id() + ":gui/" + pack.image() + ".png",
                    gui.getString("images." + imageId + ".file"));

            try (InputStream input = getClass().getClassLoader().getResourceAsStream(texture)) {
                assertNotNull(input, texture);
                BufferedImage image = ImageIO.read(input);
                assertEquals(256, image.getWidth());
                assertEquals(256, image.getHeight());
                assertEquals(pack.visibleBounds(), this.alphaBounds(image));
            }
        }
    }

    @Test
    void managementGuiIsRequiredAndUsesTheDedicatedImage() throws Exception {
        YamlConfiguration metadata = this.yaml("resources/cer_manage_gui/pack.yml");
        YamlConfiguration gui = this.yaml("resources/cer_manage_gui/configuration/gui.yml");

        assertEquals("cer_manage", metadata.getString("namespace"));
        assertEquals("cer_manage:font/gui/cer_manage.png",
                gui.getString("images.cer_manage:main.file"));
        assertEquals(110, gui.getInt("images.cer_manage:main.height"));
        assertEquals(-3, gui.getInt("images.cer_manage:main.ascent"));
        assertTrue(ResourcePackInstaller.REQUIRED_GUI_PACKS.contains("cer_manage_gui"));
        try (InputStream input = getClass().getClassLoader().getResourceAsStream(
                "resources/cer_manage_gui/resourcepack/assets/cer_manage/textures/font/gui/cer_manage.png")) {
            assertNotNull(input);
            BufferedImage image = ImageIO.read(input);
            assertEquals(164, image.getWidth());
            assertEquals(110, image.getHeight());
        }
    }

    @Test
    void recipeBookIsAnIndependentRequiredItemPack() throws Exception {
        YamlConfiguration metadata = this.yaml("resources/cer_recipe_book/pack.yml");
        YamlConfiguration items = this.yaml("resources/cer_recipe_book/configuration/items.yml");

        assertEquals("cer_recipe_book", metadata.getString("namespace"));
        assertEquals("paper", items.getString("items.cer_recipe_book:recipe_book.material"));
        assertEquals("<!i><#D4AF37>CER·配方书</#D4AF37>",
                items.getString("items.cer_recipe_book:recipe_book.data.item_name"));
        assertEquals("cer_recipe_book:item/recipe_book",
                items.getString("items.cer_recipe_book:recipe_book.model.path"));
        assertEquals("cer_recipe_book:item/recipe_book",
                items.getString("items.cer_recipe_book:recipe_book.model.generation.textures.layer0"));
        assertTrue(ResourcePackInstaller.REQUIRED_CORE_PACKS.contains("cer_recipe_book"));
        assertTrue(!ResourcePackInstaller.REQUIRED_GUI_PACKS.contains("cer_recipe_book"));

        try (InputStream input = getClass().getClassLoader().getResourceAsStream(
                "resources/cer_recipe_book/resourcepack/assets/cer_recipe_book/textures/item/recipe_book.png")) {
            assertNotNull(input);
            BufferedImage image = ImageIO.read(input);
            assertEquals(16, image.getWidth());
            assertEquals(16, image.getHeight());
        }
    }

    private Rectangle alphaBounds(BufferedImage image) {
        int minX = image.getWidth();
        int minY = image.getHeight();
        int maxX = -1;
        int maxY = -1;
        for (int y = 0; y < image.getHeight(); y++) {
            for (int x = 0; x < image.getWidth(); x++) {
                if ((image.getRGB(x, y) >>> 24) == 0) continue;
                minX = Math.min(minX, x);
                minY = Math.min(minY, y);
                maxX = Math.max(maxX, x);
                maxY = Math.max(maxY, y);
            }
        }
        return maxX < 0 ? new Rectangle() : new Rectangle(minX, minY, maxX - minX + 1, maxY - minY + 1);
    }

    private YamlConfiguration yaml(String resource) throws Exception {
        try (InputStream input = getClass().getClassLoader().getResourceAsStream(resource)) {
            assertNotNull(input, resource);
            return YamlConfiguration.loadConfiguration(
                    new InputStreamReader(input, StandardCharsets.UTF_8));
        }
    }

    private record Pack(String id, String image, Rectangle visibleBounds) {}
}
