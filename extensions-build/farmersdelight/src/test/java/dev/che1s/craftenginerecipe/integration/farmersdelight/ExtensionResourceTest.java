package dev.che1s.craftenginerecipe.integration.farmersdelight;

import org.bukkit.configuration.file.YamlConfiguration;
import org.junit.jupiter.api.Test;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

class ExtensionResourceTest {
    @Test
    void packagesOwnLanguageAndTransparentFakeButtonModel() throws Exception {
        assertNotNull(resource("lang/zh_CN.yml"));
        assertNotNull(resource("lang/en_US.yml"));
        YamlConfiguration items = yaml("resourcepack/cer_fd_gui/configuration/items.yml");
        assertEquals("paper", items.getString("items.cer_fd_gui:fake_button.material"));
        assertEquals("cer_fd_gui:item/fake_button",
                items.getString("items.cer_fd_gui:fake_button.model.path"));
        try (InputStream input = resource(
                "resourcepack/cer_fd_gui/resourcepack/assets/cer_fd_gui/textures/item/fake_button.png")) {
            BufferedImage image = ImageIO.read(input);
            assertEquals(16, image.getWidth());
            assertEquals(16, image.getHeight());
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
