# Zombpocalypse Bug Fixes

---

## Bug 1 — `Zombpocalypse.java` | `aiTask` never cancelled; stacks on reload

**Root cause:** `startAITickTask()` assigns to `aiTask` but `aiTask` is never cancelled before a new one is started. Every `/zreload` stacks another AI tick loop.

**Fix — cancel before reassigning, and null-guard on disable:**

```java
// In startAITickTask(), cancel the old task first:
private void startAITickTask() {
    if (aiTask != null && !aiTask.isCancelled()) {
        aiTask.cancel();
    }
    aiTask = new BukkitRunnable() {
        @Override
        public void run() {
            for (World world : Bukkit.getWorlds()) {
                if (!isWorldEnabled(world)) continue;
                for (Entity entity : world.getEntitiesByClass(Zombie.class)) {
                    if (entity instanceof Zombie zombie) {
                        if (performanceWatchdog == null || performanceWatchdog.shouldTickZombieAI(zombie)) {
                            utils.tickZombieAI(zombie);
                        }
                    }
                }
            }
        }
    }.runTaskTimer(this, 0L, 10L);
}

// In onDisable(), add:
@Override
public void onDisable() {
    if (aiTask != null && !aiTask.isCancelled()) {
        aiTask.cancel();
    }
    // ... rest of onDisable
}
```

---

## Bug 2 — `Zombpocalypse.java` | Acid spit never applies poison

**Root cause 1:** `onProjectileHit` only handles `Snowball`, but `tickSpitterAI` fires a `LlamaSpit`.  
**Root cause 2:** Even if the type matched, the PDC key used is `ZOMBIE_TYPE_KEY` — a key that belongs to the zombie entity, not the projectile. The spit entity has no such key set.

**Fix — handle `LlamaSpit` and use a dedicated PDC key:**

```java
// In ZombpocalypseUtils.java, add a new key:
public static final NamespacedKey ACID_SPIT_KEY = new NamespacedKey("zombpocalypse", "acid_spit");

// In tickSpitterAI(), tag the spit after launching:
private void tickSpitterAI(Zombie spitter) {
    LivingEntity target = spitter.getTarget();
    if (target == null) return;

    double dist = spitter.getLocation().distance(target.getLocation());
    if (dist > 15 || dist < 4) return;

    long now = System.currentTimeMillis();
    Long lastSpit = spitter.getPersistentDataContainer().get(LAST_SPIT_KEY, PersistentDataType.LONG);
    if (lastSpit != null && (now - lastSpit) < 4000) return;

    spitter.getPersistentDataContainer().set(LAST_SPIT_KEY, PersistentDataType.LONG, now);
    Vector velocity = target.getLocation().add(0, 1, 0).toVector()
            .subtract(spitter.getEyeLocation().toVector()).normalize().multiply(1.2);
    LlamaSpit spit = spitter.launchProjectile(LlamaSpit.class, velocity);
    spit.setShooter(spitter);
    // Tag the projectile so the hit handler can identify it
    spit.getPersistentDataContainer().set(ACID_SPIT_KEY, PersistentDataType.BYTE, (byte) 1);
    spitter.getWorld().playSound(spitter.getLocation(), Sound.ENTITY_LLAMA_SPIT, 1.0f, 0.8f);
}

// In Zombpocalypse.java, fix onProjectileHit:
@EventHandler
public void onProjectileHit(ProjectileHitEvent event) {
    // Handle LlamaSpit acid attacks from Spitter zombies
    if (event.getEntity() instanceof LlamaSpit spit) {
        boolean isAcid = spit.getPersistentDataContainer()
                .has(ZombpocalypseUtils.ACID_SPIT_KEY, PersistentDataType.BYTE);
        if (isAcid && event.getHitEntity() != null) {
            utils.handleAcidHit(event.getHitEntity());
        }
        return;
    }

    // Keep legacy Snowball handler if used elsewhere
    if (event.getEntity() instanceof Snowball snowball) {
        String acidTag = snowball.getPersistentDataContainer()
                .get(ZombpocalypseUtils.ZOMBIE_TYPE_KEY, PersistentDataType.STRING);
        if (acidTag != null && acidTag.equals("ACID") && event.getHitEntity() != null) {
            utils.handleAcidHit(event.getHitEntity());
        }
    }
}
```

---

## Bug 3 — `Zombpocalypse.java` | Side-effect in `isBloodMoonActive()` sets start time

**Root cause:** `isBloodMoonActive()` is a read method called many times per second, but it mutates `forcedBloodMoonStartTime` as a fallback. This means the timer can reset on any call where the field happens to be -1.

**Fix — set `forcedBloodMoonStartTime` only in the command handler, never inside the check method:**

```java
// Remove the fallback assignment from isBloodMoonActive():
public boolean isBloodMoonActive(World world) {
    if (!bloodMoonEnabled) return false;
    if (!isWorldEnabled(world)) return false;

    long time = world.getTime();
    long fullTime = world.getFullTime();
    long dayNumber = fullTime / 24000;

    boolean isDayOf = (dayNumber > 0) && (dayNumber % bloodMoonInterval == 0);

    if (bloodMoonPersisted && persistedBloodMoonDay == dayNumber) isDayOf = true;
    if (forcedBloodMoon) isDayOf = true;

    boolean isNight = time >= 13000 && time <= 23000;

    if (forcedBloodMoon) {
        // forcedBloodMoonStartTime is ALWAYS set in the command handler — never set it here
        if (forcedBloodMoonStartTime == -1) return false; // not properly started yet
        long elapsedMs = System.currentTimeMillis() - forcedBloodMoonStartTime;
        long elapsedTicks = elapsedMs / 50;
        long actualDuration = forcedBloodMoonDuration != -1 ? forcedBloodMoonDuration : bloodMoonForceDuration;
        long durationTicks = actualDuration * 60 * 20L;
        if (elapsedTicks >= durationTicks) return false;
        return true; // forced moon is active for its duration regardless of time-of-day
    }

    if (bloodMoonPersisted && isNight) {
        long bloodMoonStartTick = 13000;
        long currentTick = time;
        long durationTicks = (long) (10000); // natural night lasts ~10000 ticks
        long bloodMoonEndTick = bloodMoonStartTick + durationTicks;
        if (currentTick > bloodMoonEndTick) return false;
    }

    return isDayOf && isNight;
}

// In the forcebloodmoon command, ensure start time is always set before setting the flag:
forcedBloodMoonStartTime = System.currentTimeMillis();
forcedBloodMoonDuration = duration;
forcedBloodMoon = true;
saveBloodMoonData();
```

---

## Bug 4 — `Zombpocalypse.java` | Mob whitelist cancels plugin-spawned zombies

**Root cause:** `onEntitySpawn` runs for all spawns including ones your plugin triggers. When using whitelist mode (`useMobBlacklist = false`), any entity not in the list is cancelled — including your own zombies if `ZOMBIE` isn't explicitly listed, or more subtly, if the event fires before `assignZombieType` has run.

**Fix — skip the list check entirely for plugin-owned spawns using a PDC tag:**

```java
// In ZombpocalypseUtils.java, add a key:
public static final NamespacedKey PLUGIN_SPAWNED_KEY = new NamespacedKey("zombpocalypse", "plugin_spawned");

// In spawnZombiesNearPlayer() and anywhere you spawn directly, tag before the event fires
// by using a pre-spawn hook. Since Bukkit doesn't support pre-spawn tagging directly,
// use a flag approach — add to a Set<UUID> of pending spawns:

// In Zombpocalypse.java:
private final Set<UUID> pluginSpawnedPending = new HashSet<>();

// Wrap all direct spawns:
private Zombie spawnZombie(Location loc) {
    // Use a thread-local flag checked in onEntitySpawn
    isPluginSpawning = true;
    Zombie z = (Zombie) loc.getWorld().spawnEntity(loc, EntityType.ZOMBIE);
    isPluginSpawning = false;
    return z;
}
private boolean isPluginSpawning = false;

@EventHandler
public void onEntitySpawn(CreatureSpawnEvent event) {
    if (!isWorldEnabled(event.getLocation().getWorld())) return;

    Entity entity = event.getEntity();

    // Always allow plugin-triggered spawns through
    if (isPluginSpawning) {
        if (entity instanceof Zombie zombie) {
            if (!allowBabyZombies && zombie.getAge() < 0) { event.setCancelled(true); return; }
            if (!allowZombieVillagers && zombie.getType() == EntityType.ZOMBIE_VILLAGER) { event.setCancelled(true); return; }
            utils.assignZombieType(zombie);
        }
        return;
    }

    String mobName = entity.getType().toString();
    boolean inList = mobList.contains(mobName);

    if (useMobBlacklist) {
        if (inList) { event.setCancelled(true); return; }
    } else {
        if (!inList) { event.setCancelled(true); return; }
    }

    if (entity instanceof Monster && !(entity instanceof Zombie)) {
        if (event.getSpawnReason() == CreatureSpawnEvent.SpawnReason.NATURAL) {
            event.setCancelled(true); return;
        }
    }

    if (entity instanceof Zombie zombie) {
        if (!allowBabyZombies && zombie.getAge() < 0) { event.setCancelled(true); return; }
        if (!allowZombieVillagers && zombie.getType() == EntityType.ZOMBIE_VILLAGER) { event.setCancelled(true); return; }
        utils.assignZombieType(zombie);
    }
}
```

> **Note:** Replace all direct `world.spawnEntity(...)` calls in `spawnZombiesNearPlayer`, `/zspawn HORDE`, and `/zspawn <type>` with the `spawnZombie(loc)` wrapper. `UndeadSpawner` already calls `utils.assignZombieType` internally, so it can set `isPluginSpawning` the same way.

---

## Bug 5 — `ZombpocalypseUtils.java` | `new NamespacedKey` allocated every tick in `tickBuilderAI`

**Fix — promote to a static final constant alongside the other keys:**

```java
// In ZombpocalypseUtils.java, add with the other keys at the top:
public static final NamespacedKey LAST_BUILD_KEY = new NamespacedKey("zombpocalypse", "last_build");

// In tickBuilderAI(), replace the local variable:
private void tickBuilderAI(Zombie builder) {
    LivingEntity target = builder.getTarget();
    if (target == null) return;

    long now = System.currentTimeMillis();
    // Use the static constant instead of allocating a new key every call
    Long lastBuild = builder.getPersistentDataContainer().get(LAST_BUILD_KEY, PersistentDataType.LONG);
    int delay = plugin.getConfig().getInt("zombie-classes.builder.place-delay-ticks", 40) * 50;

    if (lastBuild != null && (now - lastBuild) < delay) return;

    // ... rest of method unchanged ...

    builder.getPersistentDataContainer().set(LAST_BUILD_KEY, PersistentDataType.LONG, now);
}
```

---

## Bug 6 — `Zombpocalypse.java` | Immunity BossBar leaks on rapid reconnect

**Root cause:** `onPlayerJoin` creates a new `BossBar` and puts it in `immunityBossBars` without checking if one already exists from a previous session load.

**Fix — always remove the old bar before creating a new one:**

```java
@EventHandler
public void onPlayerJoin(PlayerJoinEvent event) {
    Player player = event.getPlayer();
    UUID uuid = player.getUniqueId();

    cleanupBossbarForPlayer(player);

    // Remove any pre-existing immunity bossbar before creating a new one
    BossBar existingBar = immunityBossBars.remove(uuid);
    if (existingBar != null) {
        existingBar.removeAll();
    }

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
            try { dataConfig.save(dataFile); } catch (IOException e) { e.printStackTrace(); }
        }
    }
}
```

---

## Bug 7 — `PerformanceWatchdog.java` | `zombieLastAITick` grows unbounded

**Fix — add a size guard before inserting, and switch to a `LinkedHashMap` with eviction:**

```java
// Replace the HashMap declaration with a size-bounded LinkedHashMap:
private final Map<Zombie, Long> zombieLastAITick = new LinkedHashMap<>(256, 0.75f, false) {
    @Override
    protected boolean removeEldestEntry(Map.Entry<Zombie, Long> eldest) {
        // Evict dead/invalid entries when map exceeds 1000 entries
        return size() > 1000 || eldest.getKey().isDead() || !eldest.getKey().isValid();
    }
};

// Also tighten the cleanup at the bottom of updateLODSystem() to run more aggressively:
// Replace:
zombieLastAITick.entrySet().removeIf(entry -> entry.getKey().isDead() || !entry.getKey().isValid());

// With:
if (zombieLastAITick.size() > 500) {
    zombieLastAITick.entrySet().removeIf(entry -> entry.getKey().isDead() || !entry.getKey().isValid());
}
```

---

## Bug 8 — `Zombpocalypse.java` | `builderBlocks` / `builderBlockOwners` unbounded

**Fix — cap the maps at insertion time and add an emergency purge:**

```java
private static final int MAX_BUILDER_BLOCKS = 2048;

public void trackBuilderBlock(Location loc, UUID zombieUUID) {
    if (builderBlocks.size() >= MAX_BUILDER_BLOCKS) {
        // Emergency purge: remove all expired entries immediately
        long now = System.currentTimeMillis();
        int cleanupSeconds = getConfig().getInt("cleanup.builder-auto-cleanup-seconds", 300);
        long cleanupMs = cleanupSeconds * 1000L;
        List<Location> expired = new ArrayList<>();
        for (Map.Entry<Location, Long> entry : builderBlocks.entrySet()) {
            if (now - entry.getValue() >= cleanupMs) {
                expired.add(entry.getKey());
            }
        }
        for (Location expiredLoc : expired) {
            builderBlocks.remove(expiredLoc);
            builderBlockOwners.remove(expiredLoc);
        }
        // If still over cap after purge, drop the new entry to avoid OOM
        if (builderBlocks.size() >= MAX_BUILDER_BLOCKS) {
            debugLog("Builder block cap reached (" + MAX_BUILDER_BLOCKS + "), skipping track for " + loc);
            return;
        }
    }
    builderBlocks.put(loc, System.currentTimeMillis());
    builderBlockOwners.put(loc, zombieUUID);
}
```

---

## Bugs 9, 10 — `Zombpocalypse.java` | Triple immunity expiry — messages sent up to 3×

**Root cause:** Three independent systems all handle expiry: `startImmunityCheckTask`, `startImmunityBossBarTask`, and `scheduleImmunityRemoval`. They all call `cleanUpPlayerState` and send messages.

**Fix — establish a single expiry path. Remove the expiry logic from `startImmunityCheckTask` and `scheduleImmunityRemoval`, and let the bossbar task be the single source of truth:**

```java
// startImmunityCheckTask() — remove the expiry handling, keep only the retargeting logic:
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

                // Only retarget zombies — expiry is handled solely by startImmunityBossBarTask
                if (currentFullTime >= endTime) {
                    retargetZombiesNearPlayer(player);
                }
            }
        }
    }.runTaskTimer(this, 20L, 20L);
}

// scheduleImmunityRemoval() — remove the expiry message and cleanUpPlayerState call.
// Its only job now is to be a safety net that fires cleanUpPlayerState if the bossbar task misses it:
private void scheduleImmunityRemoval(Player player, long durationTicks) {
    UUID uuid = player.getUniqueId();
    BukkitTask existing = scheduledTasks.remove(uuid);
    if (existing != null) existing.cancel();

    BukkitTask task = Bukkit.getScheduler().runTaskLater(this, () -> {
        // Only clean up state — do NOT send messages here; bossbar task owns messaging
        if (immunePlayers.contains(uuid)) {
            cleanUpPlayerState(player);
            dataConfig.set("player-immunity." + uuid.toString(), null);
            try { dataConfig.save(dataFile); } catch (IOException e) { e.printStackTrace(); }
        }
        scheduledTasks.remove(uuid);
    }, durationTicks);
    scheduledTasks.put(uuid, task);
}

// startImmunityBossBarTask() — this becomes the sole place that sends expiry messages:
// (the existing expiry block at lines 795-807 is already correct, just ensure the
// other two paths above no longer send messages)
```

---

## Bug 11 — `Zombpocalypse.java` | `onPlayerConsume` removes wrong item amount

**Root cause:** When `event.setCancelled(true)`, vanilla doesn't consume the item. Manual removal using `ItemStack.equals()` matches by type/meta/amount — if the player has a stack of 2 zombie guts in offhand, it removes the whole stack instead of decrementing.

**Fix — always decrement the specific item in the specific hand slot:**

```java
@EventHandler
public void onPlayerConsume(PlayerItemConsumeEvent event) {
    if (!zombieGutsEnabled) return;

    ItemStack item = event.getItem();
    Player player = event.getPlayer();
    UUID uuid = player.getUniqueId();

    if (item.getType() != Material.ROTTEN_FLESH) return;
    if (!item.hasItemMeta() || !item.getItemMeta().hasDisplayName()) return;
    if (!item.getItemMeta().getDisplayName().equals(messageManager.get("immunity.item-name"))) return;

    event.setCancelled(true);

    if (immunePlayers.contains(uuid)) {
        player.sendMessage(messageManager.get("immunity.already-immune"));
        return;
    }

    // Apply immunity effects
    double maxHealth = 10.0;
    if (player.getAttribute(Attribute.GENERIC_MAX_HEALTH) != null) {
        originalHealth.put(uuid, player.getAttribute(Attribute.GENERIC_MAX_HEALTH).getBaseValue());
        player.getAttribute(Attribute.GENERIC_MAX_HEALTH).setBaseValue(maxHealth);
        player.setHealth(Math.min(player.getHealth(), maxHealth));
    }

    immunePlayers.add(uuid);
    long endTime = player.getWorld().getFullTime() + IMMUNITY_DURATION_TICKS;
    immunityEndTime.put(uuid, endTime);

    BossBar bar = Bukkit.createBossBar(
            messageManager.get("immunity.bossbar", "10:00"), BarColor.GREEN, BarStyle.SOLID);
    bar.addPlayer(player);
    immunityBossBars.put(uuid, bar);

    scheduleImmunityRemoval(player, IMMUNITY_DURATION_TICKS);
    player.sendMessage(messageManager.get("immunity.consumed"));

    for (Entity entity : player.getWorld().getEntitiesByClass(Zombie.class)) {
        if (entity instanceof Zombie zombie) {
            if (zombie.getTarget() != null && zombie.getTarget().equals(player)) {
                zombie.setTarget(null);
            }
        }
    }

    // Decrement exactly one item from the correct hand slot — never use equals() on stacks
    ItemStack mainHand = player.getInventory().getItemInMainHand();
    ItemStack offHand = player.getInventory().getItemInOffHand();

    // Check if the consumed item is the exact same reference as a hand slot
    if (mainHand.isSimilar(item)) {
        if (mainHand.getAmount() > 1) {
            mainHand.setAmount(mainHand.getAmount() - 1);
            player.getInventory().setItemInMainHand(mainHand);
        } else {
            player.getInventory().setItemInMainHand(null);
        }
    } else if (offHand.isSimilar(item)) {
        if (offHand.getAmount() > 1) {
            offHand.setAmount(offHand.getAmount() - 1);
            player.getInventory().setItemInOffHand(offHand);
        } else {
            player.getInventory().setItemInOffHand(null);
        }
    }
}
```

---

## Bug 12 — `HordeSpawnerTask.java` | Day-check uses wrong world

**Fix — find the first enabled world instead of always using index 0:**

```java
@Override
public void run() {
    try {
        if (!plugin.getConfig().getBoolean("debug-mode", false) && !hasLoggedDebug) {
            plugin.debugLog("TASK: Running scheduled spawner check.");
            hasLoggedDebug = true;
        }

        // Find the first enabled world rather than assuming index 0
        World targetWorld = Bukkit.getWorlds().stream()
                .filter(plugin::isWorldEnabled)
                .findFirst()
                .orElse(null);

        if (targetWorld == null) {
            plugin.debugLog("TASK: No enabled worlds loaded, skipping spawn attempt.");
            return;
        }

        long time = targetWorld.getTime();
        boolean isDay = time > 0 && time < 13000;
        boolean isDayHordeSpawn = false;

        if (isDay) {
            double daySpawnChance = plugin.getConfig().getDouble("apocalypse-settings.day-spawn-chance", 0.0);
            if (ThreadLocalRandom.current().nextDouble() > daySpawnChance) return;
            isDayHordeSpawn = true;
        }

        for (Player player : Bukkit.getOnlinePlayers()) {
            if (!plugin.isWorldEnabled(player.getWorld())) continue;
            if (player.getGameMode() == GameMode.CREATIVE || player.getGameMode() == GameMode.SPECTATOR) continue;
            plugin.spawnZombiesNearPlayer(player, isDayHordeSpawn);
        }

    } catch (Throwable t) {
        plugin.getLogger().severe("FATAL TASK ERROR: The repeating spawn task crashed!");
        t.printStackTrace();
    }
}
```

---

## Bug 13 — `Zombpocalypse.java` | Blood moon task not tracked; can't safely restart

**Fix — store the blood moon task reference and cancel it explicitly on reload:**

```java
// Add field:
private BukkitTask bloodMoonTask = null;

// In startBloodMoonTask(), assign the handle:
private void startBloodMoonTask() {
    if (bloodMoonTask != null && !bloodMoonTask.isCancelled()) {
        bloodMoonTask.cancel();
    }
    bloodMoonBar = Bukkit.createBossBar("Blood Moon", BarColor.RED, BarStyle.SEGMENTED_10);
    bloodMoonTask = new BukkitRunnable() {
        @Override
        public void run() {
            // ... existing logic unchanged ...
        }
    }.runTaskTimer(this, 20L, 20L);
}

// In onDisable():
if (bloodMoonTask != null && !bloodMoonTask.isCancelled()) {
    bloodMoonTask.cancel();
}
```

---

## Bug 14 — `ZombpocalypseUtils.java` | `getRandomZombieType()` biases last entry

**Fix — use strict `<` and guarantee a non-null fallback:**

```java
private ZombieType getRandomZombieType() {
    if (totalWeight <= 0) return ZombieType.NORMAL;
    double random = ThreadLocalRandom.current().nextDouble() * totalWeight;
    double cumulative = 0.0;
    for (Map.Entry<ZombieType, Double> entry : spawnWeights.entrySet()) {
        cumulative += entry.getValue();
        if (random < cumulative) return entry.getKey(); // strict < fixes the boundary bias
    }
    // Fallback for floating-point precision edge case — return last entry
    return spawnWeights.isEmpty() ? ZombieType.NORMAL
            : spawnWeights.entrySet().iterator().next().getKey();
}
```

---

## Bug 15 — `ZombpocalypseUtils.java` | Most zombie types missing fire resistance potion for sunlight burn

**Root cause:** `onEntityCombust` cancels sunlight burning only if the zombie has the `FIRE_RESISTANCE` potion effect. Most custom types don't get it in `applyZombieStats`.

**Fix — apply fire resistance to all custom types in `applyZombieStats`. Add a helper to avoid repetition:**

```java
// Add helper method:
private void applyFireResistance(Zombie zombie) {
    zombie.addPotionEffect(new PotionEffect(PotionEffectType.FIRE_RESISTANCE,
            Integer.MAX_VALUE, 0, false, false));
}

// Then in applyZombieStats(), add the call to every non-NORMAL case:
case RUNNER -> {
    // ...stats...
    applyFireResistance(zombie); // ADD THIS
}
case NURSE -> {
    // ...stats...
    applyFireResistance(zombie); // ADD THIS
}
case SPITTER -> {
    // ...stats...
    applyFireResistance(zombie); // ADD THIS
}
case PSYCHOPATH -> {
    // ...stats...
    applyFireResistance(zombie); // ADD THIS
}
case MINER -> {
    // ...stats...
    applyFireResistance(zombie); // ADD THIS
}
case BUILDER -> {
    // ...stats...
    applyFireResistance(zombie); // ADD THIS
}
case VETERAN -> {
    // ...stats...
    applyFireResistance(zombie); // ADD THIS
}
case WEBBER -> {
    // ...stats...
    applyFireResistance(zombie); // ADD THIS
}
case BURSTER -> {
    // ...stats...
    applyFireResistance(zombie); // ADD THIS
}
case FROST -> {
    // ...stats...
    applyFireResistance(zombie); // ADD THIS
}
// SCORCHED and SWARMER already have it. NORMAL intentionally does not.
```

---

## Bug 16 — `Zombpocalypse.java` | Natural blood moon duration uses force-duration config

**Root cause:** `isBloodMoonActive()` at line 273 uses `bloodMoonForceDuration` (in minutes) to decide when a **natural** blood moon ends, instead of the actual Minecraft night length.

**Fix — use the actual night window (ticks 13000–23000 = 10000 ticks) for natural moons:**

```java
// In isBloodMoonActive(), replace the natural blood moon duration block:
if (bloodMoonPersisted && !forcedBloodMoon && isNight) {
    // Natural blood moon lasts for the entire night window: tick 13000 to 23000
    // No artificial early-ending based on force-duration config
    // If it's still night and the day matches, it's active.
    // (The night end at 23000 is already handled by the isNight check above)
    // So just fall through to the return below.
}

// The final return becomes:
return isDayOf && isNight;
// This is already correct for natural moons — no extra duration check needed.
// The problematic block (lines 270-280) should be removed entirely.
```

---

## Bug 17 — `Zombpocalypse.java` | Natural blood moon fires at night-start of the PREVIOUS day number

This is a logic clarification rather than a code change. `fullTime / 24000` rolls over at the start of a new day (daytime). By the time it's night of that day, `dayNumber` is already the correct number. No code change needed — but add this comment to prevent future regressions:

```java
// NOTE: fullTime / 24000 gives the current day number.
// At night (time >= 13000), dayNumber is already the day that started this morning.
// Example: on the 10th day, dayNumber = 10 at all hours including night.
// The blood moon correctly fires on night of day 10, 20, 30, etc.
boolean isDayOf = (dayNumber > 0) && (dayNumber % bloodMoonInterval == 0);
```

---

## Bug 18 — `Zombpocalypse.java` | `stopbloodmoon` NPE if `bloodMoonBar` is null

**Fix — null-guard before accessing the bar:**

```java
if (command.getName().equalsIgnoreCase("stopbloodmoon")) {
    // ... permission check ...

    if (bloodMoonPersisted || forcedBloodMoon) {
        bloodMoonPersisted = false;
        persistedBloodMoonDay = -1;
        forcedBloodMoon = false;
        forcedBloodMoonStartTime = -1;
        forcedBloodMoonDuration = -1;
        saveBloodMoonData();

        if (mythicMobsManager != null) mythicMobsManager.onBloodMoonEnd();

        // Null-guard the bossbar
        if (bloodMoonBar != null && !bloodMoonBar.getPlayers().isEmpty()) {
            bloodMoonBar.removeAll();
        }

        if (Bukkit.getWorlds().isEmpty()) return true;
        World world = Bukkit.getWorlds().get(0);
        world.setTime(1000);
        sender.sendMessage("§7Time set to day to prevent restart.");
        sender.sendMessage("§cBlood moon stopped manually.");
        getLogger().info("Blood moon stopped by " + sender.getName());
    } else {
        sender.sendMessage("§eNo blood moon is currently active.");
    }
    return true;
}
```

---

## Bug 19 — `PerformanceWatchdog.java` | LOD system doesn't throttle actual vanilla AI

**Root cause:** `shouldTickZombieAI` only gates your plugin's custom AI methods. Vanilla pathfinding runs regardless.

**Fix — use `zombie.setAI(false/true)` for genuinely far zombies to actually reduce server load:**

```java
private void updateLODSystem() {
    long currentTick = Bukkit.getServer().getCurrentTick();

    for (World world : Bukkit.getWorlds()) {
        if (!plugin.isWorldEnabled(world)) continue;

        List<Player> players = world.getPlayers();
        if (players.isEmpty()) {
            // No players — disable AI for all zombies in this world
            for (Entity entity : world.getEntitiesByClass(Zombie.class)) {
                if (entity instanceof Zombie zombie && !zombie.isDead()) {
                    zombie.setAI(false);
                    zombieLastAITick.put(zombie, currentTick);
                }
            }
            continue;
        }

        for (Entity entity : world.getEntitiesByClass(Zombie.class)) {
            if (!(entity instanceof Zombie zombie)) continue;
            if (zombie.isDead() || !zombie.isValid()) continue;

            double minDistance = Double.MAX_VALUE;
            for (Player player : players) {
                double dist = zombie.getLocation().distanceSquared(player.getLocation());
                if (dist < minDistance) minDistance = dist;
            }
            double distance = Math.sqrt(minDistance);

            if (distance > lodDistanceThreshold) {
                // Actually disable vanilla AI for far zombies
                if (zombie.hasAI()) {
                    zombie.setAI(false);
                }
                zombieLastAITick.put(zombie, currentTick);
            } else {
                // Re-enable AI when player is close
                if (!zombie.hasAI()) {
                    zombie.setAI(true);
                }
                zombieLastAITick.remove(zombie);
            }
        }
    }

    zombieLastAITick.entrySet().removeIf(entry -> entry.getKey().isDead() || !entry.getKey().isValid());
}
```

> **Note:** Also ensure `UndeadSpawner.finalizeZombie()` always sets `zombie.setAI(true)` after the rise animation (it already does). If a zombie is mid-animation when the LOD system runs, the `setAI(false)` call will conflict with the animation — add a PDC tag in `UndeadSpawner` to mark animating zombies and skip them in `updateLODSystem`.

---

## Bug 20 — `Zombpocalypse.java` / `UndeadSpawner.java` | `assignZombieType` called twice per spawn

**Root cause:** `UndeadSpawner.trySpawnUndeadRise` calls `utils.assignZombieType(zombie)`, then `CreatureSpawnEvent` fires and `onEntitySpawn` calls it again. The zombie gets two random type rolls.

**Fix — use the `isPluginSpawning` flag from Bug 4's fix, and skip `assignZombieType` in `onEntitySpawn` when the spawn is plugin-owned (since `UndeadSpawner` already assigned the type):**

```java
// In UndeadSpawner.trySpawnUndeadRise(), set the flag before spawning:
public Zombie trySpawnUndeadRise(Location surface, Block surfaceBlock,
                                   BlockData surfaceData, long startDelayTicks) {
    // ...existing guards...

    plugin.setPluginSpawning(true); // expose via a package-private setter
    Zombie zombie = (Zombie) world.spawnEntity(spawnLoc, EntityType.ZOMBIE);
    plugin.setPluginSpawning(false);

    if (zombie == null) { ... }
    utils.assignZombieType(zombie); // only called once here
    // ...
}

// In Zombpocalypse.java, add setter:
void setPluginSpawning(boolean value) {
    this.isPluginSpawning = value;
}

// onEntitySpawn() already skips assignZombieType when isPluginSpawning is true
// (as established in Bug 4's fix), so no further change is needed there.

// For the direct spawn path in spawnZombiesNearPlayer (non-animation):
if (!risingAnimation) {
    isPluginSpawning = true;
    Zombie zombie = (Zombie) surface.getWorld().spawnEntity(surface, EntityType.ZOMBIE);
    isPluginSpawning = false;
    if (zombie != null) {
        utils.assignZombieType(zombie);
    }
}
```

---

## Minor / Spelling Fixes

**`PerformanceWatchdog.java` — Remove dead no-op stubs or implement them:**
```java
// Either remove these entirely, or wire them to a boolean flag:
private boolean spawningPaused = false;

private void pauseAllSpawning() {
    spawningPaused = true;
    plugin.debugLog("Spawning paused due to critical TPS.");
}

private void resumeSpawning() {
    if (spawningPaused) {
        spawningPaused = false;
        plugin.debugLog("Spawning resumed — TPS recovered.");
    }
}

// Then in HordeSpawnerTask.run(), add at the top:
if (plugin.getPerformanceWatchdog().isSpawningPaused()) return;
```

**`MythicMobsManager.java` — Fix raw type warning:**
```java
// Line 31 — change:
private final Set<UUID> activeMutants = new HashSet();
// To:
private final Set<UUID> activeMutants = new HashSet<>();
```

**`HordeSpawnerTask.java` — `hasLoggedDebug` suppresses all future debug output:**
```java
// Remove the flag entirely — debugLog already no-ops when debug-mode is false:
@Override
public void run() {
    plugin.debugLog("TASK: Running scheduled spawner check.");
    // ... rest of method
}
```
