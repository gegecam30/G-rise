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

import java.util.logging.Level;

/**
 * Main entry point for the G-Rise plugin.
 *
 * @author Gexel
 * @version 1.0.0
 */
public final class GRisePlugin extends JavaPlugin {

    // -----------------------------------------------------------------------
    // Singleton accessor
    // -----------------------------------------------------------------------

    private static GRisePlugin instance;

    public static GRisePlugin getInstance() {
        return instance;
    }

    // -----------------------------------------------------------------------
    // Subsystem references
    // -----------------------------------------------------------------------

    private DatabaseManager databaseManager;
    private ArenaManager    arenaManager;
    private MessageUtil     messageUtil;
    private UpdateChecker   updateChecker;
    private ReviewNotifier  reviewNotifier;

    // -----------------------------------------------------------------------
    // Lifecycle
    // -----------------------------------------------------------------------

    @Override
    public void onEnable() {
        instance = this;

        // 1. Save default config files so they exist on first run.
        saveDefaultConfig();
        saveResource("messages.yml", false);

        // 2. Initialise message utility (reads messages.yml).
        messageUtil = new MessageUtil(this);

        // 3. Initialise database.
        databaseManager = new DatabaseManager(this);
        if (!databaseManager.connect()) {
            getLogger().severe("Could not connect to the database. Disabling G-Rise.");
            getServer().getPluginManager().disablePlugin(this);
            return;
        }
        databaseManager.createTables();

        // 4. Initialise arena manager.
        arenaManager = new ArenaManager(this);
        arenaManager.loadArenas();

        // 5. Register event listeners.
        registerListeners();

        // 6. Register commands.
        registerCommands();

        // 7. Hook into PlaceholderAPI if present.
        if (getServer().getPluginManager().getPlugin("PlaceholderAPI") != null) {
            new GRisePlaceholders(this).register();
            getLogger().info("PlaceholderAPI hooked successfully.");
        }

        // 8. Check for updates asynchronously (result used in PlayerJoinEvent).
        updateChecker = new UpdateChecker(this);
        updateChecker.checkAsync();

        // 9. Show one-time review notification (console + online admins).
        reviewNotifier = new ReviewNotifier(this);
        reviewNotifier.showIfFirstRun();

        getLogger().info("G-Rise v" + getDescription().getVersion() + " enabled successfully!");
    }

    @Override
    public void onDisable() {
        if (arenaManager != null)    arenaManager.shutdownAll();
        if (databaseManager != null) databaseManager.disconnect();
        getLogger().info("G-Rise disabled. See you at the top!");
    }

    // -----------------------------------------------------------------------
    // Private helpers
    // -----------------------------------------------------------------------

    private void registerListeners() {
        var pm = getServer().getPluginManager();
        pm.registerEvents(new MaceListener(this), this);
        pm.registerEvents(new PlayerListener(this), this);
    }

    private void registerCommands() {
        var executor = new GRiseCommand(this);
        var cmd = getCommand("gr");
        if (cmd == null) {
            getLogger().log(Level.SEVERE, "Command 'gr' not found in plugin.yml!");
            return;
        }
        cmd.setExecutor(executor);
        cmd.setTabCompleter(executor);
    }

    // -----------------------------------------------------------------------
    // Public accessors
    // -----------------------------------------------------------------------

    public ArenaManager   getArenaManager()   { return arenaManager; }
    public DatabaseManager getDatabaseManager(){ return databaseManager; }
    public MessageUtil    getMessageUtil()     { return messageUtil; }
    public UpdateChecker  getUpdateChecker()  { return updateChecker; }
    public ReviewNotifier getReviewNotifier() { return reviewNotifier; }
}
