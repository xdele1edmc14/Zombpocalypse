package com.deleted.zombpocalypse;

import me.ryanhamshire.GriefPrevention.GriefPrevention;
import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
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
import org.bukkit.plugin.Plugin;
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
    private boolean debugMode;
    private double daySpawnChance;
    private boolean useMobBlacklist;
    private List<String> mobList;
    private boolean ignoreLightLevel;
    private boolean allowBabyZombies;
    private boolean allowZombieVillagers;
    private boolean zombieGutsEnabled;

    // --- HOOKS ---
    private boolean griefPreventionEnabled;
    private GriefPrevention griefPrevention;

    // --- BLOOD MOON ---
    private boolean bloodMoonEnabled;
    private boolean forcedBloodMoon = false; // <-- NEW FLAG
    private int bloodMoonInterval;
    private String bloodMoonTitle;
    private double bmHealthMult;
    private double bmDamageMult;
    private double bmSpeedMult;
    private double bmHordeMult;
    private BossBar bloodMoonBar;
    // ------------------

    // --- IMMUNITY TRACKING ---
    private final List<UUID> immunePlayers = new ArrayList<>();
    private final Map<UUID, BossBar> immunityBossBars = new HashMap<>();
    private final Map<UUID, Long> immunityEndTime = new HashMap<>();
    private final Map<UUID, Double> originalHealth = new HashMap<>();
    private final Map<UUID, BukkitTask> scheduledTasks = new HashMap<>();

    private final long IMMUNITY_DURATION_TICKS = 10 * 60 * 20L;

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

        // --- Hooks Setup ---
        setupHooks();

        getServer().getPluginManager().registerEvents(this, this);
        getCommand("zreload").setExecutor(this);
        getCommand("help").setExecutor(this);
        getCommand("zitem").setExecutor(this);
        getCommand("forcebloodmoon").setExecutor(this); // <-- REGISTER COMMAND

        startSpawnerTask();
        startImmunityBossBarTask();
        startBloodMoonTask();

        getLogger().info("[Zombpocalypse] Zombpocalypse has started! Brains...");
    }

    @Override
    public void onDisable() {
        saveImmunityData();

        // Clear Immunity Bars
        for (BossBar bar : immunityBossBars.values()) {
            bar.removeAll();
        }

        // Clear Blood Moon Bar
        if (bloodMoonBar != null) {
            bloodMoonBar.removeAll();
        }

        Bukkit.getScheduler().cancelTasks(this);
    }

    // --- HELPER FOR DEBUGGING ---
    public void debugLog(String message) {
        if (debugMode) {
            getLogger().info("[DEBUG] " + message);
        }
    }

    private void setupHooks() {
        if (getConfig().getBoolean("hooks.griefprevention.enabled")) {
            Plugin gp = getServer().getPluginManager().getPlugin("GriefPrevention");
            if (gp instanceof GriefPrevention) {
                this.griefPrevention = (GriefPrevention) gp;
                this.griefPreventionEnabled = true;
                getLogger().info("Hooked into GriefPrevention successfully.");
            }
        }
    }

    // Check if a location is inside a claim where we shouldn't spawn
    public boolean isInsideClaim(Location loc) {
        if (!griefPreventionEnabled) return false;
        if (!getConfig().getBoolean("hooks.griefprevention.prevent-spawning-in-claims")) return false;

        // GriefPrevention API check
        return griefPrevention.dataStore.getClaimAt(loc, false, null) != null;
    }

    // --- BLOOD MOON LOGIC ---

    public boolean isBloodMoonActive(World world) {
        if (!bloodMoonEnabled) return false;
        if (!isWorldEnabled(world)) return false;

        long time = world.getTime();
        long fullTime = world.getFullTime();
        long dayNumber = fullTime / 24000;

        // Check if it's the correct day cycle OR if manually forced
        boolean isDayOf = (dayNumber > 0) && (dayNumber % bloodMoonInterval == 0);

        if (forcedBloodMoon) {
            isDayOf = true;
        }

        // Check if it's night time (approx 13000 to 23000)
        boolean isNight = time >= 13000 && time <= 23000;

        return isDayOf && isNight;
    }

    private void startBloodMoonTask() {
        bloodMoonBar = Bukkit.createBossBar("Blood Moon", BarColor.RED, BarStyle.SEGMENTED_10);

        new BukkitRunnable() {
            @Override
            public void run() {
                if (!bloodMoonEnabled || Bukkit.getWorlds().isEmpty()) return;

                // We use the first world to drive the global event for now
                World mainWorld = Bukkit.getWorlds().get(0);

                if (isBloodMoonActive(mainWorld)) {
                    long time = mainWorld.getTime();
                    long endTime = 23000;
                    long remaining = endTime - time;

                    // Update BossBar
                    if (remaining > 0) {
                        double progress = (double) remaining / 10000.0; // Night lasts ~10000 ticks
                        bloodMoonBar.setProgress(Math.max(0.0, Math.min(1.0, progress)));

                        long totalSeconds = remaining / 20;
                        long minutes = totalSeconds / 60;
                        long seconds = totalSeconds % 60;
                        String timeStr = String.format("%02d:%02d", minutes, seconds);

                        bloodMoonBar.setTitle(ChatColor.translateAlternateColorCodes('&',
                                bloodMoonTitle.replace("%time%", timeStr)));

                        // Add players in enabled worlds to bar
                        for (Player p : Bukkit.getOnlinePlayers()) {
                            if (isWorldEnabled(p.getWorld()) && !bloodMoonBar.getPlayers().contains(p)) {
                                bloodMoonBar.addPlayer(p);
                            }
                        }
                    }
                } else {
                    // Not active. If it WAS forced and it is now morning (> 23000), reset the flag
                    // so we don't have endless blood moons every night.
                    if (forcedBloodMoon && mainWorld.getTime() > 23000) {
                        forcedBloodMoon = false;
                        debugLog("Forced Blood Moon flag reset (Morning arrived).");
                    }

                    // Not Blood Moon, remove everyone
                    if (!bloodMoonBar.getPlayers().isEmpty()) {
                        bloodMoonBar.removeAll();
                    }
                }
            }
        }.runTaskTimer(this, 20L, 20L); // Run every second
    }

    // --- EXPOSED CONFIG GETTERS FOR TASKS ---
    public double getBloodMoonHordeMultiplier() {
        return bmHordeMult;
    }

    private void loadImmunityData() {
        if (dataConfig.contains("player-immunity")) {
            long currentFullTime = Bukkit.getWorlds().isEmpty() ? 0 : Bukkit.getWorlds().get(0).getFullTime();

            for (String key : dataConfig.getConfigurationSection("player-immunity").getKeys(false)) {
                try {
                    UUID uuid = UUID.fromString(key);
                    long remainingTicks = dataConfig.getLong("player-immunity." + key + ".endTime");
                    double originalHealthVal = dataConfig.getDouble("player-immunity." + key + ".originalHealth");

                    if (originalHealthVal <= 0.0) continue;

                    originalHealth.put(uuid, originalHealthVal);

                    if (remainingTicks > IMMUNITY_DURATION_TICKS) {
                        remainingTicks = IMMUNITY_DURATION_TICKS;
                    }

                    if (remainingTicks > 0) {
                        long newEndTime = currentFullTime + remainingTicks;
                        immunityEndTime.put(uuid, newEndTime);
                        immunePlayers.add(uuid);
                        debugLog("Loaded active immunity for " + key);
                    }

                } catch (IllegalArgumentException e) {
                    getLogger().warning("Invalid UUID in data.yml: " + key);
                }
            }
        }
    }

    private void saveImmunityData() {
        dataConfig.set("player-immunity", null);
        long currentFullTime = Bukkit.getWorlds().isEmpty() ? 0 : Bukkit.getWorlds().get(0).getFullTime();

        for (UUID uuid : new ArrayList<>(originalHealth.keySet())) {
            String path = "player-immunity." + uuid.toString();
            double storedHealth = originalHealth.get(uuid);
            long remainingTicks = 0;

            if (immunityEndTime.containsKey(uuid)) {
                remainingTicks = immunityEndTime.get(uuid) - currentFullTime;
            }

            if (storedHealth > 0.0) {
                long finalRemainingTicks = Math.max(0, remainingTicks);
                finalRemainingTicks = Math.min(finalRemainingTicks, IMMUNITY_DURATION_TICKS);
                dataConfig.set(path + ".endTime", finalRemainingTicks);
                dataConfig.set(path + ".originalHealth", storedHealth);
            }
        }
        try { dataConfig.save(dataFile); } catch (IOException e) { e.printStackTrace(); }
    }

    private void startImmunityBossBarTask() {
        new BukkitRunnable() {
            @Override
            public void run() {
                if (immunityBossBars.isEmpty()) return;
                long currentFullTime = Bukkit.getWorlds().isEmpty() ? 0 : Bukkit.getWorlds().get(0).getFullTime();

                for (UUID uuid : new ArrayList<>(immunityBossBars.keySet())) {
                    Player player = Bukkit.getPlayer(uuid);
                    if (player == null || !player.isOnline()) { continue; }
                    if (player.getWorld() == null) continue;

                    long remainingTicks = immunityEndTime.get(uuid) - currentFullTime;
                    long remainingSeconds = remainingTicks / 20;

                    if (remainingTicks <= 0) continue;

                    double progress = (double) remainingTicks / IMMUNITY_DURATION_TICKS;
                    immunityBossBars.get(uuid).setProgress(Math.max(0.0, Math.min(1.0, progress)));

                    long minutes = remainingSeconds / 60;
                    long seconds = remainingSeconds % 60;
                    String timeString = String.format("%02d:%02d", minutes, seconds);

                    immunityBossBars.get(uuid).setTitle("§2§lZombie Guts Immunity: §a" + timeString);
                }
            }
        }.runTaskTimer(this, 0L, 5L);
    }

    private void loadConfigValues() {
        // Core
        enabledWorlds = getConfig().getStringList("enabled-worlds");
        debugMode = getConfig().getBoolean("debug-mode", false);

        // General
        daySpawnChance = getConfig().getDouble("apocalypse-settings.day-spawn-chance");
        useMobBlacklist = getConfig().getBoolean("apocalypse-settings.use-mob-blacklist");
        mobList = getConfig().getStringList("apocalypse-settings.mob-list");
        ignoreLightLevel = getConfig().getBoolean("apocalypse-settings.ignore-light-level");

        // Zombie Specifics
        allowBabyZombies = getConfig().getBoolean("zombie-settings.allow-baby-zombies");
        allowZombieVillagers = getConfig().getBoolean("zombie-settings.allow-zombie-villagers");
        zombieGutsEnabled = getConfig().getBoolean("zombie-settings.zombie-guts.enabled");

        // Blood Moon
        bloodMoonEnabled = getConfig().getBoolean("bloodmoon.enabled");
        bloodMoonInterval = getConfig().getInt("bloodmoon.interval-days", 10);
        bloodMoonTitle = getConfig().getString("bloodmoon.bossbar-title", "Blood Moon");
        bmHealthMult = getConfig().getDouble("bloodmoon.multipliers.health", 2.0);
        bmDamageMult = getConfig().getDouble("bloodmoon.multipliers.damage", 1.5);
        bmSpeedMult = getConfig().getDouble("bloodmoon.multipliers.speed", 1.2);
        bmHordeMult = getConfig().getDouble("bloodmoon.multipliers.horde-size", 1.5);

        getLogger().info("Configuration Loaded. Debug Mode: " + debugMode + ", GriefPrevention: " + getConfig().getBoolean("hooks.griefprevention.enabled"));
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
                player.getAttribute(Attribute.GENERIC_MAX_HEALTH).setBaseValue(10.0);
                player.setHealth(Math.min(player.getHealth(), 10.0));
                immunePlayers.add(uuid);

                BossBar bar = Bukkit.createBossBar("§2§lZombie Guts Immunity", BarColor.GREEN, BarStyle.SOLID);
                bar.addPlayer(player);
                immunityBossBars.put(uuid, bar);

                scheduleImmunityRemoval(player, remainingTicks);
            } else {
                double storedOriginalHealth = originalHealth.get(uuid);
                player.getAttribute(Attribute.GENERIC_MAX_HEALTH).setBaseValue(storedOriginalHealth);
                player.setHealth(Math.min(player.getHealth(), storedOriginalHealth));
                cleanUpPlayerState(player);
                dataConfig.set("player-immunity." + uuid.toString(), null);
                try { dataConfig.save(dataFile); } catch (IOException e) {}
            }
        }
    }

    @EventHandler
    public void onPlayerQuit(PlayerQuitEvent event) {
        Player player = event.getPlayer();
        UUID uuid = player.getUniqueId();

        if (immunePlayers.contains(uuid)) {
            BukkitTask task = scheduledTasks.remove(uuid);
            if (task != null) task.cancel();

            BossBar bar = immunityBossBars.remove(uuid);
            if (bar != null) bar.removeAll();
        }

        // Also remove from Blood Moon bar to prevent memory leaks
        if (bloodMoonBar != null) {
            bloodMoonBar.removePlayer(player);
        }

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

            // Base Stats
            double health = getConfig().getDouble("zombie-settings.health");
            double damage = getConfig().getDouble("zombie-settings.damage");
            double speed = getConfig().getDouble("zombie-settings.speed");

            // Blood Moon Multipliers
            if (isBloodMoonActive(zombie.getWorld())) {
                health *= bmHealthMult;
                damage *= bmDamageMult;
                speed *= bmSpeedMult;
                // We don't log this every time to avoid spam, but it's happening
            }

            if (zombie.getAttribute(Attribute.GENERIC_MAX_HEALTH) != null) {
                zombie.getAttribute(Attribute.GENERIC_MAX_HEALTH).setBaseValue(health);
                zombie.setHealth(health);
            }
            if (zombie.getAttribute(Attribute.GENERIC_ATTACK_DAMAGE) != null) {
                zombie.getAttribute(Attribute.GENERIC_ATTACK_DAMAGE).setBaseValue(damage);
            }
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
                immunityBossBars.put(uuid, bar);

                scheduleImmunityRemoval(player, IMMUNITY_DURATION_TICKS);

                player.sendMessage("§2§lYou consumed Zombie Guts!§a Zombies will ignore you for 10 minutes.");

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
                try { dataConfig.save(dataFile); } catch (IOException e) {}
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
        if (task != null) task.cancel();

        BossBar bar = immunityBossBars.remove(uuid);
        if (bar != null) bar.removeAll();
    }

    @EventHandler
    public void onEntityCombust(EntityCombustEvent event) {
        if (!isWorldEnabled(event.getEntity().getWorld())) return;
        if (event.getEntity() instanceof Zombie) {
            long time = event.getEntity().getWorld().getTime();
            boolean isDay = time > 0 && time < 12300;
            if (isDay) event.setCancelled(true);
        }
    }

    private void startSpawnerTask() {
        long rate = getConfig().getLong("apocalypse-settings.spawn-rate", 1200L);
        Bukkit.getScheduler().cancelTasks(this); // Be careful with this if adding other tasks!

        // Re-start tasks since we cancelled all
        startImmunityBossBarTask();
        startBloodMoonTask();

        getLogger().info("TASK START: Spawner Task @ " + rate + " ticks.");
        new HordeSpawnerTask(this).runTaskTimer(this, 0L, rate);
    }

    void spawnZombiesNearPlayer(Player player, boolean isDayHordeSpawn) {
        int baseAmount = getConfig().getInt("apocalypse-settings.base-horde-size", 10);
        int variance = getConfig().getInt("apocalypse-settings.horde-variance", 5);
        int radius = getConfig().getInt("apocalypse-settings.spawn-radius", 40);

        World world = player.getWorld();
        int safeVariance = Math.max(0, variance);

        double multiplier = 1.0;
        if (isBloodMoonActive(world)) {
            multiplier = getBloodMoonHordeMultiplier();
        }

        int totalAmount = (int) ((baseAmount + ThreadLocalRandom.current().nextInt(safeVariance + 1)) * multiplier);

        debugLog("Attempting to spawn horde of size: " + totalAmount + " near " + player.getName() + " (Multiplier: " + multiplier + ")");

        boolean shouldRespectLightLevel = !(isDayHordeSpawn || ignoreLightLevel);
        final int LIGHT_THRESHOLD = 8;

        for (int i = 0; i < totalAmount; i++) {
            double xOffset = ThreadLocalRandom.current().nextDouble(-radius, radius);
            double zOffset = ThreadLocalRandom.current().nextDouble(-radius, radius);
            Location spawnLoc = player.getLocation().add(xOffset, 0, zOffset);

            // --- GRIEF PREVENTION CHECK ---
            if (isInsideClaim(spawnLoc)) {
                debugLog("Blocked spawn inside GriefPrevention claim.");
                continue;
            }

            // --- RELATIVE Y-COORDINATE SEARCH ---
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
            if (!foundSurface) continue;

            Material blockType = spawnLoc.getBlock().getRelative(0, -1, 0).getType();
            if (blockType == Material.WATER || blockType == Material.LAVA) continue;

            if (shouldRespectLightLevel) {
                int lightLevelSpawn = spawnLoc.getBlock().getLightLevel();
                int lightLevelBelow = spawnLoc.clone().add(0, -1, 0).getBlock().getLightLevel();

                if (lightLevelSpawn >= LIGHT_THRESHOLD || lightLevelBelow >= LIGHT_THRESHOLD) {
                    continue;
                }
            }
            world.spawnEntity(spawnLoc, EntityType.ZOMBIE);
        }
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (command.getName().equalsIgnoreCase("zreload")) {
            if (!sender.hasPermission("zombpocalypse.admin")) {
                sender.sendMessage("§cNo permission."); return true;
            }
            Bukkit.getScheduler().cancelTasks(this);
            reloadConfig();
            loadConfigValues();
            startSpawnerTask();
            startImmunityBossBarTask();
            startBloodMoonTask();
            sender.sendMessage("§a[Zombpocalypse] Config & Systems reloaded!");
            return true;
        }

        if (command.getName().equalsIgnoreCase("zitem")) {
            if (!sender.hasPermission("zombpocalypse.admin")) return true;
            if (!(sender instanceof Player player)) return true;

            if (args.length >= 1 && args[0].equalsIgnoreCase("zombie_guts") && zombieGutsEnabled) {
                ItemStack guts = new ItemStack(Material.ROTTEN_FLESH);
                ItemMeta meta = guts.getItemMeta();
                meta.setDisplayName("§2§lZombie Guts");
                meta.setLore(List.of("§7Consume to gain temporary immunity", "§cReduces Max Health to 5 Hearts for 10 minutes."));
                guts.setItemMeta(meta);
                player.getInventory().addItem(guts);
                player.sendMessage("§aObtained Zombie Guts!");
                return true;
            }
            sender.sendMessage("§cUnknown item.");
            return true;
        }

        // --- NEW FORCE BLOOD MOON COMMAND ---
        if (command.getName().equalsIgnoreCase("forcebloodmoon")) {
            if (!sender.hasPermission("zombpocalypse.admin")) {
                sender.sendMessage("§cNo permission."); return true;
            }

            if (Bukkit.getWorlds().isEmpty()) return true;
            World world = Bukkit.getWorlds().get(0);

            forcedBloodMoon = true;
            sender.sendMessage("§4§l[Zombpocalypse] §cBlood Moon has been FORCE STARTED!");

            long time = world.getTime();
            if (time < 13000 || time > 23000) {
                world.setTime(13000); // Set to start of night
                sender.sendMessage("§7(Time set to night to begin event immediately)");
            }

            return true;
        }

        if (command.getName().equalsIgnoreCase("help")) {
            sender.sendMessage("§4§l--- Zombpocalypse v1.2 ---");
            sender.sendMessage("§aBy xDele1ed.");
            sender.sendMessage("§b/zreload§7: Reload config.");
            sender.sendMessage("§b/zitem zombie_guts§7: Get guts.");
            sender.sendMessage("§b/forcebloodmoon§7: Start Blood Moon.");
            return true;
        }
        return false;
    }
}