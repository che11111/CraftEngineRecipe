package dev.che1s.craftenginerecipe.recipe.presentation;

import java.util.function.Function;

/** Maps stable native station IDs to the externally editable mining translation keys. */
public final class MinecraftStationText {
    private MinecraftStationText() {
    }

    public static String resolve(String stationId, Function<String, String> translation) {
        String key = key(stationId);
        if (key == null || translation == null) return null;
        String value = translation.apply(key);
        return value == null || value.isBlank() || value.equals(key) ? null : value;
    }

    public static String key(String stationId) {
        if (stationId == null || !stationId.startsWith("minecraft:")) return null;
        String name = stationId.substring("minecraft:".length());
        return switch (name) {
            case "crafting", "smelting", "blasting", "smoking", "campfire_cooking",
                    "stonecutting", "smithing", "brewing" -> "station." + name;
            default -> null;
        };
    }
}
