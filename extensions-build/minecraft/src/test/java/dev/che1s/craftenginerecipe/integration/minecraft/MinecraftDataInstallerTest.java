package dev.che1s.craftenginerecipe.integration.minecraft;

import dev.che1s.craftenginerecipe.api.extension.SourceContext;
import dev.che1s.craftenginerecipe.api.extension.SourceText;
import org.bukkit.plugin.Plugin;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.function.Function;
import java.util.jar.JarEntry;
import java.util.jar.JarOutputStream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class MinecraftDataInstallerTest {
    @TempDir Path root;

    @Test
    void installsOnlyTheExactMinecraftVersion() throws Exception {
        Path jar = this.jar("multi.jar", "recipes/minecraft-26.2/crafting.yml",
                "recipes/minecraft-26.3/crafting.yml");
        SourceContext context = context(jar, "26.3");

        Path installed = MinecraftDataInstaller.install(context, "recipes");

        assertEquals("minecraft-26.3", installed.getFileName().toString());
        assertTrue(Files.isRegularFile(installed.resolve("crafting.yml")));
        assertFalse(Files.exists(installed.resolveSibling("minecraft-26.2")));
    }

    @Test
    void refusesToFallBackToADifferentMinecraftVersion() throws Exception {
        Path jar = this.jar("old.jar", "recipes/minecraft-26.2/crafting.yml");

        IOException error = assertThrows(IOException.class,
                () -> MinecraftDataInstaller.install(context(jar, "26.4"), "recipes"));

        assertTrue(error.getMessage().contains("does not contain recipes data for Minecraft 26.4"));
    }

    private Path jar(String name, String... entries) throws IOException {
        Path jar = this.root.resolve(name);
        try (JarOutputStream output = new JarOutputStream(Files.newOutputStream(jar))) {
            for (String entry : entries) {
                output.putNextEntry(new JarEntry(entry));
                output.write("recipes: {}\n".getBytes(StandardCharsets.UTF_8));
                output.closeEntry();
            }
        }
        return jar;
    }

    private SourceContext context(Path jar, String minecraftVersion) {
        return new SourceContext() {
            @Override public Plugin sourcePlugin() { return null; }
            @Override public Path coreDataFolder() { return MinecraftDataInstallerTest.this.root; }
            @Override public Path extensionJar() { return jar; }
            @Override public Path extensionDataFolder() { return MinecraftDataInstallerTest.this.root.resolve("data"); }
            @Override public String extensionVersion() { return "1.0.0"; }
            @Override public String minecraftVersion() { return minecraftVersion; }
            @Override public java.util.logging.Logger logger() {
                return java.util.logging.Logger.getLogger("MinecraftDataInstallerTest");
            }
            @Override public SourceText text(String integrationId) { return SourceText.fallbacks(); }
            @Override public Function<String, String> resourcePackTranslation() { return key -> null; }
            @Override public Function<String, String> itemDisplayName() { return key -> null; }
            @Override public long translationRevision() { return 0L; }
            @Override public boolean chinese() { return true; }
            @Override public boolean sourceEnabled(String sourceId) { return true; }
            @Override public Function<String, String> coreText() { return key -> key; }
        };
    }
}
