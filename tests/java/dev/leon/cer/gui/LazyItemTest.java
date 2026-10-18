package dev.leon.cer.gui;

import org.junit.jupiter.api.Test;
import xyz.xenondevs.invui.item.Item;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import static org.junit.jupiter.api.Assertions.*;

class LazyItemTest {
    @Test
    void largeCategoryBuildsOnlyRenderedEntriesAndReusesTheirIcons() {
        AtomicInteger builds = new AtomicInteger();
        List<Item> items = new ArrayList<>();
        for (int i = 0; i < 2000; i++) {
            items.add(BrowserService.lazyItem(() -> {
                builds.incrementAndGet();
                return Item.builder().setItemProvider(viewer -> null).build();
            }));
        }
        assertEquals(0, builds.get());
        for (int i = 0; i < 36; i++) items.get(i).getItemProvider(null);
        assertEquals(36, builds.get());
        for (int i = 0; i < 36; i++) items.get(i).getItemProvider(null);
        assertEquals(36, builds.get());
        items.get(36).getItemProvider(null);
        assertEquals(37, builds.get());
    }

    @Test
    void separateWindowsDoNotShareTheSameMaterializedItem() {
        AtomicInteger builds = new AtomicInteger();
        java.util.function.Supplier<Item> factory = () -> {
            builds.incrementAndGet();
            return Item.builder().setItemProvider(viewer -> null).build();
        };
        Item first = BrowserService.lazyItem(factory);
        Item second = BrowserService.lazyItem(factory);
        first.getItemProvider(null);
        second.getItemProvider(null);
        assertEquals(2, builds.get());
    }
}
