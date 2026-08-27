package me.poggercity.modificationFFA;

import net.kyori.adventure.key.Key;
import net.kyori.adventure.sound.Sound;
import net.kyori.adventure.text.Component;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.scheduler.BukkitTask;

import java.util.List;
import java.util.Locale;

final class ReminderService implements AutoCloseable {

    private static final long TICKS_PER_MINUTE = 20L * 60L;

    private final ModificationFFA plugin;
    private final SettingsManager settings;

    private BukkitTask task;
    private LinkMessages messages;
    private Sound sound;
    private boolean discordNext;

    ReminderService(ModificationFFA plugin, SettingsManager settings) {
        this.plugin = plugin;
        this.settings = settings;
    }

    void reload(FileConfiguration config, LinkMessages messages) {
        close();
        this.messages = messages;
        sound = readSound(config);

        if (!config.getBoolean("reminders.enabled", true)) {
            return;
        }

        long minutes = Math.max(1L, Math.min(525_600L,
                config.getLong("reminders.spacing-minutes", 5L)));
        long period = minutes * TICKS_PER_MINUTE;
        discordNext = !"store".equalsIgnoreCase(config.getString("reminders.first", "discord"));
        task = plugin.getServer().getScheduler().runTaskTimer(plugin, this::broadcast, period, period);
    }

    private Sound readSound(FileConfiguration config) {
        if (!config.getBoolean("reminders.sound.enabled", false)) {
            return null;
        }

        String key = config.getString("reminders.sound.key", "minecraft:block.note_block.pling");
        String category = config.getString("reminders.sound.category", "MASTER");
        float volume = (float) Math.max(0.0, config.getDouble("reminders.sound.volume", 1.0));
        float pitch = (float) Math.max(0.01, config.getDouble("reminders.sound.pitch", 1.0));

        try {
            Sound.Source source = Sound.Source.valueOf(category.toUpperCase(Locale.ROOT));
            return Sound.sound(Key.key(key), source, volume, pitch);
        } catch (IllegalArgumentException exception) {
            plugin.getLogger().warning("Reminder sound is invalid and has been disabled: "
                    + exception.getMessage());
            return null;
        }
    }

    private void broadcast() {
        List<Component> lines = discordNext ? messages.discord() : messages.store();
        discordNext = !discordNext;

        for (Player player : plugin.getServer().getOnlinePlayers()) {
            if (!settings.broadcastTitlesEnabled(player)) {
                continue;
            }
            lines.forEach(player::sendMessage);
            if (sound != null) {
                player.playSound(sound);
            }
        }
    }

    @Override
    public void close() {
        if (task != null) {
            task.cancel();
            task = null;
        }
    }
}
