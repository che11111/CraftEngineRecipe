package dev.che1s.craftenginerecipe.integration.craftengine;

import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import dev.che1s.craftenginerecipe.api.extension.SourceContext;
import dev.che1s.craftenginerecipe.api.extension.SourceText;
import dev.che1s.craftenginerecipe.api.extension.YamlTree;
import org.bukkit.Bukkit;
import org.bukkit.plugin.Plugin;

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

import static dev.che1s.craftenginerecipe.api.extension.YamlTree.map;
import static dev.che1s.craftenginerecipe.api.extension.YamlTree.path;
import static dev.che1s.craftenginerecipe.api.extension.YamlTree.strings;

/**
 * 将每个 CraftEngine 命名空间的栽培元数据导出为单文件，
 * 并额外导出 CustomCrops 可选的原版方块种植盆定义。
 */
public final class CultivationDataExporter {
    public static final String FILE_NAME = "vanilla.json";
    private static final Set<String> CULTIVATION_TYPES = Set.of(
            "crop_block", "stem_block", "vertical_crop_block", "vine_crop_head_block",
            "vine_crop_body_block");
    private static final Set<String> MATURE_BLOCK_KEYS = Set.of("mature-block", "mature_block");
    private static final Set<String> KNOWN_NAMESPACES =
            Set.of("default", "minecraft", "farmersdelight", "kaleidoscopecookery", "customcrops");

    private CultivationDataExporter() {}

    /**
     * Captures every enabled CraftEngine namespace as a normalized cultivation document.
     * This is used by the independent craftengine-cultivation extension.
     */
    public static Map<String, JsonObject> captureCraftEngineNamespaces(SourceContext context) {
        Path root = craftEngineResources(context);
        if (!Files.isDirectory(root)) return Map.of();
        Map<String, JsonObject> result = new LinkedHashMap<>();
        try {
            for (var entry : packs(root).entrySet()) {
                if (!enabled(context, entry.getKey())) continue;
                List<JsonObject> crops = namespaceCrops(entry.getValue(), entry.getKey(),
                        context.text("cultivation"));
                if ((crops.isEmpty() && !KNOWN_NAMESPACES.contains(entry.getKey()))
                        || (entry.getKey().equals("customcrops") && crops.isEmpty())) {
                    continue;
                }
                result.put(entry.getKey(), document(entry.getKey(), "craftengine", crops));
            }
        } catch (IOException | RuntimeException error) {
            context.logger().warning("捕获 CraftEngine 耕地数据失败: " + error.getMessage());
        }
        return Map.copyOf(result);
    }

    public static void exportMissing(SourceContext context) {
        Path root = craftEngineResources(context);
        if (!Files.isDirectory(root)) return;
        Map<String, List<Path>> packs;
        try {
            packs = packs(root);
        } catch (IOException | RuntimeException error) {
            context.logger().warning("读取 CraftEngine 资源包失败: " + error.getMessage());
            return;
        }
        for (var entry : packs.entrySet()) {
            if (!enabled(context, entry.getKey())) continue;
            Path target = target(context, entry.getKey());
            boolean missing = Files.notExists(target, LinkOption.NOFOLLOW_LINKS);
            try {
                if (!missing && !needsSeedFallbackRepair(target)) continue;
                List<JsonObject> crops = namespaceCrops(entry.getValue(), entry.getKey(),
                        context.text("cultivation"));
                if (crops.isEmpty() && !KNOWN_NAMESPACES.contains(entry.getKey())) continue;
                if (entry.getKey().equals("customcrops") && crops.isEmpty()) continue;
                if (missing) {
                    write(target, entry.getKey(), "craftengine", crops);
                } else {
                    repairSeedFallbacks(target, crops);
                }
            } catch (IOException | RuntimeException error) {
                context.logger().warning("栽培数据导出失败 " + entry.getKey() + ": " + error.getMessage());
            }
        }
        if (enabled(context, "customcrops")) {
            Path target = customCropsTarget(context);
            if (Files.notExists(target, LinkOption.NOFOLLOW_LINKS)) {
                try {
                    write(target, "customcrops", "customcrops",
                            customCropsRecords(context.text("cultivation")));
                } catch (IOException | RuntimeException error) {
                    context.logger().warning("CustomCrops 栽培数据导出失败: " + error.getMessage());
                }
            }
        }
    }

    /** 刷新某个集成命名空间；未知名称返回 0。 */
    public static int export(SourceContext context, String source) throws IOException {
        if (source.equals("customcrops")) {
            if (!enabled(context, "customcrops")) return 0;
            List<JsonObject> crops = customCropsRecords(context.text("cultivation"));
            write(customCropsTarget(context), "customcrops", "customcrops", crops);
            return crops.size();
        }
        String namespace = switch (source) {
            case "farmersdelight" -> "farmersdelight";
            case "kaleidoscope" -> "kaleidoscopecookery";
            default -> null;
        };
        if (namespace == null || !enabled(context, source)) return 0;
        List<Path> directories = packs(craftEngineResources(context)).getOrDefault(namespace, List.of());
        List<JsonObject> crops = namespaceCrops(directories, namespace,
                context.text("cultivation"));
        write(target(context, namespace), namespace, "craftengine", crops);
        return crops.size();
    }

    static List<JsonObject> namespaceCrops(Path namespaceDirectory, String namespace) throws IOException {
        return namespaceCrops(List.of(namespaceDirectory), namespace, SourceText.fallbacks());
    }

    static List<JsonObject> namespaceCrops(List<Path> namespaceDirectories, String namespace) throws IOException {
        return namespaceCrops(namespaceDirectories, namespace, SourceText.fallbacks());
    }

    static List<JsonObject> namespaceCrops(List<Path> namespaceDirectories, String namespace,
                                           SourceText text) throws IOException {
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
        Map<String, LinkedHashSet<String>> itemSeedsByBlock = new LinkedHashMap<>();
        Map<String, LinkedHashSet<String>> explicitSeedsByBlock = new LinkedHashMap<>();
        Map<String, LinkedHashSet<String>> matureTransitions = new LinkedHashMap<>();
        for (Map<String, Object> root : documents) {
            blocks(root.get("items")).forEach((id, raw) ->
                    addItemSeedBindings(itemSeedsByBlock, id, raw, templates));
        }
        for (Map<String, Object> root : documents) {
            blocks(root.get("blocks")).forEach((id, raw) -> {
                addValues(explicitSeedsByBlock, id, explicitSeedValues(raw, templates));
                addValues(matureTransitions, id, matureTargets(raw, templates));
            });
        }
        propagateBoundSeeds(explicitSeedsByBlock, matureTransitions);
        propagateBoundSeeds(itemSeedsByBlock, matureTransitions);
        for (Map<String, Object> root : documents) {
            blocks(root.get("blocks")).forEach((id, raw) ->
                    addCandidate(records, id, raw, namespace, templates, text,
                            explicitSeedsByBlock, itemSeedsByBlock));
            blocks(root.get("items")).forEach((id, raw) -> {
                Object nested = path(map(raw), "behavior", "block");
                if (nested instanceof Map<?, ?>) {
                    addCandidate(records, id, map(nested), namespace, templates, text,
                            explicitSeedsByBlock, itemSeedsByBlock);
                }
            });
        }
        return merge(records);
    }

    private static void addCandidate(List<JsonObject> out, String id, Map<String, Object> raw,
                                     String namespace, Map<String, Map<String, Object>> templates,
                                     SourceText text,
                                     Map<String, LinkedHashSet<String>> explicitSeedsByBlock,
                                     Map<String, LinkedHashSet<String>> itemSeedsByBlock) {
        if (id == null || id.contains("${") || !cultivationBlock(raw, templates)) return;
        List<String> seeds = seedValues(raw, id, templates, explicitSeedsByBlock, itemSeedsByBlock);
        if (seeds.isEmpty()) return;
        List<String> products = products(raw, templates);
        if (products.isEmpty()) products = seeds;
        List<String> substrates = substrates(raw, templates);
        String fallback = namespace.equals("default")
                ? text.text("note.special_substrate", "特殊基质或液体")
                : text.text("note.craftengine_block", "按 CE 方块配置");
        out.add(crop(id, seeds, products, substrates, substrates.isEmpty()
                ? fallback : text.text("note.placement", "按方块放置条件种植")));
    }

    /**
     * CraftEngine's block-item behavior connects an item to the block it plants.
     * Use that relation when a crop block does not declare its seed explicitly.
     */
    private static void addItemSeedBindings(Map<String, LinkedHashSet<String>> seedsByBlock,
                                            String id, Map<String, Object> raw,
                                            Map<String, Map<String, Object>> templates) {
        String seedItem = normalize(id);
        if (seedItem == null) return;
        collectItemBlockBindings(seedsByBlock, seedItem, raw.get("behavior"));
        collectItemBlockBindings(seedsByBlock, seedItem, raw.get("behaviors"));
        for (Map<String, Object> template : referencedTemplates(raw, templates)) {
            collectItemBlockBindings(seedsByBlock, seedItem, template.get("behavior"));
            collectItemBlockBindings(seedsByBlock, seedItem, template.get("behaviors"));
        }
    }

    private static void collectItemBlockBindings(Map<String, LinkedHashSet<String>> seedsByBlock,
                                                 String seedItem, Object behavior) {
        if (behavior instanceof Collection<?> collection) {
            collection.forEach(value -> collectItemBlockBindings(seedsByBlock, seedItem, value));
            return;
        }
        if (!(behavior instanceof Map<?, ?> raw)) return;
        Object type = raw.get("type");
        if (!(type instanceof String typeName)
                || !(typeName.equals("block_item") || typeName.endsWith("_block_item"))) {
            return;
        }
        Object block = raw.get("block");
        if (!(block instanceof String value)) return;
        String blockId = normalize(expandItemPlaceholders(value, seedItem));
        if (blockId == null) return;
        seedsByBlock.computeIfAbsent(blockId, ignored -> new LinkedHashSet<>()).add(seedItem);
    }

    private static String expandItemPlaceholders(String value, String itemId) {
        int separator = itemId.indexOf(':');
        String namespace = separator < 0 ? "minecraft" : itemId.substring(0, separator);
        String path = separator < 0 ? itemId : itemId.substring(separator + 1);
        return value.replace("${__NAMESPACE__}", namespace).replace("${__ID__}", path);
    }

    private static List<String> explicitSeedValues(
            Map<String, Object> raw, Map<String, Map<String, Object>> templates) {
        LinkedHashSet<String> out = new LinkedHashSet<>(
                valuesForKey(raw, Set.of("seed", "crop_seed")));
        out.addAll(itemValues(path(raw, "settings", "overrides", "item")));
        for (Map<String, Object> template : referencedTemplates(raw, templates)) {
            out.addAll(valuesForKey(template, Set.of("seed", "crop_seed")));
            out.addAll(itemValues(path(template, "settings", "overrides", "item")));
        }
        return List.copyOf(out);
    }

    private static List<String> matureTargets(
            Map<String, Object> raw, Map<String, Map<String, Object>> templates) {
        LinkedHashSet<String> out = new LinkedHashSet<>(valuesForKey(raw, MATURE_BLOCK_KEYS));
        for (Map<String, Object> template : referencedTemplates(raw, templates)) {
            out.addAll(valuesForKey(template, MATURE_BLOCK_KEYS));
        }
        return List.copyOf(out);
    }

    private static void addValues(Map<String, LinkedHashSet<String>> target,
                                  String key, List<String> values) {
        if (values.isEmpty()) return;
        target.computeIfAbsent(key, ignored -> new LinkedHashSet<>()).addAll(values);
    }

    /** A mature crop often has no seed field of its own; inherit it from the planting stage. */
    private static void propagateBoundSeeds(Map<String, LinkedHashSet<String>> seedsByBlock,
                                            Map<String, LinkedHashSet<String>> transitions) {
        boolean changed;
        do {
            changed = false;
            for (var transition : transitions.entrySet()) {
                LinkedHashSet<String> source = seedsByBlock.get(transition.getKey());
                if (source == null || source.isEmpty()) continue;
                for (String target : transition.getValue()) {
                    LinkedHashSet<String> targetSeeds = seedsByBlock.get(target);
                    if (targetSeeds == null || targetSeeds.isEmpty()) {
                        seedsByBlock.put(target, new LinkedHashSet<>(source));
                        changed = true;
                    }
                }
            }
        } while (changed);
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

    private static List<String> seedValues(
            Map<String, Object> raw, String fallback,
            Map<String, Map<String, Object>> templates,
            Map<String, LinkedHashSet<String>> explicitSeedsByBlock,
            Map<String, LinkedHashSet<String>> itemSeedsByBlock) {
        LinkedHashSet<String> out = new LinkedHashSet<>(explicitSeedValues(raw, templates));
        if (out.isEmpty()) out.addAll(explicitSeedsByBlock.getOrDefault(fallback, new LinkedHashSet<>()));
        if (out.isEmpty()) out.addAll(itemSeedsByBlock.getOrDefault(fallback, new LinkedHashSet<>()));
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
        return customCrops(dataFolder, SourceText.fallbacks());
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
        Plugin source = Bukkit.getPluginManager().getPlugin("CustomCrops");
        if (PluginFingerprints.matches(source, "customcrops")) {
            addCustomCrops(out, source.getDataFolder().toPath(), SourceText.fallbacks());
        }
    }

    private static List<JsonObject> customCropsRecords() throws IOException {
        return customCropsRecords(SourceText.fallbacks());
    }

    private static List<JsonObject> customCropsRecords(SourceText text) throws IOException {
        Plugin source = Bukkit.getPluginManager().getPlugin("CustomCrops");
        return PluginFingerprints.matches(source, "customcrops")
                ? customCrops(source.getDataFolder().toPath(), text) : List.of();
    }

    private static List<JsonObject> customCrops(Path dataFolder, SourceText text) throws IOException {
        List<JsonObject> out = new ArrayList<>();
        addCustomCrops(out, dataFolder, text);
        return merge(out);
    }

    private static void addCustomCrops(List<JsonObject> out, Path dataFolder,
                                       SourceText text) throws IOException {
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
                        List.copyOf(substrates), text.text("note.customcrops_farmland",
                                "使用 CustomCrops 原版基质种植")));
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
        writeJson(target, document(namespace, source, crops));
    }

    private static JsonObject document(String namespace, String source, List<JsonObject> crops) {
        JsonObject root = new JsonObject();
        root.addProperty("schema", 1);
        root.addProperty("source", source);
        root.addProperty("namespace", namespace);
        JsonArray array = new JsonArray();
        crops.forEach(array::add);
        root.add("crops", array);
        return root;
    }

    /**
     * Older exports could use the crop block ID as its seed. Repair only that exact fallback,
     * preserving unrelated manual edits in otherwise user-maintained JSON.
     */
    static void repairSeedFallbacks(Path target, List<JsonObject> generated) throws IOException {
        JsonObject root;
        try (Reader reader = Files.newBufferedReader(target, StandardCharsets.UTF_8)) {
            root = JsonParser.parseReader(reader).getAsJsonObject();
        }
        JsonArray existingCrops = root.getAsJsonArray("crops");
        if (existingCrops == null) return;
        Map<String, JsonObject> generatedById = new LinkedHashMap<>();
        generated.forEach(crop -> generatedById.put(crop.get("id").getAsString(), crop));
        boolean changed = false;
        for (JsonElement element : existingCrops) {
            if (!element.isJsonObject()) continue;
            JsonObject existing = element.getAsJsonObject();
            String id = string(existing, "id");
            JsonObject expected = generatedById.get(id);
            if (expected == null) continue;
            JsonArray seeds = existing.getAsJsonArray("seeds");
            if (seeds == null || seeds.asList().stream().noneMatch(value -> id.equals(value.getAsString()))) {
                continue;
            }
            LinkedHashSet<String> repaired = new LinkedHashSet<>();
            seeds.forEach(value -> repaired.add(value.getAsString()));
            repaired.remove(id);
            JsonArray expectedSeeds = expected.getAsJsonArray("seeds");
            if (expectedSeeds == null || expectedSeeds.asList().isEmpty()) continue;
            expectedSeeds.forEach(value -> repaired.add(value.getAsString()));
            if (repaired.contains(id)) continue;
            existing.add("seeds", array(List.copyOf(repaired)));
            changed = true;
        }
        if (changed) writeJson(target, root);
    }

    private static boolean needsSeedFallbackRepair(Path target) throws IOException {
        JsonElement parsed;
        try (Reader reader = Files.newBufferedReader(target, StandardCharsets.UTF_8)) {
            parsed = JsonParser.parseReader(reader);
        }
        if (!parsed.isJsonObject()) return false;
        JsonArray crops = parsed.getAsJsonObject().getAsJsonArray("crops");
        if (crops == null) return false;
        for (JsonElement element : crops) {
            if (!element.isJsonObject()) continue;
            JsonObject crop = element.getAsJsonObject();
            String id = string(crop, "id");
            JsonArray seeds = crop.getAsJsonArray("seeds");
            if (seeds != null && seeds.asList().stream()
                    .anyMatch(value -> id.equals(value.getAsString()))) {
                return true;
            }
        }
        return false;
    }

    private static String string(JsonObject object, String key) {
        JsonElement value = object.get(key);
        return value == null || value.isJsonNull() ? "" : value.getAsString();
    }

    private static void writeJson(Path target, JsonObject root) throws IOException {
        Files.createDirectories(target.getParent());
        Path staging = Files.createTempFile(target.getParent(),
                "." + target.getFileName() + "-", ".tmp");
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

    private static Path craftEngineResources(SourceContext context) {
        return context.coreDataFolder().resolveSibling("CraftEngine/resources");
    }

    private static Path target(SourceContext context, String namespace) {
        return context.coreDataFolder()
                .resolve("recipes/cultivation/craftengine/" + namespace + ".json");
    }

    private static Path customCropsTarget(SourceContext context) {
        return context.coreDataFolder().resolve("recipes/cultivation/customcrops/vanilla.json");
    }

    private static boolean enabled(SourceContext context, String namespace) {
        boolean exportMissing = context.coreOption("sources.export-missing", true);
        if (namespace.equals("customcrops")) {
            return context.sourceEnabled("customcrops")
                    && context.sourceOption("customcrops", "cultivation", true)
                    && context.sourceOption("customcrops", "export-missing", true);
        }
        if (!context.sourceEnabled("craftengine_cultivation")) return false;
        if (namespace.equals("farmersdelight")) {
            return context.sourceEnabled("farmersdelight")
                    && context.sourceOption("farmersdelight", "cultivation", true)
                    && context.sourceOption("farmersdelight", "export-missing", true);
        }
        if (namespace.equals("kaleidoscopecookery")) {
            return context.sourceEnabled("kaleidoscope")
                    && context.sourceOption("kaleidoscope", "cultivation", true)
                    && context.sourceOption("kaleidoscope", "export-missing", true);
        }
        return exportMissing;
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
        try {
            return YamlTree.read(file, "CraftEngine", true);
        } catch (IllegalStateException error) {
            throw new IOException(error.getMessage(), error);
        }
    }

    private static Map<String, Map<String, Object>> blocks(Object raw) {
        Map<String, Map<String, Object>> result = new LinkedHashMap<>();
        map(raw).forEach((id, value) -> {
            if (value instanceof Map<?, ?>) result.put(id, map(value));
        });
        return result;
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
