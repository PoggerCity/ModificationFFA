package me.poggercity.modificationFFA;

import org.bukkit.Material;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.logging.Level;

final class KitManager implements AutoCloseable {

    private static final String ADMIN_PERMISSION = "modificationffa.kit.admin";
    private static final List<String> PLAYER_SUBCOMMANDS = List.of("help", "save", "load", "delete");
    private final ModificationFFA plugin;
    private final SettingsManager settingsManager;
    private final PluginMessages messages;
    private final KitDatabase database;
    private final KitLoadCooldown loadCooldown = new KitLoadCooldown();
    private final Set<UUID> activeOperations = ConcurrentHashMap.newKeySet();

    private ItemStack[] mainKit;
    private boolean databaseReady;

    KitManager(ModificationFFA plugin, SettingsManager settingsManager, PluginMessages messages) {
        this.plugin = plugin;
        this.settingsManager = settingsManager;
        this.messages = messages;
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
            messages.sendPrefixed(sender, "core.players-only");
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
            messages.sendPrefixed(player, "core.no-permission", Map.of("permission", ADMIN_PERMISSION));
            return;
        }
        if (!canStartOperation(player)) {
            return;
        }

        ItemStack[] contents = cloneContents(player.getInventory().getContents());
        if (!KitValidator.hasAnyItems(contents)) {
            activeOperations.remove(player.getUniqueId());
            messages.sendPrefixed(player, "kit.main-empty");
            return;
        }

        byte[] serialized;
        try {
            serialized = KitCodec.serialize(contents);
        } catch (RuntimeException exception) {
            activeOperations.remove(player.getUniqueId());
            plugin.getLogger().log(Level.SEVERE, "Could not serialize the main kit.", exception);
            messages.sendPrefixed(player, "kit.main-save-failed");
            return;
        }

        UUID playerId = player.getUniqueId();
        database.saveMainKit(serialized).whenComplete((ignored, error) -> runSync(() -> {
            activeOperations.remove(playerId);
            Player onlinePlayer = plugin.getServer().getPlayer(playerId);
            if (error != null) {
                logDatabaseError("save the main kit", error);
                if (onlinePlayer != null) {
                    messages.sendPrefixed(onlinePlayer, "kit.main-save-failed");
                }
                return;
            }

            mainKit = cloneContents(contents);
            if (onlinePlayer != null) {
                messages.sendPrefixed(onlinePlayer, "kit.main-saved");
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
            messages.sendPrefixed(player, "kit.save-failed");
            return;
        }

        UUID playerId = player.getUniqueId();
        database.savePlayerKit(playerId, serialized).whenComplete((ignored, error) -> runSync(() -> {
            activeOperations.remove(playerId);
            Player onlinePlayer = plugin.getServer().getPlayer(playerId);
            if (error != null) {
                logDatabaseError("save a player kit", error);
                if (onlinePlayer != null) {
                    messages.sendPrefixed(onlinePlayer, "kit.save-failed");
                }
                return;
            }

            if (onlinePlayer != null) {
                messages.sendPrefixed(onlinePlayer, "kit.saved");
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
        String remaining = loadCooldown.remaining(player.getUniqueId());
        if (remaining != null) {
            messages.sendPrefixed(player, "kit.load-wait", Map.of("seconds", remaining));
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
                messages.sendPrefixed(onlinePlayer, "kit.load-failed");
                return;
            }

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
                loadCooldown.recordSuccess(playerId);
                messages.sendPrefixed(onlinePlayer, "kit.loaded");
            } catch (IllegalArgumentException exception) {
                plugin.getLogger().log(Level.SEVERE, "A saved kit has an invalid inventory size.", exception);
                messages.sendPrefixed(onlinePlayer, "kit.load-failed");
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
                    messages.sendPrefixed(onlinePlayer, "kit.delete-failed");
                }
                return;
            }

            if (onlinePlayer != null) {
                messages.sendPrefixed(onlinePlayer, "kit.deleted");
            }
        }));
    }

    private boolean canStartOperation(Player player) {
        if (!databaseReady) {
            messages.sendPrefixed(player, "kit.starting");
            return false;
        }
        if (!activeOperations.add(player.getUniqueId())) {
            messages.sendPrefixed(player, "kit.busy");
            return false;
        }
        return true;
    }

    private boolean hasUsableMainKit(Player player) {
        if (!databaseReady) {
            messages.sendPrefixed(player, "kit.starting");
            return false;
        }
        if (mainKit == null || !KitValidator.hasAnyItems(mainKit)) {
            messages.sendPrefixed(player, "kit.main-missing");
            return false;
        }
        return true;
    }

    private void sendValidationFailure(Player player, KitValidator.SaveResult validation) {
        Material material = validation.material();
        switch (validation.failure()) {
            case FOREIGN_MATERIAL -> messages.sendPrefixed(player, "kit.invalid-material",
                    Map.of("material", material.name()));
            case DIFFERENT_ITEM -> messages.sendPrefixed(player, "kit.invalid-item",
                    Map.of("item", material.name()));
            case WRONG_AMOUNT -> messages.sendPrefixed(player, "kit.too-many-items");
            case MORE_DURABILITY -> messages.sendPrefixed(player, "kit.too-much-durability");
            default -> messages.sendPrefixed(player, "kit.mismatch");
        }
    }

    private void sendPurchasedItemsMessage(Player player) {
        messages.sendPrefixed(player, "kit.purchased-items");
    }

    private boolean purchasedItemSafetyBlocks(Player player) {
        return settingsManager.kitSafetyEnabled(player)
                && KitValidator.hasPurchasedMaterial(player.getInventory().getContents(), mainKit);
    }

    void clearCooldown(UUID playerId) {
        loadCooldown.clear(playerId);
    }

    private void sendHelp(CommandSender sender) {
        messages.send(sender, "kit.help.title");
        messages.components("kit.help.lines").forEach(sender::sendMessage);
        if (sender.hasPermission(ADMIN_PERMISSION)) {
            messages.send(sender, "kit.help.admin");
        }
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
        loadCooldown.clear();
        database.close();
    }
}
