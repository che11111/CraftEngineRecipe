package dev.che1s.craftenginerecipe.i18n;

import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class IntegrationTextTest {
    @Test
    void existingKeysAreDeferredWithArguments() {
        IntegrationText text = new IntegrationText("test_source", key -> switch (key) {
            case "note.amount" -> "Amount: {amount}";
            default -> key;
        });

        String value = text.deferred("note.amount", "数量：{amount}", "amount", 3);

        assertTrue(value.startsWith("<cer-lang:test_source:note.amount:"));
    }

    @Test
    void missingKeysUseTheReadableFallbackImmediately() {
        IntegrationText text = new IntegrationText("test_source", key -> null);

        assertEquals("数量：3",
                text.deferred("note.amount", "数量：{amount}", "amount", 3));
    }
}
