package dev.che1s.craftenginerecipe.integration.kaleidoscope;

import org.bukkit.configuration.file.YamlConfiguration;
import org.junit.jupiter.api.Test;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ExtensionResourceTest {
    @Test
    void packagesOwnLanguageAndInteractiveNoteModel() throws Exception {
        assertNotNull(resource("lang/zh_CN.yml"));
        assertNotNull(resource("lang/en_US.yml"));
        YamlConfiguration items = yaml("resourcepack/cer_kc_gui/configuration/items.yml");
        assertEquals("paper", items.getString("items.cer_kc_gui:note.material"));
        assertEquals("cer_kc_gui:item/note", items.getString("items.cer_kc_gui:note.model.path"));
        try (InputStream input = resource(
                "resourcepack/cer_kc_gui/resourcepack/assets/cer_kc_gui/textures/item/note.png")) {
            BufferedImage image = ImageIO.read(input);
            assertEquals(16, image.getWidth());
            assertEquals(16, image.getHeight());
            assertTrue(image.getColorModel().hasAlpha());
        }
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
