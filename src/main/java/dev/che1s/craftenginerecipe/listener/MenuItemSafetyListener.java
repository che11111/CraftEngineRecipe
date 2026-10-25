package dev.che1s.craftenginerecipe.listener;

import org.bukkit.NamespacedKey;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.inventory.InventoryCreativeEvent;
import org.bukkit.inventory.ItemStack;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.plugin.Plugin;

/** 拒绝携带本插件仅用于展示的 InvUI 图标的创造模式设置槽位封包。 */
public final class MenuItemSafetyListener implements Listener {
    private final NamespacedKey displaySlotKey;

    public MenuItemSafetyListener(Plugin plugin) {
        this(new NamespacedKey(plugin, "slot"));
    }

    MenuItemSafetyListener(NamespacedKey displaySlotKey) {
        this.displaySlotKey = displaySlotKey;
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onCreativeSetSlot(InventoryCreativeEvent event) {
        // InvUI 会自行处理普通容器点击，但创造模式设置槽位封包会走 Bukkit 的另一条路径。
        // 因此需要检查传入物品，包括菜单关闭后的设置操作。
        if (this.isDisplayItem(event.getCursor())) event.setCancelled(true);
    }

    boolean isDisplayItem(ItemStack item) {
        // InvUI 2.x 会在 <所属插件>:slot 下用 BYTE 标记 SlotElement.Item 的副本。
        // 不要比较材质、模型、名称或 lore，因为真实 CE 物品也可能拥有相同值。
        return item != null && item.getPersistentDataContainer().has(this.displaySlotKey, PersistentDataType.BYTE);
    }
}
