package com.deleted.zombpocalypse;

import org.bukkit.entity.*;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.*;
import org.bukkit.event.entity.EntityDamageEvent.DamageCause;
import org.bukkit.event.player.*;
import org.bukkit.persistence.PersistentDataType;

import java.util.UUID;

/**
 * The single Bukkit {@link Listener} for Zombpocalypse. Every {@code @EventHandler} that used
 * to live on the monolith now lives here and delegates to the relevant manager. Keeping all
 * handlers in ONE class (rather than scattering them across managers) keeps the dependency
 * graph a star and avoids managers having to implement Listener.
 *
 * Manager references are resolved once in the constructor (all managers exist by the time the
 * listener is registered in onEnable).
 */
public class ZombpocalypseListener implements Listener {

    private final Zombpocalypse plugin;
    private final ZombpocalypseUtils utils;
    private final BloodMoonManager bloodMoon;
    private final ImmunityManager immunity;
    private final ScentManager scent;
    private final HordeManager horde;

    public ZombpocalypseListener(Zombpocalypse plugin) {
        this.plugin = plugin;
        this.utils = plugin.getUtils();
        this.bloodMoon = plugin.getBloodMoon();
        this.immunity = plugin.getImmunity();
        this.scent = plugin.getScent();
        this.horde = plugin.getHordeManager();
    }

    // === SCENT EVENTS ===

    @EventHandler
    public void onPlayerToggleSprint(PlayerToggleSprintEvent event) {
        scent.onToggleSprint(event.getPlayer(), event.isSprinting());
    }

    @EventHandler
    public void onPlayerMove(PlayerMoveEvent event) {
        scent.onMove(event.getPlayer());
    }

    @EventHandler
    public void onEntityDeath(EntityDeathEvent event) {
        if (event.getEntity() instanceof Zombie zombie) {
            ZombpocalypseUtils.ZombieType type = utils.getZombieType(zombie);
            if (type == ZombpocalypseUtils.ZombieType.BURSTER) {
                utils.cancelBursterFuse(zombie);
            }

            // CRITICAL FIX: Ensure proper cleanup on death to prevent animation bugs
            // Remove any lingering potion effects that could cause issues
            zombie.removePotionEffect(org.bukkit.potion.PotionEffectType.FIRE_RESISTANCE);
            zombie.removePotionEffect(org.bukkit.potion.PotionEffectType.SLOWNESS);
            zombie.removePotionEffect(org.bukkit.potion.PotionEffectType.SPEED);

            // Reset fire ticks to prevent post-death burning
            zombie.setFireTicks(0);
        }

        // NOTE: parity-preserved quirk — when the scent system is disabled this early return
        // ALSO skips the VETERAN promotion below (it sits after this guard in the original).
        // The refactor preserves the original behaviour verbatim rather than silently changing it.
        if (!plugin.getConfig().getBoolean("scent-system.enabled", true)) return;

        Player killer = event.getEntity().getKiller();
        if (killer != null) {
            scent.onKill(killer);
        }

        // Fixed: VETERAN transformation - check if a zombie killed the entity
        Entity deadEntity = event.getEntity();

        // Bug M3 fix: only a PLAYER kill should promote a zombie to VETERAN. Without this gate,
        // any entity killed near a zombie (farm animals, armor stands) triggered the upgrade,
        // letting players cheaply mass-promote a horde by herding passive mobs into it.
        if (deadEntity instanceof Player && deadEntity.getLastDamageCause() instanceof EntityDamageByEntityEvent damageEvent) {
            Entity damager = damageEvent.getDamager();

            // If a zombie killed this entity, transform it to veteran
            if (damager instanceof Zombie killerZombie) {
                if (plugin.getConfig().getBoolean("zombie-classes.veteran.permanent", true)) {
                    utils.transformToVeteran(killerZombie);
                    plugin.debugLog("Zombie " + killerZombie.getUniqueId() + " transformed to VETERAN after kill");
                }
            }
        }
    }

    // === PLAYER LIFECYCLE EVENTS ===

    @EventHandler
    public void onPlayerJoin(PlayerJoinEvent event) {
        Player player = event.getPlayer();

        // CRITICAL FIX: Clean up any existing bossbars for this player
        bloodMoon.cleanupBossbarForPlayer(player);

        immunity.onPlayerJoin(player);
    }

    @EventHandler
    public void onPlayerQuit(PlayerQuitEvent event) {
        Player player = event.getPlayer();
        UUID uuid = player.getUniqueId();

        immunity.onPlayerQuit(player);

        // CRITICAL FIX: Clean up blood moon bossbar when player quits
        bloodMoon.onPlayerQuit(player);

        scent.onPlayerQuit(uuid);

        immunity.save();
    }

    // === ENTITY EVENTS ===

    @EventHandler
    public void onEntitySpawn(CreatureSpawnEvent event) {
        // Bug C1/M2 fix: plugin-spawned zombies must bypass the mob-list gate and the
        // assignZombieType call here — the spawner code assigns the type itself after
        // world.spawnEntity() returns. Without this, every plugin-spawned zombie got a
        // second random type roll (overwriting the intended one), and a misconfigured
        // whitelist could silently cancel all plugin spawns.
        if (horde.isPluginSpawning()) return;

        if (!plugin.isWorldEnabled(event.getLocation().getWorld())) return;

        Entity entity = event.getEntity();
        String mobName = entity.getType().toString();
        boolean inList = plugin.getMobList().contains(mobName);

        if (plugin.isUseMobBlacklist()) {
            if (inList) { event.setCancelled(true); return; }
        } else {
            if (!inList) { event.setCancelled(true); return; }
        }

        if (entity instanceof Monster && !(entity instanceof Zombie)) {
            if (event.getSpawnReason() == CreatureSpawnEvent.SpawnReason.NATURAL) {
                event.setCancelled(true);
                return;
            }
        }

        if (entity instanceof Zombie zombie) {
            if (!plugin.isAllowBabyZombies() && zombie.getAge() < 0) { event.setCancelled(true); return; }
            if (!plugin.isAllowZombieVillagers() && zombie.getType() == EntityType.ZOMBIE_VILLAGER) { event.setCancelled(true); return; }

            // Assign zombie type
            utils.assignZombieType(zombie);
        }
    }

    @EventHandler
    public void onEntityTarget(EntityTargetLivingEntityEvent event) {
        EntityType entityType = event.getEntity().getType();

        if ((entityType == EntityType.ZOMBIE || entityType == EntityType.ZOMBIE_VILLAGER)
                && event.getTarget() instanceof Player player) {

            if (plugin.isZombieGutsEnabled() && immunity.isImmune(player.getUniqueId())) {
                event.setCancelled(true);
                if (event.getEntity() instanceof Zombie zombie) {
                    zombie.setTarget(null);
                }
                return;
            }

            // Handle BURSTER target event
            if (event.getEntity() instanceof Zombie zombie) {
                ZombpocalypseUtils.ZombieType type = utils.getZombieType(zombie);
                if (type == ZombpocalypseUtils.ZombieType.BURSTER) {
                    utils.handleBursterTarget(zombie, player);
                }
            }
        }
    }

    @EventHandler
    public void onEntityDamage(EntityDamageEvent event) {
        if (!(event.getEntity() instanceof Zombie zombie)) return;

        // CRITICAL FIX: Prevent fire damage to custom zombies
        if (event.getCause() == DamageCause.FIRE ||
            event.getCause() == DamageCause.FIRE_TICK ||
            event.getCause() == DamageCause.LAVA) {

            ZombpocalypseUtils.ZombieType type = utils.getZombieType(zombie);
            if (type != null && type != ZombpocalypseUtils.ZombieType.NORMAL) {
                // Cancel fire damage for all custom zombie types
                event.setCancelled(true);
                zombie.setFireTicks(0); // Extinguish any existing fire
                plugin.debugLog("Prevented fire damage to " + type + " zombie");
            }
        }
    }

    @EventHandler
    public void onEntityDamageByEntity(EntityDamageByEntityEvent event) {
        if (event.getDamager() instanceof Zombie zombie && event.getEntity() instanceof Player player) {
            ZombpocalypseUtils.ZombieType type = utils.getZombieType(zombie);
            if (type == null) return;

            switch (type) {
                case WEBBER -> {
                    utils.handleWebberHit(zombie, player);
                }
                case FROST -> {
                    utils.handleFrostHit(zombie, player);
                }
                default -> {
                    // No special handling for other types
                }
            }
        }
    }

    @EventHandler
    public void onPlayerConsume(PlayerItemConsumeEvent event) {
        immunity.handleGutsConsume(event);
    }

    @EventHandler
    public void onProjectileHit(ProjectileHitEvent event) {
        // Bug C3 fix: spitter fires a LlamaSpit (see tickSpitterAI), not a Snowball, and
        // tags it with ACID_SPIT_KEY/BYTE — not ZOMBIE_TYPE_KEY/STRING. Both the projectile
        // type check and the PDC read were wrong, so the poison effect never applied.
        if (!(event.getEntity() instanceof LlamaSpit spit)) return;

        Byte acidTag = spit.getPersistentDataContainer().get(ZombpocalypseUtils.ACID_SPIT_KEY, PersistentDataType.BYTE);
        if (acidTag != null) {
            if (event.getHitEntity() != null) {
                utils.handleAcidHit(event.getHitEntity());
            }
        }
    }

    @EventHandler
    public void onEntityCombust(EntityCombustEvent event) {
        if (!plugin.isWorldEnabled(event.getEntity().getWorld())) return;
        if (event.getEntity() instanceof Zombie zombie) {
            // Check zombie type and potion effects to decide combustion handling
            ZombpocalypseUtils.ZombieType type = utils.getZombieType(zombie);

            boolean hasFireResistance = zombie.getActivePotionEffects().stream()
                    .anyMatch(pe -> pe.getType() == org.bukkit.potion.PotionEffectType.FIRE_RESISTANCE);

            // If the zombie has explicit fire resistance or is a scorched type, cancel combustion
            if (type == ZombpocalypseUtils.ZombieType.SCORCHED || hasFireResistance) {
                event.setCancelled(true);
                zombie.setFireTicks(0);
                return;
            }

            // For other zombie types, prevent burning during day if config disallows daylight burning
            long time = zombie.getWorld().getTime();
            boolean isDay = time > 0 && time < 12300;
            if (isDay && !plugin.getConfig().getBoolean("zombie-settings.allow-daylight-burning", true)) {
                event.setCancelled(true);
            }
        }
    }
}
