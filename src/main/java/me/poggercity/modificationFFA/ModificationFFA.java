package me.poggercity.modificationFFA;

import net.kyori.adventure.key.Key;
import net.kyori.adventure.sound.Sound;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.scheduler.BukkitTask;
import org.jetbrains.annotations.NotNull;

import java.io.IOException;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

public final class ModificationFFA extends JavaPlugin implements Listener {

    private static final long TICKS_PER_MINUTE = 20L * 60L;

    private final Map<UUID, Long> linkCommandUses = new HashMap<>();

    private KitManager kitManager;
    private BinManager binManager;
    private LinkMessages linkMessages;
    private BukkitTask reminderTask;
    private Sound reminderSound;
    private boolean nextReminderIsDiscord;
    private int commandCooldownSeconds;

    @Override
    public void onEnable() {
        saveDefaultConfig();
        saveResource("messages.json", false);

        try {
            applyConfiguration();
        } catch (IOException | RuntimeException exception) {
            getLogger().severe("Could not load ModificationFFA configuration: " + exception.getMessage());
            getServer().getPluginManager().disablePlugin(this);
            return;
        }

        kitManager = new KitManager(this);
        kitManager.start();
        binManager = new BinManager(this);
        binManager.start();
        getServer().getPluginManager().registerEvents(this, this);
        getLogger().info("ModificationFFA has been enabled.");
    }

    @Override
    public void onDisable() {
        cancelReminderTask();
        if (kitManager != null) {
            kitManager.close();
        }
        if (binManager != null) {
            binManager.close();
        }
        linkCommandUses.clear();
        getLogger().info("ModificationFFA has been disabled.");
    }

    @Override
    public boolean onCommand(
            @NotNull CommandSender sender,
            @NotNull Command command,
            @NotNull String label,
            @NotNull String[] args
    ) {
        return switch (command.getName().toLowerCase(Locale.ROOT)) {
            case "discord" -> sendLinkCommand(sender, linkMessages.discord());
            case "store" -> sendLinkCommand(sender, linkMessages.store());
            case "kit" -> kitManager.handleCommand(sender, args);
            case "bin" -> binManager.open(sender);
            case "modification" -> handleModificationCommand(sender, args);
            default -> false;
        };
    }

    @Override
    public List<String> onTabComplete(
            @NotNull CommandSender sender,
            @NotNull Command command,
            @NotNull String alias,
            @NotNull String[] args
    ) {
        if (command.getName().equalsIgnoreCase("kit")) {
            return kitManager.tabComplete(sender, args);
        }
        if (command.getName().equalsIgnoreCase("modification") && args.length == 1) {
            List<String> available = sender.hasPermission("modificationffa.reload")
                    ? List.of("help", "reload")
                    : List.of("help");
            String current = args[0].toLowerCase(Locale.ROOT);
            return available.stream().filter(subcommand -> subcommand.startsWith(current)).toList();
        }
        return List.of();
    }

    @EventHandler
    public void onPlayerQuit(PlayerQuitEvent event) {
        linkCommandUses.remove(event.getPlayer().getUniqueId());
    }

    private boolean sendLinkCommand(CommandSender sender, List<Component> messages) {
        if (sender instanceof Player player && isOnCooldown(player)) {
            return true;
        }

        sendMessages(sender, messages);
        return true;
    }

    private boolean isOnCooldown(Player player) {
        if (commandCooldownSeconds <= 0 || player.hasPermission("modificationffa.cooldown.bypass")) {
            return false;
        }

        long now = System.nanoTime();
        long cooldownNanos = TimeUnit.SECONDS.toNanos(commandCooldownSeconds);
        Long lastUse = linkCommandUses.get(player.getUniqueId());

        if (lastUse != null) {
            long remaining = cooldownNanos - (now - lastUse);
            if (remaining > 0) {
                long remainingSeconds = Math.max(1L, TimeUnit.NANOSECONDS.toSeconds(remaining - 1L) + 1L);
                String message = linkMessages.cooldown().replace("{seconds}", Long.toString(remainingSeconds));
                player.sendMessage(Component.text(message, NamedTextColor.GRAY));
                return true;
            }
        }

        linkCommandUses.put(player.getUniqueId(), now);
        return false;
    }

    private boolean handleModificationCommand(CommandSender sender, String[] args) {
        if (args.length > 0 && args[0].equalsIgnoreCase("reload")) {
            return reloadPlugin(sender);
        }

        if (args.length > 0 && args[0].equalsIgnoreCase("help")) {
            sender.sendMessage(Component.text("ModificationFFA commands:", NamedTextColor.GRAY));
            sender.sendMessage(Component.text("/discord", NamedTextColor.GREEN)
                    .append(Component.text(" - View the Discord link.", NamedTextColor.GRAY)));
            sender.sendMessage(Component.text("/store", NamedTextColor.GREEN)
                    .append(Component.text(" - View the store link.", NamedTextColor.GRAY)));
            sender.sendMessage(Component.text("/bin", NamedTextColor.GREEN)
                    .append(Component.text(" - Open the Modification Bin.", NamedTextColor.GRAY)));
            if (sender.hasPermission("modificationffa.reload")) {
                sender.sendMessage(Component.text("/modification reload", NamedTextColor.GREEN)
                        .append(Component.text(" - Reload config.yml and messages.json.", NamedTextColor.GRAY)));
            }
            return true;
        }

        sendPluginInformation(sender);
        return true;
    }

    private boolean reloadPlugin(CommandSender sender) {
        if (!sender.hasPermission("modificationffa.reload")) {
            sender.sendMessage(Component.text("You do not have permission to reload ModificationFFA.", NamedTextColor.RED));
            return true;
        }

        reloadConfig();
        try {
            applyConfiguration();
            linkCommandUses.clear();
            sender.sendMessage(Component.text("ModificationFFA configuration reloaded.", NamedTextColor.GREEN));
        } catch (IOException | RuntimeException exception) {
            getLogger().warning("Could not reload ModificationFFA configuration: " + exception.getMessage());
            sender.sendMessage(Component.text("Reload failed. Check the console for details.", NamedTextColor.RED));
        }
        return true;
    }

    private void sendPluginInformation(CommandSender sender) {
        String pluginName = getPluginMeta().getName();
        String version = getPluginMeta().getVersion();
        List<String> authors = getPluginMeta().getAuthors();
        String author = authors.isEmpty() ? "Unknown" : String.join(", ", authors);

        sender.sendMessage(Component.text("The server is running ", NamedTextColor.GRAY)
                .append(Component.text(pluginName, NamedTextColor.GREEN))
                .append(Component.text(" v", NamedTextColor.GRAY))
                .append(Component.text(version, NamedTextColor.GREEN)));
        sender.sendMessage(Component.text("The plugin was created by: ", NamedTextColor.GRAY)
                .append(Component.text(author, NamedTextColor.GREEN)));
        sender.sendMessage(Component.text("Run ", NamedTextColor.GRAY)
                .append(Component.text("/modification help", NamedTextColor.GREEN))
                .append(Component.text(" for sub commands.", NamedTextColor.GRAY)));
    }

    private void applyConfiguration() throws IOException {
        FileConfiguration config = getConfig();
        String discordUrl = requireConfigString(config, "links.discord");
        String storeUrl = requireConfigString(config, "links.store");
        Path messagesPath = getDataFolder().toPath().resolve("messages.json");

        LinkMessages loadedMessages = LinkMessages.load(messagesPath, discordUrl, storeUrl);
        int loadedCooldown = Math.max(0, config.getInt("commands.cooldown-seconds", 3));
        Sound loadedSound = loadReminderSound(config);

        linkMessages = loadedMessages;
        commandCooldownSeconds = loadedCooldown;
        reminderSound = loadedSound;
        scheduleReminders(config);
    }

    private Sound loadReminderSound(FileConfiguration config) {
        if (!config.getBoolean("reminders.sound.enabled", false)) {
            return null;
        }

        String key = config.getString("reminders.sound.key", "minecraft:block.note_block.pling");
        String category = config.getString("reminders.sound.category", "MASTER");
        float volume = (float) Math.max(0.0, config.getDouble("reminders.sound.volume", 1.0));
        float pitch = (float) Math.max(0.01, config.getDouble("reminders.sound.pitch", 1.0));

        try {
            Sound.Source source = Sound.Source.valueOf(category.toUpperCase(Locale.ROOT));
            return Sound.sound(Key.key(key), source, volume, pitch);
        } catch (IllegalArgumentException exception) {
            getLogger().warning("Reminder sound is invalid and has been disabled: " + exception.getMessage());
            return null;
        }
    }

    private void scheduleReminders(FileConfiguration config) {
        cancelReminderTask();
        if (!config.getBoolean("reminders.enabled", true)) {
            return;
        }

        long spacingMinutes = Math.max(1L, Math.min(525_600L,
                config.getLong("reminders.spacing-minutes", 5L)));
        long spacingTicks = spacingMinutes * TICKS_PER_MINUTE;
        nextReminderIsDiscord = !"store".equalsIgnoreCase(config.getString("reminders.first", "discord"));

        reminderTask = getServer().getScheduler().runTaskTimer(
                this,
                this::broadcastNextReminder,
                spacingTicks,
                spacingTicks
        );
    }

    private void broadcastNextReminder() {
        List<Component> messages = nextReminderIsDiscord ? linkMessages.discord() : linkMessages.store();
        nextReminderIsDiscord = !nextReminderIsDiscord;

        for (Player player : getServer().getOnlinePlayers()) {
            sendMessages(player, messages);
            if (reminderSound != null) {
                player.playSound(reminderSound);
            }
        }
    }

    private void cancelReminderTask() {
        if (reminderTask != null) {
            reminderTask.cancel();
            reminderTask = null;
        }
    }

    private void sendMessages(CommandSender sender, List<Component> messages) {
        for (Component message : messages) {
            sender.sendMessage(message);
        }
    }

    private String requireConfigString(FileConfiguration config, String path) {
        String value = config.getString(path);
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException("Missing required config value: " + path);
        }
        return value.trim();
    }
}
