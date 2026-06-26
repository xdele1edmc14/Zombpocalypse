package com.deleted.xapocalypse;

import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.attribute.Attribute;
import org.bukkit.boss.BarColor;
import org.bukkit.boss.BarStyle;
import org.bukkit.boss.BossBar;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Player;
import org.bukkit.entity.Zombie;
import org.bukkit.event.player.PlayerItemConsumeEvent;
import org.bukkit.Location;
import org.bukkit.inventory.ItemStack;
import org.bukkit.scheduler.BukkitTask;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Owns the entire Zombie-Guts Immunity subsystem: the immunity state maps, the data.yml
 * persistence file, the bossbar + expiry-check tasks, granting immunity on guts consumption,
 * and restoring player state on join/quit.
 *
 * Extracted verbatim from the original xApocalypse monolith. All BUGFIX behaviour is
 * preserved — most importantly that all immunity timing uses System.currentTimeMillis()
 * (the real-world clock) rather than world.getFullTime(), which is warped by the blood-moon
 * system's world.setTime() calls.
 */
public class ImmunityManager {

    private final xApocalypse plugin;
    private final MessageManager messageManager;

    // --- PERSISTENCE (data.yml is owned entirely by the immunity subsystem) ---
    private File dataFile;
    private FileConfiguration dataConfig;

    // --- IMMUNITY TRACKING ---
    private final List<UUID> immunePlayers = new ArrayList<>();
    private final Map<UUID, BossBar> immunityBossBars = new HashMap<>();
    private final Map<UUID, Long> immunityEndTime = new HashMap<>();
    private final Map<UUID, Double> originalHealth = new HashMap<>();
    private final Map<UUID, BukkitTask> scheduledTasks = new HashMap<>();
    private final long IMMUNITY_DURATION_TICKS = 10 * 60 * 20L;
    // BUGFIX: immunity timing now runs off the real-world clock instead of world.getFullTime(),
    // because world.setTime() calls elsewhere (blood moon start/stop/correction) warp full-time
    // by up to +-24000 ticks instantly, which was corrupting every active immunity countdown.
    private final long IMMUNITY_DURATION_MILLIS = IMMUNITY_DURATION_TICKS * 50L;

    public ImmunityManager(xApocalypse plugin, MessageManager messageManager) {
        this.plugin = plugin;
        this.messageManager = messageManager;
    }

    // === SETUP / PERSISTENCE ===

    /** Initializes data.yml and loads persisted immunity state. */
    public void load() {
        // --- Data Persistence Setup ---
        dataFile = new File(plugin.getDataFolder(), "data.yml");
        if (!dataFile.exists()) {
            try { dataFile.createNewFile(); } catch (IOException e) { e.printStackTrace(); }
        }
        dataConfig = YamlConfiguration.loadConfiguration(dataFile);

        loadImmunityData();
    }

    private void loadImmunityData() {
        // Fixed: Null check to prevent startup crash
        if (!dataConfig.isConfigurationSection("player-immunity")) {
            plugin.debugLog("No immunity data found in data.yml");
            return;
        }

        // BUGFIX: use the real-world clock (System.currentTimeMillis()) instead of
        // world.getFullTime() — full-time gets warped by world.setTime() calls in the
        // blood moon system, which was the root cause of immunity timers freezing or
        // jumping to nonsense values. We store an ABSOLUTE end timestamp so downtime
        // between saves/restarts is accounted for automatically (just like a real clock).
        long now = System.currentTimeMillis();

        for (String key : dataConfig.getConfigurationSection("player-immunity").getKeys(false)) {
            try {
                UUID uuid = UUID.fromString(key);
                long endTimeMillis = dataConfig.getLong("player-immunity." + key + ".endTimeMillis");
                double originalHealthVal = dataConfig.getDouble("player-immunity." + key + ".originalHealth");

                if (originalHealthVal <= 0.0) continue;

                // Always remember their original max health so onPlayerJoin can restore
                // it even if immunity already expired while the server was offline.
                originalHealth.put(uuid, originalHealthVal);

                long remainingMillis = endTimeMillis - now;
                if (remainingMillis > IMMUNITY_DURATION_MILLIS) {
                    remainingMillis = IMMUNITY_DURATION_MILLIS;
                }

                if (remainingMillis > 0) {
                    long newEndTime = now + remainingMillis;
                    immunityEndTime.put(uuid, newEndTime);
                    immunePlayers.add(uuid);
                    plugin.debugLog("Loaded active immunity for " + key);

                    // BUGFIX: previously this never recreated the bossbar or scheduled a
                    // removal task, so a player already online when the plugin (re)enabled
                    // (e.g. /plugman reload) had no bossbar and depended entirely on the
                    // periodic check task to ever clear their immunity.
                    Player online = Bukkit.getPlayer(uuid);
                    if (online != null && online.isOnline()) {
                        BossBar bar = Bukkit.createBossBar("§2§lZombie Guts Immunity", BarColor.GREEN, BarStyle.SOLID);
                        bar.addPlayer(online);
                        immunityBossBars.put(uuid, bar);
                        scheduleImmunityRemoval(online, remainingMillis / 50L);
                    }
                }

            } catch (IllegalArgumentException e) {
                plugin.getLogger().warning("Invalid UUID in data.yml: " + key);
            }
        }
    }

    public void save() {
        // CRITICAL FIX: Add null safety checks
        if (dataConfig == null || dataFile == null) {
            plugin.getLogger().warning("Cannot save immunity data - data files not initialized");
            return;
        }

        dataConfig.set("player-immunity", null);

        for (UUID uuid : new ArrayList<>(originalHealth.keySet())) {
            String path = "player-immunity." + uuid.toString();
            double storedHealth = originalHealth.get(uuid);
            Long endTime = immunityEndTime.get(uuid);

            // BUGFIX: only persist players who are actually still immune. Storing an
            // absolute epoch-millis timestamp here (instead of a remaining-tick count
            // derived from world.getFullTime()) means this value survives server
            // downtime and blood-moon time skips correctly.
            if (storedHealth > 0.0 && endTime != null) {
                dataConfig.set(path + ".endTimeMillis", endTime);
                dataConfig.set(path + ".originalHealth", storedHealth);
            }
        }
        try { dataConfig.save(dataFile); } catch (IOException e) { e.printStackTrace(); }
    }

    // === REPEATING TASKS ===

    // CRITICAL FIX: immunity check task
    public void startCheckTask() {
        new org.bukkit.scheduler.BukkitRunnable() {
            @Override
            public void run() {
                if (immunePlayers.isEmpty()) return;

                // BUGFIX: was Bukkit.getWorlds().get(0).getFullTime() — world full-time gets
                // warped by world.setTime() calls (blood moon start/stop/correction), which
                // froze or scrambled this check entirely. System time is unaffected by that.
                long now = System.currentTimeMillis();

                for (UUID uuid : new ArrayList<>(immunePlayers)) {
                    Player player = Bukkit.getPlayer(uuid);
                    if (player == null || !player.isOnline()) continue;

                    Long endTime = immunityEndTime.get(uuid);
                    if (endTime == null) continue;

                    // Check if immunity has expired
                    if (now >= endTime) {
                        plugin.debugLog("Immunity expired for player " + player.getName() + ", retargeting zombies");

                        // BUGFIX: this branch used to call cleanUpPlayerState() directly,
                        // which wipes originalHealth WITHOUT ever restoring the player's
                        // max-health attribute. Any immunity that expired through this path
                        // (e.g. one restored from data.yml after a restart, which never gets
                        // a bossbar) left the player permanently stuck at reduced health.
                        // expireImmunity() does the restore first, then cleans up.
                        expireImmunity(player, "immunity.expired");

                        // Force nearby zombies to target the player
                        retargetZombiesNearPlayer(player);
                    }
                }
            }
        }.runTaskTimer(plugin, 20L, 20L); // Check every second
    }

    public void startBossBarTask() {
        new org.bukkit.scheduler.BukkitRunnable() {
            @Override
            public void run() {
                if (immunityBossBars.isEmpty()) return;

                // BUGFIX: real-world clock instead of world.getFullTime() (see startCheckTask).
                long now = System.currentTimeMillis();

                for (UUID uuid : new ArrayList<>(immunityBossBars.keySet())) {
                    Player player = Bukkit.getPlayer(uuid);
                    if (player == null || !player.isOnline()) { continue; }
                    if (player.getWorld() == null) continue;

                    Long endTime = immunityEndTime.get(uuid);
                    if (endTime == null) continue;

                    long remainingMillis = endTime - now;

                    if (remainingMillis <= 0) {
                        // Immunity expired - clean up immediately (restores health internally)
                        expireImmunity(player, "immunity.expired");
                        continue;
                    }

                    double progress = (double) remainingMillis / IMMUNITY_DURATION_MILLIS;
                    immunityBossBars.get(uuid).setProgress(Math.max(0.0, Math.min(1.0, progress)));

                    long remainingSeconds = remainingMillis / 1000;
                    long minutes = remainingSeconds / 60;
                    long seconds = remainingSeconds % 60;
                    String timeString = String.format("%02d:%02d", minutes, seconds);

                    immunityBossBars.get(uuid).setTitle(messageManager.get("immunity.bossbar", timeString));
                }
            }
        }.runTaskTimer(plugin, 0L, 5L);
    }

    // CRITICAL FIX: method to retarget zombies near player
    private void retargetZombiesNearPlayer(Player player) {
        Location loc = player.getLocation();
        double radius = 50.0; // Check within 50 blocks

        for (Entity entity : loc.getWorld().getNearbyEntities(loc, radius, radius, radius)) {
            if (entity instanceof Zombie zombie) {
                // Only retarget zombies that don't already have a target
                if (zombie.getTarget() == null) {
                    zombie.setTarget(player);
                    plugin.debugLog("Retargeted zombie to player " + player.getName());
                }
            }
        }
    }

    /**
     * BUGFIX: single source of truth for ending a player's Zombie Guts immunity.
     * Previously there were three separate copies of this logic (here, the boss bar
     * task, and the scheduled removal task) and one of them — the check task above —
     * never restored the player's max health, which is why hearts sometimes never
     * changed back after immunity wore off. Restoring health here, in one place,
     * means every expiry path behaves identically.
     */
    private void expireImmunity(Player player, String expiredMessageKey) {
        UUID uuid = player.getUniqueId();

        // Bug M6 fix: the bossbar task (every 5 ticks) and the check task (every 20 ticks) can
        // both hit the expiry edge on the same tick. Bail if this player is no longer immune so
        // the "expired" message and health restore only happen once.
        if (!immunePlayers.contains(uuid)) return;

        if (originalHealth.containsKey(uuid) && player.getAttribute(Attribute.GENERIC_MAX_HEALTH) != null) {
            double originalMaxHealth = originalHealth.get(uuid);
            player.getAttribute(Attribute.GENERIC_MAX_HEALTH).setBaseValue(originalMaxHealth);
            player.setHealth(Math.min(player.getHealth(), originalMaxHealth));
            player.sendMessage(messageManager.get("immunity.health-restored"));
        }

        cleanUpPlayerState(player);
        player.sendMessage(messageManager.get(expiredMessageKey));

        if (dataConfig != null && dataFile != null) {
            dataConfig.set("player-immunity." + uuid.toString(), null);
            try { dataConfig.save(dataFile); } catch (IOException e) {}
        }
    }

    private void scheduleImmunityRemoval(Player player, long durationTicks) {
        UUID uuid = player.getUniqueId();
        BukkitTask task = Bukkit.getScheduler().runTaskLater(plugin, () -> {
            if (player.isOnline() && immunePlayers.contains(uuid)) {
                expireImmunity(player, "immunity.expired");
            }
            scheduledTasks.remove(uuid);
        }, Math.max(0, durationTicks));
        scheduledTasks.put(uuid, task);
    }

    private void cleanUpPlayerState(Player player) {
        UUID uuid = player.getUniqueId();
        immunePlayers.remove(uuid);
        immunityEndTime.remove(uuid);
        originalHealth.remove(uuid);

        BukkitTask task = scheduledTasks.remove(uuid);
        if (task != null) task.cancel();

        BossBar bar = immunityBossBars.remove(uuid);
        if (bar != null) bar.removeAll();
    }

    // === EVENT HOOKS (called by xApocalypseListener) ===

    public boolean isImmune(UUID uuid) {
        return immunePlayers.contains(uuid);
    }

    public void onPlayerJoin(Player player) {
        UUID uuid = player.getUniqueId();

        if (originalHealth.containsKey(uuid) && player.getAttribute(Attribute.GENERIC_MAX_HEALTH) != null) {
            // BUGFIX: was player.getWorld().getFullTime() — corrupted by world.setTime()
            // calls in the blood moon system. Use the real-world clock instead.
            long remainingMillis = 0;
            if (immunityEndTime.containsKey(uuid)) {
                remainingMillis = immunityEndTime.get(uuid) - System.currentTimeMillis();
            }

            if (remainingMillis > 0) {
                player.getAttribute(Attribute.GENERIC_MAX_HEALTH).setBaseValue(10.0);
                player.setHealth(Math.min(player.getHealth(), 10.0));
                immunePlayers.add(uuid);

                BossBar bar = Bukkit.createBossBar("§2§lZombie Guts Immunity", BarColor.GREEN, BarStyle.SOLID);
                bar.addPlayer(player);
                immunityBossBars.put(uuid, bar);

                scheduleImmunityRemoval(player, remainingMillis / 50L);
            } else {
                double storedOriginalHealth = originalHealth.get(uuid);
                player.getAttribute(Attribute.GENERIC_MAX_HEALTH).setBaseValue(storedOriginalHealth);
                player.setHealth(Math.min(player.getHealth(), storedOriginalHealth));
                cleanUpPlayerState(player);
                dataConfig.set("player-immunity." + uuid.toString(), null);
                try { dataConfig.save(dataFile); } catch (IOException e) {}
            }
        }
    }

    /** Immune-player cleanup on quit. The listener invokes {@link #save()} afterwards (parity with original ordering). */
    public void onPlayerQuit(Player player) {
        UUID uuid = player.getUniqueId();

        if (immunePlayers.contains(uuid)) {
            BukkitTask task = scheduledTasks.remove(uuid);
            if (task != null) task.cancel();

            BossBar bar = immunityBossBars.remove(uuid);
            if (bar != null) bar.removeAll();
        }
    }

    /**
     * Handles a Zombie-Guts consumption. Encapsulates the original onPlayerConsume body, including
     * the zombie-guts-enabled gate and event cancellation.
     */
    public void handleGutsConsume(PlayerItemConsumeEvent event) {
        if (!plugin.isZombieGutsEnabled()) return;

        ItemStack item = event.getItem();
        Player player = event.getPlayer();
        UUID uuid = player.getUniqueId();

        if (item.getType() == Material.ROTTEN_FLESH && item.hasItemMeta() && item.getItemMeta().hasDisplayName()) {

            String displayName = item.getItemMeta().getDisplayName();

            if (displayName.equals(messageManager.get("immunity.item-name"))) {
                event.setCancelled(true);

                if (immunePlayers.contains(uuid)) {
                    player.sendMessage(messageManager.get("immunity.already-immune"));
                    return;
                }

                // BUGFIX: this null check used to run AFTER originalHealth/immunePlayers
                // were already mutated below, so a null world would leave the player
                // permanently flagged immune with no bossbar and no removal task.
                if (player.getWorld() == null) return;

                double maxHealth = 10.0;
                if (player.getAttribute(Attribute.GENERIC_MAX_HEALTH) != null) {
                    originalHealth.put(uuid, player.getAttribute(Attribute.GENERIC_MAX_HEALTH).getBaseValue());

                    player.getAttribute(Attribute.GENERIC_MAX_HEALTH).setBaseValue(maxHealth);
                    player.setHealth(Math.min(player.getHealth(), maxHealth));
                }

                immunePlayers.add(uuid);

                // BUGFIX: was player.getWorld().getFullTime() + IMMUNITY_DURATION_TICKS —
                // world full-time is warped by world.setTime() calls in the blood moon
                // system (forced/natural start, stop, and the night-time correction),
                // which is what was making this timer freeze or jump to nonsense values.
                long endTime = System.currentTimeMillis() + IMMUNITY_DURATION_MILLIS;
                immunityEndTime.put(uuid, endTime);

                BossBar bar = Bukkit.createBossBar(
                        messageManager.get("immunity.bossbar", "10:00"),
                        BarColor.GREEN,
                        BarStyle.SOLID
                );
                bar.addPlayer(player);
                immunityBossBars.put(uuid, bar);

                scheduleImmunityRemoval(player, IMMUNITY_DURATION_TICKS);

                // BUGFIX: this grant was never written to data.yml until the player quit,
                // reloaded, or the server shut down gracefully. A crash or `/stop` in
                // between meant the active immunity simply never persisted at all.
                save();

                player.sendMessage(messageManager.get("immunity.consumed"));

                // Clear all zombies currently targeting this player
                for (Entity entity : player.getWorld().getEntitiesByClass(Zombie.class)) {
                    if (entity instanceof Zombie zombie) {
                        if (zombie.getTarget() != null && zombie.getTarget().equals(player)) {
                            zombie.setTarget(null);
                        }
                    }
                }

                if (item.getAmount() > 1) {
                    item.setAmount(item.getAmount() - 1);
                } else {
                    if (player.getInventory().getItemInMainHand().equals(item)) {
                        player.getInventory().setItemInMainHand(null);
                    } else if (player.getInventory().getItemInOffHand().equals(item)) {
                        player.getInventory().setItemInOffHand(null);
                    }
                }
            }
        }
    }

    // === SHUTDOWN ===

    public void removeAllBossBars() {
        for (BossBar bar : immunityBossBars.values()) {
            bar.removeAll();
        }
    }
}
