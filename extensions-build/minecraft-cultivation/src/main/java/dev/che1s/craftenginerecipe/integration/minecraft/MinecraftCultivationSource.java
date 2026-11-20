package dev.che1s.craftenginerecipe.integration.minecraft;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import dev.che1s.craftenginerecipe.api.DisplayRecipe;
import dev.che1s.craftenginerecipe.api.extension.RecipeSource;
import dev.che1s.craftenginerecipe.api.extension.SourceContext;
import dev.che1s.craftenginerecipe.api.extension.CultivationRecipeConverter;

import java.io.IOException;
import java.io.InputStreamReader;
import java.util.List;

/** Default vanilla cultivation bundled for the exact running Minecraft version. */
public final class MinecraftCultivationSource implements RecipeSource {
    public static final String SOURCE_ID = "minecraft_cultivation";
    private static final String RESOURCE = "cultivation/vanilla.json";
    private final SourceContext context;

    public MinecraftCultivationSource(SourceContext context) {
        this.context = context;
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
        return getClass().getClassLoader().getResource(RESOURCE) != null;
    }

    @Override
    public long revision() {
        return this.ready() ? 1L : 0L;
    }

    @Override
    public List<DisplayRecipe> capture() {
        if (!this.ready()) return List.of();
        try (var input = getClass().getClassLoader().getResourceAsStream(RESOURCE)) {
            if (input == null) return List.of();
            JsonObject root = JsonParser.parseReader(
                    new InputStreamReader(input, java.nio.charset.StandardCharsets.UTF_8)).getAsJsonObject();
            return CultivationRecipeConverter.convert(root, "minecraft", this.context);
        } catch (IOException | RuntimeException error) {
            throw new IllegalStateException("Cannot read Minecraft cultivation defaults", error);
        }
    }
}
