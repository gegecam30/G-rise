package com.gexel.grise.utils;

/**
 * Utility class for time formatting in G-Rise.
 *
 * <p>All game timers are measured in server ticks (20 ticks = 1 second).
 * This utility converts tick counts and millisecond values into human-readable
 * strings used in the leaderboard and win messages.
 *
 * @author Gexel
 */
public final class TimeUtil {

    private TimeUtil() {}

    /**
     * Converts a tick count to a formatted time string.
     *
     * <p>Format: {@code mm:ss.S} where {@code S} is tenths of a second.
     * Example: {@code 1234} ticks → {@code "01:01.7"}
     *
     * @param ticks number of game ticks
     * @return formatted time string
     */
    public static String formatTicks(long ticks) {
        long totalMillis = (ticks * 1000L) / 20L;
        return formatMillis(totalMillis);
    }

    /**
     * Converts milliseconds to a {@code mm:ss.SSS} formatted string.
     *
     * <p>Example: {@code 83456} ms → {@code "01:23.456"}
     *
     * @param millis total elapsed milliseconds
     * @return formatted time string
     */
    public static String formatMillis(long millis) {
        long minutes      = millis / 60_000L;
        long seconds      = (millis % 60_000L) / 1_000L;
        long millisPart   = millis % 1_000L;
        return String.format("%02d:%02d.%03d", minutes, seconds, millisPart);
    }

    /**
     * Parses a {@code mm:ss.SSS} time string back to milliseconds.
     *
     * @param display formatted time string
     * @return total milliseconds, or {@link Long#MAX_VALUE} on parse failure
     */
    public static long parseToMillis(String display) {
        try {
            String[] parts   = display.split("[:.]");
            long minutes     = Long.parseLong(parts[0]);
            long seconds     = Long.parseLong(parts[1]);
            long millisPart  = parts.length > 2 ? Long.parseLong(parts[2]) : 0L;
            return minutes * 60_000L + seconds * 1_000L + millisPart;
        } catch (Exception e) {
            return Long.MAX_VALUE;
        }
    }
}
