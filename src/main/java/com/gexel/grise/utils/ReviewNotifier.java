package com.gexel.grise.utils;

import com.gexel.grise.GRisePlugin;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.configuration.file.YamlConfiguration;
import java.io.File;
import java.io.IOException;

public final class ReviewNotifier {

    private static final String DATA_FILE_NAME = "data.yml";
    private static final String FLAG_KEY = "review-shown";
    private static final String SPIGOT_URL = "https://www.spigotmc.org/resources/134375";

    private final GRisePlugin plugin;
    private File dataFile;
    private FileConfiguration dataConfig;

    public ReviewNotifier(GRisePlugin plugin) {
        this.plugin = plugin;
        loadDataFile();
    }

    public void showIfFirstRun() {
        if (dataConfig.getBoolean(FLAG_KEY, false)) return;

        printConsoleMessage();

        plugin.getServer().getScheduler().runTaskLater(plugin, () -> {
            for (var player : plugin.getServer().getOnlinePlayers()) {
                if (player.hasPermission("grise.admin")) sendInGameMessage(player);
            }
        }, 100L);

        markShown();
    }

    public void sendInGameMessage(org.bukkit.entity.Player player) {
        var serial = net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer.legacyAmpersand();
        player.sendMessage(serial.deserialize("&8&m                                                  "));
        player.sendMessage(serial.deserialize("  &b&lG-Rise &7— Thanks for installing the plugin!"));
        player.sendMessage(serial.deserialize(""));
        player.sendMessage(serial.deserialize("  &7If you are enjoying it, please consider leaving a &e⭐ review"));
        player.sendMessage(serial.deserialize("  &7on SpigotMC. It helps the project a lot!"));
        player.sendMessage(serial.deserialize(""));
        player.sendMessage(serial.deserialize("  &b" + SPIGOT_URL));
        player.sendMessage(serial.deserialize("&8&m                                                  "));
    }

    private void printConsoleMessage() {
        var log = plugin.getLogger();
        log.info("╔══════════════════════════════════════════════════╗");
        log.info("║   G-Rise — Thanks for installing the plugin!     ║");
        log.info("║                                                  ║");
        log.info("║  If you enjoy it, please leave a ⭐ review on    ║");
        log.info("║  SpigotMC! It really helps the project grow.     ║");
        log.info("║                                                  ║");
        log.info("║  " + SPIGOT_URL + "  ║");
        log.info("╚══════════════════════════════════════════════════╝");
    }

    private void markShown() {
        dataConfig.set(FLAG_KEY, true);
        try { dataConfig.save(dataFile); } catch (IOException e) { e.printStackTrace(); }
    }

    private void loadDataFile() {
        dataFile = new File(plugin.getDataFolder(), DATA_FILE_NAME);
        if (!dataFile.exists()) {
            plugin.getDataFolder().mkdirs();
            try { dataFile.createNewFile(); } catch (IOException e) { e.printStackTrace(); }
        }
        dataConfig = YamlConfiguration.loadConfiguration(dataFile);
    }
}