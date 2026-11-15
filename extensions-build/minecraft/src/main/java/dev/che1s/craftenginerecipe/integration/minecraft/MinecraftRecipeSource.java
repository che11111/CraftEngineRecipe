package dev.che1s.craftenginerecipe.integration.minecraft;

import dev.che1s.craftenginerecipe.api.DisplayRecipe;
import dev.che1s.craftenginerecipe.api.extension.RecipeSource;
import dev.che1s.craftenginerecipe.api.extension.SourceContext;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

/** Default recipes bundled for the exact running Minecraft version. */
public final class MinecraftRecipeSource implements RecipeSource {
    public static final String SOURCE_ID = "minecraft";
    private final SourceContext context;
    private Path installed;

    public MinecraftRecipeSource(SourceContext context) {
        this.context = context;
        try {
            this.installed = MinecraftDataInstaller.install(context, "recipes");
        } catch (IOException error) {
            this.context.logger().warning("Cannot install Minecraft recipe defaults: " + error.getMessage());
        }
    }

    @Override
    public String id() {
        return SOURCE_ID;
    }

    @Override
    public String outputDirectory() {
        return "minecraft-" + this.context.minecraftVersion();
    }

    @Override
    public boolean ready() {
        return this.installed != null && Files.isDirectory(this.installed);
    }

    @Override
    public long revision() {
        return this.ready() ? this.installed.toFile().lastModified() : 0L;
    }

    @Override
    public List<DisplayRecipe> capture() {
        if (!this.ready()) return List.of();
        try {
            return this.context.loadRecipes(this.context.extensionDataFolder());
        } catch (IOException | RuntimeException error) {
            throw new IllegalStateException("Cannot read Minecraft recipe defaults", error);
        }
    }
}
