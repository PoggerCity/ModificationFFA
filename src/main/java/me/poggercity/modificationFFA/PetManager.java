package me.poggercity.modificationFFA;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.TextDecoration;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
import org.bukkit.Bukkit;
import org.bukkit.NamespacedKey;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Ageable;
import org.bukkit.entity.Entity;
import org.bukkit.entity.EntityType;
import org.bukkit.entity.Mob;
import org.bukkit.entity.Player;
import org.bukkit.entity.Pose;
import org.bukkit.entity.Sittable;
import org.bukkit.entity.Tameable;
import org.bukkit.entity.Wolf;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.HandlerList;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.EntityCombustEvent;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.event.entity.EntityDamageEvent;
import org.bukkit.event.entity.EntityTargetLivingEntityEvent;
import org.bukkit.event.player.PlayerInteractEntityEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.scheduler.BukkitTask;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

final class PetManager implements Listener, AutoCloseable {

    private static final String LIST_PERMISSION = "modificationffa.pet.list";
    private static final int MAX_RAW_NAME_LENGTH = 2_048;
    private static final int MAX_VISIBLE_NAME_LENGTH = 64;
    private static final long FOLLOW_INTERVAL_TICKS = 10L;
    private static final double PATHFIND_DISTANCE_SQUARED = 9.0D;
    private static final double TELEPORT_DISTANCE_SQUARED = 144.0D;

    private final ModificationFFA plugin;
    private final PluginMessages messages;
    private final NamespacedKey ownerKey;
    private final Map<UUID, UUID> ownerPets = new HashMap<>();
    private final Map<UUID, UUID> petOwners = new HashMap<>();
    private final Set<UUID> sittingPets = new HashSet<>();

    private BukkitTask followTask;

    PetManager(ModificationFFA plugin, PluginMessages messages) {
        this.plugin = plugin;
        this.messages = messages;
        this.ownerKey = new NamespacedKey(plugin, "pet_owner");
    }

    void start() {
        plugin.getServer().getPluginManager().registerEvents(this, plugin);
        followTask = Bukkit.getScheduler().runTaskTimer(
                plugin, this::updateFollowingPets, FOLLOW_INTERVAL_TICKS, FOLLOW_INTERVAL_TICKS);
    }

    boolean handleCommand(CommandSender sender, String[] args) {
        String subcommand = args.length == 0 ? "help" : args[0].toLowerCase(Locale.ROOT);
        return switch (subcommand) {
            case "help" -> {
                sendHelp(sender);
                yield true;
            }
            case "spawn" -> {
                spawnPet(sender, args);
                yield true;
            }
            case "despawn" -> {
                despawnPet(sender);
                yield true;
            }
            case "list" -> {
                listPets(sender);
                yield true;
            }
            default -> {
                sendHelp(sender);
                yield true;
            }
        };
    }

    List<String> tabComplete(CommandSender sender, String[] args) {
        if (args.length == 1) {
            List<String> options = new ArrayList<>(List.of("help", "despawn"));
            if (PetType.valuesList().stream().anyMatch(type -> type.canSpawn(sender))) {
                options.add("spawn");
            }
            if (sender.hasPermission(LIST_PERMISSION)) {
                options.add("list");
            }
            return filter(options, args[0]);
        }
        if (args.length == 2 && args[0].equalsIgnoreCase("spawn")) {
            return filter(PetType.valuesList().stream()
                    .filter(type -> type.canSpawn(sender))
                    .map(type -> type.id)
                    .toList(), args[1]);
        }
        return List.of();
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = false)
    public void onPetDamage(EntityDamageEvent event) {
        if (!isPet(event.getEntity())) {
            return;
        }
        event.setCancelled(true);
        event.getEntity().setFireTicks(0);
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = false)
    public void onPetDealsDamage(EntityDamageByEntityEvent event) {
        if (isPet(event.getDamager())) {
            event.setCancelled(true);
        }
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = false)
    public void onPetTargets(EntityTargetLivingEntityEvent event) {
        if (!isPet(event.getEntity())
                && (event.getTarget() == null || !isPet(event.getTarget()))) {
            return;
        }
        event.setCancelled(true);
        if (event.getEntity() instanceof Mob mob) {
            mob.setTarget(null);
        }
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = false)
    public void onPetCombust(EntityCombustEvent event) {
        if (isPet(event.getEntity())) {
            event.setCancelled(true);
            event.getEntity().setFireTicks(0);
        }
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = false)
    public void onPetInteract(PlayerInteractEntityEvent event) {
        if (event.getHand() != EquipmentSlot.HAND) {
            return;
        }
        UUID ownerId = petOwners.get(event.getRightClicked().getUniqueId());
        if (ownerId == null || !ownerId.equals(event.getPlayer().getUniqueId())) {
            return;
        }
        event.setCancelled(true);
        toggleSitting(event.getPlayer(), event.getRightClicked());
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent event) {
        removePet(event.getPlayer().getUniqueId());
    }

    @Override
    public void close() {
        HandlerList.unregisterAll(this);
        if (followTask != null) {
            followTask.cancel();
            followTask = null;
        }
        for (UUID ownerId : new ArrayList<>(ownerPets.keySet())) {
            removePet(ownerId);
        }
        ownerPets.clear();
        petOwners.clear();
        sittingPets.clear();
    }

    private void spawnPet(CommandSender sender, String[] args) {
        if (!(sender instanceof Player player)) {
            messages.sendPrefixed(sender, "core.players-only");
            return;
        }
        if (args.length < 3) {
            messages.sendPrefixed(player, "pet.usage.spawn");
            return;
        }

        PetType petType = PetType.fromId(args[1]);
        if (petType == null) {
            messages.sendPrefixed(player, "pet.unknown");
            return;
        }
        if (!petType.canSpawn(player)) {
            messages.sendPrefixed(player, "core.no-permission", Map.of("permission", petType.permission));
            return;
        }

        String rawName = String.join(" ", Arrays.copyOfRange(args, 2, args.length));
        if (rawName.length() > MAX_RAW_NAME_LENGTH) {
            messages.sendPrefixed(player, "pet.name.raw-too-long");
            return;
        }
        Component name = PetNameFormatter.format(rawName);
        String visibleName = PlainTextComponentSerializer.plainText().serialize(name).trim();
        if (visibleName.isEmpty() || visibleName.codePointCount(0, visibleName.length()) > MAX_VISIBLE_NAME_LENGTH) {
            messages.sendPrefixed(player, "pet.name.visible-length");
            return;
        }

        removePet(player.getUniqueId());
        Entity spawned = player.getWorld().spawnEntity(player.getLocation(), petType.entityType);
        if (!(spawned instanceof Mob pet)) {
            spawned.remove();
            messages.sendPrefixed(player, "pet.spawn-failed");
            return;
        }
        configurePet(player, pet, name);
        ownerPets.put(player.getUniqueId(), pet.getUniqueId());
        petOwners.put(pet.getUniqueId(), player.getUniqueId());

        messages.sendPrefixed(player, "pet.spawned", Map.of("type", petType.id));
    }

    private void configurePet(Player owner, Mob pet, Component name) {
        pet.customName(name.decoration(TextDecoration.ITALIC, false));
        pet.setCustomNameVisible(true);
        pet.setInvulnerable(true);
        pet.setPersistent(false);
        pet.setRemoveWhenFarAway(false);
        pet.setCanPickupItems(false);
        pet.setCollidable(false);
        pet.setGravity(true);
        pet.setAI(true);
        pet.setAware(true);
        pet.setAggressive(false);
        pet.setTarget(null);
        if (pet instanceof Ageable ageable) {
            ageable.setAdult();
            ageable.setAgeLock(true);
            ageable.setBreed(false);
        }
        if (pet instanceof Tameable tameable) {
            tameable.setTamed(true);
            tameable.setOwner(owner);
        }
        if (pet instanceof Sittable sittable) {
            sittable.setSitting(false);
        }
        if (pet instanceof Wolf wolf) {
            wolf.setAngry(false);
        }
        pet.getPersistentDataContainer().set(
                ownerKey, PersistentDataType.STRING, owner.getUniqueId().toString());
    }

    private void despawnPet(CommandSender sender) {
        if (!(sender instanceof Player player)) {
            messages.sendPrefixed(sender, "core.players-only");
            return;
        }
        if (!removePet(player.getUniqueId())) {
            messages.sendPrefixed(player, "pet.none-active");
            return;
        }
        messages.sendPrefixed(player, "pet.despawned");
    }

    private void listPets(CommandSender sender) {
        if (!sender.hasPermission(LIST_PERMISSION)) {
            messages.sendPrefixed(sender, "core.no-permission", Map.of("permission", LIST_PERMISSION));
            return;
        }
        messages.send(sender, "pet.available");
    }

    private boolean removePet(UUID ownerId) {
        UUID petId = ownerPets.remove(ownerId);
        if (petId == null) {
            return false;
        }
        petOwners.remove(petId);
        sittingPets.remove(petId);
        Entity pet = Bukkit.getEntity(petId);
        if (pet != null) {
            pet.remove();
        }
        return true;
    }

    private void toggleSitting(Player owner, Entity entity) {
        if (!(entity instanceof Mob pet)) {
            return;
        }
        UUID petId = pet.getUniqueId();
        boolean sitting = !sittingPets.contains(petId);
        if (sitting) {
            sittingPets.add(petId);
        } else {
            sittingPets.remove(petId);
        }

        pet.getPathfinder().stopPathfinding();
        pet.setTarget(null);
        if (pet instanceof Sittable sittable) {
            sittable.setSitting(sitting);
        } else {
            pet.setAI(!sitting);
            pet.setPose(sitting ? Pose.SITTING : Pose.STANDING, sitting);
        }
        messages.sendPrefixed(owner, sitting ? "pet.sitting" : "pet.following");
    }

    private void updateFollowingPets() {
        for (Map.Entry<UUID, UUID> entry : new ArrayList<>(ownerPets.entrySet())) {
            Player owner = Bukkit.getPlayer(entry.getKey());
            Entity entity = Bukkit.getEntity(entry.getValue());
            if (owner == null || !owner.isOnline() || !(entity instanceof Mob pet) || !pet.isValid()) {
                removePet(entry.getKey());
                continue;
            }
            pet.setFireTicks(0);
            pet.setTarget(null);
            if (sittingPets.contains(pet.getUniqueId())) {
                pet.getPathfinder().stopPathfinding();
                continue;
            }
            if (!pet.getWorld().equals(owner.getWorld())
                    || pet.getLocation().distanceSquared(owner.getLocation()) > TELEPORT_DISTANCE_SQUARED) {
                pet.teleport(owner.getLocation());
                continue;
            }
            if (pet.getLocation().distanceSquared(owner.getLocation()) > PATHFIND_DISTANCE_SQUARED) {
                pet.getPathfinder().moveTo(owner, 1.15D);
            }
        }
    }

    private boolean isPet(Entity entity) {
        return petOwners.containsKey(entity.getUniqueId());
    }

    private void sendHelp(CommandSender sender) {
        messages.send(sender, "pet.help.title");
        messages.sendLines(sender, "pet.help.lines", Map.of());
        if (sender.hasPermission(LIST_PERMISSION)) {
            messages.send(sender, "pet.help.list");
        }
        messages.send(sender, "pet.help.interaction");
    }

    private List<String> filter(List<String> options, String partial) {
        String normalized = partial.toLowerCase(Locale.ROOT);
        return options.stream().filter(option -> option.startsWith(normalized)).toList();
    }

    private enum PetType {
        RABBIT("rabbit", EntityType.RABBIT),
        FROG("frog", EntityType.FROG),
        CAT("cat", EntityType.CAT),
        WOLF("wolf", EntityType.WOLF);

        private final String id;
        private final EntityType entityType;
        private final String permission;

        PetType(String id, EntityType entityType) {
            this.id = id;
            this.entityType = entityType;
            this.permission = "modificationffa.pet.spawn." + id;
        }

        private boolean canSpawn(CommandSender sender) {
            return sender.hasPermission(permission);
        }

        private static PetType fromId(String id) {
            for (PetType type : values()) {
                if (type.id.equalsIgnoreCase(id)) {
                    return type;
                }
            }
            return null;
        }

        private static List<PetType> valuesList() {
            return List.of(values());
        }
    }
}
