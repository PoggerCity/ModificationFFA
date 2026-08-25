package me.poggercity.modificationFFA;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.format.TextDecoration;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.AbstractWindCharge;
import org.bukkit.entity.Player;
import org.bukkit.entity.Projectile;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.HandlerList;
import org.bukkit.event.Listener;
import org.bukkit.event.block.Action;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.block.BlockPlaceEvent;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.event.entity.EntityPickupItemEvent;
import org.bukkit.event.entity.AreaEffectCloudApplyEvent;
import org.bukkit.event.entity.PotionSplashEvent;
import org.bukkit.event.entity.ProjectileLaunchEvent;
import org.bukkit.event.player.PlayerDropItemEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.event.player.PlayerQuitEvent;
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
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.logging.Level;

final class ArenaManager implements Listener, AutoCloseable {

    static final String ADMIN_PERMISSION = "modificationffa.arena.admin";
    private static final int SCHEMA_VERSION = 1;
    private static final List<String> ADMIN_COMMANDS = List.of("wand", "create", "delete", "edit");

    private final ModificationFFA plugin;
    private final NamespacedKey wandKey;
    private final Path dataFile;
    private final Gson gson = new GsonBuilder().setPrettyPrinting().disableHtmlEscaping().create();
    private final Map<UUID, RegionSelection> selections = new HashMap<>();

    private volatile List<ArenaRegion> arenas = List.of();
    private long nextCreationOrder = 1L;
    private boolean storageHealthy = true;

    ArenaManager(ModificationFFA plugin) {
        this.plugin = plugin;
        this.wandKey = new NamespacedKey(plugin, "arena_wand");
        this.dataFile = plugin.getDataFolder().toPath().resolve("arenas.json");
    }

    boolean start() {
        if (!load()) {
            return false;
        }
        plugin.getServer().getPluginManager().registerEvents(this, plugin);
        return true;
    }

    boolean handleCommand(CommandSender sender, String[] args) {
        String subcommand = args.length == 0 ? "help" : args[0].toLowerCase(Locale.ROOT);
        if (subcommand.equals("help")) {
            sendHelp(sender);
            return true;
        }
        if (!requireAdmin(sender) || !storageHealthy) {
            if (!storageHealthy) {
                sender.sendMessage(MessageStyle.prefixed(
                        "arenas.json could not be loaded safely. Check the console before editing arenas."));
            }
            return true;
        }
        return switch (subcommand) {
            case "wand" -> giveWand(sender);
            case "create" -> create(sender, args);
            case "delete" -> delete(sender, args);
            case "edit" -> edit(sender, args);
            default -> {
                sendHelp(sender);
                yield true;
            }
        };
    }

    List<String> tabComplete(CommandSender sender, String[] args) {
        if (args.length == 1) {
            List<String> choices = new ArrayList<>(List.of("help"));
            if (sender.hasPermission(ADMIN_PERMISSION)) {
                choices.addAll(ADMIN_COMMANDS);
            }
            return matching(choices, args[0]);
        }
        if (!sender.hasPermission(ADMIN_PERMISSION)) {
            return List.of();
        }
        if (args.length == 2 && (args[0].equalsIgnoreCase("delete")
                || args[0].equalsIgnoreCase("edit"))) {
            return matching(arenaNames(), args[1]);
        }
        if (args.length == 3 && args[0].equalsIgnoreCase("edit")) {
            return matching(ArenaFlag.ids(), args[2]);
        }
        return List.of();
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = false)
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
        if (!player.hasPermission(ADMIN_PERMISSION) || event.getClickedBlock() == null) {
            return;
        }
        RegionPoint point = RegionPoint.from(event.getClickedBlock());
        RegionSelection current = selections.getOrDefault(
                player.getUniqueId(), new RegionSelection(null, null));
        int position;
        if (action == Action.LEFT_CLICK_BLOCK) {
            selections.put(player.getUniqueId(), new RegionSelection(point, current.second()));
            position = 1;
        } else {
            selections.put(player.getUniqueId(), new RegionSelection(current.first(), point));
            position = 2;
        }
        sendPosition(player, position, point);
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onBlockBreak(BlockBreakEvent event) {
        if (disallows(event.getBlock().getLocation(), ArenaFlag.BLOCK_BREAK)) {
            event.setCancelled(true);
        }
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onBlockPlace(BlockPlaceEvent event) {
        if (disallows(event.getBlockPlaced().getLocation(), ArenaFlag.BLOCK_PLACE)) {
            event.setCancelled(true);
        }
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onItemDrop(PlayerDropItemEvent event) {
        if (disallows(event.getPlayer().getLocation(), ArenaFlag.ITEM_DROPS)) {
            event.setCancelled(true);
        }
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onItemPickup(EntityPickupItemEvent event) {
        if (!(event.getEntity() instanceof Player player)) {
            return;
        }
        if (disallows(player.getLocation(), ArenaFlag.ITEM_PICKUP)
                || disallows(event.getItem().getLocation(), ArenaFlag.ITEM_PICKUP)) {
            event.setCancelled(true);
        }
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onPvp(EntityDamageByEntityEvent event) {
        if (!(event.getEntity() instanceof Player victim)) {
            return;
        }
        Player attacker = attackingPlayer(event);
        if (attacker == null || attacker.equals(victim)) {
            return;
        }
        if (disallows(attacker.getLocation(), ArenaFlag.PVP)
                || disallows(victim.getLocation(), ArenaFlag.PVP)) {
            event.setCancelled(true);
        }
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onPotionSplash(PotionSplashEvent event) {
        if (!(event.getPotion().getShooter() instanceof Player attacker)) {
            return;
        }
        for (org.bukkit.entity.LivingEntity entity : event.getAffectedEntities()) {
            if (entity instanceof Player victim
                    && (disallows(attacker.getLocation(), ArenaFlag.PVP)
                    || disallows(victim.getLocation(), ArenaFlag.PVP))) {
                event.setIntensity(victim, 0.0D);
            }
        }
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onAreaEffectCloud(AreaEffectCloudApplyEvent event) {
        if (!(event.getEntity().getSource() instanceof Player attacker)) {
            return;
        }
        event.getAffectedEntities().removeIf(entity -> entity instanceof Player victim
                && (disallows(attacker.getLocation(), ArenaFlag.PVP)
                || disallows(victim.getLocation(), ArenaFlag.PVP)));
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onWindChargeUse(PlayerInteractEvent event) {
        ItemStack item = event.getItem();
        if (item == null || item.getType() != Material.WIND_CHARGE) {
            return;
        }
        if (disallows(event.getPlayer().getLocation(), ArenaFlag.WIND_CHARGE)) {
            event.setCancelled(true);
            event.setUseItemInHand(org.bukkit.event.Event.Result.DENY);
        }
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onWindChargeLaunch(ProjectileLaunchEvent event) {
        if (!(event.getEntity() instanceof AbstractWindCharge charge)
                || !(charge.getShooter() instanceof Player player)) {
            return;
        }
        if (disallows(player.getLocation(), ArenaFlag.WIND_CHARGE)
                || disallows(charge.getLocation(), ArenaFlag.WIND_CHARGE)) {
            event.setCancelled(true);
        }
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent event) {
        selections.remove(event.getPlayer().getUniqueId());
    }

    @Override
    public void close() {
        HandlerList.unregisterAll(this);
        selections.clear();
    }

    private boolean giveWand(CommandSender sender) {
        if (!(sender instanceof Player player)) {
            sender.sendMessage(MessageStyle.prefixed("This command can only be used by players."));
            return true;
        }
        ItemStack wand = new ItemStack(Material.WOODEN_AXE);
        ItemMeta meta = wand.getItemMeta();
        meta.displayName(Component.text("Arena Wand", NamedTextColor.LIGHT_PURPLE)
                .decoration(TextDecoration.ITALIC, false));
        meta.lore(List.of(
                gray("Left-click to set position 1."),
                gray("Right-click to set position 2.")
        ));
        meta.setUnbreakable(true);
        meta.getPersistentDataContainer().set(wandKey, PersistentDataType.BYTE, (byte) 1);
        wand.setItemMeta(meta);
        for (ItemStack overflow : player.getInventory().addItem(wand).values()) {
            player.getWorld().dropItemNaturally(player.getLocation(), overflow);
        }
        player.sendMessage(MessageStyle.prefixed("You have received the arena wand."));
        return true;
    }

    private boolean create(CommandSender sender, String[] args) {
        if (!(sender instanceof Player player)) {
            sender.sendMessage(MessageStyle.prefixed("This command can only be used by players."));
            return true;
        }
        if (args.length != 2 || !validName(args[1])) {
            sender.sendMessage(MessageStyle.prefixed("Usage: /arena create <name>"));
            return true;
        }
        if (find(args[1]) != null) {
            sender.sendMessage(MessageStyle.prefixed("An arena with that name already exists."));
            return true;
        }
        RegionSelection selection = selections.get(player.getUniqueId());
        if (selection == null || !selection.complete()) {
            sender.sendMessage(MessageStyle.prefixed("Select both corners with the arena wand first."));
            return true;
        }
        if (!selection.sameWorld()) {
            sender.sendMessage(MessageStyle.prefixed("Both corners must be in the same world."));
            return true;
        }
        ArenaRegion arena = new ArenaRegion(
                args[1], nextCreationOrder, RegionBounds.from(selection), ArenaRules.defaults());
        List<ArenaRegion> updated = new ArrayList<>(arenas);
        updated.add(arena);
        long updatedNextCreationOrder = nextCreationOrder + 1L;
        if (!save(new ArenaData(SCHEMA_VERSION, updatedNextCreationOrder, List.copyOf(updated)))) {
            sender.sendMessage(MessageStyle.prefixed("The arena could not be saved. Check the console."));
            return true;
        }
        arenas = List.copyOf(updated);
        nextCreationOrder = updatedNextCreationOrder;
        sender.sendMessage(MessageStyle.prefix()
                .append(Component.text("Created arena ", NamedTextColor.GRAY))
                .append(Component.text(arena.name(), NamedTextColor.GREEN))
                .append(Component.text(".", NamedTextColor.GRAY)));
        return true;
    }

    private boolean delete(CommandSender sender, String[] args) {
        if (args.length != 2 || !validName(args[1])) {
            sender.sendMessage(MessageStyle.prefixed("Usage: /arena delete <name>"));
            return true;
        }
        List<ArenaRegion> updated = new ArrayList<>(arenas);
        boolean removed = updated.removeIf(arena -> arena.name().equalsIgnoreCase(args[1]));
        if (!removed) {
            sender.sendMessage(MessageStyle.prefixed("That arena does not exist."));
            return true;
        }
        if (!save(new ArenaData(SCHEMA_VERSION, nextCreationOrder, List.copyOf(updated)))) {
            sender.sendMessage(MessageStyle.prefixed("The arena deletion could not be saved. Check the console."));
            return true;
        }
        arenas = List.copyOf(updated);
        sender.sendMessage(MessageStyle.prefixed("Deleted arena " + args[1] + "."));
        return true;
    }

    private boolean edit(CommandSender sender, String[] args) {
        if (args.length != 3) {
            sender.sendMessage(MessageStyle.prefixed(
                    "Usage: /arena edit <name> <pvp|block-break|block-place|wind-charge|item-drops|item-pickup>"));
            return true;
        }
        ArenaRegion current = find(args[1]);
        ArenaFlag flag = ArenaFlag.fromId(args[2]);
        if (current == null) {
            sender.sendMessage(MessageStyle.prefixed("That arena does not exist."));
            return true;
        }
        if (flag == null) {
            sender.sendMessage(MessageStyle.prefixed("That arena setting does not exist."));
            return true;
        }
        ArenaRules updatedRules = current.rules().toggle(flag);
        ArenaRegion replacement = new ArenaRegion(
                current.name(), current.creationOrder(), current.bounds(), updatedRules);
        List<ArenaRegion> updated = arenas.stream()
                .map(arena -> arena.name().equalsIgnoreCase(current.name()) ? replacement : arena)
                .toList();
        if (!save(new ArenaData(SCHEMA_VERSION, nextCreationOrder, List.copyOf(updated)))) {
            sender.sendMessage(MessageStyle.prefixed("The arena setting could not be saved. Check the console."));
            return true;
        }
        arenas = List.copyOf(updated);
        boolean enabled = updatedRules.allows(flag);
        sender.sendMessage(MessageStyle.prefix()
                .append(Component.text("Arena ", NamedTextColor.GRAY))
                .append(Component.text(current.name(), NamedTextColor.GREEN))
                .append(Component.text(enabled ? " now allows " : " no longer allows ", NamedTextColor.GRAY))
                .append(Component.text(flag.displayName, NamedTextColor.GREEN))
                .append(Component.text(".", NamedTextColor.GRAY)));
        return true;
    }

    private boolean disallows(Location location, ArenaFlag flag) {
        ArenaRegion arena = arenaAt(location);
        return arena != null && !arena.rules().allows(flag);
    }

    boolean allowsBlockBreak(org.bukkit.block.Block block) {
        return !disallows(block.getLocation(), ArenaFlag.BLOCK_BREAK);
    }

    private ArenaRegion arenaAt(Location location) {
        if (location.getWorld() == null) {
            return null;
        }
        ArenaRegion selected = null;
        for (ArenaRegion arena : arenas) {
            if (arena.bounds().contains(location.getWorld(),
                    location.getBlockX(), location.getBlockY(), location.getBlockZ())
                    && (selected == null || arena.creationOrder() > selected.creationOrder())) {
                selected = arena;
            }
        }
        return selected;
    }

    private Player attackingPlayer(EntityDamageByEntityEvent event) {
        if (event.getDamager() instanceof Player player) {
            return player;
        }
        if (event.getDamager() instanceof Projectile projectile
                && projectile.getShooter() instanceof Player player) {
            return player;
        }
        return null;
    }

    private ArenaRegion find(String name) {
        return arenas.stream()
                .filter(arena -> arena.name().equalsIgnoreCase(name))
                .findFirst()
                .orElse(null);
    }

    private List<String> arenaNames() {
        return arenas.stream()
                .map(ArenaRegion::name)
                .sorted(String.CASE_INSENSITIVE_ORDER)
                .toList();
    }

    private void sendHelp(CommandSender sender) {
        sender.sendMessage(Component.text("Arena help", NamedTextColor.GREEN));
        if (!sender.hasPermission(ADMIN_PERMISSION)) {
            return;
        }
        helpLine(sender, "/arena wand", "Gives you the arena selection wand.");
        helpLine(sender, "/arena create <name>", "Creates an arena from your selection.");
        helpLine(sender, "/arena delete <name>", "Deletes an arena.");
        helpLine(sender, "/arena edit <name> <setting>", "Toggles an arena setting.");
    }

    private void helpLine(CommandSender sender, String command, String description) {
        sender.sendMessage(Component.text("- ", NamedTextColor.DARK_GRAY)
                .append(Component.text(command, NamedTextColor.GREEN))
                .append(Component.text(" - " + description, NamedTextColor.GRAY)));
    }

    private Component gray(String text) {
        return Component.text(text, NamedTextColor.GRAY).decoration(TextDecoration.ITALIC, false);
    }

    private void sendPosition(Player player, int position, RegionPoint point) {
        player.sendMessage(MessageStyle.prefix()
                .append(Component.text("Position " + position + " set to ", NamedTextColor.GRAY))
                .append(Component.text(point.x() + ", " + point.y() + ", " + point.z(), NamedTextColor.GREEN))
                .append(Component.text(" in " + point.worldName() + ".", NamedTextColor.GRAY)));
    }

    private boolean isWand(ItemStack item) {
        return item != null && item.hasItemMeta()
                && item.getItemMeta().getPersistentDataContainer().has(wandKey, PersistentDataType.BYTE);
    }

    private boolean requireAdmin(CommandSender sender) {
        if (sender.hasPermission(ADMIN_PERMISSION)) {
            return true;
        }
        sender.sendMessage(MessageStyle.permissionDenied(ADMIN_PERMISSION));
        return false;
    }

    private boolean validName(String name) {
        return name != null && name.matches("[A-Za-z0-9_-]{1,32}");
    }

    private List<String> matching(List<String> choices, String partial) {
        String normalized = partial.toLowerCase(Locale.ROOT);
        return choices.stream()
                .filter(choice -> choice.toLowerCase(Locale.ROOT).startsWith(normalized))
                .sorted(String.CASE_INSENSITIVE_ORDER)
                .toList();
    }

    private boolean load() {
        if (!Files.exists(dataFile)) {
            return true;
        }
        try (Reader reader = Files.newBufferedReader(dataFile, StandardCharsets.UTF_8)) {
            ArenaData loaded = gson.fromJson(reader, ArenaData.class);
            if (loaded == null || loaded.schemaVersion() != SCHEMA_VERSION || loaded.arenas() == null
                    || loaded.nextCreationOrder() < 1L) {
                throw new IOException("Missing arena data.");
            }
            Set<String> names = new HashSet<>();
            Set<Long> creationOrders = new HashSet<>();
            for (ArenaRegion arena : loaded.arenas()) {
                if (arena == null || !arena.valid()
                        || !names.add(arena.name().toLowerCase(Locale.ROOT))
                        || !creationOrders.add(arena.creationOrder())) {
                    throw new IOException("Invalid or duplicate arena entry.");
                }
            }
            List<ArenaRegion> valid = loaded.arenas().stream()
                    .sorted(Comparator.comparingLong(ArenaRegion::creationOrder))
                    .toList();
            long highest = valid.stream().mapToLong(ArenaRegion::creationOrder).max().orElse(0L);
            if (loaded.nextCreationOrder() <= highest) {
                throw new IOException("Invalid next arena creation order.");
            }
            arenas = List.copyOf(valid);
            nextCreationOrder = loaded.nextCreationOrder();
            return true;
        } catch (Exception exception) {
            storageHealthy = false;
            plugin.getLogger().log(Level.SEVERE, "Could not safely load arenas.json.", exception);
            return false;
        }
    }

    private boolean save(ArenaData snapshot) {
        Path temporary = dataFile.resolveSibling(dataFile.getFileName() + ".tmp");
        try {
            Files.createDirectories(dataFile.getParent());
            try (Writer output = Files.newBufferedWriter(temporary, StandardCharsets.UTF_8)) {
                gson.toJson(snapshot, output);
            }
            try {
                Files.move(temporary, dataFile,
                        StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
            } catch (AtomicMoveNotSupportedException ignored) {
                Files.move(temporary, dataFile, StandardCopyOption.REPLACE_EXISTING);
            }
            return true;
        } catch (IOException exception) {
            storageHealthy = false;
            plugin.getLogger().log(Level.SEVERE, "Could not save arenas.json.", exception);
            return false;
        }
    }

    private record ArenaData(int schemaVersion, long nextCreationOrder, List<ArenaRegion> arenas) {
    }

    private record ArenaRegion(String name, long creationOrder, RegionBounds bounds, ArenaRules rules) {
        boolean valid() {
            return name != null && name.matches("[A-Za-z0-9_-]{1,32}")
                    && creationOrder >= 1L && bounds != null && bounds.valid() && rules != null;
        }
    }

    private record ArenaRules(
            boolean pvp,
            boolean blockBreak,
            boolean blockPlace,
            boolean windCharge,
            boolean itemDrops,
            boolean itemPickup
    ) {
        static ArenaRules defaults() {
            return new ArenaRules(true, true, true, true, true, true);
        }

        boolean allows(ArenaFlag flag) {
            return switch (flag) {
                case PVP -> pvp;
                case BLOCK_BREAK -> blockBreak;
                case BLOCK_PLACE -> blockPlace;
                case WIND_CHARGE -> windCharge;
                case ITEM_DROPS -> itemDrops;
                case ITEM_PICKUP -> itemPickup;
            };
        }

        ArenaRules toggle(ArenaFlag flag) {
            return switch (flag) {
                case PVP -> new ArenaRules(!pvp, blockBreak, blockPlace, windCharge, itemDrops, itemPickup);
                case BLOCK_BREAK -> new ArenaRules(pvp, !blockBreak, blockPlace, windCharge, itemDrops, itemPickup);
                case BLOCK_PLACE -> new ArenaRules(pvp, blockBreak, !blockPlace, windCharge, itemDrops, itemPickup);
                case WIND_CHARGE -> new ArenaRules(pvp, blockBreak, blockPlace, !windCharge, itemDrops, itemPickup);
                case ITEM_DROPS -> new ArenaRules(pvp, blockBreak, blockPlace, windCharge, !itemDrops, itemPickup);
                case ITEM_PICKUP -> new ArenaRules(pvp, blockBreak, blockPlace, windCharge, itemDrops, !itemPickup);
            };
        }
    }

    private enum ArenaFlag {
        PVP("pvp", "PvP"),
        BLOCK_BREAK("block-break", "block breaking"),
        BLOCK_PLACE("block-place", "block placing"),
        WIND_CHARGE("wind-charge", "wind charges"),
        ITEM_DROPS("item-drops", "item dropping"),
        ITEM_PICKUP("item-pickup", "item pickup");

        private final String id;
        private final String displayName;

        ArenaFlag(String id, String displayName) {
            this.id = id;
            this.displayName = displayName;
        }

        static ArenaFlag fromId(String id) {
            for (ArenaFlag flag : values()) {
                if (flag.id.equalsIgnoreCase(id)) {
                    return flag;
                }
            }
            return null;
        }

        static List<String> ids() {
            return List.of(values()).stream().map(flag -> flag.id).toList();
        }
    }
}
