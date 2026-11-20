package dev.che1s.craftenginerecipe.listener;

import org.bukkit.configuration.file.YamlConfiguration;
import org.junit.jupiter.api.Test;

import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class HotkeyListenerTest {
    @Test
    void handlesOnlyShiftFAndOnlyWhenEnabled() {
        assertFalse(HotkeyListener.shouldHandle(false, true, false));
        assertFalse(HotkeyListener.shouldHandle(false, true, true));
        assertFalse(HotkeyListener.shouldHandle(true, false, true));
        assertFalse(HotkeyListener.shouldHandle(true, true, false));
        assertTrue(HotkeyListener.shouldHandle(true, true, true));
    }

    @Test
    void bundledConfigDisablesShiftFAndHasNoPlainFMode() throws Exception {
        YamlConfiguration config = new YamlConfiguration();
        try (var input = getClass().getClassLoader().getResourceAsStream("config.yml")) {
            assertTrue(input != null);
            config.load(new InputStreamReader(input, StandardCharsets.UTF_8));
        }

        assertFalse(config.getBoolean("hotkey.enabled", true));
        assertFalse(config.isSet("hotkey.require-sneak"));
    }
}
