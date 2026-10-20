package dev.che1s.craftenginerecipe.resource;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.FileAlreadyExistsException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.util.jar.JarFile;

/** Keeps a local copy and installs into CraftEngine once, before CraftEngine enables. */
public final class ResourcePackInstaller {
    public static final String PACK_DIRECTORY = "cer_resource";
    public static final String INSTALL_MARKER = "resource-pack-installed.marker";

    private ResourcePackInstaller() {}

    public record Result(int localFiles, boolean installed, int craftEngineFiles) {}

    public static Result install(Path jarPath, Path localRoot, Path targetRoot) throws IOException {
        int localFiles = replenish(jarPath, localRoot);
        // Store state outside both pack directories so renaming/deleting either pack cannot reset it.
        Path marker = localRoot.toAbsolutePath().normalize().resolveSibling(INSTALL_MARKER);
        if (Files.isRegularFile(marker, LinkOption.NOFOLLOW_LINKS)) {
            return new Result(localFiles, false, 0);
        }
        if (Files.exists(marker, LinkOption.NOFOLLOW_LINKS)) {
            throw new IOException("Invalid resource installation marker: " + marker);
        }
        Path root = targetRoot.toAbsolutePath().normalize();
        int copied = 0;
        if (Files.isDirectory(localRoot, LinkOption.NOFOLLOW_LINKS)) {
            try (var paths = Files.walk(localRoot)) {
                for (Path source : paths.filter(path -> Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS))
                        .toList()) {
                    Path target = child(root, localRoot.relativize(source).toString());
                    if (Files.exists(target, LinkOption.NOFOLLOW_LINKS)) continue;
                    try (InputStream input = Files.newInputStream(source)) {
                        copied += copyMissing(input, root, target);
                    }
                }
            }
        }
        // Commit only after all files have been copied successfully; a failed install retries on startup.
        Files.writeString(marker, "CraftEngine resource pack installed. Keep this file after renaming the pack.\n"
                + "CraftEngine 资源包已安装；修改资源包目录名后请保留此文件。\n",
                java.nio.file.StandardOpenOption.CREATE_NEW);
        return new Result(localFiles, true, copied);
    }

    /** Restores missing bundled files locally without reading or modifying CraftEngine's directory. */
    public static int replenish(Path jarPath, Path localRoot) throws IOException {
        Path root = localRoot.toAbsolutePath().normalize();
        int copied = 0;
        String prefix = PACK_DIRECTORY + "/";
        try (JarFile jar = new JarFile(jarPath.toFile())) {
            var entries = jar.entries();
            while (entries.hasMoreElements()) {
                var entry = entries.nextElement();
                if (entry.isDirectory() || !entry.getName().startsWith(prefix)) continue;
                Path target = child(root, entry.getName().substring(prefix.length()));
                if (Files.exists(target, LinkOption.NOFOLLOW_LINKS)) continue;
                try (InputStream input = jar.getInputStream(entry)) {
                    copied += copyMissing(input, root, target);
                }
            }
        }
        if (!Files.isRegularFile(child(root, "pack.yml"), LinkOption.NOFOLLOW_LINKS)) {
            throw new IOException("Missing bundled resource pack.yml");
        }
        return copied;
    }

    private static Path child(Path root, String name) throws IOException {
        Path target = root.resolve(name).normalize();
        if (!target.startsWith(root) || target.equals(root)) {
            throw new IOException("Invalid resource path: " + name);
        }
        // Do not follow links inside the managed pack into unrelated server files.
        Path current = root;
        if (Files.isSymbolicLink(current)) throw new IOException("Symbolic link in resource path: " + current);
        for (Path part : root.relativize(target)) {
            current = current.resolve(part);
            if (Files.isSymbolicLink(current)) throw new IOException("Symbolic link in resource path: " + current);
        }
        return target;
    }

    private static int copyMissing(InputStream input, Path root, Path target) throws IOException {
        child(root, root.relativize(target).toString());
        Files.createDirectories(target.getParent());
        Path temporary = Files.createTempFile(target.getParent(), ".cer-resource-", ".tmp");
        try {
            Files.copy(input, temporary, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
            // No REPLACE_EXISTING: preserve a file even if it appeared during the copy.
            try {
                Files.move(temporary, target);
                return 1;
            } catch (FileAlreadyExistsException ignored) {
                return 0;
            }
        } finally {
            Files.deleteIfExists(temporary);
        }
    }
}
