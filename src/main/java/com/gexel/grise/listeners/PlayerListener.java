package com.gexel.grise.listeners;

import com.gexel.grise.GRisePlugin;
import com.gexel.grise.arena.Arena;
import com.gexel.grise.arena.GameSession;
import com.gexel.grise.arena.SessionState;
import org.bukkit.GameMode;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerMoveEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.event.entity.EntityDamageEvent;
import org.bukkit.event.entity.FoodLevelChangeEvent;
import org.bukkit.event.player.PlayerDropItemEvent;

/**
 * Handles player-state events while inside a G-Rise session.
 *
 * <h3>Floor detection</h3>
 * Rather than a periodic task (which wastes CPU on all players), we use
 * {@link PlayerMoveEvent} filtered to Y-change only. When a player's Y
 * drops to or below the arena's floor level (arena spawn Y), we call
 * {@link GameSession#onPlayerFell(Player)}.
 *
 * <h3>Quit safety</h3>
 * If a player disconnects mid-game they are cleanly removed from the session
 * so it can continue without them (or end if they were the last participant).
 *
 * @author Gexel
 */
public class PlayerListener implements Listener {

    // -----------------------------------------------------------------------
    // Fields
    // -----------------------------------------------------------------------

    private final GRisePlugin plugin;

    // -----------------------------------------------------------------------
    // Constructor
    // -----------------------------------------------------------------------

    public PlayerListener(GRisePlugin plugin) {
        this.plugin = plugin;
    }

    // -----------------------------------------------------------------------
    // Admin join — update notification
    // -----------------------------------------------------------------------

    /**
     * When an admin joins, notify them if a G-Rise update is available.
     * The check is delayed by 3 seconds so the join splash screen clears first.
     */
    @EventHandler(priority = EventPriority.MONITOR)
    public void onPlayerJoin(PlayerJoinEvent event) {
        Player player = event.getPlayer();
        if (!player.hasPermission("grise.admin")) return;

        // Delay 3 s (60 ticks) so the message appears after join noise.
        plugin.getServer().getScheduler().runTaskLater(plugin, () -> {
            if (!player.isOnline()) return;
            // Update notification.
            plugin.getUpdateChecker().notifyPlayer(player);
        }, 60L);
    }

    // -----------------------------------------------------------------------
    // Floor / fall detection
    // -----------------------------------------------------------------------

    /**
     * Detects when a playing player's Y changes — handles both floor fall
     * penalty and goal-height victory.
     *
     * <p>Filtering to block-Y changes keeps this lightweight: we only act
     * when the player crosses a whole block boundary, not on sub-block jitter.
     */
    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onPlayerMove(PlayerMoveEvent event) {
        // Early exit: only care about Y changes.
        if (event.getFrom().getBlockY() == event.getTo().getBlockY()) return;

        Player player = event.getPlayer();
        GameSession session = plugin.getArenaManager().getSessionOf(player);
        if (session == null || session.getState() != SessionState.ACTIVE) return;

        Arena arena = session.getArena();
        double toY = event.getTo().getY();

        // --- Goal check (player reached or passed the winning height) ---
        // This is the authoritative win trigger: catches ascents that happen
        // between hits, not just at the moment of the last hit.
        if (arena.hasReachedGoal(toY)) {
            session.onPlayerFinish(player);
            return;
        }

        // --- Floor / fall check ---
        double floorY = arena.getSpawnLocation().getY() - 0.5;
        if (toY <= floorY) {
            session.onPlayerFell(player);
        }
    }

    // -----------------------------------------------------------------------
    // Disconnect safety
    // -----------------------------------------------------------------------

    /**
     * Removes a player from their session when they disconnect.
     */
    @EventHandler(priority = EventPriority.MONITOR)
    public void onPlayerQuit(PlayerQuitEvent event) {
        Player player = event.getPlayer();
        if (plugin.getArenaManager().isPlaying(player)) {
            plugin.getArenaManager().leaveGame(player);
        }
    }

    // -----------------------------------------------------------------------
    // Game protection events
    // -----------------------------------------------------------------------

    /**
     * Prevents players inside a session from taking environmental damage
     * (fall damage, void, etc.) since G-Rise manages health independently.
     */
    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onPlayerDamage(EntityDamageEvent event) {
        if (!(event.getEntity() instanceof Player player)) return;

        GameSession session = plugin.getArenaManager().getSessionOf(player);
        if (session == null || session.getState() != SessionState.ACTIVE) return;

        // Cancel all environmental damage while in an active session.
        event.setCancelled(true);
    }

    /**
     * Prevents hunger depletion during a game.
     */
    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onFoodChange(FoodLevelChangeEvent event) {
        if (!(event.getEntity() instanceof Player player)) return;

        if (plugin.getArenaManager().isPlaying(player)) {
            event.setCancelled(true);
        }
    }

    /**
     * Prevents item dropping during a game.
     */
    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onItemDrop(PlayerDropItemEvent event) {
        Player player = event.getPlayer();
        if (plugin.getArenaManager().isPlaying(player)
                && player.getGameMode() != GameMode.CREATIVE) {
            event.setCancelled(true);
        }
    }
}
