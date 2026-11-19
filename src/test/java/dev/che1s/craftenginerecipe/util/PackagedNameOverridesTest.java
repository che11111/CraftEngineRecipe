package dev.che1s.craftenginerecipe.util;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.Map;
import java.util.jar.JarEntry;
import java.util.jar.JarOutputStream;

import static org.junit.jupiter.api.Assertions.assertEquals;

class PackagedNameOverridesTest {
    @TempDir
    Path directory;

    @Test
    void readsNameOverridesFromAnExtensionJar() throws Exception {
        Path jar = this.directory.resolve("cer-ext-craftengine.jar");
        String resource = "item-name-overrides/default.json";
        try (JarOutputStream output = new JarOutputStream(
                java.nio.file.Files.newOutputStream(jar))) {
            output.putNextEntry(new JarEntry(resource));
            output.write("""
                    {"ja_JP":{"item.default.amethyst_torch":"アメジストのたいまつ"}}
                    """.getBytes(StandardCharsets.UTF_8));
            output.closeEntry();
        }

        Map<String, Map<String, String>> names = PackagedNameOverrides.readJar(jar, resource);

        assertEquals("アメジストのたいまつ",
                names.get("ja_JP").get("item.default.amethyst_torch"));
    }
}
