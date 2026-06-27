# 🚦 Performance & LOD

A zombie apocalypse can spawn a *lot* of entities. xApocalypse ships with a **Performance Watchdog** and a **Level-of-Detail (LOD)** AI system to keep the siege smooth even under load.

---

## Entity Cap & Culling

The watchdog periodically (every `performance.check-interval-ticks`, default **100** ticks) counts zombies in each enabled world. If a world exceeds `performance.max-total-zombies` (default **300**), it **culls the excess**:

- Zombies are sorted by distance to the nearest player, and the **furthest are removed first**.
- Zombies **within 20 blocks** of a player are never culled — you won't see ones near you vanish.

This keeps total entity pressure bounded no matter how many players, hordes, scent multipliers, and Blood Moons are in play.

---

## TPS Watchdog

The watchdog reads server TPS (via Paper's API) each check interval:

| TPS | Behavior |
|-----|----------|
| **< 10.0** | **Critical** — all new horde spawning is **paused** |
| **≥ 15.0** | Spawning **resumes** |

While paused, the horde spawner task simply skips its work, so a struggling server stops digging itself deeper and gets a chance to recover.

> On non-Paper servers without `getTPS()`, the watchdog defaults to assuming a healthy 20 TPS and relies on the entity cap instead.

---

## LOD: Distance-Based AI Throttling

Running full AI on hundreds of zombies is expensive — most of that cost is wasted on zombies far from any player. The LOD system addresses this:

- Zombies **farther than 32 blocks** from every player have their **AI disabled** (vanilla pathfinding included), dramatically cutting CPU use.
- When a player moves back within range, AI is **re-enabled** automatically.
- In a world with **no players at all**, every zombie's AI is disabled.
- Custom-class AI (Nurse healing, Miner digging, etc.) is ticked on a throttled schedule for distant zombies and at full rate for close ones.

Zombies that are mid **rise-animation** are skipped by the LOD system so the animation isn't interrupted.

The LOD tracking map is bounded (capped at 1000 entries with automatic eviction of dead/invalid zombies), so it can't grow without limit.

---

## Configuration

```yaml
performance:
  max-total-zombies: 300        # global zombie cap per world (culls furthest over this)
  spawns-per-tick: 100          # spawn rate limiting
  tps-threshold: 18.5           # general TPS threshold knob
  check-interval-ticks: 100     # how often the watchdog runs (100 ticks = 5 s)
```

> `max-total-zombies` is the single most important knob for a busy server — lower it if you see entity-related lag, raise it if your hardware can handle denser hordes.

---

## Tuning Tips

| Symptom | Try |
|---------|-----|
| TPS drops during big fights | Lower `max-total-zombies`; lower `apocalypse-settings.max-single-horde-size` |
| Hordes feel too thin | Raise `max-total-zombies` and/or horde sizes; lower `scent-system.scent-scale` |
| Too many spawn attempts in rough terrain | Reduce `spawn-radius`; expect some "skipped" debug lines near water/ravines |
| Spawning seems to stop entirely | Check console for critical-TPS pause messages; verify the server isn't sitting below 10 TPS |

<p align="center"><i>← Back to <a href="Home.md">Wiki Home</a></i></p>
