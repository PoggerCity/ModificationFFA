package me.poggercity.modificationFFA;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.Material;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.logging.Level;

final class KitManager implements AutoCloseable {

    private static final String ADMIN_PERMISSION = "modificationffa.kit.admin";
    private static final List<String> PLAYER_SUBCOMMANDS = List.of("help", "save", "load", "delete");
    private final ModificationFFA plugin;
    private final SettingsManager settingsManager;
    private final KitDatabase database;
    private final Set<UUID> activeOperations = ConcurrentHashMap.newKeySet();

    private ItemStack[] mainKit;
    private boolean databaseReady;

    KitManager(ModificationFFA plugin, SettingsManager settingsManager) {
        this.plugin = plugin;
        this.settingsManager = settingsManager;
        this.database = new KitDatabase(plugin.getDataFolder().toPath().resolve("kits.db"));
    }

    void start() {
        database.initialize()
                .thenCompose(ignored -> database.loadMainKit())
                .whenComplete((savedMainKit, error) -> runSync(() -> {
                    if (error != null) {
                        logDatabaseError("initialize the kit database", error);
                        return;
                    }

                    if (savedMainKit != null) {
                        try {
                            mainKit = cloneContents(KitCodec.deserialize(savedMainKit));
                        } catch (RuntimeException exception) {
                            plugin.getLogger().log(Level.SEVERE, "Could not read the main kit from kits.db.", exception);
                            return;
                        }
                    }

                    databaseReady = true;
                    plugin.getLogger().info("Kit database is ready.");
                }));
    }

    boolean handleCommand(CommandSender sender, String[] args) {
        String subcommand = args.length == 0 ? "help" : args[0].toLowerCase(Locale.ROOT);

        if (subcommand.equals("help")) {
            sendHelp(sender);
            return true;
        }

        if (!(sender instanceof Player player)) {
            sender.sendMessage(MessageStyle.prefixed("This command can only be used by players."));
            return true;
        }

        return switch (subcommand) {
            case "save" -> {
                savePlayerKit(player);
                yield true;
            }
            case "load" -> {
                loadPlayerKit(player);
                yield true;
            }
            case "delete" -> {
                deletePlayerKit(player);
                yield true;
            }
            case "adminsave" -> {
                saveMainKit(player);
                yield true;
            }
            default -> {
                sendHelp(sender);
                yield true;
            }
        };
    }

    List<String> tabComplete(CommandSender sender, String[] args) {
        if (args.length != 1) {
            return List.of();
        }

        List<String> available = new ArrayList<>(PLAYER_SUBCOMMANDS);
        if (sender.hasPermission(ADMIN_PERMISSION)) {
            available.add("adminsave");
        }

        String current = args[0].toLowerCase(Locale.ROOT);
        return available.stream().filter(command -> command.startsWith(current)).toList();
    }

    private void saveMainKit(Player player) {
        if (!player.hasPermission(ADMIN_PERMISSION)) {
            player.sendMessage(MessageStyle.permissionDenied(ADMIN_PERMISSION));
            return;
        }
        if (!canStartOperation(player)) {
            return;
        }

        ItemStack[] contents = cloneContents(player.getInventory().getContents());
        if (!KitValidator.hasAnyItems(contents)) {
            activeOperations.remove(player.getUniqueId());
            player.sendMessage(MessageStyle.prefixed("The main kit cannot be empty."));
            return;
        }

        byte[] serialized;
        try {
            serialized = KitCodec.serialize(contents);
        } catch (RuntimeException exception) {
            activeOperations.remove(player.getUniqueId());
            plugin.getLogger().log(Level.SEVERE, "Could not serialize the main kit.", exception);
            player.sendMessage(MessageStyle.prefixed("The main kit could not be saved. Check the console."));
            return;
        }

        UUID playerId = player.getUniqueId();
        database.saveMainKit(serialized).whenComplete((ignored, error) -> runSync(() -> {
            activeOperations.remove(playerId);
            Player onlinePlayer = plugin.getServer().getPlayer(playerId);
            if (error != null) {
                logDatabaseError("save the main kit", error);
                if (onlinePlayer != null) {
                    onlinePlayer.sendMessage(MessageStyle.prefixed("The main kit could not be saved. Check the console."));
                }
                return;
            }

            mainKit = cloneContents(contents);
            if (onlinePlayer != null) {
                onlinePlayer.sendMessage(MessageStyle.prefixed("You have saved the main kit."));
            }
        }));
    }

    private void savePlayerKit(Player player) {
        if (!hasUsableMainKit(player) || !canStartOperation(player)) {
            return;
        }

        ItemStack[] candidate = cloneContents(player.getInventory().getContents());
        KitValidator.SaveResult validation = KitValidator.validateForSave(mainKit, candidate);
        if (!validation.valid()) {
            activeOperations.remove(player.getUniqueId());
            sendValidationFailure(player, validation);
            return;
        }

        byte[] serialized;
        try {
            serialized = KitCodec.serialize(candidate);
        } catch (RuntimeException exception) {
            activeOperations.remove(player.getUniqueId());
            plugin.getLogger().log(Level.SEVERE, "Could not serialize a player kit.", exception);
            player.sendMessage(MessageStyle.prefixed("Your kit could not be saved. Check the console."));
            return;
        }

        UUID playerId = player.getUniqueId();
        database.savePlayerKit(playerId, serialized).whenComplete((ignored, error) -> runSync(() -> {
            activeOperations.remove(playerId);
            Player onlinePlayer = plugin.getServer().getPlayer(playerId);
            if (error != null) {
                logDatabaseError("save a player kit", error);
                if (onlinePlayer != null) {
                    onlinePlayer.sendMessage(MessageStyle.prefixed("Your kit could not be saved. Check the console."));
                }
                return;
            }

            if (onlinePlayer != null) {
                onlinePlayer.sendMessage(MessageStyle.prefixed("You have saved your kit."));
            }
        }));
    }

    private void loadPlayerKit(Player player) {
        if (!hasUsableMainKit(player)) {
            return;
        }

        if (purchasedItemSafetyBlocks(player)) {
            sendPurchasedItemsMessage(player);
            return;
        }
        if (!canStartOperation(player)) {
            return;
        }

        UUID playerId = player.getUniqueId();
        database.loadPlayerKit(playerId).whenComplete((savedKit, error) -> runSync(() -> {
            activeOperations.remove(playerId);
            Player onlinePlayer = plugin.getServer().getPlayer(playerId);
            if (onlinePlayer == null) {
                return;
            }
            if (error != null) {
                logDatabaseError("load a player kit", error);
                onlinePlayer.sendMessage(MessageStyle.prefixed("Your kit could not be loaded. Check the console."));
                return;
            }

            // Re-check after the asynchronous read so items obtained during it are never deleted.
            if (purchasedItemSafetyBlocks(onlinePlayer)) {
                sendPurchasedItemsMessage(onlinePlayer);
                return;
            }

            ItemStack[] kitToLoad = cloneContents(mainKit);
            if (savedKit != null) {
                try {
                    ItemStack[] savedLayout = KitCodec.deserialize(savedKit);
                    if (KitValidator.validateForSave(mainKit, savedLayout).valid()) {
                        kitToLoad = savedLayout;
                    } else {
                        database.deletePlayerKit(playerId);
                    }
                } catch (RuntimeException exception) {
                    plugin.getLogger().log(Level.WARNING,
                            "Discarding a corrupt saved kit for " + playerId + '.', exception);
                    database.deletePlayerKit(playerId);
                }
            }

            try {
                onlinePlayer.getInventory().setContents(cloneContents(kitToLoad));
                onlinePlayer.updateInventory();
                onlinePlayer.sendMessage(MessageStyle.prefixed("You have loaded your kit."));
            } catch (IllegalArgumentException exception) {
                plugin.getLogger().log(Level.SEVERE, "A saved kit has an invalid inventory size.", exception);
                onlinePlayer.sendMessage(MessageStyle.prefixed("Your kit could not be loaded. Check the console."));
            }
        }));
    }

    private void deletePlayerKit(Player player) {
        if (!canStartOperation(player)) {
            return;
        }

        UUID playerId = player.getUniqueId();
        database.deletePlayerKit(playerId).whenComplete((ignored, error) -> runSync(() -> {
            activeOperations.remove(playerId);
            Player onlinePlayer = plugin.getServer().getPlayer(playerId);
            if (error != null) {
                logDatabaseError("delete a player kit", error);
                if (onlinePlayer != null) {
                    onlinePlayer.sendMessage(MessageStyle.prefixed("Your kit could not be deleted. Check the console."));
                }
                return;
            }

            if (onlinePlayer != null) {
                onlinePlayer.sendMessage(MessageStyle.prefixed("You have deleted your kit."));
            }
        }));
    }

    private boolean canStartOperation(Player player) {
        if (!databaseReady) {
            player.sendMessage(MessageStyle.prefixed("The kit database is still starting. Please try again."));
            return false;
        }
        if (!activeOperations.add(player.getUniqueId())) {
            player.sendMessage(MessageStyle.prefixed("Please wait for your current kit operation to finish."));
            return false;
        }
        return true;
    }

    private boolean hasUsableMainKit(Player player) {
        if (!databaseReady) {
            player.sendMessage(MessageStyle.prefixed("The kit database is still starting. Please try again."));
            return false;
        }
        if (mainKit == null || !KitValidator.hasAnyItems(mainKit)) {
            player.sendMessage(MessageStyle.prefixed("The main kit has not been configured yet."));
            return false;
        }
        return true;
    }

    private void sendValidationFailure(Player player, KitValidator.SaveResult validation) {
        Material material = validation.material();
        switch (validation.failure()) {
            case FOREIGN_MATERIAL -> player.sendMessage(MessageStyle.prefix()
                    .append(Component.text("You cannot save a kit with a ", NamedTextColor.GRAY))
                    .append(Component.text(material.name(), NamedTextColor.GREEN))
                    .append(Component.text(" material in it.", NamedTextColor.GRAY)));
            case DIFFERENT_ITEM -> player.sendMessage(MessageStyle.prefix()
                    .append(Component.text("You cannot save a modified ", NamedTextColor.GRAY))
                    .append(Component.text(material.name(), NamedTextColor.GREEN))
                    .append(Component.text(" item.", NamedTextColor.GRAY)));
            case WRONG_AMOUNT -> player.sendMessage(MessageStyle.prefixed(
                    "Your kit cannot contain more items than the main kit."));
            case MORE_DURABILITY -> player.sendMessage(MessageStyle.prefixed(
                    "Your kit cannot contain items with more durability than the main kit."));
            default -> player.sendMessage(MessageStyle.prefixed("Your kit does not match the main kit."));
        }
    }

    private void sendPurchasedItemsMessage(Player player) {
        player.sendMessage(MessageStyle.prefixed(
                "Your kit has not been loaded because your inventory has purchased items in it."));
    }

    private boolean purchasedItemSafetyBlocks(Player player) {
        return settingsManager.kitSafetyEnabled(player)
                && KitValidator.hasPurchasedMaterial(player.getInventory().getContents(), mainKit);
    }

    private void sendHelp(CommandSender sender) {
        sender.sendMessage(Component.text("Kit help", NamedTextColor.GREEN));
        sendHelpLine(sender, "/kit help", "The command to display helpful information.");
        sendHelpLine(sender, "/kit save", "Saves your current inventory as your kit.");
        sendHelpLine(sender, "/kit load", "Loads your saved kit into your inventory.");
        sendHelpLine(sender, "/kit delete", "Deletes your saved kit.");
        if (sender.hasPermission(ADMIN_PERMISSION)) {
            sendHelpLine(sender, "/kit adminsave", "Saves your inventory as the main kit.");
        }
    }

    private void sendHelpLine(CommandSender sender, String command, String description) {
        sender.sendMessage(Component.text("- ", NamedTextColor.DARK_GRAY)
                .append(Component.text(command, NamedTextColor.GREEN))
                .append(Component.text(" - " + description, NamedTextColor.GRAY)));
    }

    private void logDatabaseError(String action, Throwable throwable) {
        Throwable cause = throwable;
        while (cause.getCause() != null) {
            cause = cause.getCause();
        }
        plugin.getLogger().log(Level.SEVERE, "Could not " + action + '.', cause);
    }

    private void runSync(Runnable action) {
        if (!plugin.isEnabled()) {
            return;
        }
        plugin.getServer().getScheduler().runTask(plugin, action);
    }

    private static ItemStack[] cloneContents(ItemStack[] contents) {
        return Arrays.stream(contents)
                .map(item -> item == null ? null : item.clone())
                .toArray(ItemStack[]::new);
    }

    @Override
    public void close() {
        activeOperations.clear();
        database.close();
    }
}
