package com.deleted.zombpocalypse;

import me.ryanhamshire.GriefPrevention.GriefPrevention;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.entity.Player;
import org.bukkit.plugin.Plugin;
import org.bukkit.plugin.java.JavaPlugin;

import java.io.File;
import java.util.List;
import java.util.UUID;

/**
 * Zombpocalypse — the {@link JavaPlugin} entry point. After the decomposition refactor this
 * class is a slim <b>composition root</b> (wires up all managers in {@code onEnable}) and a
 * <b>facade</b>: it owns general config + world helpers + the GriefPrevention hook, and exposes
 * thin delegate methods so the pre-existing support classes (ZombpocalypseUtils, UndeadSpawner,
 * HordeSpawnerTask, MythicMobsManager, PerformanceWatchdog) keep talking to {@code plugin.*}
 * without knowing about the new managers. This keeps the dependency graph a star (everyone
 * depends on the facade; the facade depends on everyone) rather than a circular mesh.
 *
 * Subsystems live in dedicated managers:
 *   {@link BloodMoonManager}, {@link ImmunityManager}, {@link ScentManager}, {@link HordeManager}.
 * Events live in {@link ZombpocalypseListener}; commands in {@link ZombpocalypseCommand}.
 */
public class Zombpocalypse extends JavaPlugin {

    // --- MANAGERS ---
    private ZombpocalypseUtils utils;
    private UndeadSpawner undeadSpawner;
    private MessageManager messageManager;
    private PerformanceWatchdog performanceWatchdog;
    private MythicMobsManager mythicMobsManager;
    private BloodMoonManager bloodMoon;
    private ImmunityManager immunity;
    private ScentManager scent;
    private HordeManager horde;

    // --- GENERAL CONFIG ---
    private List<String> enabledWorlds;
    private List<String> lobbyWorlds;
    private boolean debugMode;
    private boolean useMobBlacklist;
    private List<String> mobList;
    private boolean allowBabyZombies;
    private boolean allowZombieVillagers;
    private boolean zombieGutsEnabled;

    // --- HOOKS ---
    private boolean griefPreventionEnabled;
    private GriefPrevention griefPrevention;

    // ==================================================================================
    // LIFECYCLE
    // ==================================================================================

    @Override
    public void onEnable() {
        File configFile = new File(getDataFolder(), "config.yml");
        if (!configFile.exists()) {
            saveResource("config.yml", false);
        }
        saveDefaultConfig();

        // CRITICAL FIX: Initialize blood moon data BEFORE loading config values.
        // BloodMoonManager.load() creates BloodMoonData.yml and reads persisted state; it must
        // not touch MessageManager (which isn't constructed yet).
        bloodMoon = new BloodMoonManager(this);
        bloodMoon.load(getDataFolder());

        reloadConfig();
        loadConfigValues();

        // --- Message Manager Setup ---
        messageManager = new MessageManager(this);

        // --- Immunity Setup (owns data.yml) ---
        immunity = new ImmunityManager(this, messageManager);
        immunity.load();

        // --- Hooks Setup ---
        setupHooks();

        // --- Utils / Spawner Setup ---
        utils = new ZombpocalypseUtils(this, griefPrevention, griefPreventionEnabled);
        undeadSpawner = new UndeadSpawner(this, utils);
        horde = new HordeManager(this, utils, undeadSpawner);
        scent = new ScentManager(this);

        // --- MythicMobs Integration ---
        mythicMobsManager = new MythicMobsManager(this);

        // BUGFIX: if a blood moon was already active (persisted or forced) when the
        // plugin (re)enabled, the bossbar/visual side resumes fine on its own via
        // isBloodMoonActive(), but mythicMobsManager.onBloodMoonStart() was never
        // called again — it's only invoked from the "natural blood moon start" branch,
        // which is gated on !bloodMoonPersisted and so can't fire a second time. That
        // silently orphaned the guaranteed-mutant spawn loop on every restart/reload
        // that happened mid-blood-moon.
        if (bloodMoon.wasPersistedOrForcedAtEnable()) {
            mythicMobsManager.onBloodMoonStart();
            debugLog("Resumed MythicMobs blood moon tick loop after (re)enable - persisted=" + bloodMoon.isPersisted() + ", forced=" + bloodMoon.isForced());
        }

        // --- Performance Watchdog Setup ---
        // Bug C2 fix: only construct it here. start() is called AFTER startSpawnerTask()
        // below, because startSpawnerTask() calls cancelTasks(this) which would otherwise
        // immediately kill the watchdog's TPS-monitor and LOD tasks.
        performanceWatchdog = new PerformanceWatchdog(this);

        getServer().getPluginManager().registerEvents(new ZombpocalypseListener(this), this);

        // Register commands
        ZombpocalypseCommand commandExecutor = new ZombpocalypseCommand(this);
        getCommand("zreload").setExecutor(commandExecutor);
        getCommand("help").setExecutor(commandExecutor);
        getCommand("zitem").setExecutor(commandExecutor);
        getCommand("forcebloodmoon").setExecutor(commandExecutor);
        getCommand("stopbloodmoon").setExecutor(commandExecutor);
        getCommand("zspawn").setExecutor(commandExecutor);

        // Register tab completers
        ZombpocalypseTabCompleter tabCompleter = new ZombpocalypseTabCompleter();
        getCommand("zspawn").setTabCompleter(tabCompleter);
        getCommand("zitem").setTabCompleter(tabCompleter);

        startSpawnerTask();
        horde.startBuilderCleanupTask();
        immunity.startCheckTask();

        // Bug C2 fix: start the watchdog AFTER startSpawnerTask() so its tasks survive
        // the cancelTasks(this) call inside startSpawnerTask().
        performanceWatchdog.start();

        getLogger().info("[Zombpocalypse v1.3] Zombpocalypse has started! Brains...");
    }

    @Override
    public void onDisable() {
        if (immunity != null) {
            immunity.save();
        }

        // CRITICAL FIX: Save blood moon persistence data on shutdown using separate file
        if (bloodMoon != null) {
            bloodMoon.save();
        }

        if (performanceWatchdog != null) {
            performanceWatchdog.stop();
        }

        if (immunity != null) {
            immunity.removeAllBossBars();
        }

        if (bloodMoon != null) {
            bloodMoon.removeBossBar();
        }

        Bukkit.getScheduler().cancelTasks(this);
    }

    // ==================================================================================
    // TASK ORCHESTRATION
    // ==================================================================================

    private void startSpawnerTask() {
        long rate = getConfig().getLong("apocalypse-settings.spawn-rate", 1200L);
        Bukkit.getScheduler().cancelTasks(this);

        immunity.startBossBarTask();
        bloodMoon.startTask();
        scent.startTasks();
        horde.startAITickTask();

        getLogger().info("TASK START: Spawner Task @ " + rate + " ticks.");
        new HordeSpawnerTask(this).runTaskTimer(this, 0L, rate);
    }

    /** Full reload pipeline shared by /zreload. Mirrors the original onCommand("zreload") sequence. */
    public void reloadAll() {
        Bukkit.getScheduler().cancelTasks(this);
        reloadConfig();
        loadConfigValues();
        messageManager.reload();
        utils.reloadWeights();
        if (mythicMobsManager != null) {
            mythicMobsManager.loadConfig();
        }
        startSpawnerTask();
        horde.startBuilderCleanupTask();
        immunity.startCheckTask(); // CRITICAL FIX: Restart immunity check task on reload
        // Bug C2 fix: reload the watchdog AFTER startSpawnerTask() so its tasks
        // are not killed by the cancelTasks(this) call inside startSpawnerTask().
        if (performanceWatchdog != null) {
            performanceWatchdog.reload();
        }
    }

    // ==================================================================================
    // CONFIG + HOOKS
    // ==================================================================================

    private void loadConfigValues() {
        enabledWorlds = getConfig().getStringList("enabled-worlds");
        lobbyWorlds = getConfig().getStringList("lobby-worlds");
        debugMode = getConfig().getBoolean("debug-mode", false);

        useMobBlacklist = getConfig().getBoolean("apocalypse-settings.use-mob-blacklist");
        mobList = getConfig().getStringList("apocalypse-settings.mob-list");

        allowBabyZombies = getConfig().getBoolean("zombie-settings.allow-baby-zombies");
        allowZombieVillagers = getConfig().getBoolean("zombie-settings.allow-zombie-villagers");
        zombieGutsEnabled = getConfig().getBoolean("zombie-settings.zombie-guts.enabled");

        // Blood moon config + persistence (dual-read) lives in the manager now.
        bloodMoon.loadConfigValues(getConfig());

        getLogger().info("Configuration Loaded. Debug Mode: " + debugMode);
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

    // ==================================================================================
    // FACADE — world helpers + cross-cutting utilities (used by the legacy support classes)
    // ==================================================================================

    public void debugLog(String message) {
        if (debugMode) {
            getLogger().info("[DEBUG] " + message);
        }
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

    public boolean isInsideClaim(Location loc) {
        if (!griefPreventionEnabled) return false;
        if (!getConfig().getBoolean("hooks.griefprevention.prevent-spawning-in-claims")) return false;

        return griefPrevention.dataStore.getClaimAt(loc, false, null) != null;
    }

    // === FACADE DELEGATES (forward to the owning manager so legacy callers stay unchanged) ===

    public boolean isBloodMoonActive(World world) {
        return bloodMoon.isActive(world);
    }

    public double getBloodMoonHordeMultiplier() {
        return bloodMoon.getHordeMultiplier();
    }

    /** Called by UndeadSpawner before/after world.spawnEntity so onEntitySpawn skips the mob-list check. */
    void setPluginSpawning(boolean value) {
        horde.setPluginSpawning(value);
    }

    public void trackBuilderBlock(Location loc, UUID zombieUUID) {
        horde.trackBuilderBlock(loc, zombieUUID);
    }

    void spawnZombiesNearPlayer(Player player, boolean isDayHordeSpawn) {
        horde.spawnZombiesNearPlayer(player, isDayHordeSpawn);
    }

    public double getPlayerScent(UUID uuid) {
        return scent.getScent(uuid);
    }

    public void addPlayerScent(UUID uuid, double amount) {
        scent.addScent(uuid, amount);
    }

    /** Exposed so HordeSpawnerTask can query the spawning-paused state. */
    public PerformanceWatchdog getPerformanceWatchdog() {
        return performanceWatchdog;
    }

    public MessageManager getMessages() {
        return messageManager;
    }

    // ==================================================================================
    // MANAGER GETTERS (used by listener, command, and inter-manager wiring)
    // ==================================================================================

    public BloodMoonManager getBloodMoon() {
        return bloodMoon;
    }

    public ImmunityManager getImmunity() {
        return immunity;
    }

    public ScentManager getScent() {
        return scent;
    }

    public HordeManager getHordeManager() {
        return horde;
    }

    public ZombpocalypseUtils getUtils() {
        return utils;
    }

    public UndeadSpawner getUndeadSpawner() {
        return undeadSpawner;
    }

    public MythicMobsManager getMythicMobsManager() {
        return mythicMobsManager;
    }

    // ==================================================================================
    // GENERAL CONFIG GETTERS (used by the listener / command)
    // ==================================================================================

    public boolean isZombieGutsEnabled() {
        return zombieGutsEnabled;
    }

    public boolean isUseMobBlacklist() {
        return useMobBlacklist;
    }

    public List<String> getMobList() {
        return mobList;
    }

    public boolean isAllowBabyZombies() {
        return allowBabyZombies;
    }

    public boolean isAllowZombieVillagers() {
        return allowZombieVillagers;
    }
}
