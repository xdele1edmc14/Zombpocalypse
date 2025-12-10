package com.deleted.zombpocalypse;

import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.attribute.Attribute;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Entity;
import org.bukkit.entity.EntityType;
import org.bukkit.entity.Monster;
import org.bukkit.entity.Player;
import org.bukkit.entity.Zombie;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.CreatureSpawnEvent;
import org.bukkit.event.entity.EntityCombustEvent;
import org.bukkit.plugin.java.JavaPlugin;

import java.util.List;
import java.util.concurrent.ThreadLocalRandom;

public class Zombpocalypse extends JavaPlugin implements Listener, CommandExecutor {

    private List<String> enabledWorlds;

    // --- NEW VARIABLES FOR REQUESTED FEATURES ---
    private double daySpawnChance;
    private boolean useMobBlacklist;
    private List<String> mobList;

    // --- NEW VARIABLES FOR ZOMBIE TYPES ---
    private boolean allowBabyZombies;
    private boolean allowZombieVillagers;
    // --------------------------------------

    @Override
    public void onEnable() {
        // Load config
        saveDefaultConfig();
        loadConfigValues();

        // Register Events
        getServer().getPluginManager().registerEvents(this, this);

        // Register Commands
        getCommand("zreload").setExecutor(this);
        getCommand("help").setExecutor(this);

        // Start the Apocalypse Spawner Task
        startSpawnerTask();

        // NO FANCY BANNER HERE, just simple startup message
        getLogger().info("[Zombpocalypse] Zombpocalypse has started! Brains...");
    }

    private void loadConfigValues() {
        enabledWorlds = getConfig().getStringList("enabled-worlds");

        // --- LOAD NEW CONFIG VALUES ---
        daySpawnChance = getConfig().getDouble("apocalypse-settings.day-spawn-chance");
        useMobBlacklist = getConfig().getBoolean("apocalypse-settings.use-mob-blacklist");
        mobList = getConfig().getStringList("apocalypse-settings.mob-list");

        // --- LOAD NEW ZOMBIE TYPE VALUES ---
        allowBabyZombies = getConfig().getBoolean("zombie-settings.allow-baby-zombies");
        allowZombieVillagers = getConfig().getBoolean("zombie-settings.allow-zombie-villagers");
        // -----------------------------------
    }

    private boolean isWorldEnabled(World world) {
        return enabledWorlds.contains(world.getName());
    }

    // --- EVENT: CONTROL SPAWNS & BUFF ZOMBIES (INCLUDING MOB LIST FILTERING) ---
    @EventHandler
    public void onEntitySpawn(CreatureSpawnEvent event) {
        if (!isWorldEnabled(event.getLocation().getWorld())) return;

        Entity entity = event.getEntity();
        String mobName = entity.getType().toString();

        // 1. Mob List Filtering (Blacklist/Whitelist)
        boolean inList = mobList.contains(mobName);

        if (useMobBlacklist) {
            // BLACKLIST: If the mob is in the list, CANCEL the spawn.
            if (inList) {
                event.setCancelled(true);
                return;
            }
        } else {
            // WHITELIST: If the mob is NOT in the list, CANCEL the spawn.
            if (!inList) {
                event.setCancelled(true);
                return;
            }
        }

        // 2. Disable other monsters (Existing logic)
        if (entity instanceof Monster && !(entity instanceof Zombie)) {
            if (event.getSpawnReason() == CreatureSpawnEvent.SpawnReason.NATURAL) {
                event.setCancelled(true);
                return;
            }
        }

        // 3. Buff Zombies
        if (entity instanceof Zombie zombie) {

            // --- NEW: ZOMBIE TYPE CONTROL ---
            if (!allowBabyZombies && zombie.isBaby()) {
                event.setCancelled(true);
                return;
            }

            if (!allowZombieVillagers && zombie.isVillager()) {
                event.setCancelled(true);
                return;
            }
            // --------------------------------

            // Apply Health
            double health = getConfig().getDouble("zombie-settings.health");
            if (zombie.getAttribute(Attribute.GENERIC_MAX_HEALTH) != null) {
                zombie.getAttribute(Attribute.GENERIC_MAX_HEALTH).setBaseValue(health);
                zombie.setHealth(health);
            }

            // Apply Damage
            double damage = getConfig().getDouble("zombie-settings.damage");
            if (zombie.getAttribute(Attribute.GENERIC_ATTACK_DAMAGE) != null) {
                zombie.getAttribute(Attribute.GENERIC_ATTACK_DAMAGE).setBaseValue(damage);
            }

            // Apply Speed
            double speed = getConfig().getDouble("zombie-settings.speed");
            if (zombie.getAttribute(Attribute.GENERIC_MOVEMENT_SPEED) != null) {
                zombie.getAttribute(Attribute.GENERIC_MOVEMENT_SPEED).setBaseValue(speed);
            }
        }
    }

    // --- EVENT: PREVENT SUN BURN ---
    @EventHandler
    public void onEntityCombust(EntityCombustEvent event) {
        if (!isWorldEnabled(event.getEntity().getWorld())) return;

        if (event.getEntity() instanceof Zombie) {
            long time = event.getEntity().getWorld().getTime();
            // Day time is roughly 0 to 12300 ticks
            boolean isDay = time > 0 && time < 12300;

            if (isDay) {
                event.setCancelled(true);
            }
        }
    }

    // --- TASK: FORCE SPAWN ZOMBIES (HORDE MECHANIC WITH DAYLIGHT CHANCE) ---
    private void startSpawnerTask() {
        long rate = getConfig().getLong("apocalypse-settings.spawn-rate");

        Bukkit.getScheduler().runTaskTimer(this, () -> {

            // Check for daylight and apply chance filter
            World firstWorld = Bukkit.getWorlds().get(0);
            long time = firstWorld.getTime();
            boolean isDay = time > 0 && time < 13000;

            if (isDay) {
                double randomValue = ThreadLocalRandom.current().nextDouble();

                // Skip spawn if the random chance is higher than the configured daySpawnChance
                if (randomValue > daySpawnChance) {
                    return;
                }
            }

            // Spawn near players
            for (Player player : Bukkit.getOnlinePlayers()) {
                if (!isWorldEnabled(player.getWorld())) continue;
                if (player.getGameMode().toString().equals("CREATIVE") || player.getGameMode().toString().equals("SPECTATOR")) continue;

                spawnZombiesNearPlayer(player);
            }
        }, 0L, rate);
    }

    private void spawnZombiesNearPlayer(Player player) {
        int baseAmount = getConfig().getInt("apocalypse-settings.base-horde-size");
        int variance = getConfig().getInt("apocalypse-settings.horde-variance");
        int radius = getConfig().getInt("apocalypse-settings.spawn-radius");
        World world = player.getWorld();

        // Calculate total zombies to spawn: Base + Random(0 to Variance)
        int totalAmount = baseAmount + ThreadLocalRandom.current().nextInt(variance + 1);

        for (int i = 0; i < totalAmount; i++) {

            double xOffset = ThreadLocalRandom.current().nextDouble(-radius, radius);
            double zOffset = ThreadLocalRandom.current().nextDouble(-radius, radius);

            Location spawnLoc = player.getLocation().add(xOffset, 0, zOffset);

            spawnLoc.setY(world.getHighestBlockYAt(spawnLoc) + 1);

            Material blockType = spawnLoc.getBlock().getRelative(0, -1, 0).getType();
            if (blockType == Material.WATER || blockType == Material.LAVA) continue;

            world.spawnEntity(spawnLoc, EntityType.ZOMBIE);
        }
    }

    // --- COMMAND: RELOAD CONFIG & HELP ---
    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (command.getName().equalsIgnoreCase("zreload")) {
            if (!sender.hasPermission("zombpocalypse.admin")) {
                sender.sendMessage("§cYou do not have permission to use this command.");
                return true;
            }
            reloadConfig();
            loadConfigValues();
            sender.sendMessage("§a[Zombpocalypse] Configuration reloaded!");
            return true;
        }

        // --- /HELP COMMAND ---
        if (command.getName().equalsIgnoreCase("help")) {
            sender.sendMessage("§4§l--- Zombpocalypse v" + getDescription().getVersion() + " ---");
            sender.sendMessage("§aDeveloped by xDele1ed.");
            sender.sendMessage("§fCommands:");
            sender.sendMessage("§b/zreload§7: Reloads the plugin configuration (stats, lists, rates).");
            sender.sendMessage("§b/help§7: Shows this command list.");
            sender.sendMessage("§4---------------------------------------");
            return true;
        }

        return false;
    }
}