package dev.che1s.craftenginerecipe.listener;

import dev.che1s.craftenginerecipe.CraftEngineRecipePlugin;
import dev.che1s.craftenginerecipe.debug.Diagnostics;
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
        Player player = event.getPlayer();
        var preferences = this.plugin.playerPreferences();
        boolean playerEnabled = preferences == null || preferences.hotkeyEnabled(player.getUniqueId());
        if (!shouldHandle(this.plugin.config().hotkeyEnabled(), playerEnabled, player.isSneaking())) {
            return;
        }
        event.setCancelled(true);
        this.plugin.browser(player).deferMenuAction(player, () -> {
            try (var timing = this.plugin.diagnostics().measure(Diagnostics.Operation.HOTKEY)) {
                this.plugin.browser(player).openApplied(player);
            }
        });
    }

    static boolean shouldHandle(boolean globallyEnabled, boolean playerEnabled, boolean sneaking) {
        return globallyEnabled && playerEnabled && sneaking;
    }
}
