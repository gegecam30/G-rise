package com.gexel.grise.listeners;

import com.gexel.grise.GRisePlugin;
import com.gexel.grise.arena.GameSession;
import com.gexel.grise.arena.SessionState;
import org.bukkit.GameMode;
import org.bukkit.Material;
import org.bukkit.entity.Entity;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.enchantments.Enchantment;
import org.bukkit.scheduler.BukkitRunnable;

public class MaceListener implements Listener {

    private final GRisePlugin plugin;

    public MaceListener(GRisePlugin plugin) {
        this.plugin = plugin;
    }

    @EventHandler(priority = EventPriority.LOWEST, ignoreCancelled = false)
    public void onEntityDamage(EntityDamageByEntityEvent event) {
        if (!(event.getDamager() instanceof Player player)) return;

        GameSession session = plugin.getArenaManager().getSessionOf(player);
        if (session == null || session.getState() != SessionState.ACTIVE) return;

        if (!isGriseMace(player)) {
            event.setCancelled(true);
            plugin.getMessageUtil().send(player, "mace.wrong-tool");
            return;
        }

        Entity target = event.getEntity();

        if (!target.hasMetadata(GameSession.META_KEY)) {
            if (!session.getDifficulty().isRace()) event.setCancelled(true);
            return;
        }

        // FIX SURVIVAL: Wind Burst requiere fallDistance > 0 en Survival/Adventure.
        if (player.getGameMode() == GameMode.SURVIVAL
                || player.getGameMode() == GameMode.ADVENTURE) {
            player.setFallDistance(1.5f);
        }

        if (session.getDifficulty().isEntitiesImmortal()) {
            if (target instanceof LivingEntity living) {
                living.setInvulnerable(true);
                new BukkitRunnable() {
                    @Override public void run() {
                        if (!living.isDead()) living.setInvulnerable(true);
                    }
                }.runTaskLater(plugin, 1L);
            }
            event.setDamage(0.0);
        }

        new BukkitRunnable() {
            @Override
            public void run() {
                if (!player.isOnline()) return;
                applyExtraVelocity(player);
            }
        }.runTaskLater(plugin, 1L);

        session.onValidHit(player, target);

        int combo = session.getCombo(player);
        sendComboActionBar(player, combo);
    }

    private boolean isGriseMace(Player player) {
        ItemStack item = player.getInventory().getItemInMainHand();
        if (item.getType() != Material.MACE) return false;
        ItemMeta meta = item.getItemMeta();
        if (meta == null) return false;
        return meta.hasEnchant(Enchantment.WIND_BURST);
    }

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