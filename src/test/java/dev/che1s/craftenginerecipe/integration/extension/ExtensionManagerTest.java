package dev.che1s.craftenginerecipe.integration.extension;

import org.bukkit.configuration.file.YamlConfiguration;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.jar.JarEntry;
import java.util.jar.JarOutputStream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ExtensionManagerTest {
    @TempDir Path temporary;

    @Test
    void dependencyVersionConstraintsSupportRangesAndSnapshots() {
        assertTrue(ExtensionManager.versionMatches("1.3.1-SNAPSHOT", ">=1.3.1"));
        assertTrue(ExtensionManager.versionMatches("26.9.2", ">=26.9.2 <27.0.0"));
        assertFalse(ExtensionManager.versionMatches("1.0.2", ">=1.0.3"));
        assertTrue(ExtensionManager.versionMatches("3.6.58", "=3.6.58"));
        assertTrue(ExtensionManager.versionMatches("1.2.0-alpha4", ">=1.2.0-alpha4"));
        assertTrue(ExtensionManager.versionMatches("1.2.0", ">=1.2.0-alpha4"));
        assertFalse(ExtensionManager.versionMatches("1.2.0-alpha.2", ">=1.2.0-alpha4"));
    }

    @Test
    void managedOwnershipMarkersUseStableUnversionedJarNames() {
        assertEquals("cer-ext-craftengine.jar",
                ExtensionManager.canonicalJarName("cer-ext-craftengine_v1.2.0.jar"));
        assertEquals("cer-ext-farmersdelight.jar",
                ExtensionManager.canonicalJarName("cer-ext-farmersdelight.jar"));
        assertEquals("cer-ext-private-alchemy.jar",
                ExtensionManager.canonicalJarName("cer-ext-private-alchemy_v26.3+build.4.jar"));
    }

    @Test
    void extensionFingerprintChangesWhenJarSetChanges() throws Exception {
        Path first = Files.writeString(this.temporary.resolve("one.jar"), "one");
        List<String> initial = ExtensionManager.fingerprint(List.of(first));

        Path second = Files.writeString(this.temporary.resolve("two.jar"), "two");
        List<String> added = ExtensionManager.fingerprint(List.of(first, second));
        assertNotEquals(initial, added);

        Files.delete(second);
        assertEquals(initial, ExtensionManager.fingerprint(List.of(first)));
    }

    @Test
    void selectsHighestDeclaredExtensionVersionForCanonicalName() throws Exception {
        Path old = extensionJar("cer-ext-same_v1.0.0.jar", "same", "1.0.0");
        Path latest = extensionJar("cer-ext-same_v2.0.0.jar", "same", "2.0.0");

        assertEquals(List.of(latest), ExtensionManager.selectLatestByCanonicalName(
                List.of(latest, old)));
    }

    @Test
    void bundledExtensionsShipUniformDeclarations() {
        Map<String, String> modules = Map.of(
                "craftengine", "craftengine",
                "craftengine_cultivation", "craftengine-cultivation",
                "minecraft_cultivation", "minecraft-cultivation",
                "farmersdelight", "farmersdelight",
                "trailandtalesdelight", "trailtalesdelight",
                "kaleidoscope", "kaleidoscope",
                "customcrops", "customcrops",
                "private_alchemy", "private-alchemy");

        for (var entry : modules.entrySet()) {
            Path file = Path.of("extensions-build", entry.getValue(),
                    "src/main/resources/extension.yml");
            YamlConfiguration yaml = YamlConfiguration.loadConfiguration(file.toFile());
            assertEquals(1, yaml.getInt("schema"), entry.getKey());
            assertEquals(entry.getKey(), yaml.getString("id"), entry.getKey());
            assertEquals("1.2.0", yaml.getString("version"), entry.getKey());
            assertEquals(">=1.2.0-alpha4", yaml.getString("requires-cer"), entry.getKey());
            for (String locale : dev.che1s.craftenginerecipe.i18n.LanguageManager.supportedLocales()) {
                assertFalse(yaml.getString("names." + locale, "").isBlank(),
                        entry.getKey() + " names." + locale);
                assertFalse(yaml.getString("descriptions." + locale, "").isBlank(),
                        entry.getKey() + " descriptions." + locale);
            }
            assertFalse(yaml.getStringList("authors").isEmpty(), entry.getKey());
            assertEquals(dev.che1s.craftenginerecipe.i18n.LanguageManager.supportedLocales(),
                    yaml.getStringList("supported-languages"), entry.getKey());
            assertFalse(yaml.getStringList("integration-button.icons").isEmpty(), entry.getKey());
            String expectedMode = List.of("craftengine", "craftengine_cultivation",
                    "minecraft_cultivation", "trailandtalesdelight").contains(entry.getKey())
                    ? "enabled" : "ask";
            assertEquals(expectedMode, yaml.getString("integration-defaults.mode"), entry.getKey());
            assertTrue(yaml.isBoolean("integration-defaults.cultivation"), entry.getKey());
            assertTrue(yaml.isBoolean("integration-defaults.export-missing"), entry.getKey());
            assertTrue(yaml.isBoolean("integration-defaults.reexport-on-reload"), entry.getKey());
            assertTrue(yaml.isBoolean(
                    "integration-defaults.reexport-craftengine-on-ce-reload"), entry.getKey());

            YamlConfiguration workstation = YamlConfiguration.loadConfiguration(
                    Path.of("extensions-build", entry.getValue(),
                            "src/main/resources/workstation-presets.yml").toFile());
            assertEquals(1, workstation.getInt("schema"), entry.getKey());
            assertTrue(workstation.getBoolean("presentation.styles.en_US.remove-spaces"),
                    entry.getKey());
            assertFalse(workstation.getBoolean("presentation.styles.en_US.bold"), entry.getKey());
            assertTrue(workstation.getBoolean("presentation.styles.zh_CN.bold"), entry.getKey());
            YamlConfiguration categories = YamlConfiguration.loadConfiguration(
                    Path.of("extensions-build", entry.getValue(),
                            "src/main/resources/category-presets.yml").toFile());
            assertEquals(1, categories.getInt("schema"), entry.getKey());
            assertTrue(categories.isList("presets"), entry.getKey());
        }

        YamlConfiguration farmersDelight = YamlConfiguration.loadConfiguration(Path.of(
                "extensions-build", "farmersdelight",
                "src/main/resources/extension.yml").toFile());
        assertEquals("farmersdelight", farmersDelight.getString("language-source"));
        assertEquals(List.of("cer_fd_gui"), farmersDelight.getStringList("resource-packs"));
        assertEquals(List.of("farmersdelight:cooking_pot", "minecraft:cauldron"),
                farmersDelight.getStringList("integration-button.icons"));
        assertTrue(farmersDelight.getMapList("dependencies.plugins").stream()
                .noneMatch(dependency -> dependency.containsKey("package-prefixes")));
        YamlConfiguration workstation = YamlConfiguration.loadConfiguration(Path.of(
                "extensions-build", "farmersdelight",
                "src/main/resources/workstation-presets.yml").toFile());
        assertEquals(List.of("farmersdelight:cooking_pot", "farmersdelight:cutting_board",
                        "farmersdelight:skillet", "farmersdelight:special"),
                workstation.getMapList("navigation").stream()
                        .map(entry -> String.valueOf(entry.get("id"))).toList());

        Path cultivationLang = Path.of("extensions-build", "minecraft-cultivation",
                "src/main/resources/lang");
        for (String locale : dev.che1s.craftenginerecipe.i18n.LanguageManager.supportedLocales()) {
            assertTrue(Files.isRegularFile(cultivationLang.resolve(locale + ".yml")), locale);
        }
    }

    private Path extensionJar(String fileName, String id, String version) throws Exception {
        Path jar = this.temporary.resolve(fileName);
        try (JarOutputStream output = new JarOutputStream(Files.newOutputStream(jar))) {
            output.putNextEntry(new JarEntry("extension.yml"));
            output.write(("schema: 1\nid: " + id + "\nversion: \"" + version
                    + "\"\nrequires-cer: \">=1.2.0-alpha4\"\n")
                    .getBytes(StandardCharsets.UTF_8));
            output.closeEntry();
        }
        return jar;
    }

    @Test
    void workstationPresentationDataControlsCompactionAndBold() {
        ExtensionManager.WorkstationPresentation presentation =
                new ExtensionManager.WorkstationPresentation(
                        List.of("farmersdelight:cooking_pot"),
                        List.of("farmersdelight:"),
                        java.util.Map.of(
                                "default", new ExtensionManager.WorkstationTitleStyle(false, false),
                                "en_us", new ExtensionManager.WorkstationTitleStyle(true, false),
                                "zh_cn", new ExtensionManager.WorkstationTitleStyle(false, true)));

        ExtensionManager.WorkstationTitleStyle english = presentation.style("en_US");
        ExtensionManager.WorkstationTitleStyle chinese = presentation.style("zh_CN");

        assertEquals("Farmer'sDelight·CookingPot",
                english.apply("Farmer's Delight · Cooking Pot"));
        assertFalse(english.bold());
        assertTrue(chinese.bold());
        assertEquals("农夫乐事·烹饪锅", chinese.apply("农夫乐事·烹饪锅"));
    }

    @Test
    void longestMatchingWorkstationPrefixOwnsDynamicEntries() {
        assertEquals("cer-ext-customcrops-pot.jar",
                ExtensionManager.longestMatchingPrefixOwner(
                        "customcrops:pot/default",
                        new java.util.LinkedHashMap<>(Map.of(
                                "cer-ext-customcrops.jar", List.of("customcrops:"),
                                "cer-ext-customcrops-pot.jar",
                                List.of("customcrops:pot/")))));
        assertEquals("", ExtensionManager.longestMatchingPrefixOwner(
                "local:station", Map.of("cer-ext-test.jar", List.of("test:"))));
    }

    @Test
    void extensionLanguageResourcesAreReadFromTheirOwnJar() throws Exception {
        Path jar = this.temporary.resolve("translation-extension.jar");
        try (JarOutputStream output = new JarOutputStream(Files.newOutputStream(jar))) {
            output.putNextEntry(new JarEntry("lang/zh_CN.yml"));
            output.write("station: 原版·耕地\n".getBytes(StandardCharsets.UTF_8));
            output.closeEntry();
        }

        try (var input = ExtensionManager.jarResource(jar, "lang/zh_CN.yml")) {
            assertEquals("station: 原版·耕地\n",
                    new String(input.readAllBytes(), StandardCharsets.UTF_8));
        }
    }
}
