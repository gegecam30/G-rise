package com.gexel.grise.database;

import com.gexel.grise.GRisePlugin;
import com.gexel.grise.arena.Difficulty;

import java.io.File;
import java.sql.*;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.logging.Level;

/**
 * Manages all database interactions for G-Rise.
 *
 * <h3>Supported backends</h3>
 * <ul>
 *   <li><b>SQLite</b> – zero-configuration, file-based. Ideal for single-server setups.</li>
 *   <li><b>MySQL</b>  – network database for multi-server or high-concurrency setups.</li>
 * </ul>
 * The backend is selected via {@code database.type} in config.yml.
 *
 * <h3>Threading model</h3>
 * <ul>
 *   <li>All write operations ({@link #saveRecord}) should be called <em>asynchronously</em>
 *       from a {@code BukkitRunnable} or similar. The method itself is synchronised on the
 *       connection to prevent concurrent write corruption on SQLite.</li>
 *   <li>Read operations ({@link #getTopRecords}, {@link #getPlayerStats}) should also be
 *       called asynchronously and their results dispatched back to the main thread.</li>
 * </ul>
 *
 * <h3>Schema</h3>
 * <pre>
 * TABLE players
 *   uuid          TEXT PRIMARY KEY
 *   username      TEXT NOT NULL
 *   games_played  INTEGER DEFAULT 0
 *   wins          INTEGER DEFAULT 0
 *
 * TABLE records
 *   id            INTEGER / BIGINT AUTO_INCREMENT PRIMARY KEY
 *   uuid          TEXT NOT NULL
 *   username      TEXT NOT NULL
 *   arena         TEXT NOT NULL
 *   difficulty    TEXT NOT NULL
 *   time_ms       BIGINT NOT NULL   -- elapsed milliseconds
 *   time_display  TEXT NOT NULL     -- human-readable "mm:ss.SSS"
 *   achieved_at   TIMESTAMP DEFAULT CURRENT_TIMESTAMP
 * </pre>
 *
 * @author Gexel
 */
public class DatabaseManager {

    // -----------------------------------------------------------------------
    // Fields
    // -----------------------------------------------------------------------

    private final GRisePlugin plugin;
    private Connection connection;
    private final String dbType;

    // -----------------------------------------------------------------------
    // Constructor
    // -----------------------------------------------------------------------

    public DatabaseManager(GRisePlugin plugin) {
        this.plugin = plugin;
        this.dbType = plugin.getConfig().getString("database.type", "sqlite").toLowerCase();
    }

    // -----------------------------------------------------------------------
    // Connection lifecycle
    // -----------------------------------------------------------------------

    /**
     * Opens a connection to the configured database.
     *
     * @return {@code true} if the connection was established successfully
     */
    public boolean connect() {
        try {
            if (dbType.equals("mysql")) {
                connection = openMySQLConnection();
            } else {
                connection = openSQLiteConnection();
            }
            plugin.getLogger().info("Database connected (" + dbType + ").");
            return connection != null;
        } catch (SQLException | ClassNotFoundException e) {
            plugin.getLogger().log(Level.SEVERE, "Database connection failed: " + e.getMessage(), e);
            return false;
        }
    }

    /** Closes the connection gracefully. */
    public void disconnect() {
        try {
            if (connection != null && !connection.isClosed()) {
                connection.close();
                plugin.getLogger().info("Database connection closed.");
            }
        } catch (SQLException e) {
            plugin.getLogger().log(Level.WARNING, "Error closing database connection.", e);
        }
    }

    /**
     * Creates all required tables if they do not exist.
     * Safe to call on every startup.
     */
    public void createTables() {
        String autoIncrement = dbType.equals("mysql") ? "BIGINT AUTO_INCREMENT" : "INTEGER";

        String players = """
                CREATE TABLE IF NOT EXISTS players (
                    uuid         TEXT PRIMARY KEY,
                    username     TEXT NOT NULL,
                    games_played INTEGER NOT NULL DEFAULT 0,
                    wins         INTEGER NOT NULL DEFAULT 0
                )
                """;

        String records = String.format("""
                CREATE TABLE IF NOT EXISTS records (
                    id           %s PRIMARY KEY,
                    uuid         TEXT    NOT NULL,
                    username     TEXT    NOT NULL,
                    arena        TEXT    NOT NULL,
                    difficulty   TEXT    NOT NULL,
                    time_ms      BIGINT  NOT NULL,
                    time_display TEXT    NOT NULL,
                    achieved_at  TIMESTAMP DEFAULT CURRENT_TIMESTAMP
                )
                """, autoIncrement);

        try (Statement stmt = connection.createStatement()) {
            stmt.execute(players);
            stmt.execute(records);
            plugin.getLogger().info("Database tables verified.");
        } catch (SQLException e) {
            plugin.getLogger().log(Level.SEVERE, "Failed to create database tables.", e);
        }
    }

    // -----------------------------------------------------------------------
    // Write operations  (call asynchronously)
    // -----------------------------------------------------------------------

    /**
     * Upserts player statistics and inserts a completion record.
     *
     * <p>This is a compound operation: the player row is created/updated first,
     * then the time record is appended.
     *
     * @param uuid        player UUID
     * @param username    player name (kept up-to-date)
     * @param arena       arena name
     * @param difficulty  game difficulty
     * @param timeDisplay human-readable time string (e.g. "01:23.456")
     */
    public synchronized void saveRecord(UUID uuid, String username,
                                        String arena, Difficulty difficulty,
                                        String timeDisplay) {
        ensureConnection();

        // 1. Upsert player row.
        String upsertPlayer = dbType.equals("mysql")
                ? "INSERT INTO players (uuid, username, games_played, wins) VALUES (?, ?, 1, 1) "
                  + "ON DUPLICATE KEY UPDATE username=VALUES(username), games_played=games_played+1, wins=wins+1"
                : "INSERT INTO players (uuid, username, games_played, wins) VALUES (?, ?, 1, 1) "
                  + "ON CONFLICT(uuid) DO UPDATE SET username=excluded.username, "
                  + "games_played=games_played+1, wins=wins+1";

        try (PreparedStatement ps = connection.prepareStatement(upsertPlayer)) {
            ps.setString(1, uuid.toString());
            ps.setString(2, username);
            ps.executeUpdate();
        } catch (SQLException e) {
            plugin.getLogger().log(Level.WARNING, "Failed to upsert player stats.", e);
        }

        // 2. Parse the display time back to ms for sorting purposes.
        long timeMs = parseDisplayTimeToMs(timeDisplay);

        // 3. Insert record.
        String insertRecord = "INSERT INTO records (uuid, username, arena, difficulty, time_ms, time_display) "
                              + "VALUES (?, ?, ?, ?, ?, ?)";

        try (PreparedStatement ps = connection.prepareStatement(insertRecord)) {
            ps.setString(1, uuid.toString());
            ps.setString(2, username);
            ps.setString(3, arena);
            ps.setString(4, difficulty.name());
            ps.setLong(5, timeMs);
            ps.setString(6, timeDisplay);
            ps.executeUpdate();
        } catch (SQLException e) {
            plugin.getLogger().log(Level.WARNING, "Failed to insert record.", e);
        }
    }

    // -----------------------------------------------------------------------
    // Read operations  (call asynchronously)
    // -----------------------------------------------------------------------

    /**
     * Returns the top N records for a given difficulty, ordered by
     * fastest time.
     *
     * @param difficulty difficulty to query
     * @param limit      maximum rows to return
     * @return ordered list of {@link LeaderboardEntry}
     */
    public List<LeaderboardEntry> getTopRecords(Difficulty difficulty, int limit) {
        ensureConnection();
        List<LeaderboardEntry> entries = new ArrayList<>();

        // Use a subquery to get each player's personal best, then rank globally.
        String sql = """
                SELECT username, MIN(time_ms) AS best_ms, time_display
                FROM records
                WHERE difficulty = ?
                GROUP BY uuid
                ORDER BY best_ms ASC
                LIMIT ?
                """;

        try (PreparedStatement ps = connection.prepareStatement(sql)) {
            ps.setString(1, difficulty.name());
            ps.setInt(2, limit);
            ResultSet rs = ps.executeQuery();
            int rank = 1;
            while (rs.next()) {
                entries.add(new LeaderboardEntry(
                        rank++,
                        rs.getString("username"),
                        rs.getString("time_display")
                ));
            }
        } catch (SQLException e) {
            plugin.getLogger().log(Level.WARNING, "Failed to query leaderboard.", e);
        }
        return entries;
    }

    /**
     * Returns all-time statistics for a single player.
     *
     * @param uuid player UUID
     * @return a {@link PlayerStats} record, or {@code null} if not found
     */
    public PlayerStats getPlayerStats(UUID uuid) {
        ensureConnection();

        String sql = "SELECT username, games_played, wins FROM players WHERE uuid = ?";
        try (PreparedStatement ps = connection.prepareStatement(sql)) {
            ps.setString(1, uuid.toString());
            ResultSet rs = ps.executeQuery();
            if (!rs.next()) return null;

            String username    = rs.getString("username");
            int    gamesPlayed = rs.getInt("games_played");
            int    wins        = rs.getInt("wins");

            // Fetch personal bests per difficulty.
            String pbSql = """
                    SELECT difficulty, MIN(time_ms) AS best_ms, time_display
                    FROM records
                    WHERE uuid = ?
                    GROUP BY difficulty
                    """;
            String bestEasy   = null, bestMedium = null, bestHard = null;
            try (PreparedStatement pbPs = connection.prepareStatement(pbSql)) {
                pbPs.setString(1, uuid.toString());
                ResultSet pbRs = pbPs.executeQuery();
                while (pbRs.next()) {
                    Difficulty diff = Difficulty.fromString(pbRs.getString("difficulty"));
                    if (diff == null) continue;
                    String display = pbRs.getString("time_display");
                    switch (diff) {
                        case EASY   -> bestEasy   = display;
                        case MEDIUM -> bestMedium = display;
                        case HARD   -> bestHard   = display;
                        default     -> { /* RACE not tracked as personal-best */ }
                    }
                }
            }

            return new PlayerStats(username, gamesPlayed, wins, bestEasy, bestMedium, bestHard);

        } catch (SQLException e) {
            plugin.getLogger().log(Level.WARNING, "Failed to query player stats.", e);
            return null;
        }
    }

    // -----------------------------------------------------------------------
    // Private helpers
    // -----------------------------------------------------------------------

    private Connection openSQLiteConnection() throws SQLException, ClassNotFoundException {
        Class.forName("org.sqlite.JDBC");
        File dbFile = new File(plugin.getDataFolder(),
                plugin.getConfig().getString("database.sqlite.file-name", "grise_data.db"));
        plugin.getDataFolder().mkdirs();
        return DriverManager.getConnection("jdbc:sqlite:" + dbFile.getAbsolutePath());
    }

    private Connection openMySQLConnection() throws SQLException, ClassNotFoundException {
        Class.forName("com.mysql.cj.jdbc.Driver");
        String host   = plugin.getConfig().getString("database.mysql.host", "localhost");
        int    port   = plugin.getConfig().getInt("database.mysql.port", 3306);
        String db     = plugin.getConfig().getString("database.mysql.database", "grise");
        String user   = plugin.getConfig().getString("database.mysql.username", "root");
        String pass   = plugin.getConfig().getString("database.mysql.password", "");
        boolean ssl   = plugin.getConfig().getBoolean("database.mysql.use-ssl", false);

        String url = String.format(
                "jdbc:mysql://%s:%d/%s?useSSL=%b&allowPublicKeyRetrieval=true&serverTimezone=UTC",
                host, port, db, ssl);
        return DriverManager.getConnection(url, user, pass);
    }

    /**
     * Reconnects if the connection has dropped (e.g. MySQL server restart).
     */
    private void ensureConnection() {
        try {
            if (connection == null || connection.isClosed() || !connection.isValid(2)) {
                plugin.getLogger().warning("Database connection lost. Attempting reconnect…");
                connect();
            }
        } catch (SQLException e) {
            plugin.getLogger().log(Level.WARNING, "Could not validate DB connection.", e);
        }
    }

    /**
     * Converts a display string like {@code "01:23.456"} to milliseconds.
     */
    private long parseDisplayTimeToMs(String display) {
        try {
            // Format: mm:ss.SSS
            String[] parts = display.split("[:.]");
            long minutes = Long.parseLong(parts[0]);
            long seconds = Long.parseLong(parts[1]);
            long millis  = parts.length > 2 ? Long.parseLong(parts[2]) : 0;
            return minutes * 60_000L + seconds * 1_000L + millis;
        } catch (Exception e) {
            return Long.MAX_VALUE; // Fallback so it sorts to the bottom.
        }
    }

    // -----------------------------------------------------------------------
    // Inner data classes
    // -----------------------------------------------------------------------

    /** A single row on the global leaderboard. */
    public record LeaderboardEntry(int rank, String username, String timeDisplay) {}

    /** Aggregated stats for one player. */
    public record PlayerStats(
            String username,
            int gamesPlayed,
            int wins,
            String bestEasy,
            String bestMedium,
            String bestHard) {}
}
