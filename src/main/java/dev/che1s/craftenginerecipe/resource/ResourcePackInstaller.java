package dev.che1s.craftenginerecipe.resource;


import org.bukkit.configuration.InvalidConfigurationException;
import org.bukkit.configuration.file.YamlConfiguration;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.FileAlreadyExistsException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Collection;
import java.util.Base64;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.jar.JarEntry;
import java.util.jar.JarFile;

/** 保留本地副本，并在 CraftEngine 启用前安装一次。 */
public final class ResourcePackInstaller {
    public static final String RESOURCES_DIRECTORY = "resources";
    public static final String BUNDLE_DIRECTORY = "CraftEngineRecipe";
    public static final String STATE_FILE = ".cer-resource-state.yml";
    public static final String PACK_DIRECTORY = "cer_menu_rough";
    public static final String MENU_CUTE_DIRECTORY = "cer_menu_cute";
    public static final String GUI_PACK_DIRECTORY = "cer_kc_gui";
    public static final String FD_PACK_DIRECTORY = "cer_fd_gui";
    public static final String CUSTOMCROPS_PACK_DIRECTORY = "cer_ccrops_gui";
    public static final String CULTIVATION_PACK_DIRECTORY = "cer_cultivation_gui";
    public static final String API_PACK_DIRECTORY = "cer_api_gui";
    public static final String MANAGEMENT_PACK_DIRECTORY = "cer_manage_gui";
    /**
     * Packs whose lifetime is owned by the core. Source-specific packs
     * ({@code cer_kc_gui}, {@code cer_fd_gui}, {@code cer_ccrops_gui}) are installed from their
     * extension JARs and must not be replenished from the core as empty fallbacks.
     */
    public static final List<String> REQUIRED_GUI_PACKS = List.of(
            CULTIVATION_PACK_DIRECTORY,
            API_PACK_DIRECTORY,
            MANAGEMENT_PACK_DIRECTORY);

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
        int localFiles = replenish(jarPath, localRoot, jarPrefix, "", "legacy");
        boolean alreadyInstalled = Files.isRegularFile(
                targetRoot.resolve("pack.yml"), LinkOption.NOFOLLOW_LINKS);
        Path root = targetRoot.toAbsolutePath().normalize();
        int copied = 0;
        if (Files.isDirectory(localRoot, LinkOption.NOFOLLOW_LINKS)) {
            try (var paths = Files.walk(localRoot)) {
                for (Path source : paths.filter(path -> Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS))
                        .filter(path -> !path.getFileName().toString().equals(STATE_FILE))
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
        return replenish(jarPath, localRoot, PACK_DIRECTORY, "", "legacy");
    }

    public static int replenishGui(Path jarPath, Path localRoot) throws IOException {
        return replenish(jarPath, localRoot, GUI_PACK_DIRECTORY, "", "legacy");
    }

    public static int replenishFd(Path jarPath, Path localRoot) throws IOException {
        return replenish(jarPath, localRoot, FD_PACK_DIRECTORY, "", "legacy");
    }

    public static int replenishBundled(Path jarPath, Path localRoot, String packId) throws IOException {
        return replenishBundled(jarPath, localRoot, packId, "", "legacy");
    }

    public static int replenishBundled(Path jarPath, Path localRoot, String packId,
                                       String owner, String version) throws IOException {
        return replenish(jarPath, localRoot, RESOURCES_DIRECTORY + "/" + packId, owner, version);
    }

    /** 从扩展 JAR 的指定前缀释放资源包，例如 resourcepack/cer_fd_gui。 */
    public static int replenishPrefix(Path jarPath, Path localRoot, String prefix) throws IOException {
        return replenish(jarPath, localRoot, prefix, "", "legacy");
    }

    public static int replenishPrefix(Path jarPath, Path localRoot, String prefix,
                                      String owner, String version) throws IOException {
        return replenish(jarPath, localRoot, prefix, owner, version);
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
        installBundle(localRoot, targetResources, subpacks, "1");
    }

    /** Builds the managed bundle in a staging directory, then swaps it into place. */
    public static void installBundle(Path localRoot, Path targetResources, Collection<String> subpacks,
                                     String version) throws IOException {
        LinkedHashSet<String> selected = new LinkedHashSet<>(subpacks);
        Path root = targetResources.toAbsolutePath().normalize();
        Files.createDirectories(root);
        Path bundle = child(root, BUNDLE_DIRECTORY);
        Path staging = root.resolve("." + BUNDLE_DIRECTORY + "-staging-" + UUID.randomUUID());
        Path backup = root.resolve("." + BUNDLE_DIRECTORY + "-backup-" + UUID.randomUUID());
        Path subpackRoot = staging.resolve("subpacks");
        Files.createDirectories(subpackRoot);
        Path sourceRoot = localRoot.toAbsolutePath().normalize();
        try {
            for (String id : selected) {
                Path source = child(sourceRoot, id);
                if (!Files.isRegularFile(source.resolve("pack.yml"), LinkOption.NOFOLLOW_LINKS)) {
                    throw new IOException("Missing managed resource pack: " + id);
                }
                Path destination = child(subpackRoot, id);
                try (var paths = Files.walk(source)) {
                    for (Path path : paths.filter(Files::isRegularFile)
                            .filter(value -> !value.getFileName().toString().equals(STATE_FILE)).toList()) {
                        Path target = child(destination, source.relativize(path).toString());
                        Files.createDirectories(target.getParent());
                        Files.copy(path, target);
                    }
                }
            }
            StringBuilder metadata = new StringBuilder()
                    .append("author: CraftEngineRecipe\n")
                    .append("version: ").append(version == null || version.isBlank() ? "1" : version).append("\n")
                    .append("description: CraftEngineRecipe managed resource bundle\n")
                    .append("namespace: cer_bundle\n")
                    .append("enable: true\n")
                    .append("subpacks:\n");
            for (String id : selected) metadata.append("  ").append(id).append(": true\n");
            Files.writeString(staging.resolve("pack.yml"), metadata.toString(), StandardCharsets.UTF_8);
            replaceDirectory(staging, bundle, backup);
        } finally {
            deleteRecursively(staging);
        }
    }

    private static void replaceDirectory(Path staging, Path target, Path backup) throws IOException {
        if (!Files.exists(target, LinkOption.NOFOLLOW_LINKS)) {
            moveDirectory(staging, target);
            return;
        }
        moveDirectory(target, backup);
        try {
            moveDirectory(staging, target);
        } catch (IOException error) {
            if (Files.exists(backup, LinkOption.NOFOLLOW_LINKS)
                    && !Files.exists(target, LinkOption.NOFOLLOW_LINKS)) {
                try {
                    moveDirectory(backup, target);
                } catch (IOException restoreError) {
                    error.addSuppressed(restoreError);
                }
            }
            throw error;
        }
        deleteRecursively(backup);
    }

    private static void moveDirectory(Path source, Path target) throws IOException {
        try {
            Files.move(source, target, StandardCopyOption.ATOMIC_MOVE);
        } catch (AtomicMoveNotSupportedException ignored) {
            Files.move(source, target);
        }
    }

    static void deleteRecursively(Path directory) throws IOException {
        if (!Files.exists(directory, LinkOption.NOFOLLOW_LINKS)) return;
        try (var paths = Files.walk(directory)) {
            for (Path path : paths.sorted(java.util.Comparator.reverseOrder()).toList()) {
                Files.deleteIfExists(path);
            }
        }
    }

    /** 删除早期测试结构，避免旧 menu.yml 与 pages/ 混入新的 menu/ 声明文件。 */
    static void removeLegacyMenuLayouts(Path packRoot) throws IOException {
        Files.deleteIfExists(packRoot.resolve("menu.yml"));
        deleteRecursively(packRoot.resolve("pages"));
    }

    private static int replenish(Path jarPath, Path localRoot, String packDirectory,
                                 String owner, String version) throws IOException {
        Path root = localRoot.toAbsolutePath().normalize();
        int copied = 0;
        String prefix = packDirectory + "/";
        Map<String, JarEntry> entriesByName = new LinkedHashMap<>();
        Map<String, String> bundledHashes = new LinkedHashMap<>();
        try (JarFile jar = new JarFile(jarPath.toFile())) {
            var entries = jar.entries();
            while (entries.hasMoreElements()) {
                var entry = entries.nextElement();
                if (entry.isDirectory() || !entry.getName().startsWith(prefix)) continue;
                String relative = entry.getName().substring(prefix.length());
                if (relative.isBlank() || relative.equals(STATE_FILE)) continue;
                child(root, relative);
                entriesByName.put(relative, entry);
                try (InputStream input = jar.getInputStream(entry)) {
                    bundledHashes.put(relative, sha256(input));
                }
            }
            State previous = readState(root);
            boolean versionChanged = !owner.equals(previous.owner())
                    || !version.equals(previous.version())
                    || !bundledHashes.equals(previous.files());
            for (var prepared : entriesByName.entrySet()) {
                Path target = child(root, prepared.getKey());
                try (InputStream input = jar.getInputStream(prepared.getValue())) {
                    if (!Files.exists(target, LinkOption.NOFOLLOW_LINKS)) {
                        copied += copyMissing(input, root, target);
                    } else if (versionChanged && matchesHash(target, previous.files().get(prepared.getKey()))) {
                        copyReplacing(input, root, target);
                        copied++;
                    }
                }
            }
            if (versionChanged) {
                for (var previousFile : previous.files().entrySet()) {
                    if (entriesByName.containsKey(previousFile.getKey())) continue;
                    Path target = child(root, previousFile.getKey());
                    if (matchesHash(target, previousFile.getValue())) Files.deleteIfExists(target);
                }
            }
            writeState(root, new State(owner, version, Map.copyOf(bundledHashes)));
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

    private static void copyReplacing(InputStream input, Path root, Path target) throws IOException {
        child(root, root.relativize(target).toString());
        Files.createDirectories(target.getParent());
        Path temporary = Files.createTempFile(target.getParent(), ".cer-resource-", ".tmp");
        try {
            Files.copy(input, temporary, StandardCopyOption.REPLACE_EXISTING);
            Files.move(temporary, target, StandardCopyOption.REPLACE_EXISTING);
        } finally {
            Files.deleteIfExists(temporary);
        }
    }

    private static State readState(Path root) {
        Path file = root.resolve(STATE_FILE);
        if (!Files.isRegularFile(file, LinkOption.NOFOLLOW_LINKS)) return State.EMPTY;
        try {
            YamlConfiguration yaml = new YamlConfiguration();
            yaml.load(file.toFile());
            Map<String, String> files = new LinkedHashMap<>();
            var section = yaml.getConfigurationSection("files");
            if (section != null) {
                for (String key : section.getKeys(false)) {
                    String hash = section.getString(key);
                    if (hash != null && !hash.isBlank()) {
                        try {
                            files.put(new String(Base64.getUrlDecoder().decode(key), StandardCharsets.UTF_8),
                                    hash);
                        } catch (IllegalArgumentException ignored) {
                        }
                    }
                }
            }
            return new State(yaml.getString("owner", ""), yaml.getString("version", ""), Map.copyOf(files));
        } catch (IOException | InvalidConfigurationException | RuntimeException ignored) {
            return State.EMPTY;
        }
    }

    private static void writeState(Path root, State state) throws IOException {
        YamlConfiguration yaml = new YamlConfiguration();
        yaml.set("owner", state.owner());
        yaml.set("version", state.version());
        for (var entry : state.files().entrySet()) {
            yaml.set("files." + Base64.getUrlEncoder().withoutPadding().encodeToString(
                    entry.getKey().getBytes(StandardCharsets.UTF_8)), entry.getValue());
        }
        Path target = root.resolve(STATE_FILE);
        Path temporary = Files.createTempFile(root, ".cer-state-", ".tmp");
        try {
            Files.writeString(temporary, yaml.saveToString(), StandardCharsets.UTF_8);
            try {
                Files.move(temporary, target, StandardCopyOption.ATOMIC_MOVE,
                        StandardCopyOption.REPLACE_EXISTING);
            } catch (AtomicMoveNotSupportedException ignored) {
                Files.move(temporary, target, StandardCopyOption.REPLACE_EXISTING);
            }
        } finally {
            Files.deleteIfExists(temporary);
        }
    }

    private static boolean matchesHash(Path file, String expected) throws IOException {
        if (expected == null || expected.isBlank()
                || !Files.isRegularFile(file, LinkOption.NOFOLLOW_LINKS)) return false;
        try (InputStream input = Files.newInputStream(file)) {
            return expected.equals(sha256(input));
        }
    }

    private static String sha256(InputStream input) throws IOException {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] buffer = new byte[8192];
            int read;
            while ((read = input.read(buffer)) >= 0) {
                if (read > 0) digest.update(buffer, 0, read);
            }
            return HexFormat.of().formatHex(digest.digest());
        } catch (NoSuchAlgorithmException error) {
            throw new IllegalStateException("SHA-256 is unavailable", error);
        }
    }

    private record State(String owner, String version, Map<String, String> files) {
        private static final State EMPTY = new State("", "", Map.of());
    }
}
