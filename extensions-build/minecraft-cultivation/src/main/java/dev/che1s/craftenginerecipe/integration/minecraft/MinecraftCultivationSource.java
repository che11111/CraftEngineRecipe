package dev.che1s.craftenginerecipe.integration.minecraft;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import dev.che1s.craftenginerecipe.api.DisplayRecipe;
import dev.che1s.craftenginerecipe.api.extension.RecipeSource;
import dev.che1s.craftenginerecipe.api.extension.SourceContext;
import dev.che1s.craftenginerecipe.api.extension.CultivationRecipeConverter;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

/** Default vanilla cultivation bundled for the exact running Minecraft version. */
public final class MinecraftCultivationSource implements RecipeSource {
    public static final String SOURCE_ID = "minecraft_cultivation";
    private final SourceContext context;
    private Path installed;

    public MinecraftCultivationSource(SourceContext context) {
        this.context = context;
        try {
            this.installed = MinecraftDataInstaller.install(context, "cultivation");
        } catch (IOException error) {
            this.context.logger().warning("Cannot install Minecraft cultivation defaults: " + error.getMessage());
        }
    }

    @Override
    public String id() {
        return SOURCE_ID;
    }

    @Override
    public String outputDirectory() {
        return "minecraft-cultivation";
    }

    @Override
    public boolean ready() {
        return this.installed != null
                && Files.isRegularFile(this.installed.resolve("vanilla.json"));
    }

    @Override
    public long revision() {
        return this.ready() ? this.installed.toFile().lastModified() : 0L;
    }

    @Override
    public List<DisplayRecipe> capture() {
        if (!this.ready()) return List.of();
        try {
            JsonObject root = JsonParser.parseString(Files.readString(
                    this.installed.resolve("vanilla.json"), StandardCharsets.UTF_8)).getAsJsonObject();
            return CultivationRecipeConverter.convert(root, "minecraft", this.context);
        } catch (IOException | RuntimeException error) {
            throw new IllegalStateException("Cannot read Minecraft cultivation defaults", error);
        }
    }
}
