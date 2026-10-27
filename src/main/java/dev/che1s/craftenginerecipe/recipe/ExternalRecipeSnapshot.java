package dev.che1s.craftenginerecipe.recipe;

import dev.che1s.craftenginerecipe.api.RecipeIngredient;
import dev.che1s.craftenginerecipe.api.RecipeItem;
import dev.che1s.craftenginerecipe.api.RecipeWorkstation;
import net.momirealms.craftengine.core.util.Key;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;

/** 不可变索引；不会保留第三方管理器或实时玩家背包对象。 */
public record ExternalRecipeSnapshot(Map<Key, RecipeItem> items, Map<Key, List<RecipeEntry>> results,
                                     Map<Key, List<RecipeEntry>> usages, Map<String, List<RecipeEntry>> stations,
                                     Map<String, RecipeWorkstation> workstations, List<RecipeEntry> entries,
                                     SearchIndex search) {
    public static final ExternalRecipeSnapshot EMPTY = compile(List.of());

    public static Key key(RecipeItem item) {
        if (item.provider().equals("craftengine") || item.provider().equals("minecraft")) return Key.of(item.id());
        // 第三方管理器的物品 ID 不能直接当作 CE Key，转为十六进制形式作为
        // 不透明内部键；compile() 会拒绝与此冲突的 CE ID。
        return Key.of("cer_external:" + item.provider() + "/"
                + HexFormat.of().formatHex(item.id().getBytes(StandardCharsets.UTF_8)));
    }

    public static ExternalRecipeSnapshot compile(List<RecipeEntry> entries) {
        Map<Key, RecipeItem> items = new LinkedHashMap<>();
        Map<Key, List<RecipeEntry>> results = new LinkedHashMap<>();
        Map<Key, List<RecipeEntry>> usages = new LinkedHashMap<>();
        Map<String, List<RecipeEntry>> stations = new LinkedHashMap<>();
        Map<String, RecipeWorkstation> workstations = new LinkedHashMap<>();
        Map<Key, String> names = new LinkedHashMap<>();
        Map<Key, String> aliases = new LinkedHashMap<>();
        for (RecipeEntry entry : entries) {
            var recipe = entry.display();
            workstations.putIfAbsent(recipe.workstation().id(), recipe.workstation());
            stations.computeIfAbsent(recipe.workstation().id(), ignored -> new ArrayList<>()).add(entry);
            var outputKeys = new LinkedHashSet<Key>();
            for (var result : recipe.results()) {
                Key key = key(result.item());
                catalogItem(items, names, key, result.item());
                outputKeys.add(key);
            }
            outputKeys.forEach(key -> results.computeIfAbsent(key, ignored -> new ArrayList<>()).add(entry));
            var inputKeys = new LinkedHashSet<Key>();
            for (var ingredient : recipe.ingredients()) {
                if (ingredient.role() == RecipeIngredient.Role.INFO) continue;
                for (var item : ingredient.choices()) {
                    Key key = key(item);
                    catalogItem(items, names, key, item);
                    inputKeys.add(key);
                }
            }
            inputKeys.forEach(key -> usages.computeIfAbsent(key, ignored -> new ArrayList<>()).add(entry));
        }
        Map<Key, String> searchNames = new LinkedHashMap<>();
        items.forEach((key, item) -> {
            String name = names.get(key);
            searchNames.put(key, name == null ? item.id() : name.replaceAll("<[^>]*>", ""));
            aliases.put(key, item.id());
        });
        return new ExternalRecipeSnapshot(immutable(items), lists(results), lists(usages), lists(stations),
                immutable(workstations), List.copyOf(entries), SearchIndex.fromNames(searchNames, aliases));
    }

    private static void catalogItem(Map<Key, RecipeItem> items, Map<Key, String> names, Key key, RecipeItem item) {
        RecipeItem previous = items.get(key);
        String provider = canonicalProvider(item);
        if (previous != null && (!previous.provider().equals(provider) || !previous.id().equals(item.id()))) {
            throw new IllegalArgumentException("Item identity collision at " + key + ": " + previous.provider() + "/" + previous.id()
                    + " and " + item.provider() + "/" + item.id());
        }
        var icon = item.icon();
        if (previous == null || previous.icon() == null && icon != null) {
            // 名称和 lore 覆盖只属于特定配方；分类图标保留来源元数据。
            items.put(key, new RecipeItem(provider, item.id(), icon, null, null));
        }
        if (item.name() != null) names.putIfAbsent(key, item.name());
    }

    private static String canonicalProvider(RecipeItem item) {
        // CE 也会暴露原版材料与产物 ID；两种公开写法指向同一个物品。
        if (item.id().startsWith("minecraft:")
                && (item.provider().equals("minecraft") || item.provider().equals("craftengine"))) return "minecraft";
        return item.provider();
    }

    private static <K, V> Map<K, V> immutable(Map<K, V> map) {
        return Collections.unmodifiableMap(new LinkedHashMap<>(map));
    }

    private static <K> Map<K, List<RecipeEntry>> lists(Map<K, List<RecipeEntry>> map) {
        Map<K, List<RecipeEntry>> copy = new LinkedHashMap<>();
        map.forEach((key, value) -> copy.put(key, List.copyOf(value)));
        return immutable(copy);
    }
}
