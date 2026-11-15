package dev.che1s.craftenginerecipe.api.extension;

/**
 * Entry point implemented by every CraftEngineRecipe extension JAR.
 *
 * <p>The provider class is named by the {@code CraftEngineRecipe-Provider-Class} manifest field.
 * The provider must have a public no-argument constructor and may be created before its optional
 * source plugin is available. Only {@link #create(SourceContext)} is called after dependency and
 * configuration checks pass. The extension JAR may also contain an {@code extension.yml} file
 * declaring ordered integration-button icon candidates.</p>
 */
public interface RecipeSourceProvider {
    /** Binary and semantic version of this extension contract. */
    int API_VERSION = 1;

    /** Inclusive source API version accepted by this extension. */
    default int requiredApiVersion() {
        return API_VERSION;
    }

    /** Stable provider ID, also used as the plugins/CraftEngineRecipe/recipes subdirectory. */
    String id();

    /** Human-readable provider name used by administrator diagnostics. */
    default String displayName() {
        return id();
    }

    /** Optional exact Bukkit plugin name required by the source. */
    default String requiredPluginName() {
        return "";
    }

    /** Optional exact main class used to reject plugins that merely reuse the expected name. */
    default String requiredPluginMainClass() {
        return "";
    }

    /**
     * Relative recipes/ directory owned by this provider.
     *
     * <p>This is declared at provider level so the core can keep filtering a disabled source
     * even when its runtime source plugin is unavailable. Versioned directories are allowed.</p>
     */
    default String outputDirectory() {
        return id();
    }

    /** Initial integrations.yml mode for a newly discovered provider. */
    default String defaultMode() {
        return "ask";
    }

    /** Whether this provider supplies a core default and should not appear in integration prompts. */
    default boolean promptable() {
        return !defaultMode().equalsIgnoreCase("enabled");
    }

    RecipeSource create(SourceContext context);
}
