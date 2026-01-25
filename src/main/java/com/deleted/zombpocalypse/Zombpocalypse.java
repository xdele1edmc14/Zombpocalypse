package com.deleted.zombpocalypse;

import me.ryanhamshire.GriefPrevention.GriefPrevention;
import org.bukkit.*;
import org.bukkit.attribute.Attribute;
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
    private MessageManager messageManager;
    private PerformanceWatchdog performanceWatchdog;
    private HordeDirector hordeDirector;

    // --- PERSISTENCE FIELDS ---
    private File dataFile;
    private FileConfiguration dataConfig;

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
    private boolean forcedBloodMoon = false;
    private int bloodMoonInterval;
    private String bloodMoonTitle;
    private double bmHealthMult;
    private double bmDamageMult;
    private double bmSpeedMult;
    private double bmHordeMult;
    private BossBar bloodMoonBar;

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

        reloadConfig();
        loadConfigValues();

        // --- Message Manager Setup ---
        messageManager = new MessageManager(this);

        // --- Data Persistence Setup ---
        dataFile = new File(getDataFolder(), "data.yml");
        if (!dataFile.exists()) {
            try { dataFile.createNewFile(); } catch (IOException e) { e.printStackTrace(); }
        }
        dataConfig = YamlConfiguration.loadConfiguration(dataFile);
        loadImmunityData();

        // --- Hooks Setup ---
        setupHooks();

        // --- Utils Setup ---
        utils = new ZombpocalypseUtils(this, griefPrevention, griefPreventionEnabled);

        // --- Horde Director Setup ---
        hordeDirector = new HordeDirector(this, utils);

        // --- Performance Watchdog Setup ---
        performanceWatchdog = new PerformanceWatchdog(this);
        performanceWatchdog.setHordeDirector(hordeDirector);
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

        getLogger().info("[Zombpocalypse v1.3] Zombpocalypse has started! Brains...");
    }

    @Override
    public void onDisable() {
        saveImmunityData();

        if (performanceWatchdog != null) {
            performanceWatchdog.stop();
        }

        if (hordeDirector != null) {
            hordeDirector.stop();
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

        if (forcedBloodMoon) {
            isDayOf = true;
        }

        boolean isNight = time >= 13000 && time <= 23000;

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
                    long endTime = 23000;
                    long remaining = endTime - time;

                    if (remaining > 0) {
                        double progress = (double) remaining / 10000.0;
                        bloodMoonBar.setProgress(Math.max(0.0, Math.min(1.0, progress)));

                        long totalSeconds = remaining / 20;
                        long minutes = totalSeconds / 60;
                        long seconds = totalSeconds % 60;
                        String timeStr = String.format("%02d:%02d", minutes, seconds);

                        bloodMoonBar.setTitle(ChatColor.translateAlternateColorCodes('&',
                                bloodMoonTitle.replace("%time%", timeStr)));

                        for (Player p : Bukkit.getOnlinePlayers()) {
                            if (isWorldEnabled(p.getWorld()) && !bloodMoonBar.getPlayers().contains(p)) {
                                bloodMoonBar.addPlayer(p);
                            }
                        }
                    }
                } else {
                    if (forcedBloodMoon && mainWorld.getTime() > 23000) {
                        forcedBloodMoon = false;
                        debugLog("Forced Blood Moon flag reset (Morning arrived).");
                    }

                    if (!bloodMoonBar.getPlayers().isEmpty()) {
                        bloodMoonBar.removeAll();
                    }
                }
            }
        }.runTaskTimer(this, 20L, 20L);
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
        if (player.isOnGround() && event.getTo() != null && event.getFrom() != null) {
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

        daySpawnChance = getConfig().getDouble("apocalypse-settings.day-spawn-chance");
        useMobBlacklist = getConfig().getBoolean("apocalypse-settings.use-mob-blacklist");
        mobList = getConfig().getStringList("apocalypse-settings.mob-list");
        ignoreLightLevel = getConfig().getBoolean("apocalypse-settings.ignore-light-level");

        allowBabyZombies = getConfig().getBoolean("zombie-settings.allow-baby-zombies");
        allowZombieVillagers = getConfig().getBoolean("zombie-settings.allow-zombie-villagers");
        zombieGutsEnabled = getConfig().getBoolean("zombie-settings.zombie-guts.enabled");

        bloodMoonEnabled = getConfig().getBoolean("bloodmoon.enabled");
        bloodMoonInterval = getConfig().getInt("bloodmoon.interval-days", 10);
        bloodMoonTitle = getConfig().getString("bloodmoon.bossbar-title", "Blood Moon");
        bmHealthMult = getConfig().getDouble("bloodmoon.multipliers.health", 2.0);
        bmDamageMult = getConfig().getDouble("bloodmoon.multipliers.damage", 1.5);
        bmSpeedMult = getConfig().getDouble("bloodmoon.multipliers.speed", 1.2);
        bmHordeMult = getConfig().getDouble("bloodmoon.multipliers.horde-size", 1.5);

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

        if (bloodMoonBar != null) {
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
            if (!allowBabyZombies && zombie.isBaby()) { event.setCancelled(true); return; }
            if (!allowZombieVillagers && zombie.isVillager()) { event.setCancelled(true); return; }

            // Assign zombie type
            utils.assignZombieType(zombie);
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
        if (event.getEntity() instanceof Zombie) {
            long time = event.getEntity().getWorld().getTime();
            boolean isDay = time > 0 && time < 12300;
            if (isDay) event.setCancelled(true);
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
        int baseAmount = getConfig().getInt("apocalypse-settings.base-horde-size", 10);
        int variance = getConfig().getInt("apocalypse-settings.horde-variance", 5);

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
            double scentScale = getConfig().getDouble("scent-system.scent-scale", 10.0);
            multiplier *= (1.0 + (scent / scentScale));
        }

        int totalAmount = (int) ((baseAmount + ThreadLocalRandom.current().nextInt(safeVariance + 1)) * multiplier);

        // Cap
        int spawnCap = getConfig().getInt("scent-system.spawn-cap", 50);
        totalAmount = Math.min(totalAmount, spawnCap);

        debugLog("Attempting to spawn horde of size: " + totalAmount + " near " + player.getName() + " (Multiplier: " + multiplier + ")");

        // Use HordeDirector to queue spawns with directional spawning and FOV masking
        if (hordeDirector != null) {
            hordeDirector.queueZombieSpawns(player, totalAmount, isDayHordeSpawn);
        }
    }

    // === COMMANDS ===

    @Override
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
                if (hordeDirector != null) {
                    hordeDirector.reload();
                }
                startSpawnerTask();
                startImmunityBossBarTask();
                startBloodMoonTask();
                startScentDecayTask();
                startAITickTask();
                startBuilderCleanupTask();
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

            forcedBloodMoon = true;
            sender.sendMessage(messageManager.get("bloodmoon.force-start"));

            long time = world.getTime();
            if (time < 13000 || time > 23000) {
                world.setTime(13000);
                sender.sendMessage(messageManager.get("bloodmoon.force-time-set"));
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

            count = Math.min(count, 50); // Safety cap
            radius = Math.min(radius, 50);

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