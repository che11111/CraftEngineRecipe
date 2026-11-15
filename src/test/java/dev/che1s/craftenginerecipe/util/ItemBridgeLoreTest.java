package dev.che1s.craftenginerecipe.util;

import net.kyori.adventure.text.Component;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Proxy;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotSame;

class ItemBridgeLoreTest {
    private static final Component NAME = Component.text("炖菜");
    private static final Component ORIGINAL = Component.text("食用后获得滋养效果");
    private static final List<Component> HINTS = List.of(Component.text("左键查看合成配方"), Component.text("右键查看用途"));

    @Test
    void browsingHintsFollowTheOriginalLoreAndPreserveTheSourceItem() {
        Stack source = new Stack(List.of(ORIGINAL));

        ItemStack displayed = ItemBridge.appendLore(source, HINTS);

        assertNotSame(source, displayed);
        assertEquals(NAME, displayed.getItemMeta().displayName());
        assertEquals(List.of(ORIGINAL, HINTS.get(0), HINTS.get(1)), displayed.getItemMeta().lore());
        assertEquals(List.of(ORIGINAL), source.getItemMeta().lore());
    }

    @Test
    void rebuildingDisplayFromTheSameItemDoesNotAccumulateHints() {
        Stack source = new Stack(List.of(ORIGINAL));

        ItemStack first = ItemBridge.appendLore(source, HINTS);
        ItemStack refreshed = ItemBridge.appendLore(source, HINTS);

        assertEquals(first.getItemMeta().lore(), refreshed.getItemMeta().lore());
        assertEquals(3, refreshed.getItemMeta().lore().size());
    }

    @Test
    void itemsWithoutOriginalLoreReceiveTheBrowsingHints() {
        assertEquals(HINTS, ItemBridge.appendLore(new Stack(null), HINTS).getItemMeta().lore());
    }

    /** 在不启动 Bukkit ItemFactory 的情况下测试 ItemBridge 的元数据处理。 */
    private static final class Stack extends ItemStack {
        private Component name = NAME;
        private List<Component> lore;

        private Stack(List<Component> lore) {
            this.lore = lore == null ? null : new ArrayList<>(lore);
        }

        @Override public ItemMeta getItemMeta() {
            Stack metadata = this.clone();
            return (ItemMeta) Proxy.newProxyInstance(ItemMeta.class.getClassLoader(), new Class<?>[]{ItemMeta.class},
                    (proxy, method, arguments) -> switch (method.getName()) {
                        case "displayName" -> {
                            if (arguments == null || arguments.length == 0) yield metadata.name;
                            metadata.name = (Component) arguments[0];
                            yield null;
                        }
                        case "lore" -> {
                            if (arguments == null || arguments.length == 0) yield metadata.lore;
                            @SuppressWarnings("unchecked") List<Component> lines = (List<Component>) arguments[0];
                            metadata.lore = lines == null ? null : new ArrayList<>(lines);
                            yield null;
                        }
                        default -> throw new UnsupportedOperationException(method.getName());
                    });
        }

        @Override public boolean setItemMeta(ItemMeta meta) {
            this.name = meta.displayName();
            this.lore = meta.lore() == null ? null : new ArrayList<>(meta.lore());
            return true;
        }

        @Override public Stack clone() {
            Stack copy = new Stack(this.lore);
            copy.name = this.name;
            return copy;
        }
    }
}
