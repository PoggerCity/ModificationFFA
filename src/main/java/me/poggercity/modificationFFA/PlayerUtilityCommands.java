package me.poggercity.modificationFFA;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.Bukkit;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;

import java.util.List;
import java.util.Locale;

final class PlayerUtilityCommands {

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
            sender.sendMessage(MessageStyle.prefixed("Usage: /clear [player]"));
            return true;
        }

        Player target;
        if (args.length == 0) {
            if (!sender.hasPermission("modificationffa.clear")) {
                sender.sendMessage(MessageStyle.permissionDenied("modificationffa.clear"));
                return true;
            }
            if (!(sender instanceof Player player)) {
                sender.sendMessage(MessageStyle.prefixed("Usage: /clear <player>"));
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
                sender.sendMessage(MessageStyle.prefix()
                        .append(Component.text(args[0], NamedTextColor.GREEN))
                        .append(Component.text(" is not online.", NamedTextColor.GRAY)));
                return true;
            }
        }

        target.getInventory().clear();
        target.updateInventory();
        if (sender.equals(target)) {
            sender.sendMessage(MessageStyle.prefixed("You have cleared your inventory."));
        } else {
            sender.sendMessage(MessageStyle.prefix()
                    .append(Component.text("You have cleared ", NamedTextColor.GRAY))
                    .append(Component.text(target.getName(), NamedTextColor.GREEN))
                    .append(Component.text("'s inventory.", NamedTextColor.GRAY)));
        }
        return true;
    }

    private boolean handlePing(CommandSender sender, String[] args) {
        if (args.length > 1) {
            sender.sendMessage(MessageStyle.prefixed("Usage: /ping [player]"));
            return true;
        }

        Player target;
        if (args.length == 0) {
            if (!(sender instanceof Player player)) {
                sender.sendMessage(MessageStyle.prefixed("Usage: /ping <player>"));
                return true;
            }
            target = player;
        } else {
            target = Bukkit.getPlayerExact(args[0]);
            if (target == null) {
                sender.sendMessage(MessageStyle.prefix()
                        .append(Component.text(args[0], NamedTextColor.GREEN))
                        .append(Component.text(" is not online.", NamedTextColor.GRAY)));
                return true;
            }
        }

        sender.sendMessage(MessageStyle.prefix()
                .append(Component.text(target.getName(), NamedTextColor.GREEN))
                .append(Component.text("'s ping is ", NamedTextColor.GRAY))
                .append(Component.text(target.getPing() + "ms", NamedTextColor.GREEN))
                .append(Component.text(".", NamedTextColor.GRAY)));
        return true;
    }
}
