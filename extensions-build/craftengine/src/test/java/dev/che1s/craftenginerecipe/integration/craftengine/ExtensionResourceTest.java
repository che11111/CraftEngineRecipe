package dev.che1s.craftenginerecipe.integration.craftengine;

import dev.che1s.craftenginerecipe.util.PackagedNameOverrides;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;

class ExtensionResourceTest {
    @Test
    void bundlesOnlyMissingDefaultItemLanguages() throws Exception {
        Map<String, Map<String, String>> names = PackagedNameOverrides.read(
                getClass().getClassLoader(), "item-name-overrides/default.json");

        assertEquals(4, names.size());
        assertEquals("アメジストのたいまつ",
                names.get("ja_JP").get("item.default.amethyst_torch"));
        assertEquals("Amethystfackel",
                names.get("de_DE").get("item.default.amethyst_torch"));
        assertEquals("Antorcha de amatista",
                names.get("es_ES").get("item.default.amethyst_torch"));
        assertEquals("Аметистовый факел",
                names.get("ru_RU").get("item.default.amethyst_torch"));
        names.values().forEach(values -> assertEquals(62, values.size()));
    }
}
