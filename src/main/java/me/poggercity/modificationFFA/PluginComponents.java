package me.poggercity.modificationFFA;

import java.io.IOException;
import java.util.UUID;

final class PluginComponents implements AutoCloseable {

    final SettingsManager settings;
    final KitManager kits;
    final BinManager bin;
    final PlayerUtilityCommands playerUtilities;
    final StatsManager stats;
    final BiomeManager biomes;
    final TokenManager tokens;
    final SpawnManager spawn;
    final CombatManager combat;
    final SocialManager social;
    final ArenaManager arenas;
    final ProtectArenaManager protectedArenas;
    final SwordManager swords;
    final MergeManager merge;
    final PetManager pets;

    private final ModificationFFA plugin;

    PluginComponents(ModificationFFA plugin, PluginMessages messages) {
        this.plugin = plugin;
        settings = new SettingsManager(plugin);
        kits = new KitManager(plugin, settings, messages);
        bin = new BinManager(plugin);
        playerUtilities = new PlayerUtilityCommands(messages);
        stats = new StatsManager(plugin, settings);
        biomes = new BiomeManager(plugin, messages);
        tokens = new TokenManager(plugin);
        spawn = new SpawnManager(plugin, messages);
        combat = new CombatManager(plugin, stats, spawn, tokens, messages);
        social = new SocialManager(plugin, settings, messages);
        arenas = new ArenaManager(plugin, messages);
        protectedArenas = new ProtectArenaManager(plugin, arenas, messages);
        swords = new SwordManager(plugin, settings, tokens, protectedArenas);
        merge = new MergeManager(plugin, swords);
        pets = new PetManager(plugin, messages);
    }

    void start() throws IOException {
        settings.start();
        kits.start();
        bin.start();
        stats.start();
        biomes.start();
        tokens.start();
        spawn.start();
        combat.start();
        if (!arenas.start()) {
            throw new IOException("arenas.json could not be loaded safely");
        }
        if (!protectedArenas.start()) {
            throw new IOException("protected-arenas.db could not be loaded safely");
        }

        swords.start();
        merge.start();
        pets.start();

        plugin.getServer().getPluginManager().registerEvents(stats, plugin);
        plugin.getServer().getPluginManager().registerEvents(biomes, plugin);
        plugin.getServer().getPluginManager().registerEvents(tokens, plugin);
        plugin.getServer().getPluginManager().registerEvents(spawn, plugin);
        plugin.getServer().getPluginManager().registerEvents(combat, plugin);
        plugin.getServer().getPluginManager().registerEvents(social, plugin);
    }

    void refreshTheme() {
        bin.refreshTheme();
        merge.refreshTheme();
        settings.refreshTheme();
        tokens.refreshTheme();
    }

    void clearPlayerState(UUID playerId) {
        kits.clearCooldown(playerId);
    }

    @Override
    public void close() {
        merge.close();
        pets.close();
        kits.close();
        bin.close();
        combat.close();
        stats.close();
        biomes.close();
        tokens.close();
        spawn.close();
        social.close();
        swords.close();
        protectedArenas.close();
        arenas.close();
        settings.close();
    }
}
