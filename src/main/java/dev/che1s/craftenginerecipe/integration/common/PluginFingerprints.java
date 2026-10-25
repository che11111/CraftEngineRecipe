package dev.che1s.craftenginerecipe.integration.common;

import org.bukkit.plugin.Plugin;

import java.util.List;
import java.util.Optional;

/** 每个受支持配方来源的精确 Bukkit 名称与主类标识。 */
public final class PluginFingerprints {
    public static final Fingerprint CRAFT_ENGINE = new Fingerprint(
            "craftengine", "CraftEngine",
            "net.momirealms.craftengine.bukkit.plugin.BukkitCraftEnginePlugin", "CraftEngine");
    public static final Fingerprint FARMERS_DELIGHT = new Fingerprint(
            "farmersdelight", "FarmersDelight",
            "com.huidu.farmersdelight.FarmersDelightPlugin", "FarmersDelight");
    public static final Fingerprint KALEIDOSCOPE = new Fingerprint(
            "kaleidoscope", "KaleidoscopeCookeryPlugin",
            "net.kaleidoscope.cookery.plugin.KaleidoscopeCookeryPlugin", "Kaleidoscope Cookery");
    public static final Fingerprint CUSTOM_CROPS = new Fingerprint(
            "customcrops", "CustomCrops",
            "net.momirealms.customcrops.bukkit.BukkitBootstrap", "CustomCrops");

    private static final List<Fingerprint> ALL =
            List.of(CRAFT_ENGINE, FARMERS_DELIGHT, KALEIDOSCOPE, CUSTOM_CROPS);

    private PluginFingerprints() {
    }

    public static List<Fingerprint> all() {
        return ALL;
    }

    public static Optional<Fingerprint> identify(Plugin plugin) {
        if (plugin == null || plugin.getDescription() == null) return Optional.empty();
        return identify(plugin.getName(), plugin.getDescription().getMain());
    }

    public static Optional<Fingerprint> identify(String pluginName, String mainClass) {
        if (pluginName == null || mainClass == null) return Optional.empty();
        return ALL.stream()
                .filter(fingerprint -> fingerprint.pluginName().equals(pluginName)
                        && fingerprint.mainClass().equals(mainClass))
                .findFirst();
    }

    public static Optional<Fingerprint> byPluginName(String pluginName) {
        if (pluginName == null) return Optional.empty();
        return ALL.stream().filter(fingerprint -> fingerprint.pluginName().equals(pluginName)).findFirst();
    }

    public static boolean matches(Plugin plugin, String sourceId) {
        return identify(plugin).map(fingerprint -> fingerprint.sourceId().equals(sourceId)).orElse(false);
    }

    public record Fingerprint(String sourceId, String pluginName, String mainClass, String displayName) {
    }
}
