package dev.che1s.craftenginerecipe.recipe.io;

import dev.che1s.craftenginerecipe.api.DisplayRecipe;
import dev.che1s.craftenginerecipe.api.RecipeIngredient;
import dev.che1s.craftenginerecipe.api.RecipeItem;
import dev.che1s.craftenginerecipe.api.RecipeLayout;
import dev.che1s.craftenginerecipe.api.RecipeResult;
import dev.che1s.craftenginerecipe.api.RecipeWorkstation;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.plugin.java.JavaPlugin;
import org.yaml.snakeyaml.LoaderOptions;
import org.yaml.snakeyaml.Yaml;
import org.yaml.snakeyaml.constructor.SafeConstructor;

import java.io.IOException;
import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.function.BooleanSupplier;
import java.util.function.Predicate;

/** 读取仅用于展示的配方。加载失败时不会返回部分有效的替代数据。 */
public final class CustomRecipeLoader {
    private static final List<Integer> KALEIDOSCOPE_INGREDIENT_SLOTS =
            List.of(5, 6, 7, 8, 14, 15, 16, 17, 23, 24, 25, 26);
    private static final List<Integer> KALEIDOSCOPE_RESULT_SLOTS =
            List.of(41, 42, 43, 44, 50, 51, 52, 53);
    private static final Set<String> LAYOUT_KEYS = Set.of("template", "type", "rows", "title", "workstation-slot",
            "information-slot", "previous-slot", "back-slot", "next-slot");
    private static final Set<String> ITEM_KEYS = Set.of("provider", "id", "name", "lore");
    private static final Set<String> INGREDIENT_KEYS = Set.of("provider", "id", "name", "lore", "item", "choices", "amount", "slot", "role");
    private static final Set<String> RESULT_KEYS = Set.of("provider", "id", "name", "lore", "item", "amount", "slot", "note");
    private static final Set<String> OPTIONAL_SOURCE_DIRECTORIES =
            Set.of("farmersdelight", "kaleidoscope", "customcrops");
    private final Path directory;
    private final BooleanSupplier minecraftEnabled;
    private final BooleanSupplier craftEngineEnabled;
    private final Predicate<String> integrationEnabled;

    public CustomRecipeLoader(JavaPlugin plugin) {
        this(plugin.getDataFolder().toPath(),
                () -> plugin.getConfig().getBoolean("sources.minecraft", true),
                () -> plugin.getConfig().getBoolean("sources.craftengine", true),
                source -> plugin instanceof dev.che1s.craftenginerecipe.CraftEngineRecipePlugin cer
                        && cer.integrations().enabled(source));
    }

    CustomRecipeLoader(Path dataFolder) {
        this(dataFolder, () -> true, () -> true, ignored -> true);
    }

    CustomRecipeLoader(Path dataFolder, BooleanSupplier minecraftEnabled, BooleanSupplier craftEngineEnabled) {
        this(dataFolder, minecraftEnabled, craftEngineEnabled, ignored -> true);
    }

    CustomRecipeLoader(Path dataFolder, BooleanSupplier minecraftEnabled, BooleanSupplier craftEngineEnabled,
                       Predicate<String> integrationEnabled) {
        this.directory = dataFolder.resolve("recipes");
        this.minecraftEnabled = minecraftEnabled;
        this.craftEngineEnabled = craftEngineEnabled;
        this.integrationEnabled = integrationEnabled;
    }

    public List<DisplayRecipe> load() throws IOException {
        if (!Files.exists(this.directory)) return List.of();
        if (!Files.isDirectory(this.directory)) throw new IOException(this.directory + ": expected a directory");
        List<Path> files;
        try (var paths = Files.walk(this.directory)) {
            files = paths.filter(path -> Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS))
                    .filter(this::isRecipeFile).sorted().toList();
        }
        List<DisplayRecipe> recipes = new ArrayList<>();
        Map<String, String> ids = new HashMap<>();
        for (Path file : files) {
            RecipeDocumentParser source = new RecipeDocumentParser(file);
            Map<String, Object> root = source.read();
            if (source.isMenuDocument(root)) {
                for (DisplayRecipe recipe : source.menuRecipes(root)) {
                    String previous = ids.putIfAbsent(recipe.id(), file.toString());
                    if (previous != null) throw source.error("recipes." + recipe.id(),
                            "duplicate recipe id; also declared in " + previous);
                    recipes.add(recipe);
                }
                continue;
            }
            source.keys(root, "", Set.of("enabled", "recipes", "layouts"));
            if (!source.bool(root, "enabled", true, "enabled")) continue;
            Map<String, Object> templates = source.map(root.getOrDefault("layouts", Map.of()), "layouts");
            for (var entry : templates.entrySet()) {
                Map<String, Object> template = source.map(entry.getValue(), "layouts." + entry.getKey());
                source.keys(template, "layouts." + entry.getKey(), LAYOUT_KEYS);
                // 模板只对当前文件有效，且模板之间不能相互继承。
                if (template.containsKey("template")) throw source.error("layouts." + entry.getKey() + ".template", "templates cannot inherit templates");
                source.layout(template, null, "layouts." + entry.getKey());
            }
            Map<String, Object> entries = source.map(root.getOrDefault("recipes", Map.of()), "recipes");
            for (var entry : entries.entrySet()) {
                String path = "recipes." + entry.getKey();
                Map<String, Object> definition = source.map(entry.getValue(), path);
                if (!source.bool(definition, "enabled", true, path + ".enabled")) continue;
                DisplayRecipe recipe = source.recipe(entry.getKey(), definition, templates, path);
                String previous = ids.putIfAbsent(recipe.id(), file.toString());
                if (previous != null) throw source.error(path, "duplicate recipe id; also declared in " + previous);
                recipes.add(recipe);
            }
        }
        return List.copyOf(recipes);
    }

    private boolean isRecipeFile(Path path) {
        String name = path.getFileName().toString().toLowerCase(Locale.ROOT);
        if (!name.endsWith(".yml") && !name.endsWith(".yaml")) return false;
        Path relative = this.directory.relativize(path);
        if (!this.minecraftEnabled.getAsBoolean() && relative.getNameCount() > 1
                && relative.getName(0).toString().startsWith("minecraft-")) return false;
        if (!this.craftEngineEnabled.getAsBoolean() && relative.getNameCount() > 1
                && relative.getName(0).toString().equals("craftengine")) return false;
        if (relative.getNameCount() > 1) {
            String source = relative.getName(0).toString().toLowerCase(Locale.ROOT);
            if (OPTIONAL_SOURCE_DIRECTORIES.contains(source) && !this.integrationEnabled.test(source)) return false;
        }
        for (Path part : relative) {
            String segment = part.toString().toLowerCase(Locale.ROOT);
            if (segment.equals("example") || segment.equals("examples") || segment.startsWith("example.")
                    || segment.startsWith("example-") || segment.endsWith("-example.yml") || segment.endsWith("-example.yaml")) return false;
        }
        return true;
    }

}
