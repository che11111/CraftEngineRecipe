package dev.che1s.craftenginerecipe.resource;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.jar.JarEntry;
import java.util.jar.JarOutputStream;

import static org.junit.jupiter.api.Assertions.*;

class ResourcePackInstallerTest {
    @TempDir Path temporary;

    @Test
    void installsPacksAndRemainsIdempotent() throws IOException {
        Path jar = this.packJar();
        Path pluginData = this.temporary.resolve("plugins/CraftEngineRecipe");
        Path craftEngine = this.temporary.resolve("plugins/CraftEngine/resources");
        Path resources = pluginData.resolve(ResourcePackInstaller.RESOURCES_DIRECTORY);
        Path mainLocal = resources.resolve(ResourcePackInstaller.PACK_DIRECTORY);
        Path guiLocal = resources.resolve(ResourcePackInstaller.GUI_PACK_DIRECTORY);
        Path fdLocal = resources.resolve(ResourcePackInstaller.FD_PACK_DIRECTORY);

        ResourcePackInstaller.Result main = ResourcePackInstaller.installBundled(jar, mainLocal,
                craftEngine.resolve("cer_menu_rough"), "cer_menu_rough");
        ResourcePackInstaller.Result gui = ResourcePackInstaller.installBundled(jar, guiLocal,
                craftEngine.resolve("cer_kc_gui"), "cer_kc_gui");
        ResourcePackInstaller.Result fd = ResourcePackInstaller.installBundled(jar, fdLocal,
                craftEngine.resolve("cer_fd_gui"), "cer_fd_gui");

        assertTrue(main.installed());
        assertTrue(gui.installed());
        assertTrue(fd.installed());
        assertTrue(Files.isRegularFile(craftEngine.resolve("cer_menu_rough/pack.yml")));
        assertTrue(Files.isRegularFile(craftEngine.resolve("cer_kc_gui/pack.yml")));
        assertTrue(Files.isRegularFile(craftEngine.resolve("cer_fd_gui/pack.yml")));
        assertFalse(ResourcePackInstaller.installBundled(jar, mainLocal,
                craftEngine.resolve("cer_menu_rough"), "cer_menu_rough").installed());
        assertFalse(ResourcePackInstaller.installBundled(jar, guiLocal,
                craftEngine.resolve("cer_kc_gui"), "cer_kc_gui").installed());
        assertFalse(ResourcePackInstaller.installBundled(jar, fdLocal,
                craftEngine.resolve("cer_fd_gui"), "cer_fd_gui").installed());
        try (var paths = Files.walk(craftEngine.resolve("cer_menu_rough"))) {
            for (Path path : paths.sorted(java.util.Comparator.reverseOrder()).toList()) Files.delete(path);
        }
        assertTrue(ResourcePackInstaller.installBundled(jar, mainLocal,
                craftEngine.resolve("cer_menu_rough"), "cer_menu_rough").installed());
        assertEquals(Set.of("cer_menu_cute", "cer_menu_rough"),
                ResourcePackInstaller.bundledMenuPacks(jar));
    }

    @Test
    void publishesManagedModulesAsOneCraftEngineSubpackBundle() throws IOException {
        Path local = this.temporary.resolve("local");
        List<String> packs = List.of(
                "cer_menu_cute",
                "cer_kc_gui",
                "cer_fd_gui",
                "cer_ccrops_gui",
                "cer_cultivation_gui",
                "cer_api_gui",
                "cer_manage_gui");
        for (String pack : packs) {
            Path root = local.resolve(pack);
            Files.createDirectories(root.resolve("configuration"));
            Files.writeString(root.resolve("pack.yml"), "namespace: " + pack + "\n");
            Files.writeString(root.resolve("configuration/test.yml"), "test: true\n");
        }
        Files.writeString(local.resolve("cer_fd_gui").resolve(ResourcePackInstaller.STATE_FILE),
                "owner: extension.jar\nversion: 1\n");
        Path target = this.temporary.resolve("CraftEngine/resources");
        Files.createDirectories(target.resolve("cer_fd_gui"));
        Files.createDirectories(target.resolve("cer_menu_rough"));
        Files.createDirectories(target.resolve("unrelated"));

        ResourcePackInstaller.installBundle(local, target, packs);

        Path bundle = target.resolve(ResourcePackInstaller.BUNDLE_DIRECTORY);
        assertTrue(Files.isRegularFile(bundle.resolve("pack.yml")));
        for (String pack : packs) {
            assertTrue(Files.isRegularFile(
                    bundle.resolve("subpacks").resolve(pack).resolve("configuration/test.yml")), pack);
        }
        String metadata = Files.readString(bundle.resolve("pack.yml"));
        assertTrue(metadata.contains("author: CraftEngineRecipe"));
        assertTrue(metadata.contains("version: 1"));
        for (String pack : packs) {
            assertTrue(metadata.contains(pack + ": true"), pack);
            assertFalse(Files.exists(bundle.resolve("subpacks").resolve(pack)
                    .resolve(ResourcePackInstaller.STATE_FILE)), pack);
        }
        assertTrue(Files.isDirectory(target.resolve("cer_fd_gui")));
        assertTrue(Files.isDirectory(target.resolve("cer_menu_rough")));
        assertTrue(Files.isDirectory(target.resolve("unrelated")));
    }

    @Test
    void versionedResourcesUpdateManagedFilesAndPreserveLocalEdits() throws IOException {
        Path local = this.temporary.resolve("versioned/cer_test");
        Map<String, String> first = new LinkedHashMap<>();
        first.put("resourcepack/cer_test/pack.yml", "namespace: cer_test\nversion: 1\n");
        first.put("resourcepack/cer_test/configuration/gui.yml", "image: old\n");
        first.put("resourcepack/cer_test/configuration/managed.yml", "managed: old\n");
        first.put("resourcepack/cer_test/configuration/removed.yml", "remove: me\n");
        Path firstJar = this.jar(first);

        ResourcePackInstaller.replenishPrefix(firstJar, local, "resourcepack/cer_test",
                "cer-ext-test.jar", "1.0.0");
        Files.writeString(local.resolve("configuration/gui.yml"), "image: edited-by-owner\n");

        Map<String, String> second = new LinkedHashMap<>();
        second.put("resourcepack/cer_test/pack.yml", "namespace: cer_test\nversion: 2\n");
        second.put("resourcepack/cer_test/configuration/gui.yml", "image: new\n");
        second.put("resourcepack/cer_test/configuration/managed.yml", "managed: new\n");
        second.put("resourcepack/cer_test/configuration/added.yml", "added: true\n");
        Path secondJar = this.jar(second);

        int copied = ResourcePackInstaller.replenishPrefix(secondJar, local, "resourcepack/cer_test",
                "cer-ext-test.jar", "2.0.0");

        assertEquals(3, copied);
        assertEquals("image: edited-by-owner\n", Files.readString(local.resolve("configuration/gui.yml")));
        assertEquals("managed: new\n", Files.readString(local.resolve("configuration/managed.yml")));
        assertEquals("added: true\n", Files.readString(local.resolve("configuration/added.yml")));
        assertFalse(Files.exists(local.resolve("configuration/removed.yml")));
        assertTrue(Files.readString(local.resolve(ResourcePackInstaller.STATE_FILE)).contains("2.0.0"));
    }

    @Test
    void extensionResourcePacksArePartOfTheManagedSelection() throws IOException {
        List<String> selected = MenuPackManager.selectedPacks(
                ResourcePackInstaller.REQUIRED_CORE_PACKS,
                List.of("cer_menu_cute"),
                List.of("cer_fd_gui", "cer_kc_gui", "cer_fd_gui", "../invalid"));
        assertEquals(List.of(
                        "cer_cultivation_gui",
                        "cer_api_gui",
                        "cer_manage_gui",
                        "cer_recipe_book",
                        "cer_menu_cute",
                        "cer_fd_gui",
                        "cer_kc_gui"),
                selected);

        Path local = this.temporary.resolve("selected-local");
        for (String pack : selected) {
            Path root = local.resolve(pack);
            Files.createDirectories(root);
            Files.writeString(root.resolve("pack.yml"), "namespace: " + pack + "\n");
        }
        Path target = this.temporary.resolve("selected-target");
        ResourcePackInstaller.installBundle(local, target, selected, "test-version");
        for (String pack : selected) {
            assertTrue(Files.isRegularFile(target.resolve(ResourcePackInstaller.BUNDLE_DIRECTORY)
                    .resolve("subpacks").resolve(pack).resolve("pack.yml")), pack);
        }
        assertTrue(Files.readString(target.resolve(ResourcePackInstaller.BUNDLE_DIRECTORY)
                .resolve("pack.yml")).contains("version: test-version"));
    }

    @Test
    void removesLegacyMenuLayoutFilesFromManagedMenuPacks() throws IOException {
        Path pack = this.temporary.resolve("cer_menu_cute");
        Files.createDirectories(pack.resolve("pages"));
        Files.createDirectories(pack.resolve("menu"));
        Files.writeString(pack.resolve("menu.yml"), "browser: {}\n");
        Files.writeString(pack.resolve("pages/namespaces.yml"), "browser: {}\n");
        Files.writeString(pack.resolve("menu/browse.yml"), "pages: [namespaces]\n");

        ResourcePackInstaller.removeLegacyMenuLayouts(pack);

        assertFalse(Files.exists(pack.resolve("menu.yml")));
        assertFalse(Files.exists(pack.resolve("pages")));
        assertTrue(Files.isRegularFile(pack.resolve("menu/browse.yml")));
    }

    private Path packJar() throws IOException {
        Path jar = this.temporary.resolve("CraftEngineRecipe-test.jar");
        try (OutputStream output = Files.newOutputStream(jar);
             JarOutputStream entries = new JarOutputStream(output)) {
            this.entry(entries, "resources/cer_menu_rough/pack.yml", "namespace: cer\nenable: true\n");
            this.entry(entries, "resources/cer_menu_rough/configuration/test.yml", "images: {}\n");
            this.entry(entries, "resources/cer_menu_cute/pack.yml", "namespace: cer\nenable: true\n");
            this.entry(entries, "resources/cer_menu_cute/configuration/gui.yml", "images: {}\n");
            this.entry(entries, "resources/cer_kc_gui/pack.yml", "namespace: cer_kc_gui\nenable: true\n");
            this.entry(entries, "resources/cer_kc_gui/configuration/gui.yml", "images: {}\n");
            this.entry(entries, "resources/cer_fd_gui/pack.yml", "namespace: cer_fd_gui\nenable: true\n");
            this.entry(entries, "resources/cer_fd_gui/configuration/gui.yml", "images: {}\n");
            this.entry(entries, "resources/cer_ccrops_gui/pack.yml",
                    "namespace: cer_ccrops_gui\nenable: true\n");
            this.entry(entries, "resources/cer_ccrops_gui/configuration/gui.yml", "images: {}\n");
            this.entry(entries, "resources/cer_cultivation_gui/pack.yml",
                    "namespace: cer_cultivation_gui\nenable: true\n");
            this.entry(entries, "resources/cer_cultivation_gui/configuration/gui.yml", "images: {}\n");
            this.entry(entries, "resources/cer_api_gui/pack.yml",
                    "namespace: cer_api_gui\nenable: true\n");
            this.entry(entries, "resources/cer_api_gui/configuration/gui.yml", "images: {}\n");
            this.entry(entries, "resources/cer_manage_gui/pack.yml",
                    "namespace: cer_manage\nenable: true\n");
            this.entry(entries, "resources/cer_manage_gui/configuration/gui.yml", "images: {}\n");
        }
        return jar;
    }

    private Path jar(Map<String, String> entriesByPath) throws IOException {
        Path jar = this.temporary.resolve("pack-" + System.nanoTime() + ".jar");
        try (OutputStream output = Files.newOutputStream(jar);
             JarOutputStream entries = new JarOutputStream(output)) {
            for (var entry : entriesByPath.entrySet()) {
                this.entry(entries, entry.getKey(), entry.getValue());
            }
        }
        return jar;
    }

    private void entry(JarOutputStream jar, String path, String contents) throws IOException {
        jar.putNextEntry(new JarEntry(path));
        jar.write(contents.getBytes(StandardCharsets.UTF_8));
        jar.closeEntry();
    }
}
