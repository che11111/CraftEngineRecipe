package dev.leon.cer.util;

import net.momirealms.craftengine.core.item.ItemDefinition;
import net.momirealms.craftengine.core.item.processor.ItemProcessor;
import net.momirealms.craftengine.libraries.adventure.text.Component;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Proxy;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class DisplayNamesTest {
    private ItemDefinition item(String key, ItemProcessor... processors) {
        return (ItemDefinition) Proxy.newProxyInstance(ItemDefinition.class.getClassLoader(),
                new Class<?>[]{ItemDefinition.class}, (proxy, method, args) -> switch (method.getName()) {
                    case "translationKey" -> key;
                    case "processors" -> processors;
                    default -> throw new UnsupportedOperationException(method.getName());
                });
    }

    @Test
    void itemsWithoutNameProcessorsUseTheirResourcePackTranslation() {
        var definition = item("item.example.eggplant");
        assertEquals("茄子", DisplayNames.of(definition, Map.of("item.example.eggplant", "茄子")));
        assertNull(DisplayNames.of(definition));
        assertNull(DisplayNames.of(item(null)));
    }

    @Test
    void literalNamesAreNotReplacedByResourcePackTranslations() {
        assertEquals("My custom item", DisplayNames.plain(Component.text("My custom item"),
                Map.of("item.example.eggplant", "茄子", "My custom item", "Replacement"), 0));
    }

    @Test
    void translatedComponentsResolvePackNamesWithoutLosingSurroundingText() {
        Component component = Component.text("[").append(Component.translatable("item.example.eggplant"))
                .append(Component.text("]"));
        assertEquals("[茄子]", DisplayNames.plain(component, Map.of("item.example.eggplant", "茄子"), 0));
    }
}
