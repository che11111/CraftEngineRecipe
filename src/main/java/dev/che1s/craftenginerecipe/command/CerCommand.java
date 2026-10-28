package dev.che1s.craftenginerecipe.command;

import dev.che1s.craftenginerecipe.CraftEngineRecipePlugin;
import dev.che1s.craftenginerecipe.gui.GuiSession;
import org.bukkit.Bukkit;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.PluginCommand;
import org.bukkit.command.TabCompleter;
import org.bukkit.entity.Player;

import java.util.ArrayList;
import java.util.Arrays;
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
        if (args.length > 0 && args[0].equalsIgnoreCase("debug")) {
            this.plugin.debug().execute(sender, Arrays.copyOfRange(args, 1, args.length));
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
        if (args.length > 0 && args[0].equalsIgnoreCase("menu")) {
            if (!sender.hasPermission("craftenginerecipe.admin")) {
                sender.sendMessage(this.plugin.lang().message("messages.no-permission"));
                return true;
            }
            if (args.length >= 2 && (args[1].equalsIgnoreCase("use") || args[1].equalsIgnoreCase("default"))) {
                if (args.length < 3) {
                    sender.sendMessage("用法: /cer menu default <cer_menu_*>");
                    return true;
                }
                if (this.plugin.menuPacks().setDefault(args[2])) {
                    sender.sendMessage("已设置默认主菜单样式: " + args[2]);
                } else {
                    sender.sendMessage("未知或不完整的菜单样式: " + args[2]);
                }
                return true;
            }
            if (args.length >= 2 && args[1].equalsIgnoreCase("enable")) {
                if (args.length < 3) {
                    sender.sendMessage("用法: /cer menu enable <cer_menu_*|all>");
                    return true;
                }
                boolean changed = args[2].equalsIgnoreCase("all")
                        ? this.plugin.menuPacks().enableAll()
                        : this.plugin.menuPacks().enable(args[2]);
                sender.sendMessage(changed ? "已更新启用的菜单样式。" : "无法启用该菜单样式。");
                return true;
            }
            if (args.length >= 2 && args[1].equalsIgnoreCase("disable")) {
                if (args.length < 3) {
                    sender.sendMessage("用法: /cer menu disable <cer_menu_*>");
                    return true;
                }
                sender.sendMessage(this.plugin.menuPacks().disable(args[2])
                        ? "已停用菜单样式: " + args[2]
                        : "无法停用该样式；默认样式必须保持启用。");
                return true;
            }
            sender.sendMessage("默认样式: " + this.plugin.menuPacks().defaultPack());
            sender.sendMessage("已启用: " + String.join(", ", this.plugin.menuPacks().enabled()));
            sender.sendMessage("全部可用: " + String.join(", ", this.plugin.menuPacks().available()));
            return true;
        }
        if (args.length > 0 && args[0].equalsIgnoreCase("export")) {
            if (!sender.hasPermission("craftenginerecipe.admin")) {
                sender.sendMessage(this.plugin.lang().message("messages.no-permission"));
                return true;
            }
            if (args.length < 2) {
                sender.sendMessage(this.plugin.lang().message("messages.recipe-export-usage"));
                return true;
            }
            List<String> requested;
            if (args[1].equalsIgnoreCase("all")) {
                requested = List.of("craftengine", "farmersdelight", "kaleidoscope", "customcrops");
            } else if (args[1].equalsIgnoreCase("craftengine")
                    || args[1].equalsIgnoreCase("farmersdelight")
                    || args[1].equalsIgnoreCase("kaleidoscope")
                    || args[1].equalsIgnoreCase("customcrops")) {
                requested = List.of(args[1].toLowerCase(Locale.ROOT));
            } else {
                sender.sendMessage(this.plugin.lang().message("messages.recipe-export-usage"));
                return true;
            }
            sender.sendMessage(this.plugin.lang().message("messages.recipe-export-started"));
            this.plugin.externalRecipes().reexport(requested, report -> {
                String failures = report.failures().isEmpty() ? "-" : report.failures().toString();
                sender.sendMessage(this.plugin.lang().message(
                        report.sources() == 0 ? "messages.recipe-export-failed" : "messages.recipe-export-complete",
                        "sources", String.valueOf(report.sources()),
                        "files", String.valueOf(report.files()),
                        "recipes", String.valueOf(report.recipes()),
                        "failures", failures));
            });
            return true;
        }
        if (args.length > 0 && args[0].equalsIgnoreCase("integrations")) {
            if (!sender.hasPermission("craftenginerecipe.admin")) {
                sender.sendMessage(this.plugin.lang().message("messages.no-permission"));
                return true;
            }
            this.plugin.integrationPrompts().openPrompt();
            return true;
        }
        if (!(sender instanceof Player)) {
            sender.sendMessage(this.plugin.lang().message("messages.player-only"));
            return true;
        }
        Player player = (Player) sender;
        if (args.length >= 2 && args[0].equalsIgnoreCase("open")) {
            if (!this.plugin.data().isReady()) {
                player.sendMessage(this.plugin.lang().message("messages.data-not-ready",
                        "reason", this.plugin.data().notReadyReason()));
                return true;
            }
            String id = normalizeMenuPack(args[1]);
            var selected = this.plugin.browser(id);
            if (!this.plugin.menuPacks().enabled().contains(id) || selected == null) {
                player.sendMessage("该菜单样式未启用或不可用: " + args[1]);
                return true;
            }
            for (var browser : this.plugin.browsers().values()) {
                browser.forgetPlayer(player);
                browser.recipes().cancelRotation(player);
            }
            GuiSession.clear(player);
            player.closeInventory();
            this.plugin.tasks().playerLater(player, () -> selected.openDefault(player), 1);
            return true;
        }
        // 直接查询会自行播放失败音效，包括数据尚未就绪的情况。
        if (args.length > 0 && args[0].equalsIgnoreCase("hand")) {
            this.plugin.browser(player).openHeldRecipe(player);
            return true;
        }
        if (args.length > 0 && args[0].equalsIgnoreCase("target")) {
            this.plugin.browser(player).openTargetBlockRecipe(player);
            return true;
        }
        if (!this.plugin.data().isReady()) {
            player.sendMessage(this.plugin.lang().message("messages.data-not-ready", "reason", this.plugin.data().notReadyReason()));
            return true;
        }
        if (args.length > 0 && args[0].equalsIgnoreCase("manage")) {
            if (!player.hasPermission("craftenginerecipe.admin")) {
                player.sendMessage(this.plugin.lang().message("messages.no-permission"));
                return true;
            }
            this.plugin.browser(player).openNamespaceManager(player);
            return true;
        }
        if (args.length > 0 && args[0].equalsIgnoreCase("unapplied")) {
            if (!player.hasPermission("craftenginerecipe.admin")) {
                player.sendMessage(this.plugin.lang().message("messages.no-permission"));
                return true;
            }
            this.plugin.browser(player).openUnapplied(player);
            return true;
        }
        if (args.length > 0 && args[0].equalsIgnoreCase("search")) {
            this.plugin.searchDialog().open(player);
            return true;
        }
        this.plugin.browser(player).openApplied(player);
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
            if ("open".startsWith(prefix)) {
                out.add("open");
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
            if (sender.hasPermission("craftenginerecipe.admin") && "export".startsWith(prefix)) {
                out.add("export");
            }
            if (sender.hasPermission("craftenginerecipe.admin") && "integrations".startsWith(prefix)) {
                out.add("integrations");
            }
            if (sender.hasPermission("craftenginerecipe.admin") && "unapplied".startsWith(prefix)) {
                out.add("unapplied");
            }
            if (sender.hasPermission("craftenginerecipe.admin") && "manage".startsWith(prefix)) {
                out.add("manage");
            }
            if (sender.hasPermission("craftenginerecipe.admin") && "debug".startsWith(prefix)) out.add("debug");
            if (sender.hasPermission("craftenginerecipe.admin") && "menu".startsWith(prefix)) out.add("menu");
            return out;
        }
        if (sender.hasPermission("craftenginerecipe.admin") && args.length >= 2
                && args[0].equalsIgnoreCase("menu")) {
            List<String> options = args.length == 2
                    ? List.of("default", "enable", "disable")
                    : args[1].equalsIgnoreCase("enable") ? java.util.stream.Stream.concat(
                            java.util.stream.Stream.of("all"), this.plugin.menuPacks().available().stream()).toList()
                    : this.plugin.menuPacks().available();
            String prefix = args[args.length - 1].toLowerCase(Locale.ROOT);
            return options.stream().filter(option -> option.startsWith(prefix)).toList();
        }
        if (sender.hasPermission("craftenginerecipe.admin") && args.length >= 2
                && args[0].equalsIgnoreCase("export")) {
            String prefix = args[args.length - 1].toLowerCase(Locale.ROOT);
            return List.of("craftengine", "farmersdelight", "kaleidoscope", "customcrops", "all").stream()
                    .filter(option -> option.startsWith(prefix)).toList();
        }
        if (args.length >= 2 && args[0].equalsIgnoreCase("open")) {
            String prefix = args[args.length - 1].toLowerCase(Locale.ROOT);
            return this.plugin.menuPacks().enabled().stream()
                    .filter(option -> option.startsWith(prefix)).toList();
        }
        if (sender.hasPermission("craftenginerecipe.admin") && args.length >= 2 && args[0].equalsIgnoreCase("debug")) {
            List<String> options = args.length == 2 ? List.of("recipes", "performance")
                    : args.length == 3 && args[1].equalsIgnoreCase("performance") ? List.of("reset") : List.of();
            String prefix = args[args.length - 1].toLowerCase(Locale.ROOT);
            return options.stream().filter(option -> option.startsWith(prefix)).toList();
        }
        return List.of();
    }

    private static String normalizeMenuPack(String requested) {
        String id = requested == null ? "" : requested.trim().toLowerCase(Locale.ROOT);
        return id.startsWith("cer_menu_") ? id : "cer_menu_" + id;
    }
}
