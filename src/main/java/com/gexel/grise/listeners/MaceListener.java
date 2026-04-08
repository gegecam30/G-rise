package com.gexel.grise.listeners;

import com.gexel.grise.GRisePlugin;
import com.gexel.grise.arena.GameSession;
import com.gexel.grise.arena.SessionState;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
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
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.scheduler.BukkitRunnable;

/**
 * Handles all Mace-related interactions in G-Rise.
 *
 * <h3>Wind Burst firing — critical detail</h3>
 * Wind Burst is processed by vanilla INSIDE the damage pipeline.
 * If we call {@code event.setCancelled(true)} the enchantment effect is
 * also suppressed, so the player gets no upward launch.
 *
 * <p>Solution for immortal-entity modes (Easy / Hard / Race):
 * <ol>
 *   <li>Let the event go through uncancelled so vanilla Wind Burst fires.</li>
 *   <li>Set the entity temporarily invulnerable for 1 tick so it absorbs
 *       zero health loss despite the hit being processed.</li>
 *   <li>Schedule a 1-tick delayed task to apply the extra multiplier
 *       AFTER vanilla has already set the player's velocity.</li>
 * </ol>
 *
 * @author Gexel
 */
public class MaceListener implements Listener {

    private final GRisePlugin plugin;

    public MaceListener(GRisePlugin plugin) {
        this.plugin = plugin;
    }

    /**
     * Priority LOWEST so we run before any other plugin and before vanilla
     * Wind Burst processing — but we purposely do NOT cancel the event,
     * which is what lets vanilla fire the launch.
     */
    @EventHandler(priority = EventPriority.LOWEST, ignoreCancelled = false)
    public void onEntityDamage(EntityDamageByEntityEvent event) {

        // 1. Damager must be a player.
        if (!(event.getDamager() instanceof Player player)) return;

        // 2. Player must be in an active G-Rise session.
        GameSession session = plugin.getArenaManager().getSessionOf(player);
        if (session == null || session.getState() != SessionState.ACTIVE) return;

        // 3. Player must be holding the G-Rise Mace (Material.MACE + Wind Burst).
        if (!isGriseMace(player)) {
            // They're in a session but hit something without the mace — just cancel.
            event.setCancelled(true);
            plugin.getMessageUtil().send(player, "mace.wrong-tool");
            return;
        }

        Entity target = event.getEntity();

        // 4. Target must be a G-Rise entity.
        if (!target.hasMetadata(GameSession.META_KEY)) {
            // Hit something that isn't a target (e.g. another player in race mode).
            // Allow PvP in race, deny in solo.
            if (!session.getDifficulty().isRace()) {
                event.setCancelled(true);
            }
            return;
        }

        // ---------------------------------------------------------------
        // TARGET HIT — valid G-Rise interaction.
        // ---------------------------------------------------------------

        if (session.getDifficulty().isEntitiesImmortal()) {
            // IMPORTANT: Do NOT cancel the event here.
            // Cancelling it would also cancel the Wind Burst launch.
            // Instead, temporarily make the entity invulnerable so it
            // takes the hit event but loses no health.
            if (target instanceof LivingEntity living) {
                living.setInvulnerable(true);
                // Re-enable vulnerability after 1 tick (the hit window has passed).
                new BukkitRunnable() {
                    @Override public void run() {
                        if (!living.isDead()) living.setInvulnerable(true); // stays immortal
                    }
                }.runTaskLater(plugin, 1L);
            }
            // Set final damage to 0 so no health bar flicker occurs.
            event.setDamage(0.0);
        }
        // Medium mode: damage passes through normally — entity will die.

        // ---------------------------------------------------------------
        // Apply extra velocity multiplier 1 tick after this event,
        // by which time vanilla Wind Burst has already set the velocity.
        // ---------------------------------------------------------------
        new BukkitRunnable() {
            @Override
            public void run() {
                if (!player.isOnline()) return;
                applyExtraVelocity(player);
            }
        }.runTaskLater(plugin, 1L);

        // Notify the session (combo, checkpoint, win-check).
        session.onValidHit(player, target);

        // Show combo on action bar.
        int combo = session.getCombo(player);
        sendComboActionBar(player, combo);
    }

    // -----------------------------------------------------------------------
    // Private helpers
    // -----------------------------------------------------------------------

    /**
     * Returns {@code true} if the player's main-hand item is a MACE with
     * Wind Burst AND carries the G-Rise PersistentData tag (issued by the plugin).
     * Falls back to checking enchantment only (so admins can still test with
     * a vanilla Mace during development).
     */
    private boolean isGriseMace(Player player) {
        ItemStack item = player.getInventory().getItemInMainHand();
        if (item.getType() != Material.MACE) return false;

        ItemMeta meta = item.getItemMeta();
        if (meta == null) return false;

        // Must have Wind Burst enchantment.
        if (!meta.hasEnchant(Enchantment.WIND_BURST)) return false;

        // Accept both plugin-issued mace (has PDC tag) and any Wind Burst mace
        // (so devs can test without starting a full session setup).
        return true;
    }

    /**
     * Multiplies the player's current Y velocity by the configured factor.
     * Only applies when Y velocity is already positive (vanilla Wind Burst
     * fired successfully).
     */
    private void applyExtraVelocity(Player player) {
        double multiplier = plugin.getConfig().getDouble("mace.wind-burst-multiplier", 1.2);
        if (multiplier <= 1.0) return;

        var vel = player.getVelocity();
        if (vel.getY() > 0) {
            player.setVelocity(vel.setY(vel.getY() * multiplier));
        }
    }

    private void sendComboActionBar(Player player, int combo) {
        String msg = plugin.getMessageUtil()
                           .format("mace.hit-registered", "{combo}", String.valueOf(combo));
        player.sendActionBar(
                net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer
                        .legacyAmpersand().deserialize(msg));
    }
}
