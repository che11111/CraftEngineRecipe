package dev.che1s.craftenginerecipe.util;

import dev.che1s.craftenginerecipe.CraftEngineRecipePlugin;
import dev.che1s.craftenginerecipe.data.CategoryNamespaces;
import dev.che1s.craftenginerecipe.gui.Sounds;
import dev.che1s.craftenginerecipe.scheduler.TaskDispatcher;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.event.inventory.ClickType;
import org.bukkit.plugin.IllegalPluginAccessException;

/** Executes administrator-configured actions with the originating button's authorization. */
public final class ButtonActions {
    private ButtonActions() {}

    public static void run(Player player, CategoryNamespaces.ButtonEntry button, ClickType clickType) {
        CraftEngineRecipePlugin plugin = CraftEngineRecipePlugin.get();
        if (plugin == null || player == null || button == null) return;
        BukkitEnvironment environment = new BukkitEnvironment(plugin, player, button, plugin.data().generation());
        Runnable start = () -> ButtonActionExecutor.execute(environment, button.actionsFor(clickType));
        if (Bukkit.isOwnedByCurrentRegion(player)) start.run();
        else environment.player(start);
    }

    private record BukkitEnvironment(CraftEngineRecipePlugin plugin, Player player,
                                     CategoryNamespaces.ButtonEntry button, long generation)
            implements ButtonActionExecutor.Environment {
        @Override
        public boolean isCurrent() {
            return this.plugin.isEnabled() && this.plugin.data().isReady()
                    && this.plugin.data().generation() == this.generation;
        }

        @Override
        public boolean isPlayerAuthorized() {
            return this.player.isOnline() && this.plugin.data().namespaces().canViewButton(this.player, this.button);
        }

        @Override
        public boolean mayExecuteConsole() {
            // Paper's global task owns player state and can recheck at dispatch. On Folia only
            // the last entity-thread check authorizes this handoff; never inspect Player here.
            return this.isFolia() || this.isPlayerAuthorized();
        }

        @Override
        public boolean isFolia() { return TaskDispatcher.isFolia(); }

        @Override
        public void later(Runnable action, long ticks) {
            this.submit(() -> this.plugin.tasks().playerLater(this.player, action, ticks));
        }

        @Override
        public void player(Runnable action) {
            this.plugin.tasks().player(this.player, action);
        }

        @Override
        public void global(Runnable action) {
            this.submit(() -> this.plugin.tasks().global(action));
        }

        private void submit(Runnable submission) {
            try {
                submission.run();
            } catch (IllegalPluginAccessException error) {
                // Disabling can race with the lifecycle check immediately before submission.
                if (this.plugin.isEnabled()) throw error;
            }
        }

        @Override
        public void sound(String sound) { Sounds.playAction(this.player, sound); }

        @Override
        public void close() { this.player.closeInventory(); }

        @Override
        public void consoleCommand(String command) { Bukkit.dispatchCommand(Bukkit.getConsoleSender(), command); }

        @Override
        public void playerCommand(String command) { this.player.performCommand(command); }

        @Override
        public boolean isOp() { return this.player.isOp(); }

        @Override
        public void setOp(boolean op) { this.player.setOp(op); }

        @Override
        public void rejectFoliaOp() {
            this.plugin.getLogger().warning(this.plugin.lang().text("logs.folia-op-unsupported"));
        }
    }
}
