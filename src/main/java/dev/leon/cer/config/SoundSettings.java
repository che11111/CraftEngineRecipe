package dev.leon.cer.config;

import org.bukkit.configuration.file.FileConfiguration;

import java.util.List;

/** All menu sound settings; old Open/Close lists are read only for compatibility. */
public record SoundSettings(String changeCategory, String changePage, String click, String back,
                            String search, String querySuccess, String queryFailure,
                            List<MenuSound> openMenu, List<MenuSound> closeMenu) {
    private static final String BOOK = "minecraft:item.book.page_turn";

    public static SoundSettings load(FileConfiguration config) {
        return new SoundSettings(
                config.getString("sounds.change-category", "UI_BUTTON_CLICK-1-2"),
                config.getString("sounds.change-page", BOOK),
                config.getString("sounds.click", "minecraft:ui.button.click"),
                config.getString("sounds.back", BOOK),
                config.getString("sounds.search", "minecraft:ui.toast.in"),
                config.getString("sounds.query-success", "UI_BUTTON_CLICK-1-2"),
                config.getString("sounds.query-failure", "BLOCK_NOTE_BLOCK_BASS-1-1"),
                menuSound(config, "sounds.open-menu", "Open", "sounds.open", "BLOCK_IRON_DOOR_OPEN-1-2"),
                menuSound(config, "sounds.close-menu", "Close", null, "BLOCK_IRON_DOOR_CLOSE-1-2"));
    }

    private static List<MenuSound> menuSound(FileConfiguration config, String key, String legacyActions,
                                              String legacyKey, String fallback) {
        // Explicit new values (including empty strings) must override old config and bundled defaults.
        if (config.contains(key, true)) return single(config.get(key));
        if (config.contains(legacyActions, true)) {
            return config.getStringList(legacyActions).stream().map(String::trim)
                    .filter(action -> action.regionMatches(true, 0, "sound:", 0, 6))
                    .map(action -> action.substring(6).trim()).filter(sound -> !sound.isBlank())
                    .map(sound -> new MenuSound(sound, 1.0f)).toList();
        }
        if (legacyKey != null && config.contains(legacyKey, true)) {
            return single(config.get(legacyKey));
        }
        return single(config.get(key, fallback));
    }

    private static List<MenuSound> single(Object value) {
        return value instanceof String sound && !sound.isBlank()
                ? List.of(new MenuSound(sound, 0.7f)) : List.of();
    }

    public record MenuSound(String spec, float defaultVolume) {}
}
