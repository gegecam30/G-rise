package com.gexel.grise.utils;

import com.gexel.grise.GRisePlugin;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.configuration.file.YamlConfiguration;

import java.io.File;
import java.io.IOException;

/**
 * Shows a friendly one-time review request the first time the plugin is loaded.
 *
 * <h3>Persistence</h3>
 * A small {@code data.yml} file is kept in the plugin's data folder with a
 * single boolean key {@code review-shown}. Once the message is displayed the
 * flag is set to {@code true} and never shown again — across server restarts,
 * updates, and config reloads.
 *
 * <h3>Where it appears</h3>
 * <ul>
 *   <li>In the <b>server console</b> as a sequence of INFO log lines.</li>
 *   <li>As a private <b>in-game message</b> sent 5 seconds after startup to
 *       every online player who has the {@code grise.admin} permission
 *       (catches the case where the owner is already logged in when the
 *       server starts).</li>
 * </ul>
 *
 * @author Gexel
 */
public final class ReviewNotifier {

    // -----------------------------------------------------------------------
    // Constants
    // -----------------------------------------------------------------------

    private static final String DATA_FILE_NAME = "data.yml";
    private static final String FLAG_KEY       = "review-shown";
    private static final String SPIGOT_URL     =
            "https://www.spigotmc.org/resources/12345";

    // -----------------------------------------------------------------------
    // Fields
    // -----------------------------------------------------------------------

    private final GRisePlugin plugin;
    private File              dataFile;
    private FileConfiguration dataConfig;

    // -----------------------------------------------------------------------
    // Constructor
    // -----------------------------------------------------------------------

    public ReviewNotifier(GRisePlugin plugin) {
        this.plugin = plugin;
        loadDataFile();
    }

    // -----------------------------------------------------------------------
    // Public API
    // -----------------------------------------------------------------------

    /**
     * Shows the review notification if it has never been shown before.
     * Call once from {@code onEnable()} after all subsystems are ready.
     */
    public void showIfFirstRun() {
        if (dataConfig.getBoolean(FLAG_KEY, false)) {
            // Already shown — never bother again.
            return;
        }

        // Print to console immediately.
        printConsoleMessage();

        // Send in-game to any admin already online (delayed 5 s so chat is visible).
        plugin.getServer().getScheduler().runTaskLater(plugin, () -> {
            for (var player : plugin.getServer().getOnlinePlayers()) {
                if (player.hasPermission("grise.admin")) {
                    sendInGameMessage(player);
                }
            }
        }, 100L); // 100 ticks = 5 seconds

        // Persist the flag so this never runs again.
        markShown();
    }

    /**
     * Sends the in-game review message to a single player.
     * Call this from {@code PlayerJoinEvent} for admins if it's their first join
     * and the flag hasn't been set yet (handled internally via {@link #showIfFirstRun()}).
     */
    public void sendInGameMessage(org.bukkit.entity.Player player) {
        var serial = net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer
                .legacyAmpersand();

        player.sendMessage(serial.deserialize("&8&m                                                  "));
        player.sendMessage(serial.deserialize("  &b&lG-Rise &7— Thanks for installing the plugin!"));
        player.sendMessage(serial.deserialize(""));
        player.sendMessage(serial.deserialize("  &7If you're enjoying it, please consider leaving"));
        player.sendMessage(serial.deserialize("  &7a &e⭐ review &7on SpigotMC — it helps a lot!"));
        player.sendMessage(serial.deserialize(""));
        player.sendMessage(serial.deserialize("  &b" + SPIGOT_URL));
        player.sendMessage(serial.deserialize(""));
        player.sendMessage(serial.deserialize("  &8(This message will never appear again.)"));
        player.sendMessage(serial.deserialize("&8&m                                                  "));
    }

    // -----------------------------------------------------------------------
    // Private helpers
    // -----------------------------------------------------------------------

    private void printConsoleMessage() {
        var log = plugin.getLogger();
        log.info("╔══════════════════════════════════════════════════╗");
        log.info("║   G-Rise — Thanks for installing the plugin!     ║");
        log.info("║                                                  ║");
        log.info("║  If you enjoy it, please leave a ⭐ review on    ║");
        log.info("║  SpigotMC! It really helps the project grow.     ║");
        log.info("║                                                  ║");
        log.info("║  " + SPIGOT_URL + "  ║");
        log.info("║                                                  ║");
        log.info("║  (This message appears only once.)               ║");
        log.info("╚══════════════════════════════════════════════════╝");
    }

    private void markShown() {
        dataConfig.set(FLAG_KEY, true);
        try {
            dataConfig.save(dataFile);
        } catch (IOException e) {
            plugin.getLogger().warning("[ReviewNotifier] Could not save data.yml: " + e.getMessage());
        }
    }

    private void loadDataFile() {
        dataFile = new File(plugin.getDataFolder(), DATA_FILE_NAME);
        if (!dataFile.exists()) {
            plugin.getDataFolder().mkdirs();
            try {
                dataFile.createNewFile();
            } catch (IOException e) {
                plugin.getLogger().warning(
                        "[ReviewNotifier] Could not create data.yml: " + e.getMessage());
            }
        }
        dataConfig = YamlConfiguration.loadConfiguration(dataFile);
    }
}
