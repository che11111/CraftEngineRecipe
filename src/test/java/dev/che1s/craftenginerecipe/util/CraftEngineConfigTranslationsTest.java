package dev.che1s.craftenginerecipe.util;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;

class CraftEngineConfigTranslationsTest {
    @TempDir Path root;

    @Test
    void readsLocaleBranchesFromCraftEngineConfigurationLanguageFiles() throws Exception {
        Path lang = this.root.resolve("kaleidoscopecookery/configuration/lang");
        Files.createDirectories(lang);
        Files.writeString(lang.resolve("en_us.yml"), """
                lang#items:
                  en_us:
                    item.kaleidoscopecookery.baozi: "Baozi"
                    item.kaleidoscopecookery.apple_platter: "Apple Platter"
                translations#categories:
                  en_us:
                    category.cook.food: "Food Menu"
                """);
        Files.writeString(lang.resolve("zh_cn.yml"), """
                lang#items:
                  zh_cn:
                    item.kaleidoscopecookery.baozi: "包子"
                """);

        var english = CraftEngineConfigTranslations.read(this.root, "en_US");
        var chinese = CraftEngineConfigTranslations.read(this.root, "zh_CN");

        assertEquals("Baozi", english.get("item.kaleidoscopecookery.baozi"));
        assertEquals("Food Menu", english.get("category.cook.food"));
        assertEquals("包子", chinese.get("item.kaleidoscopecookery.baozi"));
        assertEquals("Baozi", DisplayNames.expandTranslations(
                "<lang:item.kaleidoscopecookery.baozi>", english));
        assertEquals("包子", DisplayNames.expandTranslations(
                "<lang:item.kaleidoscopecookery.baozi>", chinese));
    }

    @Test
    void readsTheCraftEngineTranslationKeyInAllNinePlayerLocales() throws Exception {
        Path lang = this.root.resolve("example/configuration/lang");
        Files.createDirectories(lang);
        Map<String, String> names = Map.of(
                "en_us", "Custom Food",
                "zh_cn", "自定义食物",
                "zh_tw", "自訂食物",
                "ja_jp", "カスタムフード",
                "ko_kr", "사용자 지정 음식",
                "de_de", "Benutzerdefiniertes Essen",
                "fr_fr", "Nourriture personnalisée",
                "es_es", "Comida personalizada",
                "ru_ru", "Пользовательская еда");
        names.forEach((locale, name) -> {
            try {
                Files.writeString(lang.resolve(locale + ".yml"), """
                        lang#items:
                          %s:
                            item.example.custom_food: "%s"
                        """.formatted(locale, name));
            } catch (Exception error) {
                throw new RuntimeException(error);
            }
        });

        for (String locale : dev.che1s.craftenginerecipe.i18n.LanguageManager.supportedLocales()) {
            String lower = locale.toLowerCase();
            assertEquals(names.get(lower),
                    CraftEngineConfigTranslations.read(this.root, locale)
                            .get("item.example.custom_food"), locale);
        }
    }
}
