package dev.che1s.craftenginerecipe.integration;

import dev.che1s.craftenginerecipe.config.IntegrationSettings;
import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

class IntegrationPromptServiceTest {
    @Test
    void onlyRealModeTransitionsScheduleIndexWork() {
        Map<String, IntegrationSettings.Mode> choices = new LinkedHashMap<>();
        choices.put("farmersdelight", IntegrationSettings.Mode.ENABLED);
        choices.put("kaleidoscope", IntegrationSettings.Mode.ENABLED);
        choices.put("customcrops", IntegrationSettings.Mode.ASK);
        choices.put("unknown", IntegrationSettings.Mode.ENABLED);

        Map<String, IntegrationSettings.Mode> current = Map.of(
                "farmersdelight", IntegrationSettings.Mode.ENABLED,
                "kaleidoscope", IntegrationSettings.Mode.ASK,
                "customcrops", IntegrationSettings.Mode.ENABLED);

        IntegrationPromptService.SourceTransition transition =
                IntegrationPromptService.planTransitions(
                        choices, current::get,
                        java.util.Set.of("farmersdelight", "kaleidoscope", "customcrops"));

        assertEquals(List.of("kaleidoscope"), transition.newlyEnabled());
        assertEquals(List.of(), transition.newlyDisabled());
        assertEquals(List.of("customcrops"), transition.newlyAsk());
        assertFalse(transition.accepted().containsKey("unknown"));
    }

    @Test
    void unchangedEnabledSourceDoesNotGetReportedAsNewlyEnabled() {
        IntegrationPromptService.SourceTransition transition =
                IntegrationPromptService.planTransitions(
                        Map.of("farmersdelight", IntegrationSettings.Mode.ENABLED),
                        ignored -> IntegrationSettings.Mode.ENABLED,
                        java.util.Set.of("farmersdelight"));

        assertEquals(List.of(), transition.newlyEnabled());
    }
}
