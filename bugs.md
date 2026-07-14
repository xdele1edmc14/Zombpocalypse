# 🐛 xApocalypse — QA Bug Report

> Scope: full read of all 14 classes in `com.deleted.xapocalypse`, plus `config.yml`,
> `messages.yml`, and `plugin.yml`. Findings were cross-checked with independent audit passes.
> Line numbers are approximate and refer to the current sources.

## 0. Resolution status

All Critical, Moderate, and Minor findings are **fixed as of 1.4.3** (build verified with Maven).
The findings below are kept as the original report for reference.

**Fixed:** C1, C2, M1, M2, M3, M4, and Minor: `use-nametags` wired, `tps-threshold` wired (shipped
default lowered to `15.0`), dead keys removed from `config.yml` (`spawns-per-tick`,
`ignore-light-level`, `cleanup.miner-suppress-drops`), scent hot-path config cached, baby check uses
`isBaby()`, `getCurrentTPS()` catches `Throwable`, `/xa spawn <type>` now surface-snaps, and the
`UndeadSpawner` trackers are cleared on reload/disable.

**1.4.3 Minor closure:** the LOD and custom-AI zombie scans now share one pass; class behavior config
is cached and refreshed on reload; obsolete player-spawn overloads and their UUID tracker were
removed; scent state is released on quit; attributes use registry-backed references; dual-hand Guts
activation is deduplicated; and MiniMessage detection now requires a syntactically valid tag. The
natural Blood Moon bossbar uses the real 10,000-tick night window, so its progress and countdown now
reach zero with the event.

> ⚠️ **Behavior changes to be aware of after these fixes:** (1) natural Blood Moons now broadcast and
> spawn the MythicMobs Mutant — if you don't want that, set `mythicmobs.integration.max-global-cap`
> or disable the integration; (2) `zombie-classes.burster.break_blocks` defaults to `true`, so
> Bursters now damage terrain — set it to `false` to keep terrain intact; (3) spawning now pauses
> below `performance.tps-threshold` (default `15.0`) — tune it if that's too eager.

---

## 1. Summary

**Overall stability: solid core, one broken headline feature, and a large set of inert config knobs.**

The architecture is clean (facade + per-subsystem managers + a single listener) and shows a lot
of prior hardening (documented Bug C#/M# fixes, real-clock immunity timing, bounded LOD map).
Two findings stand out:

- **Thread safety: clean.** No async scheduling anywhere — all 17 scheduler calls are sync
  (`runTask*`), so no Bukkit world/entity API is touched off-thread.
- **No crash-level memory leaks.** Long-lived collections are bounded or cleaned; the few
  un-pruned maps are self-healing or negligible (see Minor).

The headline problem is that **natural Blood Moons silently skip their entire "start" event**
(announcement + MythicMobs boss), so on a default server the Mutant boss never appears. There is
also a reload-timing bug that can leave **permanently invincible, AI-less zombies**. Beyond those,
the dominant theme is **config keys that do nothing** — many advertised knobs are never read or
are overridden by hardcoded values.

| Severity | Count |
|----------|------:|
| 🔴 Critical | 2 |
| 🟠 Moderate | 4 |
| 🟡 Minor / Code smell | 12 |

**Verified working (no bug):** the Zombie-Guts max-health set/restore system is correct
end-to-end (capture-before-reduce, single guarded expiry path, correct rejoin/reload/persistence).

---

## 2. Critical Bugs

### 🔴 C1 — Natural Blood Moons never fire their "start" event (no announcement, no MythicMobs boss, no persistence)
**Files:** `BloodMoonManager.java` (`isActive` ~L146-200; task `if/else` ~L288/L366; natural-start branch ~L397-418), `MythicMobsManager.onBloodMoonStart`, `xApocalypse.java` (~L103-106).

On a natural interval night (`dayNumber % interval == 0`, not forced, `bloodMoonPersisted == false`):
`isActive()` computes `isDayOf = true` and `isNight = true` and `return isDayOf && isNight` → **`true`**
(L199). The repeating task therefore enters the `if (isActive(...))` branch and **never** reaches the
`else` branch where the natural-start code lives. As a result, for natural blood moons:

- ❌ the `"☠ BLOOD MOON HAS RISEN ☠"` broadcast never sends (L413-416 unreachable),
- ❌ `bloodMoonPersisted = true` / `save()` never runs (L399-401 unreachable),
- ❌ `mm.onBloodMoonStart()` never runs (L406 unreachable) → **the guaranteed Mutant and the
  periodic Mutant spawn loop never start.**

`mm.onBloodMoonStart()` is only reachable from `forceBloodMoon()` (the `/xa fbm` command) and the
`onEnable` resume path (which itself is gated on `bloodMoonPersisted || forcedBloodMoon`, never set
by the natural path). **Net effect:** the entire MythicMobs integration only works for forced blood
moons; on a normal server it silently never triggers. Zombie stat buffs, horde-size multiplier, and
the bossbar DO still work (they read `isActive()` directly), which is why this is easy to miss.

**Impact:** A headline, documented feature is silently dead in the default case; players also get no
chat warning that a blood moon began.
**Fix direction:** Detect the natural-start *transition* inside the `if (isActive)` branch (e.g. when
`isActive` is true but `bloodMoonPersisted` is false and it's an unforced interval night, run the
start once: set persistence, broadcast, call `onBloodMoonStart()`), or restructure so `isActive()`
doesn't pre-empt the start handler.

### 🔴 C2 — `/xa reload` (or disable) during a rise animation leaves permanently invincible, AI-less zombies
**Files:** `UndeadSpawner.runDigUpAnimation` (`setAI(false)`/`setGravity(false)`/`setInvulnerable(true)` + `ANIMATING_KEY` ~L135-140; `finalizeZombie` ~L200-207), `xApocalypse.reloadAll`/`onDisable` (`Bukkit.getScheduler().cancelTasks(this)` ~L198/L176), `PerformanceWatchdog.updateLODSystem` (skips `ANIMATING_KEY` ~L238-239/L250-251).

When `rising-animation` is on, each spawned zombie is set invulnerable, AI-off, gravity-off, and
tagged `ANIMATING_KEY` for a ~20-tick rise, then `finalizeZombie()` restores it. But `reloadAll()`
and `onDisable()` call `cancelTasks(this)`, which kills the animation timers **without** running
`finalizeZombie()`. Any zombie mid-rise is then stuck **invulnerable, no AI, no gravity, still
tagged `ANIMATING_KEY`** — and the LOD system explicitly skips `ANIMATING_KEY` zombies, so it never
re-enables their AI. These entities are saved to the world and persist across restart as permanent,
unkillable, frozen mobs. Additionally, their UUIDs are never removed from
`UndeadSpawner.activeAnimationEntities`, so repeated reloads-during-spawns can leak toward the
`MAX_CONCURRENT_ANIMATIONS = 50` cap and disable the rise animation entirely.

**Impact:** Permanent invulnerable zombies; admin `/xa reload` is a routine action and hordes rise
roughly every spawn cycle, so the window is hit in practice on active servers. Severe + permanent.
**Fix direction:** On disable/reload, sweep zombies carrying `ANIMATING_KEY` and finalize them
(clear tag, re-enable AI/gravity, clear invulnerability); clear `activeAnimationEntities`. Optionally
track active animation runnables and finalize them in `onDisable`.

---

## 3. Moderate Bugs

### 🟠 M1 — Many per-class tuning keys are ignored (AI uses hardcoded values)
**File:** `xApocalypseUtils.java` (the `tick*AI` methods and `applyZombieStats`).
Several documented `zombie-classes.*` knobs are never read; the behavior is hardcoded:

| Config key | Reality |
|------------|---------|
| `nurse.heal-amount-hp` (3.0) | Hardcoded **+4.0** in `tickNurseAI` |
| `nurse.heal-radius` (5.0) | Hardcoded `getNearbyEntities(5,5,5)` |
| `nurse.interval-seconds` / `max-targets-per-tick` | Interval hardcoded 3000 ms; no target cap enforced |
| `spitter.projectile-cooldown-seconds` (6) | Hardcoded **4000 ms** |
| `psychopath.rage-cooldown-seconds` (25) | Hardcoded **10000 ms** |
| `psychopath.bleed-duration-seconds` (3) | No bleed effect applied at all |
| `psychopath.speed-bonus` (0.08) | Not applied (rage uses a fixed Speed potion) |
| `burster.break_blocks` (true) | `createExplosion(..., false)` → blocks **never** broken |

**Impact:** Admins tuning these see no effect. **Fix:** read each key in the corresponding `tick*AI`
method (or remove the dead keys from `config.yml`).

### 🟠 M2 — The Scorched class is purely cosmetic (no on-hit fire/damage)
**Files:** `xApocalypseUtils.tickScorchedAI` (particles only), `xApocalypseListener.onEntityDamageByEntity` (only `WEBBER`/`FROST` handled).
Scorched spawns flame/smoke particles but applies **no fire ticks and no extra damage** to players —
there is no `SCORCHED` case in the damage handler, and `scorched.fire-duration-seconds` is unused.
Functionally it behaves like a Swarmer with effects. Contradicts its described role.
**Fix:** add a `SCORCHED` branch in `onEntityDamageByEntity` that ignites/burns the victim for
`fire-duration-seconds`, or restyle the class’s description to match the cosmetic-only behavior.

### 🟠 M3 — Veteran promotion is silently coupled to `scent-system.enabled`
**File:** `xApocalypseListener.onEntityDeath`.
The early `if (!scent-system.enabled) return;` sits **above** the VETERAN-promotion block, so turning
the scent system off also disables zombie→Veteran transformation. (Acknowledged in a code comment as
a parity quirk, but it's still surprising behavior.)
**Fix:** move the VETERAN promotion above the scent early-return, or gate it on its own config flag.

### 🟠 M4 — Per-class `enabled` flags are not honored
**File:** `xApocalypseUtils` (`getRandomZombieType`/`tick*AI`).
`zombie-classes.miner.enabled`, `nurse.enabled`, `spitter.enabled`, `scorched.enabled` are never read.
Setting any to `false` does **not** stop that class spawning (it's still in the weight pool) or stop
its ability. Admins reasonably expect these to disable a class.
**Fix:** honor each `enabled` flag in weight selection and/or the AI tick, or remove the keys.

---

## 4. Minor Bugs / Code Smell

### 🟡 Dead config keys (never read anywhere)
`performance.tps-threshold` (the watchdog hardcodes 10.0 pause / 15.0 resume instead of `18.5`),
`performance.spawns-per-tick`, `apocalypse-settings.ignore-light-level`, `visuals.use-nametags`
(zombies always get nametags; only `nametag-always-visible` is honored), `cleanup.miner-suppress-drops`
(miner uses `miner.drop-items` instead). → Remove or wire them up.

### 🟡 Uncached config reads on hot paths
`ScentManager.onMove` reads `scent-system.enabled` on **every** `PlayerMoveEvent` (fires on look-only
moves), and the per-class `tick*AI` methods call `getConfig().getX(...)` every tick. Cache these in
fields refreshed on reload (the plugin already caches other config values in `loadConfigValues`).

### 🟡 Two full-zombie iteration loops every 10 ticks
`HordeManager.startAITickTask` and `PerformanceWatchdog.updateLODSystem` each iterate every zombie in
every enabled world every 10 ticks. They could be merged into one pass.

### 🟡 Duplicate `isInsideClaim` logic
Implemented in both `xApocalypse` and `xApocalypseUtils`. Consolidate to one.

### 🟡 `UndeadSpawner.lastSpawnByPlayerMs` is never pruned
Grows one entry per player UUID and is never cleaned. It is only populated by the
`trySpawnUndeadRise(Player, ...)` overloads, which appear **unused** by the main spawn path (the horde
spawner uses the `Location` overload) — so today it likely never grows, but it's a latent leak / dead
code path. Prune on quit or remove the unused overloads.

### 🟡 `ScentManager.onPlayerQuit` doesn't remove `playerScent`
Negligible: the decay task iterates all entries (online or not) and removes them when they reach 0, so
offline players self-clean within minutes. Could still remove on quit for tidiness.

### 🟡 `allow-baby-zombies` uses `zombie.getAge() < 0` rather than `isBaby()`
**File:** `xApocalypseListener.onEntitySpawn`. Verify this actually blocks baby zombies on your server
version; `Zombie#isBaby()` is the modern, reliable check.

### 🟡 `Attribute.GENERIC_*` enum constants
Used throughout (`GENERIC_MAX_HEALTH`, etc.). These were deprecated as the attribute registry changed
in later 1.21.x. Compiles/runs against 1.21, but verify on your exact server build.

### 🟡 `Bukkit.getTPS()` wrapped in `catch (Exception)`
**File:** `PerformanceWatchdog.getCurrentTPS`. On very old Spigot without the method this throws
`NoSuchMethodError` (an `Error`, not caught). Non-issue on modern 1.21 (the method exists), noted for
completeness.

### 🟡 Natural blood-moon bossbar cosmetics
**File:** `BloodMoonManager.startTask`. For natural moons the bar drains from 100% to ~17% and then the
event ends at dawn (never reaches 0), and the displayed remaining time can read up to `10:00` while the
night actually lasts ~8 minutes. Purely cosmetic.

### 🟡 `/xa spawn <type>` (specific class) doesn't surface-snap
**File:** `xApocalypseCommand.handleSpawn`. The `horde` branch snaps to a valid surface, but the
specific-type branch spawns at the player's Y + offset, so zombies can appear inside walls or midair.

### 🟡 Dual-wielding Zombie Guts can show "already immune" on one right-click
**File:** `ImmunityManager.handleGutsInteract`. `PlayerInteractEvent` fires for both hands; if guts are
held in both, the second pass hits the already-immune guard and messages the player. Cosmetic.

### 🟡 MiniMessage detection heuristic can misfire
**File:** `MessageManager.parseMessage`. A legacy string containing both `<` and `#` is treated as
MiniMessage and may render oddly (falls back to legacy on parse failure). Edge case only.
