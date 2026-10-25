package dev.che1s.craftenginerecipe.category;

import net.momirealms.craftengine.core.plugin.gui.category.Category;
import net.momirealms.craftengine.core.util.Key;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;
import java.util.function.Predicate;

/** 同一数据版本内供浏览与搜索共用的不可变成员解析器。 */
public final class CategoryGraph {
    public record Member(Key key, boolean category, String reference) {}

    private final Map<Key, Category> categories;
    private final Map<Key, List<Member>> members;
    private final Set<Key> referencedItems;

    private CategoryGraph(Map<Key, Category> categories, Map<Key, List<Member>> members) {
        this.categories = Map.copyOf(categories);
        this.members = Map.copyOf(members);
        Set<Key> items = new HashSet<>();
        members.values().forEach(entries -> entries.stream().filter(member -> !member.category())
                .forEach(member -> items.add(member.key())));
        this.referencedItems = Set.copyOf(items);
    }

    public static CategoryGraph compile(Map<Key, Category> categories) {
        Map<Key, List<Member>> parsed = new LinkedHashMap<>();
        categories.forEach((id, category) -> {
            List<Member> entries = new ArrayList<>();
            if (category.members() != null) {
                for (String member : category.members()) {
                    if (member == null || member.isBlank()) continue;
                    boolean child = member.charAt(0) == '#';
                    String reference = child ? member.substring(1) : member;
                    entries.add(new Member(Key.of(reference), child, reference));
                }
            }
            parsed.put(id, List.copyOf(entries));
        });
        return new CategoryGraph(categories, parsed);
    }

    public List<Member> members(Key id) {
        return this.members.getOrDefault(id, List.of());
    }

    /** 记录已捕获分类中出现过的物品，包括无法通过允许路径到达的物品。 */
    public boolean hasItemReference(Key item) {
        return this.referencedItems.contains(item);
    }

    public Set<Key> visible(Collection<Category> roots, Predicate<Category> permitted,
                            Function<Category, Set<String>> hiddenItems) {
        Set<Key> items = new HashSet<>();
        Set<Key> visited = new HashSet<>();
        ArrayDeque<Category> queue = new ArrayDeque<>(roots);
        while (!queue.isEmpty()) {
            Category category = queue.remove();
            if (!visited.add(category.id()) || !permitted.test(category)) continue;
            Set<String> hidden = hiddenItems.apply(category);
            for (Member member : this.members(category.id())) {
                if (member.category()) {
                    Category child = this.categories.get(member.key());
                    if (child != null) queue.add(child);
                } else if (!CategoryItems.isHidden(hidden, member.key())) {
                    items.add(member.key());
                }
            }
        }
        // 这个本地集合发布后不再修改，避免每次玩家查询都重新复制所有可见物品。
        return Collections.unmodifiableSet(items);
    }
}
