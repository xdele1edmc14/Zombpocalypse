package com.deleted.zombpocalypse;

import me.ryanhamshire.GriefPrevention.GriefPrevention;
import org.bukkit.*;
import org.bukkit.attribute.Attribute;
import org.bukkit.block.Block;
import org.bukkit.entity.*;
import org.bukkit.inventory.ItemStack;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.potion.PotionEffect;
import org.bukkit.potion.PotionEffectType;
import org.bukkit.util.Vector;

import java.util.*;
import java.util.concurrent.ThreadLocalRandom;

public class ZombpocalypseUtils {

    private final Zombpocalypse plugin;
    private final GriefPrevention griefPrevention;
    private final boolean griefPreventionEnabled;

    public static final NamespacedKey ZOMBIE_TYPE_KEY = new NamespacedKey("zombpocalypse", "zombie_type");
    public static final NamespacedKey LAST_HEAL_KEY = new NamespacedKey("zombpocalypse", "last_heal");
    public static final NamespacedKey LAST_BREAK_KEY = new NamespacedKey("zombpocalypse", "last_break");
    public static final NamespacedKey LAST_SPIT_KEY = new NamespacedKey("zombpocalypse", "last_spit");
    public static final NamespacedKey LAST_RAGE_KEY = new NamespacedKey("zombpocalypse", "last_rage");

    public enum ZombieType {
        SWARMER, MINER, NURSE, PSYCHOPATH, SCORCHED, TANK, RUNNER, SPITTER, BUILDER, VETERAN;
    }

    private final Map<ZombieType, Double> spawnWeights = new HashMap<>();
    private double totalWeight = 0.0;

    public ZombpocalypseUtils(Zombpocalypse plugin, GriefPrevention gp, boolean gpEnabled) {
        this.plugin = plugin;
        this.griefPrevention = gp;
        this.griefPreventionEnabled = gpEnabled;
        loadWeights();
    }

    private void loadWeights() {
        spawnWeights.clear();
        totalWeight = 0.0;
        for (ZombieType type : ZombieType.values()) {
            // VETERAN and BUILDER are not spawned randomly (VETERAN from kills, BUILDER special)
            if (type == ZombieType.VETERAN || type == ZombieType.BUILDER) continue;
            double weight = plugin.getConfig().getDouble("zombie-classes.weights." + type.name(), 0.1);
            spawnWeights.put(type, weight);
            totalWeight += weight;
        }
    }

    public void reloadWeights() { loadWeights(); }

    public void assignZombieType(Zombie zombie) {
        if (!plugin.getConfig().getBoolean("zombie-classes.enabled", true)) return;
        applyZombieType(zombie, getRandomZombieType());
    }

    private ZombieType getRandomZombieType() {
        double random = ThreadLocalRandom.current().nextDouble() * totalWeight;
        double cumulative = 0.0;
        for (Map.Entry<ZombieType, Double> entry : spawnWeights.entrySet()) {
            cumulative += entry.getValue();
            if (random <= cumulative) return entry.getKey();
        }
        return ZombieType.SWARMER;
    }

    public void applyZombieType(Zombie zombie, ZombieType type) {
        zombie.getPersistentDataContainer().set(ZOMBIE_TYPE_KEY, PersistentDataType.STRING, type.name());
        applyZombieHead(zombie, type);
        applyZombieStats(zombie, type);
    }

    private void applyZombieHead(Zombie zombie, ZombieType type) {
        zombie.setCustomName(getZombieDisplayName(type));
        zombie.setCustomNameVisible(plugin.getConfig().getBoolean("visuals.nametag-always-visible", true));
    }

    private String getZombieDisplayName(ZombieType type) {
        return switch (type) {
            case SWARMER -> "§7⚔ Swarmer";
            case MINER -> "§6⛏ Miner";
            case NURSE -> "§d❤ Nurse";
            case RUNNER -> "§b⚡ Runner";
            case SPITTER -> "§a☠ Spitter";
            case PSYCHOPATH -> "§c⚔ Psychopath";
            case SCORCHED -> "§4🔥 Scorched";
            case TANK -> "§8⛨ Tank";
            case BUILDER -> "§5🏗 Builder";
            case VETERAN -> "§e★ Veteran";
            default -> "§7Zombie";
        };
    }

    private void applyZombieStats(Zombie zombie, ZombieType type) {
        double baseHealth = plugin.getConfig().getDouble("zombie-settings.health", 25.0);
        double baseDamage = plugin.getConfig().getDouble("zombie-settings.damage", 6.0);
        double baseSpeed = plugin.getConfig().getDouble("zombie-settings.speed", 0.32);

        boolean isBloodMoon = plugin.isBloodMoonActive(zombie.getWorld());
        if (isBloodMoon) {
            baseHealth *= plugin.getConfig().getDouble("bloodmoon.multipliers.health", 1.5);
            baseDamage *= plugin.getConfig().getDouble("bloodmoon.multipliers.damage", 1.3);
            baseSpeed *= plugin.getConfig().getDouble("bloodmoon.multipliers.speed", 1.1);
        }

        switch (type) {
            case SWARMER -> {
                // Basic zombie - standard stats
                setZombieStat(zombie, Attribute.GENERIC_MAX_HEALTH, baseHealth);
                zombie.setHealth(baseHealth);
                setZombieStat(zombie, Attribute.GENERIC_ATTACK_DAMAGE, baseDamage);
                setZombieStat(zombie, Attribute.GENERIC_MOVEMENT_SPEED, baseSpeed);
            }
            case RUNNER -> {
                // Fast but fragile
                double healthMult = plugin.getConfig().getDouble("zombie-classes.runner.health-multiplier", 0.75);
                double runnerSpeed = plugin.getConfig().getDouble("zombie-classes.runner.speed", 0.38);
                setZombieStat(zombie, Attribute.GENERIC_MAX_HEALTH, baseHealth * healthMult);
                zombie.setHealth(baseHealth * healthMult);
                setZombieStat(zombie, Attribute.GENERIC_ATTACK_DAMAGE, baseDamage * 0.9);
                setZombieStat(zombie, Attribute.GENERIC_MOVEMENT_SPEED, runnerSpeed);
            }
            case TANK -> {
                // High HP, armored, knockback resistant
                double tankHealth = plugin.getConfig().getDouble("zombie-classes.tank.health", 50.0);
                double knockbackResist = plugin.getConfig().getDouble("zombie-classes.tank.knockback-resistance", 0.6);
                if (isBloodMoon) {
                    tankHealth *= plugin.getConfig().getDouble("bloodmoon.multipliers.health", 1.5);
                }
                setZombieStat(zombie, Attribute.GENERIC_MAX_HEALTH, tankHealth);
                zombie.setHealth(tankHealth);
                setZombieStat(zombie, Attribute.GENERIC_ATTACK_DAMAGE, baseDamage * 1.2);
                setZombieStat(zombie, Attribute.GENERIC_MOVEMENT_SPEED, baseSpeed * 0.85);
                setZombieStat(zombie, Attribute.GENERIC_KNOCKBACK_RESISTANCE, knockbackResist);
                zombie.getEquipment().setChestplate(new ItemStack(Material.IRON_CHESTPLATE));
            }
            case MINER -> {
                // Standard stats, focused on block breaking
                setZombieStat(zombie, Attribute.GENERIC_MAX_HEALTH, baseHealth);
                zombie.setHealth(baseHealth);
                setZombieStat(zombie, Attribute.GENERIC_ATTACK_DAMAGE, baseDamage);
                setZombieStat(zombie, Attribute.GENERIC_MOVEMENT_SPEED, baseSpeed);
            }
            case NURSE -> {
                // Support zombie - lower damage, standard HP
                setZombieStat(zombie, Attribute.GENERIC_MAX_HEALTH, baseHealth);
                zombie.setHealth(baseHealth);
                setZombieStat(zombie, Attribute.GENERIC_ATTACK_DAMAGE, baseDamage * 0.7);
                setZombieStat(zombie, Attribute.GENERIC_MOVEMENT_SPEED, baseSpeed);
            }
            case SPITTER -> {
                // Ranged attacker - lower HP, standard speed
                setZombieStat(zombie, Attribute.GENERIC_MAX_HEALTH, baseHealth * 0.85);
                zombie.setHealth(baseHealth * 0.85);
                setZombieStat(zombie, Attribute.GENERIC_ATTACK_DAMAGE, baseDamage * 0.8);
                setZombieStat(zombie, Attribute.GENERIC_MOVEMENT_SPEED, baseSpeed);
            }
            case SCORCHED -> {
                // Fire zombie - standard stats with fire aura
                setZombieStat(zombie, Attribute.GENERIC_MAX_HEALTH, baseHealth);
                zombie.setHealth(baseHealth);
                setZombieStat(zombie, Attribute.GENERIC_ATTACK_DAMAGE, baseDamage);
                setZombieStat(zombie, Attribute.GENERIC_MOVEMENT_SPEED, baseSpeed);
                zombie.setFireTicks(Integer.MAX_VALUE); // Immune to fire
            }
            case PSYCHOPATH -> {
                // Berserker - higher damage, speed bonus when enraged
                double attackBonus = plugin.getConfig().getDouble("zombie-classes.psychopath.attack-bonus", 2.0);
                setZombieStat(zombie, Attribute.GENERIC_MAX_HEALTH, baseHealth);
                zombie.setHealth(baseHealth);
                setZombieStat(zombie, Attribute.GENERIC_ATTACK_DAMAGE, baseDamage + attackBonus);
                setZombieStat(zombie, Attribute.GENERIC_MOVEMENT_SPEED, baseSpeed);
            }
            case BUILDER -> {
                // Builder zombie - places blocks, slower movement
                setZombieStat(zombie, Attribute.GENERIC_MAX_HEALTH, baseHealth * 1.1);
                zombie.setHealth(baseHealth * 1.1);
                setZombieStat(zombie, Attribute.GENERIC_ATTACK_DAMAGE, baseDamage * 0.8);
                setZombieStat(zombie, Attribute.GENERIC_MOVEMENT_SPEED, baseSpeed * 0.9);
            }
            case VETERAN -> {
                // Elite zombie - transformed from kills
                double attackBonus = plugin.getConfig().getDouble("zombie-classes.veteran.attack-bonus", 4.0);
                double healthAdd = plugin.getConfig().getDouble("zombie-classes.veteran.add-health", 0.0);
                setZombieStat(zombie, Attribute.GENERIC_MAX_HEALTH, baseHealth + healthAdd);
                zombie.setHealth(baseHealth + healthAdd);
                setZombieStat(zombie, Attribute.GENERIC_ATTACK_DAMAGE, baseDamage + attackBonus);
                setZombieStat(zombie, Attribute.GENERIC_MOVEMENT_SPEED, baseSpeed * 1.1);
            }
        }
    }

    private void setZombieStat(Zombie zombie, Attribute attribute, double value) {
        if (zombie.getAttribute(attribute) != null) zombie.getAttribute(attribute).setBaseValue(value);
    }

    public ZombieType getZombieType(Zombie zombie) {
        String s = zombie.getPersistentDataContainer().get(ZOMBIE_TYPE_KEY, PersistentDataType.STRING);
        return s == null ? null : ZombieType.valueOf(s);
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
            case BUILDER -> tickBuilderAI(zombie);
            // SWARMER, RUNNER, TANK, VETERAN have no special AI behaviors
            default -> { /* No special AI for this type */ }
        }
    }

    private void tickNurseAI(Zombie nurse) {
        long now = System.currentTimeMillis();
        Long lastHeal = nurse.getPersistentDataContainer().get(LAST_HEAL_KEY, PersistentDataType.LONG);
        if (lastHeal != null && (now - lastHeal) < 3000) return;

        boolean healed = false;
        for (Entity e : nurse.getNearbyEntities(5, 5, 5)) {
            if (e instanceof Zombie z && z.getHealth() < z.getAttribute(Attribute.GENERIC_MAX_HEALTH).getValue()) {
                z.setHealth(Math.min(z.getHealth() + 4.0, z.getAttribute(Attribute.GENERIC_MAX_HEALTH).getValue()));
                z.getWorld().spawnParticle(Particle.HEART, z.getLocation().add(0, 1.5, 0), 5, 0.2, 0.2, 0.2, 0.1);
                healed = true;
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
        if (lastSpit != null && (now - lastSpit) < 4000) return;

        spitter.getPersistentDataContainer().set(LAST_SPIT_KEY, PersistentDataType.LONG, now);
        Vector velocity = target.getLocation().add(0, 1, 0).toVector().subtract(spitter.getEyeLocation().toVector()).normalize().multiply(1.2);
        LlamaSpit spit = spitter.launchProjectile(LlamaSpit.class, velocity);
        spit.setShooter(spitter);
        spitter.getWorld().playSound(spitter.getLocation(), Sound.ENTITY_LLAMA_SPIT, 1.0f, 0.8f);
    }

    private void tickScorchedAI(Zombie scorched) {
        if (ThreadLocalRandom.current().nextDouble() < 0.1) {
            scorched.getWorld().spawnParticle(Particle.FLAME, scorched.getLocation().add(0, 1, 0), 3, 0.2, 0.5, 0.2, 0.02);
        }
        for (Entity e : scorched.getNearbyEntities(2, 2, 2)) {
            if (e instanceof Player p && p.getGameMode() == GameMode.SURVIVAL) {
                p.setFireTicks(40);
            }
        }
    }

    private void tickPsychopathAI(Zombie psycho) {
        if (psycho.getHealth() < psycho.getAttribute(Attribute.GENERIC_MAX_HEALTH).getValue() * 0.5) {
            long now = System.currentTimeMillis();
            Long lastRage = psycho.getPersistentDataContainer().get(LAST_RAGE_KEY, PersistentDataType.LONG);
            if (lastRage == null || (now - lastRage) > 10000) {
                psycho.addPotionEffect(new PotionEffect(PotionEffectType.STRENGTH, 100, 1));
                psycho.addPotionEffect(new PotionEffect(PotionEffectType.SPEED, 100, 1));
                psycho.getWorld().playSound(psycho.getLocation(), Sound.ENTITY_ZOMBIE_VILLAGER_CONVERTED, 1.0f, 1.5f);
                psycho.getWorld().spawnParticle(Particle.ANGRY_VILLAGER, psycho.getLocation().add(0, 2, 0), 5);
                psycho.getPersistentDataContainer().set(LAST_RAGE_KEY, PersistentDataType.LONG, now);
            }
        }
    }

    private void tickBuilderAI(Zombie builder) {
        LivingEntity target = builder.getTarget();
        if (target == null) return;

        long now = System.currentTimeMillis();
        NamespacedKey lastBuildKey = new NamespacedKey("zombpocalypse", "last_build");
        Long lastBuild = builder.getPersistentDataContainer().get(lastBuildKey, PersistentDataType.LONG);
        int delay = plugin.getConfig().getInt("zombie-classes.builder.place-delay-ticks", 40) * 50;

        if (lastBuild != null && (now - lastBuild) < delay) return;

        // Builder places blocks to create paths/obstacles
        Vector direction = target.getLocation().toVector().subtract(builder.getLocation().toVector()).normalize();
        Block placeBlock = builder.getLocation().add(direction).getBlock();
        Block belowBlock = placeBlock.getRelative(0, -1, 0);

        // Only place if target block is air and below block is solid
        if (placeBlock.getType() == Material.AIR && belowBlock.getType().isSolid()) {
            if (!isInsideClaim(placeBlock.getLocation())) {
                Material buildMaterial = Material.DIRT; // Default builder material
                String configMaterial = plugin.getConfig().getString("zombie-classes.builder.block-type", "DIRT");
                try {
                    buildMaterial = Material.valueOf(configMaterial);
                } catch (IllegalArgumentException e) {
                    buildMaterial = Material.DIRT;
                }

                placeBlock.setType(buildMaterial);
                builder.getWorld().playSound(builder.getLocation(), Sound.BLOCK_GRAVEL_PLACE, 1.0f, 1.0f);
                builder.getWorld().spawnParticle(Particle.BLOCK, placeBlock.getLocation().add(0.5, 0.5, 0.5), 5, buildMaterial.createBlockData());
                
                // Track the block for cleanup
                plugin.trackBuilderBlock(placeBlock.getLocation(), builder.getUniqueId());
                
                builder.getPersistentDataContainer().set(lastBuildKey, PersistentDataType.LONG, now);
            }
        }
    }

    public void transformToVeteran(Zombie zombie) {
        if (!plugin.getConfig().getBoolean("zombie-classes.veteran.persist", true)) return;
        applyZombieType(zombie, ZombieType.VETERAN);
    }

    public boolean isInsideClaim(Location loc) {
        if (!griefPreventionEnabled) return false;
        if (!plugin.getConfig().getBoolean("hooks.griefprevention.prevent-spawning-in-claims", true)) return false;
        return griefPrevention.dataStore.getClaimAt(loc, false, null) != null;
    }

    public void handleAcidHit(Entity e) {
        if (!(e instanceof LivingEntity l)) return;
        int duration = plugin.getConfig().getInt("zombie-classes.spitter.poison-duration-seconds", 8);
        int level = plugin.getConfig().getInt("zombie-classes.spitter.poison-level", 2);
        l.addPotionEffect(new PotionEffect(PotionEffectType.POISON, duration * 20, level - 1));
        l.getWorld().spawnParticle(Particle.ITEM_SLIME, l.getLocation().add(0, 1, 0), 10, 0.3, 0.3, 0.3, 0.1);
        l.getWorld().playSound(l.getLocation(), Sound.ENTITY_GENERIC_SPLASH, 1.0f, 1.0f);
    }
}