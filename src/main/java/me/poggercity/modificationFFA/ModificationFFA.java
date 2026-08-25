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
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

public final class ModificationFFA extends JavaPlugin implements Listener {

    private static final long TICKS_PER_MINUTE = 20L * 60L;
    private static final List<String> MODIFICATION_SUBCOMMANDS = List.of(
            "bin", "clear", "executioner", "find", "help", "merge", "ping", "settings", "stats");

    private final Map<UUID, Long> linkCommandUses = new HashMap<>();

    private KitManager kitManager;
    private SettingsManager settingsManager;
    private MergeManager mergeManager;
    private BinManager binManager;
    private PlayerUtilityCommands playerUtilityCommands;
    private StatsManager statsManager;
    private BiomeManager biomeManager;
    private TokenManager tokenManager;
    private SpawnManager spawnManager;
    private CombatManager combatManager;
    private SocialManager socialManager;
    private ArenaManager arenaManager;
    private ProtectArenaManager protectArenaManager;
    private SwordManager swordManager;
    private PetManager petManager;
    private LinkMessages linkMessages;
    private BukkitTask reminderTask;
    private Sound reminderSound;
    private boolean nextReminderIsDiscord;
    private int commandCooldownSeconds;

    @Override
    public void onEnable() {
        saveDefaultConfig();
        if (!Files.exists(getDataFolder().toPath().resolve("messages.json"))) {
            saveResource("messages.json", false);
        }

        try {
            applyConfiguration();
        } catch (IOException | RuntimeException exception) {
            getLogger().severe("Could not load ModificationFFA configuration: " + exception.getMessage());
            getServer().getPluginManager().disablePlugin(this);
            return;
        }

        settingsManager = new SettingsManager(this);
        settingsManager.start();
        kitManager = new KitManager(this, settingsManager);
        kitManager.start();
        binManager = new BinManager(this);
        binManager.start();
        playerUtilityCommands = new PlayerUtilityCommands();
        statsManager = new StatsManager(this, settingsManager);
        statsManager.start();
        biomeManager = new BiomeManager(this);
        biomeManager.start();
        tokenManager = new TokenManager(this);
        tokenManager.start();
        spawnManager = new SpawnManager(this);
        spawnManager.start();
        combatManager = new CombatManager(this, statsManager, spawnManager, tokenManager);
        combatManager.start();
        socialManager = new SocialManager(this, settingsManager);
        arenaManager = new ArenaManager(this);
        if (!arenaManager.start()) {
            getLogger().severe("ModificationFFA stopped because arenas.json could not be loaded safely.");
            getServer().getPluginManager().disablePlugin(this);
            return;
        }
        protectArenaManager = new ProtectArenaManager(this, arenaManager);
        if (!protectArenaManager.start()) {
            getLogger().severe("ModificationFFA stopped because protected-arenas.db could not be loaded safely.");
            getServer().getPluginManager().disablePlugin(this);
            return;
        }
        swordManager = new SwordManager(this, settingsManager, tokenManager, protectArenaManager);
        swordManager.start();
        mergeManager = new MergeManager(this, swordManager);
        mergeManager.start();
        petManager = new PetManager(this);
        petManager.start();
        getServer().getPluginManager().registerEvents(statsManager, this);
        getServer().getPluginManager().registerEvents(biomeManager, this);
        getServer().getPluginManager().registerEvents(tokenManager, this);
        getServer().getPluginManager().registerEvents(spawnManager, this);
        getServer().getPluginManager().registerEvents(combatManager, this);
        getServer().getPluginManager().registerEvents(socialManager, this);
        getServer().getPluginManager().registerEvents(this, this);
        getLogger().info("ModificationFFA has been enabled.");
    }

    @Override
    public void onDisable() {
        cancelReminderTask();
        if (mergeManager != null) {
            mergeManager.close();
        }
        if (petManager != null) {
            petManager.close();
        }
        if (kitManager != null) {
            kitManager.close();
        }
        if (binManager != null) {
            binManager.close();
        }
        if (combatManager != null) {
            combatManager.close();
        }
        if (statsManager != null) {
            statsManager.close();
        }
        if (biomeManager != null) {
            biomeManager.close();
        }
        if (tokenManager != null) {
            tokenManager.close();
        }
        if (spawnManager != null) {
            spawnManager.close();
        }
        if (socialManager != null) {
            socialManager.close();
        }
        if (swordManager != null) {
            swordManager.close();
        }
        if (protectArenaManager != null) {
            protectArenaManager.close();
        }
        if (arenaManager != null) {
            arenaManager.close();
        }
        if (settingsManager != null) {
            settingsManager.close();
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
            case "merge" -> mergeManager.open(sender);
            case "settings" -> settingsManager.open(sender);
            case "clear", "ping" -> playerUtilityCommands.handleCommand(sender, command.getName(), args);
            case "stats" -> statsManager.handleCommand(sender, args);
            case "biome" -> biomeManager.handleBiome(sender, args);
            case "find" -> biomeManager.handleFind(sender, args);
            case "executioner" -> tokenManager.handleExecutioner(sender, args);
            case "tokens" -> tokenManager.handleTokens(sender, args);
            case "woodtoken" -> tokenManager.handleLumberToken(sender, args);
            case "miningtoken" -> tokenManager.handleMiningToken(sender, args);
            case "spawn" -> spawnManager.handleSpawn(sender, args);
            case "setspawn" -> spawnManager.handleSetSpawn(sender, args);
            case "combat" -> combatManager.handleCommand(sender, args);
            case "msg" -> label.equalsIgnoreCase("m")
                    && args.length > 0
                    && isModificationSubcommand(args[0])
                    ? dispatchModificationSubcommand(sender, args[0], tail(args))
                    : socialManager.handleMessage(sender, args);
            case "reply" -> socialManager.handleReply(sender, args);
            case "continue" -> socialManager.handleContinue(sender, args);
            case "sword" -> swordManager.handleCommand(sender, args);
            case "pet" -> petManager.handleCommand(sender, args);
            case "arena" -> arenaManager.handleCommand(sender, args);
            case "protectarena" -> protectArenaManager.handleCommand(sender, args);
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
        if (command.getName().equalsIgnoreCase("clear") || command.getName().equalsIgnoreCase("ping")) {
            return playerUtilityCommands.tabComplete(sender, command.getName(), args);
        }
        if (command.getName().equalsIgnoreCase("stats")) {
            return statsManager.tabComplete(args);
        }
        if (command.getName().equalsIgnoreCase("biome")) {
            return biomeManager.biomeTabComplete(sender, args);
        }
        if (command.getName().equalsIgnoreCase("find")) {
            return biomeManager.findTabComplete(sender, args);
        }
        if (command.getName().equalsIgnoreCase("tokens")) {
            return tokenManager.tabCompleteTokens(sender, args);
        }
        if (command.getName().equalsIgnoreCase("woodtoken")) {
            return tokenManager.tabCompleteLumberToken(sender, args);
        }
        if (command.getName().equalsIgnoreCase("miningtoken")) {
            return tokenManager.tabCompleteMiningToken(sender, args);
        }
        if (command.getName().equalsIgnoreCase("combat")) {
            return combatManager.tabComplete(sender, args);
        }
        if (command.getName().equalsIgnoreCase("msg")) {
            if (alias.equalsIgnoreCase("m") && args.length > 1
                    && isModificationSubcommand(args[0])) {
                return tabCompleteModificationSubcommand(sender, args[0], tail(args));
            }
            List<String> suggestions = new ArrayList<>(socialManager.tabCompleteMessage(args));
            if (alias.equalsIgnoreCase("m") && args.length == 1) {
                String partial = args[0].toLowerCase(Locale.ROOT);
                MODIFICATION_SUBCOMMANDS.stream()
                        .filter(option -> option.startsWith(partial))
                        .forEach(suggestions::add);
            }
            return suggestions.stream().distinct().toList();
        }
        if (command.getName().equalsIgnoreCase("sword")) {
            return swordManager.tabComplete(sender, args);
        }
        if (command.getName().equalsIgnoreCase("pet")) {
            return petManager.tabComplete(sender, args);
        }
        if (command.getName().equalsIgnoreCase("arena")) {
            return arenaManager.tabComplete(sender, args);
        }
        if (command.getName().equalsIgnoreCase("protectarena")) {
            return protectArenaManager.tabComplete(sender, args);
        }
        if (command.getName().equalsIgnoreCase("modification")) {
            if (args.length > 1 && isModificationSubcommand(args[0])) {
                return tabCompleteModificationSubcommand(sender, args[0], tail(args));
            }
            if (args.length == 1) {
                List<String> available = new ArrayList<>(MODIFICATION_SUBCOMMANDS);
                if (sender.hasPermission("modificationffa.reload")) {
                    available.add("reload");
                }
                String current = args[0].toLowerCase(Locale.ROOT);
                return available.stream().filter(subcommand -> subcommand.startsWith(current))
                        .distinct().toList();
            }
        }
        return List.of();
    }

    @EventHandler
    public void onPlayerQuit(PlayerQuitEvent event) {
        linkCommandUses.remove(event.getPlayer().getUniqueId());
        if (kitManager != null) {
            kitManager.clearCooldown(event.getPlayer().getUniqueId());
        }
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
        if (args.length > 0 && isModificationSubcommand(args[0])
                && !args[0].equalsIgnoreCase("help")) {
            return dispatchModificationSubcommand(sender, args[0], tail(args));
        }
        if (args.length > 0 && args[0].equalsIgnoreCase("reload")) {
            return reloadPlugin(sender);
        }

        if (args.length > 0 && args[0].equalsIgnoreCase("help")) {
            sender.sendMessage(Component.text("------ ", NamedTextColor.GRAY)
                    .append(Component.text("modify help", NamedTextColor.GREEN))
                    .append(Component.text(" ------", NamedTextColor.GRAY)));
            modificationHelpLine(sender, "/modify help", "The command to display helpful information.");
            modificationHelpLine(sender, "/modify settings", "Lets you access your settings.");
            modificationHelpLine(sender, "/modify stats", "Lets you view player statistics.");
            modificationHelpLine(sender, "/modify find", "Find the location of a player.");
            modificationHelpLine(sender, "/modify bin", "Open the Modification Bin.");
            modificationHelpLine(sender, "/modify clear", "Clear your inventory.");
            modificationHelpLine(sender, "/modify ping", "Check a player's ping to the server.");
            modificationHelpLine(sender, "/modify executioner", "Trade Executioner heads for kill tokens.");
            modificationHelpLine(sender, "/modify merge", "Merge your Punch Bows' durability.");
            modificationHelpLine(sender, "/pet help", "View and manage your cosmetic pet.");
            if (sender.hasPermission("modificationffa.reload")) {
                modificationHelpLine(sender, "/modification reload",
                        "Reload config.yml and messages.json.");
            }
            return true;
        }

        sendPluginInformation(sender);
        return true;
    }

    private boolean dispatchModificationSubcommand(
            CommandSender sender, String subcommand, String[] args) {
        return switch (subcommand.toLowerCase(Locale.ROOT)) {
            case "bin" -> binManager.open(sender);
            case "clear" -> playerUtilityCommands.handleCommand(sender, "clear", args);
            case "executioner" -> tokenManager.handleExecutioner(sender, args);
            case "find" -> biomeManager.handleFind(sender, args);
            case "help" -> handleModificationCommand(sender, new String[]{"help"});
            case "merge" -> mergeManager.open(sender);
            case "ping" -> playerUtilityCommands.handleCommand(sender, "ping", args);
            case "settings" -> settingsManager.open(sender);
            case "stats" -> statsManager.handleCommand(sender, args);
            default -> false;
        };
    }

    private List<String> tabCompleteModificationSubcommand(
            CommandSender sender, String subcommand, String[] args) {
        return switch (subcommand.toLowerCase(Locale.ROOT)) {
            case "clear", "ping" -> playerUtilityCommands.tabComplete(sender, subcommand, args);
            case "find" -> biomeManager.findTabComplete(sender, args);
            case "stats" -> statsManager.tabComplete(args);
            default -> List.of();
        };
    }

    private boolean isModificationSubcommand(String value) {
        return MODIFICATION_SUBCOMMANDS.stream().anyMatch(value::equalsIgnoreCase);
    }

    private void modificationHelpLine(CommandSender sender, String command, String description) {
        sender.sendMessage(Component.text("- ", NamedTextColor.GRAY)
                .append(Component.text(command, NamedTextColor.GREEN))
                .append(Component.text(" - " + description, NamedTextColor.GRAY)));
    }

    private String[] tail(String[] args) {
        return Arrays.copyOfRange(args, 1, args.length);
    }

    private boolean reloadPlugin(CommandSender sender) {
        if (!sender.hasPermission("modificationffa.reload")) {
            sender.sendMessage(MessageStyle.permissionDenied("modificationffa.reload"));
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
            if (!settingsManager.broadcastTitlesEnabled(player)) {
                continue;
            }
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
