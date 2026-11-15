package dev.che1s.craftenginerecipe.util;

import net.momirealms.craftengine.core.item.ItemDefinition;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Proxy;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;

class DisplayNamesTest {
    @Test
    void resolvesLangTagsAndBareCategoryTranslationKeys() {
        Map<String, String> translations = Map.of(
                "farmersdelight.category.root", "农夫乐事",
                "itemGroup.corn_delight", "玉米乐事");

        assertEquals("<!i><white>农夫乐事</white>", DisplayNames.expandTranslations(
                "<!i><white><lang:farmersdelight.category.root></white>", translations));
        assertEquals("玉米乐事", DisplayNames.resolveText("itemGroup.corn_delight", translations));
    }

    @Test
    void craftEngineItemTranslationKeyWinsOverProcessorNames() {
        ItemDefinition definition = (ItemDefinition) Proxy.newProxyInstance(
                ItemDefinition.class.getClassLoader(), new Class<?>[]{ItemDefinition.class},
                (proxy, method, arguments) -> switch (method.getName()) {
                    case "translationKey" -> "item.example.custom_food";
                    case "toString" -> "example:custom_food";
                    case "hashCode" -> System.identityHashCode(proxy);
                    case "equals" -> proxy == arguments[0];
                    default -> null;
                });

        assertEquals("Custom Food",
                DisplayNames.of(definition, Map.of("item.example.custom_food", "Custom Food")));
    }

    @Test
    void missingItemTranslationsFallBackToEnglishThenReadableNames() {
        assertEquals("Grilled Beef Skewer", DisplayNames.expandTranslations(
                "item.barbequesdelight.grilled_beef_skewer",
                Map.of("item.barbequesdelight.grilled_beef_skewer", "Grilled Beef Skewer")));
        assertEquals("Raw Beef Skewer", DisplayNames.expandTranslations(
                "item.barbequesdelight.raw_beef_skewer", Map.of()));
        assertEquals("Grilled Beef Skewer",
                DisplayNames.humanizeItemId("barbequesdelight:grilled_beef_skewer"));
    }
}
