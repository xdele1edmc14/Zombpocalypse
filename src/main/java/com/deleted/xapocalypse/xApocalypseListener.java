package com.deleted.xapocalypse;

import org.bukkit.entity.*;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.*;
import org.bukkit.event.entity.EntityDamageEvent.DamageCause;
import org.bukkit.event.player.*;
import org.bukkit.persistence.PersistentDataType;

import java.util.UUID;

/**
 * The single Bukkit {@link Listener} for xApocalypse. Every {@code @EventHandler} that used
 * to live on the monolith now lives here and delegates to the relevant manager. Keeping all
 * handlers in ONE class (rather than scattering them across managers) keeps the dependency
 * graph a star and avoids managers having to implement Listener.
 *
 * Manager references are resolved once in the constructor (all managers exist by the time the
 * listener is registered in onEnable).
 */
public class xApocalypseListener implements Listener {

    private final xApocalypse plugin;
    private final xApocalypseUtils utils;
    private final BloodMoonManager bloodMoon;
    private final ImmunityManager immunity;
    private final ScentManager scent;
    private final HordeManager horde;

    public xApocalypseListener(xApocalypse plugin) {
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
        // Grant Mutant rewards BEFORE notifyEntityDeath clears the tracking slot below.
        UUID deadId = event.getEntity().getUniqueId();
        if (plugin.getMythicMobsManager().isTrackedMutant(deadId)) {
            plugin.getDropManager().applyMutantRewards(event, event.getEntity().getKiller());
        }

        // Free a MythicMobs Mutant cap slot the moment any entity dies (cheap no-op for non-mutants).
        // Closes the cap-leak where a killed Mutant whose chunk unloaded before pruning kept its slot
        // forever, eventually filling max-global-cap with ghosts and silently stopping all spawns.
        plugin.getMythicMobsManager().notifyEntityDeath(deadId);

        if (event.getEntity() instanceof Zombie zombie) {
            xApocalypseUtils.ZombieType type = utils.getZombieType(zombie);
            if (type == xApocalypseUtils.ZombieType.BURSTER) {
                utils.cancelBursterFuse(zombie);
            }

            // CRITICAL FIX: Ensure proper cleanup on death to prevent animation bugs
            // Remove any lingering potion effects that could cause issues
            zombie.removePotionEffect(org.bukkit.potion.PotionEffectType.FIRE_RESISTANCE);
            zombie.removePotionEffect(org.bukkit.potion.PotionEffectType.SLOWNESS);
            zombie.removePotionEffect(org.bukkit.potion.PotionEffectType.SPEED);

            // Reset fire ticks to prevent post-death burning
            zombie.setFireTicks(0);

            // Rare configurable Zombie Guts drop. Inside the Zombie block (and thus BEFORE the
            // scent-system early-return below) so it works even when the scent system is disabled.
            immunity.maybeDropZombieGuts(event, zombie);

            // Configurable custom drops (separate tables for normal kills vs blood-moon kills).
            // Independent of and stacking with the Zombie Guts drop above.
            plugin.getDropManager().applyDrops(event, zombie);

            // Configurable console commands run on kill (separate normal/blood-moon tables,
            // per-entry chance, %player% placeholder). Independent of the drops above.
            plugin.getDropManager().runKillCommands(zombie);
        }

        // Bug M3 fix: scent-gain-on-kill stays gated by the scent toggle, but the VETERAN promotion
        // below must NOT be — previously a single early-return here silently disabled veteran
        // transformation whenever the scent system was turned off.
        if (plugin.getConfig().getBoolean("scent-system.enabled", true)) {
            Player killer = event.getEntity().getKiller();
            if (killer != null) {
                scent.onKill(killer);
            }
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
                    // Bug fix: defer the promotion one tick. The player's death message is rendered
                    // from the killer's display name during THIS death tick — promoting (renaming to
                    // "★ Veteran") synchronously here leaked the Veteran name into every death message,
                    // regardless of the zombie's actual class. Running it next tick lets the death
                    // message keep the original name while the zombie still becomes a Veteran.
                    org.bukkit.Bukkit.getScheduler().runTaskLater(plugin, () -> {
                        if (killerZombie.isValid() && !killerZombie.isDead()) {
                            utils.transformToVeteran(killerZombie);
                            plugin.debugLog("Zombie " + killerZombie.getUniqueId() + " transformed to VETERAN after kill");
                        }
                    }, 1L);
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
            if (!plugin.isAllowBabyZombies() && zombie.isBaby()) { event.setCancelled(true); return; }
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
                xApocalypseUtils.ZombieType type = utils.getZombieType(zombie);
                if (type == xApocalypseUtils.ZombieType.BURSTER) {
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

            xApocalypseUtils.ZombieType type = utils.getZombieType(zombie);
            if (type != null && type != xApocalypseUtils.ZombieType.NORMAL) {
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
            xApocalypseUtils.ZombieType type = utils.getZombieType(zombie);
            if (type == null) return;

            switch (type) {
                case WEBBER -> utils.handleWebberHit(zombie, player);
                case FROST -> utils.handleFrostHit(zombie, player);
                // Bug M2 fix: Scorched now actually burns its victim on hit.
                case SCORCHED -> utils.handleScorchedHit(zombie, player);
                // Bug M1 fix: Psychopath applies its configured "bleed" on hit.
                case PSYCHOPATH -> utils.handlePsychopathHit(zombie, player);
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
    public void onPlayerInteract(PlayerInteractEvent event) {
        // Primary Zombie Guts activation — a right-click works regardless of hunger level / game mode.
        immunity.handleGutsInteract(event);
    }

    @EventHandler
    public void onProjectileHit(ProjectileHitEvent event) {
        // Bug C3 fix: spitter fires a LlamaSpit (see tickSpitterAI), not a Snowball, and
        // tags it with ACID_SPIT_KEY/BYTE — not ZOMBIE_TYPE_KEY/STRING. Both the projectile
        // type check and the PDC read were wrong, so the poison effect never applied.
        if (!(event.getEntity() instanceof LlamaSpit spit)) return;

        Byte acidTag = spit.getPersistentDataContainer().get(xApocalypseUtils.ACID_SPIT_KEY, PersistentDataType.BYTE);
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
            xApocalypseUtils.ZombieType type = utils.getZombieType(zombie);

            boolean hasFireResistance = zombie.getActivePotionEffects().stream()
                    .anyMatch(pe -> pe.getType() == org.bukkit.potion.PotionEffectType.FIRE_RESISTANCE);

            // If the zombie has explicit fire resistance or is a scorched type, cancel combustion
            if (type == xApocalypseUtils.ZombieType.SCORCHED || hasFireResistance) {
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
