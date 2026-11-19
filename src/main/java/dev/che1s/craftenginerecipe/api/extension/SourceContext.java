package dev.che1s.craftenginerecipe.api.extension;

import dev.che1s.craftenginerecipe.api.DisplayRecipe;
import org.bukkit.plugin.Plugin;

import java.io.IOException;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.logging.Logger;

/**
 * Services available to an extension while it builds a recipe source.
 *
 * <p>This context deliberately exposes no internal core services. Extensions receive the source
 * plugin, display-name callbacks and localization, while indexing and GUI rendering remain under
 * the core's control.</p>
 */
public interface SourceContext {
    /** The exact source plugin instance selected by the core, or null if none is required. */
    Plugin sourcePlugin();

    Path coreDataFolder();

    Path extensionJar();

    Path extensionDataFolder();

    String extensionVersion();

    String minecraftVersion();

    Logger logger();

    SourceText text(String integrationId);

    Function<String, String> resourcePackTranslation();

    Function<String, String> itemDisplayName();

    /** Returns display names for every installed UI language when the core can resolve them. */
    default Map<String, String> localizedItemNames(String id) {
        String name = itemDisplayName().apply(id);
        return name == null || name.isBlank() ? Map.of() : Map.of("en_US", name);
    }

    long translationRevision();

    boolean chinese();

    boolean sourceEnabled(String sourceId);

    /** Reads a boolean option below the selected source's integrations.yml entry. */
    default boolean sourceOption(String sourceId, String option, boolean fallback) {
        return fallback;
    }

    /** Reads a boolean option from the core config.yml without exposing its implementation. */
    default boolean coreOption(String path, boolean fallback) {
        return fallback;
    }

    /** Whether the core's immutable CraftEngine data snapshot is ready. */
    default boolean craftEngineDataReady() {
        return true;
    }

    /** Monotonic revision of the core's CraftEngine data snapshot. */
    default long craftEngineDataGeneration() {
        return 0L;
    }

    /** Loads recipe documents using the core's supported recipe schema. */
    default List<DisplayRecipe> loadRecipes(Path dataFolder) throws IOException {
        throw new UnsupportedOperationException("Recipe document loading is unavailable");
    }

    Function<String, String> coreText();
}
