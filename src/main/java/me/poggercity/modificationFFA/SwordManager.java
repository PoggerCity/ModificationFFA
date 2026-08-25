package me.poggercity.modificationFFA;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.format.TextColor;
import net.kyori.adventure.text.format.TextDecoration;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.enchantments.Enchantment;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.Action;
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
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;

final class SwordManager implements Listener, AutoCloseable {

    static final String GIVE_PERMISSION = "modificationffa.sword.give";
    static final String ABILITY_PERMISSION = "modificationffa.sword.ability";

    private static final double STRIKE_CHANCE = 0.20D;
    private static final double VOID_CHANCE = 0.10D;
    private static final double EXECUTIONER_CHANCE = 0.20D;
    private static final long DASH_COOLDOWN_MILLIS = 20_000L;
    private static final String PUNCH_BOW_ID = "punch_bow";
    private static final String PUNCH_BOW_NAME = "Punch Bow";
    private static final List<String> GIVE_TYPES = List.of(
            "strike", "dash", "executioner", "void", PUNCH_BOW_ID);

    private final ModificationFFA plugin;
    private final NamespacedKey swordTypeKey;
    private final Map<UUID, Long> dashCooldowns = new HashMap<>();

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

    @EventHandler(priority = EventPriority.NORMAL, ignoreCancelled = true)
    public void onSwordHit(EntityDamageByEntityEvent event) {
        if (!(event.getDamager() instanceof Player attacker)
                || !(event.getEntity() instanceof LivingEntity target)
                || !attacker.hasPermission(ABILITY_PERMISSION)) {
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
        dashCooldowns.clear();
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
                    "Usage: /sword give [player] <strike|dash|executioner|void|punch_bow>"));
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
                    "Unknown item. Available: strike, dash, executioner, void, punch_bow."));
            return;
        }

        ItemStack reward = punchBow ? createPunchBow() : createSword(type);
        String itemName = punchBow ? PUNCH_BOW_NAME : type.itemName;
        TextColor itemColor = punchBow ? NamedTextColor.DARK_PURPLE : type.color;
        Map<Integer, ItemStack> overflow = target.getInventory().addItem(reward);
        for (ItemStack item : overflow.values()) {
            target.getWorld().dropItemNaturally(target.getLocation(), item);
        }
        target.sendMessage(MessageStyle.prefix()
                .append(Component.text("You have received the ", NamedTextColor.GRAY))
                .append(Component.text(itemName, itemColor))
                .append(Component.text("!", NamedTextColor.GRAY)));
        if (!sender.equals(target)) {
            sender.sendMessage(MessageStyle.prefix()
                    .append(Component.text("Gave ", NamedTextColor.GRAY))
                    .append(Component.text(itemName, itemColor))
                    .append(Component.text(" to ", NamedTextColor.GRAY))
                    .append(Component.text(target.getName(), NamedTextColor.GREEN))
                    .append(Component.text(".", NamedTextColor.GRAY)));
        }
    }

    private ItemStack createSword(SwordType type) {
        ItemStack sword = new ItemStack(Material.NETHERITE_SWORD);
        ItemMeta meta = sword.getItemMeta();
        meta.displayName(Component.text(type.itemName, type.color)
                .decoration(TextDecoration.ITALIC, false));
        meta.addEnchant(Enchantment.SHARPNESS, 5, true);
        meta.addEnchant(Enchantment.SWEEPING_EDGE, 3, true);
        meta.addEnchant(Enchantment.FIRE_ASPECT, 2, true);
        meta.setUnbreakable(true);
        meta.addItemFlags(ItemFlag.HIDE_ENCHANTS, ItemFlag.HIDE_UNBREAKABLE);
        meta.lore(type.lore.stream()
                .map(line -> Component.text(line, NamedTextColor.GRAY)
                        .decoration(TextDecoration.ITALIC, false))
                .toList());
        meta.getPersistentDataContainer().set(swordTypeKey, PersistentDataType.STRING, type.id);
        sword.setItemMeta(meta);
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
        if (item == null || item.getType() != Material.NETHERITE_SWORD || !item.hasItemMeta()) {
            return null;
        }
        ItemMeta meta = item.getItemMeta();
        String stored = meta.getPersistentDataContainer().get(swordTypeKey, PersistentDataType.STRING);
        SwordType tagged = SwordType.fromName(stored);
        if (tagged != null) {
            return tagged;
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
            boolean hasAbilityMarker = meta.lore().stream()
                    .map(PlainTextComponentSerializer.plainText()::serialize)
                    .anyMatch(type.abilityMarker::equals);
            if (plainName.equals(type.itemName) && hasAbilityMarker) {
                meta.getPersistentDataContainer().set(
                        swordTypeKey, PersistentDataType.STRING, type.id);
                item.setItemMeta(meta);
                return type;
            }
        }
        return null;
    }

    private void dash(Player player) {
        long now = System.currentTimeMillis();
        long readyAt = dashCooldowns.getOrDefault(player.getUniqueId(), 0L);
        if (readyAt > now) {
            long seconds = Math.max(1L, (readyAt - now + 999L) / 1_000L);
            player.sendMessage(MessageStyle.prefixed(
                    "You must wait " + seconds + " seconds before using this ability again."));
            return;
        }

        Vector direction = player.getLocation().getDirection().normalize().multiply(2.0D);
        player.setVelocity(direction);
        UUID playerId = player.getUniqueId();
        long expiresAt = now + DASH_COOLDOWN_MILLIS;
        dashCooldowns.put(playerId, expiresAt);
        Bukkit.getScheduler().runTaskLater(plugin,
                () -> dashCooldowns.remove(playerId, expiresAt),
                DASH_COOLDOWN_MILLIS / 50L);
        player.sendMessage(MessageStyle.prefixed("You have dashed through the air!"));
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
        STRIKE("strike", "⚡ Strike Sword", NamedTextColor.YELLOW,
                "A chance to strike enemies with lightning", List.of(
                "Sharpness V",
                "Sweeping Edge III",
                "Fire Aspect II",
                "Unbreakable",
                "A chance to strike enemies with lightning"
        )),
        DASH("dash", "🚀 Dash Sword", NamedTextColor.GREEN,
                "Lets you dash through the air!", List.of(
                "Sharpness V",
                "Sweeping Edge III",
                "Fire Aspect II",
                "Unbreakable",
                "Lets you dash through the air!",
                "Can be activated by shift right clicking while holding the sword"
        )),
        EXECUTIONER("executioner", "☠ Executioner Sword", NamedTextColor.GOLD,
                "A chance to drop player's heads when you kill them", List.of(
                "Sharpness V",
                "Sweeping Edge III",
                "Fire Aspect II",
                "Unbreakable",
                "A chance to drop player's heads when you kill them"
        )),
        VOID("void", "🌑 Void Sword", NamedTextColor.DARK_GRAY,
                "A chance to take enemies' vision!", List.of(
                "Sharpness V",
                "Sweeping Edge III",
                "Fire Aspect II",
                "Unbreakable",
                "A chance to take enemies' vision!"
        ));

        private final String id;
        private final String itemName;
        private final TextColor color;
        private final String abilityMarker;
        private final List<String> lore;

        SwordType(String id, String itemName, TextColor color, String abilityMarker,
                  List<String> lore) {
            this.id = id;
            this.itemName = itemName;
            this.color = color;
            this.abilityMarker = abilityMarker;
            this.lore = lore;
        }

        private static SwordType fromName(String name) {
            if (name == null) {
                return null;
            }
            for (SwordType type : values()) {
                if (type.id.equalsIgnoreCase(name)) {
                    return type;
                }
            }
            return null;
        }

    }
}
