package dev.che1s.craftenginerecipe.integration.extension;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.jar.Attributes;
import java.util.jar.JarEntry;
import java.util.jar.JarOutputStream;
import java.util.jar.Manifest;
import java.util.logging.Logger;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class BuiltInExtensionInstallerTest {
    private static final Logger LOGGER = Logger.getAnonymousLogger();
    private static final List<String> EMBEDDED_FILES = List.of(
            "cer-ext-craftengine_v1.2.0.jar",
            "cer-ext-craftengine-cultivation_v1.2.0.jar",
            "cer-ext-minecraft-cultivation_v1.2.0.jar");

    @TempDir Path temporary;

    @Test
    void createsDirectoryAndInstallsAllBundledExtensionsOnFirstRun() throws Exception {
        byte[] jar = extensionJar("craftengine");
        Path extensions = temporary.resolve(ExtensionManager.DIRECTORY_NAME);
        assertFalse(Files.exists(extensions));

        boolean installed = BuiltInExtensionInstaller.installOnFirstRun(
                temporary, fileName -> resource(fileName, jar), LOGGER);

        assertTrue(installed);
        assertTrue(Files.isDirectory(extensions));
        for (String fileName : EMBEDDED_FILES) {
            assertArrayEquals(jar, Files.readAllBytes(extensions.resolve(fileName)), fileName);
        }
    }

    @Test
    void existingEmptyDirectoryPreventsInstallationAndFutureRepair() throws Exception {
        Path extensions = Files.createDirectories(
                temporary.resolve(ExtensionManager.DIRECTORY_NAME));
        byte[] jar = extensionJar("ignored");

        assertFalse(BuiltInExtensionInstaller.installOnFirstRun(
                temporary, fileName -> resource(fileName, jar), LOGGER));
        try (Stream<Path> entries = Files.list(extensions)) {
            assertTrue(entries.findAny().isEmpty());
        }

        Files.delete(extensions);
        assertTrue(BuiltInExtensionInstaller.installOnFirstRun(
                temporary, fileName -> resource(fileName, jar), LOGGER));
        Path removed = extensions.resolve(EMBEDDED_FILES.getFirst());
        Files.delete(removed);

        assertFalse(BuiltInExtensionInstaller.installOnFirstRun(
                temporary, fileName -> resource(fileName, jar), LOGGER));
        assertFalse(Files.exists(removed), "existing extensions directory must not be repaired");
    }

    @Test
    void failedExtractionDoesNotCreateExtensionsDirectory() {
        boolean installed = BuiltInExtensionInstaller.installOnFirstRun(
                temporary, fileName -> null, LOGGER);

        assertFalse(installed);
        assertFalse(Files.exists(temporary.resolve(ExtensionManager.DIRECTORY_NAME)));
    }

    private static InputStream resource(String fileName, byte[] bytes) {
        if (fileName.equals(BuiltInExtensionInstaller.INDEX_RESOURCE)) {
            return new ByteArrayInputStream(String.join("\n", EMBEDDED_FILES)
                    .getBytes(StandardCharsets.UTF_8));
        }
        return EMBEDDED_FILES.stream().anyMatch(fileName::endsWith)
                ? new ByteArrayInputStream(bytes) : null;
    }

    private static byte[] extensionJar(String marker) throws IOException {
        Manifest manifest = new Manifest();
        manifest.getMainAttributes().put(Attributes.Name.MANIFEST_VERSION, "1.0");
        manifest.getMainAttributes().putValue(
                ExtensionManager.MANIFEST_PROVIDER_CLASS,
                "example." + marker + ".Provider");
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        try (JarOutputStream output = new JarOutputStream(bytes, manifest)) {
            output.putNextEntry(new JarEntry("marker.txt"));
            output.write(marker.getBytes(java.nio.charset.StandardCharsets.UTF_8));
            output.closeEntry();
        }
        return bytes.toByteArray();
    }
}
