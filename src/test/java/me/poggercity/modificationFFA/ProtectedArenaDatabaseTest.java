package me.poggercity.modificationFFA;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;

class ProtectedArenaDatabaseTest {

    @TempDir
    Path temporaryDirectory;

    @Test
    void persistsArenasExemptionsAndPlacedBlocks() throws Exception {
        Path path = temporaryDirectory.resolve("protected-arenas.db");
        RegionBounds arenaBounds = new RegionBounds("world-id", "world", -10, -64, -10, 10, 320, 10);
        RegionBounds exemptionBounds = new RegionBounds("world-id", "world", -2, 0, -2, 2, 10, 2);

        ProtectedArenaDatabase database = new ProtectedArenaDatabase(path);
        try {
            database.initializeAndLoad().get(10, TimeUnit.SECONDS);
            database.insertArena(new ProtectedArenaDatabase.ArenaRow("main", "Main", arenaBounds))
                    .get(10, TimeUnit.SECONDS);
            database.insertExemption(new ProtectedArenaDatabase.ExemptionRow(
                    "main", "pit", "Pit", exemptionBounds)).get(10, TimeUnit.SECONDS);
            database.applyBlockMutations(List.of(new ProtectedArenaDatabase.BlockMutation(
                    "main", 1, 2, 3, "minecraft:air"))).get(10, TimeUnit.SECONDS);
        } finally {
            database.closeAsync().get(10, TimeUnit.SECONDS);
        }

        ProtectedArenaDatabase reopened = new ProtectedArenaDatabase(path);
        try {
            ProtectedArenaDatabase.LoadedState state = reopened.initializeAndLoad().get(10, TimeUnit.SECONDS);
            assertEquals(List.of(new ProtectedArenaDatabase.ArenaRow("main", "Main", arenaBounds)), state.arenas());
            assertEquals(List.of(new ProtectedArenaDatabase.ExemptionRow(
                    "main", "pit", "Pit", exemptionBounds)), state.exemptions());
            assertEquals(List.of(new ProtectedArenaDatabase.PlacedRow(
                    "main", 1, 2, 3, "minecraft:air")), state.placedBlocks());

            reopened.deleteExemption("main", "pit").get(10, TimeUnit.SECONDS);
            reopened.deleteArena("main").get(10, TimeUnit.SECONDS);
        } finally {
            reopened.closeAsync().get(10, TimeUnit.SECONDS);
        }

        ProtectedArenaDatabase emptyDatabase = new ProtectedArenaDatabase(path);
        try {
            ProtectedArenaDatabase.LoadedState empty = emptyDatabase.initializeAndLoad().get(10, TimeUnit.SECONDS);
            assertEquals(List.of(), empty.arenas());
            assertEquals(List.of(), empty.exemptions());
            assertEquals(List.of(), empty.placedBlocks());
        } finally {
            emptyDatabase.closeAsync().get(10, TimeUnit.SECONDS);
        }
    }
}
