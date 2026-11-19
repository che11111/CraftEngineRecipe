package dev.che1s.craftenginerecipe.util;

import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class VanillaNameResourcesTest {
    @Test
    void packagedNamesContainAllSupportedLanguagesForTheExactVersion() throws Exception {
        Map<String, Map<String, String>> names = VanillaNameResources.read(
                getClass().getClassLoader(), "26.3");

        assertEquals(9, names.size());
        assertEquals("Wheat", names.get("en_US").get("item.minecraft.wheat"));
        assertEquals("小麦", names.get("zh_CN").get("item.minecraft.wheat"));
        assertEquals("リンゴ", names.get("ja_JP").get("item.minecraft.apple"));
        assertEquals("耕地", names.get("zh_CN").get("block.minecraft.farmland"));
        assertTrue(names.get("ru_RU").containsKey("item.minecraft.wheat"));
    }

}
