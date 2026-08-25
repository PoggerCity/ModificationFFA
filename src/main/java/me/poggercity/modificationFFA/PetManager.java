package me.poggercity.modificationFFA;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.format.TextColor;
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
    private static final int MAX_RAW_NAME_LENGTH = 512;
    private static final int MAX_VISIBLE_NAME_LENGTH = 32;
    private static final long FOLLOW_INTERVAL_TICKS = 10L;
    private static final double PATHFIND_DISTANCE_SQUARED = 9.0D;
    private static final double TELEPORT_DISTANCE_SQUARED = 144.0D;

    private final ModificationFFA plugin;
    private final NamespacedKey ownerKey;
    private final Map<UUID, UUID> ownerPets = new HashMap<>();
    private final Map<UUID, UUID> petOwners = new HashMap<>();
    private final Set<UUID> sittingPets = new HashSet<>();

    private BukkitTask followTask;

    PetManager(ModificationFFA plugin) {
        this.plugin = plugin;
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
            sender.sendMessage(MessageStyle.prefixed("This command can only be used by players."));
            return;
        }
        if (args.length < 3) {
            player.sendMessage(MessageStyle.prefixed(
                    "Usage: /pet spawn <rabbit|frog|cat|wolf> <name>"));
            return;
        }

        PetType petType = PetType.fromId(args[1]);
        if (petType == null) {
            player.sendMessage(MessageStyle.prefixed("Unknown pet. Use rabbit, frog, cat, or wolf."));
            return;
        }
        if (!petType.canSpawn(player)) {
            player.sendMessage(MessageStyle.permissionDenied(petType.permission));
            return;
        }

        String rawName = String.join(" ", Arrays.copyOfRange(args, 2, args.length));
        if (rawName.length() > MAX_RAW_NAME_LENGTH) {
            player.sendMessage(MessageStyle.prefixed("That pet name is too long."));
            return;
        }
        Component name = parseRgbName(rawName);
        String visibleName = PlainTextComponentSerializer.plainText().serialize(name).trim();
        if (visibleName.isEmpty() || visibleName.length() > MAX_VISIBLE_NAME_LENGTH) {
            player.sendMessage(MessageStyle.prefixed(
                    "Pet names must contain between 1 and 32 visible characters."));
            return;
        }

        removePet(player.getUniqueId());
        Entity spawned = player.getWorld().spawnEntity(player.getLocation(), petType.entityType);
        if (!(spawned instanceof Mob pet)) {
            spawned.remove();
            player.sendMessage(MessageStyle.prefixed("That pet could not be spawned."));
            return;
        }
        configurePet(player, pet, name);
        ownerPets.put(player.getUniqueId(), pet.getUniqueId());
        petOwners.put(pet.getUniqueId(), player.getUniqueId());

        player.sendMessage(MessageStyle.prefix()
                .append(Component.text("You spawned your ", NamedTextColor.GRAY))
                .append(Component.text(petType.id, NamedTextColor.GREEN))
                .append(Component.text(" pet.", NamedTextColor.GRAY)));
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
            sender.sendMessage(MessageStyle.prefixed("This command can only be used by players."));
            return;
        }
        if (!removePet(player.getUniqueId())) {
            player.sendMessage(MessageStyle.prefixed("You do not have a pet to despawn."));
            return;
        }
        player.sendMessage(MessageStyle.prefixed("You have despawned your pet."));
    }

    private void listPets(CommandSender sender) {
        if (!sender.hasPermission(LIST_PERMISSION)) {
            sender.sendMessage(MessageStyle.permissionDenied(LIST_PERMISSION));
            return;
        }
        sender.sendMessage(Component.text("Available pets: ", NamedTextColor.GRAY)
                .append(Component.text("rabbit, frog, cat, wolf", NamedTextColor.GREEN)));
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
        owner.sendMessage(MessageStyle.prefixed(
                sitting ? "Your pet is now sitting." : "Your pet is now following you."));
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
        sender.sendMessage(Component.text("------ ", NamedTextColor.GRAY)
                .append(Component.text("pet help", NamedTextColor.GREEN))
                .append(Component.text(" ------", NamedTextColor.GRAY)));
        helpLine(sender, "/pet help", "The command to display helpful information.");
        helpLine(sender, "/pet spawn", "Spawn your permitted pet with an RGB name.");
        helpLine(sender, "/pet despawn", "Despawn your active pet.");
        if (sender.hasPermission(LIST_PERMISSION)) {
            helpLine(sender, "/pet list", "Get a list of all the pets.");
        }
        sender.sendMessage(Component.text("Right click your pet to make it sit or follow.",
                NamedTextColor.GRAY));
    }

    private void helpLine(CommandSender sender, String command, String description) {
        sender.sendMessage(Component.text("- ", NamedTextColor.GRAY)
                .append(Component.text(command, NamedTextColor.GREEN))
                .append(Component.text(" - " + description, NamedTextColor.GRAY)));
    }

    private Component parseRgbName(String input) {
        Component result = Component.empty();
        TextColor color = NamedTextColor.WHITE;
        StringBuilder text = new StringBuilder();
        for (int index = 0; index < input.length(); index++) {
            if (index + 8 <= input.length() && input.charAt(index) == '&'
                    && input.charAt(index + 1) == '#') {
                String hex = input.substring(index + 2, index + 8);
                if (isHex(hex)) {
                    result = appendSegment(result, text, color);
                    color = TextColor.color(Integer.parseInt(hex, 16));
                    index += 7;
                    continue;
                }
            }
            text.append(input.charAt(index));
        }
        return appendSegment(result, text, color).decoration(TextDecoration.ITALIC, false);
    }

    private Component appendSegment(Component result, StringBuilder text, TextColor color) {
        if (text.isEmpty()) {
            return result;
        }
        Component segment = Component.text(text.toString(), color)
                .decoration(TextDecoration.ITALIC, false);
        text.setLength(0);
        return result.append(segment);
    }

    private boolean isHex(String value) {
        for (int index = 0; index < value.length(); index++) {
            if (Character.digit(value.charAt(index), 16) < 0) {
                return false;
            }
        }
        return true;
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
