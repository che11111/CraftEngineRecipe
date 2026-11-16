package dev.che1s.craftenginerecipe.config;

import org.bukkit.configuration.file.FileConfiguration;

import java.util.List;
import java.util.Locale;
import java.util.Objects;

/** Applies validated management-page edits to the externally editable config.yml. */
public final class PluginConfigEditor {
    public record GeneralSettings(
            String landingPage,
            boolean personalization,
            boolean rememberLastPage,
            boolean hotkey,
            boolean requireSneak,
            boolean checkForUpdates) {
    }

    public record DataSettings(
            boolean craftEngine,
            boolean minecraft,
            boolean minecraftCultivation,
            boolean craftEngineCultivation,
            boolean autoReload,
            int pollInterval,
            int rotateInterval,
            int searchMaxLength,
            int searchMaxResults) {
    }

    public record BookSettings(boolean enabled, String locale, String name, List<String> lore) {
    }

    private PluginConfigEditor() {
    }

    public static boolean apply(FileConfiguration config, GeneralSettings settings) {
        if (config == null || settings == null) return false;
        String landingPage = normalizeLandingPage(settings.landingPage());
        if (landingPage == null) return false;
        set(config, "browser.default-main-page", landingPage);
        set(config, "personalization.enabled", settings.personalization());
        set(config, "browser.remember-last-page", settings.rememberLastPage());
        set(config, "hotkey.enabled", settings.hotkey());
        set(config, "hotkey.require-sneak", settings.requireSneak());
        set(config, "check-for-updates", settings.checkForUpdates());
        return true;
    }

    public static boolean apply(FileConfiguration config, DataSettings settings) {
        if (config == null || settings == null) return false;
        if (outOfRange(settings.pollInterval(), 10, 200)
                || outOfRange(settings.rotateInterval(), 5, 200)
                || outOfRange(settings.searchMaxLength(), 1, 64)
                || outOfRange(settings.searchMaxResults(), 1, 1000)) {
            return false;
        }
        set(config, "sources.craftengine", settings.craftEngine());
        set(config, "sources.minecraft", settings.minecraft());
        set(config, "sources.minecraft-cultivation", settings.minecraftCultivation());
        set(config, "sources.craftengine-cultivation", settings.craftEngineCultivation());
        set(config, "recipes.auto-reload", settings.autoReload());
        set(config, "recipes.poll-interval", settings.pollInterval());
        set(config, "recipe.rotate-interval", settings.rotateInterval());
        set(config, "search.max-length", settings.searchMaxLength());
        set(config, "search.max-results", settings.searchMaxResults());
        return true;
    }

    public static boolean apply(FileConfiguration config, BookSettings settings) {
        if (config == null || settings == null || settings.locale() == null
                || settings.name() == null
                || settings.name().isBlank() || settings.lore() == null
                || settings.lore().isEmpty()) {
            return false;
        }
        set(config, "recipe-book.enabled", settings.enabled());
        set(config, "recipe-book.name-overrides." + settings.locale(), settings.name().trim());
        set(config, "recipe-book.lore-overrides." + settings.locale(), List.copyOf(settings.lore()));
        return true;
    }

    public static Integer parseWithinRange(String value, int min, int max) {
        try {
            int parsed = Integer.parseInt(value == null ? "" : value.trim());
            return parsed >= min && parsed <= max ? parsed : null;
        } catch (NumberFormatException ignored) {
            return null;
        }
    }

    static String normalizeLandingPage(String value) {
        if (value == null) return null;
        return switch (value.trim().toLowerCase(Locale.ROOT)) {
            case "namespaces" -> "namespaces";
            case "workstations" -> "workstations";
            default -> null;
        };
    }

    private static boolean outOfRange(int value, int min, int max) {
        return value < min || value > max;
    }

    private static void set(FileConfiguration config, String path, Object value) {
        if (!Objects.equals(config.get(path), value)) config.set(path, value);
    }
}
