package com.gexel.grise.listeners;

import com.gexel.grise.GRisePlugin;
import com.gexel.grise.arena.Arena;
import com.gexel.grise.arena.GameSession;
import com.gexel.grise.arena.SessionState;
import org.bukkit.GameMode;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.Action;
import org.bukkit.event.entity.EntityDamageEvent;
import org.bukkit.event.entity.FoodLevelChangeEvent;
import org.bukkit.event.player.PlayerDropItemEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerMoveEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.persistence.PersistentDataType;

/**
 * Handles player-state events while inside a G-Rise session.
 *
 * @author Gexel
 */
public class PlayerListener implements Listener {

    private final GRisePlugin plugin;

    public PlayerListener(GRisePlugin plugin) {
        this.plugin = plugin;
    }

    // -----------------------------------------------------------------------
    // Admin join — update notification
    // -----------------------------------------------------------------------

    @EventHandler(priority = EventPriority.MONITOR)
    public void onPlayerJoin(PlayerJoinEvent event) {
        Player player = event.getPlayer();
        if (!player.hasPermission("grise.admin")) return;

        plugin.getServer().getScheduler().runTaskLater(plugin, () -> {
            if (!player.isOnline()) return;
            if (plugin.getUpdateChecker() != null) {
                plugin.getUpdateChecker().notifyPlayer(player);
            }
        }, 60L);
    }

    // -----------------------------------------------------------------------
    // Quick-leave Redstone item
    // -----------------------------------------------------------------------

    /**
     * Detects when a player right-clicks with the G-Rise leave item (Redstone
     * in slot 8) and immediately runs /gr leave for them.
     */
    @EventHandler(priority = EventPriority.HIGH)
    public void onPlayerInteract(PlayerInteractEvent event) {
        // Only care about right-click actions (both air and block).
        if (event.getAction() != Action.RIGHT_CLICK_AIR
                && event.getAction() != Action.RIGHT_CLICK_BLOCK) return;

        // Only fire for the main hand to avoid double-triggering.
        if (event.getHand() != EquipmentSlot.HAND) return;

        Player player = event.getPlayer();

        // Must be in an active G-Rise session.
        if (!plugin.getArenaManager().isPlaying(player)) return;

        // Must be holding the leave item (identified by PDC tag).
        var item = event.getItem();
        if (item == null || item.getType() != Material.REDSTONE) return;

        var meta = item.getItemMeta();
        if (meta == null) return;

        var pdc = meta.getPersistentDataContainer();
        var key = new NamespacedKey(plugin, "grise_leave");
        if (!pdc.has(key, PersistentDataType.BYTE)) return;

        // Cancel the interact event to prevent block placement etc.
        event.setCancelled(true);

        // Execute leave.
        plugin.getArenaManager().leaveGame(player);
    }

    // -----------------------------------------------------------------------
    // Floor / fall detection + goal check
    // -----------------------------------------------------------------------

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onPlayerMove(PlayerMoveEvent event) {
        if (event.getFrom().getBlockY() == event.getTo().getBlockY()) return;

        Player player = event.getPlayer();
        GameSession session = plugin.getArenaManager().getSessionOf(player);
        if (session == null || session.getState() != SessionState.ACTIVE) return;

        Arena arena = session.getArena();
        double toY = event.getTo().getY();

        // Goal check — authoritative win trigger.
        if (arena.hasReachedGoal(toY)) {
            session.onPlayerFinish(player);
            return;
        }

        // Floor / fall check.
        double floorY = arena.getSpawnLocation().getY() - 0.5;
        if (toY <= floorY) {
            session.onPlayerFell(player);
        }
    }

    // -----------------------------------------------------------------------
    // Disconnect safety
    // -----------------------------------------------------------------------

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

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onPlayerDamage(EntityDamageEvent event) {
        if (!(event.getEntity() instanceof Player player)) return;
        GameSession session = plugin.getArenaManager().getSessionOf(player);
        if (session == null || session.getState() != SessionState.ACTIVE) return;
        event.setCancelled(true);
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onFoodChange(FoodLevelChangeEvent event) {
        if (!(event.getEntity() instanceof Player player)) return;
        if (plugin.getArenaManager().isPlaying(player)) event.setCancelled(true);
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onItemDrop(PlayerDropItemEvent event) {
        Player player = event.getPlayer();
        if (plugin.getArenaManager().isPlaying(player)
                && player.getGameMode() != GameMode.CREATIVE) {
            event.setCancelled(true);
        }
    }
}
