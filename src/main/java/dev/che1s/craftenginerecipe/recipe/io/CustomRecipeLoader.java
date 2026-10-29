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
import java.util.Collection;
import java.util.HashMap;
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

    /** 使用启动/重载时刻的配置快照，避免异步文件扫描继续读取 Bukkit 配置。 */
    public CustomRecipeLoader(Path dataFolder, boolean minecraftEnabled, boolean craftEngineEnabled,
                              Set<String> enabledOptionalSources) {
        this(dataFolder, () -> minecraftEnabled, () -> craftEngineEnabled,
                source -> enabledOptionalSources.contains(source));
    }

    /**
     * 只读取并解析 YAML，不创建 Bukkit 物品对象。该阶段可安全地放到异步线程。
     *
     * @param changedFiles 相对于 recipes/ 的已变更文件；空集合会复用所有可用缓存
     * @param previous 上一次成功发布的解析结果；初次加载时传 null
     */
    public PreparedDocuments readDocuments(Collection<String> changedFiles, PreparedDocuments previous)
            throws IOException {
        List<Path> files = this.recipeFiles();
        Map<String, ReadDocument> cached = new HashMap<>();
        if (previous != null) {
            for (ReadDocument document : previous.documents()) {
                cached.put(this.relativeKey(document.file()), document);
            }
        }
        Set<String> changed = Set.copyOf(changedFiles);
        List<ReadDocument> documents = new ArrayList<>(files.size());
        for (Path file : files) {
            String key = this.relativeKey(file);
            ReadDocument reusable = cached.get(key);
            if (previous != null && reusable != null && !changed.contains(key)) {
                documents.add(reusable);
            } else {
                RecipeDocumentParser source = new RecipeDocumentParser(file);
                documents.add(new ReadDocument(file, source.read()));
            }
        }
        return new PreparedDocuments(documents);
    }

    /** 读取全部文件，适合启动和手动全量重载。 */
    public PreparedDocuments readDocuments() throws IOException {
        return this.readDocuments(List.of(), null);
    }

    public List<DisplayRecipe> load() throws IOException {
        return this.build(this.readDocuments());
    }

    /**
     * 处理已解析文档并创建展示配方。该阶段会访问 Bukkit 注册表和 ItemStack，
     * 因此服务器中必须留在 global region 线程执行。
     */
    public List<DisplayRecipe> build(PreparedDocuments prepared) throws IOException {
        List<DisplayRecipe> recipes = new ArrayList<>();
        Map<String, String> ids = new HashMap<>();
        for (ReadDocument document : prepared.documents()) {
            Path file = document.file();
            Map<String, Object> root = document.root();
            RecipeDocumentParser source = new RecipeDocumentParser(file);
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

    private List<Path> recipeFiles() throws IOException {
        if (!Files.exists(this.directory)) return List.of();
        if (!Files.isDirectory(this.directory)) throw new IOException(this.directory + ": expected a directory");
        try (var paths = Files.walk(this.directory)) {
            return paths.filter(path -> Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS))
                    .filter(this::isRecipeFile).sorted().toList();
        }
    }

    private String relativeKey(Path file) {
        return this.directory.relativize(file).toString().replace('\\', '/');
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

    public record ReadDocument(Path file, Map<String, Object> root) {
        public ReadDocument {
            if (file == null || root == null) throw new IllegalArgumentException("parsed document is incomplete");
        }
    }

    /** 不包含 Bukkit 对象、可跨线程传递的 YAML 解析快照。 */
    public record PreparedDocuments(List<ReadDocument> documents) {
        public static final PreparedDocuments EMPTY = new PreparedDocuments(List.of());

        public PreparedDocuments {
            documents = List.copyOf(documents);
        }
    }

}
