package dev.che1s.craftenginerecipe.integration.extension;

import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.FileAlreadyExistsException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.function.Function;
import java.util.jar.JarFile;
import java.util.logging.Logger;
import java.util.stream.Stream;

/**
 * Installs the small source extensions embedded in the core JAR.
 *
 * <p>The extensions directory itself is the first-run marker. If it already exists, even when
 * empty, this installer deliberately does nothing. A temporary sibling directory prevents a
 * partial extension directory from being created when an embedded resource is missing.</p>
 */
public final class BuiltInExtensionInstaller {
    static final String RESOURCE_DIRECTORY = "META-INF/cer-embedded-extensions/";
    static final String INDEX_RESOURCE = RESOURCE_DIRECTORY + "index.txt";

    private BuiltInExtensionInstaller() {
    }

    /**
     * Creates and populates the extension directory only when the directory does not yet exist.
     *
     * @return true when this invocation created the directory and installed bundled extensions
     */
    public static boolean installOnFirstRun(
            Path dataFolder,
            Function<String, InputStream> resources,
            Logger logger) {
        Path extensions = dataFolder.resolve(ExtensionManager.DIRECTORY_NAME);
        if (Files.exists(extensions, LinkOption.NOFOLLOW_LINKS)) return false;

        Path staging = null;
        try {
            Files.createDirectories(dataFolder);
            staging = Files.createTempDirectory(dataFolder, ".extensions-bootstrap-");
            List<String> embeddedExtensions = readIndex(resources);
            for (String fileName : embeddedExtensions) {
                String resourceName = RESOURCE_DIRECTORY + fileName;
                try (InputStream input = resources.apply(resourceName)) {
                    if (input == null) {
                        throw new IOException("Missing embedded extension resource " + resourceName);
                    }
                    Path target = staging.resolve(fileName);
                    Files.copy(input, target);
                    validateJar(target, fileName);
                }
            }
            moveIntoPlace(staging, extensions);
            staging = null;
            logger.info("Created " + extensions + " and installed "
                    + embeddedExtensions.size() + " bundled CraftEngineRecipe extension(s)");
            return true;
        } catch (FileAlreadyExistsException ignored) {
            // Another initializer created the directory first; it remains the first-run marker.
            return false;
        } catch (IOException | RuntimeException error) {
            logger.warning("Cannot install bundled CraftEngineRecipe extensions into "
                    + extensions + ": " + error.getMessage());
            return false;
        } finally {
            if (staging != null) deleteRecursively(staging);
        }
    }

    private static List<String> readIndex(Function<String, InputStream> resources) throws IOException {
        try (InputStream input = resources.apply(INDEX_RESOURCE)) {
            if (input == null) {
                throw new IOException("Missing embedded extension index " + INDEX_RESOURCE);
            }
            LinkedHashSet<String> files = new LinkedHashSet<>();
            try (var reader = new InputStreamReader(input, StandardCharsets.UTF_8);
                 var lines = new java.io.BufferedReader(reader).lines()) {
                lines.map(String::trim)
                        .filter(line -> !line.isEmpty())
                        .forEach(line -> {
                            if (!line.matches("[A-Za-z0-9._-]+\\.jar")) {
                                throw new IllegalArgumentException(
                                        "Invalid embedded extension filename: " + line);
                            }
                            files.add(line);
                        });
            }
            if (files.isEmpty()) {
                throw new IOException("Embedded extension index is empty");
            }
            return List.copyOf(files);
        }
    }

    private static void validateJar(Path path, String fileName) throws IOException {
        try (JarFile jar = new JarFile(path.toFile(), false)) {
            if (jar.getManifest() == null) {
                throw new IOException("Bundled extension has no manifest: " + fileName);
            }
        }
    }

    private static void moveIntoPlace(Path staging, Path extensions) throws IOException {
        try {
            Files.move(staging, extensions, StandardCopyOption.ATOMIC_MOVE);
        } catch (AtomicMoveNotSupportedException ignored) {
            Files.move(staging, extensions);
        }
    }

    private static void deleteRecursively(Path root) {
        try (Stream<Path> paths = Files.walk(root)) {
            paths.sorted(Comparator.reverseOrder()).forEach(path -> {
                try {
                    Files.deleteIfExists(path);
                } catch (IOException ignored) {
                    // Best-effort cleanup of this invocation's private staging directory.
                }
            });
        } catch (IOException ignored) {
            // Best-effort cleanup; the staging path is never used as the extension directory.
        }
    }
}
