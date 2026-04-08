package com.gexel.grise.commands;

import com.gexel.grise.GRisePlugin;
import com.gexel.grise.arena.Arena;
import com.gexel.grise.arena.Difficulty;
import com.gexel.grise.database.DatabaseManager;
import com.gexel.grise.utils.ArenaVisualizer;
import com.gexel.grise.utils.MessageUtil;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;
import org.bukkit.entity.Player;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.stream.Collectors;

/**
 * Handles the {@code /gr} command and all its subcommands.
 *
 * <h3>Subcommand tree</h3>
 * <pre>
 * /gr help
 * /gr play  &lt;arena&gt; [difficulty]
 * /gr race  &lt;arena&gt;
 * /gr leave
 * /gr stats [player]
 * /gr top   &lt;difficulty&gt;
 * /gr admin create   &lt;name&gt;
 * /gr admin delete   &lt;name&gt;
 * /gr admin setspawn &lt;name&gt;
 * /gr admin setgoal  &lt;name&gt; &lt;y&gt;
 * /gr admin setradius &lt;name&gt; &lt;radius&gt;
 * /gr admin list
 * /gr admin reload
 * </pre>
 *
 * @author Gexel
 */
public class GRiseCommand implements CommandExecutor, TabCompleter {

    // -----------------------------------------------------------------------
    // Fields
    // -----------------------------------------------------------------------

    private final GRisePlugin plugin;
    private final MessageUtil msg;

    // -----------------------------------------------------------------------
    // Constructor
    // -----------------------------------------------------------------------

    public GRiseCommand(GRisePlugin plugin) {
        this.plugin = plugin;
        this.msg    = plugin.getMessageUtil();
    }

    // -----------------------------------------------------------------------
    // CommandExecutor
    // -----------------------------------------------------------------------

    @Override
    public boolean onCommand(CommandSender sender, Command command,
                             String label, String[] args) {

        if (args.length == 0 || args[0].equalsIgnoreCase("help")) {
            sendHelp(sender);
            return true;
        }

        switch (args[0].toLowerCase()) {
            case "play"  -> handlePlay(sender, args);
            case "race"  -> handleRace(sender, args);
            case "leave" -> handleLeave(sender);
            case "stats" -> handleStats(sender, args);
            case "top"   -> handleTop(sender, args);
            case "admin" -> handleAdmin(sender, args);
            default      -> msg.send(sender, "general.unknown-command");
        }
        return true;
    }

    // -----------------------------------------------------------------------
    // Subcommand handlers
    // -----------------------------------------------------------------------

    private void handlePlay(CommandSender sender, String[] args) {
        if (!(sender instanceof Player player)) {
            msg.send(sender, "general.player-only");
            return;
        }
        if (!sender.hasPermission("grise.use")) {
            msg.send(sender, "general.no-permission");
            return;
        }

        if (args.length < 2) {
            msg.send(sender, "general.unknown-command");
            return;
        }

        String arenaName  = args[1];
        Difficulty diff   = args.length >= 3
                ? Difficulty.fromString(args[2])
                : Difficulty.EASY;

        if (diff == null || diff == Difficulty.RACE) {
            msg.send(sender, "game.invalid-difficulty");
            return;
        }

        plugin.getArenaManager().startSolo(player, arenaName, diff);
    }

    private void handleRace(CommandSender sender, String[] args) {
        if (!(sender instanceof Player player)) {
            msg.send(sender, "general.player-only");
            return;
        }
        if (!sender.hasPermission("grise.use")) {
            msg.send(sender, "general.no-permission");
            return;
        }
        if (args.length < 2) {
            msg.send(sender, "general.unknown-command");
            return;
        }

        plugin.getArenaManager().joinRace(player, args[1]);
    }

    private void handleLeave(CommandSender sender) {
        if (!(sender instanceof Player player)) {
            msg.send(sender, "general.player-only");
            return;
        }

        boolean left = plugin.getArenaManager().leaveGame(player);
        if (!left) {
            msg.send(sender, "game.not-in-game");
        }
    }

    private void handleStats(CommandSender sender, String[] args) {
        if (!sender.hasPermission("grise.use")) {
            msg.send(sender, "general.no-permission");
            return;
        }

        // Resolve target: self or named player.
        java.util.UUID targetUUID;
        String targetName;

        if (args.length >= 2) {
            // Look up offline player by name.
            var offline = plugin.getServer().getOfflinePlayerIfCached(args[1]);
            if (offline == null) {
                msg.send(sender, "stats.player-not-found", "{player}", args[1]);
                return;
            }
            targetUUID = offline.getUniqueId();
            targetName = offline.getName() != null ? offline.getName() : args[1];
        } else if (sender instanceof Player p) {
            targetUUID = p.getUniqueId();
            targetName = p.getName();
        } else {
            msg.send(sender, "general.player-only");
            return;
        }

        final java.util.UUID finalUUID = targetUUID;
        final String finalName = targetName;

        // Query asynchronously, respond on main thread.
        plugin.getServer().getScheduler().runTaskAsynchronously(plugin, () -> {
            DatabaseManager.PlayerStats stats = plugin.getDatabaseManager().getPlayerStats(finalUUID);
            plugin.getServer().getScheduler().runTask(plugin, () -> {
                if (stats == null) {
                    msg.send(sender, "stats.player-not-found", "{player}", finalName);
                    return;
                }
                sendStats(sender, stats, finalName);
            });
        });
    }

    private void sendStats(CommandSender sender, DatabaseManager.PlayerStats stats, String playerName) {
        String noRecord = msg.colorize("stats.no-record");

        sender.sendMessage(msg.toComponent(msg.format("stats.header", "{player}", playerName)));
        sender.sendMessage(msg.toComponent(msg.format("stats.games-played", "{games}", String.valueOf(stats.gamesPlayed()))));
        sender.sendMessage(msg.toComponent(msg.format("stats.wins", "{wins}", String.valueOf(stats.wins()))));
        sender.sendMessage(msg.toComponent(msg.format("stats.best-easy",   "{time}", stats.bestEasy()   != null ? stats.bestEasy()   : noRecord)));
        sender.sendMessage(msg.toComponent(msg.format("stats.best-medium", "{time}", stats.bestMedium() != null ? stats.bestMedium() : noRecord)));
        sender.sendMessage(msg.toComponent(msg.format("stats.best-hard",   "{time}", stats.bestHard()   != null ? stats.bestHard()   : noRecord)));
    }

    private void handleTop(CommandSender sender, String[] args) {
        if (!sender.hasPermission("grise.use")) {
            msg.send(sender, "general.no-permission");
            return;
        }
        if (args.length < 2) {
            msg.send(sender, "general.unknown-command");
            return;
        }

        Difficulty diff = Difficulty.fromString(args[1]);
        if (diff == null || diff == Difficulty.RACE) {
            msg.send(sender, "game.invalid-difficulty");
            return;
        }

        int limit = plugin.getConfig().getInt("leaderboard.top-entries", 10);
        final Difficulty finalDiff = diff;

        plugin.getServer().getScheduler().runTaskAsynchronously(plugin, () -> {
            List<DatabaseManager.LeaderboardEntry> entries =
                    plugin.getDatabaseManager().getTopRecords(finalDiff, limit);

            plugin.getServer().getScheduler().runTask(plugin, () -> {
                sender.sendMessage(msg.toComponent(msg.format("leaderboard.header",
                        "{count}", String.valueOf(limit),
                        "{difficulty}", finalDiff.getDisplayName())));

                if (entries.isEmpty()) {
                    msg.send(sender, "leaderboard.empty");
                    return;
                }
                for (DatabaseManager.LeaderboardEntry e : entries) {
                    sender.sendMessage(msg.toComponent(msg.format("leaderboard.entry",
                            "{rank}", String.valueOf(e.rank()),
                            "{player}", e.username(),
                            "{time}", e.timeDisplay())));
                }
            });
        });
    }

    private void handleAdmin(CommandSender sender, String[] args) {
        if (!sender.hasPermission("grise.admin")) {
            msg.send(sender, "general.no-permission");
            return;
        }

        if (args.length < 2) {
            sendAdminHelp(sender);
            return;
        }

        switch (args[1].toLowerCase()) {
            case "create"     -> adminCreate(sender, args);
            case "delete"     -> adminDelete(sender, args);
            case "setspawn"   -> adminSetSpawn(sender, args);
            case "setgoal"    -> adminSetGoal(sender, args);
            case "setradius"  -> adminSetRadius(sender, args);
            case "setentity"  -> adminSetEntity(sender, args);
            case "setglow"    -> adminSetGlow(sender, args);
            case "removeglow" -> adminRemoveGlow(sender, args);
            case "list"       -> adminList(sender);
            case "reload"     -> adminReload(sender);
            default           -> sendAdminHelp(sender);
        }
    }

    // -----------------------------------------------------------------------
    // Admin subcommands
    // -----------------------------------------------------------------------

    private void adminCreate(CommandSender sender, String[] args) {
        if (!(sender instanceof Player player)) { msg.send(sender, "general.player-only"); return; }
        if (args.length < 3) { msg.send(sender, "general.unknown-command"); return; }

        String name = args[2];
        Arena created = plugin.getArenaManager().createArena(name, player.getLocation());
        if (created == null) {
            msg.send(sender, "arena.already-exists", "{arena}", name);
        } else {
            msg.send(sender, "arena.created", "{arena}", name);
        }
    }

    private void adminDelete(CommandSender sender, String[] args) {
        if (args.length < 3) { msg.send(sender, "general.unknown-command"); return; }
        String name = args[2];
        boolean deleted = plugin.getArenaManager().deleteArena(name);
        if (deleted) {
            msg.send(sender, "arena.deleted", "{arena}", name);
        } else {
            msg.send(sender, "arena.not-found", "{arena}", name);
        }
    }

    private void adminSetSpawn(CommandSender sender, String[] args) {
        if (!(sender instanceof Player player)) { msg.send(sender, "general.player-only"); return; }
        if (args.length < 3) { msg.send(sender, "general.unknown-command"); return; }

        String name  = args[2];
        Arena  arena = plugin.getArenaManager().getArena(name);
        if (arena == null) { msg.send(sender, "arena.not-found", "{arena}", name); return; }

        arena.setSpawnLocation(player.getLocation());
        plugin.getArenaManager().saveArenas();
        msg.send(sender, "arena.set-spawn", "{arena}", name);
    }

    private void adminSetGoal(CommandSender sender, String[] args) {
        if (args.length < 4) { msg.send(sender, "general.unknown-command"); return; }

        String name = args[2];
        Arena arena = plugin.getArenaManager().getArena(name);
        if (arena == null) { msg.send(sender, "arena.not-found", "{arena}", name); return; }

        int y;
        try { y = Integer.parseInt(args[3]); }
        catch (NumberFormatException e) {
            msg.send(sender, "general.invalid-number", "{value}", args[3]); return;
        }

        arena.setGoalY(y);
        plugin.getArenaManager().saveArenas();
        msg.send(sender, "arena.set-goal-y", "{arena}", name, "{y}", String.valueOf(y));

        // Show a particle preview so the admin can see where the goal was set.
        if (sender instanceof Player player) {
            ArenaVisualizer.showPreview(player, arena, plugin);
            msg.send(sender, "arena.preview-started");
        }
    }

    private void adminSetRadius(CommandSender sender, String[] args) {
        if (args.length < 4) { msg.send(sender, "general.unknown-command"); return; }

        String name = args[2];
        Arena arena = plugin.getArenaManager().getArena(name);
        if (arena == null) { msg.send(sender, "arena.not-found", "{arena}", name); return; }

        int radius;
        try { radius = Integer.parseInt(args[3]); }
        catch (NumberFormatException e) {
            msg.send(sender, "general.invalid-number", "{value}", args[3]); return;
        }

        arena.setRadius(radius);
        plugin.getArenaManager().saveArenas();
        msg.send(sender, "arena.set-radius", "{arena}", name, "{radius}", String.valueOf(radius));

        // Show a particle preview so the admin can see the new boundary.
        if (sender instanceof Player player) {
            ArenaVisualizer.showPreview(player, arena, plugin);
            msg.send(sender, "arena.preview-started");
        }
    }

    private void adminSetEntity(CommandSender sender, String[] args) {
        if (args.length < 4) { msg.send(sender, "general.unknown-command"); return; }

        String name = args[2];
        Arena  arena = plugin.getArenaManager().getArena(name);
        if (arena == null) { msg.send(sender, "arena.not-found", "{arena}", name); return; }

        String typeName = args[3].toUpperCase()
                .replace("MINECRAFT:", "")   // strip namespace prefix if present
                .replace("-", "_");

        org.bukkit.entity.EntityType type;
        try {
            type = org.bukkit.entity.EntityType.valueOf(typeName);
        } catch (IllegalArgumentException e) {
            msg.send(sender, "arena.invalid-entity", "{entity}", args[3]);
            return;
        }

        arena.setEntityType(type);
        plugin.getArenaManager().saveArenas();
        msg.send(sender, "arena.set-entity", "{arena}", name, "{entity}", type.name());
    }

    private void adminSetGlow(CommandSender sender, String[] args) {
        // Usage: /gr admin setglow <arena> <r> <g> <b>
        if (args.length < 6) { msg.send(sender, "general.unknown-command"); return; }

        String name = args[2];
        Arena  arena = plugin.getArenaManager().getArena(name);
        if (arena == null) { msg.send(sender, "arena.not-found", "{arena}", name); return; }

        int r, g, b;
        try {
            r = Integer.parseInt(args[3]);
            g = Integer.parseInt(args[4]);
            b = Integer.parseInt(args[5]);
        } catch (NumberFormatException e) {
            msg.send(sender, "arena.invalid-glow-color");
            return;
        }

        // Clamp to 0-255.
        r = Math.max(0, Math.min(255, r));
        g = Math.max(0, Math.min(255, g));
        b = Math.max(0, Math.min(255, b));

        arena.setGlowColor(org.bukkit.Color.fromRGB(r, g, b));
        plugin.getArenaManager().saveArenas();
        msg.send(sender, "arena.set-glow",
                "{arena}", name,
                "{r}", String.valueOf(r),
                "{g}", String.valueOf(g),
                "{b}", String.valueOf(b));
    }

    private void adminRemoveGlow(CommandSender sender, String[] args) {
        if (args.length < 3) { msg.send(sender, "general.unknown-command"); return; }

        String name = args[2];
        Arena  arena = plugin.getArenaManager().getArena(name);
        if (arena == null) { msg.send(sender, "arena.not-found", "{arena}", name); return; }

        arena.setGlowColor(null);
        plugin.getArenaManager().saveArenas();
        msg.send(sender, "arena.removed-glow", "{arena}", name);
    }

    private void adminList(CommandSender sender) {
        msg.send(sender, "arena.list-header");
        var arenas = plugin.getArenaManager().getAllArenas();
        if (arenas.isEmpty()) {
            msg.send(sender, "arena.list-empty");
            return;
        }
        for (Arena a : arenas) {
            sender.sendMessage(msg.toComponent(msg.format("arena.list-entry",
                    "{arena}", a.getName(),
                    "{radius}", String.valueOf(a.getRadius()),
                    "{goal_y}", String.valueOf(a.getGoalY()))));
        }
    }

    private void adminReload(CommandSender sender) {
        plugin.reloadConfig();
        plugin.getMessageUtil().reload();
        msg.send(sender, "general.plugin-reloaded");
    }

    // -----------------------------------------------------------------------
    // Help
    // -----------------------------------------------------------------------

    private void sendHelp(CommandSender sender) {
        sender.sendMessage(msg.toComponent(msg.colorize("help.header")));
        msg.getList("help.entries").forEach(line ->
                sender.sendMessage(msg.toComponent(line)));
        sender.sendMessage(msg.toComponent(msg.colorize("help.footer")));
    }

    private void sendAdminHelp(CommandSender sender) {
        sender.sendMessage(msg.toComponent(msg.colorize("help.header")));
        msg.getList("help.admin-entries").forEach(line ->
                sender.sendMessage(msg.toComponent(line)));
        sender.sendMessage(msg.toComponent(msg.colorize("help.footer")));
    }

    // -----------------------------------------------------------------------
    // TabCompleter
    // -----------------------------------------------------------------------

    @Override
    public List<String> onTabComplete(CommandSender sender, Command command,
                                      String alias, String[] args) {

        List<String> completions = new ArrayList<>();

        if (args.length == 1) {
            completions.addAll(Arrays.asList("play", "race", "leave", "stats", "top"));
            if (sender.hasPermission("grise.admin")) completions.add("admin");
        } else if (args.length == 2) {
            switch (args[0].toLowerCase()) {
                case "play", "race" -> completions.addAll(plugin.getArenaManager().getArenaNames());
                case "top"          -> completions.addAll(Arrays.asList("easy", "medium", "hard"));
                case "stats"        -> plugin.getServer().getOnlinePlayers()
                                             .stream().map(Player::getName)
                                             .forEach(completions::add);
                case "admin"        -> completions.addAll(Arrays.asList(
                                           "create", "delete", "setspawn", "setgoal",
                                           "setradius", "setentity", "setglow", "removeglow",
                                           "list", "reload"));
            }
        } else if (args.length == 3) {
            switch (args[0].toLowerCase()) {
                case "play" -> completions.addAll(Arrays.asList("easy", "medium", "hard"));
                case "admin" -> {
                    String sub = args[1].toLowerCase();
                    if (!sub.equals("create") && !sub.equals("reload") && !sub.equals("list")) {
                        completions.addAll(plugin.getArenaManager().getArenaNames());
                    }
                }
            }
        } else if (args.length == 4 && args[0].equalsIgnoreCase("admin")
                   && args[1].equalsIgnoreCase("setentity")) {
            // Suggest a curated list of popular entity types.
            completions.addAll(Arrays.asList(
                "WARDEN", "SLIME", "MAGMA_CUBE", "IRON_GOLEM", "HOGLIN",
                "RAVAGER", "ELDER_GUARDIAN", "GHAST", "PHANTOM", "CHICKEN",
                "COW", "PIG", "ZOMBIE", "SKELETON", "CREEPER", "ENDERMAN"
            ));
        } else if (args.length == 4 && args[0].equalsIgnoreCase("admin")
                   && args[1].equalsIgnoreCase("setglow")) {
            // R value hint
            completions.addAll(Arrays.asList("0", "85", "170", "255"));
        } else if (args.length == 5 && args[0].equalsIgnoreCase("admin")
                   && args[1].equalsIgnoreCase("setglow")) {
            // G value hint
            completions.addAll(Arrays.asList("0", "85", "170", "255"));
        } else if (args.length == 6 && args[0].equalsIgnoreCase("admin")
                   && args[1].equalsIgnoreCase("setglow")) {
            // B value hint
            completions.addAll(Arrays.asList("0", "85", "170", "255"));
        }

        // Filter by what has been typed so far.
        String typed = args[args.length - 1].toLowerCase();
        return completions.stream()
                          .filter(c -> c.toLowerCase().startsWith(typed))
                          .collect(Collectors.toList());
    }
}
