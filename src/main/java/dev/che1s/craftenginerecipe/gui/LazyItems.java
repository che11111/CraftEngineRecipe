package dev.che1s.craftenginerecipe.gui;

import xyz.xenondevs.invui.item.Item;

import java.util.AbstractList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.RandomAccess;
import java.util.function.Function;

/** 窗口本地列表：只为 InvUI 当前页请求的条目创建物品实例。 */
final class LazyItems<T> extends AbstractList<Item> implements RandomAccess {
    private final List<T> entries;
    private final Function<T, Item> factory;
    private final Map<Integer, Item> rendered = new HashMap<>();

    LazyItems(List<T> entries, Function<T, Item> factory) {
        this.entries = List.copyOf(entries);
        this.factory = factory;
    }

    @Override
    public Item get(int index) {
        T entry = this.entries.get(index);
        return this.rendered.computeIfAbsent(index, ignored -> this.factory.apply(entry));
    }

    @Override
    public int size() {
        return this.entries.size();
    }
}
