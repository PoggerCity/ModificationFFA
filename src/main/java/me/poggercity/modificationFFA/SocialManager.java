package me.poggercity.modificationFFA;

import net.kyori.adventure.text.Component;
import org.bukkit.Bukkit;
import org.bukkit.Sound;
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

    private final ModificationFFA plugin;
    private final SettingsManager settingsManager;
    private final PluginMessages messages;
    private final Map<UUID, UUID> lastSender = new HashMap<>();
    private final Map<UUID, UUID> conversationPartner = new HashMap<>();

    SocialManager(ModificationFFA plugin, SettingsManager settingsManager, PluginMessages messages) {
        this.plugin = plugin;
        this.settingsManager = settingsManager;
        this.messages = messages;
    }

    boolean handleMessage(CommandSender sender, String[] args) {
        Player player = requirePlayer(sender);
        if (player == null) {
            return true;
        }
        if (args.length < 2) {
            messages.sendPrefixed(player, "social.msg-usage");
            return true;
        }

        Player target = findOnlinePlayer(args[0]);
        if (target == null) {
            messages.sendPrefixed(player, "core.player-offline", Map.of("player", args[0]));
            return true;
        }
        if (target.getUniqueId().equals(player.getUniqueId())) {
            messages.sendPrefixed(player, "social.self-message");
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
            messages.sendPrefixed(player, "social.reply-usage");
            return true;
        }

        UUID targetId = lastSender.get(player.getUniqueId());
        if (targetId == null) {
            messages.sendPrefixed(player, "social.nobody-to-reply");
            return true;
        }
        Player target = Bukkit.getPlayer(targetId);
        if (target == null) {
            messages.sendPrefixed(player, "social.conversation-offline");
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
            messages.sendPrefixed(player, "social.continue-usage");
            return true;
        }

        UUID targetId = conversationPartner.get(player.getUniqueId());
        if (targetId == null) {
            messages.sendPrefixed(player, "social.no-conversation");
            return true;
        }
        Player target = Bukkit.getPlayer(targetId);
        if (target == null) {
            messages.sendPrefixed(player, "social.conversation-offline");
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
        event.joinMessage(null);
        Component message = messages.component("social.join", Map.of("player", event.getPlayer().getName()));
        Bukkit.getScheduler().runTask(plugin, () -> Bukkit.getOnlinePlayers().stream()
                .filter(settingsManager::connectionMessagesEnabled)
                .forEach(viewer -> viewer.sendMessage(message)));
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent event) {
        Player player = event.getPlayer();
        event.quitMessage(null);
        Component message = messages.component("social.quit", Map.of("player", player.getName()));
        Bukkit.getOnlinePlayers().stream()
                .filter(viewer -> !viewer.equals(player))
                .filter(settingsManager::connectionMessagesEnabled)
                .forEach(viewer -> viewer.sendMessage(message));
        lastSender.remove(player.getUniqueId());
        conversationPartner.remove(player.getUniqueId());
    }

    private boolean sendPrivateMessage(Player sender, Player target, String message) {
        UUID senderId = sender.getUniqueId();
        UUID targetId = target.getUniqueId();

        sender.sendMessage(privateMessage("You", target.getName(), message));
        target.sendMessage(privateMessage(sender.getName(), "You", message));
        if (settingsManager.messageSoundEnabled(target)) {
            target.playSound(target.getLocation(), Sound.ENTITY_EXPERIENCE_ORB_PICKUP, 0.5F, 1.4F);
        }

        lastSender.put(targetId, senderId);
        conversationPartner.put(senderId, targetId);
        conversationPartner.put(targetId, senderId);
        return true;
    }

    private Component privateMessage(String from, String to, String message) {
        return messages.componentWithComponents("social.private-message", Map.of(
                "sender", Component.text(from, MessageStyle.accent()),
                "receiver", Component.text(to, MessageStyle.accent()),
                "message", Component.text(message, MessageStyle.text())
        ));
    }

    private Player requirePlayer(CommandSender sender) {
        if (sender instanceof Player player) {
            return player;
        }
        messages.send(sender, "core.players-only");
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
