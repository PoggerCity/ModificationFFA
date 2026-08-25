package me.poggercity.modificationFFA;

import io.papermc.paper.datacomponent.DataComponentTypes;
import io.papermc.paper.datacomponent.item.UseCooldown;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.format.TextColor;
import net.kyori.adventure.text.format.TextDecoration;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.Sound;
import org.bukkit.attribute.Attribute;
import org.bukkit.attribute.AttributeInstance;
import org.bukkit.enchantments.Enchantment;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Event;
import org.bukkit.event.Listener;
import org.bukkit.event.block.Action;
import org.bukkit.event.block.BlockPlaceEvent;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.event.entity.PlayerDeathEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.inventory.ItemFlag;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.inventory.meta.SkullMeta;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.util.Vector;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
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
    private static final int DASH_COOLDOWN_TICKS = 20 * 20;
    private static final int COBWEB_LOCK_TICKS = 5 * 20;
    private static final String PUNCH_BOW_ID = "punch_bow";
    private static final String PUNCH_BOW_NAME = "Punch Bow";
    private static final List<String> GIVE_TYPES = List.of(
            "strike", "dash", "executioner", "void", "lifesteal", "inhibitor",
            "knockback", "kb", PUNCH_BOW_ID);

    private final ModificationFFA plugin;
    private final NamespacedKey swordTypeKey;

    SwordManager(ModificationFFA plugin) {
        this.plugin = plugin;
        this.swordTypeKey = new NamespacedKey(plugin, "sword_type");
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

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onSwordHit(EntityDamageByEntityEvent event) {
        if (!(event.getDamager() instanceof Player attacker)
                || !(event.getEntity() instanceof LivingEntity target)
                || !attacker.hasPermission(ABILITY_PERMISSION)
                || event.getFinalDamage() <= 0.0D) {
            return;
        }

        SwordType type = swordType(attacker.getInventory().getItemInMainHand());
        if (type == SwordType.STRIKE && chance(STRIKE_CHANCE)) {
            target.getWorld().strikeLightning(target.getLocation());
        } else if (type == SwordType.VOID && chance(VOID_CHANCE)) {
            target.addPotionEffect(new org.bukkit.potion.PotionEffect(
                    org.bukkit.potion.PotionEffectType.BLINDNESS,
                    100,
                    1
            ));
        } else if (type == SwordType.LIFESTEAL && target instanceof Player
                && chance(LIFESTEAL_CHANCE)) {
            healFromLifesteal(attacker);
        } else if (type == SwordType.INHIBITOR && target instanceof Player victim
                && chance(INHIBITOR_CHANCE)) {
            victim.setCooldown(Material.COBWEB, COBWEB_LOCK_TICKS);
            victim.getWorld().playSound(
                    victim.getLocation(), Sound.BLOCK_CHEST_LOCKED, 1.0F, 1.0F);
        }
    }

    @EventHandler(priority = EventPriority.NORMAL, ignoreCancelled = true)
    public void onDashInteract(PlayerInteractEvent event) {
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
        if (swordType(event.getPlayer().getInventory().getItemInMainHand()) == SwordType.DASH) {
            dash(event.getPlayer());
        }
    }

    @EventHandler(priority = EventPriority.NORMAL)
    public void onExecutionerKill(PlayerDeathEvent event) {
        Player killer = event.getPlayer().getKiller();
        if (killer == null
                || !killer.hasPermission(ABILITY_PERMISSION)
                || swordType(killer.getInventory().getItemInMainHand()) != SwordType.EXECUTIONER
                || !chance(EXECUTIONER_CHANCE)) {
            return;
        }

        ItemStack head = new ItemStack(Material.PLAYER_HEAD);
        SkullMeta meta = (SkullMeta) head.getItemMeta();
        meta.setOwningPlayer(event.getPlayer());
        head.setItemMeta(meta);
        event.getDrops().add(head);
    }

    @Override
    public void close() {
        // No persistent resources are owned by this manager.
    }

    private void activateHeldAbility(org.bukkit.command.CommandSender sender) {
        if (!(sender instanceof Player player)) {
            sender.sendMessage(MessageStyle.prefixed("This command can only be used by players."));
            return;
        }
        if (!player.hasPermission(ABILITY_PERMISSION)) {
            player.sendMessage(MessageStyle.permissionDenied(ABILITY_PERMISSION));
            return;
        }

        SwordType type = swordType(player.getInventory().getItemInMainHand());
        if (type == null) {
            player.sendMessage(MessageStyle.prefixed("You must be holding a Modification sword."));
            return;
        }
        if (type == SwordType.DASH) {
            dash(player);
            return;
        }
        player.sendMessage(MessageStyle.prefixed(
                "This sword's ability activates automatically while fighting."));
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
            sender.sendMessage(MessageStyle.prefixed(
                    "Usage: /sword give [player] <strike|dash|executioner|void|lifesteal|inhibitor|knockback|punch_bow>"));
            return;
        }

        if (target == null) {
            sender.sendMessage(MessageStyle.prefixed("That player is not online."));
            return;
        }
        SwordType type = SwordType.fromName(requestedType);
        boolean punchBow = PUNCH_BOW_ID.equalsIgnoreCase(requestedType);
        if (type == null && !punchBow) {
            sender.sendMessage(MessageStyle.prefixed(
                    "Unknown item. Available: strike, dash, executioner, void, lifesteal, inhibitor, knockback, punch_bow."));
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
        target.sendMessage(MessageStyle.prefix()
                .append(Component.text("You have received the ", NamedTextColor.GRAY))
                .append(itemName)
                .append(Component.text("!", NamedTextColor.GRAY)));
        if (!sender.equals(target)) {
            sender.sendMessage(MessageStyle.prefix()
                    .append(Component.text("Gave ", NamedTextColor.GRAY))
                    .append(itemName)
                    .append(Component.text(" to ", NamedTextColor.GRAY))
                    .append(Component.text(target.getName(), NamedTextColor.GREEN))
                    .append(Component.text(".", NamedTextColor.GRAY)));
        }
    }

    private ItemStack createSword(SwordType type) {
        ItemStack sword = new ItemStack(type.material);
        ItemMeta meta = sword.getItemMeta();
        meta.displayName(type.displayName());
        if (type == SwordType.KNOCKBACK) {
            meta.addEnchant(Enchantment.KNOCKBACK, 2, true);
            meta.addEnchant(Enchantment.UNBREAKING, 2, true);
        } else {
            meta.addEnchant(Enchantment.SHARPNESS, 5, true);
            meta.addEnchant(Enchantment.SWEEPING_EDGE, 3, true);
            meta.addEnchant(Enchantment.FIRE_ASPECT, 2, true);
            meta.setUnbreakable(true);
            meta.addItemFlags(ItemFlag.HIDE_ENCHANTS, ItemFlag.HIDE_UNBREAKABLE);
            meta.lore(type.lore.stream()
                    .map(line -> Component.text(line, NamedTextColor.GRAY)
                            .decoration(TextDecoration.ITALIC, false))
                    .toList());
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

    private SwordType swordType(ItemStack item) {
        if (item == null || !item.hasItemMeta()) {
            return null;
        }
        ItemMeta meta = item.getItemMeta();
        String stored = meta.getPersistentDataContainer().get(swordTypeKey, PersistentDataType.STRING);
        SwordType tagged = SwordType.fromName(stored);
        if (tagged != null && item.getType() == tagged.material) {
            normalizeSwordPresentation(item, tagged);
            applyCooldownComponent(item, tagged);
            return tagged;
        }

        if (item.getType() != Material.NETHERITE_SWORD) {
            return null;
        }

        // Recognize swords already issued by the supplied ModifySwords JAR.
        if (!meta.isUnbreakable()
                || meta.getEnchantLevel(Enchantment.SHARPNESS) != 5
                || meta.getEnchantLevel(Enchantment.SWEEPING_EDGE) != 3
                || meta.getEnchantLevel(Enchantment.FIRE_ASPECT) != 2
                || meta.displayName() == null || meta.lore() == null) {
            return null;
        }
        String plainName = PlainTextComponentSerializer.plainText().serialize(meta.displayName());
        for (SwordType type : SwordType.values()) {
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
                applyCooldownComponent(item, type);
                return type;
            }
        }
        return null;
    }

    private void normalizeSwordPresentation(ItemStack item, SwordType type) {
        ItemMeta meta = item.getItemMeta();
        Component desiredName = type.displayName();
        boolean shouldBeUnbreakable = type != SwordType.KNOCKBACK;
        if (desiredName.equals(meta.displayName())
                && meta.isUnbreakable() == shouldBeUnbreakable) {
            return;
        }
        meta.displayName(desiredName);
        meta.setUnbreakable(shouldBeUnbreakable);
        item.setItemMeta(meta);
    }

    private void dash(Player player) {
        ItemStack held = player.getInventory().getItemInMainHand();
        applyCooldownComponent(held, SwordType.DASH);
        if (player.hasCooldown(held)) {
            long seconds = Math.max(1L, (player.getCooldown(held) + 19L) / 20L);
            player.sendMessage(MessageStyle.prefixed(
                    "You must wait " + seconds + " seconds before using this ability again."));
            return;
        }

        Vector direction = player.getLocation().getDirection().normalize().multiply(2.0D);
        player.setVelocity(direction);
        player.setCooldown(held, DASH_COOLDOWN_TICKS);
        player.sendMessage(MessageStyle.prefixed("You have dashed through the air!"));
    }

    private void applyCooldownComponent(ItemStack item, SwordType type) {
        if (item == null || item.getType() != type.material
                || item.hasData(DataComponentTypes.USE_COOLDOWN)) {
            return;
        }
        NamespacedKey group = new NamespacedKey(plugin, "sword_" + type.id);
        item.setData(DataComponentTypes.USE_COOLDOWN,
                UseCooldown.useCooldown(DASH_COOLDOWN_TICKS / 20.0F)
                        .cooldownGroup(group));
    }

    private void healFromLifesteal(Player player) {
        AttributeInstance maxHealthAttribute = player.getAttribute(Attribute.MAX_HEALTH);
        double maxHealth = maxHealthAttribute == null ? 20.0D : maxHealthAttribute.getValue();
        double newHealth = Math.min(maxHealth, player.getHealth() + LIFESTEAL_HEALTH);
        if (newHealth <= player.getHealth()) {
            return;
        }
        player.setHealth(newHealth);
        player.sendMessage(MessageStyle.prefix()
                .append(Component.text("❤", NamedTextColor.RED)
                        .decoration(TextDecoration.BOLD, true)
                        .decoration(TextDecoration.ITALIC, false))
                .append(Component.text(" Your Lifesteal Sword restored 2 hearts!",
                                NamedTextColor.GRAY)
                        .decoration(TextDecoration.BOLD, false)));
    }

    private boolean chance(double probability) {
        return ThreadLocalRandom.current().nextDouble() < probability;
    }

    private void sendHelp(org.bukkit.command.CommandSender sender) {
        sender.sendMessage(Component.text("------ ", NamedTextColor.GRAY)
                .append(Component.text("sword help", NamedTextColor.GREEN))
                .append(Component.text(" ------", NamedTextColor.GRAY)));
        helpLine(sender, "/sword help", "The command to display helpful information.");
        if (sender.hasPermission(ABILITY_PERMISSION)) {
            helpLine(sender, "/sword ability", "Activates your Sword's ability.");
        }
        if (sender.hasPermission(GIVE_PERMISSION)) {
            helpLine(sender, "/sword give", "Gives a Modify sword to a player.");
        }
    }

    private void helpLine(org.bukkit.command.CommandSender sender, String command, String description) {
        sender.sendMessage(Component.text("- ", NamedTextColor.GRAY)
                .append(Component.text(command, NamedTextColor.GREEN))
                .append(Component.text(" - " + description, NamedTextColor.GRAY)));
    }

    private enum SwordType {
        STRIKE("strike", "⚡", "Strike Sword", NamedTextColor.YELLOW,
                Material.NETHERITE_SWORD, "⚡ Strike Sword", true,
                "A chance to strike enemies with lightning", List.of(
                "Sharpness V",
                "Sweeping Edge III",
                "Fire Aspect II",
                "Unbreakable",
                "A chance to strike enemies with lightning"
        )),
        DASH("dash", "🚀", "Dash Sword", NamedTextColor.GREEN,
                Material.NETHERITE_SWORD, "🚀 Dash Sword", true,
                "Lets you dash through the air!", List.of(
                "Sharpness V",
                "Sweeping Edge III",
                "Fire Aspect II",
                "Unbreakable",
                "Lets you dash through the air!",
                "Can be activated by shift right clicking while holding the sword"
        )),
        EXECUTIONER("executioner", "☠", "Executioner Sword", NamedTextColor.GOLD,
                Material.NETHERITE_SWORD, "☠ Executioner Sword", true,
                "A chance to drop player's heads when you kill them", List.of(
                "Sharpness V",
                "Sweeping Edge III",
                "Fire Aspect II",
                "Unbreakable",
                "A chance to drop player's heads when you kill them"
        )),
        VOID("void", "✺", "Void Sword", NamedTextColor.DARK_GRAY,
                Material.NETHERITE_SWORD, "🌑 Void Sword", true,
                "A chance to take enemies' vision!", List.of(
                "Sharpness V",
                "Sweeping Edge III",
                "Fire Aspect II",
                "Unbreakable",
                "A chance to take enemies' vision!"
        )),
        LIFESTEAL("lifesteal", "❤", "Lifesteal Sword", NamedTextColor.RED,
                Material.NETHERITE_SWORD, null, false,
                "A chance to heal when you hit someone!", List.of(
                "Sharpness V",
                "Sweeping Edge III",
                "Fire Aspect II",
                "A chance to heal when you hit someone!"
        )),
        INHIBITOR("inhibitor", "🔒", "Inhibitor Sword", NamedTextColor.AQUA,
                Material.NETHERITE_SWORD, null, false,
                "A chance to disable enemies' cobwebs!", List.of(
                "Sharpness V",
                "Sweeping Edge III",
                "Fire Aspect II",
                "A chance to disable enemies' cobwebs!"
        )),
        KNOCKBACK("knockback", "", "Knockback Sword", NamedTextColor.YELLOW,
                Material.GOLDEN_SWORD, null, false, "", List.of());

        private final String id;
        private final String emoji;
        private final String label;
        private final TextColor color;
        private final Material material;
        private final String legacyItemName;
        private final boolean legacyCompatible;
        private final String abilityMarker;
        private final List<String> lore;

        SwordType(String id, String emoji, String label, TextColor color, Material material,
                  String legacyItemName, boolean legacyCompatible, String abilityMarker,
                  List<String> lore) {
            this.id = id;
            this.emoji = emoji;
            this.label = label;
            this.color = color;
            this.material = material;
            this.legacyItemName = legacyItemName;
            this.legacyCompatible = legacyCompatible;
            this.abilityMarker = abilityMarker;
            this.lore = lore;
        }

        private Component displayName() {
            Component name = Component.empty();
            if (!emoji.isEmpty()) {
                name = name.append(Component.text(emoji, color)
                                .decoration(TextDecoration.BOLD, true)
                                .decoration(TextDecoration.ITALIC, false))
                        .append(Component.space());
            }
            return name.append(Component.text(label, color)
                    .decoration(TextDecoration.BOLD, false)
                    .decoration(TextDecoration.ITALIC, false));
        }

        private static SwordType fromName(String name) {
            if (name == null) {
                return null;
            }
            for (SwordType type : values()) {
                if (type.id.equalsIgnoreCase(name)
                        || (type == KNOCKBACK && name.equalsIgnoreCase("kb"))) {
                    return type;
                }
            }
            return null;
        }

    }
}
