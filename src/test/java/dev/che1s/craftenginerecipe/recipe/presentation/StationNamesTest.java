package dev.che1s.craftenginerecipe.recipe.presentation;

import dev.che1s.craftenginerecipe.i18n.LanguageManager;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.io.IOException;
import java.util.Map;
import java.util.logging.Logger;

import static org.junit.jupiter.api.Assertions.assertEquals;

class StationNamesTest {
    @TempDir Path root;

    @Test
    void resolvesKnownWorkstationsInBothSupportedLanguages() {
        LanguageManager language = new LanguageManager(this.root,
                this::resource,
                Logger.getAnonymousLogger());
        language.load("zh_CN");
        var chinese = language.view("zh_CN");
        var english = language.view("en_US");

        assertEquals("原版·耕地", StationNames.resolve("minecraft:cultivation", chinese, ""));
        assertEquals("Vanilla·Cultivation", StationNames.resolve("minecraft:cultivation", english, ""));
        assertEquals("森罗物语·石磨", StationNames.resolve("kc:millstone", chinese, ""));
        assertEquals("Kaleidoscope Cookery·Millstone", StationNames.resolve("kc:millstone", english, ""));
        assertEquals("农夫乐事·煎锅", StationNames.resolve("farmersdelight:skillet", chinese, ""));
        assertEquals("Farmer'sDelight·Skillet", StationNames.resolve("farmersdelight:skillet", english, ""));
        assertEquals("Farmer'sDelight·Grill", StationNames.resolve(
                "farmersdelight:addon/barbequesdelight_3agrilling", english, ""));
    }

    private InputStream resource(String resource) {
        Map<String, String> extensionModules = Map.of(
                "cultivation", "minecraft-cultivation",
                "kaleidoscope", "kaleidoscope",
                "farmersdelight", "farmersdelight");
        String prefix = "lang/integrations/";
        if (resource.startsWith(prefix)) {
            String remainder = resource.substring(prefix.length());
            int separator = remainder.indexOf('/');
            if (separator > 0) {
                String module = extensionModules.get(remainder.substring(0, separator));
                if (module != null) {
                    Path file = Path.of("extensions-build", module, "src/main/resources/lang",
                            remainder.substring(separator + 1));
                    if (java.nio.file.Files.isRegularFile(file)) {
                        try {
                            return java.nio.file.Files.newInputStream(file);
                        } catch (IOException error) {
                            throw new UncheckedIOException(error);
                        }
                    }
                }
            }
        }
        return getClass().getClassLoader().getResourceAsStream(resource);
    }
}
