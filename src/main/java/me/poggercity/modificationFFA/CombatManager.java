package me.poggercity.modificationFFA;

import com.google.gson.Gson;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.Bukkit;
import org.bukkit.GameMode;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.Registry;
import org.bukkit.World;
import org.bukkit.attribute.Attribute;
import org.bukkit.attribute.AttributeInstance;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Player;
import org.bukkit.entity.Projectile;
import org.bukkit.entity.Zombie;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.event.entity.EntityDeathEvent;
import org.bukkit.event.entity.PlayerDeathEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerCommandPreprocessEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.inventory.EntityEquipment;
import org.bukkit.inventory.ItemStack;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.potion.PotionEffect;
import org.bukkit.potion.PotionEffectType;
import org.bukkit.scheduler.BukkitTask;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Collection;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

final class CombatManager implements Listener, AutoCloseable {

    static final String TAG_PERMISSION = "modificationffa.command.combat.tag";
    static final String UNTAG_PERMISSION = "modificationffa.command.combat.untag";
    static final String COMMAND_WHITELIST_PERMISSION =
            "modificationffa.command.combat.commandwhitelist";
    static final String COMMAND_BYPASS_PERMISSION = "combat.command.bypass";

    private static final long COMBAT_MILLIS = 60_000L;
    private static final long SAVE_PERIOD_TICKS = 20L * 5L;
    private static final Set<String> DEFAULT_COMMAND_WHITELIST = Set.of(
            "msg", "message", "m", "whisper", "w", "tell", "pm", "reply", "r",
            "apprend", "continue", "c", "a", "combat");

    private final JavaPlugin plugin;
    private final StatsManager statsManager;
    private final SpawnManager spawnManager;
    private final TokenManager tokenManager;
    private final NamespacedKey loggerKey;
    private final Path dataFile;
    private final Gson gson = new Gson();
    private final Map<UUID, Long> combatEnds = new HashMap<>();
    private final Map<UUID, UUID> combatOpponents = new HashMap<>();
    private final Map<UUID, LoggerSession> loggerSessions = new HashMap<>();
    private final Set<String> commandWhitelist = new LinkedHashSet<>(DEFAULT_COMMAND_WHITELIST);
    private final ExecutorService writer;

    private BukkitTask ticker;
    private BukkitTask saver;
    private boolean closed;

    CombatManager(JavaPlugin plugin, StatsManager statsManager, SpawnManager spawnManager,
                  TokenManager tokenManager) {
        this.plugin = plugin;
        this.statsManager = statsManager;
        this.spawnManager = spawnManager;
        this.tokenManager = tokenManager;
        this.loggerKey = new NamespacedKey(plugin, "combat_logger");
        this.dataFile = plugin.getDataFolder().toPath().resolve("combat.json");
        this.writer = Executors.newSingleThreadExecutor(runnable -> {
            Thread thread = new Thread(runnable, "ModificationFFA-Combat-Writer");
            thread.setDaemon(true);
            return thread;
        });
    }

    void start() {
        load();
        long now = System.currentTimeMillis();
        combatEnds.entrySet().removeIf(entry -> entry.getValue() <= now);
        Bukkit.getScheduler().runTask(plugin, this::restoreLoggerEntities);
        ticker = Bukkit.getScheduler().runTaskTimer(plugin, this::tickCombat, 1L, 20L);
        saver = Bukkit.getScheduler().runTaskTimer(plugin, this::queueSave,
                SAVE_PERIOD_TICKS, SAVE_PERIOD_TICKS);
    }

    boolean handleCommand(CommandSender sender, String[] args) {
        if (args.length == 0 || args[0].equalsIgnoreCase("help")) {
            sendHelp(sender);
            return true;
        }

        switch (args[0].toLowerCase(Locale.ROOT)) {
            case "timer" -> {
                if (args.length != 1 || !(sender instanceof Player player)) {
                    sender.sendMessage(MessageStyle.prefixed("Usage: /combat timer"));
                    return true;
                }
                Long end = combatEnds.get(player.getUniqueId());
                long seconds = end == null ? 0L : secondsRemaining(end);
                if (seconds <= 0L) {
                    clearCombat(player.getUniqueId(), false);
                    sender.sendMessage(MessageStyle.prefixed("You are not in combat."));
                } else {
                    sender.sendMessage(MessageStyle.prefix()
                            .append(Component.text("Combat Tag: ", NamedTextColor.GRAY))
                            .append(Component.text(seconds, NamedTextColor.GREEN)));
                }
            }
            case "tag" -> forceTag(sender, args);
            case "untag" -> forceUntag(sender, args);
            case "commandwhitelist" -> handleCommandWhitelist(sender, args);
            default -> sendHelp(sender);
        }
        return true;
    }

    List<String> tabComplete(CommandSender sender, String[] args) {
        if (args.length == 1) {
            List<String> options = new ArrayList<>(List.of("help", "timer"));
            if (sender.hasPermission(TAG_PERMISSION)) {
                options.add("tag");
            }
            if (sender.hasPermission(UNTAG_PERMISSION)) {
                options.add("untag");
            }
            if (sender.hasPermission(COMMAND_WHITELIST_PERMISSION)) {
                options.add("commandwhitelist");
            }
            String partial = args[0].toLowerCase(Locale.ROOT);
            return options.stream().filter(option -> option.startsWith(partial)).toList();
        }
        if (args.length == 2 && (args[0].equalsIgnoreCase("tag") || args[0].equalsIgnoreCase("untag"))) {
            String permission = args[0].equalsIgnoreCase("tag") ? TAG_PERMISSION : UNTAG_PERMISSION;
            if (!sender.hasPermission(permission)) {
                return List.of();
            }
            String partial = args[1].toLowerCase(Locale.ROOT);
            return Bukkit.getOnlinePlayers().stream().map(Player::getName)
                    .filter(name -> name.toLowerCase(Locale.ROOT).startsWith(partial))
                    .sorted(String.CASE_INSENSITIVE_ORDER).toList();
        }
        if (args[0].equalsIgnoreCase("commandwhitelist")
                && sender.hasPermission(COMMAND_WHITELIST_PERMISSION)) {
            if (args.length == 2) {
                String partial = args[1].toLowerCase(Locale.ROOT);
                return List.of("add", "delete", "list").stream()
                        .filter(option -> option.startsWith(partial)).toList();
            }
            if (args.length == 3 && args[1].equalsIgnoreCase("add")) {
                String partial = args[2].toLowerCase(Locale.ROOT);
                return Bukkit.getCommandMap().getKnownCommands().keySet().stream()
                        .map(command -> command.toLowerCase(Locale.ROOT))
                        .filter(command -> !command.contains(":"))
                        .filter(command -> !commandWhitelist.contains(command))
                        .filter(command -> command.startsWith(partial))
                        .distinct().sorted().toList();
            }
            if (args.length == 3 && args[1].equalsIgnoreCase("delete")) {
                String partial = args[2].toLowerCase(Locale.ROOT);
                return commandWhitelist.stream()
                        .filter(command -> !command.equals("combat"))
                        .filter(command -> command.startsWith(partial))
                        .sorted().toList();
            }
        }
        return List.of();
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onCommandWhileInCombat(PlayerCommandPreprocessEvent event) {
        if (event.getPlayer().isOp()
                || event.getPlayer().hasPermission(COMMAND_BYPASS_PERMISSION)) {
            return;
        }
        Long end = combatEnds.get(event.getPlayer().getUniqueId());
        if (end == null) {
            return;
        }
        if (end <= System.currentTimeMillis()) {
            clearCombat(event.getPlayer().getUniqueId(), false);
            return;
        }

        String input = event.getMessage();
        int separator = input.indexOf(' ');
        String root = input.substring(1, separator < 0 ? input.length() : separator)
                .toLowerCase(Locale.ROOT);
        if (!commandWhitelist.contains(root)) {
            event.setCancelled(true);
            event.getPlayer().sendMessage(MessageStyle.prefixed(
                    "You cannot use that command while in combat."));
        }
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onPlayerDamage(EntityDamageByEntityEvent event) {
        Player attacker = attackingPlayer(event.getDamager());
        if (attacker == null || attacker.getGameMode() == GameMode.SPECTATOR) {
            return;
        }

        if (event.getEntity() instanceof Player victim) {
            if (attacker.equals(victim)) {
                return;
            }
            tagPlayer(attacker, victim.getUniqueId());
            tagPlayer(victim, attacker.getUniqueId());
            return;
        }

        UUID owner = activeLoggerOwner(event.getEntity());
        if (owner != null && !owner.equals(attacker.getUniqueId())) {
            tagPlayer(attacker, owner);
        }
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onPlayerDeath(PlayerDeathEvent event) {
        Player victim = event.getEntity();
        UUID opponent = combatOpponents.get(victim.getUniqueId());
        clearCombat(victim.getUniqueId(), true);
        Player killer = victim.getKiller();
        if (killer != null && !killer.equals(victim)) {
            clearCombat(killer.getUniqueId(), true);
        }
        if (opponent != null && (killer == null || !opponent.equals(killer.getUniqueId()))) {
            clearCombat(opponent, true);
        }
    }

    @EventHandler(priority = EventPriority.HIGHEST)
    public void onQuit(PlayerQuitEvent event) {
        Player player = event.getPlayer();
        Long end = combatEnds.remove(player.getUniqueId());
        combatOpponents.remove(player.getUniqueId());
        if (end == null || end <= System.currentTimeMillis()) {
            return;
        }
        createLogger(player);
    }

    @EventHandler(priority = EventPriority.HIGHEST)
    public void onJoin(PlayerJoinEvent event) {
        Player player = event.getPlayer();
        LoggerSession session = loggerSessions.remove(player.getUniqueId());
        combatEnds.remove(player.getUniqueId());
        combatOpponents.remove(player.getUniqueId());
        if (session == null) {
            return;
        }
        clearLoggerOpponents(player.getUniqueId());

        Zombie zombie = findZombie(session);
        if (!session.dead && zombie != null && !zombie.isDead()) {
            restoreFromLogger(player, zombie, session.snapshot);
            zombie.remove();
            player.sendMessage(MessageStyle.prefixed("You are no longer in combat."));
        } else {
            if (zombie != null) {
                zombie.remove();
            }
            clearPlayerAfterLoggerDeath(player);
            Location spawn = spawnManager.spawnLocation();
            if (spawn != null) {
                player.teleport(spawn);
            }
            statsManager.recordCombatLogDeath(player.getUniqueId(), player.getName());
            player.sendMessage(MessageStyle.prefixed("Your combat logger died. Your inventory has been lost."));
            player.sendMessage(MessageStyle.prefixed("You are no longer in combat."));
        }
        queueSave();
    }

    @EventHandler(priority = EventPriority.HIGHEST)
    public void onLoggerDeath(EntityDeathEvent event) {
        if (!(event.getEntity() instanceof Zombie zombie)) {
            return;
        }
        String rawOwner = zombie.getPersistentDataContainer().get(loggerKey, PersistentDataType.STRING);
        if (rawOwner == null) {
            return;
        }
        UUID owner;
        try {
            owner = UUID.fromString(rawOwner);
        } catch (IllegalArgumentException ignored) {
            return;
        }
        LoggerSession session = loggerSessions.get(owner);
        event.getDrops().clear();
        event.setDroppedExp(0);
        if (session == null || session.dead || session.zombieId == null
                || !session.zombieId.equals(zombie.getUniqueId().toString())) {
            return;
        }

        Player killer = zombie.getKiller();

        for (String encoded : session.snapshot.inventory) {
            ItemStack item = decodeItem(encoded);
            if (item != null && !item.getType().isAir()) {
                zombie.getWorld().dropItemNaturally(zombie.getLocation(), item);
            }
        }
        session.dead = true;
        session.zombieId = null;
        session.snapshot.inventory = new ArrayList<>();
        session.snapshot.location = SavedLocation.from(zombie.getLocation());
        clearLoggerOpponents(owner);
        if (killer != null) {
            tokenManager.awardKillToken(killer, owner);
        }
        queueSave();
    }

    @Override
    public void close() {
        if (closed) {
            return;
        }
        closed = true;
        if (ticker != null) {
            ticker.cancel();
        }
        if (saver != null) {
            saver.cancel();
        }
        refreshLoggerState();
        RootSnapshot finalSnapshot = snapshot();
        writer.submit(() -> write(finalSnapshot));
        writer.shutdown();
        try {
            if (!writer.awaitTermination(10L, TimeUnit.SECONDS)) {
                plugin.getLogger().warning("Timed out while saving combat.json.");
            }
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            plugin.getLogger().warning("Interrupted while saving combat.json.");
        }
    }

    private void createLogger(Player player) {
        PlayerSnapshot snapshot = PlayerSnapshot.capture(player);
        Zombie zombie = spawnLogger(player.getUniqueId(), player.getName(), snapshot);
        LoggerSession session = new LoggerSession();
        session.playerName = player.getName();
        session.zombieId = zombie.getUniqueId().toString();
        session.snapshot = snapshot;
        loggerSessions.put(player.getUniqueId(), session);

        player.getInventory().clear();
        player.getInventory().setArmorContents(new ItemStack[4]);
        player.getInventory().setItemInOffHand(new ItemStack(Material.AIR));
        queueSave();
    }

    private Zombie spawnLogger(UUID owner, String playerName, PlayerSnapshot snapshot) {
        Location location = snapshot.location.toLocation();
        World world = location.getWorld();
        if (world == null) {
            throw new IllegalStateException("Cannot spawn combat logger without its world");
        }
        Zombie zombie = world.spawn(location, Zombie.class);
        zombie.getPersistentDataContainer().set(loggerKey, PersistentDataType.STRING, owner.toString());
        zombie.customName(Component.text(playerName, NamedTextColor.GRAY));
        zombie.setCustomNameVisible(true);
        zombie.setPersistent(true);
        zombie.setRemoveWhenFarAway(false);
        zombie.setCanPickupItems(false);
        zombie.setShouldBurnInDay(false);
        configureLoggerBody(zombie);

        AttributeInstance maxHealth = zombie.getAttribute(Attribute.MAX_HEALTH);
        if (maxHealth != null) {
            maxHealth.setBaseValue(Math.max(1.0D, snapshot.maxHealth));
        }
        zombie.setHealth(Math.max(0.01D, Math.min(snapshot.health,
                maxHealth == null ? snapshot.maxHealth : maxHealth.getValue())));
        applyEffects(zombie, snapshot.effects);
        equipLogger(zombie, snapshot.decodedInventory(), snapshot.heldItemSlot);
        return zombie;
    }

    private void equipLogger(Zombie zombie, ItemStack[] contents, int heldItemSlot) {
        EntityEquipment equipment = zombie.getEquipment();
        if (equipment == null) {
            return;
        }
        equipment.setItemInMainHand(itemAt(contents, Math.max(0, Math.min(8, heldItemSlot))));
        equipment.setBoots(itemAt(contents, 36));
        equipment.setLeggings(itemAt(contents, 37));
        equipment.setChestplate(itemAt(contents, 38));
        equipment.setHelmet(itemAt(contents, 39));
        equipment.setItemInOffHand(itemAt(contents, 40));
        equipment.setItemInMainHandDropChance(0.0F);
        equipment.setItemInOffHandDropChance(0.0F);
        equipment.setBootsDropChance(0.0F);
        equipment.setLeggingsDropChance(0.0F);
        equipment.setChestplateDropChance(0.0F);
        equipment.setHelmetDropChance(0.0F);
    }

    private ItemStack itemAt(ItemStack[] contents, int index) {
        ItemStack item = index >= 0 && index < contents.length ? contents[index] : null;
        return item == null ? new ItemStack(Material.AIR) : item.clone();
    }

    private void restoreFromLogger(Player player, Zombie zombie, PlayerSnapshot snapshot) {
        Location location = zombie.getLocation().clone();
        double health = zombie.getHealth();
        List<SavedEffect> effects = SavedEffect.from(zombie.getActivePotionEffects());
        player.getInventory().setContents(snapshot.decodedInventory());
        player.getInventory().setHeldItemSlot(Math.max(0, Math.min(8, snapshot.heldItemSlot)));
        player.setLevel(snapshot.level);
        player.setExp(snapshot.exp);
        player.setFoodLevel(snapshot.foodLevel);
        player.setSaturation(snapshot.saturation);
        player.setExhaustion(snapshot.exhaustion);
        player.setFireTicks(snapshot.fireTicks);
        player.clearActivePotionEffects();
        applyEffects(player, effects);
        player.teleport(location);
        AttributeInstance maxHealth = player.getAttribute(Attribute.MAX_HEALTH);
        player.setHealth(Math.max(0.01D, Math.min(health,
                maxHealth == null ? 20.0D : maxHealth.getValue())));
        player.updateInventory();
    }

    private void clearPlayerAfterLoggerDeath(Player player) {
        player.getInventory().clear();
        player.getInventory().setArmorContents(new ItemStack[4]);
        player.getInventory().setItemInOffHand(new ItemStack(Material.AIR));
        player.clearActivePotionEffects();
        player.setFireTicks(0);
        player.setTotalExperience(0);
        player.setLevel(0);
        player.setExp(0.0F);
        player.setFoodLevel(20);
        player.setSaturation(5.0F);
        AttributeInstance maxHealth = player.getAttribute(Attribute.MAX_HEALTH);
        player.setHealth(maxHealth == null ? 20.0D : maxHealth.getValue());
        player.updateInventory();
    }

    private void tickCombat() {
        long now = System.currentTimeMillis();
        var iterator = combatEnds.entrySet().iterator();
        while (iterator.hasNext()) {
            Map.Entry<UUID, Long> entry = iterator.next();
            Player player = Bukkit.getPlayer(entry.getKey());
            long seconds = secondsRemaining(entry.getValue());
            if (seconds <= 0L) {
                iterator.remove();
                combatOpponents.remove(entry.getKey());
                if (player != null) {
                    player.sendActionBar(Component.empty());
                    player.sendMessage(MessageStyle.prefixed("You are no longer in combat."));
                }
            } else if (player != null) {
                showTimer(player, seconds);
            }
        }
    }

    private void showTimer(Player player, long seconds) {
        player.sendActionBar(Component.text("Combat Tag: ", NamedTextColor.GRAY)
                .append(Component.text(seconds, NamedTextColor.GREEN)));
    }

    private void tagPlayer(Player player, UUID opponent) {
        combatEnds.put(player.getUniqueId(), System.currentTimeMillis() + COMBAT_MILLIS);
        combatOpponents.put(player.getUniqueId(), opponent);
        showTimer(player, 60L);
    }

    private long secondsRemaining(long end) {
        return Math.max(0L, (end - System.currentTimeMillis() + 999L) / 1000L);
    }

    private void clearCombat(UUID playerId, boolean notify) {
        boolean removed = combatEnds.remove(playerId) != null;
        combatOpponents.remove(playerId);
        Player player = Bukkit.getPlayer(playerId);
        if (player != null && removed) {
            player.sendActionBar(Component.empty());
            if (notify) {
                player.sendMessage(MessageStyle.prefixed("You are no longer in combat."));
            }
        }
    }

    private Player attackingPlayer(Entity damager) {
        if (damager instanceof Player player) {
            return player;
        }
        if (damager instanceof Projectile projectile && projectile.getShooter() instanceof Player player) {
            return player;
        }
        return null;
    }

    private void forceTag(CommandSender sender, String[] args) {
        if (!sender.hasPermission(TAG_PERMISSION)) {
            sendPermissionDenied(sender, TAG_PERMISSION);
            return;
        }
        if (args.length != 2) {
            sender.sendMessage(MessageStyle.prefixed("Usage: /combat tag <player>"));
            return;
        }
        Player target = Bukkit.getPlayerExact(args[1]);
        if (target == null) {
            sender.sendMessage(MessageStyle.prefixed("Player " + args[1] + " is not online."));
            return;
        }
        combatEnds.put(target.getUniqueId(), System.currentTimeMillis() + COMBAT_MILLIS);
        combatOpponents.remove(target.getUniqueId());
        showTimer(target, 60L);
        sender.sendMessage(MessageStyle.prefix()
                .append(Component.text(target.getName(), NamedTextColor.GREEN))
                .append(Component.text(" is now in combat.", NamedTextColor.GRAY)));
    }

    private void forceUntag(CommandSender sender, String[] args) {
        if (!sender.hasPermission(UNTAG_PERMISSION)) {
            sendPermissionDenied(sender, UNTAG_PERMISSION);
            return;
        }
        if (args.length != 2) {
            sender.sendMessage(MessageStyle.prefixed("Usage: /combat untag <player>"));
            return;
        }
        Player target = Bukkit.getPlayerExact(args[1]);
        if (target == null) {
            sender.sendMessage(MessageStyle.prefixed("Player " + args[1] + " is not online."));
            return;
        }
        boolean tagged = combatEnds.containsKey(target.getUniqueId());
        clearCombat(target.getUniqueId(), true);
        sender.sendMessage(MessageStyle.prefix()
                .append(Component.text(target.getName(), NamedTextColor.GREEN))
                .append(Component.text(tagged ? " is no longer in combat." : " was not in combat.",
                        NamedTextColor.GRAY)));
    }

    private void handleCommandWhitelist(CommandSender sender, String[] args) {
        if (!sender.hasPermission(COMMAND_WHITELIST_PERMISSION)) {
            sendPermissionDenied(sender, COMMAND_WHITELIST_PERMISSION);
            return;
        }
        if (args.length == 2 && args[1].equalsIgnoreCase("list")) {
            String commands = commandWhitelist.stream().sorted().map(command -> "/" + command)
                    .collect(java.util.stream.Collectors.joining(", "));
            sender.sendMessage(MessageStyle.prefixed("Combat command whitelist: " + commands));
            return;
        }
        if (args.length != 3
                || (!args[1].equalsIgnoreCase("add") && !args[1].equalsIgnoreCase("delete"))) {
            sender.sendMessage(MessageStyle.prefixed(
                    "Usage: /combat commandwhitelist <add|delete> <command> or list."));
            return;
        }

        String command = args[2].toLowerCase(Locale.ROOT);
        if (!validRootCommand(command)) {
            sender.sendMessage(MessageStyle.prefixed(
                    "Enter one root command without / or spaces."));
            return;
        }
        if (args[1].equalsIgnoreCase("add")) {
            boolean added = commandWhitelist.add(command);
            sender.sendMessage(MessageStyle.prefixed(added
                    ? "/" + command + " is now allowed in combat."
                    : "/" + command + " is already allowed in combat."));
            if (added) {
                queueSave();
            }
            return;
        }
        if (command.equals("combat")) {
            sender.sendMessage(MessageStyle.prefixed(
                    "/combat must remain allowed so the combat timer can be checked."));
            return;
        }
        boolean removed = commandWhitelist.remove(command);
        sender.sendMessage(MessageStyle.prefixed(removed
                ? "/" + command + " is no longer allowed in combat."
                : "/" + command + " was not in the combat command whitelist."));
        if (removed) {
            queueSave();
        }
    }

    private boolean validRootCommand(String command) {
        if (command.isBlank() || command.indexOf('/') >= 0) {
            return false;
        }
        for (int index = 0; index < command.length(); index++) {
            char character = command.charAt(index);
            if (!Character.isLetterOrDigit(character) && character != '_' && character != '-'
                    && character != ':') {
                return false;
            }
        }
        return true;
    }

    private void sendHelp(CommandSender sender) {
        sender.sendMessage(Component.text("------ ", NamedTextColor.GRAY)
                .append(Component.text("Combat Help", NamedTextColor.GREEN))
                .append(Component.text(" ------", NamedTextColor.GRAY)));
        helpLine(sender, "/combat help", "The command to display helpful information.");
        helpLine(sender, "/combat timer", "Check the time left you have in combat.");
        if (sender.hasPermission(TAG_PERMISSION)) {
            helpLine(sender, "/combat tag <player>", "Force a player into combat.");
        }
        if (sender.hasPermission(UNTAG_PERMISSION)) {
            helpLine(sender, "/combat untag <player>", "Untag a player from combat.");
        }
        if (sender.hasPermission(COMMAND_WHITELIST_PERMISSION)) {
            helpLine(sender, "/combat commandwhitelist <add|delete|list>",
                    "Configure commands players may use in combat.");
        }
    }

    private void helpLine(CommandSender sender, String command, String description) {
        sender.sendMessage(Component.text("- ", NamedTextColor.GRAY)
                .append(Component.text(command, NamedTextColor.GREEN))
                .append(Component.text(" - " + description, NamedTextColor.GRAY)));
    }

    private void sendPermissionDenied(CommandSender sender, String permission) {
        sender.sendMessage(MessageStyle.permissionDenied(permission));
    }

    private void restoreLoggerEntities() {
        for (Map.Entry<UUID, LoggerSession> entry : loggerSessions.entrySet()) {
            LoggerSession session = entry.getValue();
            if (session.dead) {
                continue;
            }
            Zombie existing = findZombie(session);
            if (existing != null) {
                configureLoggerBody(existing);
                continue;
            }
            try {
                Zombie zombie = spawnLogger(entry.getKey(), session.playerName, session.snapshot);
                session.zombieId = zombie.getUniqueId().toString();
            } catch (RuntimeException exception) {
                plugin.getLogger().warning("Could not restore combat logger for "
                        + session.playerName + ": " + exception.getMessage());
            }
        }
        queueSave();
    }

    private void configureLoggerBody(Zombie zombie) {
        zombie.setAI(false);
        zombie.setGravity(true);
        AttributeInstance knockbackResistance = zombie.getAttribute(Attribute.KNOCKBACK_RESISTANCE);
        if (knockbackResistance != null) {
            knockbackResistance.setBaseValue(1.0D);
        }
    }

    private UUID activeLoggerOwner(Entity entity) {
        if (!(entity instanceof Zombie zombie)) {
            return null;
        }
        String rawOwner = zombie.getPersistentDataContainer().get(loggerKey, PersistentDataType.STRING);
        if (rawOwner == null) {
            return null;
        }
        try {
            UUID owner = UUID.fromString(rawOwner);
            LoggerSession session = loggerSessions.get(owner);
            return session != null && !session.dead && session.zombieId != null
                    && session.zombieId.equals(zombie.getUniqueId().toString()) ? owner : null;
        } catch (IllegalArgumentException ignored) {
            return null;
        }
    }

    private void clearLoggerOpponents(UUID owner) {
        List<UUID> attackers = combatOpponents.entrySet().stream()
                .filter(entry -> owner.equals(entry.getValue()))
                .map(Map.Entry::getKey)
                .toList();
        for (UUID attacker : attackers) {
            clearCombat(attacker, true);
        }
    }

    private Zombie findZombie(LoggerSession session) {
        if (session.zombieId == null) {
            return null;
        }
        try {
            Location location = session.snapshot.location.toLocation();
            if (location.getWorld() != null) {
                location.getChunk().load();
            }
            Entity entity = Bukkit.getEntity(UUID.fromString(session.zombieId));
            return entity instanceof Zombie zombie && !zombie.isDead() ? zombie : null;
        } catch (IllegalArgumentException ignored) {
            return null;
        }
    }

    private Zombie findLoadedZombie(LoggerSession session) {
        if (session.zombieId == null) {
            return null;
        }
        try {
            Entity entity = Bukkit.getEntity(UUID.fromString(session.zombieId));
            return entity instanceof Zombie zombie && !zombie.isDead() ? zombie : null;
        } catch (IllegalArgumentException ignored) {
            return null;
        }
    }

    private void refreshLoggerState() {
        for (LoggerSession session : loggerSessions.values()) {
            if (session.dead) {
                continue;
            }
            Zombie zombie = findLoadedZombie(session);
            if (zombie != null) {
                session.snapshot.location = SavedLocation.from(zombie.getLocation());
                session.snapshot.health = zombie.getHealth();
                AttributeInstance maxHealth = zombie.getAttribute(Attribute.MAX_HEALTH);
                session.snapshot.maxHealth = maxHealth == null ? 20.0D : maxHealth.getValue();
                session.snapshot.effects = SavedEffect.from(zombie.getActivePotionEffects());
            }
        }
    }

    private void queueSave() {
        if (closed) {
            return;
        }
        refreshLoggerState();
        RootSnapshot current = snapshot();
        writer.submit(() -> write(current));
    }

    private RootSnapshot snapshot() {
        RootSnapshot result = new RootSnapshot();
        combatEnds.forEach((uuid, end) -> result.tags.put(uuid.toString(), end));
        combatOpponents.forEach((uuid, opponent) -> result.opponents.put(uuid.toString(), opponent.toString()));
        loggerSessions.forEach((uuid, session) -> result.sessions.put(uuid.toString(), session.copy()));
        result.commandWhitelist = new ArrayList<>(commandWhitelist);
        return result;
    }

    private void load() {
        try {
            Files.createDirectories(dataFile.getParent());
            if (!Files.exists(dataFile)) {
                return;
            }
            RootSnapshot stored = gson.fromJson(Files.readString(dataFile, StandardCharsets.UTF_8), RootSnapshot.class);
            if (stored == null) {
                return;
            }
            if (stored.tags != null) {
                stored.tags.forEach((rawUuid, end) -> {
                    if (end != null) {
                        readUuid(rawUuid, uuid -> combatEnds.put(uuid, end));
                    }
                });
            }
            if (stored.opponents != null) {
                stored.opponents.forEach((rawUuid, rawOpponent) -> readUuid(rawUuid,
                        uuid -> readUuid(rawOpponent, opponent -> combatOpponents.put(uuid, opponent))));
            }
            if (stored.sessions != null) {
                stored.sessions.forEach((rawUuid, session) -> {
                    if (session != null && session.snapshot != null && session.snapshot.location != null) {
                        if (session.snapshot.inventory == null) {
                            session.snapshot.inventory = new ArrayList<>();
                        }
                        if (session.snapshot.effects == null) {
                            session.snapshot.effects = new ArrayList<>();
                        }
                        readUuid(rawUuid, uuid -> {
                            if (session.playerName == null || session.playerName.isBlank()) {
                                session.playerName = uuid.toString();
                            }
                            loggerSessions.put(uuid, session);
                        });
                    }
                });
            }
            if (stored.commandWhitelist != null) {
                commandWhitelist.clear();
                stored.commandWhitelist.stream()
                        .filter(java.util.Objects::nonNull)
                        .map(command -> command.toLowerCase(Locale.ROOT))
                        .filter(this::validRootCommand)
                        .forEach(commandWhitelist::add);
                commandWhitelist.add("combat");
            }
        } catch (IOException | RuntimeException exception) {
            plugin.getLogger().warning("Could not load combat.json: " + exception.getMessage());
        }
    }

    private void readUuid(String raw, java.util.function.Consumer<UUID> consumer) {
        try {
            consumer.accept(UUID.fromString(raw));
        } catch (RuntimeException ignored) {
            plugin.getLogger().warning("Ignored invalid UUID in combat.json: " + raw);
        }
    }

    private void write(RootSnapshot current) {
        Path temporary = dataFile.resolveSibling(dataFile.getFileName() + ".tmp");
        try {
            Files.createDirectories(dataFile.getParent());
            Files.writeString(temporary, gson.toJson(current), StandardCharsets.UTF_8);
            try {
                Files.move(temporary, dataFile, StandardCopyOption.REPLACE_EXISTING,
                        StandardCopyOption.ATOMIC_MOVE);
            } catch (AtomicMoveNotSupportedException ignored) {
                Files.move(temporary, dataFile, StandardCopyOption.REPLACE_EXISTING);
            }
        } catch (IOException exception) {
            plugin.getLogger().warning("Could not save combat.json: " + exception.getMessage());
        }
    }

    private static String encodeItem(ItemStack item) {
        if (item == null || item.getType().isAir()) {
            return null;
        }
        return Base64.getEncoder().encodeToString(item.serializeAsBytes());
    }

    private static ItemStack decodeItem(String encoded) {
        if (encoded == null) {
            return null;
        }
        try {
            return ItemStack.deserializeBytes(Base64.getDecoder().decode(encoded));
        } catch (IllegalArgumentException exception) {
            return null;
        }
    }

    private static void applyEffects(org.bukkit.entity.LivingEntity entity, List<SavedEffect> effects) {
        for (SavedEffect effect : effects) {
            if (effect == null) {
                continue;
            }
            PotionEffect decoded = effect.decode();
            if (decoded != null) {
                entity.addPotionEffect(decoded);
            }
        }
    }

    private static final class RootSnapshot {
        private Map<String, Long> tags = new LinkedHashMap<>();
        private Map<String, String> opponents = new LinkedHashMap<>();
        private Map<String, LoggerSession> sessions = new LinkedHashMap<>();
        private List<String> commandWhitelist;
    }

    private static final class LoggerSession {
        private String playerName;
        private String zombieId;
        private boolean dead;
        private PlayerSnapshot snapshot;

        private LoggerSession copy() {
            LoggerSession copy = new LoggerSession();
            copy.playerName = playerName;
            copy.zombieId = zombieId;
            copy.dead = dead;
            copy.snapshot = snapshot.copy();
            return copy;
        }
    }

    private static final class PlayerSnapshot {
        private SavedLocation location;
        private double health;
        private double maxHealth;
        private List<String> inventory = new ArrayList<>();
        private List<SavedEffect> effects = new ArrayList<>();
        private int level;
        private int heldItemSlot;
        private float exp;
        private int foodLevel;
        private float saturation;
        private float exhaustion;
        private int fireTicks;

        private static PlayerSnapshot capture(Player player) {
            PlayerSnapshot result = new PlayerSnapshot();
            result.location = SavedLocation.from(player.getLocation());
            result.health = player.getHealth();
            AttributeInstance maxHealth = player.getAttribute(Attribute.MAX_HEALTH);
            result.maxHealth = maxHealth == null ? 20.0D : maxHealth.getValue();
            for (ItemStack item : player.getInventory().getContents()) {
                result.inventory.add(encodeItem(item));
            }
            result.effects = SavedEffect.from(player.getActivePotionEffects());
            result.level = player.getLevel();
            result.heldItemSlot = player.getInventory().getHeldItemSlot();
            result.exp = player.getExp();
            result.foodLevel = player.getFoodLevel();
            result.saturation = player.getSaturation();
            result.exhaustion = player.getExhaustion();
            result.fireTicks = player.getFireTicks();
            return result;
        }

        private ItemStack[] decodedInventory() {
            ItemStack[] result = new ItemStack[inventory.size()];
            for (int index = 0; index < inventory.size(); index++) {
                result[index] = decodeItem(inventory.get(index));
            }
            return result;
        }

        private PlayerSnapshot copy() {
            PlayerSnapshot copy = new PlayerSnapshot();
            copy.location = location.copy();
            copy.health = health;
            copy.maxHealth = maxHealth;
            copy.inventory = new ArrayList<>(inventory);
            copy.effects = effects.stream().map(SavedEffect::copy).toList();
            copy.level = level;
            copy.heldItemSlot = heldItemSlot;
            copy.exp = exp;
            copy.foodLevel = foodLevel;
            copy.saturation = saturation;
            copy.exhaustion = exhaustion;
            copy.fireTicks = fireTicks;
            return copy;
        }
    }

    private static final class SavedLocation {
        private String world;
        private double x;
        private double y;
        private double z;
        private float yaw;
        private float pitch;

        private static SavedLocation from(Location location) {
            SavedLocation result = new SavedLocation();
            result.world = location.getWorld().getUID().toString();
            result.x = location.getX();
            result.y = location.getY();
            result.z = location.getZ();
            result.yaw = location.getYaw();
            result.pitch = location.getPitch();
            return result;
        }

        private Location toLocation() {
            World found = null;
            try {
                if (world == null) {
                    return new Location(null, x, y, z, yaw, pitch);
                }
                found = Bukkit.getWorld(UUID.fromString(world));
            } catch (IllegalArgumentException ignored) {
                // The warning is emitted by the caller if this session cannot be restored.
            }
            return new Location(found, x, y, z, yaw, pitch);
        }

        private SavedLocation copy() {
            SavedLocation copy = new SavedLocation();
            copy.world = world;
            copy.x = x;
            copy.y = y;
            copy.z = z;
            copy.yaw = yaw;
            copy.pitch = pitch;
            return copy;
        }
    }

    private static final class SavedEffect {
        private String type;
        private int duration;
        private int amplifier;
        private boolean ambient;
        private boolean particles;
        private boolean icon;

        private static List<SavedEffect> from(Collection<PotionEffect> effects) {
            List<SavedEffect> result = new ArrayList<>();
            for (PotionEffect effect : effects) {
                SavedEffect saved = new SavedEffect();
                saved.type = effect.getType().getKey().toString();
                saved.duration = effect.getDuration();
                saved.amplifier = effect.getAmplifier();
                saved.ambient = effect.isAmbient();
                saved.particles = effect.hasParticles();
                saved.icon = effect.hasIcon();
                result.add(saved);
            }
            return result;
        }

        private PotionEffect decode() {
            if (type == null) {
                return null;
            }
            NamespacedKey key = NamespacedKey.fromString(type);
            PotionEffectType effectType = key == null ? null : Registry.MOB_EFFECT.get(key);
            return effectType == null ? null
                    : new PotionEffect(effectType, duration, amplifier, ambient, particles, icon);
        }

        private SavedEffect copy() {
            SavedEffect copy = new SavedEffect();
            copy.type = type;
            copy.duration = duration;
            copy.amplifier = amplifier;
            copy.ambient = ambient;
            copy.particles = particles;
            copy.icon = icon;
            return copy;
        }
    }
}
