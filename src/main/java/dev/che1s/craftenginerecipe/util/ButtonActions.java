package dev.che1s.craftenginerecipe.util;

import dev.che1s.craftenginerecipe.CraftEngineRecipePlugin;
import dev.che1s.craftenginerecipe.category.CategoryNamespaces;
import dev.che1s.craftenginerecipe.gui.Sounds;
import dev.che1s.craftenginerecipe.scheduler.TaskDispatcher;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.event.inventory.ClickType;
import org.bukkit.plugin.IllegalPluginAccessException;

/** 使用原按钮的授权状态执行管理员配置的动作。 */
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
            // Paper 的全局任务可访问玩家状态，并能在派发时重新检查。在 Folia 中，
            // 只有最后一次实体线程检查构成移交授权；这里绝不能访问 Player。
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
                // 插件禁用可能与提交前的生命周期检查发生竞争。
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
