package com.deleted.zombpocalypse;

import me.ryanhamshire.GriefPrevention.GriefPrevention;
import org.bukkit.*;
import org.bukkit.attribute.Attribute;
import org.bukkit.block.Block;
import org.bukkit.block.BlockFace;
import org.bukkit.block.data.BlockData;
import org.bukkit.boss.BarColor;
import org.bukkit.boss.BarStyle;
import org.bukkit.boss.BossBar;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.*;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.*;
import org.bukkit.event.entity.EntityDamageEvent.DamageCause;
import org.bukkit.event.player.*;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.plugin.Plugin;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.scheduler.BukkitRunnable;
import org.bukkit.scheduler.BukkitTask;

import java.io.File;
import java.io.IOException;
import java.util.*;
import java.util.concurrent.ThreadLocalRandom;

public class Zombpocalypse extends JavaPlugin implements Listener, CommandExecutor {
    private List<String> enabledWorlds;
    private ZombpocalypseUtils utils;
    private UndeadSpawner undeadSpawner;
    private MessageManager messageManager;
    private PerformanceWatchdog performanceWatchdog;

    // --- PERSISTENCE FIELDS ---
    private File dataFile;
    private FileConfiguration dataConfig;
    
    // CRITICAL FIX: Separate blood moon data file
    private File bloodMoonDataFile;
    private FileConfiguration bloodMoonDataConfig;

    // --- CONFIG VARIABLES ---
    private boolean debugMode;
    private boolean useMobBlacklist;
    private List<String> mobList;
    private boolean allowBabyZombies;
    private boolean allowZombieVillagers;
    private boolean zombieGutsEnabled;

    // --- HOOKS ---
    private boolean griefPreventionEnabled;
    private GriefPrevention griefPrevention;

    // --- BLOOD MOON ---
    private boolean bloodMoonEnabled;
    private boolean forcedBloodMoon = false;
    private int bloodMoonInterval;
    private String bloodMoonTitle;
    private int bloodMoonForceDuration; // CRITICAL FIX: Add force duration config
    private long forcedBloodMoonStartTime = -1; // CRITICAL FIX: Track forced blood moon start time
    private long forcedBloodMoonDuration = -1; // CRITICAL FIX: Track actual forced duration
    public double bmHealthMult;
    public double bmDamageMult;
    public double bmSpeedMult;
    private double bmHordeMult;
    private BossBar bloodMoonBar;
    
    // CRITICAL FIX: Add blood moon persistence fields
    private boolean bloodMoonPersisted = false;
    private long persistedBloodMoonDay = -1;

    // --- IMMUNITY TRACKING ---
    private final List<UUID> immunePlayers = new ArrayList<>();
    private final Map<UUID, BossBar> immunityBossBars = new HashMap<>();
    private final Map<UUID, Long> immunityEndTime = new HashMap<>();
    private final Map<UUID, Double> originalHealth = new HashMap<>();
    private final Map<UUID, BukkitTask> scheduledTasks = new HashMap<>();
    private final long IMMUNITY_DURATION_TICKS = 10 * 60 * 20L;

    // --- SCENT TRACKING ---
    private final Map<UUID, Double> playerScent = new HashMap<>();
    private final Map<UUID, Boolean> playerSprinting = new HashMap<>();
    private final Map<UUID, Long> lastJumpTime = new HashMap<>();

    // --- AI TICKER ---
    private BukkitTask aiTask;

    // --- BUILDER BLOCK TRACKING ---
    private final Map<Location, Long> builderBlocks = new HashMap<>(); // Location -> Timestamp
    private final Map<Location, UUID> builderBlockOwners = new HashMap<>(); // Location -> Zombie UUID

    @Override
    public void onEnable() {
        File configFile = new File(getDataFolder(), "config.yml");
        if (!configFile.exists()) {
            saveResource("config.yml", false);
        }
        saveDefaultConfig();
        
        // CRITICAL FIX: Initialize data files BEFORE loading config values
        // --- Data Persistence Setup ---
        dataFile = new File(getDataFolder(), "data.yml");
        if (!dataFile.exists()) {
            try { dataFile.createNewFile(); } catch (IOException e) { e.printStackTrace(); }
        }
        dataConfig = YamlConfiguration.loadConfiguration(dataFile);
        
        // CRITICAL FIX: Initialize separate blood moon data file
        bloodMoonDataFile = new File(getDataFolder(), "BloodMoonData.yml");
        if (!bloodMoonDataFile.exists()) {
            try { 
                bloodMoonDataFile.createNewFile(); 
                getLogger().info("Created new BloodMoonData.yml file");
            } catch (IOException e) { 
                getLogger().severe("Could not create BloodMoonData.yml: " + e.getMessage());
            }
        }
        bloodMoonDataConfig = YamlConfiguration.loadConfiguration(bloodMoonDataFile);
        
        // CRITICAL FIX: Load blood moon persistence data BEFORE config values
        loadBloodMoonData();
        
        reloadConfig();
        loadConfigValues();

        // --- Message Manager Setup ---
        messageManager = new MessageManager(this);

        loadImmunityData();

        // --- Hooks Setup ---
        setupHooks();

        // --- Utils Setup ---
        utils = new ZombpocalypseUtils(this, griefPrevention, griefPreventionEnabled);
        undeadSpawner = new UndeadSpawner(this, utils);

        // --- Performance Watchdog Setup ---
        performanceWatchdog = new PerformanceWatchdog(this);
        performanceWatchdog.start();

        getServer().getPluginManager().registerEvents(this, this);

        // Register commands
        getCommand("zreload").setExecutor(this);
        getCommand("help").setExecutor(this);
        getCommand("zitem").setExecutor(this);
        getCommand("forcebloodmoon").setExecutor(this);
        getCommand("zspawn").setExecutor(this);

        // Register tab completers
        ZombpocalypseTabCompleter tabCompleter = new ZombpocalypseTabCompleter();
        getCommand("zspawn").setTabCompleter(tabCompleter);
        getCommand("zitem").setTabCompleter(tabCompleter);

        startSpawnerTask();
        startImmunityBossBarTask();
        startBloodMoonTask();
        startScentDecayTask();
        startAITickTask();
        startBuilderCleanupTask();
        startImmunityCheckTask();

        getLogger().info("[Zombpocalypse v1.3] Zombpocalypse has started! Brains...");
    }

    @Override
    public void onDisable() {
        saveImmunityData();
        
        // CRITICAL FIX: Save blood moon persistence data on shutdown using separate file
        saveBloodMoonData();

        if (performanceWatchdog != null) {
            performanceWatchdog.stop();
        }

        for (BossBar bar : immunityBossBars.values()) {
            bar.removeAll();
        }

        if (bloodMoonBar != null) {
            bloodMoonBar.removeAll();
        }

        Bukkit.getScheduler().cancelTasks(this);
    }

    public void debugLog(String message) {
        if (debugMode) {
            getLogger().info("[DEBUG] " + message);
        }
    }

    public MessageManager getMessages() {
        return messageManager;
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

    public boolean isInsideClaim(Location loc) {
        if (!griefPreventionEnabled) return false;
        if (!getConfig().getBoolean("hooks.griefprevention.prevent-spawning-in-claims")) return false;

        return griefPrevention.dataStore.getClaimAt(loc, false, null) != null;
    }

    // === BLOOD MOON LOGIC ===

    public boolean isBloodMoonActive(World world) {
        if (!bloodMoonEnabled) return false;
        if (!isWorldEnabled(world)) return false;

        long time = world.getTime();
        long fullTime = world.getFullTime();
        long dayNumber = fullTime / 24000;

        boolean isDayOf = (dayNumber > 0) && (dayNumber % bloodMoonInterval == 0);
        
        // CRITICAL FIX: Check for persisted blood moon state
        if (bloodMoonPersisted && persistedBloodMoonDay == dayNumber) {
            isDayOf = true;
        }

        if (forcedBloodMoon) {
            isDayOf = true;
        }

        boolean isNight = time >= 13000 && time <= 23000;

        // CRITICAL FIX: For forced blood moon, use actual elapsed time
        if (forcedBloodMoon) {
            if (forcedBloodMoonStartTime == -1) {
                forcedBloodMoonStartTime = System.currentTimeMillis();
            }
            
            long elapsedMs = System.currentTimeMillis() - forcedBloodMoonStartTime;
            long elapsedTicks = elapsedMs / 50; // Convert milliseconds to ticks (20 ticks = 1000ms)
            long actualDuration = forcedBloodMoonDuration != -1 ? forcedBloodMoonDuration : bloodMoonForceDuration;
            long durationTicks = actualDuration * 60 * 20L;
            
            // Check if forced blood moon duration has expired
            if (elapsedTicks >= durationTicks) {
                return false;
            }
            
            return isDayOf; // Still active if duration hasn't expired
        }

        // CRITICAL FIX: Check if blood moon should have ended based on duration
        if (bloodMoonPersisted && isNight) {
            long bloodMoonStartTick = 13000; // Blood moon starts at night
            long currentTick = time;
            long durationTicks = bloodMoonForceDuration * 60 * 20L; // Convert minutes to ticks
            long bloodMoonEndTick = bloodMoonStartTick + durationTicks;
            
            // If current time is past the blood moon duration, it's no longer active
            if (currentTick > bloodMoonEndTick) {
                return false;
            }
        }

        return isDayOf && isNight;
    }

    private void startBloodMoonTask() {
        bloodMoonBar = Bukkit.createBossBar("Blood Moon", BarColor.RED, BarStyle.SEGMENTED_10);

        new BukkitRunnable() {
            @Override
            public void run() {
                if (!bloodMoonEnabled || Bukkit.getWorlds().isEmpty()) return;

                World mainWorld = Bukkit.getWorlds().get(0);

                if (isBloodMoonActive(mainWorld)) {
                    long time = mainWorld.getTime();
                    
                    // CRITICAL FIX: Force night time during blood moon
                    if (time < 13000 || time > 23000) {
                        mainWorld.setTime(14000); // Force to night
                        time = 14000;
                    }
                    
                    // CRITICAL FIX: Use actual command duration, not config default
                    long actualDuration = forcedBloodMoonDuration != -1 ? forcedBloodMoonDuration : bloodMoonForceDuration;
                    long durationTicks = actualDuration * 60 * 20L;
                    long bloodMoonStartTick = 13000;
                    long bloodMoonEndTick = bloodMoonStartTick + durationTicks;
                    long remaining = bloodMoonEndTick - time;

                    if (remaining > 0) {
                        // CRITICAL FIX: Calculate progress for forced blood moon using real time
                        double progress;
                        if (forcedBloodMoon && forcedBloodMoonStartTime != -1) {
                            long elapsedMs = System.currentTimeMillis() - forcedBloodMoonStartTime;
                            long elapsedTicks = elapsedMs / 50;
                            progress = 1.0 - ((double) elapsedTicks / durationTicks);
                        } else {
                            progress = (double) remaining / durationTicks;
                        }
                        bloodMoonBar.setProgress(Math.max(0.0, Math.min(1.0, progress)));

                        // CRITICAL FIX: Calculate remaining time for forced blood moon using real time
                        long totalSeconds;
                        if (forcedBloodMoon && forcedBloodMoonStartTime != -1) {
                            long elapsedMs = System.currentTimeMillis() - forcedBloodMoonStartTime;
                            long elapsedSeconds = elapsedMs / 1000;
                            long durationSeconds = actualDuration * 60; // Use actual duration
                            totalSeconds = Math.max(0, durationSeconds - elapsedSeconds);
                        } else {
                            totalSeconds = remaining / 20;
                        }
                        long minutes = totalSeconds / 60;
                        long seconds = totalSeconds % 60;
                        String timeStr = String.format("%02d:%02d", minutes, seconds);

                        bloodMoonBar.setTitle(org.bukkit.ChatColor.translateAlternateColorCodes('&', bloodMoonTitle.replace("%time%", timeStr)));
                        // CRITICAL FIX: Proper bossbar lifecycle management
                        for (Player p : Bukkit.getOnlinePlayers()) {
                            if (isWorldEnabled(p.getWorld())) {
                                if (!bloodMoonBar.getPlayers().contains(p)) {
                                    bloodMoonBar.addPlayer(p);
                                }
                            } else {
                                if (bloodMoonBar.getPlayers().contains(p)) {
                                    bloodMoonBar.removePlayer(p);
                                }
                            }
                        }
                    } else {
                        // Blood moon ended - CRITICAL FIX: Force cleanup
                        if (!bloodMoonBar.getPlayers().isEmpty()) {
                            bloodMoonBar.removeAll();
                        }
                        
                        // CRITICAL FIX: Reset blood moon persistence when it ends
                        if (bloodMoonPersisted) {
                            bloodMoonPersisted = false;
                            persistedBloodMoonDay = -1;
                            forcedBloodMoon = false;
                            saveBloodMoonData();
                            debugLog("Blood moon ended - persistence reset.");
                        }
                    }
                } else {
                    // CRITICAL FIX: Force bossbar cleanup when blood moon is not active or when it's day
                    if (!bloodMoonBar.getPlayers().isEmpty()) {
                        bloodMoonBar.removeAll();
                        debugLog("Force cleanup: blood moon not active or it's day, removing bossbar");
                    }
                    
                    // CRITICAL FIX: Reset persistence if it's day time (someone used /time set day)
                    long time = mainWorld.getTime();
                    if (time < 13000 || time > 23000) {
                        if (bloodMoonPersisted || forcedBloodMoon) {
                            bloodMoonPersisted = false;
                            persistedBloodMoonDay = -1;
                            forcedBloodMoon = false;
                            saveBloodMoonData();
                            debugLog("Day time detected - blood moon persistence reset");
                        }
                    }
                    
                    // Check for natural blood moon start
                    if (time >= 13000 && time <= 23000) { // Night time
                        long currentDay = mainWorld.getFullTime() / 24000L;
                        if (currentDay % bloodMoonInterval == 0 && !bloodMoonPersisted) {
                            // CRITICAL FIX: Start new blood moon and save state
                            bloodMoonPersisted = true;
                            persistedBloodMoonDay = currentDay;
                            saveBloodMoonData();
                            
                            getLogger().info("Natural blood moon started on day " + currentDay);
                            debugLog("Blood moon persistence: active=" + bloodMoonPersisted + ", day=" + persistedBloodMoonDay);
                            
                            // Notify players
                            for (Player p : Bukkit.getOnlinePlayers()) {
                                if (isWorldEnabled(p.getWorld())) {
                                    p.sendMessage("§4§l☠ BLOOD MOON HAS RISEN! ☠");
                                }
                            }
                        }
                    }
                }
            }
        }.runTaskTimer(this, 20L, 20L);
    }
    
    // CRITICAL FIX: Add method to clean up bossbars for specific player
    private void cleanupBossbarForPlayer(Player player) {
        // Remove from blood moon bossbar
        if (bloodMoonBar != null && bloodMoonBar.getPlayers().contains(player)) {
            bloodMoonBar.removePlayer(player);
        }
    }

    public double getBloodMoonHordeMultiplier() {
        return bmHordeMult;
    }

    // === SCENT SYSTEM ===

    private void startScentDecayTask() {
        if (!getConfig().getBoolean("scent-system.enabled", true)) return;

        int intervalSeconds = getConfig().getInt("scent-system.decay-interval-seconds", 5);
        double decayAmount = getConfig().getDouble("scent-system.decay-amount", 1.0);

        new BukkitRunnable() {
            @Override
            public void run() {
                for (UUID uuid : new ArrayList<>(playerScent.keySet())) {
                    double current = playerScent.get(uuid);
                    double newScent = Math.max(0.0, current - decayAmount);

                    if (newScent <= 0.0) {
                        playerScent.remove(uuid);
                    } else {
                        playerScent.put(uuid, newScent);
                    }
                }
            }
        }.runTaskTimer(this, 0L, intervalSeconds * 20L);
    }

    public double getPlayerScent(UUID uuid) {
        return playerScent.getOrDefault(uuid, 0.0);
    }

    public void addPlayerScent(UUID uuid, double amount) {
        double current = playerScent.getOrDefault(uuid, 0.0);
        playerScent.put(uuid, current + amount);
        debugLog("Player " + uuid + " scent increased by " + amount + " (now: " + (current + amount) + ")");
    }

    @EventHandler
    public void onPlayerToggleSprint(PlayerToggleSprintEvent event) {
        if (!getConfig().getBoolean("scent-system.enabled", true)) return;

        Player player = event.getPlayer();
        UUID uuid = player.getUniqueId();

        if (event.isSprinting()) {
            // Started sprinting
            if (!playerSprinting.getOrDefault(uuid, false)) {
                double sprintAdd = getConfig().getDouble("scent-system.sprint-add", 2.0);
                addPlayerScent(uuid, sprintAdd);
                playerSprinting.put(uuid, true);
            }
        } else {
            // Stopped sprinting
            playerSprinting.put(uuid, false);
        }
    }

    @EventHandler
    public void onPlayerMove(PlayerMoveEvent event) {
        if (!getConfig().getBoolean("scent-system.enabled", true)) return;

        Player player = event.getPlayer();
        UUID uuid = player.getUniqueId();

        // Detect jumping by checking Y velocity (more reliable than position delta)
        // Use cooldown to prevent multiple triggers from the same jump
        long currentTime = System.currentTimeMillis();
        Long lastJump = lastJumpTime.get(uuid);
        
        if (lastJump != null && (currentTime - lastJump) < 500) {
            return; // Cooldown: only trigger once per 500ms
        }

        // Check if player has positive Y velocity (jumping) and was on ground
        if (player.getPose() == org.bukkit.entity.Pose.STANDING && event.getTo() != null && event.getFrom() != null) {
            double velocityY = player.getVelocity().getY();
            if (velocityY > 0.3) { // Significant upward velocity indicates a jump
                double jumpAdd = getConfig().getDouble("scent-system.jump-add", 0.5);
                addPlayerScent(uuid, jumpAdd);
                lastJumpTime.put(uuid, currentTime);
                debugLog("Player " + player.getName() + " jumped, added " + jumpAdd + " scent");
            }
        }
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

        if (!getConfig().getBoolean("scent-system.enabled", true)) return;

        Player killer = event.getEntity().getKiller();
        if (killer != null) {
            double killAdd = getConfig().getDouble("scent-system.kill-add", 1.0);
            addPlayerScent(killer.getUniqueId(), killAdd);
        }

        // Fixed: VETERAN transformation - check if a zombie killed the entity
        Entity deadEntity = event.getEntity();

        // Check if the entity that died was damaged by a zombie
        if (deadEntity.getLastDamageCause() instanceof EntityDamageByEntityEvent damageEvent) {
            Entity damager = damageEvent.getDamager();

            // If a zombie killed this entity, transform it to veteran
            if (damager instanceof Zombie killerZombie) {
                if (getConfig().getBoolean("zombie-classes.veteran.permanent", true)) {
                    utils.transformToVeteran(killerZombie);
                    debugLog("Zombie " + killerZombie.getUniqueId() + " transformed to VETERAN after kill");
                }
            }
        }
    }

    // === AI TICK SYSTEM ===

    private void startAITickTask() {
        aiTask = new BukkitRunnable() {
            @Override
            public void run() {
                for (World world : Bukkit.getWorlds()) {
                    if (!isWorldEnabled(world)) continue;

                    for (Entity entity : world.getEntitiesByClass(Zombie.class)) {
                        if (entity instanceof Zombie zombie) {
                            // LOD System: Only tick AI if zombie is close or LOD system allows it
                            if (performanceWatchdog == null || performanceWatchdog.shouldTickZombieAI(zombie)) {
                                utils.tickZombieAI(zombie);
                            }
                        }
                    }
                }
            }
        }.runTaskTimer(this, 0L, 10L); // Tick every 0.5 seconds
    }

    // === BUILDER BLOCK TRACKING ===

    public void trackBuilderBlock(Location loc, UUID zombieUUID) {
        builderBlocks.put(loc, System.currentTimeMillis());
        builderBlockOwners.put(loc, zombieUUID);
    }

    private void startBuilderCleanupTask() {
        new BukkitRunnable() {
            @Override
            public void run() {
                if (builderBlocks.isEmpty()) return;

                long now = System.currentTimeMillis();
                int cleanupSeconds = getConfig().getInt("cleanup.builder-auto-cleanup-seconds", 300);
                long cleanupMs = cleanupSeconds * 1000L;

                List<Location> toRemove = new ArrayList<>();

                for (Map.Entry<Location, Long> entry : builderBlocks.entrySet()) {
                    if (now - entry.getValue() >= cleanupMs) {
                        Location loc = entry.getKey();
                        if (loc.getBlock().getType() == Material.DIRT) {
                            loc.getBlock().setType(Material.AIR);
                            debugLog("Cleaned up builder block at " + loc);
                        }
                        toRemove.add(loc);
                    }
                }

                for (Location loc : toRemove) {
                    builderBlocks.remove(loc);
                    builderBlockOwners.remove(loc);
                }
            }
        }.runTaskTimer(this, 0L, 100L); // Check every 5 seconds
    }

    // === IMMUNITY SYSTEM ===

    private void loadImmunityData() {
        // Fixed: Null check to prevent startup crash
        if (!dataConfig.isConfigurationSection("player-immunity")) {
            debugLog("No immunity data found in data.yml");
            return;
        }

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

    private void saveImmunityData() {
        // CRITICAL FIX: Add null safety checks
        if (dataConfig == null || dataFile == null) {
            getLogger().warning("Cannot save immunity data - data files not initialized");
            return;
        }
        
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
    
    // CRITICAL FIX: Add blood moon data loading method
    private void loadBloodMoonData() {
        if (bloodMoonDataConfig == null) {
            getLogger().warning("Cannot load blood moon data - bloodMoonDataConfig is null");
            return;
        }
        
        // Load persisted blood moon state from separate file
        bloodMoonPersisted = bloodMoonDataConfig.getBoolean("bloodmoon.persisted", false);
        persistedBloodMoonDay = bloodMoonDataConfig.getLong("bloodmoon.persisted-day", -1);
        forcedBloodMoon = bloodMoonDataConfig.getBoolean("bloodmoon.forced", false);
        
        if (bloodMoonPersisted) {
            getLogger().info("Loaded persisted blood moon from BloodMoonData.yml - day " + persistedBloodMoonDay);
            debugLog("Blood moon persistence: active=" + bloodMoonPersisted + ", day=" + persistedBloodMoonDay + ", forced=" + forcedBloodMoon);
        } else {
            debugLog("No persisted blood moon data found in BloodMoonData.yml");
        }
    }
    
    // CRITICAL FIX: Add blood moon data saving method
    private void saveBloodMoonData() {
        if (bloodMoonDataConfig == null || bloodMoonDataFile == null) {
            getLogger().warning("Cannot save blood moon data - blood moon data files not initialized");
            return;
        }
        
        try {
            bloodMoonDataConfig.set("bloodmoon.persisted", bloodMoonPersisted);
            bloodMoonDataConfig.set("bloodmoon.persisted-day", persistedBloodMoonDay);
            bloodMoonDataConfig.set("bloodmoon.forced", forcedBloodMoon);
            bloodMoonDataConfig.save(bloodMoonDataFile);
            
            debugLog("Saved blood moon data to BloodMoonData.yml: active=" + bloodMoonPersisted + ", day=" + persistedBloodMoonDay + ", forced=" + forcedBloodMoon);
        } catch (IOException e) {
            getLogger().severe("Could not save BloodMoonData.yml: " + e.getMessage());
        }
    }
    
    // CRITICAL FIX: Add immunity check task
    private void startImmunityCheckTask() {
        new BukkitRunnable() {
            @Override
            public void run() {
                if (immunePlayers.isEmpty()) return;
                
                long currentFullTime = Bukkit.getWorlds().isEmpty() ? 0 : Bukkit.getWorlds().get(0).getFullTime();
                
                for (UUID uuid : new ArrayList<>(immunePlayers)) {
                    Player player = Bukkit.getPlayer(uuid);
                    if (player == null || !player.isOnline()) continue;
                    
                    Long endTime = immunityEndTime.get(uuid);
                    if (endTime == null) continue;
                    
                    // Check if immunity has expired
                    if (currentFullTime >= endTime) {
                        debugLog("Immunity expired for player " + player.getName() + ", retargeting zombies");
                        
                        // Remove immunity
                        cleanUpPlayerState(player);
                        
                        // Force nearby zombies to target the player
                        retargetZombiesNearPlayer(player);
                        
                        // Send message to player
                        player.sendMessage(messageManager.get("immunity.expired"));
                        
                        // Clean up data
                        dataConfig.set("player-immunity." + uuid.toString(), null);
                        try { dataConfig.save(dataFile); } catch (IOException e) {}
                    }
                }
            }
        }.runTaskTimer(this, 20L, 20L); // Check every second
    }
    
    // CRITICAL FIX: Add method to retarget zombies near player
    private void retargetZombiesNearPlayer(Player player) {
        Location loc = player.getLocation();
        double radius = 50.0; // Check within 50 blocks
        
        for (Entity entity : loc.getWorld().getNearbyEntities(loc, radius, radius, radius)) {
            if (entity instanceof Zombie zombie) {
                // Only retarget zombies that don't already have a target
                if (zombie.getTarget() == null) {
                    zombie.setTarget(player);
                    debugLog("Retargeted zombie to player " + player.getName());
                }
            }
        }
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

                    Long endTime = immunityEndTime.get(uuid);
                    if (endTime == null) continue;

                    long remainingTicks = endTime - currentFullTime;
                    long remainingSeconds = remainingTicks / 20;

                    if (remainingTicks <= 0) {
                        // Immunity expired - clean up immediately
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
                        continue;
                    }

                    double progress = (double) remainingTicks / IMMUNITY_DURATION_TICKS;
                    immunityBossBars.get(uuid).setProgress(Math.max(0.0, Math.min(1.0, progress)));

                    long minutes = remainingSeconds / 60;
                    long seconds = remainingSeconds % 60;
                    String timeString = String.format("%02d:%02d", minutes, seconds);

                    immunityBossBars.get(uuid).setTitle(messageManager.get("immunity.bossbar", timeString));
                }
            }
        }.runTaskTimer(this, 0L, 5L);
    }

    private void loadConfigValues() {
        enabledWorlds = getConfig().getStringList("enabled-worlds");
        debugMode = getConfig().getBoolean("debug-mode", false);

        useMobBlacklist = getConfig().getBoolean("apocalypse-settings.use-mob-blacklist");
        mobList = getConfig().getStringList("apocalypse-settings.mob-list");

        allowBabyZombies = getConfig().getBoolean("zombie-settings.allow-baby-zombies");
        allowZombieVillagers = getConfig().getBoolean("zombie-settings.allow-zombie-villagers");
        zombieGutsEnabled = getConfig().getBoolean("zombie-settings.zombie-guts.enabled");

        bloodMoonEnabled = getConfig().getBoolean("bloodmoon.enabled");
        bloodMoonInterval = getConfig().getInt("bloodmoon.interval-days", 10);
        bloodMoonTitle = getConfig().getString("bloodmoon.bossbar-title", "Blood Moon");
        bloodMoonForceDuration = getConfig().getInt("bloodmoon.force-duration-minutes", 10); // CRITICAL FIX: Load force duration
        bmHealthMult = getConfig().getDouble("bloodmoon.multipliers.health", 2.0);
        bmDamageMult = getConfig().getDouble("bloodmoon.multipliers.damage", 1.5);
        bmSpeedMult = getConfig().getDouble("bloodmoon.multipliers.speed", 1.2);
        bmHordeMult = getConfig().getDouble("bloodmoon.multipliers.horde-size", 1.5);
        
        // CRITICAL FIX: Load blood moon persistence data from separate file
        bloodMoonPersisted = bloodMoonDataConfig.getBoolean("bloodmoon.persisted", false);
        persistedBloodMoonDay = bloodMoonDataConfig.getLong("bloodmoon.persisted-day", -1);
        forcedBloodMoon = bloodMoonDataConfig.getBoolean("bloodmoon.forced", false);

        getLogger().info("Configuration Loaded. Debug Mode: " + debugMode);
    }

    boolean isWorldEnabled(World world) {
        return enabledWorlds.contains(world.getName());
    }

    // === EVENT HANDLERS ===

    @EventHandler
    public void onPlayerJoin(PlayerJoinEvent event) {
        Player player = event.getPlayer();
        UUID uuid = player.getUniqueId();
        
        // CRITICAL FIX: Clean up any existing bossbars for this player
        cleanupBossbarForPlayer(player);

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

        // CRITICAL FIX: Clean up blood moon bossbar when player quits
        if (bloodMoonBar != null && bloodMoonBar.getPlayers().contains(player)) {
            bloodMoonBar.removePlayer(player);
        }

        playerSprinting.remove(uuid);
        lastJumpTime.remove(uuid);

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
            if (!allowBabyZombies && zombie.getAge() < 0) { event.setCancelled(true); return; }
            if (!allowZombieVillagers && zombie.getType() == EntityType.ZOMBIE_VILLAGER) { event.setCancelled(true); return; }

            // Assign zombie type
            utils.assignZombieType(zombie);
        }
    }

    @EventHandler
    public void onEntityTarget(EntityTargetLivingEntityEvent event) {
        EntityType entityType = event.getEntity().getType();

        if ((entityType == EntityType.ZOMBIE || entityType == EntityType.ZOMBIE_VILLAGER)
                && event.getTarget() instanceof Player player) {

            if (zombieGutsEnabled && immunePlayers.contains(player.getUniqueId())) {
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
                debugLog("Prevented fire damage to " + type + " zombie");
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
        if (!zombieGutsEnabled) return;

        ItemStack item = event.getItem();
        Player player = event.getPlayer();
        UUID uuid = player.getUniqueId();

        if (item.getType() == Material.ROTTEN_FLESH && item.hasItemMeta() && item.getItemMeta().hasDisplayName()) {

            String displayName = item.getItemMeta().getDisplayName();

            if (displayName.equals(messageManager.get("immunity.item-name"))) {
                event.setCancelled(true);

                if (immunePlayers.contains(uuid)) {
                    player.sendMessage(messageManager.get("immunity.already-immune"));
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

                BossBar bar = Bukkit.createBossBar(
                        messageManager.get("immunity.bossbar", "10:00"),
                        BarColor.GREEN,
                        BarStyle.SOLID
                );
                bar.addPlayer(player);
                immunityBossBars.put(uuid, bar);

                scheduleImmunityRemoval(player, IMMUNITY_DURATION_TICKS);

                player.sendMessage(messageManager.get("immunity.consumed"));

                // Clear all zombies currently targeting this player
                for (Entity entity : player.getWorld().getEntitiesByClass(Zombie.class)) {
                    if (entity instanceof Zombie zombie) {
                        if (zombie.getTarget() != null && zombie.getTarget().equals(player)) {
                            zombie.setTarget(null);
                        }
                    }
                }

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

    @EventHandler
    public void onProjectileHit(ProjectileHitEvent event) {
        if (!(event.getEntity() instanceof Snowball snowball)) return;

        String acidTag = snowball.getPersistentDataContainer().get(ZombpocalypseUtils.ZOMBIE_TYPE_KEY, PersistentDataType.STRING);
        if (acidTag != null && acidTag.equals("ACID")) {
            if (event.getHitEntity() != null) {
                utils.handleAcidHit(event.getHitEntity());
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
                    player.sendMessage(messageManager.get("immunity.health-restored"));
                }
                cleanUpPlayerState(player);
                player.sendMessage(messageManager.get("immunity.expired"));
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
        if (event.getEntity() instanceof Zombie zombie) {
            // CRITICAL FIX: Check zombie type before canceling combustion
            ZombpocalypseUtils.ZombieType type = utils.getZombieType(zombie);
            
            // Only cancel combustion for zombies that should be immune to sunlight
            // SCORCHED zombies are fire-immune, others may burn normally
            if (type == ZombpocalypseUtils.ZombieType.SCORCHED) {
                event.setCancelled(true); // Scorched zombies never burn
                return;
            }
            
            // For other zombie types, check if it's day time
            long time = zombie.getWorld().getTime();
            boolean isDay = time > 0 && time < 12300;
            
            // CRITICAL FIX: Only prevent burning during day for non-scorched zombies if config allows
            if (isDay && !getConfig().getBoolean("zombie-settings.allow-daylight-burning", true)) {
                event.setCancelled(true);
            }
        }
    }

    private void startSpawnerTask() {
        long rate = getConfig().getLong("apocalypse-settings.spawn-rate", 1200L);
        Bukkit.getScheduler().cancelTasks(this);

        startImmunityBossBarTask();
        startBloodMoonTask();
        startScentDecayTask();
        startAITickTask();

        getLogger().info("TASK START: Spawner Task @ " + rate + " ticks.");
        new HordeSpawnerTask(this).runTaskTimer(this, 0L, rate);
    }

    void spawnZombiesNearPlayer(Player player, boolean isDayHordeSpawn) {
        // Guard clause: only spawn for SURVIVAL mode players, prevent Elytra spawns
        if (player.getGameMode() != GameMode.SURVIVAL || player.isGliding() || player.isFlying()) {
            return;
        }
        
        int baseAmount = getConfig().getInt("apocalypse-settings.base-horde-size", 6);
        int variance = getConfig().getInt("apocalypse-settings.horde-variance", 4);

        World world = player.getWorld();
        int safeVariance = Math.max(0, variance);

        double multiplier = 1.0;

        // Blood Moon multiplier
        if (isBloodMoonActive(world)) {
            multiplier = getBloodMoonHordeMultiplier();
        }

        // Scent multiplier
        if (getConfig().getBoolean("scent-system.enabled", true)) {
            double scent = getPlayerScent(player.getUniqueId());
            double scentScale = getConfig().getDouble("scent-system.scent-scale", 15.0);
            multiplier *= (1.0 + (scent / scentScale));
        }

        // Apply multiplier BEFORE calculating final amount
        int finalHordeSize = (int) ((baseAmount + ThreadLocalRandom.current().nextInt(safeVariance + 1)) * multiplier);

        // Cap with max-total-zombies instead of scent-system.spawn-cap
        int spawnCap = getConfig().getInt("performance.max-total-zombies", 300);
        finalHordeSize = Math.min(finalHordeSize, spawnCap);

        debugLog("Attempting to spawn horde of size: " + finalHordeSize + " near " + player.getName() + " (Multiplier: " + multiplier + ")");

        int spawnRadius = getConfig().getInt("apocalypse-settings.spawn-radius", 35);

        for (int i = 0; i < finalHordeSize; i++) {
            // Calculate location like /zspawn command
            double xOffset = ThreadLocalRandom.current().nextDouble(-spawnRadius, spawnRadius);
            double zOffset = ThreadLocalRandom.current().nextDouble(-spawnRadius, spawnRadius);
            Location spawnLoc = player.getLocation().clone().add(xOffset, 0, zOffset);

            Location surface = undeadSpawner.getSurfaceSpawnLocation(spawnLoc);
            
            // CRITICAL FIX: Add null check to prevent crashes
            if (surface == null) {
                debugLog("Skipping spawn - surface location is null for player " + player.getName() + " at " + spawnLoc);
                continue; // Skip this spawn attempt
            }
            
            Block surfaceBlock = surface.getBlock().getRelative(BlockFace.DOWN);
            BlockData surfaceData = surfaceBlock.getBlockData();
            
            // Check if rising animation is enabled
            boolean risingAnimation = getConfig().getBoolean("apocalypse-settings.rising-animation", true);
            
            if (risingAnimation) {
                // Use rising animation with staggered delays
                long startDelayTicks = i % 5L;
                undeadSpawner.trySpawnUndeadRise(surface, surfaceBlock, surfaceData, startDelayTicks);
            } else {
                // Spawn directly without animation
                Zombie zombie = (Zombie) surface.getWorld().spawnEntity(surface, EntityType.ZOMBIE);
                if (zombie != null) {
                    utils.assignZombieType(zombie);
                }
            }
        }
    }

    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (command.getName().equalsIgnoreCase("zreload")) {
            if (!sender.hasPermission("zombpocalypse.admin")) {
                sender.sendMessage(messageManager.get("no-permission"));
                return true;
            }

            try {
                Bukkit.getScheduler().cancelTasks(this);
                reloadConfig();
                loadConfigValues();
                messageManager.reload();
                utils.reloadWeights();
                if (performanceWatchdog != null) {
                    performanceWatchdog.reload();
                }
                startSpawnerTask();
                startImmunityBossBarTask();
                startBloodMoonTask();
                startScentDecayTask();
                startAITickTask();
                startBuilderCleanupTask();
                startImmunityCheckTask(); // CRITICAL FIX: Restart immunity check task on reload
                sender.sendMessage(messageManager.getWithPrefix("reload-success"));
            } catch (Exception e) {
                sender.sendMessage(messageManager.getWithPrefix("reload-error", e.getMessage()));
            }
            return true;
        }

        if (command.getName().equalsIgnoreCase("zitem")) {
            if (!sender.hasPermission("zombpocalypse.admin")) {
                sender.sendMessage(messageManager.get("no-permission"));
                return true;
            }
            if (!(sender instanceof Player player)) {
                sender.sendMessage(messageManager.get("player-only"));
                return true;
            }

            if (args.length >= 1 && args[0].equalsIgnoreCase("zombie_guts") && zombieGutsEnabled) {
                ItemStack guts = new ItemStack(Material.ROTTEN_FLESH);
                ItemMeta meta = guts.getItemMeta();
                meta.setDisplayName(messageManager.get("immunity.item-name"));
                meta.setLore(messageManager.getList("immunity.item-lore"));
                guts.setItemMeta(meta);
                player.getInventory().addItem(guts);
                player.sendMessage(messageManager.getWithPrefix("item.received", messageManager.get("immunity.item-name")));
                return true;
            }
            sender.sendMessage(messageManager.getWithPrefix("item.unknown", args.length > 0 ? args[0] : "none"));
            return true;
        }

        if (command.getName().equalsIgnoreCase("forcebloodmoon")) {
            if (!sender.hasPermission("zombpocalypse.admin")) {
                sender.sendMessage(messageManager.get("no-permission"));
                return true;
            }

            if (Bukkit.getWorlds().isEmpty()) return true;
            World world = Bukkit.getWorlds().get(0);

            // CRITICAL FIX: Parse duration argument
            int duration = bloodMoonForceDuration; // Default from config
            if (args.length >= 1) {
                try {
                    duration = Integer.parseInt(args[0]);
                    if (duration < 1) {
                        sender.sendMessage("§cDuration must be at least 1 minute.");
                        return true;
                    }
                    if (duration > 120) {
                        sender.sendMessage("§cDuration cannot exceed 120 minutes.");
                        return true;
                    }
                } catch (NumberFormatException e) {
                    sender.sendMessage("§cInvalid duration. Usage: /forcebloodmoon [minutes]");
                    return true;
                }
            }

            forcedBloodMoon = true;
            forcedBloodMoonStartTime = System.currentTimeMillis(); // CRITICAL FIX: Track start time
            forcedBloodMoonDuration = duration; // CRITICAL FIX: Store actual duration
            
            // CRITICAL FIX: Save forced blood moon state
            saveBloodMoonData();
            
            sender.sendMessage(messageManager.get("bloodmoon.force-start") + " §7(§e" + duration + " minutes§7)");

            long time = world.getTime();
            if (time < 13000 || time > 23000) {
                world.setTime(13000);
                sender.sendMessage(messageManager.get("bloodmoon.force-time-set"));
            }
            
            getLogger().info("Blood moon force started by " + sender.getName() + " for " + duration + " minutes");

            return true;
        }

        if (command.getName().equalsIgnoreCase("stopbloodmoon")) {
            if (!sender.hasPermission("zombpocalypse.admin")) {
                sender.sendMessage(messageManager.get("no-permission"));
                return true;
            }

            // CRITICAL FIX: Stop blood moon and clean up
            if (bloodMoonPersisted || forcedBloodMoon) {
                bloodMoonPersisted = false;
                persistedBloodMoonDay = -1;
                forcedBloodMoon = false;
                forcedBloodMoonStartTime = -1; // CRITICAL FIX: Reset forced start time
                forcedBloodMoonDuration = -1; // CRITICAL FIX: Reset forced duration
                saveBloodMoonData();
                
                // CRITICAL FIX: Force bossbar cleanup
                if (!bloodMoonBar.getPlayers().isEmpty()) {
                    bloodMoonBar.removeAll();
                }
                
                // CRITICAL FIX: Set time to day to prevent immediate restart
                if (Bukkit.getWorlds().isEmpty()) return true;
                World world = Bukkit.getWorlds().get(0);
                world.setTime(1000); // Set to day time
                sender.sendMessage("§7Time set to day to prevent restart.");
                
                sender.sendMessage("§cBlood moon stopped manually.");
                getLogger().info("Blood moon stopped by " + sender.getName() + " - time set to day");
                debugLog("Manual blood moon stop - persistence reset, time set to day.");
            } else {
                sender.sendMessage("§eNo blood moon is currently active.");
            }

            return true;
        }

        if (command.getName().equalsIgnoreCase("zspawn")) {
            if (!sender.hasPermission("zombpocalypse.command.zspawn")) {
                sender.sendMessage("§cNo permission."); return true;
            }

            if (!(sender instanceof Player player)) {
                sender.sendMessage("§cOnly players can use this command.");
                return true;
            }

            if (args.length < 1) {
                sender.sendMessage("§cUsage: /zspawn <type|horde> [count] [radius]");
                return true;
            }

            String typeArg = args[0].toUpperCase();
            int count = 1;
            int radius = 5;

            if (args.length >= 2) {
                try {
                    count = Integer.parseInt(args[1]);
                } catch (NumberFormatException e) {
                    sender.sendMessage("§cInvalid count number.");
                    return true;
                }
            }

            if (args.length >= 3) {
                try {
                    radius = Integer.parseInt(args[2]);
                } catch (NumberFormatException e) {
                    sender.sendMessage("§cInvalid radius number.");
                    return true;
                }
            }

            count = Math.min(count, getConfig().getInt("performance.max-total-zombies", 300)); // Use config value
            radius = Math.min(radius, 50); // Keep radius reasonable

            if (typeArg.equals("HORDE")) {
                // Spawn mixed horde
                for (int i = 0; i < count; i++) {
                    Location spawnLoc = player.getLocation().add(
                            ThreadLocalRandom.current().nextDouble(-radius, radius),
                            0,
                            ThreadLocalRandom.current().nextDouble(-radius, radius)
                    );

                    if (isInsideClaim(spawnLoc)) continue;

                    Zombie zombie = (Zombie) player.getWorld().spawnEntity(spawnLoc, EntityType.ZOMBIE);
                    // Type will be assigned via spawn event
                }
                sender.sendMessage("§aSpawned horde of " + count + " zombies!");
                return true;
            }

            // Specific type
            ZombpocalypseUtils.ZombieType type;
            try {
                type = ZombpocalypseUtils.ZombieType.valueOf(typeArg);
            } catch (IllegalArgumentException e) {
                sender.sendMessage("§cInvalid zombie type. Valid types: " + Arrays.toString(ZombpocalypseUtils.ZombieType.values()));
                return true;
            }

            for (int i = 0; i < count; i++) {
                Location spawnLoc = player.getLocation().add(
                        ThreadLocalRandom.current().nextDouble(-radius, radius),
                        0,
                        ThreadLocalRandom.current().nextDouble(-radius, radius)
                );

                if (isInsideClaim(spawnLoc)) continue;

                Zombie zombie = (Zombie) player.getWorld().spawnEntity(spawnLoc, EntityType.ZOMBIE);
                utils.applyZombieType(zombie, type);
            }

            sender.sendMessage("§aSpawned " + count + " " + type.name() + " zombies!");
            return true;
        }

        if (command.getName().equalsIgnoreCase("help")) {
            sender.sendMessage(messageManager.get("commands.help.header"));
            sender.sendMessage(messageManager.get("commands.help.author"));
            sender.sendMessage(messageManager.get("commands.help.reload"));
            sender.sendMessage(messageManager.get("commands.help.item"));
            sender.sendMessage(messageManager.get("commands.help.bloodmoon"));
            sender.sendMessage(messageManager.get("commands.help.spawn"));
            return true;
        }
        return false;
    }
}