package com.gexel.grise.utils;

import com.gexel.grise.arena.Arena;
import org.bukkit.Color;
import org.bukkit.Location;
import org.bukkit.Particle;
import org.bukkit.World;
import org.bukkit.entity.Player;
import org.bukkit.plugin.Plugin;
import org.bukkit.scheduler.BukkitRunnable;
import org.bukkit.scheduler.BukkitTask;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

/**
 * Draws a temporary particle preview of an arena's boundaries and goal height.
 *
 * <h3>What is drawn</h3>
 * <ul>
 *   <li><b>Border square</b> — a DUST (coloured) particle outline at the arena
 *       floor level showing the horizontal play radius. Colour: aqua/cyan.</li>
 *   <li><b>Goal ring</b> — a DUST particle ring at the goal Y height. Colour:
 *       gold/yellow.</li>
 *   <li><b>Vertical corner pillars</b> — thin particle lines at the four corners
 *       connecting floor to goal Y. Colour: white/grey.</li>
 * </ul>
 *
 * <h3>Lifecycle</h3>
 * Calling {@link #showPreview(Player, Arena, Plugin)} starts a repeating task
 * that redraws every second for {@value #PREVIEW_DURATION_TICKS} ticks
 * (about 10 seconds), then cancels automatically.
 * Only one preview per player is active at a time; starting a new one cancels
 * the previous.
 *
 * @author Gexel
 */
public final class ArenaVisualizer {

    // -----------------------------------------------------------------------
    // Constants
    // -----------------------------------------------------------------------

    /** How long the preview stays visible (ticks). 200 = 10 seconds. */
    private static final int PREVIEW_DURATION_TICKS = 200;

    /** How often particles are redrawn (ticks). 20 = 1 second. */
    private static final int REDRAW_INTERVAL_TICKS  = 20;

    /** Spacing between individual particles along each edge (blocks). */
    private static final double PARTICLE_STEP = 0.6;

    /** Spacing between particles on vertical pillars. */
    private static final double PILLAR_STEP   = 1.5;

    // Particle colours
    private static final Particle.DustOptions BORDER_DUST =
            new Particle.DustOptions(Color.fromRGB(0, 220, 255), 1.2f); // cyan
    private static final Particle.DustOptions GOAL_DUST   =
            new Particle.DustOptions(Color.fromRGB(255, 200, 0),  1.2f); // gold
    private static final Particle.DustOptions PILLAR_DUST =
            new Particle.DustOptions(Color.fromRGB(200, 200, 200), 0.9f); // light grey

    // -----------------------------------------------------------------------
    // State — one task per player
    // -----------------------------------------------------------------------

    /** Active preview tasks keyed by player UUID. */
    private static final Map<UUID, BukkitTask> activeTasks = new HashMap<>();

    // Private constructor — static utility class.
    private ArenaVisualizer() {}

    // -----------------------------------------------------------------------
    // Public API
    // -----------------------------------------------------------------------

    /**
     * Starts a timed particle preview for the given player and arena.
     * Cancels any existing preview for that player first.
     *
     * @param player the player who will see the particles
     * @param arena  the arena to visualise
     * @param plugin owning plugin (for scheduler)
     */
    public static void showPreview(Player player, Arena arena, Plugin plugin) {
        // Cancel previous preview if any.
        cancelPreview(player);

        final int[] ticksRemaining = {PREVIEW_DURATION_TICKS};

        BukkitTask task = new BukkitRunnable() {
            @Override
            public void run() {
                if (!player.isOnline() || ticksRemaining[0] <= 0) {
                    cancel();
                    activeTasks.remove(player.getUniqueId());
                    return;
                }
                drawArena(player, arena);
                ticksRemaining[0] -= REDRAW_INTERVAL_TICKS;
            }
        }.runTaskTimer(plugin, 0L, REDRAW_INTERVAL_TICKS);

        activeTasks.put(player.getUniqueId(), task);
    }

    /**
     * Cancels an active preview for the given player (if any).
     *
     * @param player the player whose preview to cancel
     */
    public static void cancelPreview(Player player) {
        BukkitTask old = activeTasks.remove(player.getUniqueId());
        if (old != null) old.cancel();
    }

    // -----------------------------------------------------------------------
    // Drawing helpers
    // -----------------------------------------------------------------------

    /**
     * Draws one frame of the arena outline for the player.
     */
    private static void drawArena(Player player, Arena arena) {
        World world = arena.getWorld();
        Location center = arena.getCenter();
        double cx  = center.getX() + 0.5;
        double cz  = center.getZ() + 0.5;
        double floorY = center.getY();
        double goalY  = arena.getGoalY();
        double r   = arena.getRadius();

        // --- Border square at floor level ---
        double minX = cx - r, maxX = cx + r;
        double minZ = cz - r, maxZ = cz + r;

        // North edge (z = minZ), East (x = maxX), South (z = maxZ), West (x = minX)
        drawLine(player, world, minX, floorY, minZ, maxX, floorY, minZ, BORDER_DUST); // N
        drawLine(player, world, maxX, floorY, minZ, maxX, floorY, maxZ, BORDER_DUST); // E
        drawLine(player, world, maxX, floorY, maxZ, minX, floorY, maxZ, BORDER_DUST); // S
        drawLine(player, world, minX, floorY, maxZ, minX, floorY, minZ, BORDER_DUST); // W

        // --- Goal ring at goalY ---
        drawLine(player, world, minX, goalY, minZ, maxX, goalY, minZ, GOAL_DUST);
        drawLine(player, world, maxX, goalY, minZ, maxX, goalY, maxZ, GOAL_DUST);
        drawLine(player, world, maxX, goalY, maxZ, minX, goalY, maxZ, GOAL_DUST);
        drawLine(player, world, minX, goalY, maxZ, minX, goalY, minZ, GOAL_DUST);

        // --- Vertical corner pillars ---
        drawVertical(player, world, minX, minZ, floorY, goalY);
        drawVertical(player, world, maxX, minZ, floorY, goalY);
        drawVertical(player, world, maxX, maxZ, floorY, goalY);
        drawVertical(player, world, minX, maxZ, floorY, goalY);
    }

    /**
     * Draws particles along a horizontal line from (x1,y,z1) to (x2,y,z2).
     */
    private static void drawLine(Player player, World world,
                                 double x1, double y, double z1,
                                 double x2, double y2, double z2,
                                 Particle.DustOptions dust) {
        double dx = x2 - x1, dy = y2 - y, dz = z2 - z1;
        double length = Math.sqrt(dx * dx + dy * dy + dz * dz);
        int steps = (int) Math.ceil(length / PARTICLE_STEP);
        if (steps == 0) return;

        double sx = dx / steps, sy = dy / steps, sz = dz / steps;
        for (int i = 0; i <= steps; i++) {
            player.spawnParticle(Particle.DUST,
                    x1 + sx * i, y + sy * i, z1 + sz * i,
                    1, 0, 0, 0, 0, dust);
        }
    }

    /**
     * Draws a vertical particle pillar at (x, z) from {@code fromY} to {@code toY}.
     */
    private static void drawVertical(Player player, World world,
                                     double x, double z,
                                     double fromY, double toY) {
        double height = toY - fromY;
        int steps = (int) Math.ceil(height / PILLAR_STEP);
        if (steps == 0) return;
        double step = height / steps;
        for (int i = 0; i <= steps; i++) {
            player.spawnParticle(Particle.DUST,
                    x, fromY + step * i, z,
                    1, 0, 0, 0, 0, PILLAR_DUST);
        }
    }
}
