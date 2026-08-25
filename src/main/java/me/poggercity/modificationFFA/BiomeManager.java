package me.poggercity.modificationFFA;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import net.kyori.adventure.text.format.TextDecoration;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.block.Action;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.persistence.PersistentDataType;

import java.io.IOException;
import java.io.Reader;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.logging.Level;

final class BiomeManager implements Listener, AutoCloseable {

    private static final String ADMIN_PERMISSION = "modificationffa.biome.admin";
    private static final List<String> PUBLIC_SUBCOMMANDS = List.of("help", "list");
    private static final List<String> ADMIN_SUBCOMMANDS = List.of("wand", "create", "delete");

    private final ModificationFFA plugin;
    private final PluginMessages messages;
    private final NamespacedKey wandKey;
    private final Path dataFile;
    private final Gson gson = new GsonBuilder().disableHtmlEscaping().create();
    private final ExecutorService writer;
    private final Map<UUID, Selection> selections = new HashMap<>();

    private volatile List<Region> regions = List.of();

    BiomeManager(ModificationFFA plugin, PluginMessages messages) {
        this.plugin = Objects.requireNonNull(plugin, "plugin");
        this.messages = Objects.requireNonNull(messages, "messages");
        this.wandKey = new NamespacedKey(plugin, "biome_wand");
        this.dataFile = plugin.getDataFolder().toPath().resolve("biomes.json");
        this.writer = Executors.newSingleThreadExecutor(task -> {
            Thread thread = new Thread(task, "ModificationFFA-biome-writer");
            thread.setDaemon(true);
            return thread;
        });
    }

    void start() {
        loadRegions();
    }

    boolean handleBiome(CommandSender sender, String[] args) {
        String subcommand = args.length == 0 ? "help" : args[0].toLowerCase(Locale.ROOT);
        return switch (subcommand) {
            case "help" -> {
                sendHelp(sender);
                yield true;
            }
            case "list" -> {
                sendRegionList(sender);
                yield true;
            }
            case "wand" -> {
                if (!requireAdmin(sender)) {
                    yield true;
                }
                if (!(sender instanceof Player player)) {
                    messages.sendPrefixed(sender, "core.players-only");
                    yield true;
                }
                giveWand(player);
                yield true;
            }
            case "create" -> {
                if (!requireAdmin(sender)) {
                    yield true;
                }
                createRegion(sender, joinedName(args));
                yield true;
            }
            case "delete" -> {
                if (!requireAdmin(sender)) {
                    yield true;
                }
                deleteRegion(sender, joinedName(args));
                yield true;
            }
            default -> {
                sendHelp(sender);
                yield true;
            }
        };
    }

    boolean handleFind(CommandSender sender, String[] args) {
        if (args.length != 1) {
            messages.sendPrefixed(sender, "biome.find.usage");
            return true;
        }

        Player target = Bukkit.getPlayerExact(args[0]);
        if (target == null) {
            messages.sendPrefixed(sender, "core.player-offline", Map.of("player", args[0]));
            return true;
        }

        Location location = target.getLocation();
        String biome = findRegionName(location);
        if (biome == null) {
            biome = location.getBlock().getBiome().getKey().getKey().toLowerCase(Locale.ROOT);
        }

        String coordinates = String.format(
                Locale.ROOT,
                "X: %.2f Y: %.2f Z: %.2f",
                location.getX(),
                location.getY(),
                location.getZ()
        );
        messages.sendPrefixed(sender, "biome.find.result", Map.of(
                "player", target.getName(),
                "coordinates", coordinates,
                "biome", biome));
        return true;
    }

    List<String> biomeTabComplete(CommandSender sender, String[] args) {
        if (args.length == 1) {
            List<String> choices = new ArrayList<>(PUBLIC_SUBCOMMANDS);
            if (sender.hasPermission(ADMIN_PERMISSION)) {
                choices.addAll(ADMIN_SUBCOMMANDS);
            }
            return matching(choices, args[0]);
        }

        if (args.length >= 2 && args[0].equalsIgnoreCase("delete") && sender.hasPermission(ADMIN_PERMISSION)) {
            String entered = String.join(" ", List.of(args).subList(1, args.length));
            return regions.stream()
                    .map(Region::name)
                    .distinct()
                    .filter(name -> name.toLowerCase(Locale.ROOT).startsWith(entered.toLowerCase(Locale.ROOT)))
                    .sorted(String.CASE_INSENSITIVE_ORDER)
                    .toList();
        }
        return List.of();
    }

    List<String> findTabComplete(CommandSender sender, String[] args) {
        if (args.length != 1) {
            return List.of();
        }
        return matching(Bukkit.getOnlinePlayers().stream().map(Player::getName).toList(), args[0]);
    }

    @EventHandler(ignoreCancelled = true)
    public void onWandUse(PlayerInteractEvent event) {
        if (event.getHand() != EquipmentSlot.HAND || !isWand(event.getItem())) {
            return;
        }

        Action action = event.getAction();
        if (action != Action.LEFT_CLICK_BLOCK && action != Action.RIGHT_CLICK_BLOCK) {
            return;
        }
        event.setCancelled(true);

        Player player = event.getPlayer();
        if (!player.hasPermission(ADMIN_PERMISSION)) {
            messages.sendPrefixed(player, "core.no-permission", Map.of("permission", ADMIN_PERMISSION));
            return;
        }

        Block block = event.getClickedBlock();
        if (block == null) {
            return;
        }
        SelectedPoint point = new SelectedPoint(
                block.getWorld().getUID(),
                block.getWorld().getName(),
                block.getX(),
                block.getY(),
                block.getZ()
        );
        Selection current = selections.getOrDefault(player.getUniqueId(), new Selection(null, null));
        int corner;
        if (action == Action.LEFT_CLICK_BLOCK) {
            selections.put(player.getUniqueId(), new Selection(point, current.second()));
            corner = 1;
        } else {
            selections.put(player.getUniqueId(), new Selection(current.first(), point));
            corner = 2;
        }

        messages.sendPrefixed(player, "biome.position-set", Map.of(
                "position", corner,
                "x", point.x(),
                "y", point.y(),
                "z", point.z(),
                "world", point.worldName()));
    }

    @Override
    public void close() {
        selections.clear();
        queueSave(regions);
        writer.shutdown();
        try {
            if (!writer.awaitTermination(5, TimeUnit.SECONDS)) {
                plugin.getLogger().warning("Timed out while saving biomes.json.");
            }
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            plugin.getLogger().warning("Interrupted while saving biomes.json.");
        }
    }

    private void giveWand(Player player) {
        ItemStack wand = new ItemStack(Material.BLAZE_ROD);
        ItemMeta meta = wand.getItemMeta();
        meta.displayName(messages.component("biome.wand.name").decoration(TextDecoration.ITALIC, false));
        meta.lore(List.of(
                messages.component("biome.wand.left-click").decoration(TextDecoration.ITALIC, false),
                messages.component("biome.wand.right-click").decoration(TextDecoration.ITALIC, false)
        ));
        meta.getPersistentDataContainer().set(wandKey, PersistentDataType.BYTE, (byte) 1);
        wand.setItemMeta(meta);

        for (ItemStack overflow : player.getInventory().addItem(wand).values()) {
            player.getWorld().dropItemNaturally(player.getLocation(), overflow);
        }
        messages.sendPrefixed(player, "biome.wand-received");
    }

    private void createRegion(CommandSender sender, String name) {
        if (!(sender instanceof Player player)) {
            messages.sendPrefixed(sender, "core.players-only");
            return;
        }
        if (!isValidName(name)) {
            messages.sendPrefixed(sender, "biome.usage.create");
            return;
        }

        Selection selection = selections.get(player.getUniqueId());
        if (selection == null || selection.first() == null || selection.second() == null) {
            messages.sendPrefixed(player, "biome.select-first");
            return;
        }
        if (!selection.first().worldId().equals(selection.second().worldId())) {
            messages.sendPrefixed(player, "biome.same-world");
            return;
        }

        SelectedPoint first = selection.first();
        SelectedPoint second = selection.second();
        Region region = new Region(
                name,
                first.worldId().toString(),
                first.worldName(),
                Math.min(first.x(), second.x()),
                Math.min(first.y(), second.y()),
                Math.min(first.z(), second.z()),
                Math.max(first.x(), second.x()),
                Math.max(first.y(), second.y()),
                Math.max(first.z(), second.z())
        );

        List<Region> updated = new ArrayList<>(regions);
        updated.removeIf(existing -> existing.name().equalsIgnoreCase(name));
        updated.add(region);
        regions = List.copyOf(updated);
        queueSave(regions);
        messages.sendPrefixed(player, "biome.created", Map.of("name", name));
    }

    private void deleteRegion(CommandSender sender, String name) {
        if (!isValidName(name)) {
            messages.sendPrefixed(sender, "biome.usage.delete");
            return;
        }

        List<Region> updated = new ArrayList<>(regions);
        boolean removed = updated.removeIf(region -> region.name().equalsIgnoreCase(name));
        if (!removed) {
            messages.sendPrefixed(sender, "biome.not-found", Map.of("name", name));
            return;
        }

        regions = List.copyOf(updated);
        queueSave(regions);
        messages.sendPrefixed(sender, "biome.deleted", Map.of("name", name));
    }

    private String findRegionName(Location location) {
        World world = location.getWorld();
        if (world == null) {
            return null;
        }
        List<Region> snapshot = regions;
        for (int index = snapshot.size() - 1; index >= 0; index--) {
            Region region = snapshot.get(index);
            if (region.contains(world, location.getBlockX(), location.getBlockY(), location.getBlockZ())) {
                return region.name();
            }
        }
        return null;
    }

    private void sendRegionList(CommandSender sender) {
        if (regions.isEmpty()) {
            messages.sendPrefixed(sender, "biome.list.empty");
            return;
        }
        messages.send(sender, "biome.list.title");
        regions.stream()
                .sorted(Comparator.comparing(Region::name, String.CASE_INSENSITIVE_ORDER))
                .forEach(region -> messages.send(sender, "biome.list.entry", Map.of(
                        "name", region.name(), "world", region.worldName())));
    }

    private void sendHelp(CommandSender sender) {
        messages.send(sender, "biome.help.title");
        messages.send(sender, "biome.help.list");
        if (sender.hasPermission(ADMIN_PERMISSION)) {
            messages.sendLines(sender, "biome.help.admin", Map.of());
        }
    }

    private boolean requireAdmin(CommandSender sender) {
        if (sender.hasPermission(ADMIN_PERMISSION)) {
            return true;
        }
        messages.sendPrefixed(sender, "core.no-permission", Map.of("permission", ADMIN_PERMISSION));
        return false;
    }

    private boolean isWand(ItemStack item) {
        return item != null
                && !item.getType().isAir()
                && item.hasItemMeta()
                && item.getItemMeta().getPersistentDataContainer().has(wandKey, PersistentDataType.BYTE);
    }

    private void loadRegions() {
        if (!Files.exists(dataFile)) {
            return;
        }
        try (Reader reader = Files.newBufferedReader(dataFile, StandardCharsets.UTF_8)) {
            Region[] loaded = gson.fromJson(reader, Region[].class);
            if (loaded != null) {
                regions = List.of(loaded).stream()
                        .filter(Objects::nonNull)
                        .filter(Region::valid)
                        .toList();
            }
            plugin.getLogger().info("Loaded " + regions.size() + " custom biome region(s).");
        } catch (Exception exception) {
            plugin.getLogger().log(Level.SEVERE, "Could not load biomes.json.", exception);
        }
    }

    private void queueSave(List<Region> snapshot) {
        List<Region> immutableSnapshot = List.copyOf(snapshot);
        writer.execute(() -> saveSnapshot(immutableSnapshot));
    }

    private void saveSnapshot(List<Region> snapshot) {
        Path temporary = dataFile.resolveSibling(dataFile.getFileName() + ".tmp");
        try {
            Files.createDirectories(dataFile.getParent());
            try (Writer output = Files.newBufferedWriter(temporary, StandardCharsets.UTF_8)) {
                gson.toJson(snapshot, output);
            }
            try {
                Files.move(temporary, dataFile, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
            } catch (AtomicMoveNotSupportedException ignored) {
                Files.move(temporary, dataFile, StandardCopyOption.REPLACE_EXISTING);
            }
        } catch (IOException exception) {
            plugin.getLogger().log(Level.SEVERE, "Could not save biomes.json.", exception);
        }
    }

    private String joinedName(String[] args) {
        if (args.length < 2) {
            return "";
        }
        return String.join(" ", List.of(args).subList(1, args.length)).trim();
    }

    private boolean isValidName(String name) {
        return !name.isBlank() && name.length() <= 48;
    }

    private List<String> matching(List<String> choices, String entered) {
        String normalized = entered.toLowerCase(Locale.ROOT);
        return choices.stream()
                .filter(choice -> choice.toLowerCase(Locale.ROOT).startsWith(normalized))
                .sorted(String.CASE_INSENSITIVE_ORDER)
                .toList();
    }

    private record Selection(SelectedPoint first, SelectedPoint second) {
    }

    private record SelectedPoint(UUID worldId, String worldName, int x, int y, int z) {
    }

    private record Region(
            String name,
            String worldId,
            String worldName,
            int minX,
            int minY,
            int minZ,
            int maxX,
            int maxY,
            int maxZ
    ) {
        boolean valid() {
            return name != null && !name.isBlank()
                    && worldId != null && worldName != null
                    && minX <= maxX && minY <= maxY && minZ <= maxZ;
        }

        boolean contains(World world, int x, int y, int z) {
            boolean sameWorld = world.getUID().toString().equals(worldId)
                    || world.getName().equalsIgnoreCase(worldName);
            return sameWorld
                    && x >= minX && x <= maxX
                    && y >= minY && y <= maxY
                    && z >= minZ && z <= maxZ;
        }
    }
}
