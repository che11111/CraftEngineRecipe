package dev.che1s.craftenginerecipe.resource;


import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.FileAlreadyExistsException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.Set;
import java.util.jar.JarFile;

/** 保留本地副本，并在 CraftEngine 启用前安装一次。 */
public final class ResourcePackInstaller {
    public static final String RESOURCES_DIRECTORY = "resources";
    public static final String BUNDLE_DIRECTORY = "CraftEngineRecipe";
    public static final String PACK_DIRECTORY = "cer_menu_rough";
    public static final String MENU_CUTE_DIRECTORY = "cer_menu_cute";
    public static final String GUI_PACK_DIRECTORY = "cer_kc_gui";
    public static final String FD_PACK_DIRECTORY = "cer_fd_gui";

    private ResourcePackInstaller() {}

    public record Result(int localFiles, boolean installed, int craftEngineFiles) {}

    public static Result install(Path jarPath, Path localRoot, Path targetRoot) throws IOException {
        return install(jarPath, localRoot, targetRoot, PACK_DIRECTORY, PACK_DIRECTORY);
    }

    public static Result installGui(Path jarPath, Path localRoot, Path targetRoot) throws IOException {
        return install(jarPath, localRoot, targetRoot, GUI_PACK_DIRECTORY, GUI_PACK_DIRECTORY);
    }

    public static Result installFd(Path jarPath, Path localRoot, Path targetRoot) throws IOException {
        return install(jarPath, localRoot, targetRoot, FD_PACK_DIRECTORY, FD_PACK_DIRECTORY);
    }

    private static Result install(Path jarPath, Path localRoot, Path targetRoot,
                                  String packDirectory, String jarPrefix) throws IOException {
        int localFiles = replenish(jarPath, localRoot, jarPrefix);
        boolean alreadyInstalled = Files.isRegularFile(
                targetRoot.resolve("pack.yml"), LinkOption.NOFOLLOW_LINKS);
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
        return new Result(localFiles, !alreadyInstalled, copied);
    }

    /** 安装插件 JAR 中 resources/<packId> 下的内置资源包。 */
    public static Result installBundled(Path jarPath, Path localRoot, Path targetRoot, String packId) throws IOException {
        return install(jarPath, localRoot, targetRoot, packId, RESOURCES_DIRECTORY + "/" + packId);
    }

    /** 只在本地补齐缺失的内置文件，不读取或修改 CraftEngine 目录。 */
    public static int replenish(Path jarPath, Path localRoot) throws IOException {
        return replenish(jarPath, localRoot, PACK_DIRECTORY);
    }

    public static int replenishGui(Path jarPath, Path localRoot) throws IOException {
        return replenish(jarPath, localRoot, GUI_PACK_DIRECTORY);
    }

    public static int replenishFd(Path jarPath, Path localRoot) throws IOException {
        return replenish(jarPath, localRoot, FD_PACK_DIRECTORY);
    }

    public static int replenishBundled(Path jarPath, Path localRoot, String packId) throws IOException {
        return replenish(jarPath, localRoot, RESOURCES_DIRECTORY + "/" + packId);
    }

    public static Set<String> bundledMenuPacks(Path jarPath) throws IOException {
        Set<String> packs = new LinkedHashSet<>();
        try (JarFile jar = new JarFile(jarPath.toFile())) {
            var entries = jar.entries();
            while (entries.hasMoreElements()) {
                String name = entries.nextElement().getName();
                if (!name.startsWith(RESOURCES_DIRECTORY + "/cer_menu_") || !name.endsWith("/pack.yml")) continue;
                String rest = name.substring((RESOURCES_DIRECTORY + "/").length());
                int slash = rest.indexOf('/');
                if (slash > 0) packs.add(rest.substring(0, slash));
            }
        }
        return packs;
    }

    /**
     * 将受管模块作为 CraftEngine 子包发布到统一顶层资源目录。
     * 该目标目录完全由本插件管理，因此每次都会重新构建。
     */
    public static void installBundle(Path localRoot, Path targetResources, Collection<String> subpacks)
            throws IOException {
        LinkedHashSet<String> selected = new LinkedHashSet<>(subpacks);
        Path root = targetResources.toAbsolutePath().normalize();
        Files.createDirectories(root);
        Path bundle = child(root, BUNDLE_DIRECTORY);
        deleteRecursively(bundle);
        Path subpackRoot = bundle.resolve("subpacks");
        Files.createDirectories(subpackRoot);
        Path sourceRoot = localRoot.toAbsolutePath().normalize();
        for (String id : selected) {
            Path source = sourceRoot.resolve(id);
            if (!Files.isRegularFile(source.resolve("pack.yml"), LinkOption.NOFOLLOW_LINKS)) {
                throw new IOException("Missing managed resource pack: " + id);
            }
            Path destination = child(subpackRoot, id);
            try (var paths = Files.walk(source)) {
                for (Path path : paths.filter(Files::isRegularFile).toList()) {
                    Path target = child(destination, source.relativize(path).toString());
                    Files.createDirectories(target.getParent());
                    Files.copy(path, target);
                }
            }
        }
        StringBuilder metadata = new StringBuilder()
                .append("author: CraftEngineRecipe\n")
                .append("version: 1\n")
                .append("description: CraftEngineRecipe managed resource bundle\n")
                .append("namespace: cer_bundle\n")
                .append("enable: true\n")
                .append("subpacks:\n");
        for (String id : selected) metadata.append("  ").append(id).append(": true\n");
        Files.writeString(bundle.resolve("pack.yml"), metadata.toString(), StandardCharsets.UTF_8);
    }

    private static void deleteRecursively(Path directory) throws IOException {
        if (!Files.exists(directory, LinkOption.NOFOLLOW_LINKS)) return;
        try (var paths = Files.walk(directory)) {
            for (Path path : paths.sorted(java.util.Comparator.reverseOrder()).toList()) {
                Files.deleteIfExists(path);
            }
        }
    }

    private static int replenish(Path jarPath, Path localRoot, String packDirectory) throws IOException {
        Path root = localRoot.toAbsolutePath().normalize();
        int copied = 0;
        String prefix = packDirectory + "/";
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
        // 不跟随受管资源包中的符号链接，避免访问无关服务器文件。
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
            // 不使用 REPLACE_EXISTING：即使文件在复制期间出现，也要保留它。
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
