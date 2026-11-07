package com.gexel.grise.listeners;

import com.gexel.grise.GRisePlugin;
import com.gexel.grise.arena.GameSession;
import com.gexel.grise.arena.SessionState;
import org.bukkit.Material;
import org.bukkit.enchantments.Enchantment;
import org.bukkit.entity.Entity;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.scheduler.BukkitRunnable;
import org.bukkit.util.Vector;

/**
 * Handles all Mace-related interactions in G-Rise.
 *
 * <h3>Wind Burst in Survival — root cause fix</h3>
 * Vanilla Wind Burst only fires when {@code player.fallDistance > 0}.
 * Players standing on the floor have fallDistance = 0, so vanilla never
 * launches them. The solution: we apply the upward impulse OURSELVES using
 * Paper's velocity API, completely bypassing vanilla Wind Burst.
 *
 * <p>This makes the mechanic work identically in Survival, Adventure and
 * Creative, and also lets us tune the power precisely via config.
 *
 * @author Gexel
 */
public class MaceListener implements Listener {

    private final GRisePlugin plugin;

    public MaceListener(GRisePlugin plugin) {
        this.plugin = plugin;
    }

    /**
     * Priority LOWEST — run first, before other plugins.
     *
     * <h3>Invulnerability fix</h3>
     * Entities in Easy/Hard are permanently invulnerable (set in JumpGenerator).
     * Paper skips firing EntityDamageByEntityEvent entirely for invulnerable
     * entities, so our handler would never trigger. Fix: we disable invulnerability
     * RIGHT HERE before the damage resolves, then restore it 1 tick later.
     * This is safe because we also zero the damage to 0.0.
     */
    @EventHandler(priority = EventPriority.LOWEST, ignoreCancelled = false)
    public void onEntityDamage(EntityDamageByEntityEvent event) {

        // 1. Damager must be a player.
        if (!(event.getDamager() instanceof Player player)) return;

        // 2. Player must be in an active G-Rise session.
        GameSession session = plugin.getArenaManager().getSessionOf(player);
        if (session == null || session.getState() != SessionState.ACTIVE) return;

        // 3. Player must hold the G-Rise Mace with Wind Burst.
        if (!isGriseMace(player)) {
            event.setCancelled(true);
            plugin.getMessageUtil().send(player, "mace.wrong-tool");
            return;
        }

        Entity target = event.getEntity();

        // 4. Target must be a G-Rise entity.
        if (!target.hasMetadata(GameSession.META_KEY)) {
            if (!session.getDifficulty().isRace()) event.setCancelled(true);
            return;
        }

        // ---------------------------------------------------------------
        // VALID HIT — manage damage and launch ourselves.
        // ---------------------------------------------------------------

        // Zero out all vanilla damage. Entity HP is managed by GameSession
        // (medium: hits-per-entity map; easy/hard: 2000 HP cap in JumpGenerator).
        // Entities are NEVER set invulnerable so this event always fires.
        event.setDamage(0.0);

        // Apply our own upward impulse 1 tick later.
        new BukkitRunnable() {
            @Override
            public void run() {
                if (!player.isOnline()) return;
                applyWindBurstImpulse(player);
            }
        }.runTaskLater(plugin, 1L);

        // Notify session (combo, checkpoint, medium HP, chain mode, win check).
        session.onValidHit(player, target);

        // Action bar combo display.
        int combo = session.getCombo(player);
        sendComboActionBar(player, combo);
    }

    // -----------------------------------------------------------------------
    // Private helpers
    // -----------------------------------------------------------------------

    private boolean isGriseMace(Player player) {
        ItemStack item = player.getInventory().getItemInMainHand();
        if (item.getType() != Material.MACE) return false;
        ItemMeta meta = item.getItemMeta();
        if (meta == null) return false;
        return meta.hasEnchant(Enchantment.WIND_BURST);
    }

    /**
     * Applies a strong upward velocity to the player, replicating Wind Burst
     * but working regardless of fallDistance or game mode.
     *
     * <p>Base power: configurable via {@code mace.wind-burst-power} (default 1.6).
     * The multiplier config key is kept for backward compatibility.
     */
    private void applyWindBurstImpulse(Player player) {
        double power      = plugin.getConfig().getDouble("mace.wind-burst-power", 1.6);
        double multiplier = plugin.getConfig().getDouble("mace.wind-burst-multiplier", 1.2);
        double totalPower = power * multiplier;

        // Preserve horizontal momentum so the player can angle jumps.
        Vector current = player.getVelocity();
        Vector impulse = new Vector(
                current.getX() * 0.3,   // bleed off most horizontal speed
                totalPower,              // strong upward push
                current.getZ() * 0.3
        );
        player.setVelocity(impulse);
    }

    private void sendComboActionBar(Player player, int combo) {
        String msg = plugin.getMessageUtil()
                .format("mace.hit-registered", "{combo}", String.valueOf(combo));
        player.sendActionBar(
                net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer
                        .legacyAmpersand().deserialize(msg));
    }
}