package me.poggercity.modificationFFA;

import io.papermc.paper.datacomponent.DataComponentTypes;
import io.papermc.paper.datacomponent.item.UseCooldown;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.format.TextDecoration;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
import org.bukkit.Bukkit;
import org.bukkit.GameMode;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.Particle;
import org.bukkit.Sound;
import org.bukkit.Tag;
import org.bukkit.attribute.Attribute;
import org.bukkit.attribute.AttributeInstance;
import org.bukkit.block.Block;
import org.bukkit.block.BlockState;
import org.bukkit.block.TileState;
import org.bukkit.enchantments.Enchantment;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Player;
import org.bukkit.entity.Projectile;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Event;
import org.bukkit.event.Listener;
import org.bukkit.event.block.Action;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.block.BlockPlaceEvent;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.event.entity.PlayerDeathEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.inventory.ItemFlag;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.InventoryHolder;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.inventory.meta.Damageable;
import org.bukkit.inventory.meta.SkullMeta;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.util.Vector;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;

final class SwordManager implements Listener, AutoCloseable {

    static final String GIVE_PERMISSION = "modificationffa.sword.give";
    static final String ABILITY_PERMISSION = "modificationffa.sword.ability";

    private static final double STRIKE_CHANCE = 0.20D;
    private static final double VOID_CHANCE = 0.10D;
    private static final double EXECUTIONER_CHANCE = 0.20D;
    private static final double LIFESTEAL_CHANCE = 0.075D;
    private static final double INHIBITOR_CHANCE = 0.02D;
    private static final double LIFESTEAL_HEALTH = 4.0D;
    private static final int PROTECTION_DURATION_TICKS = 15 * 20;
    private static final int RECENT_ATTACKER_TICKS = 10 * 20;
    private static final int DAMAGE_WINDOW_TICKS = 10;
    private static final int EXECUTIONER_HIT_TICKS = 10 * 20;
    private static final int COBWEB_LOCK_TICKS = 5 * 20;
    private static final double EXPLOSION_DAMAGE = 4.0D;
    private static final double EXPLOSION_RADIUS = 5.0D;
    private static final Set<Material> EXPLOSION_BREAKABLE_BLOCKS = Set.of(
            Material.STONE,
            Material.OBSIDIAN,
            Material.COBWEB
    );
    private static final String PUNCH_BOW_ID = "punch_bow";
    private static final String PUNCH_BOW_NAME = "Punch Bow";
    private static final List<String> GIVE_TYPES = List.of(
            "strike", "dash", "executioner", "void", "lifesteal", "inhibitor",
            "knockback", "kb", "protection", "resistance", "explosion",
            "excavator", PUNCH_BOW_ID);

    private final ModificationFFA plugin;
    private final SettingsManager settingsManager;
    private final TokenManager tokenManager;
    private final ProtectArenaManager protectArenaManager;
    private final NamespacedKey swordTypeKey;
    private final NamespacedKey executionerKillsKey;
    private final NamespacedKey executionerIdKey;
    private final NamespacedKey cooldownVersionKey;
    private final Map<UUID, Map<UUID, Long>> recentAttackers = new HashMap<>();
    private final Map<UUID, ProtectionState> protectionStates = new HashMap<>();
    private final Map<UUID, ExecutionerHit> lastExecutionerHits = new HashMap<>();
    private final Map<UUID, ExplosionKnockback> pendingExplosionKnockbacks = new HashMap<>();
    private final Set<UUID> excavatingPlayers = new HashSet<>();

    SwordManager(ModificationFFA plugin, SettingsManager settingsManager, TokenManager tokenManager,
                 ProtectArenaManager protectArenaManager) {
        this.plugin = plugin;
        this.settingsManager = settingsManager;
        this.tokenManager = tokenManager;
        this.protectArenaManager = protectArenaManager;
        this.swordTypeKey = new NamespacedKey(plugin, "sword_type");
        this.executionerKillsKey = new NamespacedKey(plugin, "executioner_kills");
        this.executionerIdKey = new NamespacedKey(plugin, "executioner_id");
        this.cooldownVersionKey = new NamespacedKey(plugin, "cooldown_version");
    }

    void start() {
        plugin.getServer().getPluginManager().registerEvents(this, plugin);
    }

    boolean handleCommand(org.bukkit.command.CommandSender sender, String[] args) {
        if (args.length == 0 || args[0].equalsIgnoreCase("help")) {
            sendHelp(sender);
            return true;
        }

        if (args[0].equalsIgnoreCase("ability")) {
            activateHeldAbility(sender);
            return true;
        }

        if (args[0].equalsIgnoreCase("give")) {
            giveSword(sender, args);
            return true;
        }

        sendHelp(sender);
        return true;
    }

    List<String> tabComplete(org.bukkit.command.CommandSender sender, String[] args) {
        if (args.length == 1) {
            List<String> options = new ArrayList<>(List.of("help"));
            if (sender.hasPermission(ABILITY_PERMISSION)) {
                options.add("ability");
            }
            if (sender.hasPermission(GIVE_PERMISSION)) {
                options.add("give");
            }
            String partial = args[0].toLowerCase(Locale.ROOT);
            return options.stream().filter(option -> option.startsWith(partial)).toList();
        }

        if (!sender.hasPermission(GIVE_PERMISSION)
                || args.length < 2 || !args[0].equalsIgnoreCase("give")) {
            return List.of();
        }

        if (args.length == 2) {
            String partial = args[1].toLowerCase(Locale.ROOT);
            List<String> options = new ArrayList<>(GIVE_TYPES);
            options.addAll(Bukkit.getOnlinePlayers().stream().map(Player::getName).toList());
            return options.stream()
                    .filter(name -> name.toLowerCase(Locale.ROOT).startsWith(partial))
                    .distinct()
                    .sorted(String.CASE_INSENSITIVE_ORDER)
                    .toList();
        }

        if (args.length == 3) {
            String partial = args[2].toLowerCase(Locale.ROOT);
            return GIVE_TYPES.stream().filter(name -> name.startsWith(partial)).toList();
        }
        return List.of();
    }

    @EventHandler(priority = EventPriority.LOWEST, ignoreCancelled = true)
    public void onLockedCobwebHit(EntityDamageByEntityEvent event) {
        if (event.getDamager() instanceof Player attacker
                && attacker.getInventory().getItemInMainHand().getType() == Material.COBWEB
                && attacker.hasCooldown(Material.COBWEB)) {
            event.setCancelled(true);
        }
    }

    @EventHandler(priority = EventPriority.LOWEST, ignoreCancelled = true)
    public void onLockedCobwebPlace(BlockPlaceEvent event) {
        if (event.getItemInHand().getType() == Material.COBWEB
                && event.getPlayer().hasCooldown(Material.COBWEB)) {
            event.setCancelled(true);
        }
    }

    @EventHandler(priority = EventPriority.LOWEST, ignoreCancelled = true)
    public void onLockedCobwebUse(PlayerInteractEvent event) {
        ItemStack item = event.getItem();
        if (item != null && item.getType() == Material.COBWEB
                && event.getPlayer().hasCooldown(Material.COBWEB)) {
            event.setUseItemInHand(Event.Result.DENY);
        }
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onIncomingWeaponDamage(EntityDamageByEntityEvent event) {
        if (!(event.getEntity() instanceof Player victim) || event.getFinalDamage() <= 0.0D) {
            return;
        }
        Player attacker = attackingPlayer(event);
        if (attacker == null || attacker.equals(victim)) {
            return;
        }

        recordRecentAttacker(victim, attacker);
        if (!victim.hasPermission(ABILITY_PERMISSION)) {
            return;
        }

        if (applyProtection(victim, event)) {
            return;
        }
        if (hasWeaponInHotbar(victim, WeaponType.RESISTANCE)) {
            int attackers = recentAttackerCount(victim);
            double resistance = Math.min(1.0D, Math.max(0, attackers - 1) * 0.05D);
            if (resistance > 0.0D) {
                event.setDamage(event.getDamage() * (1.0D - resistance));
            }
        }
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onSwordHit(EntityDamageByEntityEvent event) {
        if (!(event.getDamager() instanceof Player attacker)
                || !(event.getEntity() instanceof LivingEntity target)
                || !attacker.hasPermission(ABILITY_PERMISSION)
                || event.getFinalDamage() <= 0.0D) {
            return;
        }

        WeaponType type = swordType(attacker.getInventory().getItemInMainHand());
        if (type == WeaponType.STRIKE && chance(STRIKE_CHANCE)) {
            target.getWorld().strikeLightning(target.getLocation());
        } else if (type == WeaponType.VOID && chance(VOID_CHANCE)) {
            target.addPotionEffect(new org.bukkit.potion.PotionEffect(
                    org.bukkit.potion.PotionEffectType.BLINDNESS,
                    100,
                    1
            ));
        } else if (type == WeaponType.LIFESTEAL && target instanceof Player
                && chance(LIFESTEAL_CHANCE)) {
            healFromLifesteal(attacker);
        } else if (type == WeaponType.INHIBITOR && target instanceof Player victim
                && chance(INHIBITOR_CHANCE)) {
            victim.setCooldown(Material.COBWEB, COBWEB_LOCK_TICKS);
            victim.getWorld().playSound(
                    victim.getLocation(), Sound.BLOCK_CHEST_LOCKED, 1.0F, 1.0F);
        }
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onExecutionerDamage(EntityDamageByEntityEvent event) {
        if (!(event.getEntity() instanceof Player victim) || event.getFinalDamage() <= 0.0D) {
            return;
        }
        Player attacker = attackingPlayer(event);
        if (attacker == null || attacker.equals(victim)) {
            return;
        }
        UUID victimId = victim.getUniqueId();
        ItemStack weapon = attacker.getInventory().getItemInMainHand();
        if (!(event.getDamager() instanceof Player)
                || swordType(weapon) != WeaponType.EXECUTIONER) {
            lastExecutionerHits.remove(victimId);
            return;
        }
        String weaponId = executionerId(weapon);
        if (weaponId != null) {
            lastExecutionerHits.put(victimId, new ExecutionerHit(
                    attacker.getUniqueId(), weaponId,
                    (long) Bukkit.getCurrentTick() + EXECUTIONER_HIT_TICKS));
        }
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onExplosionDamageApplied(EntityDamageByEntityEvent event) {
        if (!(event.getEntity() instanceof Player target)
                || !(event.getDamager() instanceof Player source)) {
            return;
        }
        ExplosionKnockback pending = pendingExplosionKnockbacks.get(target.getUniqueId());
        if (pending != null && pending.sourceId.equals(source.getUniqueId())
                && !target.isInvulnerable() && target.getGameMode() != GameMode.SPECTATOR) {
            Vector velocity = pending.velocity.clone();
            Bukkit.getScheduler().runTask(plugin, () -> {
                if (target.isOnline() && !target.isInvulnerable()
                        && target.getGameMode() != GameMode.SPECTATOR) {
                    target.setVelocity(target.getVelocity().add(velocity));
                }
            });
        }
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = false)
    public void onAbilityInteract(PlayerInteractEvent event) {
        if (event.getHand() != EquipmentSlot.HAND || !event.getPlayer().isSneaking()) {
            return;
        }
        if (!event.getPlayer().hasPermission(ABILITY_PERMISSION)) {
            return;
        }
        Action action = event.getAction();
        if (action != Action.RIGHT_CLICK_AIR && action != Action.RIGHT_CLICK_BLOCK) {
            return;
        }
        WeaponType type = swordType(event.getPlayer().getInventory().getItemInMainHand());
        if (type == WeaponType.PROTECTION
                && !settingsManager.protectionShiftClickEnabled(event.getPlayer())) {
            return;
        }
        if (type == WeaponType.EXPLOSION
                && !settingsManager.explosionShiftClickEnabled(event.getPlayer())) {
            return;
        }
        if (type == WeaponType.DASH || type == WeaponType.PROTECTION
                || type == WeaponType.EXPLOSION) {
            event.setUseInteractedBlock(Event.Result.DENY);
            event.setUseItemInHand(Event.Result.DENY);
            activateWeaponAbility(event.getPlayer(), type);
        }
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onExcavatorBreak(BlockBreakEvent event) {
        Player player = event.getPlayer();
        if (!player.hasPermission(ABILITY_PERMISSION)
                || excavatingPlayers.contains(player.getUniqueId())
                || swordType(player.getInventory().getItemInMainHand()) != WeaponType.EXCAVATOR
                || !Tag.MINEABLE_PICKAXE.isTagged(event.getBlock().getType())) {
            return;
        }

        Block origin = event.getBlock();
        BlockState originState = origin.getState(false);
        if (originState instanceof TileState || originState instanceof InventoryHolder) {
            return;
        }
        UUID playerId = player.getUniqueId();
        excavatingPlayers.add(playerId);
        int centerX = origin.getX();
        int centerY = origin.getY();
        int centerZ = origin.getZ();
        org.bukkit.World world = origin.getWorld();
        Bukkit.getScheduler().runTask(plugin,
                () -> excavateArea(playerId, world, centerX, centerY, centerZ));
    }

    @EventHandler(priority = EventPriority.NORMAL)
    public void onExecutionerKill(PlayerDeathEvent event) {
        protectionStates.remove(event.getPlayer().getUniqueId());
        recentAttackers.remove(event.getPlayer().getUniqueId());
        ExecutionerHit hit = lastExecutionerHits.remove(event.getPlayer().getUniqueId());
        Player killer = event.getPlayer().getKiller();
        if (killer == null || hit == null
                || !hit.killerId.equals(killer.getUniqueId())
                || Bukkit.getCurrentTick() > hit.expiresAtTick) {
            return;
        }

        ItemStack weapon = findExecutionerWeapon(killer, hit.weaponId);
        if (weapon == null) {
            return;
        }
        incrementExecutionerKills(weapon);
        if (!killer.hasPermission(ABILITY_PERMISSION) || !chance(EXECUTIONER_CHANCE)) {
            return;
        }

        if (settingsManager.executionerTokenEnabled(killer)) {
            tokenManager.giveExecutionerToken(killer);
            return;
        }

        ItemStack head = new ItemStack(Material.PLAYER_HEAD);
        SkullMeta meta = (SkullMeta) head.getItemMeta();
        meta.setOwningPlayer(event.getPlayer());
        head.setItemMeta(meta);
        event.getDrops().add(head);
    }

    @EventHandler
    public void onJoin(PlayerJoinEvent event) {
        UUID playerId = event.getPlayer().getUniqueId();
        Bukkit.getScheduler().runTask(plugin, () -> {
            Player player = Bukkit.getPlayer(playerId);
            if (player == null || !player.isOnline()) {
                return;
            }
            for (ItemStack item : player.getInventory().getContents()) {
                if (!isPunchBow(item)) {
                    swordType(item);
                }
            }
        });
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent event) {
        UUID playerId = event.getPlayer().getUniqueId();
        recentAttackers.remove(playerId);
        recentAttackers.values().forEach(attackers -> attackers.remove(playerId));
        protectionStates.remove(playerId);
        lastExecutionerHits.remove(playerId);
        lastExecutionerHits.entrySet().removeIf(entry -> entry.getValue().killerId.equals(playerId));
        pendingExplosionKnockbacks.remove(playerId);
        excavatingPlayers.remove(playerId);
    }

    @Override
    public void close() {
        recentAttackers.clear();
        protectionStates.clear();
        lastExecutionerHits.clear();
        pendingExplosionKnockbacks.clear();
        excavatingPlayers.clear();
    }

    private void activateHeldAbility(org.bukkit.command.CommandSender sender) {
        if (!(sender instanceof Player player)) {
            sender.sendMessage(MessageStyle.prefixedMessage("core.players-only"));
            return;
        }
        if (!player.hasPermission(ABILITY_PERMISSION)) {
            player.sendMessage(MessageStyle.permissionDenied(ABILITY_PERMISSION));
            return;
        }

        WeaponType type = swordType(player.getInventory().getItemInMainHand());
        if (type == null) {
            player.sendMessage(MessageStyle.prefixedMessage("sword.holding-required"));
            return;
        }
        if (type == WeaponType.DASH || type == WeaponType.PROTECTION
                || type == WeaponType.EXPLOSION) {
            activateWeaponAbility(player, type);
            return;
        }
        player.sendMessage(MessageStyle.prefixedMessage("sword.automatic-ability"));
    }

    private void giveSword(org.bukkit.command.CommandSender sender, String[] args) {
        if (!sender.hasPermission(GIVE_PERMISSION)) {
            sender.sendMessage(MessageStyle.permissionDenied(GIVE_PERMISSION));
            return;
        }
        Player target;
        String requestedType;
        if (args.length == 2 && sender instanceof Player player) {
            target = player;
            requestedType = args[1];
        } else if (args.length == 3) {
            target = Bukkit.getPlayerExact(args[1]);
            requestedType = args[2];
        } else {
            sender.sendMessage(MessageStyle.prefixedMessage("sword.give-usage"));
            return;
        }

        if (target == null) {
            sender.sendMessage(MessageStyle.prefixedMessage("sword.player-offline"));
            return;
        }
        WeaponType type = WeaponType.fromName(requestedType);
        boolean punchBow = PUNCH_BOW_ID.equalsIgnoreCase(requestedType);
        if (type == null && !punchBow) {
            sender.sendMessage(MessageStyle.prefixedMessage("sword.unknown-item"));
            return;
        }

        ItemStack reward = punchBow ? createPunchBow() : createSword(type);
        Component itemName = punchBow
                ? Component.text(PUNCH_BOW_NAME, NamedTextColor.DARK_PURPLE)
                    .decoration(TextDecoration.ITALIC, false)
                : type.displayName();
        Map<Integer, ItemStack> overflow = target.getInventory().addItem(reward);
        for (ItemStack item : overflow.values()) {
            target.getWorld().dropItemNaturally(target.getLocation(), item);
        }
        target.sendMessage(MessageStyle.prefixedMessageWithComponents(
                "sword.received", Map.of("item", itemName)));
        if (!sender.equals(target)) {
            sender.sendMessage(MessageStyle.prefixedMessageWithComponents("sword.given", Map.of(
                    "item", itemName,
                    "player", Component.text(target.getName(), NamedTextColor.GREEN))));
        }
    }

    private ItemStack createSword(WeaponType type) {
        ItemStack sword = new ItemStack(type.material);
        ItemMeta meta = sword.getItemMeta();
        meta.displayName(type.displayName());
        applyResourcePackModel(meta, type.modelId());
        if (type == WeaponType.KNOCKBACK) {
            meta.addEnchant(Enchantment.KNOCKBACK, 2, true);
            meta.addEnchant(Enchantment.UNBREAKING, 2, true);
        } else if (type == WeaponType.PROTECTION || type == WeaponType.RESISTANCE
                || type == WeaponType.EXPLOSION) {
            meta.addEnchant(Enchantment.SHARPNESS, 5, true);
            meta.addEnchant(Enchantment.EFFICIENCY, 5, true);
            meta.setUnbreakable(true);
            meta.addItemFlags(ItemFlag.HIDE_ENCHANTS);
            meta.removeItemFlags(ItemFlag.HIDE_ATTRIBUTES, ItemFlag.HIDE_UNBREAKABLE);
            meta.lore(standardLore(type));
        } else if (type == WeaponType.EXCAVATOR) {
            meta.addEnchant(Enchantment.SILK_TOUCH, 1, true);
            meta.addEnchant(Enchantment.EFFICIENCY, 5, true);
            meta.setUnbreakable(true);
            meta.addItemFlags(ItemFlag.HIDE_ENCHANTS, ItemFlag.HIDE_UNBREAKABLE);
            meta.lore(standardLore(type));
        } else {
            meta.addEnchant(Enchantment.SHARPNESS, 5, true);
            meta.addEnchant(Enchantment.SWEEPING_EDGE, 3, true);
            meta.addEnchant(Enchantment.FIRE_ASPECT, 2, true);
            meta.setUnbreakable(true);
            meta.addItemFlags(ItemFlag.HIDE_ENCHANTS);
            if (type == WeaponType.DASH) {
                meta.removeItemFlags(ItemFlag.HIDE_ATTRIBUTES, ItemFlag.HIDE_UNBREAKABLE);
            } else {
                meta.addItemFlags(ItemFlag.HIDE_UNBREAKABLE);
            }
            if (type == WeaponType.EXECUTIONER) {
                meta.getPersistentDataContainer().set(
                        executionerKillsKey, PersistentDataType.INTEGER, 0);
                meta.getPersistentDataContainer().set(
                        executionerIdKey, PersistentDataType.STRING, UUID.randomUUID().toString());
                meta.lore(executionerLore(0));
            } else {
                meta.lore(standardLore(type));
            }
        }
        meta.getPersistentDataContainer().set(swordTypeKey, PersistentDataType.STRING, type.id);
        sword.setItemMeta(meta);
        applyCooldownComponent(sword, type);
        return sword;
    }

    private ItemStack createPunchBow() {
        ItemStack bow = new ItemStack(Material.BOW);
        ItemMeta meta = bow.getItemMeta();
        meta.displayName(Component.text(PUNCH_BOW_NAME, NamedTextColor.DARK_PURPLE)
                .decoration(TextDecoration.ITALIC, false));
        applyResourcePackModel(meta, PUNCH_BOW_ID);
        meta.addEnchant(Enchantment.POWER, 5, true);
        meta.addEnchant(Enchantment.FLAME, 1, true);
        meta.addEnchant(Enchantment.PUNCH, 2, true);
        meta.addEnchant(Enchantment.UNBREAKING, 3, true);
        meta.addEnchant(Enchantment.INFINITY, 1, true);
        meta.getPersistentDataContainer().set(
                swordTypeKey, PersistentDataType.STRING, PUNCH_BOW_ID);
        bow.setItemMeta(meta);
        return bow;
    }

    boolean isPunchBow(ItemStack item) {
        if (item == null || item.getType() != Material.BOW || !item.hasItemMeta()) {
            return false;
        }
        ItemMeta meta = item.getItemMeta();
        String stored = meta.getPersistentDataContainer().get(
                swordTypeKey, PersistentDataType.STRING);
        if (!PUNCH_BOW_ID.equals(stored)) {
            return false;
        }
        if (applyResourcePackModel(meta, PUNCH_BOW_ID)) {
            item.setItemMeta(meta);
        }
        return true;
    }

    ItemStack mergePunchBows(ItemStack first, ItemStack second) {
        if (!isPunchBow(first) || !isPunchBow(second)) {
            return null;
        }
        ItemStack merged = first.clone();
        merged.setAmount(1);
        int maxDurability = merged.getType().getMaxDurability();
        Damageable firstMeta = (Damageable) first.getItemMeta();
        Damageable secondMeta = (Damageable) second.getItemMeta();
        int firstRemaining = Math.max(0, maxDurability - firstMeta.getDamage());
        int secondRemaining = Math.max(0, maxDurability - secondMeta.getDamage());
        int mergedRemaining = Math.min(maxDurability, firstRemaining + secondRemaining);
        Damageable mergedMeta = (Damageable) merged.getItemMeta();
        mergedMeta.setDamage(Math.max(0, maxDurability - mergedRemaining));
        applyResourcePackModel(mergedMeta, PUNCH_BOW_ID);
        merged.setItemMeta(mergedMeta);
        return merged;
    }

    private WeaponType swordType(ItemStack item) {
        if (item == null || !item.hasItemMeta()) {
            return null;
        }
        ItemMeta meta = item.getItemMeta();
        String stored = meta.getPersistentDataContainer().get(swordTypeKey, PersistentDataType.STRING);
        WeaponType tagged = WeaponType.fromName(stored);
        if (tagged == WeaponType.EXCAVATOR && item.getType() == Material.DIAMOND_PICKAXE) {
            item.setType(Material.NETHERITE_PICKAXE);
            meta = item.getItemMeta();
        }
        if (tagged != null && item.getType() == tagged.material) {
            normalizeSwordPresentation(item, tagged);
            applyCooldownComponent(item, tagged);
            return tagged;
        }

        if (item.getType() != Material.NETHERITE_SWORD) {
            return null;
        }

        if (!meta.isUnbreakable()
                || meta.getEnchantLevel(Enchantment.SHARPNESS) != 5
                || meta.getEnchantLevel(Enchantment.SWEEPING_EDGE) != 3
                || meta.getEnchantLevel(Enchantment.FIRE_ASPECT) != 2
                || meta.displayName() == null || meta.lore() == null) {
            return null;
        }
        String plainName = PlainTextComponentSerializer.plainText().serialize(meta.displayName());
        for (WeaponType type : WeaponType.values()) {
            if (!type.legacyCompatible) {
                continue;
            }
            boolean hasAbilityMarker = meta.lore().stream()
                    .map(PlainTextComponentSerializer.plainText()::serialize)
                    .anyMatch(type.abilityMarker::equals);
            if (plainName.equals(type.legacyItemName) && hasAbilityMarker) {
                meta.getPersistentDataContainer().set(
                        swordTypeKey, PersistentDataType.STRING, type.id);
                meta.displayName(type.displayName());
                item.setItemMeta(meta);
                normalizeSwordPresentation(item, type);
                applyCooldownComponent(item, type);
                return type;
            }
        }
        return null;
    }

    private void normalizeSwordPresentation(ItemStack item, WeaponType type) {
        ItemMeta meta = item.getItemMeta();
        Component desiredName = type.displayName();
        boolean shouldBeUnbreakable = type != WeaponType.KNOCKBACK;
        boolean changed = !desiredName.equals(meta.displayName())
                || meta.isUnbreakable() != shouldBeUnbreakable;
        if (type.revealsNativeTooltip()) {
            if (!meta.hasItemFlag(ItemFlag.HIDE_ENCHANTS)) {
                meta.addItemFlags(ItemFlag.HIDE_ENCHANTS);
                changed = true;
            }
            if (meta.hasItemFlag(ItemFlag.HIDE_ATTRIBUTES)
                    || meta.hasItemFlag(ItemFlag.HIDE_UNBREAKABLE)) {
                meta.removeItemFlags(ItemFlag.HIDE_ATTRIBUTES, ItemFlag.HIDE_UNBREAKABLE);
                changed = true;
            }
        }
        if (applyResourcePackModel(meta, type.modelId())) {
            changed = true;
        }
        if (type == WeaponType.EXECUTIONER) {
            int kills = meta.getPersistentDataContainer().getOrDefault(
                    executionerKillsKey, PersistentDataType.INTEGER, 0);
            if (!meta.getPersistentDataContainer().has(
                    executionerKillsKey, PersistentDataType.INTEGER)) {
                meta.getPersistentDataContainer().set(
                        executionerKillsKey, PersistentDataType.INTEGER, kills);
                changed = true;
            }
            List<Component> desiredLore = executionerLore(kills);
            if (!desiredLore.equals(meta.lore())) {
                meta.lore(desiredLore);
                changed = true;
            }
        } else if (type != WeaponType.KNOCKBACK) {
            List<Component> desiredLore = standardLore(type);
            if (!desiredLore.equals(meta.lore())) {
                meta.lore(desiredLore);
                changed = true;
            }
        }
        if (type == WeaponType.EXECUTIONER
                && !meta.getPersistentDataContainer().has(
                        executionerIdKey, PersistentDataType.STRING)) {
            meta.getPersistentDataContainer().set(
                    executionerIdKey, PersistentDataType.STRING, UUID.randomUUID().toString());
            changed = true;
        }
        if (!changed) {
            return;
        }
        meta.displayName(desiredName);
        meta.setUnbreakable(shouldBeUnbreakable);
        item.setItemMeta(meta);
    }

    private void incrementExecutionerKills(ItemStack item) {
        ItemMeta meta = item.getItemMeta();
        int kills = meta.getPersistentDataContainer().getOrDefault(
                executionerKillsKey, PersistentDataType.INTEGER, 0) + 1;
        meta.getPersistentDataContainer().set(
                executionerKillsKey, PersistentDataType.INTEGER, kills);
        meta.lore(executionerLore(kills));
        item.setItemMeta(meta);
    }

    private boolean applyResourcePackModel(ItemMeta meta, String modelId) {
        var customModelData = meta.getCustomModelDataComponent();
        List<String> expected = List.of(modelId);
        if (customModelData.getStrings().equals(expected)) {
            return false;
        }
        customModelData.setStrings(expected);
        meta.setCustomModelDataComponent(customModelData);
        return true;
    }

    private String executionerId(ItemStack item) {
        if (item == null || !item.hasItemMeta()) {
            return null;
        }
        return item.getItemMeta().getPersistentDataContainer().get(
                executionerIdKey, PersistentDataType.STRING);
    }

    private ItemStack findExecutionerWeapon(Player player, String weaponId) {
        for (ItemStack item : player.getInventory().getContents()) {
            if (item != null && swordType(item) == WeaponType.EXECUTIONER
                    && weaponId.equals(executionerId(item))) {
                return item;
            }
        }
        return null;
    }

    private List<Component> standardLore(WeaponType type) {
        if (type == WeaponType.DASH) {
            return List.of(
                    grayLore("Sharpness V"),
                    grayLore("Sweeping Edge III"),
                    grayLore("Fire Aspect II"),
                    grayLore("Lets you dash through the air!"),
                    Component.empty(),
                    grayLore("Can be activated by shift right clicking or by"),
                    Component.text("doing ", NamedTextColor.GRAY)
                            .append(Component.text("/sword ability", NamedTextColor.GREEN))
                            .append(Component.text(" while holding the sword.", NamedTextColor.GRAY))
                            .decoration(TextDecoration.ITALIC, false)
            );
        }
        if (type == WeaponType.PROTECTION) {
            return List.of(
                    grayLore("Sharpness V"),
                    grayLore("Efficiency V"),
                    grayLore("Protects you against a certain amount of"),
                    grayLore("hits, scaling by the amount recent attackers"),
                    Component.text("or for ", NamedTextColor.GRAY)
                            .append(Component.text("15 seconds", NamedTextColor.GREEN))
                            .append(Component.text(".", NamedTextColor.GRAY))
                            .decoration(TextDecoration.ITALIC, false),
                    Component.empty(),
                    grayLore("Can be activated shift right clicking or by"),
                    Component.text("doing ", NamedTextColor.GRAY)
                            .append(Component.text("/sword ability", NamedTextColor.GREEN))
                            .append(Component.text(" while holding the axe.", NamedTextColor.GRAY))
                            .decoration(TextDecoration.ITALIC, false)
            );
        }
        if (type == WeaponType.RESISTANCE) {
            return List.of(
                    grayLore("Sharpness V"),
                    grayLore("Efficiency V"),
                    grayLore("Gain damage reduction per additional attacker in"),
                    Component.text("the last ", NamedTextColor.GRAY)
                            .append(Component.text("10", NamedTextColor.GREEN))
                            .append(Component.text(" seconds.", NamedTextColor.GRAY))
                            .decoration(TextDecoration.ITALIC, false),
                    Component.empty(),
                    grayLore("Axe must be in the hotbar to work.")
            );
        }
        if (type == WeaponType.EXPLOSION) {
            return List.of(
                    grayLore("Sharpness V"),
                    grayLore("Efficiency V"),
                    grayLore("Knocks nearby players away and removes"),
                    grayLore("nearby cobwebs, stone, and obsidian."),
                    Component.empty(),
                    grayLore("Can be activated shift right clicking or by"),
                    Component.text("doing ", NamedTextColor.GRAY)
                            .append(Component.text("/sword ability", NamedTextColor.GREEN))
                            .append(Component.text(" while holding the axe.", NamedTextColor.GRAY))
                            .decoration(TextDecoration.ITALIC, false)
            );
        }
        return type.lore.stream()
                .map(line -> (Component) Component.text(line, NamedTextColor.GRAY)
                        .decoration(TextDecoration.ITALIC, false))
                .toList();
    }

    private List<Component> executionerLore(int kills) {
        return List.of(
                grayLore("Sharpness V"),
                grayLore("Sweeping Edge III"),
                grayLore("Fire Aspect II"),
                Component.text("Kills: ", NamedTextColor.GRAY)
                        .append(Component.text(kills, NamedTextColor.GREEN))
                        .decoration(TextDecoration.ITALIC, false),
                Component.empty(),
                grayLore("A chance to drop player's heads when you kill them."),
                Component.text("Use: ", NamedTextColor.GRAY)
                        .append(Component.text("/m executioner", NamedTextColor.GREEN))
                        .append(Component.text(" to sell the dropped heads.", NamedTextColor.GRAY))
                        .decoration(TextDecoration.ITALIC, false)
        );
    }

    private Component grayLore(String text) {
        return Component.text(text, NamedTextColor.GRAY)
                .decoration(TextDecoration.ITALIC, false);
    }

    private void activateWeaponAbility(Player player, WeaponType type) {
        switch (type) {
            case DASH -> dash(player);
            case PROTECTION -> activateProtection(player);
            case EXPLOSION -> activateExplosion(player);
            default -> player.sendMessage(MessageStyle.prefixedMessage("sword.automatic-ability"));
        }
    }

    private void dash(Player player) {
        ItemStack held = player.getInventory().getItemInMainHand();
        if (!beginWeaponCooldown(player, held, WeaponType.DASH)) {
            return;
        }

        Vector direction = player.getLocation().getDirection().normalize().multiply(2.0D);
        player.setVelocity(direction);
        player.getWorld().playSound(
                player.getLocation(), Sound.ENTITY_FIREWORK_ROCKET_LAUNCH, 1.0F, 1.0F);
        player.sendMessage(MessageStyle.prefixedMessage("sword.dashed"));
    }

    private void activateProtection(Player player) {
        ItemStack held = player.getInventory().getItemInMainHand();
        if (!beginWeaponCooldown(player, held, WeaponType.PROTECTION)) {
            return;
        }
        int protectedHits = Math.max(1, recentAttackerCount(player));
        long currentTick = Bukkit.getCurrentTick();
        protectionStates.put(player.getUniqueId(), new ProtectionState(
                currentTick + PROTECTION_DURATION_TICKS, protectedHits));
        player.getWorld().playSound(
                player.getLocation(), Sound.ITEM_SHIELD_BLOCK, 0.9F, 1.15F);
        player.sendMessage(MessageStyle.prefixedMessage(
                protectedHits == 1 ? "sword.protection-one" : "sword.protection-many",
                Map.of("hits", protectedHits)));
    }

    private void activateExplosion(Player player) {
        ItemStack held = player.getInventory().getItemInMainHand();
        if (!beginWeaponCooldown(player, held, WeaponType.EXPLOSION)) {
            return;
        }

        player.getWorld().playSound(
                player.getLocation(), Sound.ENTITY_GENERIC_EXPLODE, 1.0F, 1.0F);
        player.getWorld().spawnParticle(
                Particle.EXPLOSION_EMITTER, player.getLocation().add(0.0D, 1.0D, 0.0D), 1);
        clearExplosionBlocks(player.getLocation());
        double radiusSquared = EXPLOSION_RADIUS * EXPLOSION_RADIUS;
        for (org.bukkit.entity.Entity nearby : player.getNearbyEntities(
                EXPLOSION_RADIUS, EXPLOSION_RADIUS, EXPLOSION_RADIUS)) {
            if (!(nearby instanceof Player target)
                    || target.equals(player)
                    || target.isInvulnerable()
                    || target.getGameMode() == GameMode.SPECTATOR
                    || target.getLocation().distanceSquared(player.getLocation()) > radiusSquared) {
                continue;
            }
            Vector knockback = target.getLocation().toVector()
                    .subtract(player.getLocation().toVector());
            if (knockback.lengthSquared() < 0.0001D) {
                knockback = player.getLocation().getDirection();
            }
            knockback.normalize().multiply(1.1D).setY(0.35D);
            pendingExplosionKnockbacks.put(target.getUniqueId(),
                    new ExplosionKnockback(player.getUniqueId(), knockback));
            try {
                target.damage(EXPLOSION_DAMAGE, player);
            } finally {
                pendingExplosionKnockbacks.remove(target.getUniqueId());
            }
        }
    }

    private void clearExplosionBlocks(Location center) {
        int radius = (int) Math.ceil(EXPLOSION_RADIUS);
        int minimumY = Math.max(center.getWorld().getMinHeight(), center.getBlockY() - radius);
        int maximumY = Math.min(center.getWorld().getMaxHeight() - 1, center.getBlockY() + radius);
        double radiusSquared = EXPLOSION_RADIUS * EXPLOSION_RADIUS;
        for (int x = center.getBlockX() - radius; x <= center.getBlockX() + radius; x++) {
            for (int y = minimumY; y <= maximumY; y++) {
                for (int z = center.getBlockZ() - radius; z <= center.getBlockZ() + radius; z++) {
                    double deltaX = x + 0.5D - center.getX();
                    double deltaY = y + 0.5D - center.getY();
                    double deltaZ = z + 0.5D - center.getZ();
                    if (deltaX * deltaX + deltaY * deltaY + deltaZ * deltaZ > radiusSquared) {
                        continue;
                    }
                    Block block = center.getWorld().getBlockAt(x, y, z);
                    if (EXPLOSION_BREAKABLE_BLOCKS.contains(block.getType())
                            && protectArenaManager.breakByAbility(block)) {
                        block.setType(Material.AIR, false);
                    }
                }
            }
        }
    }

    private boolean beginWeaponCooldown(Player player, ItemStack held, WeaponType type) {
        applyCooldownComponent(held, type);
        if (player.hasCooldown(held)) {
            if (type != WeaponType.EXPLOSION) {
                long seconds = Math.max(1L, (player.getCooldown(held) + 19L) / 20L);
                player.sendMessage(MessageStyle.prefixedMessage(
                        "sword.cooldown", Map.of("seconds", seconds)));
            }
            return false;
        }
        player.setCooldown(held, type.cooldownTicks());
        return true;
    }

    private void applyCooldownComponent(ItemStack item, WeaponType type) {
        int cooldownTicks = type.cooldownTicks();
        if (item == null || item.getType() != type.material || cooldownTicks <= 0) {
            return;
        }
        ItemMeta currentMeta = item.getItemMeta();
        if (currentMeta.getPersistentDataContainer().getOrDefault(
                cooldownVersionKey, PersistentDataType.INTEGER, 0) == 1) {
            return;
        }
        NamespacedKey group = new NamespacedKey(plugin, "sword_" + type.id);
        item.setData(DataComponentTypes.USE_COOLDOWN,
                UseCooldown.useCooldown(cooldownTicks / 20.0F)
                        .cooldownGroup(group));
        ItemMeta updatedMeta = item.getItemMeta();
        updatedMeta.getPersistentDataContainer().set(
                cooldownVersionKey, PersistentDataType.INTEGER, 1);
        item.setItemMeta(updatedMeta);
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

    private void recordRecentAttacker(Player victim, Player attacker) {
        long expiresAt = (long) Bukkit.getCurrentTick() + RECENT_ATTACKER_TICKS;
        recentAttackers.computeIfAbsent(victim.getUniqueId(), ignored -> new HashMap<>())
                .put(attacker.getUniqueId(), expiresAt);
    }

    private int recentAttackerCount(Player player) {
        Map<UUID, Long> attackers = recentAttackers.get(player.getUniqueId());
        if (attackers == null) {
            return 0;
        }
        long currentTick = Bukkit.getCurrentTick();
        attackers.entrySet().removeIf(entry -> entry.getValue() < currentTick);
        if (attackers.isEmpty()) {
            recentAttackers.remove(player.getUniqueId());
            return 0;
        }
        return attackers.size();
    }

    private boolean hasWeaponInHotbar(Player player, WeaponType type) {
        for (int slot = 0; slot < 9; slot++) {
            if (swordType(player.getInventory().getItem(slot)) == type) {
                return true;
            }
        }
        return false;
    }

    private boolean applyProtection(Player victim, EntityDamageByEntityEvent event) {
        ProtectionState state = protectionStates.get(victim.getUniqueId());
        if (state == null) {
            return false;
        }
        long currentTick = Bukkit.getCurrentTick();
        if (currentTick >= state.expiresAtTick
                || (state.remainingHits <= 0 && currentTick >= state.nextChargeTick)) {
            protectionStates.remove(victim.getUniqueId());
            return false;
        }

        event.setCancelled(true);
        if (state.remainingHits > 0 && currentTick >= state.nextChargeTick) {
            state.remainingHits--;
            state.nextChargeTick = currentTick + DAMAGE_WINDOW_TICKS;
            victim.getWorld().playSound(
                    victim.getLocation(), Sound.ITEM_SHIELD_BLOCK, 0.7F, 1.25F);
        }
        return true;
    }

    private void excavateArea(UUID playerId, org.bukkit.World world,
                              int centerX, int centerY, int centerZ) {
        try {
            Player player = Bukkit.getPlayer(playerId);
            if (player == null || !player.isOnline()
                    || swordType(player.getInventory().getItemInMainHand())
                    != WeaponType.EXCAVATOR) {
                return;
            }
            for (int xOffset = -1; xOffset <= 1; xOffset++) {
                for (int yOffset = -1; yOffset <= 1; yOffset++) {
                    for (int zOffset = -1; zOffset <= 1; zOffset++) {
                        if (xOffset == 0 && yOffset == 0 && zOffset == 0) {
                            continue;
                        }
                        int x = centerX + xOffset;
                        int y = centerY + yOffset;
                        int z = centerZ + zOffset;
                        if (y < world.getMinHeight() || y >= world.getMaxHeight()
                                || !world.isChunkLoaded(x >> 4, z >> 4)) {
                            continue;
                        }
                        Block block = world.getBlockAt(x, y, z);
                        Material material = block.getType();
                        if (material.isAir() || block.isLiquid()
                                || material.getHardness() < 0.0F
                                || !Tag.MINEABLE_PICKAXE.isTagged(material)) {
                            continue;
                        }
                        BlockState state = block.getState(false);
                        if (state instanceof TileState || state instanceof InventoryHolder) {
                            continue;
                        }
                        if (player.getGameMode() == GameMode.SPECTATOR
                                || swordType(player.getInventory().getItemInMainHand())
                                != WeaponType.EXCAVATOR) {
                            return;
                        }
                        player.breakBlock(block);
                    }
                }
            }
        } finally {
            excavatingPlayers.remove(playerId);
        }
    }

    private void healFromLifesteal(Player player) {
        AttributeInstance maxHealthAttribute = player.getAttribute(Attribute.MAX_HEALTH);
        double maxHealth = maxHealthAttribute == null ? 20.0D : maxHealthAttribute.getValue();
        double newHealth = Math.min(maxHealth, player.getHealth() + LIFESTEAL_HEALTH);
        if (newHealth <= player.getHealth()) {
            return;
        }
        player.setHealth(newHealth);
        player.sendMessage(MessageStyle.prefixedMessage("sword.lifesteal-healed"));
    }

    private boolean chance(double probability) {
        return ThreadLocalRandom.current().nextDouble() < probability;
    }

    private void sendHelp(org.bukkit.command.CommandSender sender) {
        sender.sendMessage(MessageStyle.message("sword.help.title"));
        sender.sendMessage(MessageStyle.message("sword.help.command"));
        if (sender.hasPermission(ABILITY_PERMISSION)) {
            sender.sendMessage(MessageStyle.message("sword.help.ability"));
        }
        if (sender.hasPermission(GIVE_PERMISSION)) {
            sender.sendMessage(MessageStyle.message("sword.help.give"));
        }
    }

    private static final class ProtectionState {
        private final long expiresAtTick;
        private int remainingHits;
        private long nextChargeTick;

        private ProtectionState(long expiresAtTick, int remainingHits) {
            this.expiresAtTick = expiresAtTick;
            this.remainingHits = remainingHits;
        }
    }

    private record ExecutionerHit(UUID killerId, String weaponId, long expiresAtTick) {
    }

    private record ExplosionKnockback(UUID sourceId, Vector velocity) {
    }

}
