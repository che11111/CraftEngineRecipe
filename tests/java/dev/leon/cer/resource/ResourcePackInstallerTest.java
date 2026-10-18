package dev.leon.cer.resource;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.jar.JarEntry;
import java.util.jar.JarOutputStream;

import static org.junit.jupiter.api.Assertions.*;

class ResourcePackInstallerTest {
    @TempDir Path folder;

    @Test
    void installsBothCopiesOnceAndRecordsSuccessOutsideThePackDirectories() throws Exception {
        Path jar = jar(Map.of("cer_resource/pack.yml", "pack", "cer_resource/nested/image.png", "image",
                "unrelated.yml", "ignored"));
        Path target = target();
        assertEquals(new ResourcePackInstaller.Result(2, true, 2),
                ResourcePackInstaller.install(jar, legacy(), target));
        assertEquals("pack", Files.readString(target.resolve("pack.yml")));
        assertEquals("image", Files.readString(target.resolve("nested/image.png")));
        assertFalse(Files.exists(target.resolve("unrelated.yml")));
        assertEquals("pack", Files.readString(legacy().resolve("pack.yml")));
        assertTrue(Files.isRegularFile(marker()));
        assertEquals(new ResourcePackInstaller.Result(0, false, 0),
                ResourcePackInstaller.install(jar, legacy(), target));
    }

    @Test
    void existingCraftEngineFilesWinAndMissingFilesAreCompleted() throws Exception {
        Path target = target();
        write(target.resolve("pack.yml"), "operator edit");
        Path jar = jar(Map.of("cer_resource/pack.yml", "bundled", "cer_resource/new.yml", "new"));
        assertEquals(new ResourcePackInstaller.Result(2, true, 1),
                ResourcePackInstaller.install(jar, legacy(), target));
        assertEquals("operator edit", Files.readString(target.resolve("pack.yml")));
        assertEquals("new", Files.readString(target.resolve("new.yml")));
    }

    @Test
    void migratesOldCustomFilesWithTargetThenLegacyThenBundledPrecedence() throws Exception {
        write(legacy().resolve("pack.yml"), "old custom pack");
        write(legacy().resolve("configuration/gui.yml"), "old custom gui");
        write(legacy().resolve("extra/custom.txt"), "extra");
        write(target().resolve("pack.yml"), "already installed");
        Path jar = jar(Map.of("cer_resource/pack.yml", "bundled pack",
                "cer_resource/configuration/gui.yml", "bundled gui", "cer_resource/new.yml", "new"));
        assertEquals(new ResourcePackInstaller.Result(1, true, 3),
                ResourcePackInstaller.install(jar, legacy(), target()));
        assertEquals("already installed", Files.readString(target().resolve("pack.yml")));
        assertEquals("old custom gui", Files.readString(target().resolve("configuration/gui.yml")));
        assertEquals("extra", Files.readString(target().resolve("extra/custom.txt")));
        assertEquals("new", Files.readString(target().resolve("new.yml")));
        assertEquals("old custom pack", Files.readString(legacy().resolve("pack.yml")));
        assertEquals("old custom gui", Files.readString(legacy().resolve("configuration/gui.yml")));
        assertEquals(new ResourcePackInstaller.Result(0, false, 0),
                ResourcePackInstaller.install(jar, legacy(), target()));
    }

    @Test
    void rejectsJarEntriesOutsideThePackDirectory() throws Exception {
        Path jar = jar(Map.of("cer_resource/../escape.txt", "invalid"));
        assertThrows(IOException.class, () -> ResourcePackInstaller.install(jar, legacy(), target()));
        assertFalse(Files.exists(target().getParent().resolve("escape.txt")));
    }

    @Test
    void doesNotFollowTargetSymlinksOrImportLegacySymlinks() throws Exception {
        Path outside = folder.resolve("outside");
        write(outside.resolve("custom.txt"), "keep");
        Files.createDirectories(target());
        Files.createSymbolicLink(target().resolve("linked"), outside);
        Path jar = jar(Map.of("cer_resource/pack.yml", "pack", "cer_resource/linked/custom.txt", "overwrite"));
        assertThrows(IOException.class, () -> ResourcePackInstaller.install(jar, legacy(), target()));
        assertEquals("keep", Files.readString(outside.resolve("custom.txt")));

        Files.delete(target().resolve("linked"));
        Files.createDirectories(legacy());
        Files.createSymbolicLink(legacy().resolve("custom-link"), outside);
        Path cleanJar = jar(Map.of("cer_resource/pack.yml", "pack"));
        assertTrue(ResourcePackInstaller.install(cleanJar, legacy(), target()).installed());
        assertFalse(Files.exists(target().resolve("custom-link")));
    }

    @Test
    void failedInstallCanBeRetriedWithoutOverwritingOrLeavingTemporaryFiles() throws Exception {
        write(target().resolve("configuration"), "blocks a directory");
        Path jar = jar(Map.of("cer_resource/pack.yml", "pack", "cer_resource/configuration/gui.yml", "gui"));
        assertThrows(IOException.class, () -> ResourcePackInstaller.install(jar, legacy(), target()));
        assertEquals("blocks a directory", Files.readString(target().resolve("configuration")));
        assertFalse(Files.exists(marker()));
        Files.delete(target().resolve("configuration"));
        assertTrue(ResourcePackInstaller.install(jar, legacy(), target()).installed());
        assertTrue(Files.isRegularFile(marker()));
        try (var paths = Files.walk(target())) {
            assertFalse(paths.anyMatch(path -> path.getFileName().toString().startsWith(".cer-resource-")));
        }
    }

    @Test
    void renamedCraftEngineDirectoryIsNeverRecreatedOnRestart() throws Exception {
        Path jar = jar(Map.of("cer_resource/pack.yml", "pack", "cer_resource/gui.yml", "gui"));
        ResourcePackInstaller.install(jar, legacy(), target());
        Path renamed = target().resolveSibling("my-custom-pack");
        Files.move(target(), renamed);
        Files.writeString(renamed.resolve("gui.yml"), "customized");
        assertEquals(new ResourcePackInstaller.Result(0, false, 0),
                ResourcePackInstaller.install(jar, legacy(), target()));
        assertFalse(Files.exists(target()));
        assertEquals("customized", Files.readString(renamed.resolve("gui.yml")));
    }

    @Test
    void missingOrNewFilesAreRestoredOnlyToTheLocalCopyAfterInstallation() throws Exception {
        Path jar = jar(Map.of("cer_resource/pack.yml", "pack", "cer_resource/gui.yml", "gui"));
        ResourcePackInstaller.install(jar, legacy(), target());
        Files.delete(legacy().resolve("gui.yml"));
        Files.delete(target().resolve("gui.yml"));
        Files.writeString(legacy().resolve("pack.yml"), "local custom pack");
        Path updated = jar(Map.of("cer_resource/pack.yml", "updated", "cer_resource/gui.yml", "new gui",
                "cer_resource/added.yml", "new file"));
        assertEquals(new ResourcePackInstaller.Result(2, false, 0),
                ResourcePackInstaller.install(updated, legacy(), target()));
        assertEquals("local custom pack", Files.readString(legacy().resolve("pack.yml")));
        assertEquals("new gui", Files.readString(legacy().resolve("gui.yml")));
        assertEquals("new file", Files.readString(legacy().resolve("added.yml")));
        assertFalse(Files.exists(target().resolve("gui.yml")));
        assertFalse(Files.exists(target().resolve("added.yml")));
    }

    @Test
    void reloadRestoresAnEntireMissingLocalDirectoryWithoutReinstallingCraftEngine() throws Exception {
        Path jar = jar(Map.of("cer_resource/pack.yml", "pack"));
        ResourcePackInstaller.install(jar, legacy(), target());
        Files.delete(legacy().resolve("pack.yml"));
        Files.delete(legacy());
        Files.delete(target().resolve("pack.yml"));
        Files.delete(target());
        assertEquals(1, ResourcePackInstaller.replenish(jar, legacy()));
        assertTrue(Files.isRegularFile(legacy().resolve("pack.yml")));
        assertTrue(Files.isRegularFile(marker()));
        assertFalse(Files.exists(target()));
        assertFalse(ResourcePackInstaller.install(jar, legacy(), target()).installed());
        assertFalse(Files.exists(target()));
    }

    @Test
    void localReplenishmentCannotCreateTheInstallationMarkerOnItsOwn() throws Exception {
        Path jar = jar(Map.of("cer_resource/pack.yml", "pack"));
        assertEquals(1, ResourcePackInstaller.replenish(jar, legacy()));
        assertFalse(Files.exists(marker()));
        assertFalse(Files.exists(target()));
        assertEquals(new ResourcePackInstaller.Result(0, true, 1),
                ResourcePackInstaller.install(jar, legacy(), target()));
    }

    private Path marker() {
        return legacy().resolveSibling(ResourcePackInstaller.INSTALL_MARKER);
    }

    private Path target() {
        return folder.resolve("plugins/CraftEngine/resources/cer_resource");
    }

    private Path legacy() {
        return folder.resolve("plugins/CraftEngineRecipe/cer_resource");
    }

    private void write(Path path, String value) throws IOException {
        Files.createDirectories(path.getParent());
        Files.writeString(path, value);
    }

    private Path jar(Map<String, String> entries) throws IOException {
        Path path = Files.createTempFile(folder, "plugin-", ".jar");
        try (var output = new JarOutputStream(Files.newOutputStream(path))) {
            for (var entry : entries.entrySet()) {
                output.putNextEntry(new JarEntry(entry.getKey()));
                output.write(entry.getValue().getBytes(StandardCharsets.UTF_8));
                output.closeEntry();
            }
        }
        return path;
    }
}
