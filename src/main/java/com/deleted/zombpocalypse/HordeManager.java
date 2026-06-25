package com.deleted.zombpocalypse;

import org.bukkit.Bukkit;
import org.bukkit.GameMode;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.block.BlockFace;
import org.bukkit.block.data.BlockData;
import org.bukkit.entity.Entity;
import org.bukkit.entity.EntityType;
import org.bukkit.entity.Player;
import org.bukkit.entity.Zombie;
import org.bukkit.scheduler.BukkitRunnable;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;

/**
 * Owns the core horde-spawning mechanics: the per-player horde spawn math (blood-moon and
 * scent multipliers, surface snapping, rising animation), builder-block tracking + auto
 * cleanup, the zombie AI tick loop (gated by the LOD system), and the {@code isPluginSpawning}
 * flag that lets plugin-spawned zombies bypass the CreatureSpawnEvent mob-list gate.
 *
 * Extracted verbatim from the original Zombpocalypse monolith (Bug M1 single-horde cap,
 * Bug C1/M2/20 plugin-spawning bracketing, Bug 19 animating-tag handling preserved).
 */
public class HordeManager {

    private final Zombpocalypse plugin;
    private final ZombpocalypseUtils utils;
    private final UndeadSpawner undeadSpawner;

    // Bug 4 & 20 fix: flag so onEntitySpawn bypasses the mob-list check for plugin-spawned entities
    private boolean isPluginSpawning = false;

    // --- BUILDER BLOCK TRACKING ---
    private final Map<Location, Long> builderBlocks = new HashMap<>(); // Location -> Timestamp
    private final Map<Location, UUID> builderBlockOwners = new HashMap<>(); // Location -> Zombie UUID

    public HordeManager(Zombpocalypse plugin, ZombpocalypseUtils utils, UndeadSpawner undeadSpawner) {
        this.plugin = plugin;
        this.utils = utils;
        this.undeadSpawner = undeadSpawner;
    }

    /** Called by UndeadSpawner before/after world.spawnEntity so onEntitySpawn skips the mob-list check. */
    public void setPluginSpawning(boolean value) {
        this.isPluginSpawning = value;
    }

    public boolean isPluginSpawning() {
        return isPluginSpawning;
    }

    // === BUILDER BLOCK TRACKING ===

    public void trackBuilderBlock(Location loc, UUID zombieUUID) {
        builderBlocks.put(loc, System.currentTimeMillis());
        builderBlockOwners.put(loc, zombieUUID);
    }

    public void startBuilderCleanupTask() {
        new BukkitRunnable() {
            @Override
            public void run() {
                if (builderBlocks.isEmpty()) return;

                long now = System.currentTimeMillis();
                int cleanupSeconds = plugin.getConfig().getInt("cleanup.builder-auto-cleanup-seconds", 300);
                long cleanupMs = cleanupSeconds * 1000L;

                List<Location> toRemove = new ArrayList<>();

                for (Map.Entry<Location, Long> entry : builderBlocks.entrySet()) {
                    if (now - entry.getValue() >= cleanupMs) {
                        Location loc = entry.getKey();
                        if (loc.getBlock().getType() == Material.DIRT) {
                            loc.getBlock().setType(Material.AIR);
                            plugin.debugLog("Cleaned up builder block at " + loc);
                        }
                        toRemove.add(loc);
                    }
                }

                for (Location loc : toRemove) {
                    builderBlocks.remove(loc);
                    builderBlockOwners.remove(loc);
                }
            }
        }.runTaskTimer(plugin, 0L, 100L); // Check every 5 seconds
    }

    // === AI TICK SYSTEM ===

    public void startAITickTask() {
        new BukkitRunnable() {
            @Override
            public void run() {
                for (World world : Bukkit.getWorlds()) {
                    if (!plugin.isWorldEnabled(world)) continue;

                    for (Entity entity : world.getEntitiesByClass(Zombie.class)) {
                        if (entity instanceof Zombie zombie) {
                            // LOD System: Only tick AI if zombie is close or LOD system allows it
                            if (plugin.getPerformanceWatchdog() == null || plugin.getPerformanceWatchdog().shouldTickZombieAI(zombie)) {
                                utils.tickZombieAI(zombie);
                            }
                        }
                    }
                }
            }
        }.runTaskTimer(plugin, 0L, 10L); // Tick every 0.5 seconds
    }

    // === CORE HORDE SPAWNING ===

    public void spawnZombiesNearPlayer(Player player, boolean isDayHordeSpawn) {
        // Guard clause: only spawn for SURVIVAL mode players, prevent Elytra spawns
        if (player.getGameMode() != GameMode.SURVIVAL || player.isGliding() || player.isFlying()) {
            return;
        }

        int baseAmount = plugin.getConfig().getInt("apocalypse-settings.base-horde-size", 6);
        int variance = plugin.getConfig().getInt("apocalypse-settings.horde-variance", 4);

        // Day spawns use a separate (smaller) horde size so daytime isn't as brutal as night.
        // isDayHordeSpawn was passed all the way from HordeSpawnerTask but was never consumed — fixed.
        if (isDayHordeSpawn) {
            baseAmount = plugin.getConfig().getInt("apocalypse-settings.day-horde-size", Math.max(1, baseAmount / 3));
            variance   = plugin.getConfig().getInt("apocalypse-settings.day-horde-variance", Math.max(0, variance / 2));
        }

        World world = player.getWorld();
        int safeVariance = Math.max(0, variance);

        double multiplier = 1.0;

        // Blood Moon multiplier
        if (plugin.isBloodMoonActive(world)) {
            multiplier = plugin.getBloodMoonHordeMultiplier();
        }

        // Scent multiplier
        if (plugin.getConfig().getBoolean("scent-system.enabled", true)) {
            double scent = plugin.getPlayerScent(player.getUniqueId());
            double scentScale = plugin.getConfig().getDouble("scent-system.scent-scale", 15.0);
            multiplier *= (1.0 + (scent / scentScale));
        }

        // Apply multiplier BEFORE calculating final amount
        int finalHordeSize = (int) ((baseAmount + ThreadLocalRandom.current().nextInt(safeVariance + 1)) * multiplier);

        // Bug M1 fix: cap a single player's horde here, BEFORE the spawn loop. The blood moon ×
        // scent multipliers could push this to the global max-total-zombies (e.g. 300), and that
        // loop runs per eligible player per spawner tick — thousands of getHighestBlockAt() calls
        // that tank TPS independent of actual entity count. The global cap stays as a secondary guard.
        int maxSingleHorde = plugin.getConfig().getInt("apocalypse-settings.max-single-horde-size", 30);
        finalHordeSize = Math.min(finalHordeSize, maxSingleHorde);

        // Cap with max-total-zombies instead of scent-system.spawn-cap
        int spawnCap = plugin.getConfig().getInt("performance.max-total-zombies", 300);
        finalHordeSize = Math.min(finalHordeSize, spawnCap);

        plugin.debugLog("Attempting to spawn horde of size: " + finalHordeSize + " near " + player.getName() + " (Multiplier: " + multiplier + ")");

        int spawnRadius = plugin.getConfig().getInt("apocalypse-settings.spawn-radius", 35);

        int skipped = 0;
        for (int i = 0; i < finalHordeSize; i++) {
            double xOffset = ThreadLocalRandom.current().nextDouble(-spawnRadius, spawnRadius);
            double zOffset = ThreadLocalRandom.current().nextDouble(-spawnRadius, spawnRadius);
            Location spawnLoc = player.getLocation().clone().add(xOffset, 0, zOffset);

            Location surface = undeadSpawner.getSurfaceSpawnLocation(spawnLoc);

            // Retry once with a fresh random offset before giving up — reduces wasted
            // attempts near water, ravines, or ocean biomes.
            if (surface == null) {
                xOffset = ThreadLocalRandom.current().nextDouble(-spawnRadius, spawnRadius);
                zOffset = ThreadLocalRandom.current().nextDouble(-spawnRadius, spawnRadius);
                spawnLoc = player.getLocation().clone().add(xOffset, 0, zOffset);
                surface = undeadSpawner.getSurfaceSpawnLocation(spawnLoc);
            }

            if (surface == null) {
                skipped++;
                continue;
            }

            Block surfaceBlock = surface.getBlock().getRelative(BlockFace.DOWN);
            BlockData surfaceData = surfaceBlock.getBlockData();

            boolean risingAnimation = plugin.getConfig().getBoolean("apocalypse-settings.rising-animation", true);

            if (risingAnimation) {
                long startDelayTicks = i % 5L;
                undeadSpawner.trySpawnUndeadRise(surface, surfaceBlock, surfaceData, startDelayTicks);
            } else {
                isPluginSpawning = true;
                Zombie zombie = (Zombie) surface.getWorld().spawnEntity(surface, EntityType.ZOMBIE);
                isPluginSpawning = false;
                if (zombie != null) utils.assignZombieType(zombie);
            }
        }

        // Single summary log instead of one line per failed attempt
        if (skipped > 0) {
            plugin.debugLog("Horde near " + player.getName() + ": " + (finalHordeSize - skipped) + "/" + finalHordeSize + " spawned (" + skipped + " skipped - no valid surface)");
        }
    }
}
