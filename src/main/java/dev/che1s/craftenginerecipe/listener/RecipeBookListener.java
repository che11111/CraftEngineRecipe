package dev.che1s.craftenginerecipe.listener;

import dev.che1s.craftenginerecipe.CraftEngineRecipePlugin;
import dev.che1s.craftenginerecipe.preferences.PlayerPreferences;
import org.bukkit.event.Event;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.Action;
import org.bukkit.event.inventory.PrepareItemCraftEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.inventory.EquipmentSlot;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/** Opens CER views when the player swings the recipe book. */
public final class RecipeBookListener implements Listener {
    private static final long DEBOUNCE_MILLIS = 150L;

    private final CraftEngineRecipePlugin plugin;
    private final Map<UUID, Long> lastUse = new ConcurrentHashMap<>();

    public RecipeBookListener(CraftEngineRecipePlugin plugin) {
        this.plugin = plugin;
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = false)
    public void onInteract(PlayerInteractEvent event) {
        if (event.getHand() == EquipmentSlot.OFF_HAND
                || !this.plugin.recipeBook().enabled()
                || !this.plugin.recipeBook().isRecipeBook(
                        event.getPlayer().getInventory().getItemInMainHand())) {
            return;
        }
        Action action = event.getAction();
        boolean right = action == Action.RIGHT_CLICK_AIR || action == Action.RIGHT_CLICK_BLOCK;
        boolean left = action == Action.LEFT_CLICK_AIR || action == Action.LEFT_CLICK_BLOCK;
        if (!left && !right) return;

        long now = System.currentTimeMillis();
        long previous = this.lastUse.getOrDefault(event.getPlayer().getUniqueId(), 0L);
        if (now - previous < DEBOUNCE_MILLIS) return;
        this.lastUse.put(event.getPlayer().getUniqueId(), now);

        PlayerPreferences.BookSlot slot = event.getPlayer().isSneaking()
                ? (right ? PlayerPreferences.BookSlot.SNEAK_RIGHT
                        : PlayerPreferences.BookSlot.SNEAK_LEFT)
                : (right ? PlayerPreferences.BookSlot.RIGHT : PlayerPreferences.BookSlot.LEFT);
        event.setCancelled(true);
        event.setUseInteractedBlock(Event.Result.DENY);
        event.setUseItemInHand(Event.Result.DENY);
        this.plugin.browser(event.getPlayer()).deferMenuAction(event.getPlayer(),
                () -> this.plugin.recipeBook().perform(event.getPlayer(), slot));
    }

    @EventHandler(priority = EventPriority.HIGHEST)
    public void onPrepareCraft(PrepareItemCraftEvent event) {
        if (!this.plugin.recipeBook().isRecipeBook(event.getInventory().getResult())) return;
        if (!this.plugin.recipeBook().craftingEnabled()
                || !(event.getView().getPlayer() instanceof org.bukkit.entity.Player player)) {
            event.getInventory().setResult(null);
            return;
        }
        event.getInventory().setResult(this.plugin.recipeBook().create(player));
    }
}
