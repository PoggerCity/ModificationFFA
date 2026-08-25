package me.poggercity.modificationFFA;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonParseException;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.EntityType;
import org.bukkit.entity.Player;
import org.bukkit.entity.Projectile;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.BlockPlaceEvent;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.event.entity.PlayerDeathEvent;
import org.bukkit.event.entity.ProjectileLaunchEvent;
import org.bukkit.event.player.PlayerAnimationEvent;
import org.bukkit.event.player.PlayerAnimationType;
import org.bukkit.event.player.PlayerItemConsumeEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.scheduler.BukkitTask;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.stream.Stream;

final class StatsManager implements Listener, AutoCloseable {

    private static final long SAVE_INTERVAL_TICKS = 20L * 60L;

    private final JavaPlugin plugin;
    private final SettingsManager settingsManager;
    private final Path statsFile;
    private final Gson gson = new GsonBuilder().setPrettyPrinting().create();
    private final Map<UUID, PlayerStats> stats = new HashMap<>();
    private final ExecutorService writer;
    private final AtomicBoolean saveInFlight = new AtomicBoolean();
    private final AtomicLong mutationVersion = new AtomicLong();
    private final AtomicLong persistedVersion = new AtomicLong();

    private BukkitTask saveTask;
    private boolean closed;

    StatsManager(JavaPlugin plugin, SettingsManager settingsManager) {
        this.plugin = plugin;
        this.settingsManager = settingsManager;
        this.statsFile = plugin.getDataFolder().toPath().resolve("stats.json");
        this.writer = Executors.newSingleThreadExecutor(runnable -> {
            Thread thread = new Thread(runnable, "ModificationFFA-Stats-Writer");
            thread.setDaemon(true);
            return thread;
        });
    }

    void start() {
        load();
        saveTask = Bukkit.getScheduler().runTaskTimer(plugin, this::queueSave,
                SAVE_INTERVAL_TICKS, SAVE_INTERVAL_TICKS);
    }

    boolean handleCommand(CommandSender sender, String[] args) {
        if (args.length > 1) {
            sender.sendMessage(MessageStyle.prefixedMessage("stats.usage"));
            return true;
        }

        UUID targetId;
        String targetName;
        if (args.length == 0) {
            if (!(sender instanceof Player player)) {
                sender.sendMessage(MessageStyle.prefixedMessage("stats.console-usage"));
                return true;
            }
            targetId = player.getUniqueId();
            targetName = player.getName();
            updateName(player);
        } else {
            Player online = Bukkit.getPlayerExact(args[0]);
            if (online != null) {
                targetId = online.getUniqueId();
                targetName = online.getName();
                updateName(online);
            } else {
                Map.Entry<UUID, PlayerStats> known = findKnownPlayer(args[0]);
                if (known == null) {
                    sender.sendMessage(MessageStyle.prefixedMessage(
                            "stats.not-recorded", Map.of("player", args[0])));
                    return true;
                }
                targetId = known.getKey();
                targetName = known.getValue().lastKnownName;
            }
        }

        if (sender instanceof Player viewer
                && !viewer.getUniqueId().equals(targetId)
                && settingsManager.hideStatsEnabled(targetId)
                && !viewer.hasPermission("modificationffa.stats.view-hidden")) {
            sender.sendMessage(MessageStyle.prefixedMessage(
                    "stats.hidden", Map.of("player", targetName)));
            return true;
        }

        display(sender, targetName, stats.computeIfAbsent(targetId, ignored -> new PlayerStats(targetName)));
        return true;
    }

    List<String> tabComplete(String[] args) {
        if (args.length != 1) {
            return List.of();
        }
        String partial = args[0].toLowerCase(Locale.ROOT);
        return Stream.concat(
                        Bukkit.getOnlinePlayers().stream().map(Player::getName),
                        stats.values().stream().map(value -> value.lastKnownName))
                .filter(name -> name != null && name.toLowerCase(Locale.ROOT).startsWith(partial))
                .distinct()
                .sorted(String.CASE_INSENSITIVE_ORDER)
                .toList();
    }

    void recordCombatLogDeath(UUID playerId, String lastKnownName) {
        PlayerStats value = stats.computeIfAbsent(playerId, ignored -> new PlayerStats(lastKnownName));
        value.lastKnownName = lastKnownName;
        value.deaths++;
        value.killstreak = 0;
        changed();
        queueSave();
    }

    @EventHandler
    public void onJoin(PlayerJoinEvent event) {
        updateName(event.getPlayer());
    }

    @EventHandler(ignoreCancelled = true)
    public void onCobwebPlace(BlockPlaceEvent event) {
        if (event.getBlockPlaced().getType() == Material.COBWEB) {
            edit(event.getPlayer()).cobwebs++;
            changed();
        }
    }

    @EventHandler(ignoreCancelled = true)
    public void onProjectileLaunch(ProjectileLaunchEvent event) {
        Projectile projectile = event.getEntity();
        if (!(projectile.getShooter() instanceof Player player)) {
            return;
        }
        if (projectile.getType() == EntityType.ENDER_PEARL) {
            edit(player).pearls++;
            changed();
        } else if (projectile.getType() == EntityType.EXPERIENCE_BOTTLE) {
            edit(player).experienceBottles++;
            changed();
        }
    }

    @EventHandler(ignoreCancelled = true)
    public void onConsume(PlayerItemConsumeEvent event) {
        PlayerStats value = edit(event.getPlayer());
        switch (event.getItem().getType()) {
            case GOLDEN_APPLE -> value.goldenApples++;
            case ENCHANTED_GOLDEN_APPLE -> value.enchantedGoldenApples++;
            case CHORUS_FRUIT -> value.chorusFruit++;
            default -> {
                return;
            }
        }
        changed();
    }

    @EventHandler(ignoreCancelled = true)
    public void onArmSwing(PlayerAnimationEvent event) {
        if (event.getAnimationType() == PlayerAnimationType.ARM_SWING) {
            edit(event.getPlayer()).swings++;
            changed();
        }
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onMeleeHit(EntityDamageByEntityEvent event) {
        if (!(event.getDamager() instanceof Player attacker) || !(event.getEntity() instanceof Player)) {
            return;
        }
        if (event.getCause() != org.bukkit.event.entity.EntityDamageEvent.DamageCause.ENTITY_ATTACK
                && event.getCause() != org.bukkit.event.entity.EntityDamageEvent.DamageCause.ENTITY_SWEEP_ATTACK) {
            return;
        }
        edit(attacker).hits++;
        changed();
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onPlayerDeath(PlayerDeathEvent event) {
        Player victim = event.getEntity();
        Player killer = victim.getKiller();
        PlayerStats victimStats = edit(victim);
        victimStats.deaths++;
        victimStats.killstreak = 0;

        if (killer != null && !killer.equals(victim)) {
            PlayerStats killerStats = edit(killer);
            killerStats.kills++;
            killerStats.killstreak++;
            killerStats.bestKillstreak = Math.max(killerStats.bestKillstreak, killerStats.killstreak);
        }
        changed();
    }

    @Override
    public void close() {
        if (closed) {
            return;
        }
        closed = true;
        if (saveTask != null) {
            saveTask.cancel();
            saveTask = null;
        }

        Snapshot finalSnapshot = snapshot();
        writer.submit(() -> write(finalSnapshot));
        writer.shutdown();
        try {
            if (!writer.awaitTermination(10, TimeUnit.SECONDS)) {
                plugin.getLogger().warning("Timed out while saving player stats.");
            }
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            plugin.getLogger().warning("Interrupted while saving player stats.");
        }
    }

    private void display(CommandSender sender, String name, PlayerStats value) {
        sender.sendMessage(MessageStyle.message("stats.title", Map.of("player", name)));
        line(sender, "kills", Long.toString(value.kills));
        line(sender, "deaths", Long.toString(value.deaths));
        line(sender, "kd-ratio", ratio(value.kills, value.deaths, false));
        line(sender, "killstreak", Long.toString(value.killstreak));
        line(sender, "best-killstreak", Long.toString(value.bestKillstreak));
        line(sender, "cobwebs", Long.toString(value.cobwebs));
        line(sender, "pearls", Long.toString(value.pearls));
        line(sender, "golden-apples", Long.toString(value.goldenApples));
        line(sender, "enchanted-golden-apples", Long.toString(value.enchantedGoldenApples));
        line(sender, "chorus", Long.toString(value.chorusFruit));
        line(sender, "experience-bottles", Long.toString(value.experienceBottles));
        line(sender, "swings", Long.toString(value.swings));
        line(sender, "hits", Long.toString(value.hits));
        line(sender, "accuracy", ratio(value.hits, value.swings, true));
    }

    private void line(CommandSender sender, String key, String value) {
        sender.sendMessage(MessageStyle.message(
                "stats.lines." + key, Map.of("value", value)));
    }

    private String ratio(long numerator, long denominator, boolean percentage) {
        if (denominator == 0) {
            return "Infinite";
        }
        double result = (double) numerator / denominator * (percentage ? 100.0 : 1.0);
        return String.format(Locale.ROOT, "%.2f%s", result, percentage ? "%" : "");
    }

    private PlayerStats edit(Player player) {
        PlayerStats value = stats.computeIfAbsent(player.getUniqueId(), ignored -> new PlayerStats(player.getName()));
        value.lastKnownName = player.getName();
        return value;
    }

    private void updateName(Player player) {
        PlayerStats value = stats.computeIfAbsent(player.getUniqueId(), ignored -> new PlayerStats(player.getName()));
        if (!player.getName().equals(value.lastKnownName)) {
            value.lastKnownName = player.getName();
            changed();
        }
    }

    private Map.Entry<UUID, PlayerStats> findKnownPlayer(String name) {
        return stats.entrySet().stream()
                .filter(entry -> entry.getValue().lastKnownName != null
                        && entry.getValue().lastKnownName.equalsIgnoreCase(name))
                .findFirst()
                .orElse(null);
    }

    private void changed() {
        mutationVersion.incrementAndGet();
    }

    private void queueSave() {
        long version = mutationVersion.get();
        if (version == persistedVersion.get() || !saveInFlight.compareAndSet(false, true)) {
            return;
        }
        Snapshot current = snapshot();
        writer.submit(() -> {
            try {
                if (write(current)) {
                    persistedVersion.accumulateAndGet(version, Math::max);
                }
            } finally {
                saveInFlight.set(false);
            }
        });
    }

    private Snapshot snapshot() {
        Map<String, PlayerStats> copy = new LinkedHashMap<>();
        stats.forEach((uuid, value) -> copy.put(uuid.toString(), value.copy()));
        return new Snapshot(copy);
    }

    private void load() {
        try {
            Files.createDirectories(statsFile.getParent());
            if (!Files.exists(statsFile)) {
                return;
            }
            Snapshot stored = gson.fromJson(Files.readString(statsFile, StandardCharsets.UTF_8), Snapshot.class);
            if (stored == null || stored.players == null) {
                return;
            }
            stored.players.forEach((uuid, value) -> {
                try {
                    if (value != null) {
                        stats.put(UUID.fromString(uuid), value);
                    }
                } catch (IllegalArgumentException ignored) {
                    plugin.getLogger().warning("Ignored an invalid player UUID in stats.json: " + uuid);
                }
            });
        } catch (IOException | JsonParseException exception) {
            plugin.getLogger().warning("Could not load stats.json: " + exception.getMessage());
        }
    }

    private boolean write(Snapshot current) {
        Path temporary = statsFile.resolveSibling(statsFile.getFileName() + ".tmp");
        try {
            Files.createDirectories(statsFile.getParent());
            Files.writeString(temporary, gson.toJson(current), StandardCharsets.UTF_8);
            try {
                Files.move(temporary, statsFile, StandardCopyOption.REPLACE_EXISTING,
                        StandardCopyOption.ATOMIC_MOVE);
            } catch (AtomicMoveNotSupportedException ignored) {
                Files.move(temporary, statsFile, StandardCopyOption.REPLACE_EXISTING);
            }
            return true;
        } catch (IOException exception) {
            plugin.getLogger().warning("Could not save stats.json: " + exception.getMessage());
            return false;
        }
    }

    private static final class Snapshot {
        private Map<String, PlayerStats> players;

        private Snapshot(Map<String, PlayerStats> players) {
            this.players = players;
        }
    }

    private static final class PlayerStats {
        private String lastKnownName;
        private long kills;
        private long deaths;
        private long killstreak;
        private long bestKillstreak;
        private long cobwebs;
        private long pearls;
        private long goldenApples;
        private long enchantedGoldenApples;
        private long chorusFruit;
        private long experienceBottles;
        private long swings;
        private long hits;

        private PlayerStats(String lastKnownName) {
            this.lastKnownName = lastKnownName;
        }

        private PlayerStats copy() {
            PlayerStats copy = new PlayerStats(lastKnownName);
            copy.kills = kills;
            copy.deaths = deaths;
            copy.killstreak = killstreak;
            copy.bestKillstreak = bestKillstreak;
            copy.cobwebs = cobwebs;
            copy.pearls = pearls;
            copy.goldenApples = goldenApples;
            copy.enchantedGoldenApples = enchantedGoldenApples;
            copy.chorusFruit = chorusFruit;
            copy.experienceBottles = experienceBottles;
            copy.swings = swings;
            copy.hits = hits;
            return copy;
        }
    }
}
