package net.msstore.discordbridge;

import org.bukkit.ChatColor;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;

import java.util.ArrayList;
import java.util.List;

/**
 * Handles: /db console whitelist <discord_user_id>
 *          /db console unwhitelist <discord_user_id>
 */
public class DcBridgeCommand implements CommandExecutor, TabCompleter {

    private final ConfigManager config;

    public DcBridgeCommand(ConfigManager config) {
        this.config = config;
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (args.length != 3
                || !args[0].equalsIgnoreCase("console")
                || !(args[1].equalsIgnoreCase("whitelist") || args[1].equalsIgnoreCase("unwhitelist"))) {
            sender.sendMessage(ChatColor.YELLOW + "Usage: /" + label + " console whitelist|unwhitelist <discord_user_id>");
            return true;
        }

        String sub = args[1].toLowerCase();
        String discordId = args[2];

        if (sub.equals("whitelist")) {
            if (!sender.hasPermission("dcbridge.whitelist")) {
                sender.sendMessage(ChatColor.RED + "You do not have permission to do that.");
                return true;
            }
            try {
                boolean added = config.whitelist(discordId);
                if (added) {
                    sender.sendMessage(ChatColor.GREEN + "Whitelisted Discord user ID " + discordId + " for console command execution.");
                } else {
                    sender.sendMessage(ChatColor.YELLOW + "Discord user ID " + discordId + " is already whitelisted.");
                }
            } catch (IllegalArgumentException e) {
                sender.sendMessage(ChatColor.RED + e.getMessage());
            }
            return true;
        }

        // unwhitelist
        if (!sender.hasPermission("dcbridge.unwhitelist")) {
            sender.sendMessage(ChatColor.RED + "You do not have permission to do that.");
            return true;
        }
        boolean removed = config.unwhitelist(discordId);
        if (removed) {
            sender.sendMessage(ChatColor.GREEN + "Removed Discord user ID " + discordId + " from the whitelist.");
        } else {
            sender.sendMessage(ChatColor.YELLOW + "Discord user ID " + discordId + " was not whitelisted.");
        }
        return true;
    }

    @Override
    public List<String> onTabComplete(CommandSender sender, Command command, String alias, String[] args) {
        List<String> options = new ArrayList<>();
        if (args.length == 1) {
            options.add("console");
            options.removeIf(s -> !s.startsWith(args[0].toLowerCase()));
        } else if (args.length == 2 && args[0].equalsIgnoreCase("console")) {
            if (sender.hasPermission("dcbridge.whitelist")) options.add("whitelist");
            if (sender.hasPermission("dcbridge.unwhitelist")) options.add("unwhitelist");
            options.removeIf(s -> !s.startsWith(args[1].toLowerCase()));
        }
        return options;
    }
}
