package me.poggercity.modificationFFA;

import org.sqlite.JDBC;

import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.UUID;
import java.util.Properties;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

final class KitDatabase implements AutoCloseable {

    private final Path databasePath;
    private final ExecutorService executor;
    private Connection connection;
    private CompletableFuture<Void> closeFuture;

    KitDatabase(Path databasePath) {
        this.databasePath = databasePath;
        this.executor = Executors.newSingleThreadExecutor(task -> {
            Thread thread = new Thread(task, "ModificationFFA-KitDatabase");
            thread.setDaemon(true);
            return thread;
        });
    }

    CompletableFuture<Void> initialize() {
        return runAsync(() -> {
            Files.createDirectories(databasePath.getParent());
            String jdbcUrl = "jdbc:sqlite:" + databasePath.toAbsolutePath();
            connection = new JDBC().connect(jdbcUrl, new Properties());
            if (connection == null) {
                throw new SQLException("SQLite refused the database URL: " + jdbcUrl);
            }

            try (Statement statement = connection.createStatement()) {
                statement.execute("PRAGMA journal_mode = DELETE");
                statement.execute("PRAGMA synchronous = NORMAL");
                statement.execute("PRAGMA temp_store = MEMORY");
                statement.execute("""
                        CREATE TABLE IF NOT EXISTS main_kit (
                            id INTEGER PRIMARY KEY CHECK (id = 1),
                            inventory BLOB NOT NULL,
                            updated_at INTEGER NOT NULL
                        )
                        """);
                statement.execute("""
                        CREATE TABLE IF NOT EXISTS player_kits (
                            player_uuid TEXT PRIMARY KEY,
                            inventory BLOB NOT NULL,
                            updated_at INTEGER NOT NULL
                        )
                        """);
            }
        });
    }

    CompletableFuture<byte[]> loadMainKit() {
        return supplyAsync(() -> {
            try (PreparedStatement statement = connection.prepareStatement(
                    "SELECT inventory FROM main_kit WHERE id = 1")) {
                try (ResultSet result = statement.executeQuery()) {
                    return result.next() ? result.getBytes("inventory") : null;
                }
            }
        });
    }

    CompletableFuture<Void> saveMainKit(byte[] inventory) {
        byte[] storedInventory = inventory.clone();
        return runAsync(() -> {
            try (PreparedStatement statement = connection.prepareStatement("""
                    INSERT INTO main_kit (id, inventory, updated_at)
                    VALUES (1, ?, ?)
                    ON CONFLICT(id) DO UPDATE SET
                        inventory = excluded.inventory,
                        updated_at = excluded.updated_at
                    """)) {
                statement.setBytes(1, storedInventory);
                statement.setLong(2, System.currentTimeMillis());
                statement.executeUpdate();
            }
        });
    }

    CompletableFuture<byte[]> loadPlayerKit(UUID playerId) {
        return supplyAsync(() -> {
            try (PreparedStatement statement = connection.prepareStatement(
                    "SELECT inventory FROM player_kits WHERE player_uuid = ?")) {
                statement.setString(1, playerId.toString());
                try (ResultSet result = statement.executeQuery()) {
                    return result.next() ? result.getBytes("inventory") : null;
                }
            }
        });
    }

    CompletableFuture<Void> savePlayerKit(UUID playerId, byte[] inventory) {
        byte[] storedInventory = inventory.clone();
        return runAsync(() -> {
            try (PreparedStatement statement = connection.prepareStatement("""
                    INSERT INTO player_kits (player_uuid, inventory, updated_at)
                    VALUES (?, ?, ?)
                    ON CONFLICT(player_uuid) DO UPDATE SET
                        inventory = excluded.inventory,
                        updated_at = excluded.updated_at
                    """)) {
                statement.setString(1, playerId.toString());
                statement.setBytes(2, storedInventory);
                statement.setLong(3, System.currentTimeMillis());
                statement.executeUpdate();
            }
        });
    }

    CompletableFuture<Void> deletePlayerKit(UUID playerId) {
        return runAsync(() -> {
            try (PreparedStatement statement = connection.prepareStatement(
                    "DELETE FROM player_kits WHERE player_uuid = ?")) {
                statement.setString(1, playerId.toString());
                statement.executeUpdate();
            }
        });
    }

    private CompletableFuture<Void> runAsync(CheckedRunnable operation) {
        return CompletableFuture.runAsync(() -> {
            try {
                operation.run();
            } catch (Exception exception) {
                throw new CompletionException(exception);
            }
        }, executor);
    }

    private <T> CompletableFuture<T> supplyAsync(CheckedSupplier<T> operation) {
        return CompletableFuture.supplyAsync(() -> {
            try {
                return operation.get();
            } catch (Exception exception) {
                throw new CompletionException(exception);
            }
        }, executor);
    }

    @Override
    public synchronized void close() {
        closeAsync();
    }

    synchronized CompletableFuture<Void> closeAsync() {
        if (closeFuture != null) {
            return closeFuture;
        }

        closeFuture = runAsync(() -> {
            if (connection == null) {
                return;
            }
            connection.close();
        });
        executor.shutdown();
        return closeFuture;
    }

    @FunctionalInterface
    private interface CheckedRunnable {
        void run() throws Exception;
    }

    @FunctionalInterface
    private interface CheckedSupplier<T> {
        T get() throws Exception;
    }
}
