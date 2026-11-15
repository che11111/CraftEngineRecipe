package dev.che1s.craftenginerecipe.integration.customcrops;

import org.bukkit.configuration.file.YamlConfiguration;
import org.junit.jupiter.api.Test;

import javax.imageio.ImageIO;
import java.awt.Rectangle;
import java.awt.image.BufferedImage;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

class ExtensionResourceTest {
    @Test
    void packagesOwnLanguageAndSharedFontCanvas() throws Exception {
        assertNotNull(resource("lang/zh_CN.yml"));
        assertNotNull(resource("lang/en_US.yml"));
        YamlConfiguration metadata = yaml("resourcepack/cer_ccrops_gui/pack.yml");
        YamlConfiguration gui = yaml("resourcepack/cer_ccrops_gui/configuration/gui.yml");
        assertEquals("cer_ccrops_gui", metadata.getString("namespace"));
        assertEquals(256, gui.getInt("images.cer_ccrops_gui:customcrops_gui.height"));
        assertEquals(13, gui.getInt("images.cer_ccrops_gui:customcrops_gui.ascent"));
        try (InputStream input = resource(
                "resourcepack/cer_ccrops_gui/resourcepack/assets/cer_ccrops_gui/textures/gui/customcrops_gui.png")) {
            BufferedImage image = ImageIO.read(input);
            assertEquals(256, image.getWidth());
            assertEquals(256, image.getHeight());
            assertEquals(new Rectangle(3, 13, 170, 114), alphaBounds(image));
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

    private InputStream resource(String path) {
        InputStream input = getClass().getClassLoader().getResourceAsStream(path);
        assertNotNull(input, path);
        return input;
    }

    private YamlConfiguration yaml(String path) throws Exception {
        try (InputStream input = resource(path)) {
            return YamlConfiguration.loadConfiguration(
                    new InputStreamReader(input, StandardCharsets.UTF_8));
        }
    }
}
