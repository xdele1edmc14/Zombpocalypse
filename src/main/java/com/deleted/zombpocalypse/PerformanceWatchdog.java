package com.deleted.zombpocalypse;

import org.bukkit.Bukkit;
import org.bukkit.World;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Player;
import org.bukkit.entity.Zombie;
import org.bukkit.scheduler.BukkitRunnable;
import org.bukkit.scheduler.BukkitTask;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * PerformanceWatchdog - Monitors TPS and performs entity culling to prevent lag
 */
public class PerformanceWatchdog {

    private final Zombpocalypse plugin;
    private BukkitTask watchdogTask;
    private BukkitTask lodTask;
    
    private long checkIntervalTicks;
    
    // LOD (Level of Detail) system for distance-based AI throttling
    private final Map<Zombie, Long> zombieLastAITick = new HashMap<>();
    private final double lodDistanceThreshold = 32.0; // Blocks
    private final long lodTickInterval = 20L; // Ticks between AI updates for far zombies (1 second)

    public PerformanceWatchdog(Zombpocalypse plugin) {
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
            
            // DISABLE TPS-BASED PAUSING: Only pause if TPS is critically low (< 10)
            if (currentTPS < 10.0) {
                plugin.debugLog("CRITICAL TPS: " + currentTPS + " - Pausing all spawning");
                pauseAllSpawning();
                return;
            }
            
            // Check entity count limits for all worlds
            for (World world : Bukkit.getWorlds()) {
                if (!plugin.isWorldEnabled(world)) continue;
                
                int zombieCount = countZombiesInWorld(world);
                int maxZombies = plugin.getConfig().getInt("performance.max-total-zombies", 300);
                
                // Dynamic caps based on config
                if (zombieCount > maxZombies) {
                    plugin.debugLog("Zombie count exceeded limit in " + world.getName() + ": " + zombieCount + " > " + maxZombies);
                    cullZombiesInWorld(world, zombieCount - maxZombies);
                }
            }
            
            // Resume spawning if TPS is healthy
            if (currentTPS >= 15.0) {
                resumeSpawning();
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
        } catch (Exception e) {
            plugin.debugLog("Could not get TPS from Paper API: " + e.getMessage());
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
        // no-op
    }
    
    private void resumeSpawning() {
        // no-op
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
     * Zombies further than lodDistanceThreshold from players have reduced AI update frequency
     */
    private void updateLODSystem() {
        long currentTick = Bukkit.getServer().getCurrentTick();
        
        for (World world : Bukkit.getWorlds()) {
            if (!plugin.isWorldEnabled(world)) continue;
            
            List<Player> players = world.getPlayers();
            if (players.isEmpty()) continue;
            
            for (Entity entity : world.getEntitiesByClass(Zombie.class)) {
                if (!(entity instanceof Zombie zombie)) continue;
                
                // Find nearest player
                double minDistance = Double.MAX_VALUE;
                for (Player player : players) {
                    if (!player.getWorld().equals(world)) continue;
                    double dist = zombie.getLocation().distanceSquared(player.getLocation());
                    if (dist < minDistance) {
                        minDistance = dist;
                    }
                }
                
                double distance = Math.sqrt(minDistance);
                
                // If zombie is far from players, throttle AI updates
                if (distance > lodDistanceThreshold) {
                    Long lastTick = zombieLastAITick.get(zombie);
                    if (lastTick == null || (currentTick - lastTick) >= lodTickInterval) {
                        // Allow AI tick
                        zombieLastAITick.put(zombie, currentTick);
                    }
                    // Otherwise, skip AI tick for this zombie
                } else {
                    // Close zombies get normal AI updates, remove from throttling map
                    zombieLastAITick.remove(zombie);
                }
            }
        }
        
        // Clean up map entries for zombies that no longer exist
        zombieLastAITick.entrySet().removeIf(entry -> entry.getKey().isDead() || !entry.getKey().isValid());
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
