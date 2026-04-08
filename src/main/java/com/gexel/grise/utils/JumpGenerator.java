package com.gexel.grise.utils;

import com.gexel.grise.GRisePlugin;
import com.gexel.grise.arena.Arena;
import com.gexel.grise.arena.Difficulty;
import com.gexel.grise.arena.GameSession;
import org.bukkit.Color;
import org.bukkit.Location;
import org.bukkit.entity.*;
import org.bukkit.metadata.FixedMetadataValue;

import java.util.Random;

/**
 * Controlled-random entity generator for G-Rise arenas.
 *
 * <h3>Height distribution fix</h3>
 * Entities are now distributed uniformly across the full play column (floor→goalY)
 * regardless of count. Each entity occupies its own vertical slot so even 12
 * entities cover a 200-block column end-to-end.
 *
 * <h3>Per-arena entity type and glowing</h3>
 * Entity type comes from {@link Arena#getEntityType()}.
 * Glowing color (if enabled) is applied via a scoreboard team.
 *
 * @author Gexel
 */
public class JumpGenerator {

    private final GRisePlugin plugin;
    private final Arena       arena;
    private final Difficulty  difficulty;
    private final Random      random;
    private final int         maxHoriz;
    private final int         minHoriz;

    public JumpGenerator(GRisePlugin plugin, Arena arena, Difficulty difficulty) {
        this.plugin     = plugin;
        this.arena      = arena;
        this.difficulty = difficulty;
        this.random     = new Random();
        maxHoriz = plugin.getConfig().getInt("spawning.generation.max-horizontal-offset", 3);
        minHoriz = plugin.getConfig().getInt("spawning.generation.min-horizontal-offset", 1);
    }

    /**
     * Spawns {@code count} entities uniformly distributed across the full column.
     * Guarantees full-height coverage regardless of entity count.
     */
    public Entity[] spawnAll(int count) {
        if (count <= 0) return new Entity[0];

        double floorY = arena.getSpawnLocation().getY() + 2;
        double topY   = arena.getGoalY() - 2;
        double height = topY - floorY;
        if (height <= 0) return new Entity[0];

        double slotSize = height / count;
        Entity[] result = new Entity[count];
        double prevX = arena.getCenter().getX();
        double prevZ = arena.getCenter().getZ();

        for (int i = 0; i < count; i++) {
            double slotFloor = floorY + i * slotSize;
            double spawnY    = slotFloor + random.nextDouble() * slotSize * 0.7;

            int    mag   = minHoriz + random.nextInt(maxHoriz - minHoriz + 1);
            double angle = random.nextDouble() * 2 * Math.PI;
            double newX  = clamp(prevX + Math.cos(angle) * mag,
                                 arena.getCenter().getX() - arena.getRadius(),
                                 arena.getCenter().getX() + arena.getRadius());
            double newZ  = clamp(prevZ + Math.sin(angle) * mag,
                                 arena.getCenter().getZ() - arena.getRadius(),
                                 arena.getCenter().getZ() + arena.getRadius());

            result[i] = spawnEntityAt(new Location(arena.getWorld(), newX, spawnY, newZ));
            prevX = newX;
            prevZ = newZ;
        }
        return result;
    }

    /**
     * Spawns a single entity above {@code from}. Used for chain-mode Hard.
     * Returns {@code null} when above goalY.
     */
    public Entity spawnNext(Location from) {
        int minY = plugin.getConfig().getInt("spawning.generation.min-y-offset", 3);
        int maxY = plugin.getConfig().getInt("spawning.generation.max-y-offset", 6);
        double newY = from.getY() + minY + random.nextInt(maxY - minY + 1);
        if (newY >= arena.getGoalY()) return null;

        int    mag   = minHoriz + random.nextInt(maxHoriz - minHoriz + 1);
        double angle = random.nextDouble() * 2 * Math.PI;
        double newX  = clamp(from.getX() + Math.cos(angle) * mag,
                             arena.getCenter().getX() - arena.getRadius(),
                             arena.getCenter().getX() + arena.getRadius());
        double newZ  = clamp(from.getZ() + Math.sin(angle) * mag,
                             arena.getCenter().getZ() - arena.getRadius(),
                             arena.getCenter().getZ() + arena.getRadius());

        return spawnEntityAt(new Location(arena.getWorld(), newX, newY, newZ));
    }

    // -----------------------------------------------------------------------
    // Private helpers
    // -----------------------------------------------------------------------

    private Entity spawnEntityAt(Location location) {
        Entity entity = location.getWorld().spawnEntity(location, arena.getEntityType());

        if (entity instanceof LivingEntity living) {
            living.setAI(false);
            living.setSilent(true);
            living.setInvulnerable(difficulty.isEntitiesImmortal());

            if (difficulty.isRace()) {
                int hp = plugin.getConfig().getInt("spawning.multiplayer-entity-health", 5) * 2;
                living.setMaxHealth(hp);
                living.setHealth(hp);
            }
            if (living instanceof Mob mob) mob.setPersistent(true);

            if (arena.isGlowEnabled()) {
                living.setGlowing(true);
                applyGlowColor(living, arena.getGlowColor());
            }
        }

        entity.setGravity(false);
        entity.setMetadata(GameSession.META_KEY, new FixedMetadataValue(plugin, true));
        entity.customName(net.kyori.adventure.text.Component.text("§b✦ G-Rise Target"));
        entity.setCustomNameVisible(false);
        return entity;
    }

    /** Applies the closest chat-color glow via a scoreboard team. */
    private void applyGlowColor(LivingEntity entity, Color color) {
        org.bukkit.ChatColor closest = closestChatColor(color);
        String teamName = "grise_" + closest.name().toLowerCase();
        var scoreboard  = plugin.getServer().getScoreboardManager().getMainScoreboard();
        var team        = scoreboard.getTeam(teamName);
        if (team == null) {
            team = scoreboard.registerNewTeam(teamName);
            team.setColor(closest);
        }
        team.addEntry(entity.getUniqueId().toString());
    }

    private static org.bukkit.ChatColor closestChatColor(Color t) {
        record E(org.bukkit.ChatColor cc, int r, int g, int b) {}
        E[] pal = {
            new E(org.bukkit.ChatColor.BLACK,        0,   0,   0),
            new E(org.bukkit.ChatColor.DARK_BLUE,    0,   0, 170),
            new E(org.bukkit.ChatColor.DARK_GREEN,   0, 170,   0),
            new E(org.bukkit.ChatColor.DARK_AQUA,    0, 170, 170),
            new E(org.bukkit.ChatColor.DARK_RED,   170,   0,   0),
            new E(org.bukkit.ChatColor.DARK_PURPLE,170,   0, 170),
            new E(org.bukkit.ChatColor.GOLD,       255, 170,   0),
            new E(org.bukkit.ChatColor.GRAY,       170, 170, 170),
            new E(org.bukkit.ChatColor.DARK_GRAY,   85,  85,  85),
            new E(org.bukkit.ChatColor.BLUE,        85,  85, 255),
            new E(org.bukkit.ChatColor.GREEN,        85, 255,  85),
            new E(org.bukkit.ChatColor.AQUA,         85, 255, 255),
            new E(org.bukkit.ChatColor.RED,         255,  85,  85),
            new E(org.bukkit.ChatColor.LIGHT_PURPLE,255,  85, 255),
            new E(org.bukkit.ChatColor.YELLOW,      255, 255,  85),
            new E(org.bukkit.ChatColor.WHITE,       255, 255, 255),
        };
        org.bukkit.ChatColor best = org.bukkit.ChatColor.WHITE;
        double bestD = Double.MAX_VALUE;
        for (E e : pal) {
            double d = Math.pow(t.getRed()-e.r(),2) + Math.pow(t.getGreen()-e.g(),2) + Math.pow(t.getBlue()-e.b(),2);
            if (d < bestD) { bestD = d; best = e.cc(); }
        }
        return best;
    }

    private static double clamp(double v, double min, double max) {
        return Math.max(min, Math.min(max, v));
    }
}
