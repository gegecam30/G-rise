package com.gexel.grise;

import com.gexel.grise.arena.ArenaManager;
import com.gexel.grise.commands.GRiseCommand;
import com.gexel.grise.database.DatabaseManager;
import com.gexel.grise.listeners.MaceListener;
import com.gexel.grise.listeners.PlayerListener;
import com.gexel.grise.placeholders.GRisePlaceholders;
import com.gexel.grise.utils.MessageUtil;
import com.gexel.grise.utils.ReviewNotifier;
import com.gexel.grise.utils.UpdateChecker;
import org.bukkit.plugin.java.JavaPlugin;

public final class GRisePlugin extends JavaPlugin {

    private static GRisePlugin instance;
    private DatabaseManager databaseManager;
    private ArenaManager arenaManager;
    private MessageUtil messageUtil;
    private UpdateChecker updateChecker;
    private ReviewNotifier reviewNotifier;

    @Override
    public void onEnable() {
        instance = this;

        saveDefaultConfig();
        saveResource("messages.yml", false);

        messageUtil = new MessageUtil(this);

        databaseManager = new DatabaseManager(this);
        if (!databaseManager.connect()) {
            getServer().getPluginManager().disablePlugin(this);
            return;
        }
        databaseManager.createTables();

        arenaManager = new ArenaManager(this);
        arenaManager.loadArenas();

        registerListeners();
        registerCommands();

        if (getServer().getPluginManager().getPlugin("PlaceholderAPI") != null) {
            new GRisePlaceholders(this).register();
        }

        // Activación de utilidades
        updateChecker = new UpdateChecker(this);
        updateChecker.checkAsync();

        reviewNotifier = new ReviewNotifier(this);
        reviewNotifier.showIfFirstRun();

        // Console Message (Hex Colors)
        String hexCyan = net.md_5.bungee.api.ChatColor.of("#00e5ff").toString();
        String hexLight = net.md_5.bungee.api.ChatColor.of("#4dd0e1").toString();
        String reset = net.md_5.bungee.api.ChatColor.RESET.toString();

        var console = getServer().getConsoleSender();
        console.sendMessage(hexCyan + "========================================");
        console.sendMessage(hexCyan + "  [G-Rise] " + hexLight + "v" + getDescription().getVersion() + " loaded successfully!");
        console.sendMessage(hexLight + "  Thanks for downloading. Check documentation at:");
        console.sendMessage(hexLight + "  https://myplugins.gexel.fun");
        console.sendMessage(hexCyan + "========================================" + reset);
    }

    private void registerListeners() {
        var pm = getServer().getPluginManager();
        pm.registerEvents(new MaceListener(this), this);
        pm.registerEvents(new PlayerListener(this), this);
    }

    private void registerCommands() {
        var executor = new GRiseCommand(this);
        var cmd = getCommand("gr");
        if (cmd != null) {
            cmd.setExecutor(executor);
            cmd.setTabCompleter(executor);
        }
    }

    @Override
    public void onDisable() {
        if (arenaManager != null) arenaManager.shutdownAll();
        if (databaseManager != null) databaseManager.disconnect();
    }

    public static GRisePlugin getInstance() { return instance; }
    public ArenaManager getArenaManager() { return arenaManager; }
    public DatabaseManager getDatabaseManager() { return databaseManager; }
    public MessageUtil getMessageUtil() { return messageUtil; }
    public UpdateChecker getUpdateChecker() { return updateChecker; }
    public ReviewNotifier getReviewNotifier() { return reviewNotifier; }
}