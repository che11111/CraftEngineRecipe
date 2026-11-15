package dev.che1s.craftenginerecipe.gui;

import dev.che1s.craftenginerecipe.config.IntegrationSettings;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class ManagementBrowserServiceTest {
    @Test
    void managementPageKeepsItsFixedControlsAcrossMenuStyles() {
        String[] structure = ManagementBrowserService.managementStructure();

        assertEquals(6, structure.length);
        assertEquals("<CCCCCCC>", structure[0]);
        assertEquals('<', structure[0].charAt(0));
        assertEquals('>', structure[0].charAt(8));
        for (int row = 1; row <= 4; row++) assertEquals("XXXXXXXXX", structure[row]);
        assertEquals('B', structure[5].charAt(0));
        assertEquals('L', structure[5].charAt(1));
        assertEquals('R', structure[5].charAt(2));
        assertEquals('J', structure[5].charAt(8));
    }

    @Test
    void integrationChoiceCyclesForwardAndBackward() {
        assertEquals(IntegrationSettings.Mode.ASK,
                ManagementBrowserService.cycleIntegrationMode(
                        IntegrationSettings.Mode.ENABLED, false));
        assertEquals(IntegrationSettings.Mode.DISABLED,
                ManagementBrowserService.cycleIntegrationMode(
                        IntegrationSettings.Mode.ENABLED, true));
        assertEquals(IntegrationSettings.Mode.ENABLED,
                ManagementBrowserService.cycleIntegrationMode(
                        IntegrationSettings.Mode.DISABLED, false));
        assertEquals(IntegrationSettings.Mode.ASK,
                ManagementBrowserService.cycleIntegrationMode(
                        IntegrationSettings.Mode.DISABLED, true));
    }
}
