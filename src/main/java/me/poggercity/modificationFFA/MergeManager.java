package me.poggercity.modificationFFA;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.format.TextDecoration;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.HumanEntity;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.inventory.ClickType;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryCloseEvent;
import org.bukkit.event.inventory.InventoryDragEvent;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.scheduler.BukkitTask;
import org.jetbrains.annotations.NotNull;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

final class MergeManager implements Listener, AutoCloseable {

    private static final int INVENTORY_SIZE = 36;
    private static final int LEFT_INPUT_SLOT = 12;
    private static final int CONFIRM_SLOT = 13;
    private static final int RIGHT_INPUT_SLOT = 14;
    private static final int OUTPUT_SLOT = 22;
    private static final int ANIMATION_FRAME_COUNT = 120;

    private final ModificationFFA plugin;
    private final SwordManager swordManager;
    private final Map<UUID, MergeHolder> openMergers = new HashMap<>();
    private final ItemStack filler = createPane(Material.GRAY_STAINED_GLASS_PANE, " ");
    private final ItemStack confirm = createPane(
            Material.GREEN_STAINED_GLASS_PANE, "Confirm Bow Merge");
    private final ItemStack output = createPane(
            Material.WHITE_STAINED_GLASS_PANE, "Merged Punch Bow");
    private List<Component> confirmNameFrames;
    private List<Component> outputNameFrames;

    private BukkitTask animationTask;
    private int animationFrame;

    MergeManager(ModificationFFA plugin, SwordManager swordManager) {
        this.plugin = plugin;
        this.swordManager = swordManager;
        this.confirmNameFrames = createAnimatedFrames("Confirm Bow Merge");
        this.outputNameFrames = createAnimatedFrames("Merged Punch Bow");
    }

    void start() {
        plugin.getServer().getPluginManager().registerEvents(this, plugin);
        animationTask = Bukkit.getScheduler().runTaskTimer(
                plugin, this::animateOpenMergers, 1L, 1L);
    }

    void refreshTheme() {
        confirmNameFrames = createAnimatedFrames("Confirm Bow Merge");
        outputNameFrames = createAnimatedFrames("Merged Punch Bow");
    }

    boolean open(CommandSender sender) {
        if (!(sender instanceof Player player)) {
            sender.sendMessage(MessageStyle.prefixed("This command can only be used by players."));
            return true;
        }

        if (player.getOpenInventory().getTopInventory().getHolder(false) instanceof MergeHolder) {
            player.closeInventory();
        }

        MergeHolder holder = new MergeHolder(player);
        Inventory inventory = Bukkit.createInventory(
                holder,
                INVENTORY_SIZE,
                Component.text("Punch Bow Merger", PluginTheme.primary())
                        .decoration(TextDecoration.ITALIC, false)
        );
        holder.setInventory(inventory);
        for (int slot = 0; slot < INVENTORY_SIZE; slot++) {
            if (slot != LEFT_INPUT_SLOT && slot != RIGHT_INPUT_SLOT) {
                inventory.setItem(slot, filler);
            }
        }
        inventory.setItem(CONFIRM_SLOT, confirm);
        inventory.setItem(OUTPUT_SLOT, output);

        player.openInventory(inventory);
        openMergers.put(player.getUniqueId(), holder);
        return true;
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onInventoryClick(InventoryClickEvent event) {
        Inventory top = event.getView().getTopInventory();
        if (!(top.getHolder(false) instanceof MergeHolder holder)
                || !(event.getWhoClicked() instanceof Player player)) {
            return;
        }

        int rawSlot = event.getRawSlot();
        if (rawSlot >= INVENTORY_SIZE) {
            if (event.getClick() == ClickType.DOUBLE_CLICK) {
                event.setCancelled(true);
                return;
            }
            if (event.isShiftClick()) {
                event.setCancelled(true);
                moveBottomBowIntoInput(event, top);
            }
            return;
        }

        event.setCancelled(true);
        if (rawSlot == LEFT_INPUT_SLOT || rawSlot == RIGHT_INPUT_SLOT) {
            handleInputClick(player, top, rawSlot, event.getClick());
        } else if (rawSlot == CONFIRM_SLOT) {
            merge(player, holder);
        }
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onInventoryDrag(InventoryDragEvent event) {
        if (event.getView().getTopInventory().getHolder(false) instanceof MergeHolder) {
            event.setCancelled(true);
        }
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onInventoryClose(InventoryCloseEvent event) {
        if (!(event.getInventory().getHolder(false) instanceof MergeHolder holder)
                || !(event.getPlayer() instanceof Player player)) {
            return;
        }

        openMergers.remove(player.getUniqueId(), holder);
        returnInputs(holder, player);
    }

    @Override
    public void close() {
        if (animationTask != null) {
            animationTask.cancel();
            animationTask = null;
        }
        for (Map.Entry<UUID, MergeHolder> entry : new ArrayList<>(openMergers.entrySet())) {
            MergeHolder holder = entry.getValue();
            Player player = Bukkit.getPlayer(entry.getKey());
            if (player == null) {
                player = viewer(holder);
            }
            if (player == null) {
                player = holder.owner;
            }

            returnInputs(holder, player);
            if (player.getOpenInventory().getTopInventory() == holder.getInventory()) {
                player.closeInventory();
            }
        }
        openMergers.clear();
    }

    private void animateOpenMergers() {
        animationFrame = (animationFrame + 1) % ANIMATION_FRAME_COUNT;
        for (Map.Entry<UUID, MergeHolder> entry : openMergers.entrySet()) {
            Player player = Bukkit.getPlayer(entry.getKey());
            MergeHolder holder = entry.getValue();
            if (player == null || !player.isOnline()
                    || player.getOpenInventory().getTopInventory().getHolder(false) != holder) {
                continue;
            }
            Inventory inventory = holder.getInventory();
            animateControl(inventory, CONFIRM_SLOT, confirmNameFrames.get(animationFrame));
            animateControl(inventory, OUTPUT_SLOT, outputNameFrames.get(animationFrame));
        }
    }

    private void animateControl(Inventory inventory, int slot, Component displayName) {
        ItemStack item = inventory.getItem(slot);
        if (isEmpty(item)) {
            return;
        }
        ItemMeta meta = item.getItemMeta();
        meta.displayName(displayName.decoration(TextDecoration.ITALIC, false));
        item.setItemMeta(meta);
        inventory.setItem(slot, item);
    }

    private List<Component> createAnimatedFrames(String label) {
        return java.util.stream.IntStream.range(0, ANIMATION_FRAME_COUNT)
                .mapToObj(frame -> GradientText.animatedEvenRightToLeft(
                                label, frame, ANIMATION_FRAME_COUNT)
                        .decoration(TextDecoration.ITALIC, false))
                .toList();
    }

    private void moveBottomBowIntoInput(InventoryClickEvent event, Inventory top) {
        ItemStack clicked = event.getCurrentItem();
        if (!swordManager.isPunchBow(clicked)) {
            return;
        }

        int inputSlot = emptyInputSlot(top);
        if (inputSlot < 0 || event.getClickedInventory() == null) {
            return;
        }

        ItemStack moved = clicked.clone();
        moved.setAmount(1);
        top.setItem(inputSlot, moved);
        if (clicked.getAmount() <= 1) {
            event.getClickedInventory().clear(event.getSlot());
        } else {
            ItemStack remainder = clicked.clone();
            remainder.setAmount(clicked.getAmount() - 1);
            event.getClickedInventory().setItem(event.getSlot(), remainder);
        }
    }

    private void handleInputClick(Player player, Inventory top, int slot, ClickType click) {
        if (click != ClickType.LEFT && click != ClickType.RIGHT) {
            return;
        }

        ItemStack current = top.getItem(slot);
        ItemStack cursor = player.getItemOnCursor();
        if (isEmpty(cursor)) {
            if (!isEmpty(current)) {
                top.clear(slot);
                player.setItemOnCursor(current);
            }
            return;
        }

        if (!isEmpty(current) || !swordManager.isPunchBow(cursor)) {
            return;
        }

        ItemStack moved = cursor.clone();
        moved.setAmount(1);
        top.setItem(slot, moved);
        if (cursor.getAmount() <= 1) {
            player.setItemOnCursor(null);
        } else {
            ItemStack remainder = cursor.clone();
            remainder.setAmount(cursor.getAmount() - 1);
            player.setItemOnCursor(remainder);
        }
    }

    private void merge(Player player, MergeHolder holder) {
        Inventory inventory = holder.getInventory();
        ItemStack left = inventory.getItem(LEFT_INPUT_SLOT);
        ItemStack right = inventory.getItem(RIGHT_INPUT_SLOT);
        if (!swordManager.isPunchBow(left) || !swordManager.isPunchBow(right)) {
            return;
        }

        ItemStack merged = swordManager.mergePunchBows(left.clone(), right.clone());
        if (isEmpty(merged)) {
            return;
        }

        inventory.clear(LEFT_INPUT_SLOT);
        inventory.clear(RIGHT_INPUT_SLOT);
        giveOrDrop(player, merged);
    }

    private void returnInputs(MergeHolder holder, Player player) {
        if (!holder.markResolved()) {
            return;
        }

        Inventory inventory = holder.getInventory();
        for (int slot : List.of(LEFT_INPUT_SLOT, RIGHT_INPUT_SLOT)) {
            ItemStack item = inventory.getItem(slot);
            if (isEmpty(item)) {
                continue;
            }
            inventory.clear(slot);
            giveOrDrop(player, item);
        }
    }

    private void giveOrDrop(Player player, ItemStack item) {
        for (ItemStack overflow : player.getInventory().addItem(item).values()) {
            player.getWorld().dropItemNaturally(player.getLocation(), overflow);
        }
    }

    private int emptyInputSlot(Inventory inventory) {
        if (isEmpty(inventory.getItem(LEFT_INPUT_SLOT))) {
            return LEFT_INPUT_SLOT;
        }
        if (isEmpty(inventory.getItem(RIGHT_INPUT_SLOT))) {
            return RIGHT_INPUT_SLOT;
        }
        return -1;
    }

    private Player viewer(MergeHolder holder) {
        for (HumanEntity viewer : holder.getInventory().getViewers()) {
            if (viewer instanceof Player player && player.getUniqueId().equals(holder.ownerId)) {
                return player;
            }
        }
        return null;
    }

    private boolean isEmpty(ItemStack item) {
        return item == null || item.getType().isAir() || item.getAmount() <= 0;
    }

    private static ItemStack createPane(Material material, String name) {
        ItemStack item = new ItemStack(material);
        ItemMeta meta = item.getItemMeta();
        Component displayName = " ".equals(name)
                ? Component.text(" ", NamedTextColor.GRAY)
                : GradientText.staticGradient(name);
        meta.displayName(displayName.decoration(TextDecoration.ITALIC, false));
        item.setItemMeta(meta);
        return item;
    }

    private static final class MergeHolder implements InventoryHolder {

        private final UUID ownerId;
        private final Player owner;
        private Inventory inventory;
        private boolean resolved;

        private MergeHolder(Player owner) {
            this.ownerId = owner.getUniqueId();
            this.owner = owner;
        }

        @Override
        public @NotNull Inventory getInventory() {
            if (inventory == null) {
                throw new IllegalStateException("Merge inventory has not been created yet.");
            }
            return inventory;
        }

        private void setInventory(Inventory inventory) {
            this.inventory = inventory;
        }

        private boolean markResolved() {
            if (resolved) {
                return false;
            }
            resolved = true;
            return true;
        }
    }
}
