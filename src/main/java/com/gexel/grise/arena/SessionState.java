package com.gexel.grise.arena;

/**
 * Represents the lifecycle state of a {@link GameSession}.
 *
 * <p>Valid transitions:
 * <pre>
 *   WAITING → COUNTDOWN → ACTIVE → ENDED
 * </pre>
 *
 * @author Gexel
 */
public enum SessionState {
    /** Accepting players; countdown has not yet started. */
    WAITING,
    /** Countdown is running; no new players may join. */
    COUNTDOWN,
    /** Game is live; players are climbing. */
    ACTIVE,
    /** Game has ended; session is being cleaned up. */
    ENDED
}
