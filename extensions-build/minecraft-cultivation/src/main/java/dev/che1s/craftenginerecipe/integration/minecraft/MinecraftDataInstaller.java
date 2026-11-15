package dev.che1s.craftenginerecipe.integration.minecraft;

import dev.che1s.craftenginerecipe.api.extension.SourceContext;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.List;
import java.util.UUID;
import java.util.jar.JarEntry;
import java.util.jar.JarFile;

/** Installs exactly one embedded Minecraft data set selected by the running server version. */
final class MinecraftDataInstaller {
    private static final String MARKER = ".cer-data-version";

    private MinecraftDataInstaller() {
    }

    static Path install(SourceContext context, String category) throws IOException {
        String version = context.minecraftVersion();
        String prefix = category + "/minecraft-" + version + "/";
        Path parent = context.extensionDataFolder().resolve(category);
        Path target = parent.resolve("minecraft-" + version);
        String marker = context.extensionVersion() + ":" + version;
        Path markerFile = target.resolve(MARKER);
        if (Files.isDirectory(target) && Files.isRegularFile(markerFile)
                && marker.equals(Files.readString(markerFile, StandardCharsets.UTF_8).trim())) {
            return target;
        }

        List<JarEntry> entries;
        try (JarFile jar = new JarFile(context.extensionJar().toFile(), false)) {
            entries = jar.stream()
                    .filter(entry -> !entry.isDirectory() && entry.getName().startsWith(prefix))
                    .toList();
            if (entries.isEmpty()) {
                throw new IOException("Extension " + context.extensionJar().getFileName()
                        + " does not contain " + category + " data for Minecraft " + version);
            }
            Files.createDirectories(parent);
            Path staging = parent.resolve("." + target.getFileName() + "-" + UUID.randomUUID());
            Files.createDirectories(staging);
            try {
                for (JarEntry entry : entries) {
                    Path output = staging.resolve(entry.getName().substring(prefix.length())).normalize();
                    if (!output.startsWith(staging)) throw new IOException("Unsafe extension entry: "
                            + entry.getName());
                    Files.createDirectories(output.getParent());
                    try (var input = jar.getInputStream(entry)) {
                        Files.copy(input, output, StandardCopyOption.REPLACE_EXISTING);
                    }
                }
                Files.writeString(staging.resolve(MARKER), marker + "\n", StandardCharsets.UTF_8);
                if (Files.exists(target)) deleteRecursively(target);
                Files.move(staging, target, StandardCopyOption.ATOMIC_MOVE);
            } catch (java.nio.file.AtomicMoveNotSupportedException ignored) {
                if (Files.exists(target)) deleteRecursively(target);
                Files.move(staging, target);
            } catch (IOException | RuntimeException error) {
                deleteRecursively(staging);
                throw error;
            }
        }
        return target;
    }

    private static void deleteRecursively(Path directory) throws IOException {
        if (!Files.exists(directory)) return;
        try (var paths = Files.walk(directory)) {
            for (Path path : paths.sorted(java.util.Comparator.reverseOrder()).toList()) {
                Files.deleteIfExists(path);
            }
        }
    }
}
