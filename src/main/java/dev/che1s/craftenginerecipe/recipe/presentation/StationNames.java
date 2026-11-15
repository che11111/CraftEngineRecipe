package dev.che1s.craftenginerecipe.recipe.presentation;

import dev.che1s.craftenginerecipe.i18n.LanguageManager;

/** Resolves workstation display names with the selected player's language. */
public final class StationNames {
    private StationNames() {
    }

    public static String resolve(String id, LanguageManager.Localized language, String fallback) {
        if (id == null || language == null) return fallback;
        String nativeTitle = MinecraftStationText.resolve(id,
                key -> language.integrationText("minecraft", key));
        if (nativeTitle != null) return nativeTitle;
        if (id.equals("minecraft:cultivation")) {
            return text(language, "cultivation", "station", fallback);
        }
        if (id.startsWith("kc:")) {
            String type = id.substring("kc:".length());
            String appliance = text(language, "kaleidoscope", "appliance." + type, null);
            if (appliance != null) {
                return text(language, "kaleidoscope", "station.prefix", "") + appliance;
            }
        }
        if (id.startsWith("customcrops:pot/")) {
            String pot = id.substring("customcrops:pot/".length());
            String potName = text(language, "customcrops", "pot." + pot, pot);
            return format(text(language, "customcrops", "title.station", fallback), "pot", potName);
        }
        if (id.equals("farmersdelight:skillet")) {
            return text(language, "farmersdelight", "title.skillet", fallback);
        }
        if (id.startsWith("farmersdelight:cooking_pot/")) {
            return format(text(language, "farmersdelight", "title.cooking_pot_group", fallback),
                    "group", id.substring("farmersdelight:cooking_pot/".length()));
        }
        if (id.equals("farmersdelight:cooking_pot")) {
            return text(language, "farmersdelight", "title.cooking_pot", fallback);
        }
        if (id.equals("farmersdelight:cutting_board")) {
            return text(language, "farmersdelight", "title.cutting_board", fallback);
        }
        if (id.equals("farmersdelight:special")) {
            return text(language, "farmersdelight", "title.special", fallback);
        }
        if (id.startsWith("farmersdelight:addon/")) {
            String addon = id.substring("farmersdelight:addon/".length());
            String key = addon.contains("brewinandchewin_3akeg") ? "addon.keg"
                    : addon.contains("barbequesdelight_3agrilling") ? "addon.grill"
                    : addon.contains("barbequesdelight_3askewering") ? "addon.skewer"
                    : null;
            if (key != null) {
                return text(language, "farmersdelight", "title.addon_prefix", "")
                        + text(language, "farmersdelight", key, "");
            }
            return text(language, "farmersdelight", "title.unknown_addon", fallback);
        }
        return fallback;
    }

    private static String text(LanguageManager.Localized language, String source,
                               String key, String fallback) {
        String value = language.integrationText(source, key);
        return value == null || value.isBlank() || value.equals(key) ? fallback : value;
    }

    private static String format(String template, String key, String value) {
        return template == null ? null : template.replace("{" + key + "}", value == null ? "" : value);
    }
}
