package com.deleted.zombpocalypse;

import io.lumine.mythic.api.mobs.MythicMob;
import io.lumine.mythic.bukkit.BukkitAPIHelper;
import io.lumine.mythic.bukkit.MythicBukkit;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;
import java.util.logging.Logger;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Player;
import org.bukkit.scheduler.BukkitRunnable;
import org.bukkit.scheduler.BukkitTask;

public class MythicMobsManager {
    private final Zombpocalypse plugin;
    private final Logger log;
    private BukkitAPIHelper mmAPI;
    private boolean mythicMobsEnabled = false;
    private final Set<UUID> activeMutants = new HashSet();
    private BukkitTask spawnTickTask = null;
    private String mobType;
    private int maxGlobalCap;
    private double spawnChance;
    private int spawnRadiusMin;
    private int spawnRadiusMax;
    private int spawnTickInterval;

    public MythicMobsManager(Zombpocalypse plugin) {
        this.plugin = plugin;
        this.log = plugin.getLogger();
        this.init();
    }

    public void init() {
        this.loadConfig();
        if (Bukkit.getPluginManager().getPlugin("MythicMobs") == null) {
            this.log.warning("[MythicMobs] MythicMobs not found — smart spawn disabled.");
            this.mythicMobsEnabled = false;
        } else {
            try {
                this.mmAPI = MythicBukkit.inst().getAPIHelper();
                Optional<MythicMob> mob = MythicBukkit.inst().getMobManager().getMythicMob(this.mobType);
                if (mob.isEmpty()) {
                    this.log.warning("[MythicMobs] Mob type '" + this.mobType + "' not found in MythicMobs.");
                } else {
                    this.log.info("[MythicMobs] Hooked in. Mob type '" + this.mobType + "' verified.");
                }

                this.mythicMobsEnabled = true;
            } catch (Exception e) {
                this.log.severe("[MythicMobs] Failed to hook into MythicMobs API: " + e.getMessage());
                this.mythicMobsEnabled = false;
            }

        }
    }

    public void loadConfig() {
        FileConfiguration cfg = this.plugin.getConfig();
        this.mobType = cfg.getString("mythicmobs.integration.mob-type", "The_Mutant");
        this.maxGlobalCap = cfg.getInt("mythicmobs.integration.max-global-cap", 15);
        // Bug B fix: raised from 0.05 (5%) to 0.20 (20%) so Mutants actually spawn during Blood Moon
        this.spawnChance = cfg.getDouble("mythicmobs.integration.spawn-chance", 0.20);
        this.spawnRadiusMin = cfg.getInt("mythicmobs.integration.spawn-radius.min", 20);
        this.spawnRadiusMax = cfg.getInt("mythicmobs.integration.spawn-radius.max", 40);
        this.spawnTickInterval = cfg.getInt("mythicmobs.integration.spawn-tick-interval", 100);
    }

    public void onBloodMoonStart() {
        if (this.mythicMobsEnabled) {
            this.log.info("[MythicMobs] Blood Moon started — spawning guaranteed Mutant.");
            this.spawnGuaranteedMutant();
            this.startSpawnTickLoop();
        }
    }

    public void onBloodMoonEnd() {
        this.stopSpawnTickLoop();
        this.pruneDeadMutants();
        this.log.info("[MythicMobs] Blood Moon ended. Mutants still alive: " + this.activeMutants.size());
    }

    public int spawnMutantCommand(Player player, int count, int radius) {
        if (!this.mythicMobsEnabled) {
            player.sendMessage("§c[MythicMobs] MythicMobs is not enabled on this server.");
            return 0;
        } else {
            this.pruneDeadMutants();
            int spawned = 0;

            for(int i = 0; i < count; ++i) {
                if (this.activeMutants.size() >= this.maxGlobalCap) {
                    player.sendMessage("§e[MythicMobs] Global cap reached.");
                    break;
                }

                Location loc = this.findSpawnLocation(player.getLocation(), radius, radius + 2);
                if (loc != null) {
                    Entity entity = this.spawnMythicMob(loc);
                    if (entity != null) {
                        ++spawned;
                    }
                }
            }

            return spawned;
        }
    }

    private void spawnGuaranteedMutant() {
        List<? extends Player> online = Bukkit.getOnlinePlayers().stream().filter((p) -> this.plugin.isWorldEnabled(p.getWorld())).toList();
        if (!online.isEmpty()) {
            this.pruneDeadMutants();
            if (this.maxGlobalCap <= 0 || this.activeMutants.size() < this.maxGlobalCap) {
                Player target = (Player)online.get(ThreadLocalRandom.current().nextInt(online.size()));
                Location loc = this.findSpawnLocation(target.getLocation(), this.spawnRadiusMin, this.spawnRadiusMax);
                if (loc == null) {
                    loc = target.getLocation().add((double)ThreadLocalRandom.current().nextInt(-5, 5), (double)0.0F, (double)ThreadLocalRandom.current().nextInt(-5, 5));
                }

                Entity entity = this.spawnMythicMob(loc);
                if (entity != null) {
                    this.broadcastBloodMoonSpawn(target);
                }

            }
        }
    }

    private void startSpawnTickLoop() {
        this.stopSpawnTickLoop();
        this.spawnTickTask = (new BukkitRunnable() {
            public void run() {
                // Use the first enabled world as the reference — same as startBloodMoonTask()
                World world = Bukkit.getWorlds().stream()
                        .filter(w -> MythicMobsManager.this.plugin.isWorldEnabled(w))
                        .findFirst().orElse(null);

                // Delegate the active check entirely to the plugin — it already handles
                // forced blood moon real-time elapsed vs natural night-time checks.
                if (world == null || !MythicMobsManager.this.plugin.isBloodMoonActive(world)) {
                    // Blood moon has ended or never started — shut down the loop.
                    MythicMobsManager.this.stopSpawnTickLoop();
                    return;
                }
                MythicMobsManager.this.pruneDeadMutants();
                if (MythicMobsManager.this.activeMutants.size() < MythicMobsManager.this.maxGlobalCap || MythicMobsManager.this.maxGlobalCap <= 0) {
                    for(Player player : Bukkit.getOnlinePlayers()) {
                        if (MythicMobsManager.this.plugin.isWorldEnabled(player.getWorld())) {
                            if (MythicMobsManager.this.activeMutants.size() >= MythicMobsManager.this.maxGlobalCap && MythicMobsManager.this.maxGlobalCap > 0) {
                                break;
                            }

                            if (ThreadLocalRandom.current().nextDouble() < MythicMobsManager.this.spawnChance) {
                                Location loc = MythicMobsManager.this.findSpawnLocation(player.getLocation(), MythicMobsManager.this.spawnRadiusMin, MythicMobsManager.this.spawnRadiusMax);
                                if (loc != null) {
                                    MythicMobsManager.this.spawnMythicMob(loc);
                                }
                            }
                        }
                    }

                }
            }
        }).runTaskTimer(this.plugin, (long)this.spawnTickInterval, (long)this.spawnTickInterval);
    }

    private void stopSpawnTickLoop() {
        if (this.spawnTickTask != null && !this.spawnTickTask.isCancelled()) {
            this.spawnTickTask.cancel();
            this.spawnTickTask = null;
        }

    }

    private Entity spawnMythicMob(Location loc) {
        loc = this.snapToGround(loc);
        if (loc == null) {
            return null;
        } else {
            try {
                Entity entity = this.mmAPI.spawnMythicMob(this.mobType, loc);
                if (entity != null) {
                    this.activeMutants.add(entity.getUniqueId());
                    Zombpocalypse var10000 = this.plugin;
                    String var5 = this.formatLoc(loc);
                    var10000.debugLog("[MythicMobs] Spawned at " + var5);
                }

                return entity;
            } catch (Exception e) {
                String var10001 = this.mobType;
                this.log.warning("[MythicMobs] Failed to spawn " + var10001 + ": " + e.getMessage());
                return null;
            }
        }
    }

    private Location findSpawnLocation(Location anchor, int minRadius, int maxRadius) {
        Player nearestPlayer = this.getNearestPlayer(anchor);
        ThreadLocalRandom rng = ThreadLocalRandom.current();

        for(int attempt = 0; attempt < 15; ++attempt) {
            double angle = rng.nextDouble((double)0.0F, (Math.PI * 2D));
            double dist = rng.nextDouble((double)minRadius, (double)maxRadius);
            double x = anchor.getX() + Math.cos(angle) * dist;
            double z = anchor.getZ() + Math.sin(angle) * dist;
            Location candidate = new Location(anchor.getWorld(), x, anchor.getY(), z);
            candidate = this.snapToGround(candidate);
            if (candidate != null && !this.plugin.isInsideClaim(candidate) && (nearestPlayer == null || !this.hasLineOfSight(nearestPlayer, candidate) || attempt >= 10)) {
                return candidate;
            }
        }

        return null;
    }

    // Bug A fix: use getHighestBlockYAt so terrain above the player's Y level is found correctly.
    // The old scan-down approach started from the player's Y, missing hills and causing null returns.
    private Location snapToGround(Location loc) {
        World world = loc.getWorld();
        if (world == null) return null;

        int x = loc.getBlockX();
        int z = loc.getBlockZ();
        
        // Start at the very top of the world terrain, not the player's level
        int highestY = world.getHighestBlockYAt(x, z);
        
        Block highestBlock = world.getBlockAt(x, highestY, z);
        // Optional: prevent the boss from spawning in the middle of an ocean or lava lake
        if (highestBlock.getType() == Material.WATER || highestBlock.getType() == Material.LAVA) {
            return null; 
        }

        // Return the location one block above the highest solid block
        return new Location(world, x + 0.5, highestY + 1, z + 0.5, loc.getYaw(), loc.getPitch());
    }

    private boolean hasLineOfSight(Player player, Location target) {
        if (target == null) {
            return false;
        } else {
            try {
                return player.hasLineOfSight(target);
            } catch (Exception var4) {
                return false;
            }
        }
    }

    private Player getNearestPlayer(Location loc) {
        Player nearest = null;
        double nearestDist = Double.MAX_VALUE;

        for(Player p : Bukkit.getOnlinePlayers()) {
            if (p.getWorld().equals(loc.getWorld())) {
                double d = p.getLocation().distanceSquared(loc);
                if (d < nearestDist) {
                    nearestDist = d;
                    nearest = p;
                }
            }
        }

        return nearest;
    }

    // Bug C fix: Bukkit.getEntity() returns null when the entity's chunk is unloaded, not just when dead.
    // Treating null as "dead" caused the cap to be bypassed whenever players ran far enough away.
    private void pruneDeadMutants() {
        this.activeMutants.removeIf((uuid) -> {
            Entity entity = Bukkit.getEntity(uuid);
            return entity == null || entity.isDead();
        });
    }

    private void broadcastBloodMoonSpawn(Player nearPlayer) {
        for(Player p : Bukkit.getOnlinePlayers()) {
            if (this.plugin.isWorldEnabled(p.getWorld())) {
                p.sendMessage("§4§l☠ §cA Mutant has emerged near §f" + nearPlayer.getName() + "§c!");
            }
        }

    }

    private String formatLoc(Location loc) {
        return String.format("(%.1f, %.1f, %.1f)", loc.getX(), loc.getY(), loc.getZ());
    }

    public boolean isMythicMobsEnabled() {
        return this.mythicMobsEnabled;
    }

    public int getActiveMutantCount() {
        this.pruneDeadMutants();
        return this.activeMutants.size();
    }

    public int getMaxGlobalCap() {
        return this.maxGlobalCap;
    }

    public String getMobType() {
        return this.mobType;
    }
}