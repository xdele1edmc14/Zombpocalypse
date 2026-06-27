package com.deleted.xapocalypse;

import org.bukkit.Bukkit;
import org.bukkit.World;
import org.bukkit.boss.BarColor;
import org.bukkit.boss.BarStyle;
import org.bukkit.boss.BossBar;
import org.bukkit.command.CommandSender;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.scheduler.BukkitRunnable;

import java.io.File;
import java.io.IOException;

/**
 * Owns the entire Blood Moon subsystem: configuration multipliers, the BloodMoonData.yml
 * persistence file, the bossbar, the repeating lifecycle task, and the force/stop commands.
 *
 * Extracted verbatim from the original xApocalypse monolith — every bug-fix (Bug C5/C6,
 * Fix RC1-RC4, Bug M5) is preserved. The MythicMobs integration is resolved lazily through
 * the plugin facade ({@code plugin.getMythicMobsManager()}) so this manager holds no direct
 * reference to MythicMobsManager (keeps the dependency graph acyclic at the type level).
 */
public class BloodMoonManager {

    private final xApocalypse plugin;

    // --- CONFIG VALUES ---
    private boolean bloodMoonEnabled;
    private int bloodMoonInterval;
    private String bloodMoonTitle;
    private int bloodMoonForceDuration; // CRITICAL FIX: force duration config

    // --- MULTIPLIERS ---
    private double bmHealthMult;
    private double bmDamageMult;
    private double bmSpeedMult;
    private double bmHordeMult;

    // --- RUNTIME STATE ---
    private boolean forcedBloodMoon = false;
    private long forcedBloodMoonStartTime = -1; // CRITICAL FIX: Track forced blood moon start time
    private long forcedBloodMoonDuration = -1; // CRITICAL FIX: Track actual forced duration
    private BossBar bloodMoonBar;

    // CRITICAL FIX: blood moon persistence fields
    private boolean bloodMoonPersisted = false;
    private long persistedBloodMoonDay = -1;

    // CRITICAL FIX: Separate blood moon data file
    private File bloodMoonDataFile;
    private FileConfiguration bloodMoonDataConfig;

    public BloodMoonManager(xApocalypse plugin) {
        this.plugin = plugin;
    }

    // === SETUP / PERSISTENCE ===

    /** Initializes BloodMoonData.yml and loads persisted state. Must NOT touch MessageManager. */
    public void load(File dataFolder) {
        // CRITICAL FIX: Initialize separate blood moon data file
        bloodMoonDataFile = new File(dataFolder, "BloodMoonData.yml");
        if (!bloodMoonDataFile.exists()) {
            try {
                bloodMoonDataFile.createNewFile();
                plugin.getLogger().info("Created new BloodMoonData.yml file");
            } catch (IOException e) {
                plugin.getLogger().severe("Could not create BloodMoonData.yml: " + e.getMessage());
            }
        }
        bloodMoonDataConfig = YamlConfiguration.loadConfiguration(bloodMoonDataFile);

        // CRITICAL FIX: Load blood moon persistence data BEFORE config values
        loadBloodMoonData();
    }

    public void loadConfigValues(FileConfiguration cfg) {
        bloodMoonEnabled = cfg.getBoolean("bloodmoon.enabled");
        bloodMoonInterval = cfg.getInt("bloodmoon.interval-days", 10);
        bloodMoonTitle = cfg.getString("bloodmoon.bossbar-title", "Blood Moon");
        bloodMoonForceDuration = cfg.getInt("bloodmoon.force-duration-minutes", 10); // CRITICAL FIX: Load force duration
        bmHealthMult = cfg.getDouble("bloodmoon.multipliers.health", 2.0);
        bmDamageMult = cfg.getDouble("bloodmoon.multipliers.damage", 1.5);
        bmSpeedMult = cfg.getDouble("bloodmoon.multipliers.speed", 1.2);
        bmHordeMult = cfg.getDouble("bloodmoon.multipliers.horde-size", 1.5);

        // CRITICAL FIX: Load blood moon persistence data from separate file (dual-read preserved
        // from the original loadConfigValues, which re-asserted these flags after reloadConfig()).
        bloodMoonPersisted = bloodMoonDataConfig.getBoolean("bloodmoon.persisted", false);
        persistedBloodMoonDay = bloodMoonDataConfig.getLong("bloodmoon.persisted-day", -1);
        forcedBloodMoon = bloodMoonDataConfig.getBoolean("bloodmoon.forced", false);
    }

    // CRITICAL FIX: blood moon data loading method
    private void loadBloodMoonData() {
        if (bloodMoonDataConfig == null) {
            plugin.getLogger().warning("Cannot load blood moon data - bloodMoonDataConfig is null");
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
            plugin.getLogger().info("Loaded persisted blood moon from BloodMoonData.yml - day " + persistedBloodMoonDay);
            plugin.debugLog("Blood moon persistence: active=" + bloodMoonPersisted + ", day=" + persistedBloodMoonDay + ", forced=" + forcedBloodMoon);
        } else {
            plugin.debugLog("No persisted blood moon data found in BloodMoonData.yml");
        }
    }

    // CRITICAL FIX: blood moon data saving method
    public void save() {
        if (bloodMoonDataConfig == null || bloodMoonDataFile == null) {
            plugin.getLogger().warning("Cannot save blood moon data - blood moon data files not initialized");
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

            plugin.debugLog("Saved blood moon data to BloodMoonData.yml: active=" + bloodMoonPersisted + ", day=" + persistedBloodMoonDay + ", forced=" + forcedBloodMoon);
        } catch (IOException e) {
            plugin.getLogger().severe("Could not save BloodMoonData.yml: " + e.getMessage());
        }
    }

    // === STATE QUERIES ===

    public boolean isActive(World world) {
        if (!bloodMoonEnabled) return false;
        if (!plugin.isWorldEnabled(world)) return false;

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

    public double getHordeMultiplier() {
        return bmHordeMult;
    }

    public double getHealthMult() {
        return bmHealthMult;
    }

    public double getDamageMult() {
        return bmDamageMult;
    }

    public double getSpeedMult() {
        return bmSpeedMult;
    }

    public int getDefaultForceDuration() {
        return bloodMoonForceDuration;
    }

    public boolean isPersisted() {
        return bloodMoonPersisted;
    }

    public boolean isForced() {
        return forcedBloodMoon;
    }

    /** Used by onEnable to decide whether to resume the MythicMobs blood-moon spawn loop. */
    public boolean wasPersistedOrForcedAtEnable() {
        return bloodMoonPersisted || forcedBloodMoon;
    }

    // === REPEATING TASK ===

    public void startTask() {
        // Bug C5 fix: remove the previous bossbar from all players before replacing the
        // reference. On /xa reload this method runs again; without this, each reload left an
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
                        .filter(plugin::isWorldEnabled)
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
                        save();
                        bloodMoonBar.removeAll();
                        plugin.debugLog("Forced blood moon expired by real-time duration — cleaning up.");
                        MythicMobsManager mm = plugin.getMythicMobsManager();
                        if (mm != null) {
                            mm.onBloodMoonEnd();
                        }
                        return;
                    }
                }

                if (isActive(mainWorld)) {
                    // Bug C1 fix: fire the natural blood-moon "start" exactly once. isActive() returns
                    // true on the FIRST night tick of an interval day — before the old else-branch
                    // start code could ever run — so that code was unreachable and natural blood
                    // moons silently never broadcast, never persisted, and never triggered the
                    // MythicMobs spawn loop. Detect the transition here instead: active, but not yet
                    // persisted and not forced. Once persisted=true this won't fire again, and forced
                    // blood moons already ran onBloodMoonStart() from forceBloodMoon().
                    if (!bloodMoonPersisted && !forcedBloodMoon) {
                        long currentDay = mainWorld.getFullTime() / 24000L;
                        bloodMoonPersisted = true;
                        persistedBloodMoonDay = currentDay;
                        save();

                        MythicMobsManager mm = plugin.getMythicMobsManager();
                        if (mm != null) {
                            mm.onBloodMoonStart();
                        }

                        plugin.getLogger().info("Natural blood moon started on day " + currentDay);
                        plugin.debugLog("Blood moon persistence: active=" + bloodMoonPersisted + ", day=" + persistedBloodMoonDay);

                        for (Player p : Bukkit.getOnlinePlayers()) {
                            if (plugin.isWorldEnabled(p.getWorld()) || plugin.isLobbyWorld(p.getWorld())) {
                                p.sendMessage(plugin.getMessages().get("bloodmoon.natural-start"));
                            }
                        }
                    }

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
                            if (plugin.isWorldEnabled(p.getWorld()) || plugin.isLobbyWorld(p.getWorld())) {
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
                        // /xa forcebloodmoon sets forcedBloodMoon=true but NOT bloodMoonPersisted=true,
                        // so the old guard silently skipped onBloodMoonEnd() for every forced blood
                        // moon, permanently orphaning the MM spawn tick loop.
                        if (bloodMoonPersisted || forcedBloodMoon) {
                            bloodMoonPersisted = false;
                            persistedBloodMoonDay = -1;
                            forcedBloodMoon = false;
                            forcedBloodMoonStartTime = -1;
                            forcedBloodMoonDuration = -1;
                            save();
                            plugin.debugLog("Blood moon ended (in-game time) - persistence reset.");
                            MythicMobsManager mm = plugin.getMythicMobsManager();
                            if (mm != null) {
                                mm.onBloodMoonEnd();
                            }
                        }
                    }
                } else {
                    // CRITICAL FIX: Force bossbar cleanup when blood moon is not active or when it's day
                    if (!bloodMoonBar.getPlayers().isEmpty()) {
                        bloodMoonBar.removeAll();
                        plugin.debugLog("Force cleanup: blood moon not active or it's day, removing bossbar");
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
                            save();
                            plugin.debugLog("Day time detected - blood moon persistence reset");
                            // Fix RC3: Signal MM manager here too.  Previously onBloodMoonEnd()
                            // was missing from this path, so the spawn tick loop was never told
                            // to stop when a blood moon was wiped by a /time set day command.
                            MythicMobsManager mm = plugin.getMythicMobsManager();
                            if (mm != null) {
                                mm.onBloodMoonEnd();
                            }
                        }
                    }

                    // Bug C1 fix: natural blood-moon START is now handled in the isActive() branch
                    // above. The block that used to live here was unreachable (isActive() returns
                    // true on the first night tick of an interval day, so this else is never entered
                    // during a natural blood moon). Only the day-time persistence cleanup above
                    // remains in this branch.
                }
            }
        }.runTaskTimer(plugin, 20L, 20L);
    }

    // === BOSSBAR LIFECYCLE ===

    // CRITICAL FIX: clean up bossbars for a specific player (called from onPlayerJoin)
    public void cleanupBossbarForPlayer(Player player) {
        // Remove from blood moon bossbar
        if (bloodMoonBar != null && bloodMoonBar.getPlayers().contains(player)) {
            bloodMoonBar.removePlayer(player);
        }
    }

    // CRITICAL FIX: Clean up blood moon bossbar when player quits
    public void onPlayerQuit(Player player) {
        if (bloodMoonBar != null && bloodMoonBar.getPlayers().contains(player)) {
            bloodMoonBar.removePlayer(player);
        }
    }

    public void removeBossBar() {
        if (bloodMoonBar != null) {
            bloodMoonBar.removeAll();
        }
    }

    // === COMMANDS ===

    /** /xa forcebloodmoon — the world has already been resolved and the duration validated by the command. */
    public void forceBloodMoon(CommandSender sender, World world, int duration) {
        forcedBloodMoon = true;
        forcedBloodMoonStartTime = System.currentTimeMillis(); // CRITICAL FIX: Track start time
        forcedBloodMoonDuration = duration; // CRITICAL FIX: Store actual duration

        // CRITICAL FIX: Save forced blood moon state
        save();

        // --- MythicMobs: guaranteed Mutant + tick loop ---
        MythicMobsManager mm = plugin.getMythicMobsManager();
        if (mm != null) {
            mm.onBloodMoonStart();
        }

        sender.sendMessage(plugin.getMessages().get("bloodmoon.force-start") + " §7(§e" + duration + " minutes§7)");

        long time = world.getTime();
        if (time < 13000 || time > 23000) {
            world.setTime(13000);
            sender.sendMessage(plugin.getMessages().get("bloodmoon.force-time-set"));
        }

        plugin.getLogger().info("Blood moon force started by " + sender.getName() + " for " + duration + " minutes");
    }

    /** /xa stopbloodmoon */
    public void stopBloodMoon(CommandSender sender) {
        // CRITICAL FIX: Stop blood moon and clean up
        if (bloodMoonPersisted || forcedBloodMoon) {
            bloodMoonPersisted = false;
            persistedBloodMoonDay = -1;
            forcedBloodMoon = false;
            forcedBloodMoonStartTime = -1; // CRITICAL FIX: Reset forced start time
            forcedBloodMoonDuration = -1; // CRITICAL FIX: Reset forced duration
            save();

            // --- MythicMobs: stop tick loop ---
            MythicMobsManager mm = plugin.getMythicMobsManager();
            if (mm != null) {
                mm.onBloodMoonEnd();
            }

            // CRITICAL FIX: Force bossbar cleanup
            if (bloodMoonBar != null && !bloodMoonBar.getPlayers().isEmpty()) {
                bloodMoonBar.removeAll();
            }

            // CRITICAL FIX: Set time to day to prevent immediate restart
            if (Bukkit.getWorlds().isEmpty()) return;
            // Bug M5 fix: target the first ENABLED world, not getWorlds().get(0).
            World world = Bukkit.getWorlds().stream()
                    .filter(plugin::isWorldEnabled)
                    .findFirst()
                    .orElse(null);
            if (world == null) {
                sender.sendMessage("§cNo enabled world is currently loaded.");
                return;
            }
            world.setTime(1000); // Set to day time
            sender.sendMessage("§7Time set to day to prevent restart.");

            sender.sendMessage("§cBlood moon stopped manually.");
            plugin.getLogger().info("Blood moon stopped by " + sender.getName() + " - time set to day");
            plugin.debugLog("Manual blood moon stop - persistence reset, time set to day.");
        } else {
            sender.sendMessage("§eNo blood moon is currently active.");
        }
    }
}
