package com.deleted.zombpocalypse;

import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.Particle;
import org.bukkit.Sound;
import org.bukkit.World;
import org.bukkit.entity.EntityType;
import org.bukkit.entity.Player;
import org.bukkit.entity.Zombie;
import org.bukkit.scheduler.BukkitRunnable;
import org.bukkit.scheduler.BukkitTask;
import org.bukkit.util.Vector;

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
        startQueueProcessor();
    }

    private void loadConfig() {
        maxSpawnsPerTick = plugin.getConfig().getInt("performance.spawns-per-tick", 5);
        spawnRadius = plugin.getConfig().getInt("apocalypse-settings.spawn-radius", 40);
    }

    public void reload() {
        loadConfig();
        stopQueueProcessor();
        startQueueProcessor();
    }

    private void startQueueProcessor() {
        if (queueProcessor != null) {
            queueProcessor.cancel();
        }

        queueProcessor = new BukkitRunnable() {
            @Override
            public void run() {
                processSpawnQueue();
            }
        }.runTaskTimer(plugin, 0L, 1L); // Run every tick
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
        while (!spawnQueue.isEmpty() && spawned < maxSpawnsPerTick) {
            SpawnRequest request = spawnQueue.poll();
            if (request == null) break;

            if (request.player == null || !request.player.isOnline()) {
                continue;
            }

            if (!plugin.isWorldEnabled(request.player.getWorld())) {
                continue;
            }

            Location spawnLoc = findSpawnLocation(request.player, request.isDayHordeSpawn);
            if (spawnLoc != null) {
                // Spawn zombie with rising effect
                Zombie zombie = spawnZombieWithRisingEffect(spawnLoc, request.player.getWorld());
                if (zombie != null) {
                    utils.assignZombieType(zombie);
                    spawned++;
                }
            }
        }
    }

    /**
     * Queue zombies to spawn near a player
     * @param player The target player
     * @param totalAmount Total number of zombies to spawn
     * @param isDayHordeSpawn Whether this is a day horde spawn
     */
    public void queueZombieSpawns(Player player, int totalAmount, boolean isDayHordeSpawn) {
        for (int i = 0; i < totalAmount; i++) {
            spawnQueue.offer(new SpawnRequest(player, isDayHordeSpawn));
        }
    }

    /**
     * Find a valid spawn location in a strict 160° rear arc behind the player
     * Uses FOV masking to ensure zombies only spawn behind the player
     * Uses dot product check: if dotProduct > -0.3, reject and retry
     */
    private Location findSpawnLocation(Player player, boolean isDayHordeSpawn) {
        Location playerLoc = player.getLocation();
        World world = playerLoc.getWorld();
        if (world == null) return null;

        Vector playerDirection = playerLoc.getDirection().setY(0).normalize();
        Vector playerPosition = playerLoc.toVector();

        int maxAttempts = 15;
        double minDistance = spawnRadius * 0.6;
        double maxDistance = spawnRadius;
        
        for (int attempt = 0; attempt < maxAttempts; attempt++) {
            // Generate angle in 160° rear arc (100° to 260° relative to player facing)
            // This creates a tighter 80° spread on each side of directly behind
            double angleProgress = ThreadLocalRandom.current().nextDouble();
            double angleRadians = Math.toRadians(100.0 + (angleProgress * 160.0));
            double distance = ThreadLocalRandom.current().nextDouble(minDistance, maxDistance);
            
            // Calculate spawn offset using trigonometry
            double xOffset = distance * Math.cos(angleRadians);
            double zOffset = distance * Math.sin(angleRadians);
            
            // Rotate the offset to align with player's facing direction
            Vector rotatedOffset = rotateVector(new Vector(xOffset, 0, zOffset), playerLoc.getYaw());
            Vector spawnPosition = playerPosition.clone().add(rotatedOffset);
            
            Location candidateLoc = new Location(world, 
                    spawnPosition.getX(),
                    playerLoc.getY(),
                    spawnPosition.getZ(),
                    playerLoc.getYaw(),
                    playerLoc.getPitch());

            // Strict dot product check - ensure spawn is behind player
            Vector toSpawn = spawnPosition.subtract(playerPosition).normalize();
            double dotProduct = playerDirection.dot(toSpawn);
            
            // Reject if not in rear 160° arc (dotProduct > -0.3 means too far to the side or front)
            if (dotProduct > -0.3) {
                continue; // Not in rear arc
            }

            // Check if inside claim
            if (plugin.isInsideClaim(candidateLoc)) {
                continue;
            }

            Location surfaceLoc = findSurfaceLocation(candidateLoc);
            if (surfaceLoc == null) continue;

            // Check for water/lava
            Material blockType = surfaceLoc.getBlock().getRelative(0, -1, 0).getType();
            if (blockType == Material.WATER || blockType == Material.LAVA) {
                continue;
            }

            // Check light level if needed
            boolean shouldRespectLightLevel = !(isDayHordeSpawn || plugin.getConfig().getBoolean("apocalypse-settings.ignore-light-level", false));
            if (shouldRespectLightLevel) {
                int lightLevelSpawn = surfaceLoc.getBlock().getLightLevel();
                int lightLevelBelow = surfaceLoc.getBlock().getRelative(0, -1, 0).getLightLevel();
                int LIGHT_THRESHOLD = 8;
                
                if (lightLevelSpawn >= LIGHT_THRESHOLD || lightLevelBelow >= LIGHT_THRESHOLD) {
                    continue;
                }
            }

            return surfaceLoc;
        }

        // Fallback: try a simple random location if all attempts failed
        return findFallbackSpawnLocation(player, isDayHordeSpawn);
    }

    /**
     * Find surface location by searching downward from a candidate location
     * Optimized: Reuses Location objects to reduce GC pressure
     */
    private Location findSurfaceLocation(Location candidateLoc) {
        int startY = candidateLoc.getBlockY() + 3;
        int minY = Math.max(candidateLoc.getBlockY() - 3, candidateLoc.getWorld().getMinHeight());
        
        for (int y = startY; y >= minY; y--) {
            Location checkLoc = new Location(candidateLoc.getWorld(), 
                    candidateLoc.getX(), y, candidateLoc.getZ());
            if (checkLoc.getBlock().getRelative(0, -1, 0).getType().isSolid()) {
                candidateLoc.setY(y);
                return candidateLoc;
            }
        }
        return null;
    }

    /**
     * Fallback spawn location finder (simpler logic if directional spawning fails)
     */
    private Location findFallbackSpawnLocation(Player player, boolean isDayHordeSpawn) {
        Location playerLoc = player.getLocation();
        World world = playerLoc.getWorld();
        if (world == null) return null;

        for (int attempt = 0; attempt < 10; attempt++) {
            double xOffset = ThreadLocalRandom.current().nextDouble(-spawnRadius, spawnRadius);
            double zOffset = ThreadLocalRandom.current().nextDouble(-spawnRadius, spawnRadius);
            Location spawnLoc = playerLoc.clone().add(xOffset, 0, zOffset);

            if (plugin.isInsideClaim(spawnLoc)) {
                continue;
            }

            Location surfaceLoc = findSurfaceLocation(spawnLoc);
            if (surfaceLoc == null) continue;

            Material blockType = surfaceLoc.getBlock().getRelative(0, -1, 0).getType();
            if (blockType == Material.WATER || blockType == Material.LAVA) {
                continue;
            }

            boolean shouldRespectLightLevel = !(isDayHordeSpawn || plugin.getConfig().getBoolean("apocalypse-settings.ignore-light-level", false));
            if (shouldRespectLightLevel) {
                int lightLevelSpawn = surfaceLoc.getBlock().getLightLevel();
                int lightLevelBelow = surfaceLoc.clone().add(0, -1, 0).getBlock().getLightLevel();
                int LIGHT_THRESHOLD = 8;
                
                if (lightLevelSpawn >= LIGHT_THRESHOLD || lightLevelBelow >= LIGHT_THRESHOLD) {
                    continue;
                }
            }

            return surfaceLoc;
        }

        return null;
    }

    /**
     * Get current queue size
     */
    public int getQueueSize() {
        return spawnQueue.size();
    }
    
    /**
     * Get max spawns per tick (for PerformanceWatchdog integration)
     */
    public int getMaxSpawnsPerTick() {
        return maxSpawnsPerTick;
    }
    
    /**
     * Set max spawns per tick (for PerformanceWatchdog emergency throttling)
     */
    public void setMaxSpawnsPerTick(int maxSpawnsPerTick) {
        this.maxSpawnsPerTick = Math.max(1, Math.min(maxSpawnsPerTick, 10)); // Clamp between 1 and 10
    }

    /**
     * Spawn zombie with rising from grave effect
     */
    private Zombie spawnZombieWithRisingEffect(Location surfaceLoc, World world) {
        // Spawn 1.5 blocks underground
        Location undergroundLoc = surfaceLoc.clone().subtract(0, 1.5, 0);
        
        // Check if underground location is safe (not inside solid blocks)
        if (undergroundLoc.getBlock().getType().isSolid()) {
            // Fallback to surface spawn if underground is blocked
            return (Zombie) world.spawnEntity(surfaceLoc, EntityType.ZOMBIE);
        }
        
        // Spawn zombie underground
        Zombie zombie = (Zombie) world.spawnEntity(undergroundLoc, EntityType.ZOMBIE);
        
        // Create rising effect over 2-3 ticks
        new BukkitRunnable() {
            int ticks = 0;
            final Location targetLoc = surfaceLoc.clone();
            
            @Override
            public void run() {
                if (ticks >= 3 || zombie.isDead() || !zombie.isValid()) {
                    this.cancel();
                    return;
                }
                
                // Move zombie up gradually
                Location currentLoc = zombie.getLocation();
                double newY = currentLoc.getY() + 0.5; // Rise 0.5 blocks per tick
                zombie.teleport(new Location(currentLoc.getWorld(), currentLoc.getX(), newY, currentLoc.getZ(), currentLoc.getYaw(), currentLoc.getPitch()));
                
                // Spawn particles at ground level
                if (ticks == 0) {
                    // Initial spawn particles
                    world.spawnParticle(Particle.SMOKE, targetLoc.add(0, 0.5, 0), 10, 0.3, 0.5, 0.3, 0.05);
                    world.spawnParticle(Particle.HAPPY_VILLAGER, targetLoc.add(0, 0.5, 0), 5, 0.2, 0.3, 0.2, 0.1);
                    world.playSound(targetLoc, Sound.BLOCK_GRAVEL_BREAK, 0.5f, 0.8f);
                }
                
                ticks++;
            }
        }.runTaskTimer(plugin, 1L, 1L); // Start after 1 tick, then every tick
        
        return zombie;
    }

    /**
     * Helper method to rotate a vector by a yaw angle
     */
    private Vector rotateVector(Vector vector, float yaw) {
        double yawRad = Math.toRadians(-yaw);
        double cos = Math.cos(yawRad);
        double sin = Math.sin(yawRad);
        
        double x = vector.getX() * cos - vector.getZ() * sin;
        double z = vector.getX() * sin + vector.getZ() * cos;
        
        return new Vector(x, vector.getY(), z);
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
