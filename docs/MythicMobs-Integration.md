# 🐉 MythicMobs Integration

xApocalypse can optionally spawn a **Mutant** boss — a custom [MythicMobs](https://www.spigotmc.org/resources/mythicmobs.5702/) mob — tied to the [Blood Moon](Blood-Moon.md). This is an entirely optional feature: if MythicMobs isn't installed (or the configured mob doesn't exist), the integration silently disables itself and the rest of the plugin runs normally.

---

## Requirements

- **MythicMobs** installed on the server (listed as a `softdepend` in `plugin.yml`).
- A MythicMobs mob definition whose internal name matches `mythicmobs.integration.mob-type`.

On startup the plugin verifies the mob type and logs whether the hook succeeded:

```
[MythicMobs] Hooked in. Mob type 'ExampleMutantBoss' verified.
```

If the mob name can't be found, you'll get a warning and Mutant spawning won't occur until it's fixed.

---

## How Mutants Spawn

### During a Blood Moon

When a Blood Moon **starts** (natural or forced):

1. **One guaranteed Mutant** spawns near a random eligible player, with a server-wide broadcast.
2. A **periodic spawn loop** begins. Every `spawn-tick-interval` ticks (default **100** = 5 s), for each online player in an enabled, non-lobby world, the plugin rolls `spawn-chance` (default **0.05** = 5%) to spawn a Mutant near them — until the global cap is hit.

When the Blood Moon **ends**, the spawn loop stops. Mutants already alive remain until killed.

### Via Command

`/xa spawn mutant [count] [radius]` spawns Mutants on demand (respecting the global cap). See [Commands](Commands-and-Permissions.md).

---

## Spawn Placement

Mutants are placed using smart positioning:

- A spawn point is chosen at a random angle, between `spawn-radius.min` and `spawn-radius.max` blocks from the anchor player (default **20–40**).
- The system **prefers spots outside the player's line of sight**, so the boss "emerges" rather than popping in visibly (it falls back to a visible spot after several failed attempts).
- Spawns are snapped to the surface and **skip oceans and lava lakes** and **GriefPrevention claims**.

---

## The Global Cap

`max-global-cap` (default **15**) limits how many Mutants can be alive **across the entire server** at once. Set it to `0` to disable the cap (not recommended).

The cap is maintained carefully:

- A Mutant's slot is freed the instant it dies (via the death listener), so a boss whose chunk unloads after death can't permanently "leak" a slot.
- Unloaded-but-alive Mutants keep their slot (they aren't mistaken for dead), so far-away players can't bypass the cap.

---

## Configuration

```yaml
mythicmobs:
  integration:
    # Internal MythicMobs mob name (case-sensitive, must match your .yml mob file).
    # ExampleMutantBoss is only a placeholder — point this at any mob you define in MythicMobs.
    mob-type: "ExampleMutantBoss"

    # Max alive server-wide at once. 0 disables the cap (not recommended).
    max-global-cap: 15

    # Chance (0.0–1.0) to spawn per player per tick-interval. 0.05 = 5%.
    spawn-chance: 0.05

    # Ticks between chance rolls (20 ticks = 1 second). 100 = every 5 s per player.
    spawn-tick-interval: 100

    # Distance from a player where the mob tries to spawn.
    spawn-radius:
      min: 20
      max: 40
```

> **Tip:** the Mutant is whatever you define it to be in MythicMobs. Point `mob-type` at any mob in your MythicMobs config to use a different boss — its stats, skills, model, and drops are all controlled by MythicMobs, not xApocalypse.

<p align="center"><i>← Back to <a href="Home.md">Wiki Home</a></i></p>
