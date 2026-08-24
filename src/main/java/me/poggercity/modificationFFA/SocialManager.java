package me.poggercity.modificationFFA;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.Bukkit;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerQuitEvent;

import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;

final class SocialManager implements Listener {

    private final Map<UUID, UUID> lastSender = new HashMap<>();
    private final Map<UUID, UUID> conversationPartner = new HashMap<>();

    boolean handleMessage(CommandSender sender, String[] args) {
        Player player = requirePlayer(sender);
        if (player == null) {
            return true;
        }
        if (args.length < 2) {
            player.sendMessage(MessageStyle.prefixed("Usage: /msg <player> <message>"));
            return true;
        }

        Player target = findOnlinePlayer(args[0]);
        if (target == null) {
            player.sendMessage(MessageStyle.prefixed("Player " + args[0] + " is not online."));
            return true;
        }
        if (target.getUniqueId().equals(player.getUniqueId())) {
            player.sendMessage(MessageStyle.prefixed("You cannot message yourself."));
            return true;
        }

        return sendPrivateMessage(player, target, joinMessage(args, 1));
    }

    boolean handleReply(CommandSender sender, String[] args) {
        Player player = requirePlayer(sender);
        if (player == null) {
            return true;
        }
        if (args.length == 0) {
            player.sendMessage(MessageStyle.prefixed("Usage: /reply <message>"));
            return true;
        }

        UUID targetId = lastSender.get(player.getUniqueId());
        if (targetId == null) {
            player.sendMessage(MessageStyle.prefixed("You have nobody to reply to."));
            return true;
        }
        Player target = Bukkit.getPlayer(targetId);
        if (target == null) {
            player.sendMessage(MessageStyle.prefixed("That player is no longer online."));
            return true;
        }
        return sendPrivateMessage(player, target, joinMessage(args, 0));
    }

    boolean handleContinue(CommandSender sender, String[] args) {
        Player player = requirePlayer(sender);
        if (player == null) {
            return true;
        }
        if (args.length == 0) {
            player.sendMessage(MessageStyle.prefixed("Usage: /continue <message>"));
            return true;
        }

        UUID targetId = conversationPartner.get(player.getUniqueId());
        if (targetId == null) {
            player.sendMessage(MessageStyle.prefixed("You have no conversation to continue."));
            return true;
        }
        Player target = Bukkit.getPlayer(targetId);
        if (target == null) {
            player.sendMessage(MessageStyle.prefixed("That player is no longer online."));
            return true;
        }
        return sendPrivateMessage(player, target, joinMessage(args, 0));
    }

    List<String> tabCompleteMessage(String[] args) {
        if (args.length != 1) {
            return List.of();
        }
        String current = args[0].toLowerCase(Locale.ROOT);
        return Bukkit.getOnlinePlayers().stream()
                .map(Player::getName)
                .filter(name -> name.toLowerCase(Locale.ROOT).startsWith(current))
                .sorted(String.CASE_INSENSITIVE_ORDER)
                .toList();
    }

    void close() {
        lastSender.clear();
        conversationPartner.clear();
    }

    @EventHandler
    public void onJoin(PlayerJoinEvent event) {
        event.joinMessage(presenceMessage(event.getPlayer(), "+", NamedTextColor.GREEN));
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent event) {
        Player player = event.getPlayer();
        event.quitMessage(presenceMessage(player, "-", NamedTextColor.RED));
        lastSender.remove(player.getUniqueId());
        conversationPartner.remove(player.getUniqueId());
    }

    private boolean sendPrivateMessage(Player sender, Player target, String message) {
        UUID senderId = sender.getUniqueId();
        UUID targetId = target.getUniqueId();

        sender.sendMessage(privateMessage("You", target.getName(), message));
        target.sendMessage(privateMessage(sender.getName(), "You", message));

        lastSender.put(targetId, senderId);
        conversationPartner.put(senderId, targetId);
        conversationPartner.put(targetId, senderId);
        return true;
    }

    private Component privateMessage(String from, String to, String message) {
        return Component.text(from, NamedTextColor.GREEN)
                .append(Component.text(" » ", NamedTextColor.DARK_GRAY))
                .append(Component.text(to, NamedTextColor.GREEN))
                .append(Component.text(" | ", NamedTextColor.DARK_GRAY))
                .append(Component.text(message, NamedTextColor.GRAY));
    }

    private Component presenceMessage(Player player, String marker, NamedTextColor markerColor) {
        return Component.text("[", NamedTextColor.DARK_GRAY)
                .append(Component.text(marker, markerColor))
                .append(Component.text("] ", NamedTextColor.DARK_GRAY))
                .append(Component.text(player.getName(), NamedTextColor.GRAY));
    }

    private Player requirePlayer(CommandSender sender) {
        if (sender instanceof Player player) {
            return player;
        }
        sender.sendMessage(Component.text("Only players can use this command.", NamedTextColor.RED));
        return null;
    }

    private Player findOnlinePlayer(String name) {
        Player exact = Bukkit.getPlayerExact(name);
        return exact != null ? exact : Bukkit.getPlayer(name);
    }

    private String joinMessage(String[] args, int start) {
        return String.join(" ", Arrays.copyOfRange(args, start, args.length));
    }
}
