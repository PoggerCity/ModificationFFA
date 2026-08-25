package me.poggercity.modificationFFA;

import io.papermc.paper.command.brigadier.BasicCommand;
import io.papermc.paper.command.brigadier.CommandSourceStack;
import io.papermc.paper.plugin.lifecycle.event.types.LifecycleEvents;
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
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

public final class ModificationFFA extends JavaPlugin implements Listener {

    private static final long TICKS_PER_MINUTE = 20L * 60L;
    private static final List<String> MODIFICATION_SUBCOMMANDS = List.of(
            "bin", "clear", "executioner", "find", "help", "merge", "ping", "settings", "stats");
    private static final Set<String> MESSAGE_COMMAND_LABELS = Set.of(
            "m", "message", "w", "whisper", "tell", "pm");

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
    private List<String> rootAliasesAtLoad = List.of("m", "p");

    @Override
    public void onLoad() {
        saveDefaultConfig();
        getConfig().options().copyDefaults(true);
        saveConfig();
        reloadConfig();
        try {
            PluginTheme.install(PluginTheme.from(getConfig()));
            rootAliasesAtLoad = List.copyOf(PluginTheme.rootAliases());
        } catch (RuntimeException exception) {
            getLogger().warning("Branding settings could not be loaded: " + exception.getMessage());
        }
        getLifecycleManager().registerEventHandler(LifecycleEvents.COMMANDS, event -> {
            List<String> aliases = new ArrayList<>(List.of("modificationffa", "modify"));
            rootAliasesAtLoad.stream()
                    .filter(alias -> !MESSAGE_COMMAND_LABELS.contains(alias))
                    .forEach(aliases::add);
            event.registrar().register(
                    "modification",
                    "Shows information and opens ModificationFFA features.",
                    aliases.stream().distinct().toList(),
                    new ModificationRootCommand()
            );
        });
    }

    @Override
    public void onEnable() {
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
            case "msg" -> MESSAGE_COMMAND_LABELS.contains(label.toLowerCase(Locale.ROOT))
                    && rootAliasesAtLoad.contains(label.toLowerCase(Locale.ROOT))
                    && args.length > 0
                    && (isModificationSubcommand(args[0]) || args[0].equalsIgnoreCase("reload"))
                    ? handleModificationCommand(sender, args)
                    : socialManager.handleMessage(sender, args);
            case "reply" -> socialManager.handleReply(sender, args);
            case "continue" -> socialManager.handleContinue(sender, args);
            case "sword" -> swordManager.handleCommand(sender, args);
            case "pet" -> petManager.handleCommand(sender, args);
            case "arena" -> arenaManager.handleCommand(sender, args);
            case "protectarena" -> protectArenaManager.handleCommand(sender, args);
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
            if (rootAliasesAtLoad.contains(alias.toLowerCase(Locale.ROOT)) && args.length > 1
                    && isModificationSubcommand(args[0])) {
                return tabCompleteModificationSubcommand(sender, args[0], tail(args));
            }
            List<String> suggestions = new ArrayList<>(socialManager.tabCompleteMessage(args));
            if (rootAliasesAtLoad.contains(alias.toLowerCase(Locale.ROOT)) && args.length == 1) {
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
                player.sendMessage(Component.text(message, MessageStyle.text()));
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
            String root = "/" + preferredRootLabel();
            sender.sendMessage(Component.text("------ ", MessageStyle.text())
                    .append(Component.text(preferredRootLabel() + " help", MessageStyle.accent()))
                    .append(Component.text(" ------", MessageStyle.text())));
            modificationHelpLine(sender, root + " help", "The command to display helpful information.");
            modificationHelpLine(sender, root + " settings", "Lets you access your settings.");
            modificationHelpLine(sender, root + " stats", "Lets you view player statistics.");
            modificationHelpLine(sender, root + " find", "Find the location of a player.");
            modificationHelpLine(sender, root + " bin", "Open the " + PluginTheme.menuName() + " Bin.");
            modificationHelpLine(sender, root + " clear", "Clear your inventory.");
            modificationHelpLine(sender, root + " ping", "Check a player's ping to the server.");
            modificationHelpLine(sender, root + " executioner", "Trade Executioner heads for kill tokens.");
            modificationHelpLine(sender, root + " merge", "Merge your Punch Bows' durability.");
            modificationHelpLine(sender, "/pet help", "View and manage your cosmetic pet.");
            if (sender.hasPermission("modificationffa.reload")) {
                modificationHelpLine(sender, root + " reload",
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

    private List<String> tabCompleteModificationRoot(CommandSender sender, String[] args) {
        if (args.length > 1 && isModificationSubcommand(args[0])) {
            return tabCompleteModificationSubcommand(sender, args[0], tail(args));
        }
        if (args.length != 1) {
            return List.of();
        }
        List<String> available = new ArrayList<>(MODIFICATION_SUBCOMMANDS);
        if (sender.hasPermission("modificationffa.reload")) {
            available.add("reload");
        }
        String current = args[0].toLowerCase(Locale.ROOT);
        return available.stream()
                .filter(subcommand -> subcommand.startsWith(current))
                .distinct()
                .toList();
    }

    private boolean isModificationSubcommand(String value) {
        return MODIFICATION_SUBCOMMANDS.stream().anyMatch(value::equalsIgnoreCase);
    }

    private void modificationHelpLine(CommandSender sender, String command, String description) {
        sender.sendMessage(Component.text("- ", MessageStyle.text())
                .append(Component.text(command, MessageStyle.accent()))
                .append(Component.text(" - " + description, MessageStyle.text())));
    }

    private String preferredRootLabel() {
        return rootAliasesAtLoad.stream().findFirst().orElse("modify");
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
            binManager.refreshTheme();
            mergeManager.refreshTheme();
            settingsManager.refreshTheme();
            tokenManager.refreshTheme();
            sender.sendMessage(Component.text(PluginTheme.displayName() + " configuration reloaded.", MessageStyle.accent()));
            if (!PluginTheme.rootAliases().equals(rootAliasesAtLoad)) {
                sender.sendMessage(MessageStyle.prefixed("Command alias changes will apply after the next restart."));
            }
        } catch (IOException | RuntimeException exception) {
            getLogger().warning("Could not reload ModificationFFA configuration: " + exception.getMessage());
            sender.sendMessage(Component.text("Reload failed. Check the console for details.", MessageStyle.error()));
        }
        return true;
    }

    private void sendPluginInformation(CommandSender sender) {
        String pluginName = PluginTheme.displayName();
        String version = getPluginMeta().getVersion();
        List<String> authors = getPluginMeta().getAuthors();
        String author = authors.isEmpty() ? "Unknown" : String.join(", ", authors);

        sender.sendMessage(Component.text("The server is running ", MessageStyle.text())
                .append(Component.text(pluginName, MessageStyle.accent()))
                .append(Component.text(" v", MessageStyle.text()))
                .append(Component.text(version, MessageStyle.accent())));
        sender.sendMessage(Component.text("The plugin was created by: ", MessageStyle.text())
                .append(Component.text(author, MessageStyle.accent())));
        sender.sendMessage(Component.text("Run ", MessageStyle.text())
                .append(Component.text("/" + preferredRootLabel() + " help", MessageStyle.accent()))
                .append(Component.text(" for sub commands.", MessageStyle.text())));
    }

    private void applyConfiguration() throws IOException {
        FileConfiguration config = getConfig();
        PluginTheme loadedTheme = PluginTheme.from(config);
        String discordUrl = requireConfigString(config, "links.discord");
        String storeUrl = requireConfigString(config, "links.store");
        Path messagesPath = getDataFolder().toPath().resolve("messages.json");

        LinkMessages loadedMessages = LinkMessages.load(
                messagesPath, discordUrl, storeUrl, loadedTheme.textColor(), loadedTheme.accentColor());
        int loadedCooldown = Math.max(0, config.getInt("commands.cooldown-seconds", 3));
        Sound loadedSound = loadReminderSound(config);

        linkMessages = loadedMessages;
        commandCooldownSeconds = loadedCooldown;
        reminderSound = loadedSound;
        PluginTheme.install(loadedTheme);
        scheduleReminders(config);
    }

    private final class ModificationRootCommand implements BasicCommand {

        @Override
        public void execute(CommandSourceStack source, String[] args) {
            handleModificationCommand(source.getSender(), args);
        }

        @Override
        public java.util.Collection<String> suggest(CommandSourceStack source, String[] args) {
            return tabCompleteModificationRoot(source.getSender(), args);
        }
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
