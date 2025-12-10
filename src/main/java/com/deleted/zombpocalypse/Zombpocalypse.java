package com.deleted.zombpocalypse;

import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.attribute.Attribute;
import org.bukkit.boss.BarColor;
import org.bukkit.boss.BarStyle;
import org.bukkit.boss.BossBar;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.configuration.file.YamlConfiguration;
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
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.scheduler.BukkitRunnable;
import org.bukkit.scheduler.BukkitTask;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;

public class Zombpocalypse extends JavaPlugin implements Listener, CommandExecutor {

    private List<String> enabledWorlds;

    // --- PERSISTENCE FIELDS ---
    private File dataFile;
    private FileConfiguration dataConfig;
    // --------------------------

    // --- CONFIG VARIABLES ---
    private double daySpawnChance;
    private boolean useMobBlacklist;
    private List<String> mobList;
    private boolean ignoreLightLevel;
    private boolean allowBabyZombies;
    private boolean allowZombieVillagers;
    private boolean zombieGutsEnabled;
    // ------------------------

    // --- PERSISTENCE/IMMUNITY TRACKING ---
    private final List<UUID> immunePlayers = new ArrayList<>();
    private final Map<UUID, BossBar> activeBossBars = new HashMap<>();
    private final Map<UUID, Long> immunityEndTime = new HashMap<>();
    private final Map<UUID, Double> originalHealth = new HashMap<>();
    private final Map<UUID, BukkitTask> scheduledTasks = new HashMap<>();

    private final long IMMUNITY_DURATION_TICKS = 10 * 60 * 20L; // 12000 ticks (10 minutes)
    // --------------------------

    @Override
    public void onEnable() {
        File configFile = new File(getDataFolder(), "config.yml");
        if (!configFile.exists()) {
            saveResource("config.yml", false);
        }

        reloadConfig();
        loadConfigValues();

        // --- Data Persistence Setup ---
        dataFile = new File(getDataFolder(), "data.yml");
        if (!dataFile.exists()) {
            try { dataFile.createNewFile(); } catch (IOException e) { e.printStackTrace(); }
        }
        dataConfig = YamlConfiguration.loadConfiguration(dataFile);
        loadImmunityData();
        // --------------------------------------------

        getServer().getPluginManager().registerEvents(this, this);
        getCommand("zreload").setExecutor(this);
        getCommand("help").setExecutor(this);
        getCommand("zitem").setExecutor(this);
        startSpawnerTask();
        startBossBarTask();

        getLogger().info("[Zombpocalypse] Zombpocalypse has started! Brains...");
    }

    @Override
    public void onDisable() {
        saveImmunityData(); // Save remaining duration on shutdown
        for (BossBar bar : activeBossBars.values()) {
            bar.removeAll();
        }
        Bukkit.getScheduler().cancelTasks(this);
    }

    /**
     * Loads persistence data. The stored value is the REMAINING DURATION IN TICKS,
     * which is converted to a new absolute end time using the current world time.
     */
    private void loadImmunityData() {
        if (dataConfig.contains("player-immunity")) {
            long currentFullTime = Bukkit.getWorlds().isEmpty() ? 0 : Bukkit.getWorlds().get(0).getFullTime();

            for (String key : dataConfig.getConfigurationSection("player-immunity").getKeys(false)) {
                try {
                    UUID uuid = UUID.fromString(key);
                    long remainingTicks = dataConfig.getLong("player-immunity." + key + ".endTime");
                    double originalHealthVal = dataConfig.getDouble("player-immunity." + key + ".originalHealth");

                    if (originalHealthVal <= 0.0) continue;

                    // Always load the original health, regardless of remaining ticks.
                    originalHealth.put(uuid, originalHealthVal);

                    // FIX: Cap loaded duration to the maximum possible duration (10 minutes)
                    if (remainingTicks > IMMUNITY_DURATION_TICKS) {
                        getLogger().warning("Detected corrupted immunity time (" + remainingTicks + " ticks) for " + key + ". Capping to " + IMMUNITY_DURATION_TICKS + " ticks (10 minutes).");
                        remainingTicks = IMMUNITY_DURATION_TICKS;
                    }

                    if (remainingTicks > 0) {
                        // Calculate the NEW absolute end time for this server session.
                        long newEndTime = currentFullTime + remainingTicks;

                        // Load active immunity state into volatile maps
                        immunityEndTime.put(uuid, newEndTime);
                        immunePlayers.add(uuid);

                        getLogger().info("Loaded active immunity for " + key + ": " + remainingTicks + " ticks remaining.");
                    } else {
                        getLogger().info("Loaded expired immunity data for " + key + ". Cleanup pending on player join.");
                    }

                } catch (IllegalArgumentException e) {
                    getLogger().warning("Invalid UUID in data.yml: " + key);
                }
            }
            getLogger().info("Loaded " + originalHealth.size() + " player health persistence records.");
        }
    }

    /**
     * Saves persistence data. Calculates the REMAINING DURATION IN TICKS
     * and saves that, making it safe for server restarts.
     */
    private void saveImmunityData() {
        // We clear and rebuild the data section to ensure old/expired data is removed
        dataConfig.set("player-immunity", null);

        // Get the current world time for offset calculation
        long currentFullTime = Bukkit.getWorlds().isEmpty() ? 0 : Bukkit.getWorlds().get(0).getFullTime();

        for (UUID uuid : new ArrayList<>(originalHealth.keySet())) {

            String path = "player-immunity." + uuid.toString();
            double storedHealth = originalHealth.get(uuid);
            long remainingTicks = 0;

            if (immunityEndTime.containsKey(uuid)) {
                remainingTicks = immunityEndTime.get(uuid) - currentFullTime;
            }

            // Save the original health and remaining time if it was consumed at some point.
            if (storedHealth > 0.0) {
                // If it's expired or a negative time (due to time passing), save 0 ticks remaining.
                long finalRemainingTicks = Math.max(0, remainingTicks);

                // IMPORTANT: Recap the saved duration to prevent writing large corrupted numbers
                finalRemainingTicks = Math.min(finalRemainingTicks, IMMUNITY_DURATION_TICKS);

                dataConfig.set(path + ".endTime", finalRemainingTicks); // Save remaining ticks or 0
                dataConfig.set(path + ".originalHealth", storedHealth);
            }
        }

        try {
            dataConfig.save(dataFile);
            getLogger().info("Saved " + originalHealth.size() + " player health persistence records.");
        } catch (IOException e) {
            getLogger().severe("Could not save player data: " + e.getMessage());
        }
    }

    private void startBossBarTask() {
        new BukkitRunnable() {
            @Override
            public void run() {
                if (activeBossBars.isEmpty()) return;

                long currentFullTime = Bukkit.getWorlds().isEmpty() ? 0 : Bukkit.getWorlds().get(0).getFullTime();

                for (UUID uuid : new ArrayList<>(activeBossBars.keySet())) {
                    Player player = Bukkit.getPlayer(uuid);
                    if (player == null || !player.isOnline()) { continue; }

                    if (player.getWorld() == null) continue;

                    long remainingTicks = immunityEndTime.get(uuid) - currentFullTime;
                    long remainingSeconds = remainingTicks / 20;

                    if (remainingTicks <= 0) {
                        continue;
                    }

                    double progress = (double) remainingTicks / IMMUNITY_DURATION_TICKS;
                    progress = Math.max(0.0, Math.min(1.0, progress));
                    activeBossBars.get(uuid).setProgress(progress);

                    long minutes = remainingSeconds / 60;
                    long seconds = remainingSeconds % 60;
                    String timeString = String.format("%02d:%02d", minutes, seconds);

                    activeBossBars.get(uuid).setTitle("§2§lZombie Guts Immunity: §a" + timeString);
                }
            }
        }.runTaskTimer(this, 0L, 5L);
    }

    private void loadConfigValues() {
        enabledWorlds = getConfig().getStringList("enabled-worlds");
        daySpawnChance = getConfig().getDouble("apocalypse-settings.day-spawn-chance");
        useMobBlacklist = getConfig().getBoolean("apocalypse-settings.use-mob-blacklist");
        mobList = getConfig().getStringList("apocalypse-settings.mob-list");
        ignoreLightLevel = getConfig().getBoolean("apocalypse-settings.ignore-light-level");
        allowBabyZombies = getConfig().getBoolean("zombie-settings.allow-baby-zombies");
        allowZombieVillagers = getConfig().getBoolean("zombie-settings.allow-zombie-villagers");
        zombieGutsEnabled = getConfig().getBoolean("zombie-settings.zombie-guts.enabled");

        getLogger().info("CONFIG CHECK: Ignore Light Level (Global Fallback): " + ignoreLightLevel);
    }

    boolean isWorldEnabled(World world) {
        return enabledWorlds.contains(world.getName());
    }

    @EventHandler
    public void onPlayerJoin(PlayerJoinEvent event) {
        Player player = event.getPlayer();
        UUID uuid = player.getUniqueId();

        if (originalHealth.containsKey(uuid) && player.getAttribute(Attribute.GENERIC_MAX_HEALTH) != null) {

            long remainingTicks = 0;
            if (immunityEndTime.containsKey(uuid)) {
                remainingTicks = immunityEndTime.get(uuid) - player.getWorld().getFullTime();
            }

            if (remainingTicks > 0) {
                // Case 1: ACTIVE BUFF (Immunity still running)

                player.getAttribute(Attribute.GENERIC_MAX_HEALTH).setBaseValue(10.0);
                player.setHealth(Math.min(player.getHealth(), 10.0));

                immunePlayers.add(uuid);

                BossBar bar = Bukkit.createBossBar("§2§lZombie Guts Immunity", BarColor.GREEN, BarStyle.SOLID);
                bar.addPlayer(player);
                activeBossBars.put(uuid, bar);

                scheduleImmunityRemoval(player, remainingTicks);

                player.sendMessage("§2§lWelcome back!§a Your Zombie Guts immunity is still active.");
                player.sendMessage("§cWARNING: Your maximum health is still reduced to 5 hearts!");

            } else {
                // Case 2: EXPIRED BUFF (or zero remaining)

                double storedOriginalHealth = originalHealth.get(uuid);

                // RESTORE HEALTH
                player.getAttribute(Attribute.GENERIC_MAX_HEALTH).setBaseValue(storedOriginalHealth);
                player.setHealth(Math.min(player.getHealth(), storedOriginalHealth));
                player.sendMessage("§aYour maximum health has been restored.");

                // CLEAN UP ALL STATE/PERSISTENCE
                cleanUpPlayerState(player);

                // Ensure data.yml is also cleaned up immediately.
                dataConfig.set("player-immunity." + uuid.toString(), null);
                try { dataConfig.save(dataFile); } catch (IOException e) { getLogger().severe("Error saving data on expired health restore: " + e.getMessage()); }

                player.sendMessage("§6§lYour Zombie Guts immunity wore off while you were away.§r");
            }
        }
    }

    @EventHandler
    public void onPlayerQuit(PlayerQuitEvent event) {
        Player player = event.getPlayer();
        UUID uuid = player.getUniqueId();

        if (immunePlayers.contains(uuid)) {

            BukkitTask task = scheduledTasks.remove(uuid);
            if (task != null) {
                task.cancel();
            }

            BossBar bar = activeBossBars.remove(uuid);
            if (bar != null) {
                bar.removeAll();
            }
        }

        // Immediate save on quit to prevent data loss if server crashes later
        saveImmunityData();
    }

    @EventHandler
    public void onEntitySpawn(CreatureSpawnEvent event) {
        if (!isWorldEnabled(event.getLocation().getWorld())) return;

        Entity entity = event.getEntity();
        String mobName = entity.getType().toString();

        boolean inList = mobList.contains(mobName);

        if (useMobBlacklist) {
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
            if (!allowBabyZombies && zombie.isBaby()) { event.setCancelled(true); return; }
            if (!allowZombieVillagers && zombie.isVillager()) { event.setCancelled(true); return; }

            double health = getConfig().getDouble("zombie-settings.health");
            if (zombie.getAttribute(Attribute.GENERIC_MAX_HEALTH) != null) {
                zombie.getAttribute(Attribute.GENERIC_MAX_HEALTH).setBaseValue(health);
                zombie.setHealth(health);
            }
            double damage = getConfig().getDouble("zombie-settings.damage");
            if (zombie.getAttribute(Attribute.GENERIC_ATTACK_DAMAGE) != null) {
                zombie.getAttribute(Attribute.GENERIC_ATTACK_DAMAGE).setBaseValue(damage);
            }
            double speed = getConfig().getDouble("zombie-settings.speed");
            if (zombie.getAttribute(Attribute.GENERIC_MOVEMENT_SPEED) != null) {
                zombie.getAttribute(Attribute.GENERIC_MOVEMENT_SPEED).setBaseValue(speed);
            }
        }
    }

    @EventHandler
    public void onEntityTarget(EntityTargetLivingEntityEvent event) {
        if (!zombieGutsEnabled) return;

        EntityType entityType = event.getEntity().getType();

        if ((entityType == EntityType.ZOMBIE || entityType == EntityType.ZOMBIE_VILLAGER)
                && event.getTarget() instanceof Player player) {

            if (immunePlayers.contains(player.getUniqueId())) {
                event.setCancelled(true);
                if (event.getEntity() instanceof Zombie zombie) {
                    zombie.setTarget(null);
                }
            }
        }
    }

    @EventHandler
    public void onPlayerConsume(PlayerItemConsumeEvent event) {
        if (!zombieGutsEnabled) return;

        ItemStack item = event.getItem();
        Player player = event.getPlayer();
        UUID uuid = player.getUniqueId();

        if (item.getType() == Material.ROTTEN_FLESH && item.hasItemMeta() && item.getItemMeta().hasDisplayName()) {

            String displayName = item.getItemMeta().getDisplayName();

            if (displayName.equals("§2§lZombie Guts")) {
                event.setCancelled(true);

                if (immunePlayers.contains(uuid)) {
                    player.sendMessage("§eYou are already immune! Wait for the effect to wear off.");
                    return;
                }

                double maxHealth = 10.0;
                if (player.getAttribute(Attribute.GENERIC_MAX_HEALTH) != null) {
                    originalHealth.put(uuid, player.getAttribute(Attribute.GENERIC_MAX_HEALTH).getBaseValue());

                    player.getAttribute(Attribute.GENERIC_MAX_HEALTH).setBaseValue(maxHealth);
                    player.setHealth(Math.min(player.getHealth(), maxHealth));
                }

                immunePlayers.add(uuid);

                if (player.getWorld() == null) return;
                long endTime = player.getWorld().getFullTime() + IMMUNITY_DURATION_TICKS;
                immunityEndTime.put(uuid, endTime);

                BossBar bar = Bukkit.createBossBar("§2§lZombie Guts Immunity: §a10:00", BarColor.GREEN, BarStyle.SOLID);
                bar.addPlayer(player);
                activeBossBars.put(uuid, bar);

                scheduleImmunityRemoval(player, IMMUNITY_DURATION_TICKS);

                player.sendMessage("§2§lYou consumed Zombie Guts!§a Zombies will ignore you for 10 minutes.");
                player.sendMessage("§cWARNING: Your maximum health has been reduced to 5 hearts!");

                if (item.getAmount() > 1) {
                    item.setAmount(item.getAmount() - 1);
                } else {
                    if (player.getInventory().getItemInMainHand().equals(item)) {
                        player.getInventory().setItemInMainHand(null);
                    } else if (player.getInventory().getItemInOffHand().equals(item)) {
                        player.getInventory().setItemInOffHand(null);
                    }
                }
            }
        }
    }

    private void scheduleImmunityRemoval(Player player, long durationTicks) {
        UUID uuid = player.getUniqueId();

        BukkitTask task = Bukkit.getScheduler().runTaskLater(this, () -> {

            if (player.isOnline() && immunePlayers.contains(uuid)) {

                if (originalHealth.containsKey(uuid) && player.getAttribute(Attribute.GENERIC_MAX_HEALTH) != null) {
                    double originalMaxHealth = originalHealth.get(uuid);

                    player.getAttribute(Attribute.GENERIC_MAX_HEALTH).setBaseValue(originalMaxHealth);
                    player.setHealth(Math.min(player.getHealth(), originalMaxHealth));
                    player.sendMessage("§aYour maximum health has been restored.");
                }

                cleanUpPlayerState(player);
                player.sendMessage("§6§lYour Zombie Guts immunity has worn off!§r");

                dataConfig.set("player-immunity." + uuid.toString(), null);
                try { dataConfig.save(dataFile); } catch (IOException e) { getLogger().severe("Error saving player expiry data: " + e.getMessage()); }
            }
            scheduledTasks.remove(uuid);

        }, durationTicks);

        scheduledTasks.put(uuid, task);
    }

    private void cleanUpPlayerState(Player player) {
        UUID uuid = player.getUniqueId();

        immunePlayers.remove(uuid);
        immunityEndTime.remove(uuid);
        originalHealth.remove(uuid);

        BukkitTask task = scheduledTasks.remove(uuid);
        if (task != null) {
            task.cancel();
        }

        BossBar bar = activeBossBars.remove(uuid);
        if (bar != null) {
            bar.removeAll();
        }
    }

    @EventHandler
    public void onEntityCombust(EntityCombustEvent event) {
        if (!isWorldEnabled(event.getEntity().getWorld())) return;

        if (event.getEntity() instanceof Zombie) {
            long time = event.getEntity().getWorld().getTime();
            boolean isDay = time > 0 && time < 12300;

            if (isDay) {
                event.setCancelled(true);
            }
        }
    }

    private void startSpawnerTask() {
        long rate = getConfig().getLong("apocalypse-settings.spawn-rate", 1200L);

        Bukkit.getScheduler().cancelTasks(this);

        getLogger().info("TASK START: Custom Spawner Task initialized with rate: " + rate + " ticks (using BukkitRunnable).");

        new HordeSpawnerTask(this).runTaskTimer(this, 0L, rate);
    }

    void spawnZombiesNearPlayer(Player player, boolean isDayHordeSpawn) {
        int baseAmount = getConfig().getInt("apocalypse-settings.base-horde-size", 10);
        int variance = getConfig().getInt("apocalypse-settings.horde-variance", 5);
        int radius = getConfig().getInt("apocalypse-settings.spawn-radius", 40);

        World world = player.getWorld();

        int safeVariance = Math.max(0, variance);

        int totalAmount = baseAmount + ThreadLocalRandom.current().nextInt(safeVariance + 1);

        getLogger().info("DEBUG: Attempting to spawn horde of size: " + totalAmount + " near player: " + player.getName());

        boolean shouldRespectLightLevel = !(isDayHordeSpawn || ignoreLightLevel);

        getLogger().info("DEBUG: Is Day Horde Spawn: " + isDayHordeSpawn +
                ", Global Config Ignore: " + ignoreLightLevel +
                ", FINAL RESPECT LIGHT DECISION: " + shouldRespectLightLevel);

        final int LIGHT_THRESHOLD = 8;

        for (int i = 0; i < totalAmount; i++) {
            double xOffset = ThreadLocalRandom.current().nextDouble(-radius, radius);
            double zOffset = ThreadLocalRandom.current().nextDouble(-radius, radius);
            Location spawnLoc = player.getLocation().add(xOffset, 0, zOffset);

            // --- RELATIVE Y-COORDINATE SEARCH (Prevents roof spawning) ---
            Location searchLoc = spawnLoc.clone().add(0, 3, 0);

            boolean foundSurface = false;
            for (int j = 0; j < 6; j++) {
                if (searchLoc.clone().add(0, -1, 0).getBlock().getType().isSolid()) {
                    spawnLoc.setY(searchLoc.getBlockY());
                    foundSurface = true;
                    break;
                }
                searchLoc.add(0, -1, 0);
            }

            if (!foundSurface) {
                continue;
            }
            // --- END FIX ---


            Material blockType = spawnLoc.getBlock().getRelative(0, -1, 0).getType();
            if (blockType == Material.WATER || blockType == Material.LAVA) continue;

            // --- CRITICAL LIGHT CHECK WITH DEBUGGING & DUAL CHECK ---
            if (shouldRespectLightLevel) {
                int lightLevelSpawn = spawnLoc.getBlock().getLightLevel();
                int lightLevelBelow = spawnLoc.clone().add(0, -1, 0).getBlock().getLightLevel();


                if (lightLevelSpawn >= LIGHT_THRESHOLD || lightLevelBelow >= LIGHT_THRESHOLD) {
                    getLogger().info("DEBUG LIGHT CHECK: Blocked spawn attempt. Spawn: " + lightLevelSpawn +
                            ", Below: " + lightLevelBelow + " (Threshold: " + LIGHT_THRESHOLD + ")");
                    continue;
                } else {
                    getLogger().info("DEBUG LIGHT CHECK: Allowed spawn attempt. Spawn: " + lightLevelSpawn +
                            ", Below: " + lightLevelBelow + " (Threshold: " + LIGHT_THRESHOLD + ")");
                }
            }

            world.spawnEntity(spawnLoc, EntityType.ZOMBIE);
        }
    }

    // --- COMMANDS ---
    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (command.getName().equalsIgnoreCase("zreload")) {
            if (!sender.hasPermission("zombpocalypse.admin")) { sender.sendMessage("§cYou do not have permission to use this command."); return true; }
            Bukkit.getScheduler().cancelTasks(this);
            reloadConfig();
            loadConfigValues();
            startSpawnerTask();
            startBossBarTask();
            sender.sendMessage("§a[Zombpocalypse] Configuration reloaded!");
            return true;
        }

        if (command.getName().equalsIgnoreCase("zitem")) {
            if (!sender.hasPermission("zombpocalypse.admin")) { sender.sendMessage("§cYou do not have permission to use this command."); return true; }
            if (!(sender instanceof Player player)) { sender.sendMessage("§cOnly players can use this command."); return true; }
            if (args.length < 1) { sender.sendMessage("§cUsage: /zitem <item_name>"); return true; }

            if (args[0].equalsIgnoreCase("zombie_guts") && zombieGutsEnabled) {
                ItemStack guts = new ItemStack(Material.ROTTEN_FLESH);
                ItemMeta meta = guts.getItemMeta();
                meta.setDisplayName("§2§lZombie Guts");
                meta.setLore(List.of(
                        "§7Consume to gain temporary immunity", "§7from zombie attacks.", "",
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