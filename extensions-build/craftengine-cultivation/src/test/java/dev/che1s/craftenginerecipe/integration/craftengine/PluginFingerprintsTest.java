package dev.che1s.craftenginerecipe.integration.craftengine;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PluginFingerprintsTest {
    @Test
    void everySupportedSourceRequiresBothExactNameAndMainClass() {
        assertEquals("farmersdelight", identify("FarmersDelight",
                "com.huidu.farmersdelight.FarmersDelightPlugin"));
        assertEquals("kaleidoscope", identify("KaleidoscopeCookeryPlugin",
                "net.kaleidoscope.cookery.plugin.KaleidoscopeCookeryPlugin"));
        assertEquals("customcrops", identify("CustomCrops",
                "net.momirealms.customcrops.bukkit.BukkitBootstrap"));
        assertEquals("craftengine", identify("CraftEngine",
                "net.momirealms.craftengine.bukkit.plugin.BukkitCraftEnginePlugin"));

        assertTrue(PluginFingerprints.identify("CustomCrops", "example.fake.CustomCropsPlugin").isEmpty());
        assertTrue(PluginFingerprints.identify("OtherPlugin",
                "net.momirealms.customcrops.bukkit.BukkitBootstrap").isEmpty());
    }

    @Test
    void knownNameCanBeReportedAsAnIdentityMismatch() {
        PluginFingerprints.Fingerprint expected = PluginFingerprints.byPluginName("CustomCrops").orElseThrow();

        assertEquals("net.momirealms.customcrops.bukkit.BukkitBootstrap", expected.mainClass());
        assertFalse(PluginFingerprints.identify("CustomCrops", "example.fake.CustomCropsPlugin").isPresent());
    }

    private static String identify(String name, String mainClass) {
        return PluginFingerprints.identify(name, mainClass).map(
                PluginFingerprints.Fingerprint::sourceId).orElse(null);
    }
}
