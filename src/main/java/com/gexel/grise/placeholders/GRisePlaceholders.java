package com.gexel.grise.placeholders;

import com.gexel.grise.GRisePlugin;
import com.gexel.grise.arena.GameSession;
import com.gexel.grise.database.DatabaseManager;
import me.clip.placeholderapi.expansion.PlaceholderExpansion;
import org.bukkit.OfflinePlayer;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

/**
 * PlaceholderAPI expansion for G-Rise.
 *
 * <h3>Available placeholders</h3>
 * <pre>
 * %grise_playing%          → "true" / "false"
 * %grise_arena%            → arena name or "Not Playing"
 * %grise_difficulty%       → difficulty name or "Not Playing"
 * %grise_best_easy%        → personal best time on Easy, or "No Record"
 * %grise_best_medium%      → personal best time on Medium, or "No Record"
 * %grise_best_hard%        → personal best time on Hard, or "No Record"
 * %grise_wins%             → total win count
 * %grise_games%            → total games played
 * </pre>
 *
 * @author Gexel
 */
public class GRisePlaceholders extends PlaceholderExpansion {

    // -----------------------------------------------------------------------
    // Fields
    // -----------------------------------------------------------------------

    private final GRisePlugin plugin;

    // -----------------------------------------------------------------------
    // Constructor
    // -----------------------------------------------------------------------

    public GRisePlaceholders(GRisePlugin plugin) {
        this.plugin = plugin;
    }

    // -----------------------------------------------------------------------
    // PlaceholderExpansion meta
    // -----------------------------------------------------------------------

    @Override
    public @NotNull String getIdentifier() { return "grise"; }

    @Override
    public @NotNull String getAuthor() { return "Gexel"; }

    @Override
    public @NotNull String getVersion() { return plugin.getDescription().getVersion(); }

    /** Prevents PAPI from unregistering on plugin disable/reload. */
    @Override
    public boolean persist() { return true; }

    // -----------------------------------------------------------------------
    // Placeholder resolution
    // -----------------------------------------------------------------------

    @Override
    public @Nullable String onRequest(OfflinePlayer player, @NotNull String params) {

        String notPlaying = plugin.getMessageUtil().colorize("placeholders.not-playing");
        String noRecord   = plugin.getMessageUtil().colorize("placeholders.no-record");

        // --- Live / session placeholders ---
        var onlinePlayer = player.getPlayer();

        switch (params.toLowerCase()) {
            case "playing" -> {
                if (onlinePlayer == null) return "false";
                return String.valueOf(plugin.getArenaManager().isPlaying(onlinePlayer));
            }
            case "arena" -> {
                if (onlinePlayer == null) return notPlaying;
                GameSession session = plugin.getArenaManager().getSessionOf(onlinePlayer);
                return session != null ? session.getArena().getName() : notPlaying;
            }
            case "difficulty" -> {
                if (onlinePlayer == null) return notPlaying;
                GameSession session = plugin.getArenaManager().getSessionOf(onlinePlayer);
                return session != null ? session.getDifficulty().getDisplayName() : notPlaying;
            }
        }

        // --- Database placeholders (synchronous – PAPI expects it) ---
        // Note: PAPI calls this on the main thread. For heavy queries, consider
        // caching results in a scheduled async task instead of querying here.
        DatabaseManager.PlayerStats stats = plugin.getDatabaseManager().getPlayerStats(player.getUniqueId());

        if (stats == null) {
            return switch (params.toLowerCase()) {
                case "wins", "games" -> "0";
                default              -> noRecord;
            };
        }

        return switch (params.toLowerCase()) {
            case "best_easy"   -> stats.bestEasy()   != null ? stats.bestEasy()   : noRecord;
            case "best_medium" -> stats.bestMedium() != null ? stats.bestMedium() : noRecord;
            case "best_hard"   -> stats.bestHard()   != null ? stats.bestHard()   : noRecord;
            case "wins"        -> String.valueOf(stats.wins());
            case "games"       -> String.valueOf(stats.gamesPlayed());
            default            -> null; // unknown placeholder
        };
    }
}
