package dev.che1s.craftenginerecipe.config;

import org.bukkit.configuration.file.YamlConfiguration;
import org.junit.jupiter.api.Test;

import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PluginManifestTest {
    @Test
    void commandUsageStaysInsideTheCerCommandSection() throws Exception {
        try (var input = getClass().getClassLoader().getResourceAsStream("plugin.yml")) {
            assertNotNull(input);
            YamlConfiguration yaml = new YamlConfiguration();
            yaml.load(new InputStreamReader(input, StandardCharsets.UTF_8));

            assertEquals(1, yaml.getConfigurationSection("commands").getKeys(false).size());
            assertTrue(yaml.getString("commands.cer.usage").contains("manage"));
            assertTrue(yaml.getString("commands.cer.usage").contains("craft"));
            assertTrue(yaml.getString("commands.cer.usage").contains("items"));
            assertEquals(java.util.List.of("cg", "browser"), yaml.getStringList("commands.cer.aliases"));
        }
    }

    @Test
    void bundledExamplesUseTheDedicatedExampleDirectory() {
        assertNotNull(getClass().getResourceAsStream("/example/navigation/categories-example.yml"));
        assertNotNull(getClass().getResourceAsStream("/example/recipes/recipes-example.yml"));
    }
}
