package me.poggercity.modificationFFA;

import org.sqlite.JDBC;

import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;
import java.util.Properties;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

final class ProtectedArenaDatabase implements AutoCloseable {

    private final Path path;
    private final ExecutorService executor;
    private Connection connection;
    private CompletableFuture<Void> closeFuture;

    ProtectedArenaDatabase(Path path) {
        this.path = path;
        this.executor = Executors.newSingleThreadExecutor(task -> {
            Thread thread = new Thread(task, "ModificationFFA-ProtectedArenaDatabase");
            thread.setDaemon(true);
            return thread;
        });
    }

    CompletableFuture<LoadedState> initializeAndLoad() {
        return supplyAsync(() -> {
            Files.createDirectories(path.getParent());
            String url = "jdbc:sqlite:" + path.toAbsolutePath();
            connection = new JDBC().connect(url, new Properties());
            if (connection == null) {
                throw new SQLException("SQLite refused the database URL: " + url);
            }
            try (Statement statement = connection.createStatement()) {
                statement.execute("PRAGMA journal_mode = WAL");
                statement.execute("PRAGMA synchronous = NORMAL");
                statement.execute("PRAGMA foreign_keys = ON");
                statement.execute("PRAGMA temp_store = MEMORY");
                statement.execute("""
                        CREATE TABLE IF NOT EXISTS protected_arenas (
                            arena_key TEXT PRIMARY KEY COLLATE NOCASE,
                            name TEXT NOT NULL,
                            world_id TEXT NOT NULL,
                            world_name TEXT NOT NULL,
                            min_x INTEGER NOT NULL,
                            min_y INTEGER NOT NULL,
                            min_z INTEGER NOT NULL,
                            max_x INTEGER NOT NULL,
                            max_y INTEGER NOT NULL,
                            max_z INTEGER NOT NULL
                        )
                        """);
                statement.execute("""
                        CREATE TABLE IF NOT EXISTS protected_exemptions (
                            arena_key TEXT NOT NULL COLLATE NOCASE,
                            exemption_key TEXT NOT NULL COLLATE NOCASE,
                            name TEXT NOT NULL,
                            world_id TEXT NOT NULL,
                            world_name TEXT NOT NULL,
                            min_x INTEGER NOT NULL,
                            min_y INTEGER NOT NULL,
                            min_z INTEGER NOT NULL,
                            max_x INTEGER NOT NULL,
                            max_y INTEGER NOT NULL,
                            max_z INTEGER NOT NULL,
                            PRIMARY KEY (arena_key, exemption_key),
                            FOREIGN KEY (arena_key) REFERENCES protected_arenas(arena_key) ON DELETE CASCADE
                        )
                        """);
                statement.execute("""
                        CREATE TABLE IF NOT EXISTS protected_placed_blocks (
                            arena_key TEXT NOT NULL COLLATE NOCASE,
                            x INTEGER NOT NULL,
                            y INTEGER NOT NULL,
                            z INTEGER NOT NULL,
                            base_data TEXT NOT NULL,
                            PRIMARY KEY (arena_key, x, y, z),
                            FOREIGN KEY (arena_key) REFERENCES protected_arenas(arena_key) ON DELETE CASCADE
                        )
                        """);
            }
            return loadState();
        });
    }

    CompletableFuture<Void> insertArena(ArenaRow arena) {
        return runAsync(() -> {
            try (PreparedStatement statement = connection.prepareStatement("""
                    INSERT INTO protected_arenas (
                        arena_key, name, world_id, world_name,
                        min_x, min_y, min_z, max_x, max_y, max_z
                    ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                    """)) {
                bindArena(statement, arena);
                statement.executeUpdate();
            }
        });
    }

    CompletableFuture<Void> deleteArena(String arenaKey) {
        return runAsync(() -> {
            try (PreparedStatement statement = connection.prepareStatement(
                    "DELETE FROM protected_arenas WHERE arena_key = ?")) {
                statement.setString(1, arenaKey);
                statement.executeUpdate();
            }
        });
    }

    CompletableFuture<Void> insertExemption(ExemptionRow exemption) {
        return runAsync(() -> {
            try (PreparedStatement statement = connection.prepareStatement("""
                    INSERT INTO protected_exemptions (
                        arena_key, exemption_key, name, world_id, world_name,
                        min_x, min_y, min_z, max_x, max_y, max_z
                    ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                    """)) {
                statement.setString(1, exemption.arenaKey());
                statement.setString(2, exemption.exemptionKey());
                statement.setString(3, exemption.name());
                bindBounds(statement, 4, exemption.bounds());
                statement.executeUpdate();
            }
        });
    }

    CompletableFuture<Void> deleteExemption(String arenaKey, String exemptionKey) {
        return runAsync(() -> {
            try (PreparedStatement statement = connection.prepareStatement(
                    "DELETE FROM protected_exemptions WHERE arena_key = ? AND exemption_key = ?")) {
                statement.setString(1, arenaKey);
                statement.setString(2, exemptionKey);
                statement.executeUpdate();
            }
        });
    }

    CompletableFuture<Void> applyBlockMutations(List<BlockMutation> mutations) {
        List<BlockMutation> snapshot = List.copyOf(mutations);
        return runAsync(() -> {
            boolean previousAutoCommit = connection.getAutoCommit();
            connection.setAutoCommit(false);
            try (PreparedStatement upsert = connection.prepareStatement("""
                    INSERT INTO protected_placed_blocks (arena_key, x, y, z, base_data)
                    VALUES (?, ?, ?, ?, ?)
                    ON CONFLICT(arena_key, x, y, z) DO UPDATE SET base_data = excluded.base_data
                    """);
                 PreparedStatement delete = connection.prepareStatement("""
                    DELETE FROM protected_placed_blocks
                    WHERE arena_key = ? AND x = ? AND y = ? AND z = ?
                    """)) {
                for (BlockMutation mutation : snapshot) {
                    PreparedStatement statement = mutation.baseData() == null ? delete : upsert;
                    statement.setString(1, mutation.arenaKey());
                    statement.setInt(2, mutation.x());
                    statement.setInt(3, mutation.y());
                    statement.setInt(4, mutation.z());
                    if (mutation.baseData() != null) {
                        statement.setString(5, mutation.baseData());
                    }
                    statement.addBatch();
                }
                upsert.executeBatch();
                delete.executeBatch();
                connection.commit();
            } catch (Exception exception) {
                connection.rollback();
                throw exception;
            } finally {
                connection.setAutoCommit(previousAutoCommit);
            }
        });
    }

    private LoadedState loadState() throws SQLException {
        List<ArenaRow> arenas = new ArrayList<>();
        List<ExemptionRow> exemptions = new ArrayList<>();
        List<PlacedRow> placedBlocks = new ArrayList<>();
        try (Statement statement = connection.createStatement();
             ResultSet result = statement.executeQuery("SELECT * FROM protected_arenas")) {
            while (result.next()) {
                arenas.add(new ArenaRow(
                        result.getString("arena_key"),
                        result.getString("name"),
                        bounds(result)
                ));
            }
        }
        try (Statement statement = connection.createStatement();
             ResultSet result = statement.executeQuery("SELECT * FROM protected_exemptions")) {
            while (result.next()) {
                exemptions.add(new ExemptionRow(
                        result.getString("arena_key"),
                        result.getString("exemption_key"),
                        result.getString("name"),
                        bounds(result)
                ));
            }
        }
        try (Statement statement = connection.createStatement();
             ResultSet result = statement.executeQuery("SELECT * FROM protected_placed_blocks")) {
            while (result.next()) {
                placedBlocks.add(new PlacedRow(
                        result.getString("arena_key"),
                        result.getInt("x"),
                        result.getInt("y"),
                        result.getInt("z"),
                        result.getString("base_data")
                ));
            }
        }
        return new LoadedState(arenas, exemptions, placedBlocks);
    }

    private void bindArena(PreparedStatement statement, ArenaRow arena) throws SQLException {
        statement.setString(1, arena.arenaKey());
        statement.setString(2, arena.name());
        bindBounds(statement, 3, arena.bounds());
    }

    private void bindBounds(PreparedStatement statement, int index, RegionBounds bounds) throws SQLException {
        statement.setString(index, bounds.worldId());
        statement.setString(index + 1, bounds.worldName());
        statement.setInt(index + 2, bounds.minX());
        statement.setInt(index + 3, bounds.minY());
        statement.setInt(index + 4, bounds.minZ());
        statement.setInt(index + 5, bounds.maxX());
        statement.setInt(index + 6, bounds.maxY());
        statement.setInt(index + 7, bounds.maxZ());
    }

    private RegionBounds bounds(ResultSet result) throws SQLException {
        return new RegionBounds(
                result.getString("world_id"),
                result.getString("world_name"),
                result.getInt("min_x"),
                result.getInt("min_y"),
                result.getInt("min_z"),
                result.getInt("max_x"),
                result.getInt("max_y"),
                result.getInt("max_z")
        );
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
            if (connection != null) {
                connection.close();
            }
        });
        executor.shutdown();
        return closeFuture;
    }

    record LoadedState(List<ArenaRow> arenas, List<ExemptionRow> exemptions, List<PlacedRow> placedBlocks) {
    }

    record ArenaRow(String arenaKey, String name, RegionBounds bounds) {
    }

    record ExemptionRow(String arenaKey, String exemptionKey, String name, RegionBounds bounds) {
    }

    record PlacedRow(String arenaKey, int x, int y, int z, String baseData) {
    }

    record BlockMutation(String arenaKey, int x, int y, int z, String baseData) {
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
