package dev.che1s.craftenginerecipe.api.extension;

import dev.che1s.craftenginerecipe.api.DisplayRecipe;

import java.util.List;

/**
 * A complete, read-only recipe snapshot exposed by an optional source extension.
 *
 * <p>Implementations are created by a {@link RecipeSourceProvider}. The capture method is
 * invoked by the core after the source reports that it is ready and on the scheduler required
 * by the source plugin.</p>
 */
public interface RecipeSource {
    String id();

    /**
     * Relative directory under plugins/CraftEngineRecipe/recipes that persists this source.
     * Providers may use a stable source-specific directory or a versioned directory.
     */
    default String outputDirectory() {
        return id();
    }

    boolean ready();

    long revision();

    List<DisplayRecipe> capture();
}
