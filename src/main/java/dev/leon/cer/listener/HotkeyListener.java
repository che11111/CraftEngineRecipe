package dev.leon.cer.listener;

import dev.leon.cer.CraftEngineRecipePlugin;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerSwapHandItemsEvent;

public final class HotkeyListener implements Listener {

    private final CraftEngineRecipePlugin plugin;

    public HotkeyListener(CraftEngineRecipePlugin plugin) {
        this.plugin = plugin;
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onSwapHand(PlayerSwapHandItemsEvent event) {
        if (!this.plugin.data().isReady()) return;
        if (!this.plugin.config().hotkeyEnabled()) {
            return;
        }
        Player player = event.getPlayer();
        if (this.plugin.config().hotkeyRequireSneak() && !player.isSneaking()) {
            return;
        }
        if (!this.plugin.data().isReady()) {
            return;
        }
        event.setCancelled(true);
        this.plugin.browser().openApplied(player);
    }
}
