package dev.che1s.craftenginerecipe.i18n;

import com.google.gson.JsonParser;
import dev.che1s.craftenginerecipe.data.CraftEngineData;
import org.bukkit.configuration.file.YamlConfiguration;
import org.junit.jupiter.api.Test;
import org.yaml.snakeyaml.Yaml;

import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class LanguageBundleCoverageTest {
    private static final Pattern PLACEHOLDER =
            Pattern.compile("\\{[^}]+}|%[A-Za-z0-9_.%+-]+|&[0-9a-fk-orx]");

    @Test
    void allBundledLanguageTreesHaveMatchingKeysAndPlaceholders() throws Exception {
        List<Path> directories = new ArrayList<>();
        directories.add(Path.of("src/main/resources/lang"));
        try (var paths = Files.list(Path.of("src/main/resources/resources"))) {
            paths.filter(path -> path.getFileName().toString().startsWith("cer_menu_"))
                    .map(path -> path.resolve("lang"))
                    .filter(Files::isDirectory)
                    .sorted()
                    .forEach(directories::add);
        }
        for (String module : List.of("craftengine", "craftengine-cultivation", "minecraft",
                "minecraft-cultivation", "farmersdelight", "kaleidoscope", "customcrops",
                "private-alchemy")) {
            directories.add(Path.of("extensions-build", module, "src/main/resources/lang"));
        }

        for (Path directory : directories) {
            Map<String, String> english = read(directory.resolve("en_US.yml"));
            for (String locale : LanguageManager.supportedLocales()) {
                Path file = directory.resolve(locale + ".yml");
                assertTrue(Files.isRegularFile(file), file.toString());
                Map<String, String> translated = read(file);
                assertEquals(english.keySet(), translated.keySet(), file.toString());
                for (String key : english.keySet()) {
                    assertEquals(placeholders(english.get(key)), placeholders(translated.get(key)),
                            file + " " + key);
                }
            }
        }
    }

    @Test
    void workstationTitlesDoNotUseSpacesAroundMiddleDots() throws Exception {
        List<String> modules = List.of("craftengine", "craftengine-cultivation", "minecraft",
                "minecraft-cultivation", "farmersdelight", "kaleidoscope", "customcrops",
                "private-alchemy");
        for (String module : modules) {
            Path directory = Path.of("extensions-build", module, "src/main/resources/lang");
            for (String locale : LanguageManager.supportedLocales()) {
                Map<String, String> values = read(directory.resolve(locale + ".yml"));
                for (var entry : values.entrySet()) {
                    if (!isWorkstationTitleKey(entry.getKey())) continue;
                    assertFalse(entry.getValue().contains(" · "),
                            directory.resolve(locale + ".yml") + " " + entry.getKey());
                }
            }
        }

        for (String locale : LanguageManager.supportedLocales()) {
            Path file = Path.of("src/main/resources/lang", locale + ".yml");
            for (var entry : read(file).entrySet()) {
                if (!entry.getKey().startsWith("ui.workstations.categories.")
                        && !entry.getKey().startsWith("ui.recipe.title-")) continue;
                assertFalse(entry.getValue().contains(" · "),
                        file + " " + entry.getKey());
            }
        }

        YamlConfiguration german = YamlConfiguration.loadConfiguration(
                Path.of("src/main/resources/lang/de_DE.yml").toFile());
        assertEquals("Vanille·Basteltisch",
                german.getString("ui.workstations.categories.crafting"));
    }

    @Test
    void commonIntegrationLanguageNamespaceIsNotBundled() {
        assertFalse(Files.exists(Path.of("src/main/resources/lang/integrations/common")));
    }

    @Test
    void everyExtensionDeclaresAndShipsAllNineLanguages() throws Exception {
        for (String module : List.of("craftengine", "craftengine-cultivation", "minecraft",
                "minecraft-cultivation", "farmersdelight", "kaleidoscope", "customcrops",
                "private-alchemy")) {
            Path manifest = Path.of("extensions-build", module, "src/main/resources/extension.yml");
            YamlConfiguration yaml = YamlConfiguration.loadConfiguration(manifest.toFile());
            assertEquals(LanguageManager.supportedLocales(), yaml.getStringList("supported-languages"),
                    manifest.toString());
            for (String locale : LanguageManager.supportedLocales()) {
                assertFalse(yaml.getString("names." + locale, "").isBlank(),
                        manifest + " names." + locale);
                assertFalse(yaml.getString("descriptions." + locale, "").isBlank(),
                        manifest + " descriptions." + locale);
            }
        }
    }

    @Test
    void minecraftExtensionShipsNineOfficialVanillaTranslationsPerVersion() throws Exception {
        Path root = Path.of("extensions-build/minecraft/src/main/resources/vanilla-lang");
        for (String version : List.of(
                "minecraft-1.21.7", "minecraft-1.21.8", "minecraft-1.21.9",
                "minecraft-1.21.10", "minecraft-1.21.11",
                "minecraft-26.1", "minecraft-26.2", "minecraft-26.3")) {
            for (String locale : LanguageManager.supportedLocales()) {
                Path file = root.resolve(version).resolve(locale.toLowerCase() + ".json");
                assertTrue(Files.isRegularFile(file), file.toString());
                try (Reader reader = Files.newBufferedReader(file, StandardCharsets.UTF_8)) {
                    assertTrue(JsonParser.parseReader(reader).getAsJsonObject().size() > 1000,
                            file.toString());
                }
            }
        }
    }

    @Test
    void titleBoldComesFromLanguageFilesAndOnlyChineseKeepsIt() throws Exception {
        for (String locale : LanguageManager.supportedLocales()) {
            YamlConfiguration yaml = YamlConfiguration.loadConfiguration(
                    Path.of("src/main/resources/lang", locale + ".yml").toFile());
            boolean chinese = locale.startsWith("zh_");
            for (String path : List.of(
                    "ui.browser.title",
                    "ui.workstations.title",
                    "ui.recipe.title")) {
                String title = yaml.getString(path);
                assertNotNull(title, locale + " " + path);
                assertEquals(chinese, title.contains("<bold>"), locale + " " + path);
            }
        }
        YamlConfiguration config = YamlConfiguration.loadConfiguration(
                Path.of("src/main/resources/config.yml").toFile());
        for (String path : List.of("category-title", "workstation-title", "recipe-title")) {
            assertFalse(config.getString("name-prefixes." + path, "").contains("<bold>"), path);
        }
    }

    @Test
    void oldSearchLanguageListsAreExpandedToAllSupportedLocales() {
        assertEquals(List.of(
                        "ja_jp", "zh_cn", "en_us", "zh_tw", "ko_kr",
                        "de_de", "fr_fr", "es_es", "ru_ru"),
                CraftEngineData.searchLanguages(List.of("ja_jp", "zh_cn", "en_us", "ja_jp")));
    }

    private Map<String, String> read(Path file) throws Exception {
        Object root;
        try (Reader reader = Files.newBufferedReader(file, StandardCharsets.UTF_8)) {
            root = new Yaml().load(reader);
        }
        Map<String, String> values = new LinkedHashMap<>();
        flatten(root, "", values);
        return values;
    }

    private void flatten(Object value, String path, Map<String, String> output) {
        if (value instanceof Map<?, ?> map) {
            map.forEach((key, child) -> flatten(child,
                    path.isEmpty() ? String.valueOf(key) : path + "." + key, output));
        } else if (value instanceof List<?> list) {
            for (int index = 0; index < list.size(); index++) {
                flatten(list.get(index), path + "." + index, output);
            }
        } else {
            output.put(path, String.valueOf(value));
        }
    }

    private List<String> placeholders(String value) {
        List<String> result = new ArrayList<>();
        Matcher matcher = PLACEHOLDER.matcher(value == null ? "" : value);
        while (matcher.find()) result.add(matcher.group());
        result.sort(String::compareTo);
        return List.copyOf(result);
    }

    private boolean isWorkstationTitleKey(String key) {
        return key.startsWith("station") || key.startsWith("title.")
                || key.endsWith(".title");
    }
}
