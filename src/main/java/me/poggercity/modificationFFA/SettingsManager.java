package me.poggercity.modificationFFA;

import com.google.gson.Gson;
import com.google.gson.JsonParseException;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.format.TextColor;
import net.kyori.adventure.text.format.TextDecoration;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.WeatherType;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.HandlerList;
import org.bukkit.event.Listener;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryCloseEvent;
import org.bukkit.event.inventory.InventoryDragEvent;
import org.bukkit.event.entity.EntityPotionEffectEvent;
import org.bukkit.event.player.PlayerBucketEmptyEvent;
import org.bukkit.event.player.PlayerBucketFillEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.inventory.meta.SkullMeta;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.potion.PotionEffectType;
import org.bukkit.scheduler.BukkitTask;
import org.jetbrains.annotations.NotNull;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

/** Stores per-player interface preferences and owns the Modification Settings GUI. */
final class SettingsManager implements Listener, AutoCloseable {

    static final String BROADCAST_PERMISSION = "modificationffa.gui.settings.broadcast";
    static final String CONNECTION_PERMISSION = "modificationffa.gui.settings.connection-messages";
    static final String MESSAGE_SOUND_PERMISSION = "modificationffa.gui.settings.message-sound";
    static final String PROTECTION_PERMISSION = "modificationffa.gui.settings.protection";
    static final String EXPLOSION_PERMISSION = "modificationffa.gui.settings.explosion";
    static final String HIDE_STATS_PERMISSION = "modificationffa.gui.settings.hide-stats";
    static final String KIT_SAFETY_PERMISSION = "modificationffa.gui.settings.kit-safety";
    static final String INVISIBILITY_PERMISSION = "modificationffa.gui.settings.invisibility";
    static final String PERSONAL_TIME_PERMISSION = "modificationffa.gui.settings.personal-time";
    static final String PERSONAL_WEATHER_PERMISSION = "modificationffa.gui.settings.personal-weather";
    static final String EXECUTIONER_TOKEN_PERMISSION = "modificationffa.gui.settings.executioner-token";
    static final String ANTI_GHOST_WATER_PERMISSION = "modificationffa.gui.settings.anti-ghost-water";
    static final String STAFF_PERMISSION = "modificationffa.gui.settings.staff";

    private static final int GUI_SIZE = 27;
    private static final int OWNER_SLOT = 4;
    private static final int PREVIOUS_PAGE_SLOT = 9;
    private static final int NEXT_PAGE_SLOT = 17;
    private static final int STAFF_SLOT = 22;
    private static final int ANIMATION_FRAME_COUNT = 120;
    private static final long SAVE_DEBOUNCE_TICKS = 40L;
    private final ModificationFFA plugin;
    private final Path settingsFile;
    private final NamespacedKey animatedNameKey;
    private final Gson gson = new Gson();
    private final Map<UUID, PlayerSettings> settings = new HashMap<>();
    private final Map<UUID, SettingsHolder> openSettings = new HashMap<>();
    private final Map<String, List<Component>> animatedNameFrames = new HashMap<>();
    private final Set<UUID> pendingBucketResyncs = new HashSet<>();
    private final ExecutorService writer;

    private BukkitTask saveTask;
    private BukkitTask animationTask;
    private int animationFrame;
    private boolean started;
    private boolean closed;

    SettingsManager(ModificationFFA plugin) {
        this.plugin = plugin;
        this.settingsFile = plugin.getDataFolder().toPath().resolve("settings.json");
        this.animatedNameKey = new NamespacedKey(plugin, "settings_animated_name");
        this.writer = Executors.newSingleThreadExecutor(runnable -> {
            Thread thread = new Thread(runnable, "ModificationFFA-Settings-Writer");
            thread.setDaemon(true);
            return thread;
        });
    }

    void start() {
        if (started) {
            return;
        }
        started = true;
        load();
        plugin.getServer().getPluginManager().registerEvents(this, plugin);
        animationTask = Bukkit.getScheduler().runTaskTimer(
                plugin, this::animateOpenSettings, 1L, 1L);
        for (Player player : Bukkit.getOnlinePlayers()) {
            applyPersonalEnvironment(player);
            enforceInvisibilitySetting(player);
        }
    }

    boolean open(CommandSender sender) {
        if (!(sender instanceof Player player)) {
            sender.sendMessage(MessageStyle.prefixed("This command can only be used by players."));
            return true;
        }
        openPage(player, 1);
        return true;
    }

    boolean broadcastTitlesEnabled(Player player) {
        return permittedAndEnabled(player, BROADCAST_PERMISSION,
                value(player.getUniqueId()).broadcastTitles, true);
    }

    boolean connectionMessagesEnabled(Player player) {
        return permittedAndEnabled(player, CONNECTION_PERMISSION,
                value(player.getUniqueId()).connectionMessages, true);
    }

    boolean messageSoundEnabled(Player player) {
        return permittedAndEnabled(player, MESSAGE_SOUND_PERMISSION,
                value(player.getUniqueId()).messageSound, true);
    }

    boolean protectionShiftClickEnabled(Player player) {
        return permittedAndEnabled(player, PROTECTION_PERMISSION,
                value(player.getUniqueId()).protectionShiftClick, true);
    }

    boolean explosionShiftClickEnabled(Player player) {
        return permittedAndEnabled(player, EXPLOSION_PERMISSION,
                value(player.getUniqueId()).explosionShiftClick, true);
    }

    boolean hideStatsEnabled(UUID playerId) {
        PlayerSettings stored = settings.get(playerId);
        boolean enabled = stored != null && bool(stored.hideStats, false);
        Player online = Bukkit.getPlayer(playerId);
        return enabled && (online == null || online.hasPermission(HIDE_STATS_PERMISSION));
    }

    boolean kitSafetyEnabled(Player player) {
        return bool(value(player.getUniqueId()).kitSafety, true);
    }

    boolean invisibilityEnabled(Player player) {
        return permittedAndEnabled(player, INVISIBILITY_PERMISSION,
                value(player.getUniqueId()).invisibility, true);
    }

    boolean executionerTokenEnabled(Player player) {
        return permittedAndEnabled(player, EXECUTIONER_TOKEN_PERMISSION,
                value(player.getUniqueId()).executionerToken, false);
    }

    @EventHandler
    public void onJoin(PlayerJoinEvent event) {
        Bukkit.getScheduler().runTask(plugin, () -> {
            Player player = event.getPlayer();
            if (player.isOnline()) {
                applyPersonalEnvironment(player);
                enforceInvisibilitySetting(player);
            }
        });
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onPotionEffectChange(EntityPotionEffectEvent event) {
        if (!(event.getEntity() instanceof Player player)
                || event.getNewEffect() == null
                || event.getModifiedType() != PotionEffectType.INVISIBILITY) {
            return;
        }
        if (!invisibilityEnabled(player)) {
            event.setCancelled(true);
        }
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent event) {
        pendingBucketResyncs.remove(event.getPlayer().getUniqueId());
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onBucketEmpty(PlayerBucketEmptyEvent event) {
        scheduleBucketResync(event.getPlayer());
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onBucketFill(PlayerBucketFillEvent event) {
        scheduleBucketResync(event.getPlayer());
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onBucketInteract(PlayerInteractEvent event) {
        ItemStack item = event.getItem();
        if (item != null && (item.getType() == Material.WATER_BUCKET
                || item.getType() == Material.BUCKET)) {
            scheduleBucketResync(event.getPlayer());
        }
    }

    @EventHandler(priority = EventPriority.HIGHEST)
    public void onSettingsClick(InventoryClickEvent event) {
        if (!(event.getView().getTopInventory().getHolder(false) instanceof SettingsHolder holder)) {
            return;
        }
        event.setCancelled(true);
        if (!(event.getWhoClicked() instanceof Player player)
                || !holder.ownerId.equals(player.getUniqueId())) {
            return;
        }

        int slot = event.getRawSlot();
        if (slot < 0 || slot >= GUI_SIZE) {
            return;
        }
        if (holder.page == 1) {
            handlePageOneClick(player, slot);
        } else {
            handlePageTwoClick(player, slot, event.isRightClick());
        }
    }

    @EventHandler(priority = EventPriority.HIGHEST)
    public void onSettingsDrag(InventoryDragEvent event) {
        if (event.getView().getTopInventory().getHolder(false) instanceof SettingsHolder) {
            event.setCancelled(true);
        }
    }

    @EventHandler
    public void onSettingsClose(InventoryCloseEvent event) {
        if (event.getInventory().getHolder(false) instanceof SettingsHolder holder
                && event.getPlayer() instanceof Player player) {
            openSettings.remove(player.getUniqueId(), holder);
        }
    }

    @Override
    public void close() {
        if (closed) {
            return;
        }
        closed = true;
        HandlerList.unregisterAll(this);
        if (saveTask != null) {
            saveTask.cancel();
            saveTask = null;
        }
        if (animationTask != null) {
            animationTask.cancel();
            animationTask = null;
        }

        SettingsSnapshot finalSnapshot = snapshot();
        writer.submit(() -> write(finalSnapshot));
        writer.shutdown();
        try {
            if (!writer.awaitTermination(5L, TimeUnit.SECONDS)) {
                plugin.getLogger().warning("Timed out while saving settings.json.");
            }
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            plugin.getLogger().warning("Interrupted while saving settings.json.");
        }
        pendingBucketResyncs.clear();
        openSettings.clear();
        animatedNameFrames.clear();
        settings.clear();
    }

    private void handlePageOneClick(Player player, int slot) {
        PlayerSettings value = value(player.getUniqueId());
        switch (slot) {
            case 10 -> toggle(player, BROADCAST_PERMISSION,
                    () -> value.broadcastTitles = !bool(value.broadcastTitles, true));
            case 11 -> toggle(player, CONNECTION_PERMISSION,
                    () -> value.connectionMessages = !bool(value.connectionMessages, true));
            case 12 -> toggle(player, MESSAGE_SOUND_PERMISSION,
                    () -> value.messageSound = !bool(value.messageSound, true));
            case 13 -> toggle(player, PROTECTION_PERMISSION,
                    () -> value.protectionShiftClick = !bool(value.protectionShiftClick, true));
            case 14 -> toggle(player, EXPLOSION_PERMISSION,
                    () -> value.explosionShiftClick = !bool(value.explosionShiftClick, true));
            case 15 -> toggle(player, HIDE_STATS_PERMISSION,
                    () -> value.hideStats = !bool(value.hideStats, false));
            case 16 -> toggle(player, KIT_SAFETY_PERMISSION,
                    () -> value.kitSafety = !bool(value.kitSafety, true));
            case PREVIOUS_PAGE_SLOT, NEXT_PAGE_SLOT -> openPage(player, 2);
            case STAFF_SLOT -> showStaffComingSoon(player);
            default -> {
                return;
            }
        }
        if (slot >= 10 && slot <= 16) {
            openPage(player, 1);
        }
    }

    private void handlePageTwoClick(Player player, int slot, boolean previous) {
        PlayerSettings value = value(player.getUniqueId());
        switch (slot) {
            case 10 -> {
                if (!hasSettingPermission(player, PERSONAL_TIME_PERMISSION)) {
                    return;
                }
                PersonalTime current = PersonalTime.parse(value.personalTime);
                PersonalTime selected = previous ? current.previous() : current.next();
                value.personalTime = selected.name();
                selected.apply(player);
                changed();
                openPage(player, 2);
            }
            case 11 -> {
                if (!hasSettingPermission(player, PERSONAL_WEATHER_PERMISSION)) {
                    return;
                }
                PersonalWeather current = PersonalWeather.parse(value.personalWeather);
                PersonalWeather selected = previous ? current.previous() : current.next();
                value.personalWeather = selected.name();
                selected.apply(player);
                changed();
                openPage(player, 2);
            }
            case 12 -> {
                toggle(player, EXECUTIONER_TOKEN_PERMISSION,
                        () -> value.executionerToken = !bool(value.executionerToken, false));
                openPage(player, 2);
            }
            case 13 -> {
                toggle(player, ANTI_GHOST_WATER_PERMISSION,
                        () -> value.antiGhostWater = !bool(value.antiGhostWater, true));
                openPage(player, 2);
            }
            case 14 -> {
                if (!hasSettingPermission(player, INVISIBILITY_PERMISSION)) {
                    return;
                }
                value.invisibility = !bool(value.invisibility, true);
                enforceInvisibilitySetting(player);
                changed();
                openPage(player, 2);
            }
            case PREVIOUS_PAGE_SLOT, NEXT_PAGE_SLOT -> openPage(player, 1);
            case STAFF_SLOT -> showStaffComingSoon(player);
            default -> {
            }
        }
    }

    private void toggle(Player player, String permission, Runnable mutation) {
        if (!hasSettingPermission(player, permission)) {
            return;
        }
        mutation.run();
        changed();
    }

    private boolean hasSettingPermission(Player player, String permission) {
        if (player.hasPermission(permission)) {
            return true;
        }
        player.sendMessage(MessageStyle.permissionDenied(permission));
        return false;
    }

    private void showStaffComingSoon(Player player) {
        if (!player.hasPermission(STAFF_PERMISSION)) {
            player.sendMessage(MessageStyle.permissionDenied(STAFF_PERMISSION));
            return;
        }
        player.sendMessage(MessageStyle.prefixed("Staff Settings are coming soon."));
    }

    private void openPage(Player player, int page) {
        SettingsHolder holder = new SettingsHolder(player.getUniqueId(), page);
        Inventory inventory = Bukkit.createInventory(holder, GUI_SIZE,
                Component.text("Modification Settings", NamedTextColor.DARK_PURPLE)
                        .decoration(TextDecoration.ITALIC, false));
        holder.inventory = inventory;

        ItemStack filler = blankPane(Material.GRAY_STAINED_GLASS_PANE);
        for (int slot = 0; slot < GUI_SIZE; slot++) {
            inventory.setItem(slot, filler);
        }
        inventory.setItem(PREVIOUS_PAGE_SLOT, blankPane(Material.WHITE_STAINED_GLASS_PANE));
        inventory.setItem(NEXT_PAGE_SLOT, blankPane(Material.WHITE_STAINED_GLASS_PANE));
        inventory.setItem(OWNER_SLOT, ownerHead(player));
        inventory.setItem(STAFF_SLOT, staffItem(player));

        if (page == 1) {
            populatePageOne(inventory, player);
        } else {
            populatePageTwo(inventory, player);
        }
        player.openInventory(inventory);
        openSettings.put(player.getUniqueId(), holder);
    }

    private void populatePageOne(Inventory inventory, Player player) {
        PlayerSettings value = value(player.getUniqueId());
        inventory.setItem(10, toggleItem(Material.BOOK, "Broadcast Title",
                "Click here to toggle the broadcast title.",
                bool(value.broadcastTitles, true), BROADCAST_PERMISSION, player));
        inventory.setItem(11, toggleItem(Material.WRITABLE_BOOK, "Connection Messages",
                "Click here to toggle the connection messages.",
                bool(value.connectionMessages, true), CONNECTION_PERMISSION, player));
        inventory.setItem(12, toggleItem(Material.NOTE_BLOCK, "Message Sound",
                "Click here to toggle the sound when you are messaged.",
                bool(value.messageSound, true), MESSAGE_SOUND_PERMISSION, player));
        inventory.setItem(13, toggleItem(Material.IRON_CHESTPLATE, "Protection Shift Click",
                "Toggle shift-right-click activation for the protection axe.",
                bool(value.protectionShiftClick, true), PROTECTION_PERMISSION, player));
        inventory.setItem(14, toggleItem(Material.TNT, "Explosion Shift Click",
                "Toggle shift-right-click activation for the explosion axe.",
                bool(value.explosionShiftClick, true), EXPLOSION_PERMISSION, player));
        inventory.setItem(15, toggleItem(Material.GLASS, "Hide Stats",
                "Hide your stats from other players using /m stats.",
                bool(value.hideStats, false), HIDE_STATS_PERMISSION, player));
        inventory.setItem(16, toggleItem(Material.SHIELD, "Kit Safety",
                "Click here to toggle if you want to make sure you don't load a kit when you have purchased items in your inventory.",
                bool(value.kitSafety, true), KIT_SAFETY_PERMISSION, player));
        inventory.setItem(PREVIOUS_PAGE_SLOT, navigationItem(
                Material.WHITE_STAINED_GLASS_PANE, "Previous Page",
                "Click here to go to the previous page."));
        inventory.setItem(NEXT_PAGE_SLOT, navigationItem(
                Material.WHITE_STAINED_GLASS_PANE, "Next Page",
                "Click here to go to the next page."));
    }

    private void populatePageTwo(Inventory inventory, Player player) {
        PlayerSettings value = value(player.getUniqueId());
        inventory.setItem(10, personalTimeItem(value, player));
        inventory.setItem(11, personalWeatherItem(value, player));
        inventory.setItem(12, toggleItem(Material.PLAYER_HEAD, "Executioner Token",
                "Receive kill tokens instead of heads from your Executioner Sword.",
                bool(value.executionerToken, false), EXECUTIONER_TOKEN_PERMISSION, player));
        inventory.setItem(13, toggleItem(Material.WATER_BUCKET, "Anti-Ghost Water",
                "Resync your inventory after using water buckets.",
                bool(value.antiGhostWater, true), ANTI_GHOST_WATER_PERMISSION, player));
        inventory.setItem(14, toggleItem(Material.GHAST_TEAR, "Invisibility",
                "Click here to toggle if invisibility potions affect you.",
                bool(value.invisibility, true), INVISIBILITY_PERMISSION, player));
        inventory.setItem(PREVIOUS_PAGE_SLOT, navigationItem(
                Material.WHITE_STAINED_GLASS_PANE, "Previous Page",
                "Click here to go to the previous page."));
        inventory.setItem(NEXT_PAGE_SLOT, navigationItem(
                Material.WHITE_STAINED_GLASS_PANE, "Next Page",
                "Click here to go to the next page."));
    }

    private ItemStack ownerHead(Player player) {
        ItemStack item = new ItemStack(Material.PLAYER_HEAD);
        SkullMeta meta = (SkullMeta) item.getItemMeta();
        meta.setOwningPlayer(player);
        meta.displayName(gradient(player.getName()));
        meta.getPersistentDataContainer().set(
                animatedNameKey, PersistentDataType.STRING, player.getName());
        item.setItemMeta(meta);
        return item;
    }

    private ItemStack staffItem(Player player) {
        List<Component> lore = List.of(
                gray("Click here to change your staff settings."),
                Component.empty(),
                Component.text("Coming Soon", NamedTextColor.YELLOW)
                        .decoration(TextDecoration.ITALIC, false),
                permissionLine(STAFF_PERMISSION, player)
        );
        return namedItem(Material.NETHER_STAR, "Staff Settings", lore);
    }

    private ItemStack toggleItem(Material material, String name, String description,
                                 boolean enabled, String permission, Player player) {
        return namedItem(material, name, List.of(
                gray(description),
                status(enabled),
                permissionLine(permission, player)
        ));
    }

    private ItemStack personalTimeItem(PlayerSettings value, Player player) {
        PersonalTime current = PersonalTime.parse(value.personalTime);
        List<Component> lore = new java.util.ArrayList<>();
        lore.add(gray("Left click for the next time."));
        lore.add(gray("Right click for the previous time."));
        for (PersonalTime option : PersonalTime.values()) {
            lore.add(optionLine(option.displayName, option == current));
        }
        lore.add(Component.text("[VIP Exclusive]", TextColor.color(0x00FBAD))
                .decoration(TextDecoration.ITALIC, false));
        lore.add(permissionLine(PERSONAL_TIME_PERMISSION, player));
        return namedItem(Material.CLOCK, "Personal Time", lore);
    }

    private ItemStack personalWeatherItem(PlayerSettings value, Player player) {
        PersonalWeather current = PersonalWeather.parse(value.personalWeather);
        List<Component> lore = new java.util.ArrayList<>();
        lore.add(gray("Left click for the next weather."));
        lore.add(gray("Right click for the previous weather."));
        for (PersonalWeather option : PersonalWeather.values()) {
            lore.add(optionLine(option.displayName, option == current));
        }
        lore.add(Component.text("[VIP Exclusive]", TextColor.color(0x00FBAD))
                .decoration(TextDecoration.ITALIC, false));
        lore.add(permissionLine(PERSONAL_WEATHER_PERMISSION, player));
        return namedItem(Material.WIND_CHARGE, "Personal Weather", lore);
    }

    private Component optionLine(String label, boolean selected) {
        return Component.text("» " + label, selected ? NamedTextColor.GREEN : NamedTextColor.GRAY)
                .decoration(TextDecoration.ITALIC, false);
    }

    private ItemStack navigationItem(Material material, String name, String description) {
        return namedItem(material, name, List.of(gray(description)));
    }

    private ItemStack namedItem(Material material, String name, List<Component> lore) {
        ItemStack item = new ItemStack(material);
        ItemMeta meta = item.getItemMeta();
        meta.displayName(gradient(name));
        meta.lore(lore);
        meta.getPersistentDataContainer().set(
                animatedNameKey, PersistentDataType.STRING, name);
        item.setItemMeta(meta);
        return item;
    }

    private ItemStack blankPane(Material material) {
        ItemStack item = new ItemStack(material);
        ItemMeta meta = item.getItemMeta();
        meta.displayName(Component.text(" ").decoration(TextDecoration.ITALIC, false));
        item.setItemMeta(meta);
        return item;
    }

    private Component status(boolean enabled) {
        return Component.text("Status: ", NamedTextColor.GRAY)
                .append(Component.text(enabled ? "Enabled" : "Disabled",
                        enabled ? NamedTextColor.GREEN : NamedTextColor.RED))
                .decoration(TextDecoration.ITALIC, false);
    }

    private Component permissionLine(String permission, Player player) {
        NamedTextColor color = player.hasPermission(permission)
                ? NamedTextColor.DARK_GRAY : NamedTextColor.RED;
        return Component.text("Permission: " + permission, color)
                .decoration(TextDecoration.ITALIC, false);
    }

    private Component gray(String text) {
        return Component.text(text, NamedTextColor.GRAY)
                .decoration(TextDecoration.ITALIC, false);
    }

    private Component gradient(String text) {
        return animatedNameFrames.computeIfAbsent(text, this::createAnimatedNameFrames)
                .get(animationFrame);
    }

    private List<Component> createAnimatedNameFrames(String text) {
        return java.util.stream.IntStream.range(0, ANIMATION_FRAME_COUNT)
                .mapToObj(frame -> GradientText.animatedEvenRightToLeft(
                                text, frame, ANIMATION_FRAME_COUNT)
                        .decoration(TextDecoration.ITALIC, false))
                .toList();
    }

    private void animateOpenSettings() {
        animationFrame = (animationFrame + 1) % ANIMATION_FRAME_COUNT;
        var iterator = openSettings.entrySet().iterator();
        while (iterator.hasNext()) {
            Map.Entry<UUID, SettingsHolder> entry = iterator.next();
            Player player = Bukkit.getPlayer(entry.getKey());
            if (player == null || !player.isOnline()) {
                iterator.remove();
                continue;
            }
            Inventory inventory = player.getOpenInventory().getTopInventory();
            SettingsHolder holder = entry.getValue();
            if (inventory.getHolder(false) != holder
                    || !holder.ownerId.equals(player.getUniqueId())) {
                iterator.remove();
                continue;
            }
            for (int slot = 0; slot < inventory.getSize(); slot++) {
                ItemStack item = inventory.getItem(slot);
                if (item == null || item.getType().isAir() || !item.hasItemMeta()) {
                    continue;
                }
                ItemMeta meta = item.getItemMeta();
                String label = meta.getPersistentDataContainer().get(
                        animatedNameKey, PersistentDataType.STRING);
                if (label == null) {
                    continue;
                }
                meta.displayName(gradient(label));
                item.setItemMeta(meta);
                inventory.setItem(slot, item);
            }
        }
    }

    private void scheduleBucketResync(Player player) {
        if (!antiGhostWaterEnabled(player)
                || !pendingBucketResyncs.add(player.getUniqueId())) {
            return;
        }
        UUID playerId = player.getUniqueId();
        Bukkit.getScheduler().runTaskLater(plugin, () -> updateInventory(playerId), 1L);
        Bukkit.getScheduler().runTaskLater(plugin, () -> {
            try {
                updateInventory(playerId);
            } finally {
                pendingBucketResyncs.remove(playerId);
            }
        }, 3L);
    }

    private void updateInventory(UUID playerId) {
        Player player = Bukkit.getPlayer(playerId);
        if (player != null && player.isOnline() && antiGhostWaterEnabled(player)) {
            player.updateInventory();
        }
    }

    private boolean antiGhostWaterEnabled(Player player) {
        return permittedAndEnabled(player, ANTI_GHOST_WATER_PERMISSION,
                value(player.getUniqueId()).antiGhostWater, true);
    }

    private boolean permittedAndEnabled(Player player, String permission,
                                        Boolean configured, boolean defaultValue) {
        return player.hasPermission(permission) && bool(configured, defaultValue);
    }

    private boolean bool(Boolean configured, boolean defaultValue) {
        return configured == null ? defaultValue : configured;
    }

    private PlayerSettings value(UUID playerId) {
        return settings.computeIfAbsent(playerId, ignored -> new PlayerSettings());
    }

    private void applyPersonalEnvironment(Player player) {
        PlayerSettings value = settings.get(player.getUniqueId());
        if (value == null) {
            return;
        }
        if (value.personalTime != null && player.hasPermission(PERSONAL_TIME_PERMISSION)) {
            PersonalTime.parse(value.personalTime).apply(player);
        }
        if (value.personalWeather != null && player.hasPermission(PERSONAL_WEATHER_PERMISSION)) {
            PersonalWeather.parse(value.personalWeather).apply(player);
        }
    }

    private void enforceInvisibilitySetting(Player player) {
        if (!invisibilityEnabled(player)) {
            player.removePotionEffect(PotionEffectType.INVISIBILITY);
        }
    }

    private void changed() {
        if (closed) {
            return;
        }
        if (saveTask != null) {
            saveTask.cancel();
        }
        saveTask = Bukkit.getScheduler().runTaskLater(plugin, () -> {
            saveTask = null;
            SettingsSnapshot current = snapshot();
            writer.submit(() -> write(current));
        }, SAVE_DEBOUNCE_TICKS);
    }

    private SettingsSnapshot snapshot() {
        Map<String, PlayerSettings> players = new LinkedHashMap<>();
        settings.forEach((uuid, value) -> players.put(uuid.toString(), value.copy()));
        return new SettingsSnapshot(players);
    }

    private void load() {
        try {
            Files.createDirectories(settingsFile.getParent());
            if (!Files.exists(settingsFile)) {
                return;
            }
            SettingsSnapshot stored = gson.fromJson(
                    Files.readString(settingsFile, StandardCharsets.UTF_8), SettingsSnapshot.class);
            if (stored == null || stored.players == null) {
                return;
            }
            stored.players.forEach((uuid, value) -> {
                try {
                    if (value != null) {
                        settings.put(UUID.fromString(uuid), value.copy());
                    }
                } catch (IllegalArgumentException ignored) {
                    plugin.getLogger().warning("Ignored an invalid player UUID in settings.json: " + uuid);
                }
            });
        } catch (IOException | JsonParseException exception) {
            plugin.getLogger().warning("Could not load settings.json: " + exception.getMessage());
        }
    }

    private void write(SettingsSnapshot current) {
        Path temporary = settingsFile.resolveSibling(settingsFile.getFileName() + ".tmp");
        try {
            Files.createDirectories(settingsFile.getParent());
            Files.writeString(temporary, gson.toJson(current), StandardCharsets.UTF_8);
            try {
                Files.move(temporary, settingsFile, StandardCopyOption.REPLACE_EXISTING,
                        StandardCopyOption.ATOMIC_MOVE);
            } catch (AtomicMoveNotSupportedException ignored) {
                Files.move(temporary, settingsFile, StandardCopyOption.REPLACE_EXISTING);
            }
        } catch (IOException exception) {
            plugin.getLogger().warning("Could not save settings.json: " + exception.getMessage());
        }
    }

    private enum PersonalTime {
        SUNRISE("Sunrise", 23_000L),
        DAY("Day", 1_000L),
        NOON("Noon", 6_000L),
        SUNSET("Sunset", 12_000L),
        NIGHT("Night", 13_000L),
        MIDNIGHT("Midnight", 18_000L);

        private final String displayName;
        private final long ticks;

        PersonalTime(String displayName, long ticks) {
            this.displayName = displayName;
            this.ticks = ticks;
        }

        private void apply(Player player) {
            player.setPlayerTime(ticks, false);
        }

        private PersonalTime next() {
            PersonalTime[] values = values();
            return values[(ordinal() + 1) % values.length];
        }

        private PersonalTime previous() {
            PersonalTime[] values = values();
            return values[(ordinal() + values.length - 1) % values.length];
        }

        private static PersonalTime parse(String value) {
            if (value != null) {
                try {
                    return valueOf(value.toUpperCase(Locale.ROOT));
                } catch (IllegalArgumentException ignored) {
                }
            }
            return DAY;
        }
    }

    private enum PersonalWeather {
        CLEAR("Clear", WeatherType.CLEAR),
        RAIN_SNOW("Rain/Snow", WeatherType.DOWNFALL),
        THUNDER("Thunder", WeatherType.DOWNFALL);

        private final String displayName;
        private final WeatherType weatherType;

        PersonalWeather(String displayName, WeatherType weatherType) {
            this.displayName = displayName;
            this.weatherType = weatherType;
        }

        private void apply(Player player) {
            player.setPlayerWeather(weatherType);
        }

        private PersonalWeather next() {
            PersonalWeather[] values = values();
            return values[(ordinal() + 1) % values.length];
        }

        private PersonalWeather previous() {
            PersonalWeather[] values = values();
            return values[(ordinal() + values.length - 1) % values.length];
        }

        private static PersonalWeather parse(String value) {
            if (value != null) {
                try {
                    return valueOf(value.toUpperCase(Locale.ROOT));
                } catch (IllegalArgumentException ignored) {
                }
            }
            return CLEAR;
        }
    }

    private static final class PlayerSettings {
        private Boolean broadcastTitles;
        private Boolean connectionMessages;
        private Boolean messageSound;
        private Boolean protectionShiftClick;
        private Boolean explosionShiftClick;
        private Boolean hideStats;
        private Boolean kitSafety;
        private Boolean invisibility;
        private Boolean executionerToken;
        private Boolean antiGhostWater;
        private String personalTime;
        private String personalWeather;

        private PlayerSettings copy() {
            PlayerSettings copy = new PlayerSettings();
            copy.broadcastTitles = broadcastTitles;
            copy.connectionMessages = connectionMessages;
            copy.messageSound = messageSound;
            copy.protectionShiftClick = protectionShiftClick;
            copy.explosionShiftClick = explosionShiftClick;
            copy.hideStats = hideStats;
            copy.kitSafety = kitSafety;
            copy.invisibility = invisibility;
            copy.executionerToken = executionerToken;
            copy.antiGhostWater = antiGhostWater;
            copy.personalTime = personalTime;
            copy.personalWeather = personalWeather;
            return copy;
        }
    }

    private static final class SettingsSnapshot {
        private Map<String, PlayerSettings> players;

        private SettingsSnapshot(Map<String, PlayerSettings> players) {
            this.players = players;
        }
    }

    private static final class SettingsHolder implements InventoryHolder {
        private final UUID ownerId;
        private final int page;
        private Inventory inventory;

        private SettingsHolder(UUID ownerId, int page) {
            this.ownerId = ownerId;
            this.page = page;
        }

        @Override
        public @NotNull Inventory getInventory() {
            if (inventory == null) {
                throw new IllegalStateException("Settings inventory has not been initialized.");
            }
            return inventory;
        }
    }
}
