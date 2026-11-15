package dev.che1s.craftenginerecipe.util;

import dev.che1s.craftenginerecipe.api.extension.VanillaTranslations;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.jar.JarEntry;
import java.util.jar.JarOutputStream;

import static org.junit.jupiter.api.Assertions.assertEquals;

class VanillaTranslationsTest {
    @TempDir Path root;

    @Test
    void readsVersionedChineseAndEnglishNamesFromThePluginJar() throws Exception {
        Path jar = this.root.resolve("translations.jar");
        try (JarOutputStream output = new JarOutputStream(Files.newOutputStream(jar))) {
            entry(output, "vanilla-lang/minecraft-26.3/zh_cn.json",
                    "{\"block.minecraft.oak_planks\":\"橡木木板\"}");
            entry(output, "vanilla-lang/minecraft-26.3/en_us.json",
                    "{\"block.minecraft.oak_planks\":\"Oak Planks\"}");
            entry(output, "vanilla-lang/minecraft-26.2/de_de.json",
                    "{\"block.minecraft.oak_planks\":\"Vanilla-Eichenholz\"}");
            entry(output, "vanilla-lang/minecraft-26.3/de_de.json",
                    "{\"block.minecraft.oak_planks\":\"Eichenholzbretter\"}");
        }

        Map<String, String> chinese = VanillaTranslations.read(jar, "zh_cn");
        Map<String, String> english = VanillaTranslations.read(jar, "en_us");

        assertEquals("橡木木板", VanillaTranslations.itemName(chinese, "minecraft:oak_planks"));
        assertEquals("Oak Planks", VanillaTranslations.itemName(english, "minecraft:oak_planks"));
        assertEquals("Eichenholzbretter", VanillaTranslations.itemName(
                VanillaTranslations.read(jar, "26.3", "de_de"), "minecraft:oak_planks"));
    }

    private static void entry(JarOutputStream output, String name, String json) throws Exception {
        output.putNextEntry(new JarEntry(name));
        output.write(json.getBytes(StandardCharsets.UTF_8));
        output.closeEntry();
    }
}
