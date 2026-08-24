package me.poggercity.modificationFFA;

import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import io.papermc.paper.datacomponent.DataComponentTypes;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.format.TextColor;
import net.kyori.adventure.text.format.TextDecoration;
import org.bukkit.Bukkit;
import org.bukkit.GameMode;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Item;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.block.BlockExplodeEvent;
import org.bukkit.event.block.BlockPlaceEvent;
import org.bukkit.event.block.BlockPistonExtendEvent;
import org.bukkit.event.block.BlockPistonRetractEvent;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.event.entity.EntityExplodeEvent;
import org.bukkit.event.entity.EntityPickupItemEvent;
import org.bukkit.event.entity.PlayerDeathEvent;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryDragEvent;
import org.bukkit.event.player.PlayerItemDamageEvent;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;
import org.bukkit.inventory.ItemFlag;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.PlayerInventory;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.scheduler.BukkitTask;
import org.jetbrains.annotations.NotNull;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/** Handles authenticated tokens, resource nodes, and the Executioner trader. */
final class TokenManager implements Listener, AutoCloseable {

    private static final String ADMIN_PERMISSION = "modificationffa.tokens.admin";
    private static final long PAIR_COOLDOWN_MILLIS = 10L * 60L * 1_000L;
    private static final long NODE_REGEN_MILLIS = 5L * 60L * 1_000L;
    private static final int MAX_GIVE_AMOUNT = 2_304;
    private static final int EXECUTIONER_SIZE = 27;
    private static final int EXECUTIONER_SLOT = 13;
    private static final int EXECUTIONER_FRAMES = 100;
    private static final int[] GRADIENT_STOPS = {
            0x8A00B8, 0xB000B5, 0xD81796, 0xF15B5B, 0xFF9C00, 0xFFE11A
    };

    private final JavaPlugin plugin;
    private final NamespacedKey tokenKey;
    private final NamespacedKey nodeKindKey;
    private final NamespacedKey nodeMaterialKey;
    private final Path dataPath;
    private final Gson gson = new Gson();
    private final Map<NodeKey, ResourceNode> nodes = new HashMap<>();
    private final Map<KillPair, Long> killCooldowns = new HashMap<>();
    private final Map<TokenType, ItemStack> tokenTemplates;
    private final Set<Inventory> executionerInventories = new HashSet<>();
    private final List<ItemStack> executionerFrames;
    private final ItemStack executionerFiller;

    private BukkitTask animationTask;
    private BukkitTask regenerationTask;
    private BukkitTask saveTask;
    private int animationFrame;
    private volatile long saveGeneration;
    private boolean closed;

    TokenManager(ModificationFFA plugin) {
        this.plugin = plugin;
        this.tokenKey = new NamespacedKey(plugin, "token_type");
        this.nodeKindKey = new NamespacedKey(plugin, "resource_node_kind");
        this.nodeMaterialKey = new NamespacedKey(plugin, "resource_node_material");
        this.dataPath = plugin.getDataFolder().toPath().resolve("token-nodes.json");
        this.tokenTemplates = createTokenTemplates();
        this.executionerFrames = createExecutionerFrames();
        this.executionerFiller = createFiller();
    }

    /** Loads persisted state and starts shared schedulers. Listener registration is owned by the plugin. */
    void start() {
        loadState();
        animationTask = Bukkit.getScheduler().runTaskTimer(plugin, this::animateExecutioners, 2L, 2L);
        regenerationTask = Bukkit.getScheduler().runTaskTimer(plugin, this::regenerateNodes, 20L, 20L);
    }

    boolean handleTokens(CommandSender sender, String[] args) {
        if (!sender.hasPermission(ADMIN_PERMISSION)) {
            sender.sendMessage(MessageStyle.prefixed("You do not have permission to use this command."));
            return true;
        }
        if (args.length != 4 || !args[0].equalsIgnoreCase("give")) {
            sender.sendMessage(MessageStyle.prefixed("Usage: /tokens give <player> <type> <amount>"));
            return true;
        }

        Player target = Bukkit.getPlayerExact(args[1]);
        TokenType type = TokenType.parse(args[2]);
        Integer amount = parseAmount(args[3]);
        if (target == null) {
            sender.sendMessage(MessageStyle.prefixed("That player is not online."));
        } else if (type == null) {
            sender.sendMessage(MessageStyle.prefixed("Unknown token type."));
        } else if (amount == null) {
            sender.sendMessage(MessageStyle.prefixed("Amount must be between 1 and " + MAX_GIVE_AMOUNT + "."));
        } else {
            giveTokens(target, type, amount);
            sender.sendMessage(MessageStyle.prefixed("Gave " + amount + " " + type.displayName + " to " + target.getName() + "."));
        }
        return true;
    }

    boolean handleExecutioner(CommandSender sender, String[] args) {
        if (args.length != 0) {
            sender.sendMessage(MessageStyle.prefixed("Usage: /executioner"));
            return true;
        }
        if (!(sender instanceof Player player)) {
            sender.sendMessage(MessageStyle.prefixed("This command can only be used by players."));
            return true;
        }

        ExecutionerHolder holder = new ExecutionerHolder();
        Inventory inventory = Bukkit.createInventory(holder, EXECUTIONER_SIZE,
                Component.text("Executioner Trader", TextColor.color(0xA000B8)));
        holder.inventory = inventory;
        for (int slot = 0; slot < inventory.getSize(); slot++) {
            inventory.setItem(slot, slot == EXECUTIONER_SLOT
                    ? executionerFrames.get(animationFrame)
                    : executionerFiller);
        }
        executionerInventories.add(inventory);
        player.openInventory(inventory);
        return true;
    }

    boolean handleLumberToken(CommandSender sender, String[] args) {
        return handleNodeGive(sender, args, NodeKind.LUMBER);
    }

    boolean handleMiningToken(CommandSender sender, String[] args) {
        return handleNodeGive(sender, args, NodeKind.MINING);
    }

    List<String> tabCompleteTokens(CommandSender sender, String[] args) {
        if (!sender.hasPermission(ADMIN_PERMISSION)) {
            return List.of();
        }
        if (args.length == 1) {
            return matching(args[0], List.of("give"));
        }
        if (args.length == 2 && args[0].equalsIgnoreCase("give")) {
            return matching(args[1], Bukkit.getOnlinePlayers().stream().map(Player::getName).sorted().toList());
        }
        if (args.length == 3 && args[0].equalsIgnoreCase("give")) {
            return matching(args[2], TokenType.names());
        }
        return List.of();
    }

    List<String> tabCompleteLumberToken(CommandSender sender, String[] args) {
        return tabCompleteNode(sender, args, NodeKind.LUMBER);
    }

    List<String> tabCompleteMiningToken(CommandSender sender, String[] args) {
        return tabCompleteNode(sender, args, NodeKind.MINING);
    }

    @EventHandler(ignoreCancelled = true)
    public void onPlayerDeath(PlayerDeathEvent event) {
        Player victim = event.getPlayer();
        Player killer = victim.getKiller();
        if (killer == null || killer.getUniqueId().equals(victim.getUniqueId())) {
            return;
        }

        long now = System.currentTimeMillis();
        KillPair pair = new KillPair(killer.getUniqueId(), victim.getUniqueId());
        if (killCooldowns.getOrDefault(pair, 0L) > now) {
            return;
        }
        killCooldowns.put(pair, now + PAIR_COOLDOWN_MILLIS);
        giveTokens(killer, TokenType.KILL, 1);
        markDirty();
    }

    @EventHandler(ignoreCancelled = true)
    public void onBlockPlace(BlockPlaceEvent event) {
        ItemStack hand = event.getItemInHand();
        ItemMeta meta = hand.getItemMeta();
        String kindName = meta.getPersistentDataContainer().get(nodeKindKey, PersistentDataType.STRING);
        String materialName = meta.getPersistentDataContainer().get(nodeMaterialKey, PersistentDataType.STRING);
        if (kindName == null || materialName == null) {
            return;
        }

        NodeKind kind;
        Material original;
        try {
            kind = NodeKind.valueOf(kindName);
            original = Material.valueOf(materialName);
        } catch (IllegalArgumentException exception) {
            event.setCancelled(true);
            return;
        }
        if (event.getBlockPlaced().getType() != original || !kind.supports(original)) {
            event.setCancelled(true);
            return;
        }

        NodeKey key = NodeKey.of(event.getBlockPlaced());
        nodes.put(key, new ResourceNode(kind, original, kind.replacement(original), true, 0L));
        markDirty();
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onBlockBreak(BlockBreakEvent event) {
        NodeKey key = NodeKey.of(event.getBlock());
        ResourceNode node = nodes.get(key);
        if (node != null && event.getPlayer().getGameMode() == GameMode.CREATIVE) {
            if (event.getPlayer().isSneaking()) {
                nodes.remove(key);
                markDirty();
            } else {
                event.setCancelled(true);
                event.getPlayer().sendMessage(MessageStyle.prefixed("Shift click to delete it."));
            }
            return;
        }

        if (isProtectedToolToken(event.getPlayer().getInventory().getItemInMainHand())) {
            event.setCancelled(true);
            return;
        }

        if (node == null) {
            return;
        }

        event.setCancelled(true);
        if (!node.active) {
            return;
        }

        node.active = false;
        node.readyAt = System.currentTimeMillis() + NODE_REGEN_MILLIS;
        event.getBlock().setType(node.replacement, false);
        giveTokens(event.getPlayer(), node.kind.reward, 1);
        markDirty();
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onTokenToolDamage(EntityDamageByEntityEvent event) {
        if (event.getDamager() instanceof Player player
                && isProtectedToolToken(player.getInventory().getItemInMainHand())) {
            event.setCancelled(true);
        }
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onTokenToolDurability(PlayerItemDamageEvent event) {
        if (isProtectedToolToken(event.getItem())) {
            event.setCancelled(true);
        }
    }

    @EventHandler(ignoreCancelled = true)
    public void onBlockExplode(BlockExplodeEvent event) {
        event.blockList().removeIf(block -> nodes.containsKey(NodeKey.of(block)));
    }

    @EventHandler(ignoreCancelled = true)
    public void onEntityExplode(EntityExplodeEvent event) {
        event.blockList().removeIf(block -> nodes.containsKey(NodeKey.of(block)));
    }

    @EventHandler(ignoreCancelled = true)
    public void onPistonExtend(BlockPistonExtendEvent event) {
        if (event.getBlocks().stream().anyMatch(block -> nodes.containsKey(NodeKey.of(block)))) {
            event.setCancelled(true);
        }
    }

    @EventHandler(ignoreCancelled = true)
    public void onPistonRetract(BlockPistonRetractEvent event) {
        if (event.getBlocks().stream().anyMatch(block -> nodes.containsKey(NodeKey.of(block)))) {
            event.setCancelled(true);
        }
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onExecutionerClick(InventoryClickEvent event) {
        Inventory top = event.getView().getTopInventory();
        if (!(top.getHolder(false) instanceof ExecutionerHolder)) {
            return;
        }
        event.setCancelled(true);
        if (event.getRawSlot() == EXECUTIONER_SLOT && event.getWhoClicked() instanceof Player player) {
            tradeHeads(player);
        }
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onExecutionerDrag(InventoryDragEvent event) {
        if (event.getView().getTopInventory().getHolder(false) instanceof ExecutionerHolder) {
            event.setCancelled(true);
        }
    }

    @EventHandler(priority = EventPriority.LOWEST, ignoreCancelled = true)
    public void prioritizeKillTokenPickup(EntityPickupItemEvent event) {
        if (!(event.getEntity() instanceof Player player) || isToken(event.getItem().getItemStack(), TokenType.KILL)) {
            return;
        }

        for (Entity nearby : player.getNearbyEntities(2.25, 1.5, 2.25)) {
            if (!(nearby instanceof Item item) || item.isDead() || !isToken(item.getItemStack(), TokenType.KILL)) {
                continue;
            }
            ItemStack ground = item.getItemStack();
            Map<Integer, ItemStack> overflow = player.getInventory().addItem(ground.clone());
            int left = overflow.values().stream().mapToInt(ItemStack::getAmount).sum();
            if (left == ground.getAmount()) {
                continue;
            }
            event.setCancelled(true);
            if (left == 0) {
                item.remove();
            } else {
                ground.setAmount(left);
                item.setItemStack(ground);
            }
            return;
        }
    }

    @Override
    public void close() {
        closed = true;
        cancel(animationTask);
        cancel(regenerationTask);
        cancel(saveTask);
        animationTask = null;
        regenerationTask = null;
        saveTask = null;
        saveState(snapshotState(), ++saveGeneration);
        executionerInventories.clear();
        nodes.clear();
        killCooldowns.clear();
    }

    private boolean handleNodeGive(CommandSender sender, String[] args, NodeKind kind) {
        if (!sender.hasPermission(ADMIN_PERMISSION)) {
            sender.sendMessage(MessageStyle.prefixed("You do not have permission to use this command."));
            return true;
        }
        if (args.length < 3 || !args[0].equalsIgnoreCase("give")) {
            sender.sendMessage(MessageStyle.prefixed("Usage: /" + kind.command + " give <player> <type>"));
            return true;
        }
        Player target = Bukkit.getPlayerExact(args[1]);
        Material material = parseNodeMaterial(joinArguments(args, 2), kind);
        if (target == null) {
            sender.sendMessage(MessageStyle.prefixed("That player is not online."));
        } else if (material == null || !kind.supports(material)) {
            sender.sendMessage(MessageStyle.prefixed("That is not a supported " + kind.label + " type."));
        } else {
            giveItem(target, createNodeItem(kind, material));
            sender.sendMessage(MessageStyle.prefixed("Gave a " + readable(material) + " resource node to " + target.getName() + "."));
        }
        return true;
    }

    private List<String> tabCompleteNode(CommandSender sender, String[] args, NodeKind kind) {
        if (!sender.hasPermission(ADMIN_PERMISSION)) {
            return List.of();
        }
        if (args.length == 1) {
            return matching(args[0], List.of("give"));
        }
        if (args.length == 2 && args[0].equalsIgnoreCase("give")) {
            return matching(args[1], Bukkit.getOnlinePlayers().stream().map(Player::getName).sorted().toList());
        }
        if (args.length == 3 && args[0].equalsIgnoreCase("give")) {
            return matching(args[2], kind.materialNames());
        }
        return List.of();
    }

    private void tradeHeads(Player player) {
        PlayerInventory inventory = player.getInventory();
        ItemStack[] storage = inventory.getStorageContents();
        int heads = 0;
        for (int slot = 0; slot < storage.length; slot++) {
            ItemStack item = storage[slot];
            if (item == null || item.getType() != Material.PLAYER_HEAD) {
                continue;
            }
            heads += item.getAmount();
            storage[slot] = null;
        }
        if (heads == 0) {
            player.sendMessage(MessageStyle.prefixed("You do not have any executioner heads to trade."));
            return;
        }
        inventory.setStorageContents(storage);
        giveTokens(player, TokenType.KILL, heads);
        player.sendMessage(MessageStyle.prefixed("You traded " + heads + " executioner head"
                + (heads == 1 ? "" : "s") + " for " + heads + " kill token" + (heads == 1 ? "." : "s.")));
    }

    private void giveTokens(Player player, TokenType type, int amount) {
        int remaining = amount;
        while (remaining > 0) {
            int batch = Math.min(64, remaining);
            ItemStack token = createToken(type);
            token.setAmount(batch);
            giveItem(player, token);
            remaining -= batch;
        }
    }

    private void giveItem(Player player, ItemStack item) {
        for (ItemStack overflow : player.getInventory().addItem(item).values()) {
            Item dropped = player.getWorld().dropItemNaturally(player.getLocation(), overflow);
            dropped.setPickupDelay(0);
            dropped.setOwner(player.getUniqueId());
        }
    }

    private ItemStack createToken(TokenType type) {
        return tokenTemplates.get(type).clone();
    }

    private Map<TokenType, ItemStack> createTokenTemplates() {
        Map<TokenType, ItemStack> templates = new EnumMap<>(TokenType.class);
        for (TokenType type : TokenType.values()) {
            templates.put(type, buildToken(type));
        }
        return Map.copyOf(templates);
    }

    private ItemStack buildToken(TokenType type) {
        ItemStack item = new ItemStack(type.material);
        if (type.forceStackable) {
            item.unsetData(DataComponentTypes.MAX_DAMAGE);
            item.unsetData(DataComponentTypes.DAMAGE);
            item.setData(DataComponentTypes.MAX_STACK_SIZE, 64);
        }
        if (type == TokenType.ENCHANTED_KILL) {
            item.setData(DataComponentTypes.ENCHANTMENT_GLINT_OVERRIDE, true);
        }
        ItemMeta meta = item.getItemMeta();
        Component name = Component.empty()
                .append(Component.text(type.icon, type.color)
                        .decoration(TextDecoration.BOLD, false))
                .append(Component.space())
                .append(Component.text(type.displayName, type.color)
                        .decoration(TextDecoration.BOLD, true))
                .decoration(TextDecoration.ITALIC, false);
        meta.displayName(name);
        if (!type.lore.isEmpty()) {
            meta.lore(type.lore.stream()
                    .map(line -> Component.text(line, NamedTextColor.GRAY).decoration(TextDecoration.ITALIC, false))
                    .toList());
        }
        meta.getPersistentDataContainer().set(tokenKey, PersistentDataType.STRING, type.name());
        meta.addItemFlags(ItemFlag.HIDE_ATTRIBUTES);
        item.setItemMeta(meta);
        return item;
    }

    private ItemStack createNodeItem(NodeKind kind, Material material) {
        ItemStack item = new ItemStack(material);
        ItemMeta meta = item.getItemMeta();
        TextColor color = kind == NodeKind.LUMBER ? TextColor.color(0x865546) : NamedTextColor.GRAY;
        meta.displayName(Component.text(readable(material) + " Resource Node", color)
                .decoration(TextDecoration.ITALIC, false));
        meta.lore(List.of(
                Component.text("Place this block to create a renewable resource node.", NamedTextColor.GRAY)
                        .decoration(TextDecoration.ITALIC, false),
                Component.text("Regenerates five minutes after being mined.", NamedTextColor.GRAY)
                        .decoration(TextDecoration.ITALIC, false)
        ));
        meta.getPersistentDataContainer().set(nodeKindKey, PersistentDataType.STRING, kind.name());
        meta.getPersistentDataContainer().set(nodeMaterialKey, PersistentDataType.STRING, material.name());
        item.setItemMeta(meta);
        return item;
    }

    private boolean isToken(ItemStack item, TokenType type) {
        if (item == null || item.getType().isAir()) {
            return false;
        }
        return type.name().equals(item.getPersistentDataContainer().get(tokenKey, PersistentDataType.STRING));
    }

    private boolean isProtectedToolToken(ItemStack item) {
        if (item == null || item.getType().isAir()) {
            return false;
        }
        String type = item.getPersistentDataContainer().get(tokenKey, PersistentDataType.STRING);
        return TokenType.MINING.name().equals(type)
                || TokenType.WOOD.name().equals(type)
                || TokenType.COMPRESSED_MINING.name().equals(type)
                || TokenType.COMPRESSED_WOOD.name().equals(type);
    }

    private void animateExecutioners() {
        animationFrame = (animationFrame + 1) % executionerFrames.size();
        ItemStack frame = executionerFrames.get(animationFrame);
        executionerInventories.removeIf(inventory -> {
            if (inventory.getViewers().isEmpty()) {
                return true;
            }
            inventory.setItem(EXECUTIONER_SLOT, frame);
            return false;
        });
    }

    private void regenerateNodes() {
        long now = System.currentTimeMillis();
        boolean changed = false;
        for (Map.Entry<NodeKey, ResourceNode> entry : nodes.entrySet()) {
            ResourceNode node = entry.getValue();
            if (node.active || node.readyAt > now) {
                continue;
            }
            World world = Bukkit.getWorld(entry.getKey().worldId);
            if (world == null) {
                continue;
            }
            world.getBlockAt(entry.getKey().x, entry.getKey().y, entry.getKey().z).setType(node.original, false);
            node.active = true;
            node.readyAt = 0L;
            changed = true;
        }
        killCooldowns.entrySet().removeIf(entry -> entry.getValue() <= now);
        if (changed) {
            markDirty();
        }
    }

    private List<ItemStack> createExecutionerFrames() {
        List<ItemStack> frames = new ArrayList<>(EXECUTIONER_FRAMES);
        String label = "Trade Executioner Heads";
        for (int frame = 0; frame < EXECUTIONER_FRAMES; frame++) {
            Component name = Component.empty();
            for (int index = 0; index < label.length(); index++) {
                double phase = ((index / (double) label.length()) - (frame / (double) EXECUTIONER_FRAMES));
                phase -= Math.floor(phase);
                double progress = 0.5 + 0.5 * Math.cos(phase * Math.PI * 2.0);
                name = name.append(Component.text(label.charAt(index), gradientColor(progress)));
            }
            ItemStack head = new ItemStack(Material.PLAYER_HEAD);
            ItemMeta meta = head.getItemMeta();
            meta.displayName(name.decoration(TextDecoration.ITALIC, false));
            meta.lore(List.of(
                    Component.text("Trade in executioner heads for kill tokens.", NamedTextColor.GRAY)
                            .decoration(TextDecoration.ITALIC, false),
                    Component.text("Every player head is worth one kill token.", NamedTextColor.GRAY)
                            .decoration(TextDecoration.ITALIC, false),
                    Component.empty(),
                    Component.text("Click to trade.", NamedTextColor.GRAY).decoration(TextDecoration.ITALIC, false)
            ));
            head.setItemMeta(meta);
            frames.add(head);
        }
        return List.copyOf(frames);
    }

    private ItemStack createFiller() {
        ItemStack item = new ItemStack(Material.GRAY_STAINED_GLASS_PANE);
        ItemMeta meta = item.getItemMeta();
        meta.displayName(Component.text(" ").decoration(TextDecoration.ITALIC, false));
        item.setItemMeta(meta);
        return item;
    }

    private TextColor gradientColor(double progress) {
        double scaled = Math.max(0.0, Math.min(1.0, progress)) * (GRADIENT_STOPS.length - 1);
        int lowerIndex = Math.min((int) scaled, GRADIENT_STOPS.length - 2);
        double blend = scaled - lowerIndex;
        int lower = GRADIENT_STOPS[lowerIndex];
        int upper = GRADIENT_STOPS[lowerIndex + 1];
        return TextColor.color(
                blendChannel(lower >> 16, upper >> 16, blend),
                blendChannel(lower >> 8, upper >> 8, blend),
                blendChannel(lower, upper, blend)
        );
    }

    private int blendChannel(int from, int to, double amount) {
        return (int) Math.round((from & 0xFF) + ((to & 0xFF) - (from & 0xFF)) * amount);
    }

    private void markDirty() {
        if (closed || saveTask != null) {
            return;
        }
        saveTask = Bukkit.getScheduler().runTaskLater(plugin, () -> {
            saveTask = null;
            String json = snapshotState();
            long generation = ++saveGeneration;
            Bukkit.getScheduler().runTaskAsynchronously(plugin, () -> saveState(json, generation));
        }, 10L);
    }

    private String snapshotState() {
        JsonObject root = new JsonObject();
        JsonArray nodeArray = new JsonArray();
        nodes.entrySet().stream()
                .sorted(Comparator.comparing(entry -> entry.getKey().toString()))
                .forEach(entry -> {
                    NodeKey key = entry.getKey();
                    ResourceNode node = entry.getValue();
                    JsonObject value = new JsonObject();
                    value.addProperty("w", key.worldId.toString());
                    value.addProperty("x", key.x);
                    value.addProperty("y", key.y);
                    value.addProperty("z", key.z);
                    value.addProperty("k", node.kind.code);
                    value.addProperty("m", node.original.name());
                    value.addProperty("r", node.replacement.name());
                    value.addProperty("a", node.active);
                    if (!node.active) {
                        value.addProperty("t", node.readyAt);
                    }
                    nodeArray.add(value);
                });
        root.add("nodes", nodeArray);

        long now = System.currentTimeMillis();
        JsonArray cooldownArray = new JsonArray();
        killCooldowns.forEach((pair, expiry) -> {
            if (expiry <= now) {
                return;
            }
            JsonObject value = new JsonObject();
            value.addProperty("k", pair.killer.toString());
            value.addProperty("v", pair.victim.toString());
            value.addProperty("t", expiry);
            cooldownArray.add(value);
        });
        root.add("cooldowns", cooldownArray);
        return gson.toJson(root);
    }

    private void loadState() {
        if (!Files.isRegularFile(dataPath)) {
            return;
        }
        try {
            JsonObject root = JsonParser.parseString(Files.readString(dataPath, StandardCharsets.UTF_8)).getAsJsonObject();
            for (JsonElement element : root.getAsJsonArray("nodes")) {
                JsonObject value = element.getAsJsonObject();
                NodeKey key = new NodeKey(UUID.fromString(value.get("w").getAsString()),
                        value.get("x").getAsInt(), value.get("y").getAsInt(), value.get("z").getAsInt());
                NodeKind kind = NodeKind.fromCode(value.get("k").getAsString());
                Material original = Material.valueOf(value.get("m").getAsString());
                Material replacement = Material.valueOf(value.get("r").getAsString());
                boolean active = value.get("a").getAsBoolean();
                long readyAt = active || !value.has("t") ? 0L : value.get("t").getAsLong();
                if (kind.supports(original)) {
                    nodes.put(key, new ResourceNode(kind, original, replacement, active, readyAt));
                }
            }
            long now = System.currentTimeMillis();
            JsonArray cooldowns = root.has("cooldowns") ? root.getAsJsonArray("cooldowns") : new JsonArray();
            for (JsonElement element : cooldowns) {
                JsonObject value = element.getAsJsonObject();
                long expiry = value.get("t").getAsLong();
                if (expiry > now) {
                    killCooldowns.put(new KillPair(UUID.fromString(value.get("k").getAsString()),
                            UUID.fromString(value.get("v").getAsString())), expiry);
                }
            }
        } catch (Exception exception) {
            plugin.getLogger().warning("Could not load token-nodes.json: " + exception.getMessage());
            nodes.clear();
            killCooldowns.clear();
        }
    }

    private synchronized void saveState(String json, long generation) {
        if (generation != saveGeneration) {
            return;
        }
        try {
            Files.createDirectories(dataPath.getParent());
            Path temporary = dataPath.resolveSibling(dataPath.getFileName() + ".tmp");
            Files.writeString(temporary, json, StandardCharsets.UTF_8);
            try {
                Files.move(temporary, dataPath, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
            } catch (AtomicMoveNotSupportedException exception) {
                Files.move(temporary, dataPath, StandardCopyOption.REPLACE_EXISTING);
            }
        } catch (IOException exception) {
            plugin.getLogger().warning("Could not save token-nodes.json: " + exception.getMessage());
        }
    }

    private Integer parseAmount(String input) {
        try {
            int amount = Integer.parseInt(input);
            return amount >= 1 && amount <= MAX_GIVE_AMOUNT ? amount : null;
        } catch (NumberFormatException exception) {
            return null;
        }
    }

    private Material parseMaterial(String input) {
        String normalized = input.trim().toUpperCase(Locale.ROOT).replace(' ', '_').replace('-', '_');
        Material material = Material.matchMaterial(normalized);
        if (material == null && normalized.startsWith("PALE_") && !normalized.startsWith("PALE_OAK_")) {
            material = Material.matchMaterial("PALE_OAK_" + normalized.substring("PALE_".length()));
        }
        return material;
    }

    private Material parseNodeMaterial(String input, NodeKind kind) {
        String normalized = input.trim().toUpperCase(Locale.ROOT).replace(' ', '_').replace('-', '_');
        Material direct = parseMaterial(normalized);
        if (direct != null && kind.supports(direct)) {
            return direct;
        }

        if (kind == NodeKind.LUMBER) {
            if (normalized.equals("PALE") || normalized.equals("PALE_WOOD") || normalized.equals("PALE_OAK")) {
                return Material.PALE_OAK_WOOD;
            }
            for (String suffix : List.of("_LOG", "_WOOD", "_STEM", "_HYPHAE")) {
                Material material = Material.matchMaterial(normalized + suffix);
                if (material != null && kind.supports(material)) {
                    return material;
                }
            }
        } else {
            Material ore = Material.matchMaterial(normalized + "_ORE");
            if (ore != null && kind.supports(ore)) {
                return ore;
            }
        }
        return null;
    }

    private String joinArguments(String[] args, int from) {
        StringBuilder joined = new StringBuilder();
        for (int index = from; index < args.length; index++) {
            if (!joined.isEmpty()) {
                joined.append('_');
            }
            joined.append(args[index]);
        }
        return joined.toString();
    }

    private String readable(Material material) {
        String[] words = material.name().toLowerCase(Locale.ROOT).split("_");
        StringBuilder result = new StringBuilder();
        for (String word : words) {
            if (!result.isEmpty()) {
                result.append(' ');
            }
            result.append(Character.toUpperCase(word.charAt(0))).append(word.substring(1));
        }
        return result.toString();
    }

    private List<String> matching(String prefix, Collection<String> options) {
        String normalized = prefix.toLowerCase(Locale.ROOT);
        return options.stream().filter(value -> value.toLowerCase(Locale.ROOT).startsWith(normalized)).toList();
    }

    private void cancel(BukkitTask task) {
        if (task != null) {
            task.cancel();
        }
    }

    private enum TokenType {
        KILL(Material.NETHER_STAR, "🗡", "Kill Token", NamedTextColor.YELLOW, true,
                List.of(), "kill"),
        MINING(Material.WOODEN_PICKAXE, "⛏", "Mining Token", NamedTextColor.GRAY, true,
                List.of("Trade this with the miner to get a kill token!", "You can find the miner at the mine shaft or temple."), "mining", "mine"),
        WOOD(Material.WOODEN_AXE, "🪓", "Wood Token", TextColor.color(0x865546), true,
                List.of("Trade this with the lumberjack to get a kill token!", "You can find the lumberjack at the igloo."), "wood", "lumber"),
        AFK(Material.CLOCK, "☁", "AFK Token", TextColor.color(0x72BFC8), false,
                List.of(), "afk"),
        COMPRESSED_MINING(Material.STONE_PICKAXE, "⛏", "Compressed Mining Token", NamedTextColor.GRAY, true,
                List.of("Trade this with the miner to get a kill token!", "You can find the miner at the mine shaft or temple."), "compressed_mining", "compressedmine"),
        COMPRESSED_WOOD(Material.STONE_AXE, "🪓", "Compressed Wood Token", TextColor.color(0x865546), true,
                List.of("Trade this with the lumberjack to get a kill token!", "You can find the lumberjack at the igloo."), "compressed_wood", "compressed_lumber"),
        ENCHANTED_KILL(Material.BEACON, "💰", "Enchanted Kill Token", NamedTextColor.YELLOW, false,
                List.of(), "enchanted_kill", "enchanted"),
        COMPRESSED_KILL(Material.SEA_LANTERN, "💰", "Compressed Kill Token", NamedTextColor.YELLOW, false,
                List.of(), "compressed_kill", "compressed");

        private final Material material;
        private final String icon;
        private final String displayName;
        private final TextColor color;
        private final boolean forceStackable;
        private final List<String> lore;
        private final Set<String> aliases;

        TokenType(Material material, String icon, String displayName, TextColor color,
                  boolean forceStackable, List<String> lore, String... aliases) {
            this.material = material;
            this.icon = icon;
            this.displayName = displayName;
            this.color = color;
            this.forceStackable = forceStackable;
            this.lore = lore;
            this.aliases = Set.of(aliases);
        }

        private static TokenType parse(String input) {
            String normalized = input.toLowerCase(Locale.ROOT).replace('-', '_');
            for (TokenType type : values()) {
                if (type.name().equalsIgnoreCase(normalized) || type.aliases.contains(normalized)) {
                    return type;
                }
            }
            return null;
        }

        private static List<String> names() {
            return List.of("kill", "mining", "wood", "afk", "compressed_mining", "compressed_wood",
                    "enchanted_kill", "compressed_kill");
        }
    }

    private enum NodeKind {
        LUMBER("L", "woodtoken", "wood", TokenType.WOOD),
        MINING("M", "miningtoken", "ore", TokenType.MINING);

        private final String code;
        private final String command;
        private final String label;
        private final TokenType reward;

        NodeKind(String code, String command, String label, TokenType reward) {
            this.code = code;
            this.command = command;
            this.label = label;
            this.reward = reward;
        }

        private boolean supports(Material material) {
            String name = material.name();
            if (this == MINING) {
                return name.endsWith("_ORE") || material == Material.ANCIENT_DEBRIS;
            }
            return name.endsWith("_LOG") || name.endsWith("_WOOD") || name.endsWith("_STEM")
                    || name.endsWith("_HYPHAE") || material == Material.BAMBOO_BLOCK;
        }

        private Material replacement(Material original) {
            if (this == MINING) {
                return original.name().startsWith("DEEPSLATE_") ? Material.DEEPSLATE : Material.STONE;
            }
            Material stripped = Material.matchMaterial("STRIPPED_" + original.name());
            if (stripped == null) {
                throw new IllegalArgumentException("No stripped form for " + original);
            }
            return stripped;
        }

        private List<String> materialNames() {
            return java.util.Arrays.stream(Material.values())
                    .filter(this::supports)
                    .filter(material -> this != LUMBER || Material.matchMaterial("STRIPPED_" + material.name()) != null)
                    .map(material -> material.name().toLowerCase(Locale.ROOT))
                    .sorted()
                    .toList();
        }

        private static NodeKind fromCode(String code) {
            return "L".equals(code) ? LUMBER : MINING;
        }
    }

    private record NodeKey(UUID worldId, int x, int y, int z) {
        private static NodeKey of(Block block) {
            return new NodeKey(block.getWorld().getUID(), block.getX(), block.getY(), block.getZ());
        }
    }

    private static final class ResourceNode {
        private final NodeKind kind;
        private final Material original;
        private final Material replacement;
        private boolean active;
        private long readyAt;

        private ResourceNode(NodeKind kind, Material original, Material replacement, boolean active, long readyAt) {
            this.kind = kind;
            this.original = original;
            this.replacement = replacement;
            this.active = active;
            this.readyAt = readyAt;
        }
    }

    private record KillPair(UUID killer, UUID victim) {
    }

    private static final class ExecutionerHolder implements InventoryHolder {
        private Inventory inventory;

        @Override
        public @NotNull Inventory getInventory() {
            if (inventory == null) {
                throw new IllegalStateException("Executioner inventory has not been created yet.");
            }
            return inventory;
        }
    }
}
