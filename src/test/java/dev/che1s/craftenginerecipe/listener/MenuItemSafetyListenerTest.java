package dev.che1s.craftenginerecipe.listener;

import io.papermc.paper.persistence.PersistentDataContainerView;
import org.bukkit.NamespacedKey;
import org.bukkit.event.inventory.InventoryCreativeEvent;
import org.bukkit.event.inventory.InventoryType;
import org.bukkit.inventory.InventoryView;
import org.bukkit.inventory.ItemStack;
import org.bukkit.persistence.PersistentDataType;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Proxy;

import static org.junit.jupiter.api.Assertions.*;

class MenuItemSafetyListenerTest {
    private static final NamespacedKey DISPLAY_KEY = new NamespacedKey("craftenginerecipe", "slot");
    private final MenuItemSafetyListener listener = new MenuItemSafetyListener(DISPLAY_KEY);

    @Test
    void rejectsDisplayCopiesForBothInventoryPlacementAndCreativeDrop() {
        ItemStack display = stack(DISPLAY_KEY, PersistentDataType.BYTE);
        for (int slot : new int[]{36, -999}) {
            var event = creativeEvent(slot, display);

            this.listener.onCreativeSetSlot(event);

            assertTrue(event.isCancelled());
            assertSame(display, event.getCursor(), "Do not mutate the real player's cursor or inventory");
        }
    }

    @Test
    void leavesRealItemsAndOtherPluginsItemsUntouched() {
        for (ItemStack real : new ItemStack[]{null,
                stack(null, null),
                stack(new NamespacedKey("anotherplugin", "slot"), PersistentDataType.BYTE),
                stack(DISPLAY_KEY, PersistentDataType.STRING)}) {
            var event = creativeEvent(36, real);

            this.listener.onCreativeSetSlot(event);

            assertFalse(event.isCancelled());
            assertSame(real, event.getCursor());
        }
    }

    private static InventoryCreativeEvent creativeEvent(int slot, ItemStack incoming) {
        InventoryView view = (InventoryView) Proxy.newProxyInstance(InventoryView.class.getClassLoader(),
                new Class<?>[]{InventoryView.class}, (proxy, method, args) -> {
                    if (method.getName().equals("convertSlot")) return args[0];
                    throw new AssertionError("Unexpected view mutation or access: " + method.getName());
                });
        return new InventoryCreativeEvent(view, slot < 0 ? InventoryType.SlotType.OUTSIDE : InventoryType.SlotType.QUICKBAR,
                slot, incoming);
    }

    private static ItemStack stack(NamespacedKey key, PersistentDataType<?, ?> type) {
        PersistentDataContainerView data = (PersistentDataContainerView) Proxy.newProxyInstance(
                PersistentDataContainerView.class.getClassLoader(), new Class<?>[]{PersistentDataContainerView.class},
                (proxy, method, args) -> {
                    if (method.getName().equals("has") && args.length == 2) {
                        return args[0].equals(key) && args[1] == type;
                    }
                    throw new AssertionError("Unexpected metadata mutation or access: " + method.getName());
                });
        return new ItemStack() {
            @Override
            public PersistentDataContainerView getPersistentDataContainer() { return data; }
        };
    }
}
