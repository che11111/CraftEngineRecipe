package dev.che1s.craftenginerecipe.api;

import java.util.List;
import java.util.Objects;
import java.util.regex.Pattern;

/** API 配方中的原料、产物或工作站展示物品。图标由物品 ID 解析。 */
public record ApiItem(String id, LocalizedText name, List<LocalizedText> lore, int amount) {
    private static final Pattern ITEM_ID = Pattern.compile("[a-z0-9_.-]+:[a-z0-9/._-]+");

    public ApiItem {
        if (id == null || !ITEM_ID.matcher(id).matches()) {
            throw new IllegalArgumentException("item id must be a lowercase namespaced ID (namespace:path)");
        }
        // 来源插件可在注册后继续修改原集合；API 快照必须持有自己的不可变副本。
        lore = lore == null ? List.of() : List.copyOf(lore);
        if (lore.stream().anyMatch(Objects::isNull)) {
            throw new IllegalArgumentException("lore lines must not be null");
        }
        if (amount < 1) throw new IllegalArgumentException("amount must be positive");
    }

    public ApiItem(String id) {
        // 保留旧便捷构造器，未指定数量时始终展示一个物品。
        this(id, null, List.of(), 1);
    }

    public ApiItem(String id, LocalizedText name) {
        this(id, name, List.of(), 1);
    }

    public ApiItem(String id, LocalizedText name, List<LocalizedText> lore) {
        this(id, name, lore, 1);
    }
}
