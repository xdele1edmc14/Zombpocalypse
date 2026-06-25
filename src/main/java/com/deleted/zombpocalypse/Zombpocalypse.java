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
    private List<String> lobbyWorlds;
    private ZombpocalypseUtils utils;
    private UndeadSpawner undeadSpawner;
    private MessageManager messageManager;
    private PerformanceWatchdog performanceWatchdog;
    private MythicMobsManager mythicMobsManager;

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
    // BUGFIX: immunity timing now runs off the real-world clock instead of world.getFullTime(),
    // because world.setTime() calls elsewhere (blood moon start/stop/correction) warp full-time
    // by up to +-24000 ticks instantly, which was corrupting every active immunity countdown.
    private final long IMMUNITY_DURATION_MILLIS = IMMUNITY_DURATION_TICKS * 50L;

    // --- SCENT TRACKING ---
    private final Map<UUID, Double> playerScent = new HashMap<>();
    private final Map<UUID, Boolean> playerSprinting = new HashMap<>();
    private final Map<UUID, Long> lastJumpTime = new HashMap<>();
    // Bug C4 fix: previous-tick ground state so onPlayerMove can detect the on-ground -> airborne jump edge
    private final Map<UUID, Boolean> playerWasOnGround = new HashMap<>();
    private BukkitTask scentDecayTask;
    private BukkitTask scentSprintTask;

    // --- AI TICKER ---
    private BukkitTask aiTask;

    // Bug 1/13 fix: track blood moon task so it can be cancelled on reload/shutdown
    private BukkitTask bloodMoonTask = null;

    // Bug 4 & 20 fix: flag so onEntitySpawn bypasses the mob-list check for plugin-spawned entities
    private boolean isPluginSpawning = false;

    /** Called by UndeadSpawner before/after world.spawnEntity so onEntitySpawn skips the mob-list check. */
    void setPluginSpawning(boolean value) {
        this.isPluginSpawning = value;
    }

    /** Exposed so HordeSpawnerTask can query the spawning-paused state. */
    public PerformanceWatchdog getPerformanceWatchdog() {
        return performanceWatchdog;
    }

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

        // --- MythicMobs Integration ---
        mythicMobsManager = new MythicMobsManager(this);

        // BUGFIX: if a blood moon was already active (persisted or forced) when the
        // plugin (re)enabled, the bossbar/visual side resumes fine on its own via
        // isBloodMoonActive(), but mythicMobsManager.onBloodMoonStart() was never
        // called again — it's only invoked from the "natural blood moon start" branch,
        // which is gated on !bloodMoonPersisted and so can't fire a second time. That
        // silently orphaned the guaranteed-mutant spawn loop on every restart/reload
        // that happened mid-blood-moon.
        if (bloodMoonPersisted || forcedBloodMoon) {
            mythicMobsManager.onBloodMoonStart();
            debugLog("Resumed MythicMobs blood moon tick loop after (re)enable - persisted=" + bloodMoonPersisted + ", forced=" + forcedBloodMoon);
        }

        // --- Performance Watchdog Setup ---
        // Bug C2 fix: only construct it here. start() is called AFTER startSpawnerTask()
        // below, because startSpawnerTask() calls cancelTasks(this) which would otherwise
        // immediately kill the watchdog's TPS-monitor and LOD tasks.
        performanceWatchdog = new PerformanceWatchdog(this);

        getServer().getPluginManager().registerEvents(this, this);

        // Register commands
        getCommand("zreload").setExecutor(this);
        getCommand("help").setExecutor(this);
        getCommand("zitem").setExecutor(this);
        getCommand("forcebloodmoon").setExecutor(this);
        getCommand("stopbloodmoon").setExecutor(this);
        getCommand("zspawn").setExecutor(this);

        // Register tab completers
        ZombpocalypseTabCompleter tabCompleter = new ZombpocalypseTabCompleter();
        getCommand("zspawn").setTabCompleter(tabCompleter);
        getCommand("zitem").setTabCompleter(tabCompleter);

        startSpawnerTask();
        startBuilderCleanupTask();
        startImmunityCheckTask();

        // Bug C2 fix: start the watchdog AFTER startSpawnerTask() so its tasks survive
        // the cancelTasks(this) call inside startSpawnerTask().
        performanceWatchdog.start();

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
        // Bug C5 fix: remove the previous bossbar from all players before replacing the
        // reference. On /zreload this method runs again; without this, each reload left an
        // orphaned bar stuck in every player's HUD (visible and a memory leak) until restart.
        if (bloodMoonBar != null) {
            bloodMoonBar.removeAll();
        }
        bloodMoonBar = Bukkit.createBossBar("Blood Moon", BarColor.RED, BarStyle.SEGMENTED_10);

        new BukkitRunnable() {
            @Override
            public void run() {
                if (!bloodMoonEnabled || Bukkit.getWorlds().isEmpty()) return;

                // Fix RC1: Use the first *enabled* world, not Bukkit.getWorlds().get(0).
                // With Multiverse/BetterRTP, world[0] can be a lobby or temp world that
                // is NOT in enabledWorlds.  isBloodMoonActive() short-circuits on
                // !isWorldEnabled(), returns false, and the else-branch below would
                // spuriously reset forcedBloodMoon=false, killing the MM spawn loop.
                World mainWorld = Bukkit.getWorlds().stream()
                        .filter(Zombpocalypse.this::isWorldEnabled)
                        .findFirst()
                        .orElse(null);
                if (mainWorld == null) return;

                // Fix RC4: Detect real-time forced-blood-moon expiry HERE, before
                // calling isBloodMoonActive().  When duration runs out during nighttime,
                // isBloodMoonActive() returns false but the else-branch's daytime guard
                // (time < 13000 || time > 23000) never fires (task keeps time at ~14000).
                // Without this, forcedBloodMoon stays true as a zombie state and
                // onBloodMoonEnd() is never signalled to the MM tick loop.
                if (forcedBloodMoon && forcedBloodMoonStartTime != -1) {
                    long elapsedMs = System.currentTimeMillis() - forcedBloodMoonStartTime;
                    long actualDur = forcedBloodMoonDuration != -1 ? forcedBloodMoonDuration : bloodMoonForceDuration;
                    if (elapsedMs >= actualDur * 60_000L) {
                        forcedBloodMoon = false;
                        forcedBloodMoonStartTime = -1;
                        forcedBloodMoonDuration = -1;
                        bloodMoonPersisted = false;
                        persistedBloodMoonDay = -1;
                        saveBloodMoonData();
                        bloodMoonBar.removeAll();
                        debugLog("Forced blood moon expired by real-time duration — cleaning up.");
                        if (mythicMobsManager != null) {
                            mythicMobsManager.onBloodMoonEnd();
                        }
                        return;
                    }
                }

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
                            if (isWorldEnabled(p.getWorld()) || isLobbyWorld(p.getWorld())) {
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
                        // Blood moon ended (in-game time check).
                        if (!bloodMoonBar.getPlayers().isEmpty()) {
                            bloodMoonBar.removeAll();
                        }
                        // Fix RC2: Gate on (bloodMoonPersisted || forcedBloodMoon).
                        // /forcebloodmoon sets forcedBloodMoon=true but NOT bloodMoonPersisted=true,
                        // so the old guard silently skipped onBloodMoonEnd() for every forced blood
                        // moon, permanently orphaning the MM spawn tick loop.
                        if (bloodMoonPersisted || forcedBloodMoon) {
                            bloodMoonPersisted = false;
                            persistedBloodMoonDay = -1;
                            forcedBloodMoon = false;
                            forcedBloodMoonStartTime = -1;
                            forcedBloodMoonDuration = -1;
                            saveBloodMoonData();
                            debugLog("Blood moon ended (in-game time) - persistence reset.");
                            if (mythicMobsManager != null) {
                                mythicMobsManager.onBloodMoonEnd();
                            }
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
                            forcedBloodMoonStartTime = -1;
                            forcedBloodMoonDuration = -1;
                            saveBloodMoonData();
                            debugLog("Day time detected - blood moon persistence reset");
                            // Fix RC3: Signal MM manager here too.  Previously onBloodMoonEnd()
                            // was missing from this path, so the spawn tick loop was never told
                            // to stop when a blood moon was wiped by a /time set day command.
                            if (mythicMobsManager != null) {
                                mythicMobsManager.onBloodMoonEnd();
                            }
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

                            // --- MythicMobs: guaranteed Mutant + tick loop ---
                            if (mythicMobsManager != null) {
                                mythicMobsManager.onBloodMoonStart();
                            }

                            getLogger().info("Natural blood moon started on day " + currentDay);
                            debugLog("Blood moon persistence: active=" + bloodMoonPersisted + ", day=" + persistedBloodMoonDay);
                            
                            // Notify players
                            for (Player p : Bukkit.getOnlinePlayers()) {
                                if (isWorldEnabled(p.getWorld()) || isLobbyWorld(p.getWorld())) {
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

        // Cancel existing tasks on reload to prevent stacking
        if (scentDecayTask != null && !scentDecayTask.isCancelled()) scentDecayTask.cancel();
        if (scentSprintTask != null && !scentSprintTask.isCancelled()) scentSprintTask.cancel();

        int intervalSeconds = getConfig().getInt("scent-system.decay-interval-seconds", 5);
        double decayAmount = getConfig().getDouble("scent-system.decay-amount", 1.0);

        scentDecayTask = new BukkitRunnable() {
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

        // Continuous sprint scent: fires every 20 ticks (1 second) while player is sprinting.
        // The toggle event only catches start/stop — this ensures scent actually builds while running.
        double sprintAdd = getConfig().getDouble("scent-system.sprint-add", 2.0);
        scentSprintTask = new BukkitRunnable() {
            @Override
            public void run() {
                for (Player p : Bukkit.getOnlinePlayers()) {
                    if (!isWorldEnabled(p.getWorld())) continue;
                    if (playerSprinting.getOrDefault(p.getUniqueId(), false)) {
                        addPlayerScent(p.getUniqueId(), sprintAdd);
                    }
                }
            }
        }.runTaskTimer(this, 20L, 20L);
    }

    public double getPlayerScent(UUID uuid) {
        return playerScent.getOrDefault(uuid, 0.0);
    }

    public void addPlayerScent(UUID uuid, double amount) {
        double current = playerScent.getOrDefault(uuid, 0.0);
        double maxScent = getConfig().getDouble("scent-system.max-scent", 100.0);
        double newScent = Math.min(current + amount, maxScent);
        playerScent.put(uuid, newScent);
        debugLog("Player " + uuid + " scent increased by " + amount + " (now: " + newScent + " / " + maxScent + ")");
    }

    @EventHandler
    public void onPlayerToggleSprint(PlayerToggleSprintEvent event) {
        if (!getConfig().getBoolean("scent-system.enabled", true)) return;
        // Just track sprint state — scent is added continuously by scentSprintTask, not here.
        // Adding scent on toggle meant only one burst per sprint session regardless of duration.
        playerSprinting.put(event.getPlayer().getUniqueId(), event.isSprinting());
    }

    @EventHandler
    public void onPlayerMove(PlayerMoveEvent event) {
        if (!getConfig().getBoolean("scent-system.enabled", true)) return;

        Player player = event.getPlayer();
        UUID uuid = player.getUniqueId();

        // Jump detection: a jump is the transition from on-ground (last tick) to airborne
        // (this tick) with upward velocity. Bug C4 fix: the old guard `if (!player.isOnGround())
        // return;` fired for the entire airborne portion of every jump, so the velocity check
        // below was never reached and jumps produced zero scent. Track the previous-tick ground
        // state to detect the take-off edge instead.
        boolean wasOnGround = playerWasOnGround.getOrDefault(uuid, true);
        boolean nowOnGround = player.isOnGround();
        playerWasOnGround.put(uuid, nowOnGround);

        if (!wasOnGround || nowOnGround) return;

        long currentTime = System.currentTimeMillis();
        Long lastJump = lastJumpTime.get(uuid);
        if (lastJump != null && (currentTime - lastJump) < 500) return;

        double velocityY = player.getVelocity().getY();
        if (velocityY > 0.3) {
            double jumpAdd = getConfig().getDouble("scent-system.jump-add", 0.5);
            addPlayerScent(uuid, jumpAdd);
            lastJumpTime.put(uuid, currentTime);
            debugLog("Player " + player.getName() + " jumped, added " + jumpAdd + " scent");
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

        // Bug M3 fix: only a PLAYER kill should promote a zombie to VETERAN. Without this gate,
        // any entity killed near a zombie (farm animals, armor stands) triggered the upgrade,
        // letting players cheaply mass-promote a horde by herding passive mobs into it.
        if (deadEntity instanceof Player && deadEntity.getLastDamageCause() instanceof EntityDamageByEntityEvent damageEvent) {
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

        // BUGFIX: use the real-world clock (System.currentTimeMillis()) instead of
        // world.getFullTime() — full-time gets warped by world.setTime() calls in the
        // blood moon system, which was the root cause of immunity timers freezing or
        // jumping to nonsense values. We store an ABSOLUTE end timestamp so downtime
        // between saves/restarts is accounted for automatically (just like a real clock).
        long now = System.currentTimeMillis();

        for (String key : dataConfig.getConfigurationSection("player-immunity").getKeys(false)) {
            try {
                UUID uuid = UUID.fromString(key);
                long endTimeMillis = dataConfig.getLong("player-immunity." + key + ".endTimeMillis");
                double originalHealthVal = dataConfig.getDouble("player-immunity." + key + ".originalHealth");

                if (originalHealthVal <= 0.0) continue;

                // Always remember their original max health so onPlayerJoin can restore
                // it even if immunity already expired while the server was offline.
                originalHealth.put(uuid, originalHealthVal);

                long remainingMillis = endTimeMillis - now;
                if (remainingMillis > IMMUNITY_DURATION_MILLIS) {
                    remainingMillis = IMMUNITY_DURATION_MILLIS;
                }

                if (remainingMillis > 0) {
                    long newEndTime = now + remainingMillis;
                    immunityEndTime.put(uuid, newEndTime);
                    immunePlayers.add(uuid);
                    debugLog("Loaded active immunity for " + key);

                    // BUGFIX: previously this never recreated the bossbar or scheduled a
                    // removal task, so a player already online when the plugin (re)enabled
                    // (e.g. /plugman reload) had no bossbar and depended entirely on the
                    // periodic check task to ever clear their immunity.
                    Player online = Bukkit.getPlayer(uuid);
                    if (online != null && online.isOnline()) {
                        BossBar bar = Bukkit.createBossBar("§2§lZombie Guts Immunity", BarColor.GREEN, BarStyle.SOLID);
                        bar.addPlayer(online);
                        immunityBossBars.put(uuid, bar);
                        scheduleImmunityRemoval(online, remainingMillis / 50L);
                    }
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

        for (UUID uuid : new ArrayList<>(originalHealth.keySet())) {
            String path = "player-immunity." + uuid.toString();
            double storedHealth = originalHealth.get(uuid);
            Long endTime = immunityEndTime.get(uuid);

            // BUGFIX: only persist players who are actually still immune. Storing an
            // absolute epoch-millis timestamp here (instead of a remaining-tick count
            // derived from world.getFullTime()) means this value survives server
            // downtime and blood-moon time skips correctly.
            if (storedHealth > 0.0 && endTime != null) {
                dataConfig.set(path + ".endTimeMillis", endTime);
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
        // Bug C6 fix: restore the forced blood moon's original anchor and duration so a restart
        // mid-forced-blood-moon can't reset the timer (which let a forced blood moon be extended
        // indefinitely by restarting before it expired).
        forcedBloodMoonStartTime = bloodMoonDataConfig.getLong("bloodmoon.forced-start-time", -1);
        forcedBloodMoonDuration = bloodMoonDataConfig.getLong("bloodmoon.forced-duration-minutes", -1);

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
            // Bug C6 fix: persist the forced blood moon anchor + duration alongside the flags.
            bloodMoonDataConfig.set("bloodmoon.forced-start-time", forcedBloodMoonStartTime);
            bloodMoonDataConfig.set("bloodmoon.forced-duration-minutes", forcedBloodMoonDuration);
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

                // BUGFIX: was Bukkit.getWorlds().get(0).getFullTime() — world full-time gets
                // warped by world.setTime() calls (blood moon start/stop/correction), which
                // froze or scrambled this check entirely. System time is unaffected by that.
                long now = System.currentTimeMillis();

                for (UUID uuid : new ArrayList<>(immunePlayers)) {
                    Player player = Bukkit.getPlayer(uuid);
                    if (player == null || !player.isOnline()) continue;

                    Long endTime = immunityEndTime.get(uuid);
                    if (endTime == null) continue;

                    // Check if immunity has expired
                    if (now >= endTime) {
                        debugLog("Immunity expired for player " + player.getName() + ", retargeting zombies");

                        // BUGFIX: this branch used to call cleanUpPlayerState() directly,
                        // which wipes originalHealth WITHOUT ever restoring the player's
                        // max-health attribute. Any immunity that expired through this path
                        // (e.g. one restored from data.yml after a restart, which never gets
                        // a bossbar) left the player permanently stuck at reduced health.
                        // expireImmunity() does the restore first, then cleans up.
                        expireImmunity(player, "immunity.expired");

                        // Force nearby zombies to target the player
                        retargetZombiesNearPlayer(player);
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

    /**
     * BUGFIX: single source of truth for ending a player's Zombie Guts immunity.
     * Previously there were three separate copies of this logic (here, the boss bar
     * task, and the scheduled removal task) and one of them — the check task above —
     * never restored the player's max health, which is why hearts sometimes never
     * changed back after immunity wore off. Restoring health here, in one place,
     * means every expiry path behaves identically.
     */
    private void expireImmunity(Player player, String expiredMessageKey) {
        UUID uuid = player.getUniqueId();

        // Bug M6 fix: the bossbar task (every 5 ticks) and the check task (every 20 ticks) can
        // both hit the expiry edge on the same tick. Bail if this player is no longer immune so
        // the "expired" message and health restore only happen once.
        if (!immunePlayers.contains(uuid)) return;

        if (originalHealth.containsKey(uuid) && player.getAttribute(Attribute.GENERIC_MAX_HEALTH) != null) {
            double originalMaxHealth = originalHealth.get(uuid);
            player.getAttribute(Attribute.GENERIC_MAX_HEALTH).setBaseValue(originalMaxHealth);
            player.setHealth(Math.min(player.getHealth(), originalMaxHealth));
            player.sendMessage(messageManager.get("immunity.health-restored"));
        }

        cleanUpPlayerState(player);
        player.sendMessage(messageManager.get(expiredMessageKey));

        if (dataConfig != null && dataFile != null) {
            dataConfig.set("player-immunity." + uuid.toString(), null);
            try { dataConfig.save(dataFile); } catch (IOException e) {}
        }
    }

    private void startImmunityBossBarTask() {
        new BukkitRunnable() {
            @Override
            public void run() {
                if (immunityBossBars.isEmpty()) return;

                // BUGFIX: real-world clock instead of world.getFullTime() (see startImmunityCheckTask).
                long now = System.currentTimeMillis();

                for (UUID uuid : new ArrayList<>(immunityBossBars.keySet())) {
                    Player player = Bukkit.getPlayer(uuid);
                    if (player == null || !player.isOnline()) { continue; }
                    if (player.getWorld() == null) continue;

                    Long endTime = immunityEndTime.get(uuid);
                    if (endTime == null) continue;

                    long remainingMillis = endTime - now;

                    if (remainingMillis <= 0) {
                        // Immunity expired - clean up immediately (restores health internally)
                        expireImmunity(player, "immunity.expired");
                        continue;
                    }

                    double progress = (double) remainingMillis / IMMUNITY_DURATION_MILLIS;
                    immunityBossBars.get(uuid).setProgress(Math.max(0.0, Math.min(1.0, progress)));

                    long remainingSeconds = remainingMillis / 1000;
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
        lobbyWorlds = getConfig().getStringList("lobby-worlds");
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

    /**
     * Returns true if the given world is configured as a lobby world.
     * In lobby worlds: zombie spawning and MythicMobs boss spawning are suppressed,
     * but all other systems (blood moon bossbar, immunity, scent, etc.) remain active.
     */
    public boolean isLobbyWorld(World world) {
        return lobbyWorlds.contains(world.getName());
    }

    // === EVENT HANDLERS ===

    @EventHandler
    public void onPlayerJoin(PlayerJoinEvent event) {
        Player player = event.getPlayer();
        UUID uuid = player.getUniqueId();
        
        // CRITICAL FIX: Clean up any existing bossbars for this player
        cleanupBossbarForPlayer(player);

        if (originalHealth.containsKey(uuid) && player.getAttribute(Attribute.GENERIC_MAX_HEALTH) != null) {
            // BUGFIX: was player.getWorld().getFullTime() — corrupted by world.setTime()
            // calls in the blood moon system. Use the real-world clock instead.
            long remainingMillis = 0;
            if (immunityEndTime.containsKey(uuid)) {
                remainingMillis = immunityEndTime.get(uuid) - System.currentTimeMillis();
            }

            if (remainingMillis > 0) {
                player.getAttribute(Attribute.GENERIC_MAX_HEALTH).setBaseValue(10.0);
                player.setHealth(Math.min(player.getHealth(), 10.0));
                immunePlayers.add(uuid);

                BossBar bar = Bukkit.createBossBar("§2§lZombie Guts Immunity", BarColor.GREEN, BarStyle.SOLID);
                bar.addPlayer(player);
                immunityBossBars.put(uuid, bar);

                scheduleImmunityRemoval(player, remainingMillis / 50L);
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
        playerWasOnGround.remove(uuid);

        saveImmunityData();
    }

    @EventHandler
    public void onEntitySpawn(CreatureSpawnEvent event) {
        // Bug C1/M2 fix: plugin-spawned zombies must bypass the mob-list gate and the
        // assignZombieType call here — the spawner code assigns the type itself after
        // world.spawnEntity() returns. Without this, every plugin-spawned zombie got a
        // second random type roll (overwriting the intended one), and a misconfigured
        // whitelist could silently cancel all plugin spawns.
        if (isPluginSpawning) return;

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

                // BUGFIX: this null check used to run AFTER originalHealth/immunePlayers
                // were already mutated below, so a null world would leave the player
                // permanently flagged immune with no bossbar and no removal task.
                if (player.getWorld() == null) return;

                double maxHealth = 10.0;
                if (player.getAttribute(Attribute.GENERIC_MAX_HEALTH) != null) {
                    originalHealth.put(uuid, player.getAttribute(Attribute.GENERIC_MAX_HEALTH).getBaseValue());

                    player.getAttribute(Attribute.GENERIC_MAX_HEALTH).setBaseValue(maxHealth);
                    player.setHealth(Math.min(player.getHealth(), maxHealth));
                }

                immunePlayers.add(uuid);

                // BUGFIX: was player.getWorld().getFullTime() + IMMUNITY_DURATION_TICKS —
                // world full-time is warped by world.setTime() calls in the blood moon
                // system (forced/natural start, stop, and the night-time correction),
                // which is what was making this timer freeze or jump to nonsense values.
                long endTime = System.currentTimeMillis() + IMMUNITY_DURATION_MILLIS;
                immunityEndTime.put(uuid, endTime);

                BossBar bar = Bukkit.createBossBar(
                        messageManager.get("immunity.bossbar", "10:00"),
                        BarColor.GREEN,
                        BarStyle.SOLID
                );
                bar.addPlayer(player);
                immunityBossBars.put(uuid, bar);

                scheduleImmunityRemoval(player, IMMUNITY_DURATION_TICKS);

                // BUGFIX: this grant was never written to data.yml until the player quit,
                // reloaded, or the server shut down gracefully. A crash or `/stop` in
                // between meant the active immunity simply never persisted at all.
                saveImmunityData();

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

    private void scheduleImmunityRemoval(Player player, long durationTicks) {
        UUID uuid = player.getUniqueId();
        BukkitTask task = Bukkit.getScheduler().runTaskLater(this, () -> {
            if (player.isOnline() && immunePlayers.contains(uuid)) {
                expireImmunity(player, "immunity.expired");
            }
            scheduledTasks.remove(uuid);
        }, Math.max(0, durationTicks));
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

        // Day spawns use a separate (smaller) horde size so daytime isn't as brutal as night.
        // isDayHordeSpawn was passed all the way from HordeSpawnerTask but was never consumed — fixed.
        if (isDayHordeSpawn) {
            baseAmount = getConfig().getInt("apocalypse-settings.day-horde-size", Math.max(1, baseAmount / 3));
            variance   = getConfig().getInt("apocalypse-settings.day-horde-variance", Math.max(0, variance / 2));
        }

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

        // Bug M1 fix: cap a single player's horde here, BEFORE the spawn loop. The blood moon ×
        // scent multipliers could push this to the global max-total-zombies (e.g. 300), and that
        // loop runs per eligible player per spawner tick — thousands of getHighestBlockAt() calls
        // that tank TPS independent of actual entity count. The global cap stays as a secondary guard.
        int maxSingleHorde = getConfig().getInt("apocalypse-settings.max-single-horde-size", 30);
        finalHordeSize = Math.min(finalHordeSize, maxSingleHorde);

        // Cap with max-total-zombies instead of scent-system.spawn-cap
        int spawnCap = getConfig().getInt("performance.max-total-zombies", 300);
        finalHordeSize = Math.min(finalHordeSize, spawnCap);

        debugLog("Attempting to spawn horde of size: " + finalHordeSize + " near " + player.getName() + " (Multiplier: " + multiplier + ")");

        int spawnRadius = getConfig().getInt("apocalypse-settings.spawn-radius", 35);

        int skipped = 0;
        for (int i = 0; i < finalHordeSize; i++) {
            double xOffset = ThreadLocalRandom.current().nextDouble(-spawnRadius, spawnRadius);
            double zOffset = ThreadLocalRandom.current().nextDouble(-spawnRadius, spawnRadius);
            Location spawnLoc = player.getLocation().clone().add(xOffset, 0, zOffset);

            Location surface = undeadSpawner.getSurfaceSpawnLocation(spawnLoc);

            // Retry once with a fresh random offset before giving up — reduces wasted
            // attempts near water, ravines, or ocean biomes.
            if (surface == null) {
                xOffset = ThreadLocalRandom.current().nextDouble(-spawnRadius, spawnRadius);
                zOffset = ThreadLocalRandom.current().nextDouble(-spawnRadius, spawnRadius);
                spawnLoc = player.getLocation().clone().add(xOffset, 0, zOffset);
                surface = undeadSpawner.getSurfaceSpawnLocation(spawnLoc);
            }

            if (surface == null) {
                skipped++;
                continue;
            }

            Block surfaceBlock = surface.getBlock().getRelative(BlockFace.DOWN);
            BlockData surfaceData = surfaceBlock.getBlockData();

            boolean risingAnimation = getConfig().getBoolean("apocalypse-settings.rising-animation", true);

            if (risingAnimation) {
                long startDelayTicks = i % 5L;
                undeadSpawner.trySpawnUndeadRise(surface, surfaceBlock, surfaceData, startDelayTicks);
            } else {
                isPluginSpawning = true;
                Zombie zombie = (Zombie) surface.getWorld().spawnEntity(surface, EntityType.ZOMBIE);
                isPluginSpawning = false;
                if (zombie != null) utils.assignZombieType(zombie);
            }
        }

        // Single summary log instead of one line per failed attempt
        if (skipped > 0) {
            debugLog("Horde near " + player.getName() + ": " + (finalHordeSize - skipped) + "/" + finalHordeSize + " spawned (" + skipped + " skipped - no valid surface)");
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
                if (mythicMobsManager != null) {
                    mythicMobsManager.loadConfig();
                }
                startSpawnerTask();
                startBuilderCleanupTask();
                startImmunityCheckTask(); // CRITICAL FIX: Restart immunity check task on reload
                // Bug C2 fix: reload the watchdog AFTER startSpawnerTask() so its tasks
                // are not killed by the cancelTasks(this) call inside startSpawnerTask().
                if (performanceWatchdog != null) {
                    performanceWatchdog.reload();
                }
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
            
            Player targetPlayer = null;
            
            // Check if command is run from console with player parameter
            if (!(sender instanceof Player)) {
                if (args.length >= 2) {
                    targetPlayer = Bukkit.getPlayer(args[1]);
                    if (targetPlayer == null) {
                        sender.sendMessage(messageManager.getWithPrefix("player-not-found", args[1]));
                        return true;
                    }
                } else {
                    sender.sendMessage(messageManager.getWithPrefix("commands.item.usage"));
                    return true;
                }
            } else {
                targetPlayer = (Player) sender;
            }

            if (args.length >= 1 && args[0].equalsIgnoreCase("zombie_guts") && zombieGutsEnabled) {
                ItemStack guts = new ItemStack(Material.ROTTEN_FLESH);
                ItemMeta meta = guts.getItemMeta();
                meta.setDisplayName(messageManager.get("immunity.item-name"));
                meta.setLore(messageManager.getList("immunity.item-lore"));
                guts.setItemMeta(meta);
                targetPlayer.getInventory().addItem(guts);
                
                if (sender instanceof Player) {
                    targetPlayer.sendMessage(messageManager.getWithPrefix("commands.item.received", messageManager.get("immunity.item-name")));
                } else {
                    sender.sendMessage(messageManager.getWithPrefix("commands.item.given", targetPlayer.getName(), messageManager.get("immunity.item-name")));
                }
                return true;
            }
            sender.sendMessage(messageManager.getWithPrefix("commands.item.unknown", args.length > 0 ? args[0] : "none"));
            return true;
        }

        if (command.getName().equalsIgnoreCase("forcebloodmoon")) {
            if (!sender.hasPermission("zombpocalypse.admin")) {
                sender.sendMessage(messageManager.get("no-permission"));
                return true;
            }

            if (Bukkit.getWorlds().isEmpty()) return true;
            // Bug M5 fix: use the first ENABLED world, not getWorlds().get(0), which on
            // Multiverse/BetterRTP is often a lobby/temp world. setTime() on the wrong world
            // left the actual gameplay world in daytime while the blood moon logic ran elsewhere.
            World world = Bukkit.getWorlds().stream()
                    .filter(this::isWorldEnabled)
                    .findFirst()
                    .orElse(null);
            if (world == null) {
                sender.sendMessage("§cNo enabled world is currently loaded.");
                return true;
            }

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

            // --- MythicMobs: guaranteed Mutant + tick loop ---
            if (mythicMobsManager != null) {
                mythicMobsManager.onBloodMoonStart();
            }

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

                // --- MythicMobs: stop tick loop ---
                if (mythicMobsManager != null) {
                    mythicMobsManager.onBloodMoonEnd();
                }

                // CRITICAL FIX: Force bossbar cleanup
                if (!bloodMoonBar.getPlayers().isEmpty()) {
                    bloodMoonBar.removeAll();
                }
                
                // CRITICAL FIX: Set time to day to prevent immediate restart
                if (Bukkit.getWorlds().isEmpty()) return true;
                // Bug M5 fix: target the first ENABLED world, not getWorlds().get(0).
                World world = Bukkit.getWorlds().stream()
                        .filter(this::isWorldEnabled)
                        .findFirst()
                        .orElse(null);
                if (world == null) {
                    sender.sendMessage("§cNo enabled world is currently loaded.");
                    return true;
                }
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

            // --- MythicMobs: MUTANT type ---
            if (typeArg.equals("MUTANT")) {
                if (mythicMobsManager == null || !mythicMobsManager.isMythicMobsEnabled()) {
                    sender.sendMessage("§c[MythicMobs] MythicMobs is not available on this server.");
                    return true;
                }
                int spawned = mythicMobsManager.spawnMutantCommand(player, count, radius);
                sender.sendMessage("§aSpawned §c" + spawned + " §a" + mythicMobsManager.getMobType()
                        + "§a! (Active: §c" + mythicMobsManager.getActiveMutantCount()
                        + "§a/§c" + mythicMobsManager.getMaxGlobalCap() + "§a)");
                return true;
            }

            if (typeArg.equals("HORDE")) {
                // Spawn mixed horde
                int hordeSpawned = 0;
                for (int i = 0; i < count; i++) {
                    Location spawnLoc = player.getLocation().add(
                            ThreadLocalRandom.current().nextDouble(-radius, radius),
                            0,
                            ThreadLocalRandom.current().nextDouble(-radius, radius)
                    );

                    // Bug fix: do NOT skip claims here. The natural horde spawner ignores
                    // GriefPrevention claims entirely (zombies spawn inside claims normally), so
                    // gating the admin /zspawn command on claims was inconsistent — and at a
                    // claimed hub/main-world spawn it skipped every attempt ("horde of 0").
                    // Bug M4 fix: snap to a valid surface instead of spawning at the player's raw
                    // Y. On non-flat terrain the old code buried/suffocated zombies inside hills or
                    // dropped them into the void. Skip the slot if no valid surface is found.
                    Location surface = undeadSpawner.getSurfaceSpawnLocation(spawnLoc);
                    if (surface == null) continue;

                    // Bug fix: admin /zspawn must bypass the onEntitySpawn mob-list gate (same
                    // rationale as C1). Without the flag, an enabled world running a whitelist
                    // (or a blacklist that lists ZOMBIE) silently cancels the spawn — which is why
                    // /zspawn worked in non-enabled worlds (nether/end) but not the main world.
                    // Because the gate is bypassed, onEntitySpawn no longer assigns a type, so we
                    // assign it here ourselves.
                    setPluginSpawning(true);
                    Zombie zombie = (Zombie) player.getWorld().spawnEntity(surface, EntityType.ZOMBIE);
                    setPluginSpawning(false);
                    if (zombie != null) {
                        utils.assignZombieType(zombie);
                        hordeSpawned++;
                    }
                }
                sender.sendMessage("§aSpawned horde of " + hordeSpawned + " zombies!");
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

                // Bug fix: don't gate the admin command on GriefPrevention claims (the natural
                // spawner ignores them too — see HORDE branch above).
                // Bug fix: bypass the onEntitySpawn mob-list gate for admin spawns (see HORDE
                // branch above), then apply the requested type directly.
                setPluginSpawning(true);
                Zombie zombie = (Zombie) player.getWorld().spawnEntity(spawnLoc, EntityType.ZOMBIE);
                setPluginSpawning(false);
                if (zombie != null) utils.applyZombieType(zombie, type);
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