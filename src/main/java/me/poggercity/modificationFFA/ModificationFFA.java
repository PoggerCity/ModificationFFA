package me.poggercity.modificationFFA;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.plugin.java.JavaPlugin;

import java.util.List;

import org.jetbrains.annotations.NotNull;

public final class ModificationFFA extends JavaPlugin {

    @Override
    public void onEnable() {
        getLogger().info("ModificationFFA has been enabled.");
    }

    @Override
    public void onDisable() {
        getLogger().info("ModificationFFA has been disabled.");
    }

    @Override
    public boolean onCommand(
            @NotNull CommandSender sender,
            @NotNull Command command,
            @NotNull String label,
            @NotNull String[] args
    ) {
        if (!command.getName().equalsIgnoreCase("modification")) {
            return false;
        }

        String pluginName = getPluginMeta().getName();
        String version = getPluginMeta().getVersion();
        List<String> authors = getPluginMeta().getAuthors();
        String author = authors.isEmpty() ? "Unknown" : String.join(", ", authors);

        sender.sendMessage(Component.text("The server is running ", NamedTextColor.GRAY)
                .append(Component.text(pluginName + " v" + version, NamedTextColor.GREEN)));
        sender.sendMessage(Component.text("The plugin was created by: ", NamedTextColor.GRAY)
                .append(Component.text(author, NamedTextColor.GREEN)));
        sender.sendMessage(Component.text("Run ", NamedTextColor.GRAY)
                .append(Component.text("/modification help", NamedTextColor.GREEN))
                .append(Component.text(" for sub commands.", NamedTextColor.GRAY)));

        return true;
    }
}
