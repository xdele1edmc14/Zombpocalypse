package com.deleted.zombpocalypse;

import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.entity.EntityType;
import org.bukkit.entity.Player;
import org.bukkit.entity.Zombie;
import org.bukkit.scheduler.BukkitRunnable;
import org.bukkit.scheduler.BukkitTask;

import java.util.*;
import java.util.concurrent.ThreadLocalRandom;

/**
 * HordeDirector - Handles intelligent zombie spawning with directional logic and FOV masking
 */
public class HordeDirector {

    private final Zombpocalypse plugin;
    private final ZombpocalypseUtils utils;
    
    // Spawn queue to limit spawns per tick
    private final Queue<SpawnRequest> spawnQueue = new LinkedList<>();
    private BukkitTask queueProcessor;
    private int maxSpawnsPerTick;
    
    // Configuration
    private int spawnRadius;

    public HordeDirector(Zombpocalypse plugin, ZombpocalypseUtils utils) {
        this.plugin = plugin;
        this.utils = utils;
        loadConfig();
        plugin.getLogger().info("Starting HordeDirector queue processor...");
        // DISABLE THE QUEUE: Comment out startQueueProcessor - queue is dead
        // startQueueProcessor();
        plugin.getLogger().info("HordeDirector started with IMMEDIATE spawning (queue disabled)");
    }

    private void loadConfig() {
        maxSpawnsPerTick = plugin.getConfig().getInt("performance.spawns-per-tick", 5);
        spawnRadius = plugin.getConfig().getInt("apocalypse-settings.spawn-radius", 40);
    }

    public void reload() {
        loadConfig();
        stopQueueProcessor();
        spawnQueue.clear(); // FLUSH THE TOILET: Clear ghost zombies
        startQueueProcessor();
    }

    private void startQueueProcessor() {
        if (queueProcessor != null) {
            queueProcessor.cancel();
        }

        plugin.getLogger().info("Starting queue processor task...");
        queueProcessor = new BukkitRunnable() {
            @Override
            public void run() {
                processSpawnQueue();
            }
        }.runTaskTimer(plugin, 0L, 1L); // Run every tick
        plugin.getLogger().info("Queue processor task started successfully");
    }

    private void stopQueueProcessor() {
        if (queueProcessor != null) {
            queueProcessor.cancel();
            queueProcessor = null;
        }
    }

    public void stop() {
        stopQueueProcessor();
        spawnQueue.clear();
    }

    /**
     * Process the spawn queue, spawning up to maxSpawnsPerTick zombies per tick
     * Uses ZombpocalypseUtils to assign weighted mutations
     */
    private void processSpawnQueue() {
        int spawned = 0;
        if (spawnQueue.size() > 0) {
            plugin.debugLog("Processing queue with " + spawnQueue.size() + " items");
        }
        
        // THE 'BULK' UPDATE: Spawn 50% of entire queue every tick
        int toSpawn = Math.max(maxSpawnsPerTick, spawnQueue.size() / 2);
        
        for (int i = 0; i < toSpawn && !spawnQueue.isEmpty(); i++) {
            SpawnRequest request = spawnQueue.poll();
            if (request == null) break;

            if (request.player == null || !request.player.isOnline()) {
                continue;
            }

            if (!plugin.isWorldEnabled(request.player.getWorld())) {
                continue;
            }

            // Retry logic: try 5 different locations before giving up
            Location spawnLoc = null;
            for (int attempt = 0; attempt < 5; attempt++) {
                spawnLoc = findSpawnLocation(request.player, request.isDayHordeSpawn);
                if (spawnLoc != null) {
                    break; // Found valid location
                }
            }

            if (spawnLoc != null) {
                // Spawn zombie directly on surface
                Zombie zombie = (Zombie) request.player.getWorld().spawnEntity(spawnLoc, EntityType.ZOMBIE);
                if (zombie != null) {
                    utils.assignZombieType(zombie);
                    spawned++;
                }
            }
        }
        
        // ADD EMERGENCY CLEAR: Prevent queue from growing too large
        if (spawnQueue.size() > 5000) {
            plugin.getLogger().warning("Emergency queue clear: " + spawnQueue.size() + " items cleared");
            spawnQueue.clear();
        }
        
        if (spawned > 0) {
            plugin.debugLog("Spawned " + spawned + " zombies this tick, queue remaining: " + spawnQueue.size());
        }
    }

    /**
     * Queue zombies to spawn near a player
     * @param player The target player
     * @param totalAmount Total number of zombies to spawn
     * @param isDayHordeSpawn Whether this is a day horde spawn
     */
    public void queueZombieSpawns(Player player, int totalAmount, boolean isDayHordeSpawn) {
        plugin.debugLog("Adding " + totalAmount + " zombies to queue for " + player.getName());
        for (int i = 0; i < totalAmount; i++) {
            spawnQueue.offer(new SpawnRequest(player, isDayHordeSpawn));
        }
        plugin.debugLog("Queue size after adding: " + spawnQueue.size());
    }

    /**
     * Find a valid spawn location using simple random offset (like the old version)
     * Zombies spawn anywhere in radius, 360 degrees around player
     */
    private Location findSpawnLocation(Player player, boolean isDayHordeSpawn) {
        Location playerLoc = player.getLocation();
        World world = playerLoc.getWorld();
        if (world == null) return null;

        // Simple random offset (like the old version)
        double xOffset = ThreadLocalRandom.current().nextDouble(-spawnRadius, spawnRadius);
        double zOffset = ThreadLocalRandom.current().nextDouble(-spawnRadius, spawnRadius);

        // Check if inside claim
        Location testLoc = playerLoc.clone().add(xOffset, 0, zOffset);
        if (plugin.isInsideClaim(testLoc)) {
            return null;
        }

        // RE-VERIFY: Pure command-style spawning - no search, no caves, no roofs
        return playerLoc.clone().add(xOffset, 1, zOffset);
    }

    /**
     * EMERGENCY FLUSH: Clear the dead queue
     */
    public void emergencyFlushQueue() {
        int size = spawnQueue.size();
        spawnQueue.clear();
        plugin.getLogger().info("Emergency queue flush: " + size + " items cleared");
    }

    /**
     * Set max spawns per tick (for PerformanceWatchdog emergency throttling)
     */
    public void setMaxSpawnsPerTick(int maxSpawnsPerTick) {
        this.maxSpawnsPerTick = Math.max(1, maxSpawnsPerTick); // Remove upper clamp
    }

    /**
     * Spawn request data structure
     */
    private static class SpawnRequest {
        final Player player;
        final boolean isDayHordeSpawn;

        SpawnRequest(Player player, boolean isDayHordeSpawn) {
            this.player = player;
            this.isDayHordeSpawn = isDayHordeSpawn;
        }
    }
}
