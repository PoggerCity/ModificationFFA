package me.poggercity.modificationFFA;

import org.bukkit.Bukkit;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;

import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;

final class PlayerUtilityCommands {

    private final PluginMessages messages;

    PlayerUtilityCommands(PluginMessages messages) {
        this.messages = Objects.requireNonNull(messages, "messages");
    }

    boolean handleCommand(CommandSender sender, String commandName, String[] args) {
        return switch (commandName.toLowerCase(Locale.ROOT)) {
            case "clear" -> handleClear(sender, args);
            case "ping" -> handlePing(sender, args);
            default -> false;
        };
    }

    List<String> tabComplete(CommandSender sender, String commandName, String[] args) {
        if (args.length != 1) {
            return List.of();
        }

        String normalizedCommand = commandName.toLowerCase(Locale.ROOT);
        if (normalizedCommand.equals("clear") && !sender.hasPermission("modificationffa.clear.others")) {
            return List.of();
        }
        if (!normalizedCommand.equals("clear") && !normalizedCommand.equals("ping")) {
            return List.of();
        }

        String partial = args[0].toLowerCase(Locale.ROOT);
        return Bukkit.getOnlinePlayers().stream()
                .map(Player::getName)
                .filter(name -> name.toLowerCase(Locale.ROOT).startsWith(partial))
                .sorted(String.CASE_INSENSITIVE_ORDER)
                .toList();
    }

    private boolean handleClear(CommandSender sender, String[] args) {
        if (args.length > 1) {
            messages.sendPrefixed(sender, "utility.clear.usage");
            return true;
        }

        Player target;
        if (args.length == 0) {
            if (!sender.hasPermission("modificationffa.clear")) {
                sender.sendMessage(MessageStyle.permissionDenied("modificationffa.clear"));
                return true;
            }
            if (!(sender instanceof Player player)) {
                messages.sendPrefixed(sender, "utility.clear.console-usage");
                return true;
            }
            target = player;
        } else {
            if (!sender.hasPermission("modificationffa.clear.others")) {
                sender.sendMessage(MessageStyle.permissionDenied("modificationffa.clear.others"));
                return true;
            }
            target = Bukkit.getPlayerExact(args[0]);
            if (target == null) {
                messages.sendPrefixed(sender, "utility.player-offline", Map.of("player", args[0]));
                return true;
            }
        }

        target.getInventory().clear();
        target.updateInventory();
        if (sender.equals(target)) {
            messages.sendPrefixed(sender, "utility.clear.self");
        } else {
            messages.sendPrefixed(sender, "utility.clear.other", Map.of("player", target.getName()));
        }
        return true;
    }

    private boolean handlePing(CommandSender sender, String[] args) {
        if (args.length > 1) {
            messages.sendPrefixed(sender, "utility.ping.usage");
            return true;
        }

        Player target;
        if (args.length == 0) {
            if (!(sender instanceof Player player)) {
                messages.sendPrefixed(sender, "utility.ping.console-usage");
                return true;
            }
            target = player;
        } else {
            target = Bukkit.getPlayerExact(args[0]);
            if (target == null) {
                messages.sendPrefixed(sender, "utility.player-offline", Map.of("player", args[0]));
                return true;
            }
        }

        messages.sendPrefixed(sender, "utility.ping.result", Map.of(
                "player", target.getName(),
                "ping", target.getPing()
        ));
        return true;
    }
}
