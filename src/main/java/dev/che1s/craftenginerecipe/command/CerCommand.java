package dev.che1s.craftenginerecipe.command;

import dev.che1s.craftenginerecipe.CraftEngineRecipePlugin;
import dev.che1s.craftenginerecipe.gui.GuiSession;
import dev.che1s.craftenginerecipe.i18n.LanguageManager;
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
                sender.sendMessage(this.lang(sender).message("messages.no-permission"));
                return true;
            }
            sender.sendMessage(this.lang(sender).message("messages.reload-start"));
            this.plugin.reloadSelf(ok -> {
                Runnable reply = () -> sender.sendMessage(this.lang(sender).message(
                        ok ? "messages.reload-success" : "messages.reload-failed"));
                if (sender instanceof Player player) this.plugin.tasks().player(player, reply);
                else reply.run();
            });
            return true;
        }
        if (args.length > 0 && args[0].equalsIgnoreCase("menu")) {
            if (!sender.hasPermission("craftenginerecipe.admin")) {
                sender.sendMessage(this.lang(sender).message("messages.no-permission"));
                return true;
            }
            if (args.length >= 2 && (args[1].equalsIgnoreCase("use") || args[1].equalsIgnoreCase("default"))) {
                if (args.length < 3) {
                    sender.sendMessage(this.lang(sender).message("messages.menu-default-usage"));
                    return true;
                }
                if (this.plugin.menuPacks().setDefault(args[2])) {
                    sender.sendMessage(this.lang(sender).message(
                            "messages.menu-default-set", "menu", args[2]));
                } else {
                    sender.sendMessage(this.lang(sender).message(
                            "messages.menu-unknown", "menu", args[2]));
                }
                return true;
            }
            if (args.length >= 2 && args[1].equalsIgnoreCase("enable")) {
                if (args.length < 3) {
                    sender.sendMessage(this.lang(sender).message("messages.menu-enable-usage"));
                    return true;
                }
                boolean changed = args[2].equalsIgnoreCase("all")
                        ? this.plugin.menuPacks().enableAll()
                        : this.plugin.menuPacks().enable(args[2]);
                sender.sendMessage(this.lang(sender).message(changed
                        ? "messages.menu-enabled-updated" : "messages.menu-enable-failed"));
                return true;
            }
            if (args.length >= 2 && args[1].equalsIgnoreCase("disable")) {
                if (args.length < 3) {
                    sender.sendMessage(this.lang(sender).message("messages.menu-disable-usage"));
                    return true;
                }
                sender.sendMessage(this.plugin.menuPacks().disable(args[2])
                        ? this.lang(sender).message("messages.menu-disabled", "menu", args[2])
                        : this.lang(sender).message("messages.menu-disable-failed"));
                return true;
            }
            sender.sendMessage(this.lang(sender).message(
                    "messages.menu-status-default", "menu", this.plugin.menuPacks().defaultPack()));
            sender.sendMessage(this.lang(sender).message(
                    "messages.menu-status-enabled", "menus", String.join(", ", this.plugin.menuPacks().enabled())));
            sender.sendMessage(this.lang(sender).message(
                    "messages.menu-status-available", "menus", String.join(", ", this.plugin.menuPacks().available())));
            return true;
        }
        if (args.length > 0 && args[0].equalsIgnoreCase("export")) {
            if (!sender.hasPermission("craftenginerecipe.admin")) {
                sender.sendMessage(this.lang(sender).message("messages.no-permission"));
                return true;
            }
            if (args.length < 2) {
                sender.sendMessage(this.lang(sender).message("messages.recipe-export-usage"));
                return true;
            }
            List<String> requested;
            if (args[1].equalsIgnoreCase("all")) {
                requested = this.plugin.extensions().ids();
            } else if (this.plugin.extensions().ids().contains(args[1].toLowerCase(Locale.ROOT))) {
                requested = List.of(args[1].toLowerCase(Locale.ROOT));
            } else {
                sender.sendMessage(this.lang(sender).message("messages.recipe-export-usage"));
                return true;
            }
            sender.sendMessage(this.lang(sender).message("messages.recipe-export-started"));
            this.plugin.externalRecipes().reexport(requested, report -> {
                String failures = report.failures().isEmpty() ? "-" : report.failures().toString();
                sender.sendMessage(this.lang(sender).message(
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
                sender.sendMessage(this.lang(sender).message("messages.no-permission"));
                return true;
            }
            if (sender instanceof Player player) {
                this.plugin.browser(player).openNamespaceManager(player);
            } else {
                this.plugin.integrationPrompts().openPrompt();
            }
            return true;
        }
        if (!(sender instanceof Player)) {
            sender.sendMessage(this.lang(sender).message("messages.player-only"));
            return true;
        }
        Player player = (Player) sender;
        if (args.length >= 2 && args[0].equalsIgnoreCase("open")) {
            if (!this.plugin.data().isReady()) {
                player.sendMessage(this.lang(sender).message("messages.data-not-ready",
                        "reason", this.lang(sender).text("messages.not-ready-reason")));
                return true;
            }
            String id = normalizeMenuPack(args[1]);
            var selected = this.plugin.browser(id, this.plugin.lang(player).locale());
            if (!this.plugin.menuPacks().enabled().contains(id) || selected == null) {
                player.sendMessage(this.lang(sender).message(
                        "messages.menu-unavailable", "menu", args[1]));
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
            player.sendMessage(this.lang(sender).message("messages.data-not-ready",
                    "reason", this.lang(sender).text("messages.not-ready-reason")));
            return true;
        }
        if (args.length > 0 && args[0].equalsIgnoreCase("manage")) {
            if (!player.hasPermission("craftenginerecipe.admin")) {
                player.sendMessage(this.lang(sender).message("messages.no-permission"));
                return true;
            }
            this.plugin.browser(player).openNamespaceManager(player);
            return true;
        }
        if (args.length > 0 && args[0].equalsIgnoreCase("craft")) {
            this.plugin.browser(player).openWorkstationBrowser(player);
            return true;
        }
        if (args.length > 0 && args[0].equalsIgnoreCase("items")) {
            this.plugin.browser(player).openNamespaceBrowser(player);
            return true;
        }
        if (args.length > 0 && args[0].equalsIgnoreCase("unapplied")) {
            if (!player.hasPermission("craftenginerecipe.admin")) {
                player.sendMessage(this.lang(sender).message("messages.no-permission"));
                return true;
            }
            this.plugin.browser(player).openUnapplied(player);
            return true;
        }
        if (args.length > 0 && args[0].equalsIgnoreCase("search")) {
            this.plugin.dialogs().search().open(player);
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
            if ("craft".startsWith(prefix)) {
                out.add("craft");
            }
            if ("items".startsWith(prefix)) {
                out.add("items");
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
            return java.util.stream.Stream.concat(this.plugin.extensions().ids().stream(),
                            java.util.stream.Stream.of("all"))
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

    private LanguageManager.Localized lang(CommandSender sender) {
        if (sender instanceof Player player) return this.plugin.lang(player);
        return this.plugin.lang().view(this.plugin.lang().locale());
    }
}
