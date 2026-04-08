package com.gexel.grise.arena;

/**
 * Represents the three available difficulty modes in G-Rise.
 *
 * <p>Each difficulty alters entity behaviour, visibility and fall penalties.
 * The {@code RACE} value is used exclusively for multiplayer sessions.
 *
 * @author Gexel
 */
public enum Difficulty {

    /**
     * Entities are immortal and always visible.
     * No penalty on fall – designed for newcomers.
     */
    EASY,

    /**
     * Entities die in one hit.
     * Touching the floor teleports the player to the last checkpoint (or start).
     */
    MEDIUM,

    /**
     * "Memory" mode: entities appear briefly with a dispenser sound,
     * become invisible after a short window, and never die.
     * Touching the floor triggers a checkpoint reset.
     */
    HARD,

    /**
     * Multiplayer race mode.
     * Entities are persistent (require multiple hits to die) and
     * player-to-player collision is enabled.
     */
    RACE;

    // -----------------------------------------------------------------------
    // Utility
    // -----------------------------------------------------------------------

    /**
     * Parses a {@link Difficulty} from a string, case-insensitively.
     *
     * @param value the string to parse
     * @return the matching difficulty, or {@code null} if not found
     */
    public static Difficulty fromString(String value) {
        if (value == null) return null;
        try {
            return valueOf(value.toUpperCase());
        } catch (IllegalArgumentException e) {
            return null;
        }
    }

    /**
     * Returns a human-friendly display label for this difficulty.
     *
     * @return capitalised name
     */
    public String getDisplayName() {
        String n = name();
        return n.charAt(0) + n.substring(1).toLowerCase();
    }

    /**
     * Returns {@code true} if entities should be immortal (cannot be killed) in
     * this difficulty.
     */
    public boolean isEntitiesImmortal() {
        return this == EASY || this == HARD || this == RACE;
    }

    /**
     * Returns {@code true} if the player should be teleported to a checkpoint
     * when they touch the arena floor.
     */
    public boolean hasFallPenalty() {
        return this == MEDIUM || this == HARD;
    }

    /**
     * Returns {@code true} if entities start invisible (Hard "memory" mode).
     */
    public boolean isMemoryMode() {
        return this == HARD;
    }

    /**
     * Returns {@code true} if this is a multiplayer race session.
     */
    public boolean isRace() {
        return this == RACE;
    }
}
