package dev.leon.cer.gui;

import org.junit.jupiter.api.Test;
import xyz.xenondevs.invui.item.Item;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.IntStream;

import static org.junit.jupiter.api.Assertions.*;

class LazyItemsTest {
    @Test
    void largeMenuCreatesOnlyVisitedSlotsAndReusesPages() {
        AtomicInteger builds = new AtomicInteger();
        List<Integer> entries = IntStream.range(0, 50_000).boxed().toList();
        List<Item> items = new LazyItems<>(entries, entry -> {
            builds.incrementAndGet();
            return Item.builder().setItemProvider(viewer -> null).build();
        });
        assertEquals(50_000, items.size());
        assertEquals(0, builds.get());
        for (int i = 0; i < 36; i++) items.get(i);
        assertEquals(36, builds.get());
        Item first = items.get(0);
        for (int i = 36; i < 72; i++) items.get(i);
        assertEquals(72, builds.get());
        assertSame(first, items.get(0));
        assertEquals(72, builds.get());
        assertThrows(IndexOutOfBoundsException.class, () -> items.get(-1));
        assertThrows(IndexOutOfBoundsException.class, () -> items.get(50_000));
        assertEquals(72, builds.get());
    }

    @Test
    void windowsCopyEntryOrderAndKeepRenderedObjectsPrivate() {
        List<Integer> source = new ArrayList<>(List.of(1, 2, 3));
        List<Integer> requested = new ArrayList<>();
        java.util.function.Function<Integer, Item> factory = entry -> {
            requested.add(entry);
            return Item.builder().setItemProvider(viewer -> null).build();
        };
        List<Item> first = new LazyItems<>(source, factory);
        List<Item> second = new LazyItems<>(source, factory);
        source.clear();
        assertNotSame(first.get(2), second.get(2));
        assertEquals(List.of(3, 3), requested);
        assertEquals(3, first.size());
        assertThrows(UnsupportedOperationException.class, () -> first.add(Item.EMPTY));
    }
}
