package me.poggercity.modificationFFA;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

class KitDatabaseTest {

    @TempDir
    Path temporaryDirectory;

    @Test
    void persistsMainAndPlayerKitsAndDeletesPlayerLayout() throws Exception {
        KitDatabase database = new KitDatabase(temporaryDirectory.resolve("kits.db"));
        try {
            database.initialize().get(10, TimeUnit.SECONDS);

            byte[] mainKit = {1, 3, 3, 7};
            database.saveMainKit(mainKit).get(10, TimeUnit.SECONDS);
            assertArrayEquals(mainKit, database.loadMainKit().get(10, TimeUnit.SECONDS));

            UUID playerId = UUID.randomUUID();
            byte[] playerKit = {4, 2, 0, 6, 9};
            database.savePlayerKit(playerId, playerKit).get(10, TimeUnit.SECONDS);
            assertArrayEquals(playerKit, database.loadPlayerKit(playerId).get(10, TimeUnit.SECONDS));

            database.deletePlayerKit(playerId).get(10, TimeUnit.SECONDS);
            assertNull(database.loadPlayerKit(playerId).get(10, TimeUnit.SECONDS));
        } finally {
            database.closeAsync().get(10, TimeUnit.SECONDS);
        }
    }
}
