package com.deleted.xapocalypse;

import org.bukkit.Bukkit;
import org.bukkit.World;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Player;
import org.bukkit.entity.Zombie;
import org.bukkit.scheduler.BukkitRunnable;
import org.bukkit.scheduler.BukkitTask;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * PerformanceWatchdog - Monitors TPS and performs entity culling to prevent lag
 */
public class PerformanceWatchdog {

    private final xApocalypse plugin;
    private BukkitTask watchdogTask;
    private BukkitTask lodTask;
    
    private long checkIntervalTicks;
    
    // Minor fix: implement spawningPaused so HordeSpawnerTask can query it
    private boolean spawningPaused = false;
    
    // LOD (Level of Detail) system for distance-based AI throttling
    // Bug 7 fix: bounded LinkedHashMap — evicts dead/invalid entries and caps at 1000 to prevent unbounded growth
    private final Map<Zombie, Long> zombieLastAITick = new LinkedHashMap<>(256, 0.75f, false) {
        @Override
        protected boolean removeEldestEntry(Map.Entry<Zombie, Long> eldest) {
            return size() > 1000 || eldest.getKey().isDead() || !eldest.getKey().isValid();
        }
    };
    private final double lodDistanceThreshold = 32.0; // Blocks
    private final long lodTickInterval = 20L; // Ticks between AI updates for far zombies (1 second)

    public PerformanceWatchdog(xApocalypse plugin) {
        this.plugin = plugin;
        loadConfig();
    }

    private void loadConfig() {
        checkIntervalTicks = plugin.getConfig().getLong("performance.check-interval-ticks", 100L);
    }

    public void start() {
        if (watchdogTask != null) {
            watchdogTask.cancel();
        }
        if (lodTask != null) {
            lodTask.cancel();
        }

        watchdogTask = new BukkitRunnable() {
            @Override
            public void run() {
                checkPerformance();
            }
        }.runTaskTimer(plugin, 0L, checkIntervalTicks);
        
        // Start LOD system for distance-based AI throttling
        lodTask = new BukkitRunnable() {
            @Override
            public void run() {
                updateLODSystem();
            }
        }.runTaskTimer(plugin, 0L, 10L); // Check every 0.5 seconds
        
        plugin.debugLog("PerformanceWatchdog started (check interval: " + checkIntervalTicks + " ticks)");
    }

    public void stop() {
        if (watchdogTask != null) {
            watchdogTask.cancel();
            watchdogTask = null;
        }
        if (lodTask != null) {
            lodTask.cancel();
            lodTask = null;
        }
        zombieLastAITick.clear();
    }

    public void reload() {
        loadConfig();
        stop();
        start();
    }

    private void checkPerformance() {
        try {
            double currentTPS = getCurrentTPS();
            // Minor fix: honor performance.tps-threshold (previously the config value was ignored and
            // the thresholds were hardcoded 10/15). Pause new spawns below the threshold; resume once
            // TPS recovers a little above it (hysteresis avoids rapid flapping around the threshold).
            double tpsThreshold = plugin.getConfig().getDouble("performance.tps-threshold", 15.0);

            if (currentTPS < tpsThreshold) {
                plugin.debugLog("Low TPS: " + currentTPS + " (< " + tpsThreshold + ") - pausing spawning");
                pauseAllSpawning();
            } else if (currentTPS >= tpsThreshold + 1.5) {
                resumeSpawning();
            }

            // Entity-count culling always runs (it helps TPS recover).
            for (World world : Bukkit.getWorlds()) {
                if (!plugin.isWorldEnabled(world)) continue;

                int zombieCount = countZombiesInWorld(world);
                int maxZombies = plugin.getConfig().getInt("performance.max-total-zombies", 300);

                if (zombieCount > maxZombies) {
                    plugin.debugLog("Zombie count exceeded limit in " + world.getName() + ": " + zombieCount + " > " + maxZombies);
                    cullZombiesInWorld(world, zombieCount - maxZombies);
                }
            }
        } catch (Exception e) {
            plugin.getLogger().warning("PerformanceWatchdog error: " + e.getMessage());
            e.printStackTrace();
        }
    }

    private double getCurrentTPS() {
        try {
            // Use Paper API TPS monitoring
            double[] tps = Bukkit.getTPS();
            if (tps != null && tps.length > 0) {
                return tps[0]; // 1 minute average
            }
        } catch (Throwable t) {
            // Throwable (not just Exception) so a NoSuchMethodError on very old Spigot builds
            // without getTPS() can't kill the watchdog task.
            plugin.debugLog("Could not get TPS from server API: " + t.getMessage());
        }

        // Fallback if something goes wrong
        return 20.0;
    }

    private int countZombiesInWorld(World world) {
        // world.getEntitiesByClass is already filtered to Zombies
        // .size() is much faster than running a manual 'for' loop
        return world.getEntitiesByClass(Zombie.class).size();
    }

    private void pauseAllSpawning() {
        spawningPaused = true;
        plugin.debugLog("Spawning paused due to critical TPS.");
    }
    
    private void resumeSpawning() {
        if (spawningPaused) {
            spawningPaused = false;
            plugin.debugLog("Spawning resumed — TPS recovered.");
        }
    }

    public boolean isSpawningPaused() {
        return spawningPaused;
    }

    private void cullZombiesInWorld(World world, int amount) {
        List<Zombie> zombies = new ArrayList<>();
        
        for (Entity entity : world.getEntitiesByClass(Zombie.class)) {
            if (entity instanceof Zombie zombie) {
                // Prioritize culling zombies that are far from players
                zombies.add(zombie);
            }
        }

        if (zombies.isEmpty()) return;

        // Cache distances to avoid recalculating during sorting and culling
        List<ZombieDistance> zombieDistances = new ArrayList<>(zombies.size());
        for (Zombie zombie : zombies) {
            double distance = getDistanceToNearestPlayer(zombie);
            zombieDistances.add(new ZombieDistance(zombie, distance));
        }

        // Sort by distance to nearest player (furthest first)
        zombieDistances.sort((zd1, zd2) -> Double.compare(zd2.distance, zd1.distance));

        int culled = 0;
        for (ZombieDistance zd : zombieDistances) {
            if (culled >= amount) break;
            
            // Don't cull zombies that are very close to players
            if (zd.distance < 20) continue;
            
            zd.zombie.remove();
            culled++;
        }

        if (culled > 0) {
            plugin.debugLog("Culled " + culled + " zombies in " + world.getName() + " due to performance issues");
        }
    }

    private double getDistanceToNearestPlayer(Entity entity) {
        double minDist = Double.MAX_VALUE;
        for (org.bukkit.entity.Player player : Bukkit.getOnlinePlayers()) {
            if (!player.getWorld().equals(entity.getWorld())) continue;
            double dist = player.getLocation().distanceSquared(entity.getLocation());
            if (dist < minDist) {
                minDist = dist;
            }
        }
        return Math.sqrt(minDist);
    }

    public double getCurrentTPSValue() {
        return getCurrentTPS();
    }
    
    /**
     * LOD System: Distance-based AI throttling
     * Bug 19 fix: call zombie.setAI(false/true) to actually suppress vanilla pathfinding for far zombies.
     * The previous implementation only gated plugin-side AI methods — vanilla pathfinding ran regardless.
     */
    private void updateLODSystem() {
        long currentTick = Bukkit.getServer().getCurrentTick();

        for (World world : Bukkit.getWorlds()) {
            if (!plugin.isWorldEnabled(world)) continue;

            List<Player> players = world.getPlayers();
            if (players.isEmpty()) {
                // No players — disable AI for all zombies in this world to save CPU
                for (Entity entity : world.getEntitiesByClass(Zombie.class)) {
                    if (!(entity instanceof Zombie zombie) || zombie.isDead() || !zombie.isValid()) continue;
                    // Bug 19 fix: skip zombies mid-rise-animation (setAI(false) would conflict)
                    if (zombie.getPersistentDataContainer().has(
                            xApocalypseUtils.ANIMATING_KEY, org.bukkit.persistence.PersistentDataType.BYTE)) continue;
                    if (zombie.hasAI()) zombie.setAI(false);
                    zombieLastAITick.put(zombie, currentTick);
                }
                continue;
            }

            for (Entity entity : world.getEntitiesByClass(Zombie.class)) {
                if (!(entity instanceof Zombie zombie)) continue;
                if (zombie.isDead() || !zombie.isValid()) continue;
                // Bug 19 fix: skip animating zombies
                if (zombie.getPersistentDataContainer().has(
                        xApocalypseUtils.ANIMATING_KEY, org.bukkit.persistence.PersistentDataType.BYTE)) continue;

                double minDistance = Double.MAX_VALUE;
                for (Player player : players) {
                    double dist = zombie.getLocation().distanceSquared(player.getLocation());
                    if (dist < minDistance) minDistance = dist;
                }
                double distance = Math.sqrt(minDistance);

                if (distance > lodDistanceThreshold) {
                    // Bug 19 fix: actually disable vanilla AI for far zombies
                    if (zombie.hasAI()) zombie.setAI(false);
                    zombieLastAITick.put(zombie, currentTick);
                } else {
                    // Re-enable AI when a player moves close
                    if (!zombie.hasAI()) zombie.setAI(true);
                    zombieLastAITick.remove(zombie);
                }
            }
        }

        // Bug 7 fix: only run the removeIf sweep when the map is large enough to warrant it
        if (zombieLastAITick.size() > 500) {
            zombieLastAITick.entrySet().removeIf(entry -> entry.getKey().isDead() || !entry.getKey().isValid());
        }
    }
    
    /**
     * Check if a zombie should have its AI ticked (LOD system)
     */
    public boolean shouldTickZombieAI(Zombie zombie) {
        if (zombie.isDead() || !zombie.isValid()) return false;
        
        Long lastTick = zombieLastAITick.get(zombie);
        if (lastTick == null) return true; // Not in LOD system, allow tick
        
        long currentTick = Bukkit.getServer().getCurrentTick();
        return (currentTick - lastTick) >= lodTickInterval;
    }

    /**
     * Helper class to cache zombie distance calculations
     */
    private static class ZombieDistance {
        final Zombie zombie;
        final double distance;

        ZombieDistance(Zombie zombie, double distance) {
            this.zombie = zombie;
            this.distance = distance;
        }
    }
}
