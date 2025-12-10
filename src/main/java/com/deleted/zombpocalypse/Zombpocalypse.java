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
import org.bukkit.event.entity.EntityTargetLivingEntityEvent;
import org.bukkit.event.player.PlayerItemConsumeEvent;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.plugin.java.JavaPlugin;

import java.io.File;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
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

    // --- NEW VARIABLES FOR ZOMBIE GUTS ---
    private boolean zombieGutsEnabled;
    private final List<Player> immunePlayers = new ArrayList<>();
    private final Map<Player, Double> originalHealth = new HashMap<>();
    // --------------------------------------

    @Override
    public void onEnable() {
        // --- CONFIG FILE CHECK & CREATION (PREVENTS YAMLL/BOM ISSUES) ---
        File configFile = new File(getDataFolder(), "config.yml");
        if (!configFile.exists()) {
            saveResource("config.yml", false);
        }
        // -----------------------------------------------------------------

        // Load config
        reloadConfig();
        loadConfigValues();

        // Register Events
        getServer().getPluginManager().registerEvents(this, this);

        // Register Commands
        getCommand("zreload").setExecutor(this);
        getCommand("help").setExecutor(this);
        getCommand("zitem").setExecutor(this);

        // Start the Apocalypse Spawner Task
        startSpawnerTask();

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

        // --- LOAD ZOMBIE GUTS VALUES ---
        zombieGutsEnabled = getConfig().getBoolean("zombie-settings.zombie-guts.enabled");
        // -----------------------------------
    }

    private boolean isWorldEnabled(World world) {
        return enabledWorlds.contains(world.getName());
    }

    // --- EVENT: CONTROL SPAWNS & BUFF ZOMBIES (REVERTED TO ALLOW NATURAL ZOMBIE SPAWNS) ---
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

        // 2. REVERTED FIX: Disable only OTHER natural monsters,
        //    allowing natural ZOMBIE spawns to occur alongside our custom spawner.
        if (entity instanceof Monster && !(entity instanceof Zombie)) {
            if (event.getSpawnReason() == CreatureSpawnEvent.SpawnReason.NATURAL) {
                event.setCancelled(true);
                return;
            }
        }

        // 3. Buff Zombies (This runs for Zombies that were NOT cancelled)
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

    // --- EVENT: PREVENT ZOMBIE TARGETING (Zombie Guts Feature) ---
    @EventHandler
    public void onEntityTarget(EntityTargetLivingEntityEvent event) {
        if (!zombieGutsEnabled) return;

        // Check if the entity is a Zombie and the target is a Player
        if (event.getEntity().getType() == EntityType.ZOMBIE && event.getTarget() instanceof Player player) {
            if (immunePlayers.contains(player)) {
                event.setCancelled(true); // Zombie ignores the immune player

                // If it's a zombie, clear its current target
                if (event.getEntity() instanceof Zombie zombie) {
                    zombie.setTarget(null);
                }
            }
        }
    }

    // --- EVENT: CONSUME ZOMBIE GUTS ---
    @EventHandler
    public void onPlayerConsume(PlayerItemConsumeEvent event) {
        if (!zombieGutsEnabled) return;

        ItemStack item = event.getItem();

        // Check if the item is Rotten Flesh and has the custom name
        if (item.getType() == Material.ROTTEN_FLESH && item.hasItemMeta() && item.getItemMeta().hasDisplayName()) {

            // We use the exact display name we set for the item
            String displayName = item.getItemMeta().getDisplayName();

            if (displayName.equals("§2§lZombie Guts")) {
                event.setCancelled(true); // Cancel default Rotten Flesh effect

                Player player = event.getPlayer();

                // Prevent consuming if already immune
                if (immunePlayers.contains(player)) {
                    player.sendMessage("§eYou are already immune! Wait for the effect to wear off.");
                    return;
                }

                // 1. Reduce Health (Weakness: 5 hearts = 10.0 health)
                double maxHealth = 10.0;
                if (player.getAttribute(Attribute.GENERIC_MAX_HEALTH) != null) {
                    // Store original health
                    originalHealth.put(player, player.getAttribute(Attribute.GENERIC_MAX_HEALTH).getBaseValue());

                    player.getAttribute(Attribute.GENERIC_MAX_HEALTH).setBaseValue(maxHealth);
                    player.setHealth(Math.min(player.getHealth(), maxHealth)); // Ensure health doesn't exceed new max
                }

                // 2. Grant Immunity & Schedule Removal (10 minutes)
                immunePlayers.add(player);
                scheduleImmunityRemoval(player, 10 * 60 * 20); // 10 minutes in ticks

                // 3. Feedback and Inventory update
                player.sendMessage("§2§lYou consumed Zombie Guts!§a Zombies will ignore you for 10 minutes.");
                player.sendMessage("§cWARNING: Your maximum health has been reduced to 5 hearts!");

                // Manually remove one item from the stack
                if (item.getAmount() > 1) {
                    item.setAmount(item.getAmount() - 1);
                } else {
                    // Item in hand is removed by setting it to null
                    if (player.getInventory().getItemInMainHand().equals(item)) {
                        player.getInventory().setItemInMainHand(null);
                    } else if (player.getInventory().getItemInOffHand().equals(item)) {
                        player.getInventory().setItemInOffHand(null);
                    }
                }
            }
        }
    }

    // --- UTILITY: SCHEDULE IMMUNITY REMOVAL ---
    private void scheduleImmunityRemoval(Player player, long durationTicks) {
        Bukkit.getScheduler().runTaskLater(this, () -> {
            // Check if the player is still online and still marked as immune
            if (player.isOnline() && immunePlayers.remove(player)) {
                // Immunity expired
                player.sendMessage("§6§lYour Zombie Guts immunity has worn off!§r");

                // Restore Health
                if (originalHealth.containsKey(player)) {
                    double originalMaxHealth = originalHealth.remove(player);

                    if (player.getAttribute(Attribute.GENERIC_MAX_HEALTH) != null) {
                        player.getAttribute(Attribute.GENERIC_MAX_HEALTH).setBaseValue(originalMaxHealth);
                        // Restore health to the full new max health, or current health if lower
                        player.setHealth(Math.min(player.getHealth(), originalMaxHealth));
                        player.sendMessage("§aYour maximum health has been restored.");
                    }
                }
            } else if (!player.isOnline() && originalHealth.containsKey(player)) {
                // Clean up data for offline player if they still have stored health
                originalHealth.remove(player);
                immunePlayers.remove(player);
            }
        }, durationTicks);
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

    // --- COMMAND: RELOAD CONFIG, ITEM COMMANDS, & HELP ---
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

        // --- /ZITEM COMMAND ---
        if (command.getName().equalsIgnoreCase("zitem")) {
            if (!sender.hasPermission("zombpocalypse.admin")) {
                sender.sendMessage("§cYou do not have permission to use this command.");
                return true;
            }
            if (!(sender instanceof Player player)) {
                sender.sendMessage("§cOnly players can use this command.");
                return true;
            }
            if (args.length < 1) {
                sender.sendMessage("§cUsage: /zitem <item_name>");
                return true;
            }

            if (args[0].equalsIgnoreCase("zombie_guts") && zombieGutsEnabled) {
                ItemStack guts = new ItemStack(Material.ROTTEN_FLESH);
                ItemMeta meta = guts.getItemMeta();

                // Setting the custom name and lore
                meta.setDisplayName("§2§lZombie Guts");
                meta.setLore(List.of(
                        "§7Consume to gain temporary immunity",
                        "§7from zombie attacks.",
                        "",
                        "§cWARNING: Reduces Max Health to 5 Hearts for 10 minutes."
                ));
                guts.setItemMeta(meta);

                player.getInventory().addItem(guts);
                player.sendMessage("§aObtained Zombie Guts!");
                return true;
            }

            sender.sendMessage("§cItem not found or feature disabled.");
            return true;
        }

        // --- /HELP COMMAND ---
        if (command.getName().equalsIgnoreCase("help")) {
            sender.sendMessage("§4§l--- Zombpocalypse v" + getDescription().getVersion() + " ---");
            sender.sendMessage("§aDeveloped by xDele1ed.");
            sender.sendMessage("§fCommands:");
            sender.sendMessage("§b/zreload§7: Reloads the plugin configuration (stats, lists, rates).");
            sender.sendMessage("§b/zitem <item_name>§7: Gives a special item like 'zombie_guts'.");
            sender.sendMessage("§b/help§7: Shows this command list.");
            sender.sendMessage("§4---------------------------------------");
            return true;
        }

        return false;
    }
}