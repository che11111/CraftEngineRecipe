package dev.che1s.craftenginerecipe.config;

import org.bukkit.configuration.file.FileConfiguration;

import java.util.List;

/** 菜单的全部音效设置。 */
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
                menuSound(config, "sounds.open-menu", "BLOCK_IRON_DOOR_OPEN-1-2"),
                menuSound(config, "sounds.close-menu", "BLOCK_IRON_DOOR_CLOSE-1-2"));
    }

    private static List<MenuSound> menuSound(FileConfiguration config, String key, String fallback) {
        // 显式配置值（包括空字符串）会覆盖默认值。
        if (config.contains(key, true)) return single(config.get(key));
        return single(config.get(key, fallback));
    }

    private static List<MenuSound> single(Object value) {
        return value instanceof String sound && !sound.isBlank()
                ? List.of(new MenuSound(sound, 0.7f)) : List.of();
    }

    public record MenuSound(String spec, float defaultVolume) {}
}
