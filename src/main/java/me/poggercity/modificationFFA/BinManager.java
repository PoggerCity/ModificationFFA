package me.poggercity.modificationFFA;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.format.TextColor;
import net.kyori.adventure.text.format.TextDecoration;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
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

final class BinManager implements Listener, AutoCloseable {

    private static final int INVENTORY_SIZE = 36;
    private static final int STORAGE_SIZE = 27;
    private static final int DELETE_SLOT = 31;
    private static final long ANIMATION_PERIOD_TICKS = 2L;
    private static final int ANIMATION_FRAME_COUNT = 40;
    private static final String DELETE_LABEL = "Delete Items";
    private static final int GRADIENT_PURPLE = 0xA000B8;
    private static final int GRADIENT_YELLOW = 0xFFD21A;

    private final ModificationFFA plugin;
    private final Map<UUID, BinHolder> openBins = new HashMap<>();
    private final List<ItemStack> deleteFrames;
    private final ItemStack filler;

    private BukkitTask animationTask;
    private int currentFrame;

    BinManager(ModificationFFA plugin) {
        this.plugin = plugin;
        this.deleteFrames = createDeleteFrames();
        this.filler = createFiller();
    }

    void start() {
        plugin.getServer().getPluginManager().registerEvents(this, plugin);
        animationTask = plugin.getServer().getScheduler().runTaskTimer(
                plugin,
                this::animateOpenBins,
                ANIMATION_PERIOD_TICKS,
                ANIMATION_PERIOD_TICKS
        );
    }

    boolean open(CommandSender sender) {
        if (!(sender instanceof Player player)) {
            sender.sendMessage(Component.text("This command can only be used by players.", NamedTextColor.GRAY));
            return true;
        }

        if (player.getOpenInventory().getTopInventory().getHolder(false) instanceof BinHolder) {
            player.closeInventory();
        }

        BinHolder holder = new BinHolder();
        Inventory inventory = Bukkit.createInventory(
                holder,
                INVENTORY_SIZE,
                Component.text("Modification Bin", NamedTextColor.LIGHT_PURPLE)
        );
        holder.setInventory(inventory);

        for (int slot = STORAGE_SIZE; slot < INVENTORY_SIZE; slot++) {
            inventory.setItem(slot, slot == DELETE_SLOT ? deleteFrames.get(currentFrame) : filler);
        }

        openBins.put(player.getUniqueId(), holder);
        player.openInventory(inventory);
        return true;
    }

    @EventHandler
    public void onInventoryClick(InventoryClickEvent event) {
        if (!(event.getView().getTopInventory().getHolder(false) instanceof BinHolder holder)) {
            return;
        }

        int rawSlot = event.getRawSlot();
        if (rawSlot < STORAGE_SIZE || rawSlot >= INVENTORY_SIZE) {
            return;
        }

        event.setCancelled(true);
        if (rawSlot == DELETE_SLOT) {
            deleteStoredItems(holder);
        }
    }

    @EventHandler
    public void onInventoryDrag(InventoryDragEvent event) {
        if (!(event.getView().getTopInventory().getHolder(false) instanceof BinHolder)) {
            return;
        }

        if (event.getRawSlots().stream().anyMatch(slot -> slot >= STORAGE_SIZE && slot < INVENTORY_SIZE)) {
            event.setCancelled(true);
        }
    }

    @EventHandler
    public void onInventoryClose(InventoryCloseEvent event) {
        if (!(event.getInventory().getHolder(false) instanceof BinHolder holder)) {
            return;
        }

        openBins.remove(event.getPlayer().getUniqueId(), holder);
        returnStoredItems(holder, (Player) event.getPlayer());
    }

    @Override
    public void close() {
        if (animationTask != null) {
            animationTask.cancel();
            animationTask = null;
        }

        for (Map.Entry<UUID, BinHolder> entry : new ArrayList<>(openBins.entrySet())) {
            Player player = Bukkit.getPlayer(entry.getKey());
            if (player != null) {
                returnStoredItems(entry.getValue(), player);
                player.closeInventory();
            }
        }
        openBins.clear();
    }

    private void animateOpenBins() {
        currentFrame = (currentFrame + 1) % deleteFrames.size();
        ItemStack frame = deleteFrames.get(currentFrame);

        openBins.entrySet().removeIf(entry -> {
            Player player = Bukkit.getPlayer(entry.getKey());
            BinHolder holder = entry.getValue();
            if (player == null || player.getOpenInventory().getTopInventory() != holder.getInventory()) {
                return true;
            }
            holder.getInventory().setItem(DELETE_SLOT, frame);
            return false;
        });
    }

    private void deleteStoredItems(BinHolder holder) {
        Inventory inventory = holder.getInventory();
        for (int slot = 0; slot < STORAGE_SIZE; slot++) {
            inventory.clear(slot);
        }
    }

    private void returnStoredItems(BinHolder holder, Player player) {
        if (!holder.markResolved()) {
            return;
        }

        Inventory inventory = holder.getInventory();
        for (int slot = 0; slot < STORAGE_SIZE; slot++) {
            ItemStack item = inventory.getItem(slot);
            if (item == null || item.getType().isAir()) {
                continue;
            }

            inventory.clear(slot);
            for (ItemStack overflow : player.getInventory().addItem(item).values()) {
                player.getWorld().dropItemNaturally(player.getLocation(), overflow);
            }
        }
    }

    private List<ItemStack> createDeleteFrames() {
        List<ItemStack> frames = new ArrayList<>(ANIMATION_FRAME_COUNT);
        for (int phase = 0; phase < ANIMATION_FRAME_COUNT; phase++) {
            Component name = Component.empty();
            for (int character = 0; character < DELETE_LABEL.length(); character++) {
                double position = (character / (double) (DELETE_LABEL.length() - 1)
                        + phase / (double) ANIMATION_FRAME_COUNT) % 1.0;
                double progress = (1.0 - Math.cos(position * Math.PI * 2.0)) / 2.0;
                TextColor color = gradientColor(progress);
                name = name.append(Component.text(DELETE_LABEL.charAt(character), color));
            }

            ItemStack button = new ItemStack(Material.LAVA_BUCKET);
            ItemMeta meta = button.getItemMeta();
            meta.displayName(name.decoration(TextDecoration.ITALIC, false));
            button.setItemMeta(meta);
            frames.add(button);
        }
        return List.copyOf(frames);
    }

    private TextColor gradientColor(double progress) {
        int red = interpolateChannel(GRADIENT_PURPLE >> 16, GRADIENT_YELLOW >> 16, progress);
        int green = interpolateChannel(GRADIENT_PURPLE >> 8, GRADIENT_YELLOW >> 8, progress);
        int blue = interpolateChannel(GRADIENT_PURPLE, GRADIENT_YELLOW, progress);
        return TextColor.color(red, green, blue);
    }

    private int interpolateChannel(int from, int to, double blend) {
        return (int) Math.round((from & 0xFF) + (((to & 0xFF) - (from & 0xFF)) * blend));
    }

    private ItemStack createFiller() {
        ItemStack item = new ItemStack(Material.GRAY_STAINED_GLASS_PANE);
        ItemMeta meta = item.getItemMeta();
        meta.displayName(Component.text(" ").decoration(TextDecoration.ITALIC, false));
        item.setItemMeta(meta);
        return item;
    }

    private static final class BinHolder implements InventoryHolder {

        private Inventory inventory;
        private boolean resolved;

        @Override
        public @NotNull Inventory getInventory() {
            if (inventory == null) {
                throw new IllegalStateException("Bin inventory has not been created yet.");
            }
            return inventory;
        }

        void setInventory(Inventory inventory) {
            this.inventory = inventory;
        }

        boolean markResolved() {
            if (resolved) {
                return false;
            }
            resolved = true;
            return true;
        }
    }
}
