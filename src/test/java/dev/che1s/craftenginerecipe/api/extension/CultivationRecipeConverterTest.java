package dev.che1s.craftenginerecipe.api.extension;

import com.google.gson.JsonParser;
import dev.che1s.craftenginerecipe.api.DisplayRecipe;
import org.bukkit.plugin.Plugin;
import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.util.List;
import java.util.function.Function;
import java.util.logging.Logger;

import static org.junit.jupiter.api.Assertions.assertTrue;

class CultivationRecipeConverterTest {
    @Test
    void cultivationTextIsResolvedWhenTheRecipeIsRenderedForAPlayer() {
        SourceText text = new SourceText() {
            @Override
            public String text(String key, String fallback, Object... arguments) {
                return has(key) ? "translated-" + key : fallback;
            }

            @Override
            public boolean has(String key) {
                return key.equals("seed.lore") || key.equals("note.wheat");
            }
        };
        SourceContext context = new SourceContext() {
            @Override public Plugin sourcePlugin() { return null; }
            @Override public Path coreDataFolder() { return Path.of("."); }
            @Override public Path extensionJar() { return Path.of("extension.jar"); }
            @Override public Path extensionDataFolder() { return Path.of("."); }
            @Override public String extensionVersion() { return "1"; }
            @Override public String minecraftVersion() { return "26.3"; }
            @Override public Logger logger() { return Logger.getAnonymousLogger(); }
            @Override public SourceText text(String integrationId) { return text; }
            @Override public Function<String, String> resourcePackTranslation() { return key -> null; }
            @Override public Function<String, String> itemDisplayName() { return key -> null; }
            @Override public long translationRevision() { return 0; }
            @Override public boolean chinese() { return false; }
            @Override public boolean sourceEnabled(String sourceId) { return true; }
            @Override public Function<String, String> coreText() { return key -> "Cultivation"; }
        };

        DisplayRecipe recipe = CultivationRecipeConverter.convert(JsonParser.parseString("""
                {"crops":[{
                  "id":"minecraft:wheat",
                  "seeds":["minecraft:wheat_seeds"],
                  "products":["minecraft:wheat"],
                  "substrates":["minecraft:farmland"],
                  "note":"旧的中文说明"
                }]}
                """).getAsJsonObject(), "minecraft", context).getFirst();

        assertTrue(recipe.ingredients().getFirst().choices().getFirst().lore()
                .contains("<cer-lang:cultivation:seed.lore>"));
        assertTrue(recipe.results().getFirst().item().lore().stream()
                .anyMatch(line -> line.contains("<cer-lang:cultivation:note.wheat>")));
    }
}
