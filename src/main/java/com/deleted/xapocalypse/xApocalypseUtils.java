package com.deleted.xapocalypse;

import org.bukkit.*;
import org.bukkit.attribute.Attribute;
import org.bukkit.block.Block;
import org.bukkit.block.data.BlockData;
import org.bukkit.entity.*;
import org.bukkit.inventory.ItemStack;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.potion.PotionEffect;
import org.bukkit.potion.PotionEffectType;
import org.bukkit.scheduler.BukkitRunnable;
import org.bukkit.util.Vector;

import java.util.*;
import java.util.concurrent.ThreadLocalRandom;

public class xApocalypseUtils {

    private final xApocalypse plugin;

    public static final NamespacedKey ZOMBIE_TYPE_KEY = new NamespacedKey("xapocalypse", "zombie_type");
    public static final NamespacedKey LAST_HEAL_KEY = new NamespacedKey("xapocalypse", "last_heal");
    public static final NamespacedKey LAST_BREAK_KEY = new NamespacedKey("xapocalypse", "last_break");
    public static final NamespacedKey LAST_SPIT_KEY = new NamespacedKey("xapocalypse", "last_spit");
    public static final NamespacedKey LAST_RAGE_KEY = new NamespacedKey("xapocalypse", "last_rage");
    public static final NamespacedKey LAST_WEB_KEY = new NamespacedKey("xapocalypse", "last_web");
    public static final NamespacedKey BURSTER_PRIMED_KEY = new NamespacedKey("xapocalypse", "burster_primed");
    // Bug 2 fix: dedicated PDC key for acid spit projectiles (ZOMBIE_TYPE_KEY belongs to zombie entities, not projectiles)
    public static final NamespacedKey ACID_SPIT_KEY = new NamespacedKey("xapocalypse", "acid_spit");
    // Bug 19 fix: marks a zombie as mid-rise-animation so the LOD system won't call setAI(false) on it
    public static final NamespacedKey ANIMATING_KEY = new NamespacedKey("xapocalypse", "animating");
    // Marker placed on the Zombie Guts ItemStack so it is identified by PDC rather than display name.
    public static final NamespacedKey ZOMBIE_GUTS_KEY = new NamespacedKey("xapocalypse", "zombie_guts_item");
    // Marks a zombie that was spawned/typed during an active blood moon, so it can be despawned
    // when the blood moon ends (see despawnBloodMoonZombies). Zombies present before the event are untagged.
    public static final NamespacedKey BLOOD_MOON_KEY = new NamespacedKey("xapocalypse", "blood_moon");

    public enum ZombieType {
        SWARMER, MINER, NURSE, PSYCHOPATH, SCORCHED, TANK, RUNNER, SPITTER, VETERAN, WEBBER, BURSTER, FROST, NORMAL;
    }

    private final Map<ZombieType, Double> spawnWeights = new HashMap<>();
    private double totalWeight = 0.0;

    private final Map<UUID, BukkitRunnable> activeBursterFuses = new HashMap<>();
    private final Map<Block, BlockData> temporaryWebBlocks = new HashMap<>();

    public xApocalypseUtils(xApocalypse plugin) {
        this.plugin = plugin;
        loadWeights();
    }

    private void loadWeights() {
        spawnWeights.clear();
        totalWeight = 0.0;
        for (ZombieType type : ZombieType.values()) {
            // VETERAN is not spawned randomly (it is transformed from kills, never rolled)
            if (type == ZombieType.VETERAN || type == ZombieType.NORMAL) continue;
            // Bug M4 fix: honor per-class enabled flags so disabling a class removes it from the
            // random spawn pool (previously these flags were never read).
            if (!isClassEnabled(type)) continue;
            double weight = Math.max(0.0,
                    plugin.getConfig().getDouble("zombie-classes.weights." + type.name(), 0.1));
            if (weight == 0.0) continue;
            spawnWeights.put(type, weight);
            totalWeight += weight;
        }
    }

    /** True unless the class carries an explicit {@code zombie-classes.<class>.enabled: false} flag. */
    private boolean isClassEnabled(ZombieType type) {
        String key = switch (type) {
            case MINER -> "zombie-classes.miner.enabled";
            case NURSE -> "zombie-classes.nurse.enabled";
            case SPITTER -> "zombie-classes.spitter.enabled";
            case SCORCHED -> "zombie-classes.scorched.enabled";
            default -> null;
        };
        return key == null || plugin.getConfig().getBoolean(key, true);
    }

    public void reloadWeights() { loadWeights(); }

    public void assignZombieType(Zombie zombie) {
        if (!plugin.getConfig().getBoolean("zombie-classes.enabled", true)) return;
        applyZombieType(zombie, getRandomZombieType());
    }

    private ZombieType getRandomZombieType() {
        // Bug 14 fix: guard against zero/empty weights
        if (totalWeight <= 0 || spawnWeights.isEmpty()) return ZombieType.NORMAL;
        double random = ThreadLocalRandom.current().nextDouble() * totalWeight;
        double cumulative = 0.0;
        for (Map.Entry<ZombieType, Double> entry : spawnWeights.entrySet()) {
            cumulative += entry.getValue();
            // Bug 14 fix: strict < instead of <= eliminates last-entry boundary bias
            if (random < cumulative) return entry.getKey();
        }
        // Floating-point edge case fallback — return last entry rather than a hardcoded type
        return spawnWeights.entrySet().iterator().next().getKey();
    }

    public void applyZombieType(Zombie zombie, ZombieType type) {
        zombie.getPersistentDataContainer().set(ZOMBIE_TYPE_KEY, PersistentDataType.STRING, type.name());
        // Tag zombies typed during an active blood moon so they can be cleaned up when it ends.
        // Guard on type != VETERAN: veteran promotion (transformToVeteran) re-runs this method on an
        // already-living zombie. Without the guard, a zombie that existed BEFORE the blood moon and
        // promotes to Veteran mid-event would be freshly tagged and then wrongly despawned at event
        // end — violating the "leave pre-existing zombies alone" contract. The tag is only stamped
        // at initial typing (every spawn path passes a non-VETERAN type first).
        if (type != ZombieType.VETERAN && plugin.isBloodMoonActive(zombie.getWorld())) {
            zombie.getPersistentDataContainer().set(BLOOD_MOON_KEY, PersistentDataType.BYTE, (byte) 1);
        }
        applyZombieHead(zombie, type);
        applyZombieStats(zombie, type);
    }

    private void applyZombieHead(Zombie zombie, ZombieType type) {
        // Minor fix: honor visuals.use-nametags (previously ignored — zombies always got a nametag).
        if (!plugin.getConfig().getBoolean("visuals.use-nametags", true)) {
            zombie.setCustomName(null);
            zombie.setCustomNameVisible(false);
            return;
        }
        zombie.setCustomName(getZombieDisplayName(type));
        zombie.setCustomNameVisible(plugin.getConfig().getBoolean("visuals.nametag-always-visible", true));
    }

    private String getZombieDisplayName(ZombieType type) {
        return switch (type) {
            case NORMAL -> "§8Zombie";
            case SWARMER -> "§7⚔ Swarmer";
            case MINER -> "§6⛏ Miner";
            case NURSE -> "§d❤ Nurse";
            case RUNNER -> "§b⚡ Runner";
            case SPITTER -> "§a☠ Spitter";
            case PSYCHOPATH -> "§c⚔ Psychopath";
            case SCORCHED -> "§4🔥 Scorched";
            case TANK -> "§8⛨ Tank";
            case VETERAN -> "§e★ Veteran";
            case WEBBER -> "§8🕸 Webber";
            case BURSTER -> "§c💣 Burster";
            case FROST -> "§b❄ Frost";
            default -> "§7Zombie";
        };
    }

    private void applyZombieStats(Zombie zombie, ZombieType type) {
        double baseHealth = plugin.getConfig().getDouble("zombie-settings.health", 25.0);
        double baseDamage = plugin.getConfig().getDouble("zombie-settings.damage", 6.0);
        double baseSpeed = plugin.getConfig().getDouble("zombie-settings.speed", 0.32);

        boolean isBloodMoon = plugin.isBloodMoonActive(zombie.getWorld());
        if (isBloodMoon) {
            baseHealth *= plugin.getBloodMoon().getHealthMult();
            baseDamage *= plugin.getBloodMoon().getDamageMult();
            baseSpeed *= plugin.getBloodMoon().getSpeedMult();
        }

        switch (type) {
            case NORMAL -> {
                // Basic vanilla zombie - no special effects
                setZombieStat(zombie, Attribute.GENERIC_MAX_HEALTH, baseHealth);
                zombie.setHealth(baseHealth);
                setZombieStat(zombie, Attribute.GENERIC_ATTACK_DAMAGE, baseDamage);
                setZombieStat(zombie, Attribute.GENERIC_MOVEMENT_SPEED, baseSpeed);
                
                // CRITICAL FIX: Add permanent fire resistance to all custom zombie types
                // NORMAL zombies don't get fire immunity (they can burn normally)
            }
            case SWARMER -> {
                // Basic zombie - standard stats
                setZombieStat(zombie, Attribute.GENERIC_MAX_HEALTH, baseHealth);
                zombie.setHealth(baseHealth);
                setZombieStat(zombie, Attribute.GENERIC_ATTACK_DAMAGE, baseDamage);
                setZombieStat(zombie, Attribute.GENERIC_MOVEMENT_SPEED, baseSpeed);
                
                // CRITICAL FIX: Add permanent fire resistance to all custom zombie types
                zombie.addPotionEffect(new PotionEffect(PotionEffectType.FIRE_RESISTANCE, Integer.MAX_VALUE, 0, false, false));
            }
            case RUNNER -> {
                // Fast but fragile
                double healthMult = plugin.getConfig().getDouble("zombie-classes.runner.health-multiplier", 0.75);
                double runnerSpeed = plugin.getConfig().getDouble("zombie-classes.runner.speed", 0.38);
                if (isBloodMoon) {
                    runnerSpeed *= plugin.getBloodMoon().getSpeedMult();
                }
                setZombieStat(zombie, Attribute.GENERIC_MAX_HEALTH, baseHealth * healthMult);
                zombie.setHealth(baseHealth * healthMult);
                setZombieStat(zombie, Attribute.GENERIC_ATTACK_DAMAGE, baseDamage * 0.9);
                setZombieStat(zombie, Attribute.GENERIC_MOVEMENT_SPEED, runnerSpeed);
                applyFireResistance(zombie); // Bug 15 fix
            }
            case TANK -> {
                // High HP, armored, knockback resistant
                double tankHealth = plugin.getConfig().getDouble("zombie-classes.tank.health", 50.0);
                double knockbackResist = plugin.getConfig().getDouble("zombie-classes.tank.knockback-resistance", 0.6);
                if (isBloodMoon) {
                    tankHealth *= plugin.getBloodMoon().getHealthMult();
                }
                setZombieStat(zombie, Attribute.GENERIC_MAX_HEALTH, tankHealth);
                zombie.setHealth(tankHealth);
                setZombieStat(zombie, Attribute.GENERIC_ATTACK_DAMAGE, baseDamage * 1.2);
                setZombieStat(zombie, Attribute.GENERIC_MOVEMENT_SPEED, baseSpeed * 0.85);
                setZombieStat(zombie, Attribute.GENERIC_KNOCKBACK_RESISTANCE, knockbackResist);
                zombie.getEquipment().setChestplate(new ItemStack(Material.IRON_CHESTPLATE));
                applyFireResistance(zombie); // Bug 15 fix
            }
            case MINER -> {
                // Standard stats, focused on block breaking
                setZombieStat(zombie, Attribute.GENERIC_MAX_HEALTH, baseHealth);
                zombie.setHealth(baseHealth);
                setZombieStat(zombie, Attribute.GENERIC_ATTACK_DAMAGE, baseDamage);
                setZombieStat(zombie, Attribute.GENERIC_MOVEMENT_SPEED, baseSpeed);
                applyFireResistance(zombie); // Bug 15 fix
            }
            case NURSE -> {
                // Support zombie - lower damage, standard HP
                setZombieStat(zombie, Attribute.GENERIC_MAX_HEALTH, baseHealth);
                zombie.setHealth(baseHealth);
                setZombieStat(zombie, Attribute.GENERIC_ATTACK_DAMAGE, baseDamage * 0.7);
                setZombieStat(zombie, Attribute.GENERIC_MOVEMENT_SPEED, baseSpeed);
                applyFireResistance(zombie); // Bug 15 fix
            }
            case SPITTER -> {
                // Ranged attacker - lower HP, standard speed
                setZombieStat(zombie, Attribute.GENERIC_MAX_HEALTH, baseHealth * 0.85);
                zombie.setHealth(baseHealth * 0.85);
                setZombieStat(zombie, Attribute.GENERIC_ATTACK_DAMAGE, baseDamage * 0.8);
                setZombieStat(zombie, Attribute.GENERIC_MOVEMENT_SPEED, baseSpeed);
                applyFireResistance(zombie); // Bug 15 fix
            }
            case SCORCHED -> {
                // Fire zombie - standard stats with fire aura
                setZombieStat(zombie, Attribute.GENERIC_MAX_HEALTH, baseHealth);
                zombie.setHealth(baseHealth);
                setZombieStat(zombie, Attribute.GENERIC_ATTACK_DAMAGE, baseDamage);
                setZombieStat(zombie, Attribute.GENERIC_MOVEMENT_SPEED, baseSpeed);
                
                // CRITICAL FIX: Proper fire immunity for scorched zombies
                zombie.setFireTicks(Integer.MAX_VALUE); // Immune to fire
                // CRITICAL FIX: Add fire resistance potion effect to prevent self-damage
                zombie.addPotionEffect(new PotionEffect(PotionEffectType.FIRE_RESISTANCE, Integer.MAX_VALUE, 0, false, false));
            }
            case PSYCHOPATH -> {
                // Berserker - higher damage plus a small passive speed bonus
                double attackBonus = plugin.getConfig().getDouble("zombie-classes.psychopath.attack-bonus", 2.0);
                // Bug M1 fix: honor zombie-classes.psychopath.speed-bonus (was never applied).
                double speedBonus = plugin.getConfig().getDouble("zombie-classes.psychopath.speed-bonus", 0.08);
                setZombieStat(zombie, Attribute.GENERIC_MAX_HEALTH, baseHealth);
                zombie.setHealth(baseHealth);
                setZombieStat(zombie, Attribute.GENERIC_ATTACK_DAMAGE, baseDamage + attackBonus);
                setZombieStat(zombie, Attribute.GENERIC_MOVEMENT_SPEED, baseSpeed + speedBonus);
                applyFireResistance(zombie); // Bug 15 fix
            }
            case VETERAN -> {
                // Elite zombie - transformed from kills
                double attackBonus = plugin.getConfig().getDouble("zombie-classes.veteran.attack-bonus", 4.0);
                double healthAdd = plugin.getConfig().getDouble("zombie-classes.veteran.add-health", 0.0);
                setZombieStat(zombie, Attribute.GENERIC_MAX_HEALTH, baseHealth + healthAdd);
                zombie.setHealth(baseHealth + healthAdd);
                setZombieStat(zombie, Attribute.GENERIC_ATTACK_DAMAGE, baseDamage + attackBonus);
                setZombieStat(zombie, Attribute.GENERIC_MOVEMENT_SPEED, baseSpeed * 1.1);
                applyFireResistance(zombie); // Bug 15 fix
            }
            case WEBBER -> {
                // Webber - places cobwebs on hit, holds string in off-hand
                setZombieStat(zombie, Attribute.GENERIC_MAX_HEALTH, baseHealth);
                zombie.setHealth(baseHealth);
                setZombieStat(zombie, Attribute.GENERIC_ATTACK_DAMAGE, baseDamage);
                setZombieStat(zombie, Attribute.GENERIC_MOVEMENT_SPEED, baseSpeed);
                zombie.getEquipment().setItemInOffHand(new ItemStack(Material.STRING));
                applyFireResistance(zombie); // Bug 15 fix
            }
            case BURSTER -> {
                // Burster - explodes when close, lower HP
                setZombieStat(zombie, Attribute.GENERIC_MAX_HEALTH, baseHealth * 0.8);
                zombie.setHealth(baseHealth * 0.8);
                setZombieStat(zombie, Attribute.GENERIC_ATTACK_DAMAGE, baseDamage * 0.5);
                setZombieStat(zombie, Attribute.GENERIC_MOVEMENT_SPEED, baseSpeed * 0.6);
                applyFireResistance(zombie); // Bug 15 fix
            }
            case FROST -> {
                // Frost - slows targets on hit, wears aqua chestplate
                setZombieStat(zombie, Attribute.GENERIC_MAX_HEALTH, baseHealth);
                zombie.setHealth(baseHealth);
                setZombieStat(zombie, Attribute.GENERIC_ATTACK_DAMAGE, baseDamage);
                setZombieStat(zombie, Attribute.GENERIC_MOVEMENT_SPEED, baseSpeed);
                applyFireResistance(zombie); // Bug 15 fix
                
                // CRITICAL FIX: Only frost zombies get blue leather chestplate
                ItemStack chestplate = new ItemStack(Material.LEATHER_CHESTPLATE);
                chestplate.getItemMeta();
                if (chestplate.getItemMeta() instanceof org.bukkit.inventory.meta.LeatherArmorMeta meta) {
                    meta.setColor(org.bukkit.Color.AQUA);
                    chestplate.setItemMeta(meta);
                }
                zombie.getEquipment().setChestplate(chestplate);
            }
        }
    }

    private void setZombieStat(Zombie zombie, Attribute attribute, double value) {
        if (zombie.getAttribute(attribute) != null) zombie.getAttribute(attribute).setBaseValue(value);
    }

    // Bug 15 fix: centralised helper so every non-NORMAL type gets fire resistance
    // (onEntityCombust cancels sunlight burning only when this effect is present)
    private void applyFireResistance(Zombie zombie) {
        zombie.addPotionEffect(new PotionEffect(PotionEffectType.FIRE_RESISTANCE,
                Integer.MAX_VALUE, 0, false, false));
    }

    public ZombieType getZombieType(Zombie zombie) {
        String s = zombie.getPersistentDataContainer().get(ZOMBIE_TYPE_KEY, PersistentDataType.STRING);
        return s == null ? null : ZombieType.valueOf(s);
    }

    /** True only for zombies whose class/stats are owned by xApocalypse. */
    public boolean isCustomZombie(Zombie zombie) {
        return zombie != null
                && zombie.getPersistentDataContainer().has(ZOMBIE_TYPE_KEY, PersistentDataType.STRING);
    }

    public void tickZombieAI(Zombie zombie) {
        ZombieType type = getZombieType(zombie);
        if (type == null) return;
        
        // Clean switch expression for AI behaviors (Strategy Pattern-like approach)
        switch (type) {
            case NURSE -> tickNurseAI(zombie);
            case MINER -> tickMinerAI(zombie);
            case SPITTER -> tickSpitterAI(zombie);
            case SCORCHED -> tickScorchedAI(zombie);
            case PSYCHOPATH -> tickPsychopathAI(zombie);
            case BURSTER -> tickBursterAI(zombie);
            // SWARMER, RUNNER, TANK, VETERAN have no special AI behaviors
            default -> { /* No special AI for this type */ }
        }
    }

    private void tickNurseAI(Zombie nurse) {
        long now = System.currentTimeMillis();
        // Bug M1 fix: honor the configured heal interval/radius/amount/target-cap (these keys were
        // previously ignored — the values below were all hardcoded).
        long intervalMs = plugin.getConfig().getInt("zombie-classes.nurse.interval-seconds", 3) * 1000L;
        Long lastHeal = nurse.getPersistentDataContainer().get(LAST_HEAL_KEY, PersistentDataType.LONG);
        if (lastHeal != null && (now - lastHeal) < intervalMs) return;

        double radius = plugin.getConfig().getDouble("zombie-classes.nurse.heal-radius", 5.0);
        double healAmount = plugin.getConfig().getDouble("zombie-classes.nurse.heal-amount-hp", 3.0);
        int maxTargets = plugin.getConfig().getInt("zombie-classes.nurse.max-targets-per-tick", 5);

        boolean healed = false;
        int healedCount = 0;
        for (Entity e : nurse.getNearbyEntities(radius, radius, radius)) {
            if (healedCount >= maxTargets) break;
            if (e instanceof Zombie z && z.getHealth() < z.getAttribute(Attribute.GENERIC_MAX_HEALTH).getValue()) {
                z.setHealth(Math.min(z.getHealth() + healAmount, z.getAttribute(Attribute.GENERIC_MAX_HEALTH).getValue()));
                z.getWorld().spawnParticle(Particle.HEART, z.getLocation().add(0, 1.5, 0), 5, 0.2, 0.2, 0.2, 0.1);
                healed = true;
                healedCount++;
            }
        }
        if (healed) {
            nurse.getPersistentDataContainer().set(LAST_HEAL_KEY, PersistentDataType.LONG, now);
            nurse.getWorld().playSound(nurse.getLocation(), Sound.ENTITY_VILLAGER_YES, 1.0f, 1.0f);
        }
    }

    private void tickMinerAI(Zombie miner) {
        LivingEntity target = miner.getTarget();
        if (target == null) return;

        long now = System.currentTimeMillis();
        Long lastBreak = miner.getPersistentDataContainer().get(LAST_BREAK_KEY, PersistentDataType.LONG);
        int delay = plugin.getConfig().getInt("zombie-classes.miner.break-delay-ticks", 30) * 50;

        if (lastBreak != null && (now - lastBreak) < delay) return;

        Vector direction = target.getLocation().toVector().subtract(miner.getLocation().toVector()).normalize();
        Block block = miner.getLocation().add(direction).getBlock();
        Block eyeBlock = miner.getEyeLocation().add(direction).getBlock();

        if (tryBreak(miner, block) || tryBreak(miner, eyeBlock)) {
            miner.getPersistentDataContainer().set(LAST_BREAK_KEY, PersistentDataType.LONG, now);
        }
    }

    private boolean tryBreak(Zombie miner, Block b) {
        if (b.getType() == Material.AIR || b.getType() == Material.BEDROCK) return false;
        List<String> breakables = plugin.getConfig().getStringList("zombie-classes.miner.breakables");
        if (!breakables.contains(b.getType().name())) return false;
        if (isInsideClaim(b.getLocation())) return false;

        if (plugin.getConfig().getBoolean("zombie-classes.miner.drop-items", true)) {
            b.breakNaturally();
        } else {
            b.setType(Material.AIR);
        }
        b.getWorld().playSound(b.getLocation(), Sound.BLOCK_STONE_BREAK, 1.0f, 1.0f);
        b.getWorld().spawnParticle(Particle.BLOCK, b.getLocation().add(0.5, 0.5, 0.5), 10, b.getBlockData());
        return true;
    }

    private void tickSpitterAI(Zombie spitter) {
        LivingEntity target = spitter.getTarget();
        if (target == null) return;

        double dist = spitter.getLocation().distance(target.getLocation());
        if (dist > 15 || dist < 4) return;

        long now = System.currentTimeMillis();
        Long lastSpit = spitter.getPersistentDataContainer().get(LAST_SPIT_KEY, PersistentDataType.LONG);
        // Bug M1 fix: honor zombie-classes.spitter.projectile-cooldown-seconds (was hardcoded 4 s).
        long cooldownMs = plugin.getConfig().getInt("zombie-classes.spitter.projectile-cooldown-seconds", 6) * 1000L;
        if (lastSpit != null && (now - lastSpit) < cooldownMs) return;

        spitter.getPersistentDataContainer().set(LAST_SPIT_KEY, PersistentDataType.LONG, now);
        Vector velocity = target.getLocation().add(0, 1, 0).toVector().subtract(spitter.getEyeLocation().toVector()).normalize().multiply(1.2);
        LlamaSpit spit = spitter.launchProjectile(LlamaSpit.class, velocity);
        spit.setShooter(spitter);
        // Bug 2 fix: tag the projectile with a dedicated key so onProjectileHit can identify it.
        // Using ZOMBIE_TYPE_KEY on the spit never worked — that key belongs to the zombie entity, not the projectile.
        spit.getPersistentDataContainer().set(ACID_SPIT_KEY, PersistentDataType.BYTE, (byte) 1);
        spitter.getWorld().playSound(spitter.getLocation(), Sound.ENTITY_LLAMA_SPIT, 1.0f, 0.8f);
    }

    private void tickScorchedAI(Zombie scorched) {
        if (ThreadLocalRandom.current().nextDouble() < 0.1) {
            scorched.getWorld().spawnParticle(Particle.FLAME, scorched.getLocation().add(0, 1, 0), 3, 0.2, 0.5, 0.2, 0.02);
        }
        for (Entity e : scorched.getNearbyEntities(2, 2, 2)) {
            if (e instanceof Player p && p.getGameMode() == GameMode.SURVIVAL) {
                // Cosmetic flame particles instead of actual fire
                p.getWorld().spawnParticle(Particle.FLAME, p.getLocation().add(0, 0.5, 0), 8, 0.3, 0.6, 0.3, 0.05);
                p.getWorld().spawnParticle(Particle.SMOKE, p.getLocation().add(0, 0.5, 0), 4, 0.2, 0.4, 0.2, 0.02);
            }
        }
    }

    private void tickPsychopathAI(Zombie psycho) {
        if (psycho.getHealth() < psycho.getAttribute(Attribute.GENERIC_MAX_HEALTH).getValue() * 0.5) {
            long now = System.currentTimeMillis();
            Long lastRage = psycho.getPersistentDataContainer().get(LAST_RAGE_KEY, PersistentDataType.LONG);
            // Bug M1 fix: honor zombie-classes.psychopath.rage-cooldown-seconds (was hardcoded 10 s).
            long rageCooldownMs = plugin.getConfig().getInt("zombie-classes.psychopath.rage-cooldown-seconds", 25) * 1000L;
            if (lastRage == null || (now - lastRage) > rageCooldownMs) {
                psycho.addPotionEffect(new PotionEffect(PotionEffectType.STRENGTH, 100, 1));
                psycho.addPotionEffect(new PotionEffect(PotionEffectType.SPEED, 100, 1));
                psycho.getWorld().playSound(psycho.getLocation(), Sound.ENTITY_ZOMBIE_VILLAGER_CONVERTED, 1.0f, 1.5f);
                psycho.getWorld().spawnParticle(Particle.ANGRY_VILLAGER, psycho.getLocation().add(0, 2, 0), 5);
                psycho.getPersistentDataContainer().set(LAST_RAGE_KEY, PersistentDataType.LONG, now);
            }
        }
    }

    private void tickBursterAI(Zombie burster) {
        if (burster.getTarget() instanceof Player player) {
            handleBursterTarget(burster, player);
        }
    }

    public void transformToVeteran(Zombie zombie) {
        // Single source of truth for the toggle: the same key the listener gates on
        // (zombie-classes.veteran.permanent). Previously this re-checked a separate "persist" key,
        // so disabling one but not the other produced confusing half-on behavior.
        if (!plugin.getConfig().getBoolean("zombie-classes.veteran.permanent", true)) return;
        applyZombieType(zombie, ZombieType.VETERAN);
    }

    /**
     * Removes every blood-moon-tagged zombie (see {@link #BLOOD_MOON_KEY}) across all enabled worlds
     * with a small smoke puff + sound — called when a blood moon ends so the horde it summoned does
     * not linger. Zombies that existed before the blood moon are left untouched. Returns the count
     * removed. The entities are removed outright (no death drops), avoiding an end-of-event loot dump.
     */
    public int despawnBloodMoonZombies() {
        int removed = 0;
        for (org.bukkit.World world : Bukkit.getWorlds()) {
            if (!plugin.isWorldEnabled(world)) continue;
            for (Zombie zombie : world.getEntitiesByClass(Zombie.class)) {
                if (!zombie.getPersistentDataContainer().has(BLOOD_MOON_KEY, PersistentDataType.BYTE)) continue;
                // Cancel any pending burster fuse so its delayed explosion can't fire post-removal.
                cancelBursterFuse(zombie);
                Location loc = zombie.getLocation().add(0, 1, 0);
                world.spawnParticle(Particle.SMOKE, loc, 12, 0.25, 0.4, 0.25, 0.02);
                world.playSound(zombie.getLocation(), Sound.ENTITY_ZOMBIE_DEATH, 0.6f, 0.7f);
                zombie.remove();
                removed++;
            }
        }
        if (removed > 0) {
            plugin.debugLog("Blood moon ended — despawned " + removed + " blood-moon zombie(s).");
        }
        return removed;
    }

    /**
     * Completes deferred Blood Moon cleanup when a previously-unloaded chunk returns. Bukkit's
     * world entity collections only contain loaded chunks, so the normal end-of-event sweep cannot
     * see every tagged zombie. The persistent tag is the durable cleanup record.
     */
    public int cleanupExpiredBloodMoonZombies(Chunk chunk) {
        if (chunk == null || plugin.isBloodMoonActive(chunk.getWorld())) return 0;

        int removed = 0;
        for (Entity entity : chunk.getEntities()) {
            if (!(entity instanceof Zombie zombie)) continue;
            if (!zombie.getPersistentDataContainer().has(BLOOD_MOON_KEY, PersistentDataType.BYTE)) continue;
            cancelBursterFuse(zombie);
            zombie.remove();
            removed++;
        }
        return removed;
    }

    public boolean isInsideClaim(Location loc) {
        return plugin.isInsideClaim(loc);
    }

    public void handleAcidHit(Entity e) {
        if (!(e instanceof LivingEntity l)) return;
        int duration = plugin.getConfig().getInt("zombie-classes.spitter.poison-duration-seconds", 8);
        int level = plugin.getConfig().getInt("zombie-classes.spitter.poison-level", 2);
        l.addPotionEffect(new PotionEffect(PotionEffectType.POISON, duration * 20, level - 1));
        l.getWorld().spawnParticle(Particle.ITEM_SLIME, l.getLocation().add(0, 1, 0), 10, 0.3, 0.3, 0.3, 0.1);
        l.getWorld().playSound(l.getLocation(), Sound.ENTITY_GENERIC_SPLASH, 1.0f, 1.0f);
    }

    // === WEBBER EVENT HANDLERS ===
    
    public void handleWebberHit(Zombie webber, Player victim) {
        long now = System.currentTimeMillis();
        Long lastWeb = webber.getPersistentDataContainer().get(LAST_WEB_KEY, PersistentDataType.LONG);
        
        // Cooldown: once every 7 seconds per zombie
        if (lastWeb != null && (now - lastWeb) < 7000) return;

         int webCount = Math.max(0,
                 plugin.getConfig().getInt("zombie-classes.webber.web_count", 3));
         // Prefer seconds-based config key for readability (fallback to old ticks-based key)
         long cleanupDelayTicks;
         if (plugin.getConfig().contains("zombie-classes.webber.cleanup_delay_seconds")) {
             long seconds = plugin.getConfig().getLong("zombie-classes.webber.cleanup_delay_seconds", 5L);
             cleanupDelayTicks = Math.max(1L, seconds) * 20L;
         } else {
             // Backwards compatible: default to ~5 seconds if old key missing
             cleanupDelayTicks = Math.max(1L,
                     plugin.getConfig().getLong("zombie-classes.webber.cleanup_delay", 100L));
         }

         Block baseBlock = victim.getLocation().getBlock();
         Set<Block> placed = new HashSet<>();
         int maxAttempts = Math.max(1, webCount) * 10;
         int attempts = 0;

         while (placed.size() < webCount && attempts < maxAttempts) {
             int dx = ThreadLocalRandom.current().nextInt(-1, 2);
             int dz = ThreadLocalRandom.current().nextInt(-1, 2);
             Block block = baseBlock.getRelative(dx, 0, dz);
             if (block.getType() == Material.AIR && !plugin.isInsideClaim(block.getLocation())) {
                 temporaryWebBlocks.put(block, block.getBlockData().clone());
                 block.setType(Material.COBWEB, false);
                 placed.add(block);
             }
             attempts++;
         }

         List<Block> webBlocks = new ArrayList<>(placed);
         Bukkit.getScheduler().runTaskLater(plugin, () -> {
             for (Block block : webBlocks) {
                 restoreTemporaryWeb(block);
             }
         }, cleanupDelayTicks);

        webber.getPersistentDataContainer().set(LAST_WEB_KEY, PersistentDataType.LONG, now);
    }

    // === BURSTER EVENT HANDLERS ===
    
    public void handleBursterTarget(Zombie burster, Player target) {
        if (!burster.getWorld().equals(target.getWorld())) return;
        double radius = Math.max(0.0, plugin.getConfig().getDouble("zombie-classes.burster.radius", 3.0));
        if (burster.getLocation().distanceSquared(target.getLocation()) > radius * radius) return;

        UUID id = burster.getUniqueId();
        if (activeBursterFuses.containsKey(id)) return;

        int fuseTicks = Math.max(1, plugin.getConfig().getInt("zombie-classes.burster.fuse_ticks", 30));
        float power = (float) Math.max(0.0, plugin.getConfig().getDouble("zombie-classes.burster.power", 3.0));
        // Bug M1 fix: honor zombie-classes.burster.break_blocks (the explosion was hardcoded to never
        // break terrain). Read here so it's effectively final for the fuse runnable below.
        boolean breakBlocks = plugin.getConfig().getBoolean("zombie-classes.burster.break_blocks", true);
        
        // CRITICAL FIX: Add burster fuse to tracking map
        BukkitRunnable fuse = new BukkitRunnable() {
            private int ticks = 0;
            
            @Override
            public void run() {
                if (burster.isDead() || !burster.isValid()) {
                    this.cancel();
                    activeBursterFuses.remove(id);
                    return;
                }
                
                ticks++;
                
                if (ticks >= fuseTicks) {
                    // Explode
                    Location loc = burster.getLocation();
                    burster.remove(); // Remove entity before explosion
                    loc.getWorld().createExplosion(loc, power, false, breakBlocks);
                    this.cancel();
                    activeBursterFuses.remove(id);
                } else {
                    // Visual effects
                    burster.setGlowing(true);
                    if (ticks % 5 == 0) {
                        burster.setGlowing(false);
                    }
                }
            }
        };
        
        activeBursterFuses.put(id, fuse);
        fuse.runTaskTimer(plugin, 0, 1);
    }
    
    // CRITICAL FIX: Add method to cancel burster fuse
    public void cancelBursterFuse(Zombie burster) {
        UUID id = burster.getUniqueId();
        BukkitRunnable fuse = activeBursterFuses.remove(id);
        if (fuse != null) {
            fuse.cancel();
        }
        if (burster.isValid() && !burster.isDead()) {
            burster.setGlowing(false);
        }
        burster.getPersistentDataContainer().remove(BURSTER_PRIMED_KEY);
    }

    public void forgetTemporaryWeb(Block block) {
        temporaryWebBlocks.remove(block);
    }

    private void restoreTemporaryWeb(Block block) {
        BlockData original = temporaryWebBlocks.remove(block);
        if (original != null && block.getType() == Material.COBWEB) {
            block.setBlockData(original, false);
        }
    }

    public void cleanupTransientEffects() {
        for (BukkitRunnable fuse : new ArrayList<>(activeBursterFuses.values())) {
            fuse.cancel();
        }
        activeBursterFuses.clear();

        for (World world : Bukkit.getWorlds()) {
            for (Zombie zombie : world.getEntitiesByClass(Zombie.class)) {
                String type = zombie.getPersistentDataContainer().get(
                        ZOMBIE_TYPE_KEY, PersistentDataType.STRING);
                if (ZombieType.BURSTER.name().equals(type)
                        || zombie.getPersistentDataContainer().has(BURSTER_PRIMED_KEY, PersistentDataType.BYTE)
                        || zombie.getPersistentDataContainer().has(BURSTER_PRIMED_KEY, PersistentDataType.LONG)) {
                    zombie.setGlowing(false);
                    zombie.getPersistentDataContainer().remove(BURSTER_PRIMED_KEY);
                }
            }
        }

        for (Block block : new ArrayList<>(temporaryWebBlocks.keySet())) {
            restoreTemporaryWeb(block);
        }
    }

    // === FROST EVENT HANDLERS ===

    public void handleFrostHit(Zombie frost, Player victim) {
        int durationTicks = plugin.getConfig().getInt("zombie-classes.frost.duration_ticks", 100);
        int level = plugin.getConfig().getInt("zombie-classes.frost.slowness_level", 2);
        victim.addPotionEffect(new PotionEffect(PotionEffectType.SLOWNESS, durationTicks, Math.max(0, level - 1)));
    }

    // === SCORCHED EVENT HANDLERS ===

    /**
     * Bug M2 fix: a Scorched zombie sets its melee victim on fire for the configured duration.
     * Previously the class only emitted cosmetic flame particles and had no on-hit effect at all,
     * so zombie-classes.scorched.fire-duration-seconds did nothing.
     */
    public void handleScorchedHit(Zombie scorched, Player victim) {
        int seconds = plugin.getConfig().getInt("zombie-classes.scorched.fire-duration-seconds", 4);
        if (seconds <= 0) return;
        victim.setFireTicks(Math.max(victim.getFireTicks(), seconds * 20));
    }

    // === PSYCHOPATH EVENT HANDLERS ===

    /**
     * Bug M1 fix: a Psychopath's melee applies a short "bleed" (Wither) for the configured duration.
     * Previously zombie-classes.psychopath.bleed-duration-seconds was never read.
     */
    public void handlePsychopathHit(Zombie psycho, Player victim) {
        int seconds = plugin.getConfig().getInt("zombie-classes.psychopath.bleed-duration-seconds", 3);
        if (seconds <= 0) return;
        victim.addPotionEffect(new PotionEffect(PotionEffectType.WITHER, seconds * 20, 0));
    }
}
