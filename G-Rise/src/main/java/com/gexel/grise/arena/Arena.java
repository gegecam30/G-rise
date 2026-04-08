package com.gexel.grise.arena;

import org.bukkit.Color;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.entity.EntityType;

import java.util.Objects;

/**
 * Immutable-ish data object representing a configured G-Rise arena.
 *
 * @author Gexel
 */
public class Arena {

    // -----------------------------------------------------------------------
    // Fields
    // -----------------------------------------------------------------------

    private final String name;
    private final World  world;
    private Location center;
    private int      radius;
    private int      goalY;
    private Location spawnLocation;

    /**
     * Entity type used as targets in this arena.
     * Defaults to WARDEN; configurable per-arena via {@code /gr admin setentity}.
     */
    private EntityType entityType;

    /**
     * Glowing colour applied to target entities.
     * {@code null} means glowing is disabled for this arena.
     * Set via {@code /gr admin setglow}.
     */
    private Color glowColor;

    // -----------------------------------------------------------------------
    // Constructor
    // -----------------------------------------------------------------------

    public Arena(String name, Location center, int radius, int goalY,
                 Location spawnLocation, EntityType entityType, Color glowColor) {
        this.name          = Objects.requireNonNull(name, "Arena name cannot be null");
        this.center        = Objects.requireNonNull(center, "Center cannot be null");
        this.world         = Objects.requireNonNull(center.getWorld(), "Center world cannot be null");
        this.radius        = radius;
        this.goalY         = goalY;
        this.spawnLocation = spawnLocation != null ? spawnLocation : center.clone().add(0, 1, 0);
        this.entityType    = entityType != null ? entityType : EntityType.WARDEN;
        this.glowColor     = glowColor;
    }

    /** Convenience constructor without entity/glow (uses defaults). */
    public Arena(String name, Location center, int radius, int goalY, Location spawnLocation) {
        this(name, center, radius, goalY, spawnLocation, EntityType.WARDEN, null);
    }

    // -----------------------------------------------------------------------
    // Validation helpers
    // -----------------------------------------------------------------------

    public boolean isInsideBounds(Location loc) {
        if (!loc.getWorld().equals(world)) return false;
        double dx = Math.abs(loc.getX() - center.getX());
        double dz = Math.abs(loc.getZ() - center.getZ());
        return dx <= radius && dz <= radius;
    }

    public boolean hasReachedGoal(double y) {
        return y >= goalY;
    }

    // -----------------------------------------------------------------------
    // Getters / setters
    // -----------------------------------------------------------------------

    public String     getName()         { return name; }
    public World      getWorld()        { return world; }
    public Location   getCenter()       { return center.clone(); }
    public int        getRadius()       { return radius; }
    public int        getGoalY()        { return goalY; }
    public Location   getSpawnLocation(){ return spawnLocation.clone(); }
    public EntityType getEntityType()   { return entityType; }
    public Color      getGlowColor()    { return glowColor; }

    public void setCenter(Location center)         { this.center = Objects.requireNonNull(center); }
    public void setGoalY(int goalY)                { this.goalY  = goalY; }
    public void setRadius(int radius)              { if (radius <= 0) throw new IllegalArgumentException("Radius must be positive"); this.radius = radius; }
    public void setSpawnLocation(Location loc)     { this.spawnLocation = Objects.requireNonNull(loc); }
    public void setEntityType(EntityType type)     { this.entityType = Objects.requireNonNull(type); }
    /** Pass {@code null} to disable glowing. */
    public void setGlowColor(Color color)          { this.glowColor = color; }

    /** Returns {@code true} if this arena has glowing enabled for targets. */
    public boolean isGlowEnabled()                 { return glowColor != null; }

    // -----------------------------------------------------------------------
    // Object overrides
    // -----------------------------------------------------------------------

    @Override
    public String toString() {
        return "Arena{name='" + name + "', world=" + world.getName()
                + ", radius=" + radius + ", goalY=" + goalY
                + ", entity=" + entityType + ", glow=" + glowColor + "}";
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (!(o instanceof Arena other)) return false;
        return name.equalsIgnoreCase(other.name);
    }

    @Override
    public int hashCode() {
        return name.toLowerCase().hashCode();
    }
}

