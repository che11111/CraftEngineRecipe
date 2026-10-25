package dev.che1s.craftenginerecipe.integration.common;

import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import dev.che1s.craftenginerecipe.CraftEngineRecipePlugin;
import dev.che1s.craftenginerecipe.integration.customcrops.CustomCropsRecipeSource;
import org.bukkit.Bukkit;
import org.bukkit.plugin.Plugin;
import org.yaml.snakeyaml.LoaderOptions;
import org.yaml.snakeyaml.Yaml;
import org.yaml.snakeyaml.constructor.SafeConstructor;

import java.io.IOException;
import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * 将每个 CraftEngine 命名空间的栽培元数据导出为单文件，
 * 并额外导出 CustomCrops 可选的原版方块种植盆定义。
 */
public final class CultivationDataExporter {
    public static final String FILE_NAME = "vanilla.json";
    private static final Set<String> CULTIVATION_TYPES = Set.of(
            "crop_block", "stem_block", "vertical_crop_block", "vine_crop_head_block",
            "vine_crop_body_block");
    private static final Set<String> KNOWN_NAMESPACES =
            Set.of("default", "minecraft", "farmersdelight", "kaleidoscopecookery", "customcrops");

    private CultivationDataExporter() {}

    public static void exportMissing(CraftEngineRecipePlugin plugin) {
        Path root = craftEngineResources(plugin);
        if (!Files.isDirectory(root)) return;
        Map<String, List<Path>> packs;
        try {
            packs = packs(root);
        } catch (IOException | RuntimeException error) {
            plugin.getLogger().warning("读取 CraftEngine 资源包失败: " + error.getMessage());
            return;
        }
        for (var entry : packs.entrySet()) {
            if (!enabled(plugin, entry.getKey())) continue;
            Path target = target(plugin, entry.getKey());
            if (Files.exists(target, LinkOption.NOFOLLOW_LINKS)) continue;
            try {
                List<JsonObject> crops = namespaceCrops(entry.getValue(), entry.getKey());
                if (crops.isEmpty() && !KNOWN_NAMESPACES.contains(entry.getKey())) continue;
                if (entry.getKey().equals("customcrops") && crops.isEmpty()) continue;
                write(target, entry.getKey(), "craftengine", crops);
            } catch (IOException | RuntimeException error) {
                plugin.getLogger().warning("栽培数据导出失败 " + entry.getKey() + ": " + error.getMessage());
            }
        }
        if (plugin.getConfig().getBoolean("sources.customcrops-cultivation", true)) {
            Path target = customCropsTarget(plugin);
            if (Files.notExists(target, LinkOption.NOFOLLOW_LINKS)) {
                try {
                    write(target, "customcrops", "customcrops", customCropsRecords());
                } catch (IOException | RuntimeException error) {
                    plugin.getLogger().warning("CustomCrops 栽培数据导出失败: " + error.getMessage());
                }
            }
        }
    }

    /** 刷新某个集成命名空间；未知名称返回 0。 */
    public static int export(CraftEngineRecipePlugin plugin, String source) throws IOException {
        if (source.equals(CustomCropsRecipeSource.SOURCE_ID)) {
            List<JsonObject> crops = customCropsRecords();
            write(customCropsTarget(plugin), "customcrops", "customcrops", crops);
            return crops.size();
        }
        String namespace = switch (source) {
            case "farmersdelight" -> "farmersdelight";
            case "kaleidoscope" -> "kaleidoscopecookery";
            default -> null;
        };
        if (namespace == null) return 0;
        List<Path> directories = packs(craftEngineResources(plugin)).getOrDefault(namespace, List.of());
        List<JsonObject> crops = namespaceCrops(directories, namespace);
        write(target(plugin, namespace), namespace, "craftengine", crops);
        return crops.size();
    }

    static List<JsonObject> namespaceCrops(Path namespaceDirectory, String namespace) throws IOException {
        return namespaceCrops(List.of(namespaceDirectory), namespace);
    }

    static List<JsonObject> namespaceCrops(List<Path> namespaceDirectories, String namespace) throws IOException {
        List<JsonObject> records = new ArrayList<>();
        List<Map<String, Object>> documents = new ArrayList<>();
        Map<String, Map<String, Object>> templates = new LinkedHashMap<>();
        for (Path directory : namespaceDirectories) {
            for (Path file : yamlFiles(directory.resolve("configuration"))) {
                Map<String, Object> root = readYaml(file);
                documents.add(root);
                templates.putAll(blocks(root.get("templates")));
            }
        }
        for (Map<String, Object> root : documents) {
            blocks(root.get("blocks")).forEach((id, raw) ->
                    addCandidate(records, id, raw, namespace, templates));
            blocks(root.get("items")).forEach((id, raw) -> {
                Object nested = path(map(raw), "behavior", "block");
                if (nested instanceof Map<?, ?>) {
                    addCandidate(records, id, map(nested), namespace, templates);
                }
            });
        }
        return merge(records);
    }

    private static void addCandidate(List<JsonObject> out, String id, Map<String, Object> raw,
                                     String namespace, Map<String, Map<String, Object>> templates) {
        if (id == null || id.contains("${") || !cultivationBlock(raw, templates)) return;
        List<String> seeds = seedValues(raw, id, templates);
        if (seeds.isEmpty()) return;
        List<String> products = products(raw, templates);
        if (products.isEmpty()) products = seeds;
        List<String> substrates = substrates(raw, templates);
        String fallback = namespace.equals("default") ? "特殊基质或液体" : "按 CE 方块配置";
        out.add(crop(id, seeds, products, substrates, substrates.isEmpty() ? fallback : "按方块放置条件种植"));
    }

    private static boolean cultivationBlock(Map<String, Object> raw,
                                            Map<String, Map<String, Object>> templates) {
        if (cultivationBlock(raw)) return true;
        return referencedTemplates(raw, templates).stream()
                .anyMatch(CultivationDataExporter::cultivationBlock);
    }

    private static boolean cultivationBlock(Map<String, Object> raw) {
        if (containsType(raw, CULTIVATION_TYPES)) return true;
        if (containsKey(raw, "seed") || containsKey(raw, "crop_seed") || containsKey(raw, "crop_item")) return true;
        return !itemValues(path(raw, "loot", "arguments", "crop_item")).isEmpty();
    }

    private static List<String> seedValues(Map<String, Object> raw, String fallback,
                                           Map<String, Map<String, Object>> templates) {
        LinkedHashSet<String> out = new LinkedHashSet<>(valuesForKey(raw, Set.of("seed", "crop_seed")));
        out.addAll(itemValues(path(raw, "settings", "overrides", "item")));
        for (Map<String, Object> template : referencedTemplates(raw, templates)) {
            out.addAll(valuesForKey(template, Set.of("seed", "crop_seed")));
            out.addAll(itemValues(path(template, "settings", "overrides", "item")));
        }
        if (out.isEmpty()) out.add(fallback);
        return List.copyOf(out);
    }

    private static List<String> products(Map<String, Object> raw,
                                         Map<String, Map<String, Object>> templates) {
        LinkedHashSet<String> out = new LinkedHashSet<>(
                itemValuesAtKeys(raw, Set.of("crop_item", "fruit")));
        out.addAll(itemValues(raw.get("loot")));
        for (Map<String, Object> template : referencedTemplates(raw, templates)) {
            out.addAll(itemValuesAtKeys(template, Set.of("crop_item", "fruit")));
            out.addAll(itemValues(template.get("loot")));
        }
        return List.copyOf(out);
    }

    private static List<String> substrates(Map<String, Object> raw,
                                           Map<String, Map<String, Object>> templates) {
        LinkedHashSet<String> out = new LinkedHashSet<>(substrates(raw));
        for (Map<String, Object> template : referencedTemplates(raw, templates)) {
            out.addAll(substrates(template));
        }
        return out.stream().filter(id -> !id.startsWith("#")).toList();
    }

    private static List<String> substrates(Map<String, Object> raw) {
        LinkedHashSet<String> out = new LinkedHashSet<>(valuesForKey(raw,
                Set.of("bottom_blocks", "bottom-blocks", "bottom_block_tags", "bottom-block-tags",
                        "fruit_bottom_blocks", "fruit-bottom-blocks", "liquid_type", "liquid-type")));
        return out.stream().filter(id -> !id.startsWith("#")).toList();
    }

    private static List<Map<String, Object>> referencedTemplates(
            Object node, Map<String, Map<String, Object>> templates) {
        List<Map<String, Object>> found = new ArrayList<>();
        collectTemplateReferences(node, templates, found, new LinkedHashSet<>());
        return found;
    }

    private static void collectTemplateReferences(
            Object node, Map<String, Map<String, Object>> templates,
            List<Map<String, Object>> found, Set<String> seen) {
        if (node instanceof String value) {
            if (!seen.add(value)) return;
            Map<String, Object> template = templates.get(value);
            if (template != null) {
                found.add(template);
                collectTemplateReferences(template, templates, found, seen);
            }
            return;
        }
        if (node instanceof Collection<?> collection) {
            collection.forEach(value -> collectTemplateReferences(value, templates, found, seen));
            return;
        }
        if (!(node instanceof Map<?, ?> raw)) return;
        for (Object value : raw.values()) collectTemplateReferences(value, templates, found, seen);
    }

    static List<JsonObject> customCrops(Path dataFolder) throws IOException {
        List<JsonObject> records = new ArrayList<>();
        addCustomCrops(records, dataFolder);
        return merge(records);
    }

    private static boolean containsType(Object node, Set<String> wanted) {
        if (node instanceof Collection<?> collection) {
            return collection.stream().anyMatch(value -> containsType(value, wanted));
        }
        if (!(node instanceof Map<?, ?> raw)) return false;
        for (var entry : raw.entrySet()) {
            if (String.valueOf(entry.getKey()).equals("type")
                    && wanted.contains(String.valueOf(entry.getValue()))) return true;
            if (containsType(entry.getValue(), wanted)) return true;
        }
        return false;
    }

    private static boolean containsKey(Object node, String wanted) {
        if (node instanceof Collection<?> collection) {
            return collection.stream().anyMatch(value -> containsKey(value, wanted));
        }
        if (!(node instanceof Map<?, ?> raw)) return false;
        for (var entry : raw.entrySet()) {
            if (String.valueOf(entry.getKey()).equals(wanted)) return true;
            if (containsKey(entry.getValue(), wanted)) return true;
        }
        return false;
    }


    private static List<String> valuesForKey(Object node, Set<String> wanted) {
        List<String> out = new ArrayList<>();
        collectStringsForKeys(node, wanted, out);
        return out.stream().map(CultivationDataExporter::normalize)
                .filter(java.util.Objects::nonNull).distinct().toList();
    }

    private static List<String> itemValuesAtKeys(Object node, Set<String> wanted) {
        List<String> out = new ArrayList<>();
        collectItemsAtKeys(node, wanted, out);
        return out.stream().map(CultivationDataExporter::normalize)
                .filter(java.util.Objects::nonNull).distinct().toList();
    }

    private static List<String> itemValues(Object node) {
        List<String> out = new ArrayList<>();
        if (node instanceof String value) {
            String id = normalize(value);
            if (id != null) out.add(id);
            return out;
        }
        collectItems(node, out);
        return out.stream().distinct().toList();
    }

    private static void collectItems(Object node, List<String> out) {
        if (node instanceof Collection<?> collection) {
            collection.forEach(value -> collectItems(value, out));
            return;
        }
        if (!(node instanceof Map<?, ?> raw)) return;
        for (var entry : raw.entrySet()) {
            String key = String.valueOf(entry.getKey());
            if (Set.of("item", "crop_item", "fruit").contains(key)
                    && entry.getValue() instanceof String value) {
                String id = normalize(value);
                if (id != null) out.add(id);
            } else if (key.equals("items")) {
                strings(entry.getValue()).stream().map(CultivationDataExporter::normalize)
                        .filter(java.util.Objects::nonNull).forEach(out::add);
            } else if (entry.getValue() instanceof Map<?, ?> || entry.getValue() instanceof Collection<?>) {
                collectItems(entry.getValue(), out);
            }
        }
    }

    private static void collectItemsAtKeys(Object node, Set<String> wanted, List<String> out) {
        if (node instanceof Collection<?> collection) {
            collection.forEach(value -> collectItemsAtKeys(value, wanted, out));
            return;
        }
        if (!(node instanceof Map<?, ?> raw)) return;
        for (var entry : raw.entrySet()) {
            String key = String.valueOf(entry.getKey());
            if (wanted.contains(key)) out.addAll(strings(entry.getValue()));
            if (entry.getValue() instanceof Map<?, ?> || entry.getValue() instanceof Collection<?>) {
                collectItemsAtKeys(entry.getValue(), wanted, out);
            }
        }
    }

    private static void collectStringsForKeys(Object node, Set<String> wanted, List<String> out) {
        if (node instanceof Collection<?> collection) {
            collection.forEach(value -> collectStringsForKeys(value, wanted, out));
            return;
        }
        if (!(node instanceof Map<?, ?> raw)) return;
        for (var entry : raw.entrySet()) {
            if (wanted.contains(String.valueOf(entry.getKey()))) out.addAll(strings(entry.getValue()));
            if (entry.getValue() instanceof Map<?, ?> || entry.getValue() instanceof Collection<?>) {
                collectStringsForKeys(entry.getValue(), wanted, out);
            }
        }
    }

    private static void addCustomCrops(List<JsonObject> out) throws IOException {
        Plugin source = Bukkit.getPluginManager().getPlugin(CustomCropsRecipeSource.PLUGIN_NAME);
        if (PluginFingerprints.matches(source, CustomCropsRecipeSource.SOURCE_ID)) {
            addCustomCrops(out, source.getDataFolder().toPath());
        }
    }

    private static List<JsonObject> customCropsRecords() throws IOException {
        Plugin source = Bukkit.getPluginManager().getPlugin(CustomCropsRecipeSource.PLUGIN_NAME);
        return PluginFingerprints.matches(source, CustomCropsRecipeSource.SOURCE_ID)
                ? customCrops(source.getDataFolder().toPath()) : List.of();
    }

    private static void addCustomCrops(List<JsonObject> out, Path dataFolder) throws IOException {
        Map<String, List<String>> farmlandPots = new LinkedHashMap<>();
        for (Path file : yamlFiles(dataFolder.resolve("contents/pots"))) {
            for (var entry : readYaml(file).entrySet()) {
                Map<String, Object> raw = map(entry.getValue());
                List<String> substrates = new ArrayList<>(valuesForKey(raw, Set.of("vanilla-blocks")));
                if (bool(raw.get("vanilla-farmland"), false)) substrates.add("minecraft:farmland");
                if (!substrates.isEmpty()) farmlandPots.put(entry.getKey(), substrates.stream().distinct().toList());
            }
        }
        if (farmlandPots.isEmpty()) return;
        for (Path file : yamlFiles(dataFolder.resolve("contents/crops"))) {
            for (var entry : readYaml(file).entrySet()) {
                Map<String, Object> raw = map(entry.getValue());
                List<String> seeds = itemValues(raw.get("seed"));
                if (seeds.isEmpty()) continue;
                LinkedHashSet<String> substrates = new LinkedHashSet<>();
                for (String pot : strings(raw.get("pot-whitelist"))) {
                    substrates.addAll(farmlandPots.getOrDefault(pot, List.of()));
                }
                if (substrates.isEmpty()) continue;
                List<String> products = itemValues(raw.get("points"));
                if (products.isEmpty()) products = seeds;
                out.add(crop("customcrops:" + entry.getKey(), seeds, products,
                        List.copyOf(substrates), "使用 CustomCrops 原版基质种植"));
            }
        }
    }

    private static JsonObject crop(String id, List<String> seeds, List<String> products,
                                   List<String> substrates, String note) {
        JsonObject crop = new JsonObject();
        crop.addProperty("id", id);
        crop.add("seeds", array(seeds));
        crop.add("products", array(products));
        crop.add("substrates", array(substrates));
        crop.addProperty("note", note);
        return crop;
    }

    private static JsonArray array(List<String> values) {
        JsonArray array = new JsonArray();
        values.forEach(array::add);
        return array;
    }

    private static List<JsonObject> merge(List<JsonObject> crops) {
        Map<String, JsonObject> merged = new LinkedHashMap<>();
        for (JsonObject crop : crops) {
            String key = crop.get("id").getAsString();
            JsonObject previous = merged.get(key);
            if (previous == null) {
                merged.put(key, crop);
                continue;
            }
            mergeArray(previous, crop, "products");
            mergeArray(previous, crop, "substrates");
        }
        return List.copyOf(merged.values());
    }

    private static void mergeArray(JsonObject target, JsonObject source, String name) {
        LinkedHashSet<String> values = new LinkedHashSet<>();
        target.getAsJsonArray(name).forEach(value -> values.add(value.getAsString()));
        source.getAsJsonArray(name).forEach(value -> values.add(value.getAsString()));
        target.add(name, array(List.copyOf(values)));
    }

    private static void write(Path target, String namespace, String source, List<JsonObject> crops) throws IOException {
        JsonObject root = new JsonObject();
        root.addProperty("schema", 1);
        root.addProperty("source", source);
        root.addProperty("namespace", namespace);
        JsonArray array = new JsonArray();
        crops.forEach(array::add);
        root.add("crops", array);
        Files.createDirectories(target.getParent());
        Path staging = Files.createTempFile(target.getParent(), "." + namespace + "-cultivation-", ".tmp");
        Files.writeString(staging, new GsonBuilder().setPrettyPrinting().disableHtmlEscaping().create()
                .toJson(root) + "\n", StandardCharsets.UTF_8);
        try {
            Files.move(staging, target, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        } catch (java.nio.file.AtomicMoveNotSupportedException ignored) {
            Files.move(staging, target, StandardCopyOption.REPLACE_EXISTING);
        }
    }

    private static Map<String, List<Path>> packs(Path resourcesRoot) throws IOException {
        Map<String, List<Path>> packs = new LinkedHashMap<>();
        if (!Files.isDirectory(resourcesRoot)) return packs;
        try (var paths = Files.walk(resourcesRoot, 2)) {
            for (Path pack : paths.filter(path -> Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS)
                    && path.getFileName().toString().equals("pack.yml"))
                    .sorted(Comparator.comparing(Path::toString)).toList()) {
                String namespace = String.valueOf(readYaml(pack).getOrDefault("namespace", "")).trim();
                if (!namespace.isBlank()) packs.computeIfAbsent(namespace, ignored -> new ArrayList<>())
                        .add(pack.getParent());
            }
        }
        return packs;
    }

    private static Path craftEngineResources(CraftEngineRecipePlugin plugin) {
        return plugin.getDataFolder().toPath().resolveSibling("CraftEngine/resources");
    }

    private static Path target(CraftEngineRecipePlugin plugin, String namespace) {
        return plugin.getDataFolder().toPath()
                .resolve("recipes/cultivation/craftengine/" + namespace + ".json");
    }

    private static Path customCropsTarget(CraftEngineRecipePlugin plugin) {
        return plugin.getDataFolder().toPath().resolve("recipes/cultivation/customcrops/vanilla.json");
    }

    private static boolean enabled(CraftEngineRecipePlugin plugin, String namespace) {
        if (namespace.equals("customcrops")) {
            return plugin.getConfig().getBoolean("sources.customcrops-cultivation", true);
        }
        if (!plugin.getConfig().getBoolean("sources.craftengine-cultivation", true)) return false;
        if (namespace.equals("farmersdelight")) {
            return plugin.getConfig().getBoolean("sources.farmersdelight-cultivation", true);
        }
        if (namespace.equals("kaleidoscopecookery")) {
            return plugin.getConfig().getBoolean("sources.kaleidoscope-cultivation", true);
        }
        return true;
    }

    private static List<Path> yamlFiles(Path directory) throws IOException {
        if (!Files.isDirectory(directory)) return List.of();
        try (var paths = Files.walk(directory)) {
            return paths.filter(path -> Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS))
                    .filter(path -> {
                        String name = path.getFileName().toString().toLowerCase(Locale.ROOT);
                        return name.endsWith(".yml") || name.endsWith(".yaml");
                    }).sorted().toList();
        }
    }

    private static Map<String, Object> readYaml(Path file) throws IOException {
        LoaderOptions options = new LoaderOptions();
        options.setAllowDuplicateKeys(true);
        try (Reader reader = Files.newBufferedReader(file, StandardCharsets.UTF_8)) {
            Object value = new Yaml(new SafeConstructor(options)).load(reader);
            return value == null ? Map.of() : map(value);
        } catch (RuntimeException error) {
            throw new IOException("Invalid YAML " + file + ": " + error.getMessage(), error);
        }
    }

    private static Map<String, Map<String, Object>> blocks(Object raw) {
        Map<String, Map<String, Object>> result = new LinkedHashMap<>();
        map(raw).forEach((id, value) -> {
            if (value instanceof Map<?, ?>) result.put(id, map(value));
        });
        return result;
    }

    private static Map<String, Object> map(Object raw) {
        if (!(raw instanceof Map<?, ?> values)) return Map.of();
        Map<String, Object> result = new LinkedHashMap<>();
        values.forEach((key, value) -> result.put(String.valueOf(key), value));
        return result;
    }

    private static Object path(Map<String, Object> root, String... keys) {
        Object value = root;
        for (String key : keys) value = map(value).get(key);
        return value;
    }

    private static List<String> strings(Object raw) {
        if (raw == null) return List.of();
        if (raw instanceof Collection<?> collection) {
            List<String> out = new ArrayList<>();
            collection.forEach(value -> out.addAll(strings(value)));
            return out;
        }
        String value = String.valueOf(raw).trim();
        return value.isEmpty() || value.equals("null") ? List.of() : List.of(value);
    }

    private static String normalize(String value) {
        if (value == null || value.isBlank() || value.contains("${")) return null;
        if (value.contains(":")) return value.toLowerCase(Locale.ROOT);
        if (value.matches("[A-Z0-9_]+")) return "minecraft:" + value.toLowerCase(Locale.ROOT);
        if (Set.of("water", "lava", "air").contains(value.toLowerCase(Locale.ROOT))) {
            return "minecraft:" + value.toLowerCase(Locale.ROOT);
        }
        return value.matches("[a-z0-9_./-]+") ? value.toLowerCase(Locale.ROOT) : null;
    }

    private static boolean bool(Object value, boolean fallback) {
        if (value instanceof Boolean bool) return bool;
        return value == null ? fallback : Boolean.parseBoolean(String.valueOf(value));
    }
}
