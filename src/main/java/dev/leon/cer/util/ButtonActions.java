package dev.leon.cer.util;

import dev.leon.cer.CraftEngineRecipePlugin;
import dev.leon.cer.gui.Sounds;
import dev.leon.cer.scheduler.TaskDispatcher;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;

import java.util.List;
import java.util.Locale;

/**
 * 自定义按钮的动作执行器。支持：sound / close / delay / command(console) / player / op。
 */
public final class ButtonActions {

    private ButtonActions() {
    }

    public static void run(Player player, List<String> actions) {
        CraftEngineRecipePlugin plugin = CraftEngineRecipePlugin.get();
        if (plugin == null) return;
        run(plugin, player, actions, 0, plugin.data().generation());
    }

    private static void run(CraftEngineRecipePlugin plugin, Player player, List<String> actions, int index, long generation) {
        if (!plugin.isEnabled() || !plugin.data().isReady() || plugin.data().generation() != generation
                || player == null || !player.isOnline() || actions == null || index >= actions.size()) {
            return;
        }
        String action = actions.get(index);
        if (action == null) {
            run(plugin, player, actions, index + 1, generation);
            return;
        }
        action = action.trim();
        if (action.isEmpty()) {
            run(plugin, player, actions, index + 1, generation);
            return;
        }
        String lower = action.toLowerCase(Locale.ROOT);

        if (lower.equals("close")) {
            player.closeInventory();
            run(plugin, player, actions, index + 1, generation);
            return;
        }
        if (lower.startsWith("delay:")) {
            long ticks = parseDelayTicks(action.substring("delay:".length()).trim());
            plugin.tasks().playerLater(player, () -> run(plugin, player, actions, index + 1, generation), ticks);
            return;
        }
        if (lower.startsWith("sound:")) {
            Sounds.playAction(player, action.substring(action.indexOf(':') + 1).trim());
            run(plugin, player, actions, index + 1, generation);
            return;
        }
        if (lower.startsWith("console:")) {
            String cmd = action.substring(action.indexOf(':') + 1).trim();
            if (cmd.isEmpty()) {
                run(plugin, player, actions, index + 1, generation);
                return;
            }
            plugin.tasks().global(() -> {
                if (!plugin.data().isReady() || plugin.data().generation() != generation) return;
                Bukkit.dispatchCommand(Bukkit.getConsoleSender(), cmd);
                plugin.tasks().player(player, () -> run(plugin, player, actions, index + 1, generation));
            });
            return;
        }
        if (lower.startsWith("command:") || lower.startsWith("player:")) {
            String cmd = action.substring(action.indexOf(':') + 1).trim();
            if (!cmd.isEmpty()) {
                player.performCommand(cmd);
            }
            run(plugin, player, actions, index + 1, generation);
            return;
        }
        if (lower.startsWith("op:")) {
            if (TaskDispatcher.isFolia()) {
                plugin.getLogger().warning(plugin.lang().text("logs.folia-op-unsupported"));
                return;
            }
            String cmd = action.substring(action.indexOf(':') + 1).trim();
            if (!cmd.isEmpty()) {
                boolean wasOp = player.isOp();
                try {
                    player.setOp(true);
                    player.performCommand(cmd);
                } finally {
                    player.setOp(wasOp);
                }
            }
            run(plugin, player, actions, index + 1, generation);
            return;
        }
        run(plugin, player, actions, index + 1, generation);
    }

    private static long parseDelayTicks(String v) {
        v = v.trim();
        try {
            if (v.endsWith("t") || v.endsWith("T")) {
                return Math.max(1, (long) Double.parseDouble(v.substring(0, v.length() - 1)));
            }
            if (v.endsWith("s") || v.endsWith("S")) {
                v = v.substring(0, v.length() - 1);
            }
            double seconds = Double.parseDouble(v);
            return Math.max(1, Math.round(seconds * 20.0));
        } catch (NumberFormatException e) {
            return 20L;
        }
    }
}
