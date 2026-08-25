package me.poggercity.modificationFFA;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import net.kyori.adventure.key.Key;
import net.kyori.adventure.sound.Sound;
import net.kyori.adventure.text.Component;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.EntityDamageEvent;
import org.bukkit.event.entity.PlayerDeathEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerMoveEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.event.player.PlayerRespawnEvent;
import org.bukkit.event.player.PlayerTeleportEvent;
import org.bukkit.scheduler.BukkitTask;

import java.io.IOException;
import java.io.Reader;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.HashMap;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.logging.Level;

final class SpawnManager implements Listener, AutoCloseable {

    private static final String SET_SPAWN_PERMISSION = "modificationffa.spawn.set";
    private static final int COUNTDOWN_SECONDS = 5;
    private static final double MOVE_CANCEL_DISTANCE_SQUARED = 0.01D;
    private static final Sound COUNTDOWN_SOUND = Sound.sound(
            Key.key("minecraft:block.note_block.guitar"),
            Sound.Source.MASTER,
            1.0F,
            1.0F
    );

    private final ModificationFFA plugin;
    private final PluginMessages messages;
    private final Path dataFile;
    private final Gson gson = new GsonBuilder().disableHtmlEscaping().create();
    private final Map<UUID, Countdown> countdowns = new HashMap<>();

    private StoredSpawn storedSpawn;

    SpawnManager(ModificationFFA plugin, PluginMessages messages) {
        this.plugin = Objects.requireNonNull(plugin, "plugin");
        this.messages = Objects.requireNonNull(messages, "messages");
        this.dataFile = plugin.getDataFolder().toPath().resolve("spawn.json");
    }

    void start() {
        loadSpawn();
    }

    Location spawnLocation() {
        Location spawn = resolveSpawn();
        return spawn == null ? null : spawn.clone();
    }

    boolean handleSpawn(CommandSender sender, String[] args) {
        if (args.length != 0) {
            messages.sendPrefixed(sender, "spawn.usage.spawn");
            return true;
        }
        if (!(sender instanceof Player player)) {
            messages.sendPrefixed(sender, "core.players-only");
            return true;
        }

        Location spawn = resolveSpawn();
        if (spawn == null) {
            messages.sendPrefixed(player, "spawn.not-set");
            return true;
        }
        if (countdowns.containsKey(player.getUniqueId())) {
            messages.sendPrefixed(player, "spawn.already-teleporting");
            return true;
        }

        Countdown countdown = new Countdown(player.getLocation().clone(), COUNTDOWN_SECONDS);
        countdowns.put(player.getUniqueId(), countdown);
        showCountdown(player, countdown.remainingSeconds);
        countdown.task = Bukkit.getScheduler().runTaskTimer(
                plugin,
                () -> tickCountdown(player.getUniqueId(), countdown),
                20L,
                20L
        );
        return true;
    }

    boolean handleSetSpawn(CommandSender sender, String[] args) {
        if (!sender.hasPermission(SET_SPAWN_PERMISSION)) {
            messages.sendPrefixed(sender, "core.no-permission",
                    Map.of("permission", SET_SPAWN_PERMISSION));
            return true;
        }
        if (args.length != 0) {
            messages.sendPrefixed(sender, "spawn.usage.setspawn");
            return true;
        }
        if (!(sender instanceof Player player)) {
            messages.sendPrefixed(sender, "core.players-only");
            return true;
        }

        Location location = player.getLocation();
        StoredSpawn previous = storedSpawn;
        storedSpawn = StoredSpawn.from(location);
        try {
            saveSpawn();
            messages.sendPrefixed(player, "spawn.set");
        } catch (IOException exception) {
            storedSpawn = previous;
            plugin.getLogger().log(Level.SEVERE, "Could not save spawn.json", exception);
            messages.sendPrefixed(player, "spawn.save-failed");
        }
        return true;
    }

    @EventHandler(priority = EventPriority.HIGHEST)
    public void onFirstJoin(PlayerJoinEvent event) {
        Player player = event.getPlayer();
        if (player.hasPlayedBefore()) {
            return;
        }
        Location spawn = resolveSpawn();
        if (spawn != null) {
            player.teleport(spawn);
        }
    }

    @EventHandler(priority = EventPriority.HIGHEST)
    public void onRespawn(PlayerRespawnEvent event) {
        Location spawn = resolveSpawn();
        if (spawn != null) {
            event.setRespawnLocation(spawn);
        }
    }

    @EventHandler(ignoreCancelled = true)
    public void onMove(PlayerMoveEvent event) {
        if (event instanceof PlayerTeleportEvent || event.getTo() == null) {
            return;
        }
        Countdown countdown = countdowns.get(event.getPlayer().getUniqueId());
        if (countdown == null || !sameWorld(countdown.start, event.getTo())) {
            return;
        }
        if (countdown.start.distanceSquared(event.getTo()) >= MOVE_CANCEL_DISTANCE_SQUARED) {
            cancelCountdown(event.getPlayer(), true);
        }
    }

    @EventHandler(ignoreCancelled = true)
    public void onTeleport(PlayerTeleportEvent event) {
        cancelCountdown(event.getPlayer(), true);
    }

    @EventHandler(ignoreCancelled = true)
    public void onDamage(EntityDamageEvent event) {
        if (event.getEntity() instanceof Player player) {
            cancelCountdown(player, true);
        }
    }

    @EventHandler
    public void onDeath(PlayerDeathEvent event) {
        cancelCountdown(event.getPlayer(), true);
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent event) {
        cancelCountdown(event.getPlayer(), false);
    }

    @Override
    public void close() {
        for (Countdown countdown : countdowns.values()) {
            if (countdown.task != null) {
                countdown.task.cancel();
            }
        }
        countdowns.clear();
    }

    private void tickCountdown(UUID playerId, Countdown countdown) {
        if (countdowns.get(playerId) != countdown) {
            cancelTask(countdown);
            return;
        }

        Player player = Bukkit.getPlayer(playerId);
        if (player == null || !player.isOnline() || player.isDead()) {
            countdowns.remove(playerId, countdown);
            cancelTask(countdown);
            return;
        }

        countdown.remainingSeconds--;
        if (countdown.remainingSeconds > 0) {
            showCountdown(player, countdown.remainingSeconds);
            return;
        }

        countdowns.remove(playerId, countdown);
        cancelTask(countdown);
        Location spawn = resolveSpawn();
        if (spawn == null) {
            player.sendActionBar(Component.empty());
            messages.sendPrefixed(player, "spawn.not-set");
            return;
        }

        player.teleport(spawn);
        player.sendActionBar(Component.empty());
        messages.sendPrefixed(player, "spawn.teleported");
    }

    private void showCountdown(Player player, int seconds) {
        player.sendActionBar(messages.component("spawn.countdown", Map.of("seconds", seconds)));
        player.playSound(COUNTDOWN_SOUND);
    }

    private void cancelCountdown(Player player, boolean notify) {
        Countdown countdown = countdowns.remove(player.getUniqueId());
        if (countdown == null) {
            return;
        }
        cancelTask(countdown);
        player.sendActionBar(Component.empty());
        if (notify) {
            messages.sendPrefixed(player, "spawn.cancelled");
        }
    }

    private void cancelTask(Countdown countdown) {
        if (countdown.task != null) {
            countdown.task.cancel();
            countdown.task = null;
        }
    }

    private Location resolveSpawn() {
        if (storedSpawn == null) {
            return null;
        }
        World world = Bukkit.getWorld(storedSpawn.world);
        if (world == null) {
            return null;
        }
        return new Location(
                world,
                storedSpawn.x,
                storedSpawn.y,
                storedSpawn.z,
                storedSpawn.yaw,
                storedSpawn.pitch
        );
    }

    private boolean sameWorld(Location first, Location second) {
        return first.getWorld() != null && first.getWorld().equals(second.getWorld());
    }

    private void loadSpawn() {
        if (!Files.exists(dataFile)) {
            return;
        }
        try (Reader reader = Files.newBufferedReader(dataFile, StandardCharsets.UTF_8)) {
            StoredSpawn loaded = gson.fromJson(reader, StoredSpawn.class);
            if (loaded == null || loaded.world == null || loaded.world.isBlank()) {
                throw new IOException("spawn.json does not contain a valid world");
            }
            storedSpawn = loaded;
        } catch (IOException | RuntimeException exception) {
            plugin.getLogger().log(Level.SEVERE, "Could not load spawn.json", exception);
        }
    }

    private void saveSpawn() throws IOException {
        Files.createDirectories(dataFile.getParent());
        Path temporary = dataFile.resolveSibling(dataFile.getFileName() + ".tmp");
        try (Writer writer = Files.newBufferedWriter(temporary, StandardCharsets.UTF_8)) {
            gson.toJson(storedSpawn, writer);
        }
        try {
            Files.move(temporary, dataFile, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        } catch (AtomicMoveNotSupportedException exception) {
            Files.move(temporary, dataFile, StandardCopyOption.REPLACE_EXISTING);
        }
    }

    private static final class Countdown {
        private final Location start;
        private int remainingSeconds;
        private BukkitTask task;

        private Countdown(Location start, int remainingSeconds) {
            this.start = start;
            this.remainingSeconds = remainingSeconds;
        }
    }

    private static final class StoredSpawn {
        private String world;
        private double x;
        private double y;
        private double z;
        private float yaw;
        private float pitch;

        private static StoredSpawn from(Location location) {
            StoredSpawn stored = new StoredSpawn();
            stored.world = location.getWorld().getName();
            stored.x = location.getX();
            stored.y = location.getY();
            stored.z = location.getZ();
            stored.yaw = location.getYaw();
            stored.pitch = location.getPitch();
            return stored;
        }
    }
}
