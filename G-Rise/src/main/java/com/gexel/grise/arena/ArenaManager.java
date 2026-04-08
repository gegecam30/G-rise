package com.gexel.grise.arena;

import com.gexel.grise.GRisePlugin;
import org.bukkit.Color;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.EntityType;
import org.bukkit.entity.Player;

import java.io.File;
import java.io.IOException;
import java.util.*;
import java.util.logging.Level;

/**
 * Central manager for all {@link Arena} configurations and active
 * {@link GameSession}s.
 *
 * <h3>Storage format</h3>
 * Arenas are persisted in <code>plugins/G-Rise/arenas.yml</code>.
 * Each arena is a YAML section keyed by its lower-case name:
 * <pre>
 * arenas:
 *   sky_rush:
 *     world: world
 *     center: {x: 100, y: 64, z: 200}
 *     radius: 8
 *     goalY: 164
 *     spawn: {x: 100.5, y: 65, z: 200.5, yaw: 0, pitch: 0}
 * </pre>
 *
 * <h3>Session lifecycle</h3>
 * At most <em>one</em> {@link GameSession} may be active per arena at a time.
 * The session removes itself via {@link #removeSession(String)} on completion.
 *
 * @author Gexel
 */
public class ArenaManager {

    // -----------------------------------------------------------------------
    // Fields
    // -----------------------------------------------------------------------

    private final GRisePlugin plugin;

    /** All registered arenas, keyed by lower-case name. */
    private final Map<String, Arena> arenas = new HashMap<>();

    /** Active sessions, keyed by lower-case arena name. */
    private final Map<String, GameSession> activeSessions = new HashMap<>();

    /** Which session each player currently belongs to (UUID → arena name). */
    private final Map<UUID, String> playerSessions = new HashMap<>();

    /** arenas.yml file handle. */
    private File    arenasFile;
    private FileConfiguration arenasConfig;

    // -----------------------------------------------------------------------
    // Constructor
    // -----------------------------------------------------------------------

    public ArenaManager(GRisePlugin plugin) {
        this.plugin = plugin;
    }

    // -----------------------------------------------------------------------
    // Persistence
    // -----------------------------------------------------------------------

    /** Loads all arenas from arenas.yml. Creates the file if absent. */
    public void loadArenas() {
        arenasFile = new File(plugin.getDataFolder(), "arenas.yml");
        if (!arenasFile.exists()) {
            try {
                arenasFile.createNewFile();
            } catch (IOException e) {
                plugin.getLogger().log(Level.SEVERE, "Could not create arenas.yml", e);
            }
        }
        arenasConfig = YamlConfiguration.loadConfiguration(arenasFile);
        arenas.clear();

        ConfigurationSection section = arenasConfig.getConfigurationSection("arenas");
        if (section == null) return;

        for (String key : section.getKeys(false)) {
            ConfigurationSection as = section.getConfigurationSection(key);
            if (as == null) continue;

            try {
                Arena arena = deserializeArena(key, as);
                arenas.put(key.toLowerCase(), arena);
            } catch (Exception e) {
                plugin.getLogger().log(Level.WARNING, "Failed to load arena '" + key + "': " + e.getMessage());
            }
        }

        plugin.getLogger().info("Loaded " + arenas.size() + " arena(s).");
    }

    /** Saves all arenas to arenas.yml. */
    public void saveArenas() {
        if (arenasConfig == null) return;

        // Clear and rewrite.
        arenasConfig.set("arenas", null);
        for (Arena arena : arenas.values()) {
            serializeArena(arena);
        }

        try {
            arenasConfig.save(arenasFile);
        } catch (IOException e) {
            plugin.getLogger().log(Level.SEVERE, "Could not save arenas.yml", e);
        }
    }

    // -----------------------------------------------------------------------
    // Arena CRUD
    // -----------------------------------------------------------------------

    /**
     * Creates and registers a new arena.
     *
     * @param name   unique name (must not already exist)
     * @param center floor-level centre location
     * @return the new arena, or {@code null} if a name conflict exists
     */
    public Arena createArena(String name, Location center) {
        String key = name.toLowerCase();
        if (arenas.containsKey(key)) return null;

        int    radius = plugin.getConfig().getInt("arena.default-radius", 8);
        int    goalY  = plugin.getConfig().getInt("arena.default-goal-y", 164);
        Arena  arena  = new Arena(name, center, radius, goalY, center.clone().add(0.5, 1, 0.5));

        arenas.put(key, arena);
        saveArenas();
        return arena;
    }

    /**
     * Deletes an arena by name.
     *
     * @param name arena name (case-insensitive)
     * @return {@code true} if found and removed
     */
    public boolean deleteArena(String name) {
        Arena removed = arenas.remove(name.toLowerCase());
        if (removed != null) saveArenas();
        return removed != null;
    }

    /**
     * Returns an arena by name, or {@code null}.
     *
     * @param name arena name (case-insensitive)
     */
    public Arena getArena(String name) {
        return arenas.get(name.toLowerCase());
    }

    /** Returns an unmodifiable view of all registered arenas. */
    public Collection<Arena> getAllArenas() {
        return Collections.unmodifiableCollection(arenas.values());
    }

    /** Returns all arena names. */
    public Set<String> getArenaNames() {
        return Collections.unmodifiableSet(arenas.keySet());
    }

    // -----------------------------------------------------------------------
    // Session management
    // -----------------------------------------------------------------------

    /**
     * Attempts to start a solo game for a player in the given arena.
     *
     * @param player     the player
     * @param arenaName  arena name
     * @param difficulty game difficulty
     * @return {@code true} if the session was created and countdown started
     */
    public boolean startSolo(Player player, String arenaName, Difficulty difficulty) {
        String key = arenaName.toLowerCase();
        Arena arena = arenas.get(key);
        if (arena == null) {
            plugin.getMessageUtil().send(player, "arena.not-found",
                    "{arena}", arenaName);
            return false;
        }

        if (playerSessions.containsKey(player.getUniqueId())) {
            plugin.getMessageUtil().send(player, "game.already-in-game");
            return false;
        }

        if (activeSessions.containsKey(key)) {
            plugin.getMessageUtil().send(player, "arena.in-game", "{arena}", arenaName);
            return false;
        }

        GameSession session = new GameSession(plugin, arena, difficulty);
        session.addPlayer(player);

        activeSessions.put(key, session);
        playerSessions.put(player.getUniqueId(), key);

        session.startCountdown();
        return true;
    }

    /**
     * Attempts to join or create a multiplayer race session in the given arena.
     *
     * @param player    the player
     * @param arenaName arena name
     * @return {@code true} if the player successfully joined/created a session
     */
    public boolean joinRace(Player player, String arenaName) {
        String key   = arenaName.toLowerCase();
        Arena  arena = arenas.get(key);
        if (arena == null) {
            plugin.getMessageUtil().send(player, "arena.not-found", "{arena}", arenaName);
            return false;
        }

        if (playerSessions.containsKey(player.getUniqueId())) {
            plugin.getMessageUtil().send(player, "game.already-in-game");
            return false;
        }

        // Re-use an existing WAITING session or create a new one.
        GameSession session = activeSessions.get(key);
        if (session == null || session.getState() != SessionState.WAITING) {
            session = new GameSession(plugin, arena, Difficulty.RACE);
            activeSessions.put(key, session);
        }

        int maxPlayers = plugin.getConfig().getInt("game.max-players-per-arena", 8);
        if (session.getPlayers().size() >= maxPlayers) {
            plugin.getMessageUtil().send(player, "game.arena-full",
                    "{arena}", arenaName, "{max}", String.valueOf(maxPlayers));
            return false;
        }

        boolean joined = session.addPlayer(player);
        if (!joined) return false;

        playerSessions.put(player.getUniqueId(), key);

        // Auto-start when minimum players are met.
        int minPlayers = plugin.getConfig().getInt("game.min-players-multiplayer", 2);
        if (session.getPlayers().size() >= minPlayers
                && session.getState() == SessionState.WAITING) {
            session.startCountdown();
        } else {
            plugin.getMessageUtil().send(player, "game.waiting-for-players",
                    "{current}", String.valueOf(session.getPlayers().size()),
                    "{min}", String.valueOf(minPlayers));
        }
        return true;
    }

    /**
     * Removes a player from their current session.
     *
     * @param player player to remove
     * @return {@code true} if the player was in a session
     */
    public boolean leaveGame(Player player) {
        String arenaKey = playerSessions.remove(player.getUniqueId());
        if (arenaKey == null) return false;

        GameSession session = activeSessions.get(arenaKey);
        if (session != null) {
            session.removePlayer(player);
        }
        return true;
    }

    /**
     * Forcibly removes a single player from {@code playerSessions} without
     * touching the active session. Used by Hard-mode elimination so the player
     * can immediately rejoin a new game.
     *
     * @param player the eliminated player
     */
    public void forceRemovePlayer(Player player) {
        playerSessions.remove(player.getUniqueId());
    }

    /**
     * Returns the active session for the given arena name, or {@code null}.
     */
    public GameSession getSession(String arenaName) {
        return activeSessions.get(arenaName.toLowerCase());
    }

    /**
     * Returns the session a player is currently in, or {@code null}.
     */
    public GameSession getSessionOf(Player player) {
        String key = playerSessions.get(player.getUniqueId());
        return key == null ? null : activeSessions.get(key);
    }

    /**
     * Returns {@code true} if a player is currently in any session.
     */
    public boolean isPlaying(Player player) {
        return playerSessions.containsKey(player.getUniqueId());
    }

    /**
     * Called by {@link GameSession} to deregister itself when it ends.
     *
     * @param arenaName the arena name whose session ended
     */
    public void removeSession(String arenaName) {
        GameSession session = activeSessions.remove(arenaName.toLowerCase());
        if (session == null) return;
        // Clean up playerSessions for all players that were in this session.
        session.getPlayers().forEach(p -> playerSessions.remove(p.getUniqueId()));
    }

    /**
     * Ends all active sessions cleanly. Called on plugin disable.
     */
    public void shutdownAll() {
        List<GameSession> sessions = new ArrayList<>(activeSessions.values());
        for (GameSession session : sessions) {
            session.endSession(null);
        }
        activeSessions.clear();
        playerSessions.clear();
    }

    // -----------------------------------------------------------------------
    // Serialisation helpers
    // -----------------------------------------------------------------------

    private Arena deserializeArena(String key, ConfigurationSection as) {
        String worldName = as.getString("world", "world");
        World world = plugin.getServer().getWorld(worldName);
        if (world == null) throw new IllegalStateException("World '" + worldName + "' not found");

        ConfigurationSection cs = as.getConfigurationSection("center");
        if (cs == null) throw new IllegalStateException("Missing 'center' section");

        double cx = cs.getDouble("x");
        double cy = cs.getDouble("y");
        double cz = cs.getDouble("z");
        Location center = new Location(world, cx, cy, cz);

        int radius = as.getInt("radius", 8);
        int goalY  = as.getInt("goalY", 164);

        Location spawn = center.clone().add(0.5, 1, 0.5);
        ConfigurationSection ss = as.getConfigurationSection("spawn");
        if (ss != null) {
            spawn = new Location(world,
                    ss.getDouble("x"), ss.getDouble("y"), ss.getDouble("z"),
                    (float) ss.getDouble("yaw"), (float) ss.getDouble("pitch"));
        }

        // Entity type (per-arena, falls back to WARDEN).
        EntityType entityType = EntityType.WARDEN;
        String typeName = as.getString("entity-type");
        if (typeName != null) {
            try { entityType = EntityType.valueOf(typeName.toUpperCase()); }
            catch (IllegalArgumentException ignored) {}
        }

        // Glow color (stored as R,G,B integers; absent = no glow).
        Color glowColor = null;
        if (as.contains("glow-color")) {
            int r = as.getInt("glow-color.r", 255);
            int g = as.getInt("glow-color.g", 255);
            int b = as.getInt("glow-color.b", 255);
            glowColor = Color.fromRGB(r, g, b);
        }

        return new Arena(as.getString("name", key), center, radius, goalY,
                spawn, entityType, glowColor);
    }

    private void serializeArena(Arena arena) {
        String path = "arenas." + arena.getName().toLowerCase();

        arenasConfig.set(path + ".name",        arena.getName());
        arenasConfig.set(path + ".world",        arena.getWorld().getName());

        Location c = arena.getCenter();
        arenasConfig.set(path + ".center.x", c.getX());
        arenasConfig.set(path + ".center.y", c.getY());
        arenasConfig.set(path + ".center.z", c.getZ());

        arenasConfig.set(path + ".radius", arena.getRadius());
        arenasConfig.set(path + ".goalY",  arena.getGoalY());

        Location s = arena.getSpawnLocation();
        arenasConfig.set(path + ".spawn.x",     s.getX());
        arenasConfig.set(path + ".spawn.y",     s.getY());
        arenasConfig.set(path + ".spawn.z",     s.getZ());
        arenasConfig.set(path + ".spawn.yaw",   s.getYaw());
        arenasConfig.set(path + ".spawn.pitch", s.getPitch());

        // Per-arena entity type.
        arenasConfig.set(path + ".entity-type", arena.getEntityType().name());

        // Per-arena glow color (only written when enabled).
        if (arena.isGlowEnabled()) {
            Color col = arena.getGlowColor();
            arenasConfig.set(path + ".glow-color.r", col.getRed());
            arenasConfig.set(path + ".glow-color.g", col.getGreen());
            arenasConfig.set(path + ".glow-color.b", col.getBlue());
        } else {
            arenasConfig.set(path + ".glow-color", null); // remove if previously set
        }
    }
}
