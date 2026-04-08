package com.gexel.grise.utils;

import com.gexel.grise.GRisePlugin;
import org.bukkit.entity.Player;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.logging.Level;

/**
 * Checks SpigotMC for a newer version of G-Rise and notifies online admins.
 *
 * <h3>How it works</h3>
 * <ol>
 *   <li>On plugin startup, an async task hits the Spigot resource API:
 *       {@code https://api.spigotmc.org/legacy/update.php?resource=PLUGIN_ID}</li>
 *   <li>The response is a single plain-text string with the latest version.</li>
 *   <li>We compare it to {@link org.bukkit.plugin.PluginDescriptionFile#getVersion()}.
 *       If they differ, we store the new version string.</li>
 *   <li>When an admin joins, {@link GRisePlugin} calls
 *       {@link #notifyPlayer(Player)} which sends a private chat message.</li>
 * </ol>
 *
 * <h3>Thread safety</h3>
 * The HTTP call runs on a new thread (via {@code runTaskAsynchronously}).
 * The result is stored in a volatile field and read on the main thread.
 *
 * @author Gexel
 */
public final class UpdateChecker {

    // -----------------------------------------------------------------------
    // Constants
    // -----------------------------------------------------------------------

    /**
     * Your SpigotMC resource ID.
     * Replace "12345" with your real ID once the plugin is uploaded.
     */
    private static final int SPIGOT_RESOURCE_ID = 12345;

    private static final String API_URL =
            "https://api.spigotmc.org/legacy/update.php?resource=" + SPIGOT_RESOURCE_ID;

    private static final String DOWNLOAD_URL =
            "https://www.spigotmc.org/resources/" + SPIGOT_RESOURCE_ID;

    // -----------------------------------------------------------------------
    // Fields
    // -----------------------------------------------------------------------

    private final GRisePlugin plugin;

    /**
     * The latest version string from Spigot, or {@code null} if the check
     * hasn't completed yet or failed.
     */
    private volatile String latestVersion = null;

    /** {@code true} if an update is available (latestVersion != current). */
    private volatile boolean updateAvailable = false;

    // -----------------------------------------------------------------------
    // Constructor
    // -----------------------------------------------------------------------

    public UpdateChecker(GRisePlugin plugin) {
        this.plugin = plugin;
    }

    // -----------------------------------------------------------------------
    // Public API
    // -----------------------------------------------------------------------

    /**
     * Starts the async version check. Call once from {@code onEnable()}.
     * Results are stored internally; use {@link #notifyPlayer(Player)} to
     * surface them.
     */
    public void checkAsync() {
        plugin.getServer().getScheduler().runTaskAsynchronously(plugin, () -> {
            try {
                HttpURLConnection conn = (HttpURLConnection)
                        new URL(API_URL).openConnection();
                conn.setRequestMethod("GET");
                conn.setConnectTimeout(5_000);
                conn.setReadTimeout(5_000);
                conn.setRequestProperty("User-Agent", "G-Rise UpdateChecker");

                if (conn.getResponseCode() != 200) {
                    plugin.getLogger().warning(
                            "[UpdateChecker] Spigot API returned HTTP " + conn.getResponseCode());
                    return;
                }

                try (BufferedReader reader = new BufferedReader(
                        new InputStreamReader(conn.getInputStream()))) {
                    String remote = reader.readLine().trim();
                    String current = plugin.getDescription().getVersion();

                    if (!remote.equalsIgnoreCase(current)) {
                        latestVersion   = remote;
                        updateAvailable = true;
                        plugin.getLogger().info(
                                "[UpdateChecker] Update available: " + current
                                + " → " + remote + " | " + DOWNLOAD_URL);
                    } else {
                        plugin.getLogger().info(
                                "[UpdateChecker] G-Rise is up to date (v" + current + ").");
                    }
                }
            } catch (Exception e) {
                // Network errors are non-fatal; log at fine level to avoid spam.
                plugin.getLogger().log(Level.FINE,
                        "[UpdateChecker] Could not reach Spigot API: " + e.getMessage());
            }
        });
    }

    /**
     * Sends the update notification to a player if an update is available.
     * Call this from a {@code PlayerJoinEvent} handler for admins.
     *
     * @param player the player to notify (should have {@code grise.admin})
     */
    public void notifyPlayer(Player player) {
        if (!updateAvailable || latestVersion == null) return;

        String current = plugin.getDescription().getVersion();

        player.sendMessage(net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer
                .legacyAmpersand().deserialize(
                        "&8[&bG-Rise&8] &eUpdate available! &7v" + current
                        + " &8→ &av" + latestVersion));
        player.sendMessage(net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer
                .legacyAmpersand().deserialize(
                        "&8[&bG-Rise&8] &7Download: &b" + DOWNLOAD_URL));
    }

    // -----------------------------------------------------------------------
    // Getters
    // -----------------------------------------------------------------------

    /** Returns {@code true} if a newer version was found on Spigot. */
    public boolean isUpdateAvailable() {
        return updateAvailable;
    }

    /** Returns the latest version string, or {@code null} if check is pending/failed. */
    public String getLatestVersion() {
        return latestVersion;
    }
}
