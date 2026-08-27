package me.poggercity.modificationFFA;

import io.papermc.paper.command.brigadier.BasicCommand;
import io.papermc.paper.command.brigadier.CommandSourceStack;
import io.papermc.paper.plugin.lifecycle.event.types.LifecycleEvents;
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
import org.jetbrains.annotations.NotNull;

import java.io.IOException;
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

    private static final List<String> MODIFICATION_SUBCOMMANDS = List.of(
            "bin", "clear", "executioner", "find", "help", "merge", "ping", "settings", "stats");
    private static final Set<String> MESSAGE_COMMAND_LABELS = Set.of(
            "m", "message", "w", "whisper", "tell", "pm");

    private final Map<UUID, Long> linkCommandUses = new HashMap<>();

    private PluginComponents components;
    private PluginMessages pluginMessages;
    private LinkMessages linkMessages;
    private ReminderService reminders;
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
        pluginMessages = new PluginMessages(this);
        MessageStyle.installMessages(pluginMessages);

        try {
            applyConfiguration();
        } catch (IOException | RuntimeException exception) {
            getLogger().severe("Could not load ModificationFFA configuration: " + exception.getMessage());
            getServer().getPluginManager().disablePlugin(this);
            return;
        }

        components = new PluginComponents(this, pluginMessages);
        try {
            components.start();
        } catch (IOException exception) {
            getLogger().severe("ModificationFFA stopped because " + exception.getMessage() + ".");
            getServer().getPluginManager().disablePlugin(this);
            return;
        }

        reminders = new ReminderService(this, components.settings);
        reminders.reload(getConfig(), linkMessages);
        getServer().getPluginManager().registerEvents(this, this);
        getLogger().info("ModificationFFA has been enabled.");
    }

    @Override
    public void onDisable() {
        if (reminders != null) {
            reminders.close();
        }
        if (components != null) {
            components.close();
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
            case "kit" -> components.kits.handleCommand(sender, args);
            case "bin" -> components.bin.open(sender);
            case "merge" -> components.merge.open(sender);
            case "settings" -> components.settings.open(sender);
            case "clear", "ping" -> components.playerUtilities.handleCommand(sender, command.getName(), args);
            case "stats" -> components.stats.handleCommand(sender, args);
            case "biome" -> components.biomes.handleBiome(sender, args);
            case "find" -> components.biomes.handleFind(sender, args);
            case "executioner" -> components.tokens.handleExecutioner(sender, args);
            case "tokens" -> components.tokens.handleTokens(sender, args);
            case "woodtoken" -> components.tokens.handleLumberToken(sender, args);
            case "miningtoken" -> components.tokens.handleMiningToken(sender, args);
            case "spawn" -> components.spawn.handleSpawn(sender, args);
            case "setspawn" -> components.spawn.handleSetSpawn(sender, args);
            case "combat" -> components.combat.handleCommand(sender, args);
            case "msg" -> MESSAGE_COMMAND_LABELS.contains(label.toLowerCase(Locale.ROOT))
                    && rootAliasesAtLoad.contains(label.toLowerCase(Locale.ROOT))
                    && args.length > 0
                    && (isModificationSubcommand(args[0]) || args[0].equalsIgnoreCase("reload"))
                    ? handleModificationCommand(sender, args)
                    : components.social.handleMessage(sender, args);
            case "reply" -> components.social.handleReply(sender, args);
            case "continue" -> components.social.handleContinue(sender, args);
            case "sword" -> components.swords.handleCommand(sender, args);
            case "pet" -> components.pets.handleCommand(sender, args);
            case "arena" -> components.arenas.handleCommand(sender, args);
            case "protectarena" -> components.protectedArenas.handleCommand(sender, args);
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
            return components.kits.tabComplete(sender, args);
        }
        if (command.getName().equalsIgnoreCase("clear") || command.getName().equalsIgnoreCase("ping")) {
            return components.playerUtilities.tabComplete(sender, command.getName(), args);
        }
        if (command.getName().equalsIgnoreCase("stats")) {
            return components.stats.tabComplete(args);
        }
        if (command.getName().equalsIgnoreCase("biome")) {
            return components.biomes.biomeTabComplete(sender, args);
        }
        if (command.getName().equalsIgnoreCase("find")) {
            return components.biomes.findTabComplete(sender, args);
        }
        if (command.getName().equalsIgnoreCase("tokens")) {
            return components.tokens.tabCompleteTokens(sender, args);
        }
        if (command.getName().equalsIgnoreCase("woodtoken")) {
            return components.tokens.tabCompleteLumberToken(sender, args);
        }
        if (command.getName().equalsIgnoreCase("miningtoken")) {
            return components.tokens.tabCompleteMiningToken(sender, args);
        }
        if (command.getName().equalsIgnoreCase("combat")) {
            return components.combat.tabComplete(sender, args);
        }
        if (command.getName().equalsIgnoreCase("msg")) {
            if (rootAliasesAtLoad.contains(alias.toLowerCase(Locale.ROOT)) && args.length > 1
                    && isModificationSubcommand(args[0])) {
                return tabCompleteModificationSubcommand(sender, args[0], tail(args));
            }
            List<String> suggestions = new ArrayList<>(components.social.tabCompleteMessage(args));
            if (rootAliasesAtLoad.contains(alias.toLowerCase(Locale.ROOT)) && args.length == 1) {
                String partial = args[0].toLowerCase(Locale.ROOT);
                MODIFICATION_SUBCOMMANDS.stream()
                        .filter(option -> option.startsWith(partial))
                        .forEach(suggestions::add);
            }
            return suggestions.stream().distinct().toList();
        }
        if (command.getName().equalsIgnoreCase("sword")) {
            return components.swords.tabComplete(sender, args);
        }
        if (command.getName().equalsIgnoreCase("pet")) {
            return components.pets.tabComplete(sender, args);
        }
        if (command.getName().equalsIgnoreCase("arena")) {
            return components.arenas.tabComplete(sender, args);
        }
        if (command.getName().equalsIgnoreCase("protectarena")) {
            return components.protectedArenas.tabComplete(sender, args);
        }
        return List.of();
    }

    @EventHandler
    public void onPlayerQuit(PlayerQuitEvent event) {
        linkCommandUses.remove(event.getPlayer().getUniqueId());
        if (components != null) {
            components.clearPlayerState(event.getPlayer().getUniqueId());
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
                player.sendMessage(linkMessages.cooldown(remainingSeconds));
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
            Map<String, Object> placeholders = Map.of("root", root);
            pluginMessages.send(sender, "modification.help.title", placeholders);
            pluginMessages.sendLines(sender, "modification.help.lines", placeholders);
            if (sender.hasPermission("modificationffa.reload")) {
                pluginMessages.send(sender, "modification.help.reload", placeholders);
            }
            return true;
        }

        sendPluginInformation(sender);
        return true;
    }

    private boolean dispatchModificationSubcommand(
            CommandSender sender, String subcommand, String[] args) {
        return switch (subcommand.toLowerCase(Locale.ROOT)) {
            case "bin" -> components.bin.open(sender);
            case "clear" -> components.playerUtilities.handleCommand(sender, "clear", args);
            case "executioner" -> components.tokens.handleExecutioner(sender, args);
            case "find" -> components.biomes.handleFind(sender, args);
            case "help" -> handleModificationCommand(sender, new String[]{"help"});
            case "merge" -> components.merge.open(sender);
            case "ping" -> components.playerUtilities.handleCommand(sender, "ping", args);
            case "settings" -> components.settings.open(sender);
            case "stats" -> components.stats.handleCommand(sender, args);
            default -> false;
        };
    }

    private List<String> tabCompleteModificationSubcommand(
            CommandSender sender, String subcommand, String[] args) {
        return switch (subcommand.toLowerCase(Locale.ROOT)) {
            case "clear", "ping" -> components.playerUtilities.tabComplete(sender, subcommand, args);
            case "find" -> components.biomes.findTabComplete(sender, args);
            case "stats" -> components.stats.tabComplete(args);
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
            components.refreshTheme();
            sender.sendMessage(MessageStyle.prefixedMessage("core.reloaded"));
            if (!PluginTheme.rootAliases().equals(rootAliasesAtLoad)) {
                sender.sendMessage(MessageStyle.prefixedMessage("core.aliases-restart"));
            }
        } catch (IOException | RuntimeException exception) {
            getLogger().warning("Could not reload ModificationFFA configuration: " + exception.getMessage());
            sender.sendMessage(MessageStyle.prefixedMessage("core.reload-failed"));
        }
        return true;
    }

    private void sendPluginInformation(CommandSender sender) {
        String version = getPluginMeta().getVersion();
        List<String> authors = getPluginMeta().getAuthors();
        String author = authors.isEmpty() ? "Unknown" : String.join(", ", authors);
        pluginMessages.sendLines(sender, "modification.info", Map.of(
                "version", version,
                "author", author,
                "root", "/" + preferredRootLabel()
        ));
    }

    private void applyConfiguration() throws IOException {
        FileConfiguration config = getConfig();
        PluginTheme loadedTheme = PluginTheme.from(config);
        String discordUrl = requireConfigString(config, "links.discord");
        String storeUrl = requireConfigString(config, "links.store");
        PluginTheme.install(loadedTheme);
        if (!pluginMessages.reload()) {
            throw new IOException("messages.yml is invalid");
        }
        LinkMessages loadedMessages = LinkMessages.load(pluginMessages, discordUrl, storeUrl);
        int loadedCooldown = Math.max(0, config.getInt("commands.cooldown-seconds", 3));
        linkMessages = loadedMessages;
        commandCooldownSeconds = loadedCooldown;
        if (reminders != null) {
            reminders.reload(config, linkMessages);
        }
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
