package me.poggercity.modificationFFA;

import net.kyori.adventure.text.format.TextDecoration;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.block.Block;
import org.bukkit.block.BlockState;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.HandlerList;
import org.bukkit.event.Listener;
import org.bukkit.event.block.Action;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.block.BlockBurnEvent;
import org.bukkit.event.block.BlockExplodeEvent;
import org.bukkit.event.block.BlockMultiPlaceEvent;
import org.bukkit.event.block.BlockPistonExtendEvent;
import org.bukkit.event.block.BlockPistonRetractEvent;
import org.bukkit.event.block.BlockPlaceEvent;
import org.bukkit.event.entity.EntityChangeBlockEvent;
import org.bukkit.event.entity.EntityExplodeEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.scheduler.BukkitTask;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletionException;
import java.util.logging.Level;

final class ProtectArenaManager implements Listener, AutoCloseable {

    static final String ADMIN_PERMISSION = "modificationffa.protectarena.admin";
    private static final String MAIN_WAND = "protect";
    private static final String EXEMPT_WAND = "exempt";

    private final ModificationFFA plugin;
    private final PluginMessages messages;
    private final ArenaManager arenaManager;
    private final ProtectedArenaDatabase database;
    private final NamespacedKey wandKey;
    private final Map<String, ProtectedArena> arenas = new HashMap<>();
    private final Map<UUID, RegionSelection> mainSelections = new HashMap<>();
    private final Map<UUID, RegionSelection> exemptionSelections = new HashMap<>();
    private final Set<String> pending = new HashSet<>();
    private final Map<MutationKey, ProtectedArenaDatabase.BlockMutation> pendingMutations = new HashMap<>();
    private BukkitTask mutationFlushTask;
    private boolean ready;

    ProtectArenaManager(ModificationFFA plugin, ArenaManager arenaManager, PluginMessages messages) {
        this.plugin = plugin;
        this.arenaManager = arenaManager;
        this.messages = messages;
        this.database = new ProtectedArenaDatabase(plugin.getDataFolder().toPath().resolve("protected-arenas.db"));
        this.wandKey = new NamespacedKey(plugin, "protect_arena_wand");
    }

    boolean start() {
        try {
            load(database.initializeAndLoad().join());
            ready = true;
            plugin.getServer().getPluginManager().registerEvents(this, plugin);
            return true;
        } catch (CompletionException exception) {
            plugin.getLogger().log(Level.SEVERE, "Could not initialize protected-arenas.db", exception.getCause());
            return false;
        }
    }

    boolean handleCommand(CommandSender sender, String[] args) {
        if (!sender.hasPermission(ADMIN_PERMISSION)) {
            messages.sendPrefixed(sender, "core.no-permission", Map.of("permission", ADMIN_PERMISSION));
            return true;
        }
        if (!ready) {
            messages.sendPrefixed(sender, "protect-arena.unavailable");
            return true;
        }
        if (args.length == 0 || args[0].equalsIgnoreCase("help")) {
            help(sender);
            return true;
        }
        return switch (args[0].toLowerCase(Locale.ROOT)) {
            case "wand" -> giveWand(sender, MAIN_WAND);
            case "create" -> createArena(sender, args);
            case "delete" -> deleteArena(sender, args);
            case "exempt" -> exemptionCommand(sender, args);
            default -> {
                help(sender);
                yield true;
            }
        };
    }

    List<String> tabComplete(CommandSender sender, String[] args) {
        if (!sender.hasPermission(ADMIN_PERMISSION)) {
            return List.of();
        }
        if (args.length == 1) {
            return matching(List.of("help", "wand", "create", "delete", "exempt"), args[0]);
        }
        if (args[0].equalsIgnoreCase("delete") && args.length == 2) {
            return matching(arenas.values().stream().map(ProtectedArena::name).toList(), args[1]);
        }
        if (!args[0].equalsIgnoreCase("exempt")) {
            return List.of();
        }
        if (args.length == 2) {
            return matching(List.of("wand", "create", "delete"), args[1]);
        }
        if (args.length == 3 && (args[1].equalsIgnoreCase("create") || args[1].equalsIgnoreCase("delete"))) {
            return matching(arenas.values().stream().map(ProtectedArena::name).toList(), args[2]);
        }
        if (args.length == 4 && args[1].equalsIgnoreCase("delete")) {
            ProtectedArena arena = arenas.get(key(args[2]));
            return arena == null ? List.of() : matching(arena.exemptions.values().stream().map(Exemption::name).toList(), args[3]);
        }
        return List.of();
    }

    boolean breakByAbility(Block block) {
        if (!arenaManager.allowsBlockBreak(block) || !canBreak(block)) {
            return false;
        }
        commitBreak(block);
        return true;
    }

    private boolean createArena(CommandSender sender, String[] args) {
        if (!(sender instanceof Player player) || args.length != 2) {
            messages.sendPrefixed(sender, "protect-arena.usage.create");
            return true;
        }
        String name = args[1];
        String arenaKey = key(name);
        RegionSelection selection = mainSelections.get(player.getUniqueId());
        if (!validName(name) || selection == null || !selection.complete() || !selection.sameWorld()) {
            messages.sendPrefixed(player, "protect-arena.selection-invalid");
            return true;
        }
        if (arenas.containsKey(arenaKey) || !pending.add("arena:" + arenaKey)) {
            messages.sendPrefixed(player, "protect-arena.already-exists");
            return true;
        }
        RegionBounds bounds = RegionBounds.from(selection);
        ProtectedArena arena = new ProtectedArena(arenaKey, name, bounds);
        database.insertArena(new ProtectedArenaDatabase.ArenaRow(arenaKey, name, bounds))
                .whenComplete((ignored, error) -> sync(() -> {
                    pending.remove("arena:" + arenaKey);
                    if (error != null) {
                        storageError(player, error);
                        return;
                    }
                    arenas.put(arenaKey, arena);
                    messages.sendPrefixed(player, "protect-arena.created", Map.of("name", name));
                }));
        return true;
    }

    private boolean deleteArena(CommandSender sender, String[] args) {
        if (args.length != 2) {
            messages.sendPrefixed(sender, "protect-arena.usage.delete");
            return true;
        }
        String arenaKey = key(args[1]);
        ProtectedArena arena = arenas.get(arenaKey);
        if (arena == null || !pending.add("arena:" + arenaKey)) {
            messages.sendPrefixed(sender, "protect-arena.not-found-or-busy");
            return true;
        }
        database.deleteArena(arenaKey).whenComplete((ignored, error) -> sync(() -> {
            pending.remove("arena:" + arenaKey);
            if (error != null) {
                storageError(sender, error);
                return;
            }
            arenas.remove(arenaKey);
            messages.sendPrefixed(sender, "protect-arena.deleted", Map.of("name", arena.name));
        }));
        return true;
    }

    private boolean exemptionCommand(CommandSender sender, String[] args) {
        if (args.length < 2) {
            exemptionHelp(sender);
            return true;
        }
        return switch (args[1].toLowerCase(Locale.ROOT)) {
            case "wand" -> giveWand(sender, EXEMPT_WAND);
            case "create" -> createExemption(sender, args);
            case "delete" -> deleteExemption(sender, args);
            default -> {
                exemptionHelp(sender);
                yield true;
            }
        };
    }

    private boolean createExemption(CommandSender sender, String[] args) {
        if (!(sender instanceof Player player) || args.length != 4) {
            messages.sendPrefixed(sender, "protect-arena.usage.exempt-create");
            return true;
        }
        ProtectedArena arena = arenas.get(key(args[2]));
        String exemptionKey = key(args[3]);
        RegionSelection selection = exemptionSelections.get(player.getUniqueId());
        if (arena == null || !validName(args[3]) || selection == null || !selection.complete() || !selection.sameWorld()) {
            messages.sendPrefixed(player, "protect-arena.exemption.selection-invalid");
            return true;
        }
        RegionBounds bounds = RegionBounds.from(selection);
        if (!arena.bounds.encloses(bounds)) {
            messages.sendPrefixed(player, "protect-arena.exemption.outside");
            return true;
        }
        String operation = "exempt:" + arena.key + ":" + exemptionKey;
        if (arena.exemptions.containsKey(exemptionKey) || !pending.add(operation)) {
            messages.sendPrefixed(player, "protect-arena.exemption.already-exists-or-busy");
            return true;
        }
        Exemption exemption = new Exemption(exemptionKey, args[3], bounds);
        database.insertExemption(new ProtectedArenaDatabase.ExemptionRow(arena.key, exemptionKey, args[3], bounds))
                .whenComplete((ignored, error) -> sync(() -> {
                    pending.remove(operation);
                    if (error != null) {
                        storageError(player, error);
                        return;
                    }
                    arena.exemptions.put(exemptionKey, exemption);
                    messages.sendPrefixed(player, "protect-arena.exemption.created", Map.of(
                            "name", exemption.name, "arena", arena.name));
                }));
        return true;
    }

    private boolean deleteExemption(CommandSender sender, String[] args) {
        if (args.length != 4) {
            messages.sendPrefixed(sender, "protect-arena.usage.exempt-delete");
            return true;
        }
        ProtectedArena arena = arenas.get(key(args[2]));
        String exemptionKey = key(args[3]);
        Exemption exemption = arena == null ? null : arena.exemptions.get(exemptionKey);
        String operation = arena == null ? "" : "exempt:" + arena.key + ":" + exemptionKey;
        if (exemption == null || !pending.add(operation)) {
            messages.sendPrefixed(sender, "protect-arena.exemption.not-found-or-busy");
            return true;
        }
        database.deleteExemption(arena.key, exemptionKey).whenComplete((ignored, error) -> sync(() -> {
            pending.remove(operation);
            if (error != null) {
                storageError(sender, error);
                return;
            }
            arena.exemptions.remove(exemptionKey);
            messages.sendPrefixed(sender, "protect-arena.exemption.deleted", Map.of("name", exemption.name));
        }));
        return true;
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onBreak(BlockBreakEvent event) {
        if (!canBreak(event.getBlock())) {
            event.setCancelled(true);
        }
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void afterBreak(BlockBreakEvent event) {
        commitBreak(event.getBlock());
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onPlace(BlockPlaceEvent event) {
        if (event instanceof BlockMultiPlaceEvent multiPlaceEvent) {
            for (BlockState state : multiPlaceEvent.getReplacedBlockStates()) {
                trackPlacement(state.getBlock(), state);
            }
            return;
        }
        trackPlacement(event.getBlockPlaced(), event.getBlockReplacedState());
    }

    private void trackPlacement(Block block, BlockState replacedState) {
        BlockCoordinate coordinate = BlockCoordinate.of(block);
        String baseData = replacedState.getBlockData().getAsString();
        List<ProtectedArenaDatabase.BlockMutation> mutations = new ArrayList<>();
        for (ProtectedArena arena : containing(block)) {
            if (arena.placed.putIfAbsent(coordinate, baseData) == null) {
                mutations.add(new ProtectedArenaDatabase.BlockMutation(arena.key, coordinate.x, coordinate.y, coordinate.z, baseData));
            }
        }
        persist(mutations);
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onBlockExplode(BlockExplodeEvent event) {
        filterExplosion(event.blockList());
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void afterBlockExplode(BlockExplodeEvent event) {
        event.blockList().forEach(this::commitBreak);
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onEntityExplode(EntityExplodeEvent event) {
        filterExplosion(event.blockList());
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void afterEntityExplode(EntityExplodeEvent event) {
        event.blockList().forEach(this::commitBreak);
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onBurn(BlockBurnEvent event) {
        if (protectedBaseline(event.getBlock())) {
            event.setCancelled(true);
        }
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void afterBurn(BlockBurnEvent event) {
        commitBreak(event.getBlock());
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onEntityChangeBlock(EntityChangeBlockEvent event) {
        if (protectedBaseline(event.getBlock())) {
            event.setCancelled(true);
        }
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void afterEntityChangeBlock(EntityChangeBlockEvent event) {
        commitBreak(event.getBlock());
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onPistonExtend(BlockPistonExtendEvent event) {
        if (pistonAffectsProtected(event.getBlocks(), event.getDirection().getModX(), event.getDirection().getModY(), event.getDirection().getModZ())) {
            event.setCancelled(true);
        }
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onPistonRetract(BlockPistonRetractEvent event) {
        if (pistonAffectsProtected(event.getBlocks(), -event.getDirection().getModX(), -event.getDirection().getModY(), -event.getDirection().getModZ())) {
            event.setCancelled(true);
        }
    }

    @EventHandler(priority = EventPriority.HIGHEST)
    public void onWand(PlayerInteractEvent event) {
        if (event.getHand() != EquipmentSlot.HAND || event.getClickedBlock() == null || !event.getPlayer().hasPermission(ADMIN_PERMISSION)) {
            return;
        }
        ItemStack item = event.getItem();
        if (item == null || !item.hasItemMeta()) {
            return;
        }
        String mode = item.getItemMeta().getPersistentDataContainer().get(wandKey, PersistentDataType.STRING);
        if (!MAIN_WAND.equals(mode) && !EXEMPT_WAND.equals(mode)) {
            return;
        }
        Action action = event.getAction();
        if (action != Action.LEFT_CLICK_BLOCK && action != Action.RIGHT_CLICK_BLOCK) {
            return;
        }
        event.setCancelled(true);
        Map<UUID, RegionSelection> selections = MAIN_WAND.equals(mode) ? mainSelections : exemptionSelections;
        RegionSelection current = selections.getOrDefault(event.getPlayer().getUniqueId(), new RegionSelection(null, null));
        RegionPoint point = RegionPoint.from(event.getClickedBlock());
        RegionSelection updated = action == Action.LEFT_CLICK_BLOCK
                ? new RegionSelection(point, current.second())
                : new RegionSelection(current.first(), point);
        selections.put(event.getPlayer().getUniqueId(), updated);
        String corner = action == Action.LEFT_CLICK_BLOCK ? "first" : "second";
        messages.sendPrefixed(event.getPlayer(), "protect-arena.position-set", Map.of(
                "position", corner,
                "mode", mode,
                "x", point.x(),
                "y", point.y(),
                "z", point.z()));
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent event) {
        mainSelections.remove(event.getPlayer().getUniqueId());
        exemptionSelections.remove(event.getPlayer().getUniqueId());
    }

    private void filterExplosion(List<Block> blocks) {
        blocks.removeIf(block -> !canBreak(block));
    }

    private boolean pistonAffectsProtected(List<Block> blocks, int dx, int dy, int dz) {
        for (Block block : blocks) {
            if (!containing(block).isEmpty() || !containing(block.getRelative(dx, dy, dz)).isEmpty()) {
                return true;
            }
        }
        return false;
    }

    private boolean canBreak(Block block) {
        BlockCoordinate coordinate = BlockCoordinate.of(block);
        for (ProtectedArena arena : containing(block)) {
            if (!arena.isExempt(block) && !arena.placed.containsKey(coordinate)) {
                return false;
            }
        }
        return true;
    }

    private boolean protectedBaseline(Block block) {
        BlockCoordinate coordinate = BlockCoordinate.of(block);
        for (ProtectedArena arena : containing(block)) {
            if (!arena.isExempt(block) && !arena.placed.containsKey(coordinate)) {
                return true;
            }
        }
        return false;
    }

    private void commitBreak(Block block) {
        BlockCoordinate coordinate = BlockCoordinate.of(block);
        String restore = null;
        List<ProtectedArenaDatabase.BlockMutation> mutations = new ArrayList<>();
        for (ProtectedArena arena : containing(block)) {
            String baseData = arena.placed.remove(coordinate);
            if (baseData == null) {
                continue;
            }
            mutations.add(new ProtectedArenaDatabase.BlockMutation(arena.key, coordinate.x, coordinate.y, coordinate.z, null));
            if (!arena.isExempt(block) && !isAir(baseData)) {
                restore = baseData;
            }
        }
        persist(mutations);
        if (restore != null) {
            String blockData = restore;
            Bukkit.getScheduler().runTask(plugin, () -> {
                try {
                    block.setBlockData(Bukkit.createBlockData(blockData), false);
                } catch (IllegalArgumentException exception) {
                    plugin.getLogger().log(Level.WARNING, "Could not restore protected block data " + blockData, exception);
                }
            });
        }
    }

    private List<ProtectedArena> containing(Block block) {
        return arenas.values().stream()
                .filter(arena -> arena.bounds.contains(block.getWorld(), block.getX(), block.getY(), block.getZ()))
                .toList();
    }

    private void load(ProtectedArenaDatabase.LoadedState state) {
        for (ProtectedArenaDatabase.ArenaRow row : state.arenas()) {
            if (row.bounds().valid()) {
                arenas.put(row.arenaKey(), new ProtectedArena(row.arenaKey(), row.name(), row.bounds()));
            }
        }
        for (ProtectedArenaDatabase.ExemptionRow row : state.exemptions()) {
            ProtectedArena arena = arenas.get(row.arenaKey());
            if (arena != null && row.bounds().valid() && arena.bounds.encloses(row.bounds())) {
                arena.exemptions.put(row.exemptionKey(), new Exemption(row.exemptionKey(), row.name(), row.bounds()));
            }
        }
        for (ProtectedArenaDatabase.PlacedRow row : state.placedBlocks()) {
            ProtectedArena arena = arenas.get(row.arenaKey());
            if (arena != null) {
                arena.placed.put(new BlockCoordinate(row.x(), row.y(), row.z()), row.baseData());
            }
        }
    }

    private boolean giveWand(CommandSender sender, String mode) {
        if (!(sender instanceof Player player)) {
            messages.sendPrefixed(sender, "protect-arena.wand.players-only");
            return true;
        }
        ItemStack wand = new ItemStack(Material.WOODEN_AXE);
        ItemMeta meta = wand.getItemMeta();
        String wandName = mode.equals(MAIN_WAND) ? "protect-arena.wand.arena-name" : "protect-arena.wand.exemption-name";
        meta.displayName(messages.component(wandName).decoration(TextDecoration.ITALIC, false));
        meta.lore(List.of(
                messages.component("protect-arena.wand.left-click").decoration(TextDecoration.ITALIC, false),
                messages.component("protect-arena.wand.right-click").decoration(TextDecoration.ITALIC, false)
        ));
        meta.getPersistentDataContainer().set(wandKey, PersistentDataType.STRING, mode);
        wand.setItemMeta(meta);
        player.getInventory().addItem(wand).values().forEach(item -> player.getWorld().dropItemNaturally(player.getLocation(), item));
        messages.sendPrefixed(player, "protect-arena.wand.received", Map.of("mode", mode));
        return true;
    }

    private void help(CommandSender sender) {
        messages.sendLines(sender, "protect-arena.help", Map.of());
    }

    private void exemptionHelp(CommandSender sender) {
        messages.sendPrefixed(sender, "protect-arena.exemption.help");
    }

    private void persist(List<ProtectedArenaDatabase.BlockMutation> mutations) {
        if (mutations.isEmpty()) {
            return;
        }
        for (ProtectedArenaDatabase.BlockMutation mutation : mutations) {
            pendingMutations.put(MutationKey.of(mutation), mutation);
        }
        if (mutationFlushTask == null) {
            mutationFlushTask = Bukkit.getScheduler().runTask(plugin, this::flushMutations);
        }
    }

    private void flushMutations() {
        mutationFlushTask = null;
        if (pendingMutations.isEmpty()) {
            return;
        }
        List<ProtectedArenaDatabase.BlockMutation> batch = new ArrayList<>(pendingMutations.values());
        pendingMutations.clear();
        database.applyBlockMutations(batch).whenComplete((ignored, error) -> {
            if (error == null || !ready) {
                return;
            }
            sync(() -> {
                for (ProtectedArenaDatabase.BlockMutation mutation : batch) {
                    pendingMutations.putIfAbsent(MutationKey.of(mutation), mutation);
                }
                plugin.getLogger().log(Level.SEVERE, "Could not save protected block state. The batch will be retried.", error);
                if (mutationFlushTask == null) {
                    mutationFlushTask = Bukkit.getScheduler().runTaskLater(plugin, this::flushMutations, 20L);
                }
            });
        });
    }

    private void storageError(CommandSender sender, Throwable error) {
        plugin.getLogger().log(Level.SEVERE, "Could not update protected-arenas.db", error);
        messages.sendPrefixed(sender, "protect-arena.save-failed");
    }

    private void sync(Runnable task) {
        Bukkit.getScheduler().runTask(plugin, task);
    }

    private static boolean isAir(String blockData) {
        try {
            return Bukkit.createBlockData(blockData).getMaterial().isAir();
        } catch (IllegalArgumentException exception) {
            return false;
        }
    }

    private static boolean validName(String name) {
        return name != null && name.matches("[A-Za-z0-9_-]{1,32}");
    }

    private static String key(String name) {
        return name.toLowerCase(Locale.ROOT);
    }

    private static List<String> matching(List<String> values, String input) {
        String prefix = input.toLowerCase(Locale.ROOT);
        return values.stream().filter(value -> value.toLowerCase(Locale.ROOT).startsWith(prefix)).sorted().toList();
    }

    @Override
    public void close() {
        HandlerList.unregisterAll(this);
        ready = false;
        if (mutationFlushTask != null) {
            mutationFlushTask.cancel();
            mutationFlushTask = null;
        }
        if (!pendingMutations.isEmpty()) {
            try {
                database.applyBlockMutations(new ArrayList<>(pendingMutations.values())).join();
                pendingMutations.clear();
            } catch (CompletionException exception) {
                plugin.getLogger().log(Level.SEVERE, "Could not flush protected block state", exception.getCause());
            }
        }
        try {
            database.closeAsync().join();
        } catch (CompletionException exception) {
            plugin.getLogger().log(Level.SEVERE, "Could not close protected-arenas.db", exception.getCause());
        }
        arenas.clear();
        mainSelections.clear();
        exemptionSelections.clear();
    }

    private static final class ProtectedArena {
        private final String key;
        private final String name;
        private final RegionBounds bounds;
        private final Map<String, Exemption> exemptions = new HashMap<>();
        private final Map<BlockCoordinate, String> placed = new HashMap<>();

        private ProtectedArena(String key, String name, RegionBounds bounds) {
            this.key = key;
            this.name = name;
            this.bounds = bounds;
        }

        private String name() {
            return name;
        }

        private boolean isExempt(Block block) {
            return exemptions.values().stream().anyMatch(exemption -> exemption.bounds.contains(
                    block.getWorld(), block.getX(), block.getY(), block.getZ()));
        }
    }

    private record Exemption(String key, String name, RegionBounds bounds) {
    }

    private record BlockCoordinate(int x, int y, int z) {
        private static BlockCoordinate of(Block block) {
            return new BlockCoordinate(block.getX(), block.getY(), block.getZ());
        }
    }

    private record MutationKey(String arenaKey, int x, int y, int z) {
        private static MutationKey of(ProtectedArenaDatabase.BlockMutation mutation) {
            return new MutationKey(mutation.arenaKey(), mutation.x(), mutation.y(), mutation.z());
        }
    }
}
