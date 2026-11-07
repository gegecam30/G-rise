package com.gexel.grise.utils;

import com.gexel.grise.GRisePlugin;
import org.bukkit.entity.Player;
import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.logging.Level;

public final class UpdateChecker {

    private static final int SPIGOT_RESOURCE_ID = 134375;
    private static final String API_URL = "https://api.spigotmc.org/legacy/update.php?resource=" + SPIGOT_RESOURCE_ID;
    private static final String DOWNLOAD_URL = "https://www.spigotmc.org/resources/" + SPIGOT_RESOURCE_ID;

    private final GRisePlugin plugin;
    private volatile String latestVersion = null;
    private volatile boolean updateAvailable = false;

    public UpdateChecker(GRisePlugin plugin) {
        this.plugin = plugin;
    }

    public void checkAsync() {
        plugin.getServer().getScheduler().runTaskAsynchronously(plugin, () -> {
            try {
                HttpURLConnection conn = (HttpURLConnection) new URL(API_URL).openConnection();
                conn.setRequestMethod("GET");
                conn.setConnectTimeout(5000);
                conn.setReadTimeout(5000);
                conn.setRequestProperty("User-Agent", "G-Rise UpdateChecker");

                if (conn.getResponseCode() != 200) return;

                try (BufferedReader reader = new BufferedReader(new InputStreamReader(conn.getInputStream()))) {
                    String remote = reader.readLine().trim();
                    String current = plugin.getDescription().getVersion();
                    
                    if (!remote.equalsIgnoreCase(current)) {
                        latestVersion = remote;
                        updateAvailable = true;
                        
                        // Console Alert (HEX Colors)
                        String hexAlert = net.md_5.bungee.api.ChatColor.of("#ff5555").toString();
                        String hexWarn = net.md_5.bungee.api.ChatColor.of("#ffb86c").toString();
                        String reset = net.md_5.bungee.api.ChatColor.RESET.toString();
                        
                        var console = plugin.getServer().getConsoleSender();
                        console.sendMessage(hexAlert + "========================================");
                        console.sendMessage(hexAlert + "  [G-Rise] " + hexWarn + "UPDATE AVAILABLE!");
                        console.sendMessage(hexWarn + "  Current version: " + current + " -> New: " + remote);
                        console.sendMessage(hexWarn + "  Download: " + DOWNLOAD_URL);
                        console.sendMessage(hexAlert + "========================================" + reset);
                    } else {
                        plugin.getLogger().info("[UpdateChecker] G-Rise is up to date (v" + current + ").");
                    }
                }
            } catch (Exception e) {
                plugin.getLogger().log(Level.FINE, "[UpdateChecker] Error connecting to Spigot: " + e.getMessage());
            }
        });
    }

    public void notifyPlayer(Player player) {
        if (!updateAvailable || latestVersion == null) return;

        String hexCyan = net.md_5.bungee.api.ChatColor.of("#00e5ff").toString();
        String hexLight = net.md_5.bungee.api.ChatColor.of("#4dd0e1").toString();
        String hexWarn = net.md_5.bungee.api.ChatColor.of("#ffb86c").toString();

        player.sendMessage("");
        player.sendMessage(hexCyan + "========================================");
        player.sendMessage(hexCyan + "  [G-Rise] " + hexWarn + "NEW UPDATE AVAILABLE!");
        player.sendMessage(hexLight + "  Current version: " + plugin.getDescription().getVersion() + " " + hexCyan + "→ " + hexLight + "New: " + latestVersion);
        player.sendMessage(hexLight + "  Download it at: " + hexCyan + "https://www.spigotmc.org/resources/134375");
        player.sendMessage(hexCyan + "========================================");
        player.sendMessage("");
    }
}