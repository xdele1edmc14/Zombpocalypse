package com.deleted.xapocalypse;

import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.Particle;
import org.bukkit.Sound;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.block.BlockFace;
import org.bukkit.block.data.BlockData;
import org.bukkit.entity.EntityType;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Player;
import org.bukkit.entity.Zombie;
import org.bukkit.GameMode;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.scheduler.BukkitRunnable;

import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

public final class UndeadSpawner {

    private static final int ANIMATION_TICKS = 20;
    private static final double RISE_PER_TICK = 0.06;
    private static final double START_BELOW_SURFACE_Y = 1.2;

    private static final long PER_PLAYER_COOLDOWN_MS = 3000L;
    private static final long PER_BLOCK_COOLDOWN_MS = 2000L;

    private static final int MAX_CONCURRENT_ANIMATIONS = 50;

    private final xApocalypse plugin;
    private final xApocalypseUtils utils;

    private final Map<UUID, Long> lastSpawnByPlayerMs = new HashMap<>();
    private final Map<Long, Long> activeOrRecentByBlockMs = new HashMap<>();
    private final Set<UUID> activeAnimationEntities = new HashSet<>();

    public UndeadSpawner(xApocalypse plugin, xApocalypseUtils utils) {
        this.plugin = plugin;
        this.utils = utils;
    }

    public Zombie trySpawnUndeadRise(Player player) {
        if (player == null) return null;
        return trySpawnUndeadRise(player, player.getLocation());
    }

    public Zombie trySpawnUndeadRise(Player player, Location target) {
        if (player == null || target == null) return null;

        if (player.hasPermission("xapocalypse.admin")) return null;
        if (player.getGameMode() != GameMode.SURVIVAL) return null;
        if (player.isFlying() || player.isGliding()) return null;

        long now = System.currentTimeMillis();
        Long last = lastSpawnByPlayerMs.get(player.getUniqueId());
        if (last != null && (now - last) < PER_PLAYER_COOLDOWN_MS) return null;

        Zombie zombie = trySpawnUndeadRise(target);
        if (zombie != null) {
            lastSpawnByPlayerMs.put(player.getUniqueId(), now);
        }
        return zombie;
    }

    public Zombie trySpawnUndeadRise(Location target) {
        if (target == null) return null;

        World world = target.getWorld();
        if (world == null) return null;

        Location surface = getSurfaceSpawnLocation(target);
        if (surface == null) return null;

        Block surfaceBlock = surface.getBlock().getRelative(BlockFace.DOWN);
        BlockData surfaceData = surfaceBlock.getBlockData();

        return trySpawnUndeadRise(surface, surfaceBlock, surfaceData);
    }

    public Zombie trySpawnUndeadRise(Location surface, Block surfaceBlock, BlockData surfaceData) {
        return trySpawnUndeadRise(surface, surfaceBlock, surfaceData, 0L);
    }

    public Zombie trySpawnUndeadRise(Location surface, Block surfaceBlock, BlockData surfaceData, long startDelayTicks) {
        if (surface == null || surfaceBlock == null || surfaceData == null) return null;

        World world = surface.getWorld();
        if (world == null) return null;

        if (!isValidSurface(surfaceBlock)) return null;
        if (activeAnimationEntities.size() >= MAX_CONCURRENT_ANIMATIONS) return null;

        long blockKey = packBlockKey(world, surfaceBlock);
        long now = System.currentTimeMillis();

        if (activeOrRecentByBlockMs.size() > 2048) {
            long cutoff = now - (PER_BLOCK_COOLDOWN_MS * 2);
            activeOrRecentByBlockMs.entrySet().removeIf(e -> e.getValue() < cutoff);
        }

        Long recent = activeOrRecentByBlockMs.get(blockKey);
        if (recent != null && (now - recent) < PER_BLOCK_COOLDOWN_MS) return null;

        activeOrRecentByBlockMs.put(blockKey, now);

        Location spawnLoc = surface.clone().add(0.0, -START_BELOW_SURFACE_Y, 0.0);
        // Bug 20 fix: set the plugin-spawning flag so onEntitySpawn skips the mob-list check
        // and does not call assignZombieType again (we call it ourselves below).
        Zombie zombie;
        plugin.setPluginSpawning(true);
        try {
            zombie = (Zombie) world.spawnEntity(spawnLoc, EntityType.ZOMBIE);
        } finally {
            plugin.setPluginSpawning(false);
        }
        if (zombie == null) {
            activeOrRecentByBlockMs.remove(blockKey);
            return null;
        }

        utils.assignZombieType(zombie);

        activeAnimationEntities.add(zombie.getUniqueId());
        runDigUpAnimation(zombie, surface, surfaceData, surfaceBlock, blockKey, Math.max(0L, startDelayTicks));
        return zombie;
    }

    private void runDigUpAnimation(LivingEntity entity, Location surface, BlockData surfaceBlockData, Block surfaceBlock, long blockKey, long startDelayTicks) {
        if (!(entity instanceof Zombie zombie)) {
            entity.remove();
            activeOrRecentByBlockMs.remove(blockKey);
            return;
        }

        zombie.setAI(false);
        zombie.setGravity(false);
        zombie.setInvulnerable(true);
        // Bug 19 fix: mark as animating so the LOD system's setAI(false) doesn't fight the animation
        zombie.getPersistentDataContainer().set(xApocalypseUtils.ANIMATING_KEY,
                org.bukkit.persistence.PersistentDataType.BYTE, (byte) 1);

        Location working = surface.clone().add(0.0, -START_BELOW_SURFACE_Y, 0.0);

        BukkitRunnable timer = new BukkitRunnable() {
            int ticks = 0;

            @Override
            public void run() {
                if (!zombie.isValid() || zombie.isDead()) {
                    activeOrRecentByBlockMs.remove(blockKey);
                    activeAnimationEntities.remove(zombie.getUniqueId());
                    cancel();
                    return;
                }

                if (!isValidSurface(surfaceBlock)) {
                    finalizeZombie(zombie);
                    activeOrRecentByBlockMs.remove(blockKey);
                    activeAnimationEntities.remove(zombie.getUniqueId());
                    cancel();
                    return;
                }

                working.setY(working.getY() + RISE_PER_TICK);
                zombie.teleport(working.clone());

                World w = zombie.getWorld();
                Location particleLoc = zombie.getLocation().clone().add(0, 0.2, 0);
                w.spawnParticle(Particle.BLOCK, particleLoc, 8, 0.25, 0.1, 0.25, 0.02, surfaceBlockData);
                w.playSound(particleLoc, Sound.BLOCK_GRAVEL_BREAK, 0.6f, 0.7f);

                ticks++;
                if (ticks >= ANIMATION_TICKS) {
                    zombie.teleport(surface.clone());
                    finalizeZombie(zombie);
                    activeOrRecentByBlockMs.remove(blockKey);
                    activeAnimationEntities.remove(zombie.getUniqueId());
                    cancel();
                }
            }
        };

        if (startDelayTicks > 0L) {
            new BukkitRunnable() {
                @Override
                public void run() {
                    if (!zombie.isValid() || zombie.isDead()) {
                        activeOrRecentByBlockMs.remove(blockKey);
                        activeAnimationEntities.remove(zombie.getUniqueId());
                        return;
                    }
                    timer.runTaskTimer(plugin, 0L, 1L);
                }
            }.runTaskLater(plugin, startDelayTicks);
        } else {
            timer.runTaskTimer(plugin, 0L, 1L);
        }
    }

    private void finalizeZombie(Zombie zombie) {
        if (!zombie.isValid() || zombie.isDead()) return;
        // Bug 19 fix: remove the animating tag before re-enabling AI so the LOD system takes over normally
        zombie.getPersistentDataContainer().remove(xApocalypseUtils.ANIMATING_KEY);
        zombie.setInvulnerable(false);
        zombie.setGravity(true);
        zombie.setAI(true);
    }

    /**
     * Bug C2 fix: finalize every zombie left mid rise-animation. The animation timers are plugin
     * tasks, so a {@code /xa reload} or server shutdown calls {@code cancelTasks(this)} and kills
     * them WITHOUT running {@link #finalizeZombie}, which strands the zombie permanently
     * invulnerable, AI-off, gravity-off and still tagged {@code ANIMATING_KEY} — and the LOD system
     * then refuses to re-enable its AI, so it survives restarts as a frozen, unkillable mob.
     *
     * Sweeping every world for the animating tag (rather than only the in-memory tracker) also
     * recovers orphans left by a previous crash. Then the concurrency trackers are cleared so a
     * future reload can't leak toward {@code MAX_CONCURRENT_ANIMATIONS} and disable rises entirely.
     * Safe to call repeatedly.
     */
    public void finalizeAllAnimations() {
        for (World world : Bukkit.getWorlds()) {
            for (Zombie zombie : world.getEntitiesByClass(Zombie.class)) {
                if (zombie.getPersistentDataContainer().has(xApocalypseUtils.ANIMATING_KEY, PersistentDataType.BYTE)) {
                    finalizeZombie(zombie);
                }
            }
        }
        activeAnimationEntities.clear();
        activeOrRecentByBlockMs.clear();
        lastSpawnByPlayerMs.clear();
    }

    private boolean isValidSurface(Block surfaceBlock) {
        if (surfaceBlock == null) return false;
        // In the Nether the highest block is normally the bedrock ceiling. Treating bedrock as a
        // valid floor placed hordes on top of the roof instead of in the cavern with the player.
        if (surfaceBlock.getType() == org.bukkit.Material.BEDROCK) return false;
        if (!surfaceBlock.getType().isSolid()) return false;
        if (!surfaceBlock.getType().isOccluding()) return false;
        if (surfaceBlock.isLiquid()) return false;

        Block above = surfaceBlock.getRelative(BlockFace.UP);
        if (!above.getType().isAir()) return false;

        Block above2 = above.getRelative(BlockFace.UP);
        return above2.getType().isAir();
    }

    Location getSurfaceSpawnLocation(Location target) {
        if (target == null) return null;
        World world = target.getWorld();
        if (world == null) return null;

        // Bug fix: getHighestBlockAt() returns the single top block of the column, which in
        // forests/jungles/swamps is leaves, and in tall-grass/flower/snow terrain is the plant —
        // none of which pass isValidSurface(). That made every spawn attempt return null wherever
        // /rtp dropped the player into natural (non-cleared) terrain, producing zero spawns, while
        // the cleared /mvtp world-spawn worked. Scan downward from the top to the first real
        // standing surface so we land on the ground beneath the canopy instead of giving up.
        int targetY = target.getBlockY();
        int topY = Math.min(world.getHighestBlockAt(target).getY(), targetY + 48);
        int x = target.getBlockX();
        int z = target.getBlockZ();
        int floorY = Math.max(world.getMinHeight(), targetY - 48);

        for (int y = topY; y >= floorY; y--) {
            Block candidate = world.getBlockAt(x, y, z);
            if (isValidSurface(candidate)) {
                return candidate.getLocation().add(0.5, 1.0, 0.5);
            }
        }
        return null;
    }

    private long packBlockKey(World world, Block block) {
        int x = block.getX();
        int y = block.getY();
        int z = block.getZ();

        long key = (((long) x) & 0x3FFFFFFL) << 38;
        key |= (((long) z) & 0x3FFFFFFL) << 12;
        key |= ((long) y) & 0xFFFL;

        return key ^ world.getUID().getLeastSignificantBits();
    }
}
