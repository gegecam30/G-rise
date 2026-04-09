package com.gexel.grise.arena;

import com.gexel.grise.GRisePlugin;
import com.gexel.grise.utils.JumpGenerator;
import com.gexel.grise.utils.MessageUtil;
import com.gexel.grise.utils.TimeUtil;
import net.kyori.adventure.title.Title;
import org.bukkit.*;
import org.bukkit.enchantments.Enchantment;
import org.bukkit.entity.*;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.metadata.FixedMetadataValue;
import org.bukkit.potion.PotionEffect;
import org.bukkit.potion.PotionEffectType;
import org.bukkit.scheduler.BukkitRunnable;
import org.bukkit.scheduler.BukkitTask;

import java.util.*;

/**
 * Represents a single active game session running inside an {@link Arena}.
 *
 * <p>Responsibilities:
 * <ul>
 *   <li>Countdown → start → game-loop → end lifecycle.</li>
 *   <li>Spawning and tracking target entities via {@link JumpGenerator}.</li>
 *   <li>Tracking per-player progress (combo, checkpoint, finish time).</li>
 *   <li>Cleaning up entities and player state when the session ends.</li>
 * </ul>
 *
 * <p><b>Thread safety:</b> all logic runs on the main server thread through
 * {@link BukkitRunnable}s – no raw threads are ever used.
 *
 * @author Gexel
 */
public class GameSession {

    // -----------------------------------------------------------------------
    // Constants
    // -----------------------------------------------------------------------

    /** Metadata key used to mark G-Rise target entities. */
    public static final String META_KEY = "GRiseTarget";

    // -----------------------------------------------------------------------
    // Fields – session identity
    // -----------------------------------------------------------------------

    private final GRisePlugin plugin;
    private final Arena       arena;
    private final Difficulty  difficulty;

    /** All players currently in this session. */
    private final List<Player> players = new ArrayList<>();

    /** State machine. */
    private SessionState state = SessionState.WAITING;

    // -----------------------------------------------------------------------
    // Fields – timing
    // -----------------------------------------------------------------------

    /** Server tick at which the "GO!" title was displayed. */
    private long startTick;

    /** Elapsed-time string for each player (only populated upon finish). */
    private final Map<UUID, String> finishTimes = new LinkedHashMap<>();

    // -----------------------------------------------------------------------
    // Fields – entities and progression
    // -----------------------------------------------------------------------

    /** All spawned target entities for this session. */
    private final List<Entity> spawnedEntities = new ArrayList<>();

    /**
     * Chain mode (Hard difficulty): entities are pre-generated but kept
     * invisible; only one is revealed at a time. After hitting it the next
     * one in the list becomes the active target.
     */
    private boolean chainMode  = false;
    private int     chainIndex = 0;

    /**
     * Last checkpoint location per player.
     * For solo runs this is simply the spawn; updated as the player progresses.
     */
    private final Map<UUID, Location> checkpoints = new HashMap<>();

    /** Current combo count per player. */
    private final Map<UUID, Integer> comboCounts = new HashMap<>();

    /**
     * Medium mode: remaining hit points per entity (entity UUID → hits left).
     * When a value reaches 0 the entity is removed.
     * Populated at game start from {@code difficulty.medium.hits-per-entity}.
     */
    private final Map<UUID, Integer> mediumEntityHits = new HashMap<>();

    /**
     * Tracks which combo milestones have already been rewarded this session
     * per player, so the same threshold is never triggered twice.
     * Key = player UUID, Value = highest milestone already fired.
     */
    private final Map<UUID, Integer> lastMilestone = new HashMap<>();

    /**
     * Saved inventories – contents are stored when the game starts and
     * restored when the player leaves or the session ends.
     */
    private final Map<UUID, ItemStack[]> savedInventories = new HashMap<>();

    // nuevo modo 08/04/26
    private final Map<UUID, org.bukkit.GameMode> savedGameModes = new HashMap<>();
    /**
     * Players who have already finished (prevent double-win from concurrent
     * PlayerMoveEvent + onValidHit triggers).
     */
    private final Set<UUID> finishedPlayers = new HashSet<>();

    /** The location of the topmost spawned entity. */
    private Location lastSpawnedLocation;

    // -----------------------------------------------------------------------
    // Fields – scheduled tasks
    // -----------------------------------------------------------------------

    private BukkitTask countdownTask;
    private BukkitTask hardModeTask;
    private BukkitTask fallWatchTask;

    /**
     * Last server tick at which each player landed a valid hit.
     * Used by the fall-watch system to decide if a player is stuck/falling.
     * Stored as Integer because {@code Server#getCurrentTick()} returns int.
     */
    private final Map<UUID, Integer> lastHitTick = new HashMap<>();

    // -----------------------------------------------------------------------
    // Constructor
    // -----------------------------------------------------------------------

    /**
     * @param plugin     plugin instance
     * @param arena      the arena this session runs in
     * @param difficulty difficulty for this session
     */
    public GameSession(GRisePlugin plugin, Arena arena, Difficulty difficulty) {
        this.plugin     = plugin;
        this.arena      = arena;
        this.difficulty = difficulty;
        this.lastSpawnedLocation = arena.getSpawnLocation();
    }

    // -----------------------------------------------------------------------
    // Player management
    // -----------------------------------------------------------------------

    /**
     * Adds a player to the session (must be in {@link SessionState#WAITING}).
     *
     * @param player player to add
     * @return {@code true} if added successfully
     */
    public boolean addPlayer(Player player) {
        if (state != SessionState.WAITING) return false;

        int maxPlayers = plugin.getConfig().getInt("game.max-players-per-arena", 8);
        if (players.size() >= maxPlayers) return false;

        players.add(player);
        checkpoints.put(player.getUniqueId(), arena.getSpawnLocation());
        comboCounts.put(player.getUniqueId(), 0);
        lastHitTick.put(player.getUniqueId(), plugin.getServer().getCurrentTick());
        lastMilestone.put(player.getUniqueId(), 0);

        MessageUtil msg = plugin.getMessageUtil();
        String joined = msg.format("game.player-joined",
                "{player}", player.getName(),
                "{current}", String.valueOf(players.size()),
                "{max}", String.valueOf(maxPlayers));

        broadcastToSession(joined);
        return true;
    }

    /**
     * Removes a player from the session and restores their state.
     *
     * @param player player to remove
     */
    public void removePlayer(Player player) {
        players.remove(player);
        checkpoints.remove(player.getUniqueId());
        comboCounts.remove(player.getUniqueId());
        lastHitTick.remove(player.getUniqueId());
        lastMilestone.remove(player.getUniqueId());
        restorePlayerState(player);

        // Tell the leaving player they left, and broadcast to others remaining.
        plugin.getMessageUtil().send(player, "game.player-left-self");
        if (!players.isEmpty()) {
            broadcastToSession(plugin.getMessageUtil().format("game.player-left",
                    "{player}", player.getName()));
        }

        // If no players remain during an active game, end the session.
        if (players.isEmpty() && state == SessionState.ACTIVE) {
            endSession(null);
        }
    }

    // -----------------------------------------------------------------------
    // Lifecycle
    // -----------------------------------------------------------------------

    /**
     * Begins the countdown sequence. Must be called from the main thread.
     */
    public void startCountdown() {
        if (state != SessionState.WAITING) return;
        state = SessionState.COUNTDOWN;

        int countdownSeconds = plugin.getConfig().getInt("game.countdown-seconds", 3);

        countdownTask = new BukkitRunnable() {
            int remaining = countdownSeconds;

            @Override
            public void run() {
                if (players.isEmpty()) {
                    cancel();
                    endSession(null);
                    return;
                }

                if (remaining > 0) {
                    // Show countdown title to every player in the session.
                    String title    = plugin.getMessageUtil().colorize("game.countdown-title")
                                            .replace("{seconds}", String.valueOf(remaining));
                    String subtitle = plugin.getMessageUtil().colorize("game.countdown-subtitle");

                    for (Player p : players) {
                        p.showTitle(Title.title(
                                net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer
                                        .legacyAmpersand().deserialize(title),
                                net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer
                                        .legacyAmpersand().deserialize(subtitle),
                                Title.Times.times(
                                        java.time.Duration.ofMillis(100),
                                        java.time.Duration.ofMillis(900),
                                        java.time.Duration.ofMillis(100))
                        ));
                        p.playSound(p.getLocation(), Sound.BLOCK_NOTE_BLOCK_PLING, 1f,
                                0.8f + (countdownSeconds - remaining) * 0.1f);
                    }
                    remaining--;
                } else {
                    // GO!
                    cancel();
                    launchGame();
                }
            }
        }.runTaskTimer(plugin, 0L, 20L); // fire every 20 ticks (1 second)
    }

    /**
     * Called after countdown finishes. Spawns entities and launches players.
     */
    private void launchGame() {
        state     = SessionState.ACTIVE;
        startTick = plugin.getServer().getCurrentTick();

        // Teleport all players to the arena spawn.
        for (Player p : players) {
            p.teleport(arena.getSpawnLocation());
            preparePlayer(p);
        }

        // Spawn all target entities distributed uniformly across the full column.
        JumpGenerator generator = new JumpGenerator(plugin, arena, difficulty);
        int count = computeInitialEntityCount();
        Entity[] spawned = generator.spawnAll(count);
        for (Entity e : spawned) {
            if (e != null) spawnedEntities.add(e);
        }

        // Detect chain mode: Hard difficulty + config flag.
        chainMode = difficulty.isMemoryMode()
                && plugin.getConfig().getBoolean("difficulty.hard.chain-mode", false);

        // Medium mode: assign hit-points to every entity.
        if (difficulty == Difficulty.MEDIUM) {
            int hitsPerEntity = plugin.getConfig().getInt("difficulty.medium.hits-per-entity", 5);
            for (Entity e : spawnedEntities) {
                mediumEntityHits.put(e.getUniqueId(), hitsPerEntity);
            }
        }

        // Display GO title.
        String goTitle    = plugin.getMessageUtil().colorize("game.go-title");
        String goSubtitle = plugin.getMessageUtil().colorize("game.go-subtitle")
                                  .replace("{goal_y}", String.valueOf(arena.getGoalY()));

        for (Player p : players) {
            p.showTitle(Title.title(
                    net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer
                            .legacyAmpersand().deserialize(goTitle),
                    net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer
                            .legacyAmpersand().deserialize(goSubtitle),
                    Title.Times.times(
                            java.time.Duration.ofMillis(50),
                            java.time.Duration.ofMillis(1500),
                            java.time.Duration.ofMillis(500))
            ));

            // Apply start boost (velocity + levitation).
            applyStartBoost(p);
        }

        // If HARD mode, start the appropriate visibility system.
        if (difficulty.isMemoryMode()) {
            if (chainMode) {
                startChainMode();
            } else {
                scheduleHardModeBlinking();
            }
        }

        // Start the fall-watch safety net for all players.
        scheduleFallWatch();

        // Broadcast game-started message.
        broadcastToSession(plugin.getMessageUtil().format("game.started",
                "{goal_y}", String.valueOf(arena.getGoalY())));
    }

    /**
     * Ends the session, optionally declaring a winner.
     *
     * @param winner the winning player, or {@code null} for a cancelled session
     */
    public void endSession(Player winner) {
        if (state == SessionState.ENDED) return;
        state = SessionState.ENDED;

        // Cancel all scheduled tasks.
        if (countdownTask != null) countdownTask.cancel();
        if (hardModeTask  != null) hardModeTask.cancel();
        if (fallWatchTask != null) fallWatchTask.cancel();

        // Announce winner.
        if (winner != null) {
            String time = finishTimes.getOrDefault(winner.getUniqueId(), "?");

            if (difficulty.isRace()) {
                String broadcast = plugin.getMessageUtil().format("game.race-win-broadcast",
                        "{player}", winner.getName(),
                        "{arena}", arena.getName(),
                        "{time}", time);
                plugin.getServer().broadcast(
                        net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer
                                .legacyAmpersand().deserialize(broadcast));
            } else {
                String broadcast = plugin.getMessageUtil().format("game.win-broadcast",
                        "{player}", winner.getName(),
                        "{arena}", arena.getName(),
                        "{time}", time,
                        "{difficulty}", difficulty.getDisplayName());
                plugin.getServer().broadcast(
                        net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer
                                .legacyAmpersand().deserialize(broadcast));
            }

            // Win title to all remaining players.
            for (Player p : players) {
                boolean isWinner = p.equals(winner);
                p.showTitle(Title.title(
                        net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer
                                .legacyAmpersand().deserialize(
                                        isWinner
                                        ? plugin.getMessageUtil().colorize("game.win-title")
                                        : "&cYou lost!"),
                        net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer
                                .legacyAmpersand().deserialize(
                                        isWinner
                                        ? plugin.getMessageUtil().colorize("game.win-subtitle")
                                                  .replace("{time}", time)
                                        : "&7Better luck next time!"),
                        Title.Times.times(
                                java.time.Duration.ofMillis(100),
                                java.time.Duration.ofMillis(3000),
                                java.time.Duration.ofMillis(500))
                ));
                p.playSound(p.getLocation(), Sound.UI_TOAST_CHALLENGE_COMPLETE, 1f, 1f);
            }

            // Persist time to database.
            String finalTime = time;
            Player finalWinner = winner;
            plugin.getServer().getScheduler().runTaskAsynchronously(plugin, () ->
                    plugin.getDatabaseManager().saveRecord(
                            finalWinner.getUniqueId(),
                            finalWinner.getName(),
                            arena.getName(),
                            difficulty,
                            finalTime));
        }

        // Remove entities and restore players.
        despawnEntities();
        List<Player> copy = new ArrayList<>(players);
        copy.forEach(this::restorePlayerState);

        // Unregister session from the manager BEFORE clearing the list,
        // so removeSession() can still read getPlayers() to purge playerSessions.
        plugin.getArenaManager().removeSession(arena.getName());

        players.clear();
    }

    // -----------------------------------------------------------------------
    // Player state helpers
    // -----------------------------------------------------------------------

    /**
     * Prepares a player for gameplay:
     * saves their inventory, clears it, gives them the G-Rise Mace.
     */
    private void preparePlayer(Player player) {
        // Guardar gamemode y forzar Survival para que Wind Burst funcione
        savedGameModes.put(player.getUniqueId(), player.getGameMode());
        player.setGameMode(GameMode.SURVIVAL);

        player.setAllowFlight(false);
        player.setFlying(false);
        player.setHealth(player.getMaxHealth());
        player.setFoodLevel(20);
        player.setSaturation(20f);
        player.getActivePotionEffects().forEach(e -> player.removePotionEffect(e.getType()));

        ItemStack[] saved = new ItemStack[player.getInventory().getSize() + 5];
        ItemStack[] contents = player.getInventory().getContents();
        System.arraycopy(contents, 0, saved, 0, contents.length);
        saved[contents.length]     = player.getInventory().getHelmet();
        saved[contents.length + 1] = player.getInventory().getChestplate();
        saved[contents.length + 2] = player.getInventory().getLeggings();
        saved[contents.length + 3] = player.getInventory().getBoots();
        saved[contents.length + 4] = player.getInventory().getItemInOffHand();
        savedInventories.put(player.getUniqueId(), saved);

        player.getInventory().clear();
        player.getInventory().setHelmet(null);
        player.getInventory().setChestplate(null);
        player.getInventory().setLeggings(null);
        player.getInventory().setBoots(null);
        player.getInventory().setItemInOffHand(null);

        player.getInventory().setItemInMainHand(buildGriseMace());
        player.getInventory().setHeldItemSlot(0);
    }
    /**
     * Builds the G-Rise Mace: a MACE with Wind Burst I and a custom display name.
     * The item is unbreakable so it survives the session.
     */
    private ItemStack buildGriseMace() {
        ItemStack mace = new ItemStack(Material.MACE);
        ItemMeta meta  = mace.getItemMeta();
        if (meta != null) {
            meta.displayName(
                    net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer
                            .legacyAmpersand().deserialize("&b✦ G-Rise Mace"));
            // Wind Burst I — the key enchantment for the upward launch.
            meta.addEnchant(Enchantment.WIND_BURST, 1, true);
            meta.setUnbreakable(true);
            // Tag so we can identify it as the plugin-issued item.
            meta.getPersistentDataContainer().set(
                    new org.bukkit.NamespacedKey(plugin, "grise_mace"),
                    org.bukkit.persistence.PersistentDataType.BYTE,
                    (byte) 1);
            mace.setItemMeta(meta);
        }
        return mace;
    }

    /**
     * Applies the velocity + levitation boost at game start.
     */
    private void applyStartBoost(Player player) {
        boolean enabled = plugin.getConfig().getBoolean("game.start-boost.enabled", true);
        if (!enabled) return;

        double vy       = plugin.getConfig().getDouble("game.start-boost.velocity-y", 0.8);
        int levTicks    = plugin.getConfig().getInt("game.start-boost.levitation-ticks", 40);
        int levAmp      = plugin.getConfig().getInt("game.start-boost.levitation-amplifier", 1);

        player.setVelocity(player.getVelocity().setY(vy));
        player.addPotionEffect(new PotionEffect(PotionEffectType.LEVITATION, levTicks, levAmp, false, false, false));
    }

    /**
     * Restores the player to their pre-game state:
     * removes effects, zeroes velocity, restores inventory, teleports to world spawn.
     */
    private void restorePlayerState(Player player) {
        player.getActivePotionEffects().forEach(e -> player.removePotionEffect(e.getType()));
        player.setVelocity(new org.bukkit.util.Vector(0, 0, 0));

        // Restaurar gamemode original
        GameMode savedMode = savedGameModes.remove(player.getUniqueId());
        if (savedMode != null) player.setGameMode(savedMode);

        ItemStack[] saved = savedInventories.remove(player.getUniqueId());
        player.getInventory().clear();
        player.getInventory().setHelmet(null);
        player.getInventory().setChestplate(null);
        player.getInventory().setLeggings(null);
        player.getInventory().setBoots(null);
        player.getInventory().setItemInOffHand(null);

        if (saved != null) {
            int contentsLen = player.getInventory().getSize();
            player.getInventory().setContents(Arrays.copyOfRange(saved, 0, contentsLen));
            if (saved.length > contentsLen)     player.getInventory().setHelmet(saved[contentsLen]);
            if (saved.length > contentsLen + 1) player.getInventory().setChestplate(saved[contentsLen + 1]);
            if (saved.length > contentsLen + 2) player.getInventory().setLeggings(saved[contentsLen + 2]);
            if (saved.length > contentsLen + 3) player.getInventory().setBoots(saved[contentsLen + 3]);
            if (saved.length > contentsLen + 4) player.getInventory().setItemInOffHand(saved[contentsLen + 4]);
        }

        player.teleport(player.getWorld().getSpawnLocation());
    }

    // -----------------------------------------------------------------------
    // Entity management
    // -----------------------------------------------------------------------

    private int computeInitialEntityCount() {
        int height  = arena.getGoalY() - (int) arena.getSpawnLocation().getY();
        int minY    = plugin.getConfig().getInt("spawning.generation.min-y-offset", 3);
        int maxCap  = plugin.getConfig().getInt("spawning.max-initial-entities", 12);
        // Derive from column height but never exceed the configured cap.
        int derived = Math.max(5, height / (minY + 2));
        return Math.min(derived, maxCap);
    }

    /** Removes all spawned entities from the world. */
    private void despawnEntities() {
        for (Entity entity : spawnedEntities) {
            if (!entity.isDead()) entity.remove();
        }
        spawnedEntities.clear();
    }

    /**
     * Hard mode — "wave flash" system.
     *
     * <p>Instead of revealing one entity at a time slowly, we reveal a small
     * cluster of nearby entities simultaneously every few seconds. Each cluster
     * stays visible for a short configurable window then disappears. This creates
     * a fast-paced memory challenge:
     * <ol>
     *   <li>All entities start invisible.</li>
     *   <li>Every {@code wave-interval} ticks a cluster of {@code wave-size}
     *       entities (closest to the player's current position) is made visible.</li>
     *   <li>After {@code entity-visible-ticks} ticks the cluster goes invisible
     *       again.</li>
     *   <li>A dispenser sound fires at each revealed entity's location.</li>
     * </ol>
     */
    private void scheduleHardModeBlinking() {
        int visibleTicks = plugin.getConfig().getInt("difficulty.hard.entity-visible-ticks", 40);
        int waveInterval = plugin.getConfig().getInt("difficulty.hard.wave-interval-ticks", 60);
        int waveSize     = plugin.getConfig().getInt("difficulty.hard.wave-size", 3);

        String soundName = plugin.getConfig().getString("difficulty.hard.spawn-sound", "BLOCK_DISPENSER_LAUNCH");
        float  volume    = (float) plugin.getConfig().getDouble("difficulty.hard.spawn-sound-volume", 1.0);
        float  pitch     = (float) plugin.getConfig().getDouble("difficulty.hard.spawn-sound-pitch", 1.4);

        Sound sound;
        try { sound = Sound.valueOf(soundName); }
        catch (IllegalArgumentException e) { sound = Sound.BLOCK_DISPENSER_LAUNCH; }
        final Sound finalSound = sound;

        // Start all invisible.
        for (Entity e : spawnedEntities) setEntityInvisible(e, true);

        hardModeTask = new BukkitRunnable() {
            @Override
            public void run() {
                if (state == SessionState.ENDED || spawnedEntities.isEmpty()) {
                    cancel();
                    return;
                }

                // Find the highest player Y to reveal entities near the action.
                double refY = players.stream()
                        .mapToDouble(p -> p.getLocation().getY())
                        .max().orElse(arena.getSpawnLocation().getY());

                // Sort all invisible entities by proximity to refY, pick top waveSize.
                List<Entity> wave = spawnedEntities.stream()
                        .filter(e -> !e.isDead())
                        .sorted(Comparator.comparingDouble(
                                e -> Math.abs(e.getLocation().getY() - refY)))
                        .limit(waveSize)
                        .toList();

                for (Entity entity : wave) {
                    setEntityInvisible(entity, false);
                    // Play sound for all players at the entity's position.
                    for (Player p : players) {
                        p.playSound(entity.getLocation(), finalSound, volume, pitch);
                    }
                }

                // Hide the wave after visibleTicks.
                new BukkitRunnable() {
                    @Override public void run() {
                        if (state != SessionState.ACTIVE) return;
                        for (Entity entity : wave) {
                            if (!entity.isDead()) setEntityInvisible(entity, true);
                        }
                    }
                }.runTaskLater(plugin, visibleTicks);
            }
        }.runTaskTimer(plugin, 20L, waveInterval);
    }

    /**
     * Chain mode: all entities start invisible. Only the current target
     * (by index) is revealed. When a player hits it, the next one is revealed.
     * If a player falls, they are sent to the lobby with a loss message.
     */
    private void startChainMode() {
        // Hide all entities.
        for (Entity e : spawnedEntities) setEntityInvisible(e, true);

        // Reveal the first target if any.
        revealChainTarget();
    }

    /**
     * Reveals the current chain target and hides the previous one.
     * Called at game start and after each successful hit in chain mode.
     */
    public void revealChainTarget() {
        if (chainIndex >= spawnedEntities.size()) return;

        Entity target = spawnedEntities.get(chainIndex);
        if (target == null || target.isDead()) {
            chainIndex++;
            revealChainTarget();
            return;
        }

        setEntityInvisible(target, false);

        // Play reveal sound near the entity.
        String soundName = plugin.getConfig().getString("difficulty.hard.spawn-sound", "BLOCK_DISPENSER_LAUNCH");
        float  volume    = (float) plugin.getConfig().getDouble("difficulty.hard.spawn-sound-volume", 1.0);
        float  pitch     = (float) plugin.getConfig().getDouble("difficulty.hard.spawn-sound-pitch", 1.4);
        Sound  sound;
        try { sound = Sound.valueOf(soundName); }
        catch (IllegalArgumentException e) { sound = Sound.BLOCK_DISPENSER_LAUNCH; }

        for (Player p : players) {
            p.playSound(target.getLocation(), sound, volume, pitch);
        }
    }

    /**
     * Fall-watch safety net — runs every 2 seconds while the game is active.
     *
     * <p>If a player has not landed a hit for longer than {@code fall-watch-timeout}
     * ticks AND their Y is below their last checkpoint, they are considered stuck
     * or falling. Behaviour depends on difficulty:
     * <ul>
     *   <li><b>Easy:</b> give a Levitation rescue boost to get back into the action.</li>
     *   <li><b>Medium / Hard:</b> teleport to last checkpoint (same as floor-fall).</li>
     * </ul>
     */
    private void scheduleFallWatch() {
        int timeoutTicks = plugin.getConfig().getInt("game.fall-watch-timeout-ticks", 100);
        int levTicks     = plugin.getConfig().getInt("game.start-boost.levitation-ticks", 100);
        int levAmp       = plugin.getConfig().getInt("game.start-boost.levitation-amplifier", 2);

        fallWatchTask = new BukkitRunnable() {
            @Override
            public void run() {
                if (state != SessionState.ACTIVE) { cancel(); return; }

                int now = plugin.getServer().getCurrentTick();

                for (Player player : List.copyOf(players)) {
                    if (!player.isOnline()) continue;

                    int lastHit  = lastHitTick.getOrDefault(player.getUniqueId(), now - timeoutTicks - 1);
                    int sinceHit = now - lastHit;

                    // Only act if idle for longer than the configured timeout.
                    if (sinceHit < timeoutTicks) continue;

                    Location checkpoint = checkpoints.getOrDefault(
                            player.getUniqueId(), arena.getSpawnLocation());

                    // Player must be below their checkpoint to be considered stuck.
                    if (player.getLocation().getY() >= checkpoint.getY()) continue;

                    if (difficulty == Difficulty.EASY) {
                        // Rescue: give levitation so they can reach the nearest target.
                        player.addPotionEffect(new PotionEffect(
                                PotionEffectType.LEVITATION, levTicks, levAmp, false, false, false));
                        plugin.getMessageUtil().send(player, "game.rescue-levitation");
                    } else {
                        // Medium / Hard: checkpoint reset.
                        onPlayerFell(player);
                    }

                    // Reset the timer so we don't spam rescues every 2 seconds.
                    lastHitTick.put(player.getUniqueId(), now);
                }
            }
        }.runTaskTimer(plugin, 40L, 40L); // check every 2 seconds
    }

    /** Applies or removes the invisibility potion effect on a living entity. */
    private void setEntityInvisible(Entity entity, boolean invisible) {
        if (!(entity instanceof LivingEntity living)) return;

        if (invisible) {
            // Desactivar glow antes de aplicar invisibilidad
            if (arena.isGlowEnabled()) living.setGlowing(false);

            living.addPotionEffect(new PotionEffect(
                    PotionEffectType.INVISIBILITY, Integer.MAX_VALUE, 0, false, false, false));
        } else {
            living.removePotionEffect(PotionEffectType.INVISIBILITY);

            // Restaurar glow si la arena lo tiene configurado
            if (arena.isGlowEnabled()) living.setGlowing(true);
        }
    }
    // -----------------------------------------------------------------------
    // In-game events (called from listeners)
    // -----------------------------------------------------------------------

    /**
     * Called by {@link com.gexel.grise.listeners.MaceListener} when a valid
     * Mace + Wind Burst hit is detected on a G-Rise target entity.
     */
    public void onValidHit(Player player, Entity entity) {
        if (state != SessionState.ACTIVE) return;

        // Record hit tick for fall-watch.
        lastHitTick.put(player.getUniqueId(), plugin.getServer().getCurrentTick());

        // Increment combo.
        int combo = comboCounts.merge(player.getUniqueId(), 1, Integer::sum);

        // Update checkpoint to entity location.
        checkpoints.put(player.getUniqueId(), entity.getLocation().clone());

        // Combo ascending sound.
        if (plugin.getConfig().getBoolean("mace.combo-sound-enabled", true)) {
            playComboSound(player, combo);
        }

        // --- Hit effects (particles + impact sound) ---
        spawnHitEffects(player, entity);

        // --- Combo milestone rewards ---
        checkComboMilestone(player, combo);

        // --- Medium mode: multi-hit entity HP ---
        if (difficulty == Difficulty.MEDIUM) {
            UUID eid = entity.getUniqueId();
            int hitsLeft = mediumEntityHits.getOrDefault(eid, 1) - 1;

            if (hitsLeft <= 0) {
                // Entity is destroyed.
                mediumEntityHits.remove(eid);
                spawnedEntities.remove(entity);
                entity.remove();

                if (spawnedEntities.isEmpty()) {
                    onPlayerFinish(player);
                    return;
                }
            } else {
                // Entity survives — update HP and flash it.
                mediumEntityHits.put(eid, hitsLeft);
                flashEntityHit(entity, hitsLeft);
            }
        }

        // --- Chain mode (Hard): advance to next target ---
        if (chainMode) {
            setEntityInvisible(entity, true);
            chainIndex++;
            if (chainIndex >= spawnedEntities.size()) {
                onPlayerFinish(player);
                return;
            }
            revealChainTarget();
        }

        // Goal-height check 2 ticks later (player is still ascending).
        new org.bukkit.scheduler.BukkitRunnable() {
            @Override public void run() {
                if (state != SessionState.ACTIVE) return;
                if (!player.isOnline()) return;
                if (arena.hasReachedGoal(player.getLocation().getY())) {
                    onPlayerFinish(player);
                }
            }
        }.runTaskLater(plugin, 2L);
    }

    // -----------------------------------------------------------------------
    // Hit effects helpers
    // -----------------------------------------------------------------------

    /**
     * Spawns particles and plays an impact sound at the entity's location.
     * All nearby players see and hear the effect.
     */
    private void spawnHitEffects(Player hitter, Entity entity) {
        if (!plugin.getConfig().getBoolean("effects.hit-effects-enabled", true)) return;

        Location loc = entity.getLocation().add(0, 1, 0);

        // Particle burst — CRIT for the hit feel, EXPLOSION_EMITTER scaled small.
        String particleName = plugin.getConfig().getString("effects.hit-particle", "CRIT");
        int    particleCount = plugin.getConfig().getInt("effects.hit-particle-count", 12);
        Particle particle;
        try { particle = Particle.valueOf(particleName); }
        catch (IllegalArgumentException e) { particle = Particle.CRIT; }

        final Particle finalParticle = particle;
        // Spawn for all players in the session so everyone sees it.
        for (Player p : players) {
            p.spawnParticle(finalParticle, loc, particleCount, 0.3, 0.3, 0.3, 0.1);
        }

        // Impact sound.
        String soundName = plugin.getConfig().getString("effects.hit-sound", "ENTITY_GENERIC_EXPLODE");
        float  volume    = (float) plugin.getConfig().getDouble("effects.hit-sound-volume", 0.6);
        float  pitch     = (float) plugin.getConfig().getDouble("effects.hit-sound-pitch", 1.8);
        Sound  sound;
        try { sound = Sound.valueOf(soundName); }
        catch (IllegalArgumentException e) { sound = Sound.ENTITY_GENERIC_EXPLODE; }

        final Sound finalSound = sound;
        for (Player p : players) {
            p.playSound(loc, finalSound, volume, pitch);
        }
    }

    /**
     * Flashes the entity's name tag to show remaining hits in Medium mode.
     * Also plays a "taking damage" particle to reinforce the multi-hit feel.
     */
    private void flashEntityHit(Entity entity, int hitsLeft) {
        // Update custom name to show remaining hits.
        String bar = "§c" + "█".repeat(hitsLeft) + "§8" + "█".repeat(
                plugin.getConfig().getInt("difficulty.medium.hits-per-entity", 5) - hitsLeft);
        entity.customName(net.kyori.adventure.text.Component.text("§b✦ " + bar));
        entity.setCustomNameVisible(true);

        // Small damage flash: briefly make entity red-tinted with a particle.
        Location loc = entity.getLocation().add(0, 1, 0);
        for (Player p : players) {
            p.spawnParticle(Particle.DAMAGE_INDICATOR, loc, 4, 0.2, 0.2, 0.2, 0);
        }

        // Hide name tag again after 2 seconds.
        new org.bukkit.scheduler.BukkitRunnable() {
            @Override public void run() {
                if (!entity.isDead()) entity.setCustomNameVisible(false);
            }
        }.runTaskLater(plugin, 40L);
    }

    /**
     * Checks if the player's current combo has crossed a configured milestone
     * and, if so, fires the reward command and shows a milestone title.
     */
    private void checkComboMilestone(Player player, int combo) {
        if (!plugin.getConfig().getBoolean("rewards.enabled", false)) return;

        var section = plugin.getConfig().getConfigurationSection("rewards.milestones");
        if (section == null) return;

        int lastFired = lastMilestone.getOrDefault(player.getUniqueId(), 0);

        for (String key : section.getKeys(false)) {
            int threshold;
            try { threshold = Integer.parseInt(key); }
            catch (NumberFormatException e) { continue; }

            // Only fire if we just crossed this threshold and haven't fired it yet.
            if (combo >= threshold && threshold > lastFired) {
                lastMilestone.put(player.getUniqueId(), threshold);

                // Show milestone title.
                String titleText    = plugin.getConfig().getString(
                        "rewards.milestones." + key + ".title",
                        "&6&lCOMBO x" + threshold + "!");
                String subtitleText = plugin.getConfig().getString(
                        "rewards.milestones." + key + ".subtitle",
                        "&eKeep going!");

                player.showTitle(Title.title(
                        net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer
                                .legacyAmpersand().deserialize(titleText),
                        net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer
                                .legacyAmpersand().deserialize(subtitleText),
                        Title.Times.times(
                                java.time.Duration.ofMillis(50),
                                java.time.Duration.ofMillis(1200),
                                java.time.Duration.ofMillis(300))
                ));

                // Play milestone sound.
                player.playSound(player.getLocation(), Sound.UI_TOAST_CHALLENGE_COMPLETE, 0.8f, 1.4f);

                // Execute reward command (replace %player% with real name).
                String command = plugin.getConfig().getString(
                        "rewards.milestones." + key + ".command", "");
                if (!command.isBlank()) {
                    String finalCmd = command.replace("%player%", player.getName());
                    plugin.getServer().dispatchCommand(
                            plugin.getServer().getConsoleSender(), finalCmd);
                }
            }
        }
    }

    /**
     * Called when a player touches the arena floor.
     *
     * <ul>
     *   <li><b>Easy:</b> no penalty (fall-watch handles rescue levitation).</li>
     *   <li><b>Medium:</b> teleport to last checkpoint.</li>
     *   <li><b>Hard:</b> instant elimination — player is removed and sent to
     *       lobby with a "you lost" message. In multiplayer the remaining
     *       players continue; in solo the session ends immediately.</li>
     * </ul>
     */
public void onPlayerFell(Player player) {
    if (state != SessionState.ACTIVE) return;
    if (!difficulty.hasFallPenalty()) return;

    comboCounts.put(player.getUniqueId(), 0);
    lastHitTick.put(player.getUniqueId(), plugin.getServer().getCurrentTick());

    if (difficulty == Difficulty.HARD) {
        plugin.getMessageUtil().send(player, "game.hard-fell-eliminated");
        player.showTitle(net.kyori.adventure.title.Title.title(
                net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer
                        .legacyAmpersand().deserialize("&c&lELIMINATED"),
                net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer
                        .legacyAmpersand().deserialize("&7You fell and lost your run."),
                net.kyori.adventure.title.Title.Times.times(
                        java.time.Duration.ofMillis(100),
                        java.time.Duration.ofMillis(2500),
                        java.time.Duration.ofMillis(500))
        ));
        player.playSound(player.getLocation(), Sound.ENTITY_WITHER_DEATH, 0.7f, 1.5f);

        plugin.getArenaManager().forceRemovePlayer(player);
        players.remove(player);
        checkpoints.remove(player.getUniqueId());
        comboCounts.remove(player.getUniqueId());
        lastHitTick.remove(player.getUniqueId());
        lastMilestone.remove(player.getUniqueId());
        restorePlayerState(player);

        if (players.isEmpty()) endSession(null);
        return;
    }

    // Medium: Slow Falling en lugar de TP
    int slowFallTicks = plugin.getConfig().getInt("game.fall-recovery-ticks", 60);
    player.addPotionEffect(new PotionEffect(
            PotionEffectType.LEVITATION, 40, 7, false, false, false));
    plugin.getMessageUtil().send(player, "game.fell-slow-falling");
}

    /**
     * Records the player's finish time and triggers end-of-session logic.
     * Public so {@link com.gexel.grise.listeners.PlayerListener} can call it
     * directly from the move-event goal check.
     *
     * <p>Guard: if the player already finished (race mode lets multiple players
     * finish; solo ends immediately) this is a no-op.
     */
    public void onPlayerFinish(Player player) {
        if (state != SessionState.ACTIVE) return;
        // Prevent double-trigger (PlayerMoveEvent fires every block, onValidHit
        // also schedules a delayed check).
        if (!finishedPlayers.add(player.getUniqueId())) return;

        long elapsedTicks = plugin.getServer().getCurrentTick() - startTick;
        String time = TimeUtil.formatTicks(elapsedTicks);
        finishTimes.put(player.getUniqueId(), time);

        endSession(player);
    }

    private void playComboSound(Player player, int combo) {
        String soundName = plugin.getConfig().getString("mace.combo-sound", "ENTITY_PLAYER_ATTACK_SWEEP");
        float basePitch  = (float) plugin.getConfig().getDouble("mace.combo-sound-base-pitch", 0.8);
        float step       = (float) plugin.getConfig().getDouble("mace.combo-sound-pitch-step", 0.15);
        int maxCombo     = plugin.getConfig().getInt("mace.combo-max-for-pitch", 10);

        Sound sound;
        try {
            sound = Sound.valueOf(soundName);
        } catch (IllegalArgumentException e) {
            sound = Sound.ENTITY_PLAYER_ATTACK_SWEEP;
        }

        float pitch = basePitch + Math.min(combo, maxCombo) * step;
        player.playSound(player.getLocation(), sound, 1f, pitch);
    }

    // -----------------------------------------------------------------------
    // Broadcast helper
    // -----------------------------------------------------------------------

    /** Sends a coloured message to every player in this session. */
    private void broadcastToSession(String message) {
        var component = net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer
                .legacyAmpersand().deserialize(message);
        players.forEach(p -> p.sendMessage(component));
    }

    // -----------------------------------------------------------------------
    // Getters
    // -----------------------------------------------------------------------

    public Arena getArena()               { return arena; }
    public Difficulty getDifficulty()     { return difficulty; }
    public SessionState getState()        { return state; }
    public List<Player> getPlayers()      { return Collections.unmodifiableList(players); }
    public List<Entity> getSpawnedEntities() { return Collections.unmodifiableList(spawnedEntities); }

    /** Returns the current combo count for a player (0 if not in session). */
    public int getCombo(Player player) {
        return comboCounts.getOrDefault(player.getUniqueId(), 0);
    }

    /** Returns {@code true} if the given entity is a target in this session. */
    public boolean isTarget(Entity entity) {
        return entity.hasMetadata(META_KEY) && spawnedEntities.contains(entity);
    }
}
