package me.poggercity.modificationFFA;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import net.kyori.adventure.text.Component;
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
    private final PluginMessages messages;
    private final NamespacedKey wandKey;
    private final Path dataFile;
    private final Gson gson = new GsonBuilder().setPrettyPrinting().disableHtmlEscaping().create();
    private final Map<UUID, RegionSelection> selections = new HashMap<>();

    private volatile List<ArenaRegion> arenas = List.of();
    private long nextCreationOrder = 1L;
    private boolean storageHealthy = true;

    ArenaManager(ModificationFFA plugin, PluginMessages messages) {
        this.plugin = plugin;
        this.messages = messages;
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
                messages.sendPrefixed(sender, "arena.storage-unhealthy");
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
        if (args.length == 2 && args[0].equalsIgnoreCase("delete")) {
            return matching(arenaNames(), args[1]);
        }
        if (args.length == 2 && args[0].equalsIgnoreCase("edit")) {
            List<String> choices = new ArrayList<>(arenaNames());
            choices.addAll(ArenaFlag.ids());
            return matching(choices, args[1]);
        }
        if (args.length == 3 && args[0].equalsIgnoreCase("edit")) {
            ArenaFlag firstArgumentFlag = ArenaFlag.fromId(args[1]);
            return firstArgumentFlag == null
                    ? matching(ArenaFlag.ids(), args[2])
                    : matching(arenaNames(), args[2]);
        }
        if (args.length == 4 && args[0].equalsIgnoreCase("edit")) {
            return matching(List.of("true", "false"), args[3]);
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
    public void onBlockInteract(PlayerInteractEvent event) {
        Action action = event.getAction();
        if (action == Action.RIGHT_CLICK_BLOCK && event.getHand() != EquipmentSlot.HAND) {
            return;
        }
        if ((action != Action.RIGHT_CLICK_BLOCK && action != Action.PHYSICAL)
                || event.getClickedBlock() == null
                || !disallows(event.getClickedBlock().getLocation(), ArenaFlag.INTERACT)) {
            return;
        }
        event.setCancelled(true);
        event.setUseInteractedBlock(org.bukkit.event.Event.Result.DENY);
        messages.sendPrefixed(event.getPlayer(), "arena.denied.interact");
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
            messages.sendPrefixed(sender, "core.players-only");
            return true;
        }
        ItemStack wand = new ItemStack(Material.WOODEN_AXE);
        ItemMeta meta = wand.getItemMeta();
        meta.displayName(messages.component("arena.wand.name")
                .decoration(TextDecoration.ITALIC, false));
        meta.lore(List.of(
                messages.component("arena.wand.left-click").decoration(TextDecoration.ITALIC, false),
                messages.component("arena.wand.right-click").decoration(TextDecoration.ITALIC, false)
        ));
        meta.setUnbreakable(true);
        meta.getPersistentDataContainer().set(wandKey, PersistentDataType.BYTE, (byte) 1);
        wand.setItemMeta(meta);
        for (ItemStack overflow : player.getInventory().addItem(wand).values()) {
            player.getWorld().dropItemNaturally(player.getLocation(), overflow);
        }
        messages.sendPrefixed(player, "arena.wand-received");
        return true;
    }

    private boolean create(CommandSender sender, String[] args) {
        if (!(sender instanceof Player player)) {
            messages.sendPrefixed(sender, "core.players-only");
            return true;
        }
        if (args.length != 2 || !validName(args[1])) {
            messages.sendPrefixed(sender, "arena.usage.create");
            return true;
        }
        if (find(args[1]) != null) {
            messages.sendPrefixed(sender, "arena.already-exists", Map.of("name", args[1]));
            return true;
        }
        RegionSelection selection = selections.get(player.getUniqueId());
        if (selection == null || !selection.complete()) {
            messages.sendPrefixed(sender, "arena.select-first");
            return true;
        }
        if (!selection.sameWorld()) {
            messages.sendPrefixed(sender, "arena.same-world");
            return true;
        }
        ArenaRegion arena = new ArenaRegion(
                args[1], nextCreationOrder, RegionBounds.from(selection), ArenaRules.defaults());
        List<ArenaRegion> updated = new ArrayList<>(arenas);
        updated.add(arena);
        long updatedNextCreationOrder = nextCreationOrder + 1L;
        if (!save(new ArenaData(SCHEMA_VERSION, updatedNextCreationOrder, List.copyOf(updated)))) {
            messages.sendPrefixed(sender, "arena.save-failed");
            return true;
        }
        arenas = List.copyOf(updated);
        nextCreationOrder = updatedNextCreationOrder;
        messages.sendPrefixed(sender, "arena.created", Map.of("name", arena.name()));
        return true;
    }

    private boolean delete(CommandSender sender, String[] args) {
        if (args.length != 2 || !validName(args[1])) {
            messages.sendPrefixed(sender, "arena.usage.delete");
            return true;
        }
        List<ArenaRegion> updated = new ArrayList<>(arenas);
        boolean removed = updated.removeIf(arena -> arena.name().equalsIgnoreCase(args[1]));
        if (!removed) {
            messages.sendPrefixed(sender, "arena.not-found", Map.of("name", args[1]));
            return true;
        }
        if (!save(new ArenaData(SCHEMA_VERSION, nextCreationOrder, List.copyOf(updated)))) {
            messages.sendPrefixed(sender, "arena.delete-failed");
            return true;
        }
        arenas = List.copyOf(updated);
        messages.sendPrefixed(sender, "arena.deleted", Map.of("name", args[1]));
        return true;
    }

    private boolean edit(CommandSender sender, String[] args) {
        if (args.length != 4) {
            messages.sendPrefixed(sender, "arena.usage.edit");
            return true;
        }
        ArenaFlag firstArgumentFlag = ArenaFlag.fromId(args[1]);
        ArenaRegion alternateArena = firstArgumentFlag == null ? null : find(args[2]);
        boolean alternateSyntax = alternateArena != null;
        ArenaRegion current = alternateSyntax ? alternateArena : find(args[1]);
        ArenaFlag flag = alternateSyntax ? firstArgumentFlag : ArenaFlag.fromId(args[2]);
        Boolean enabled = parseBoolean(args[3]);
        if (current == null) {
            String arenaName = firstArgumentFlag == null ? args[1] : args[2];
            messages.sendPrefixed(sender, "arena.not-found", Map.of("name", arenaName));
            return true;
        }
        if (flag == null) {
            messages.sendPrefixed(sender, "arena.setting-invalid");
            return true;
        }
        if (enabled == null) {
            messages.sendPrefixed(sender, "arena.setting-value");
            return true;
        }
        ArenaRules updatedRules = current.rules().with(flag, enabled);
        ArenaRegion replacement = new ArenaRegion(
                current.name(), current.creationOrder(), current.bounds(), updatedRules);
        List<ArenaRegion> updated = arenas.stream()
                .map(arena -> arena.name().equalsIgnoreCase(current.name()) ? replacement : arena)
                .toList();
        if (!save(new ArenaData(SCHEMA_VERSION, nextCreationOrder, List.copyOf(updated)))) {
            messages.sendPrefixed(sender, "arena.edit-failed");
            return true;
        }
        arenas = List.copyOf(updated);
        messages.sendPrefixed(sender, "arena.setting-updated", Map.of(
                "arena", current.name(),
                "setting", flag.id,
                "value", enabled ? "&atrue" : "&cfalse"));
        return true;
    }

    private Boolean parseBoolean(String value) {
        if (value.equalsIgnoreCase("true")) {
            return true;
        }
        if (value.equalsIgnoreCase("false")) {
            return false;
        }
        return null;
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
        messages.send(sender, "arena.help.title");
        if (!sender.hasPermission(ADMIN_PERMISSION)) {
            return;
        }
        messages.sendLines(sender, "arena.help.lines", Map.of());
    }

    private void sendPosition(Player player, int position, RegionPoint point) {
        messages.sendPrefixed(player, "arena.position-set", Map.of(
                "position", position,
                "x", point.x(),
                "y", point.y(),
                "z", point.z(),
                "world", point.worldName()));
    }

    private boolean isWand(ItemStack item) {
        return item != null && item.hasItemMeta()
                && item.getItemMeta().getPersistentDataContainer().has(wandKey, PersistentDataType.BYTE);
    }

    private boolean requireAdmin(CommandSender sender) {
        if (sender.hasPermission(ADMIN_PERMISSION)) {
            return true;
        }
        messages.sendPrefixed(sender, "core.no-permission", Map.of("permission", ADMIN_PERMISSION));
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
            boolean itemPickup,
            Boolean interact
    ) {
        static ArenaRules defaults() {
            return new ArenaRules(true, true, true, true, true, true, true);
        }

        boolean allows(ArenaFlag flag) {
            return switch (flag) {
                case PVP -> pvp;
                case BLOCK_BREAK -> blockBreak;
                case BLOCK_PLACE -> blockPlace;
                case WIND_CHARGE -> windCharge;
                case ITEM_DROPS -> itemDrops;
                case ITEM_PICKUP -> itemPickup;
                case INTERACT -> interact == null || interact;
            };
        }

        ArenaRules with(ArenaFlag flag, boolean enabled) {
            return switch (flag) {
                case PVP -> new ArenaRules(enabled, blockBreak, blockPlace, windCharge,
                        itemDrops, itemPickup, interact);
                case BLOCK_BREAK -> new ArenaRules(pvp, enabled, blockPlace, windCharge,
                        itemDrops, itemPickup, interact);
                case BLOCK_PLACE -> new ArenaRules(pvp, blockBreak, enabled, windCharge,
                        itemDrops, itemPickup, interact);
                case WIND_CHARGE -> new ArenaRules(pvp, blockBreak, blockPlace, enabled,
                        itemDrops, itemPickup, interact);
                case ITEM_DROPS -> new ArenaRules(pvp, blockBreak, blockPlace, windCharge,
                        enabled, itemPickup, interact);
                case ITEM_PICKUP -> new ArenaRules(pvp, blockBreak, blockPlace, windCharge,
                        itemDrops, enabled, interact);
                case INTERACT -> new ArenaRules(pvp, blockBreak, blockPlace, windCharge,
                        itemDrops, itemPickup, enabled);
            };
        }
    }

    private enum ArenaFlag {
        PVP("pvp", "PvP"),
        BLOCK_BREAK("block-break", "block breaking"),
        BLOCK_PLACE("block-place", "block placing"),
        WIND_CHARGE("wind-charge", "wind charges"),
        ITEM_DROPS("item-drops", "item dropping"),
        ITEM_PICKUP("item-pickup", "item pickup"),
        INTERACT("interact", "block interaction");

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
