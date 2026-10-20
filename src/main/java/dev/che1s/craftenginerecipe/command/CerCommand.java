package dev.che1s.craftenginerecipe.command;

import dev.che1s.craftenginerecipe.CraftEngineRecipePlugin;
import org.bukkit.Bukkit;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.PluginCommand;
import org.bukkit.command.TabCompleter;
import org.bukkit.entity.Player;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

public final class CerCommand implements CommandExecutor, TabCompleter {

    private final CraftEngineRecipePlugin plugin;

    public CerCommand(CraftEngineRecipePlugin plugin) {
        this.plugin = plugin;
    }

    public void register() {
        PluginCommand cmd = this.plugin.getCommand("cer");
        if (cmd != null) {
            cmd.setDescription(this.plugin.lang().text("command.description"));
            cmd.setExecutor(this);
            cmd.setTabCompleter(this);
        }
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (sender instanceof Player player && !Bukkit.isOwnedByCurrentRegion(player)) {
            String[] copy = args.clone();
            this.plugin.tasks().player(player, () -> this.onCommand(player, command, label, copy));
            return true;
        }
        if (args.length > 0 && args[0].equalsIgnoreCase("reload")) {
            if (!sender.hasPermission("craftenginerecipe.admin")) {
                sender.sendMessage(this.plugin.lang().message("messages.no-permission"));
                return true;
            }
            sender.sendMessage(this.plugin.lang().message("messages.reload-start"));
            this.plugin.reloadSelf(ok -> {
                Runnable reply = () -> sender.sendMessage(this.plugin.lang().message(
                        ok ? "messages.reload-success" : "messages.reload-failed"));
                if (sender instanceof Player player) this.plugin.tasks().player(player, reply);
                else reply.run();
            });
            return true;
        }
        if (!(sender instanceof Player)) {
            sender.sendMessage(this.plugin.lang().message("messages.player-only"));
            return true;
        }
        Player player = (Player) sender;
        // Direct queries report their own failure sound, including while data is unavailable.
        if (args.length > 0 && args[0].equalsIgnoreCase("hand")) {
            this.plugin.browser().openHeldRecipe(player);
            return true;
        }
        if (args.length > 0 && args[0].equalsIgnoreCase("target")) {
            this.plugin.browser().openTargetBlockRecipe(player);
            return true;
        }
        if (!this.plugin.data().isReady()) {
            player.sendMessage(this.plugin.lang().message("messages.data-not-ready", "reason", this.plugin.data().notReadyReason()));
            return true;
        }
        if (args.length > 0 && args[0].equalsIgnoreCase("unapplied")) {
            if (!player.hasPermission("craftenginerecipe.admin")) {
                player.sendMessage(this.plugin.lang().message("messages.no-permission"));
                return true;
            }
            this.plugin.browser().openUnapplied(player);
            return true;
        }
        if (args.length > 0 && args[0].equalsIgnoreCase("search")) {
            this.plugin.searchDialog().open(player);
            return true;
        }
        this.plugin.browser().openApplied(player);
        return true;
    }

    @Override
    public List<String> onTabComplete(CommandSender sender, Command command, String alias, String[] args) {
        if (args.length == 1) {
            String prefix = args[0].toLowerCase(Locale.ROOT);
            List<String> out = new ArrayList<>();
            if ("search".startsWith(prefix)) {
                out.add("search");
            }
            if ("hand".startsWith(prefix)) {
                out.add("hand");
            }
            if ("target".startsWith(prefix)) {
                out.add("target");
            }
            if (sender.hasPermission("craftenginerecipe.admin") && "reload".startsWith(prefix)) {
                out.add("reload");
            }
            if (sender.hasPermission("craftenginerecipe.admin") && "unapplied".startsWith(prefix)) {
                out.add("unapplied");
            }
            return out;
        }
        return List.of();
    }
}
