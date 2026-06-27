# 👃 Scent System

The Scent System makes the apocalypse **react to how you play**. The more active and aggressive you are, the more attention you draw — and the bigger the hordes that come for you. Play quietly and the heat dies down.

Each player has a hidden **scent** value between `0` and `max-scent` (default **100**). It rises with activity and decays over time, and it directly scales the size of the hordes spawned near you.

---

## How Scent Is Gained

| Action | Config key | Default | Notes |
|--------|------------|--------:|-------|
| **Sprinting** | `scent-system.sprint-add` | `1.5` | Added **every second** *while* sprinting (continuous, not just on toggle) |
| **Killing** | `scent-system.kill-add` | `0.8` | Added each time you kill any entity |
| **Jumping** | `scent-system.jump-add` | `0.5` | Added on each jump take-off (rate-limited to ~once per 0.5 s) |

Scent is capped at `scent-system.max-scent` (default **100**). Only activity in **enabled worlds** contributes.

---

## How Scent Decays

Every `decay-interval-seconds` (default **4 s**), each player's scent is reduced by `decay-amount` (default **1.2**). When it reaches zero, the player is dropped from tracking entirely. Standing still and avoiding combat will steadily bring your scent — and the horde sizes — back down.

---

## How Scent Affects Hordes

When a horde spawns near you, its size is scaled by a scent multiplier:

```
multiplier *= 1 + (scent / scent-scale)
```

Where `scent-scale` is `scent-system.scent-scale` (default **15.0**). A **higher** `scent-scale` means scent has **less** impact on horde size.

**Worked example** (default `scent-scale = 15`):

| Your scent | Scent multiplier |
|-----------:|-----------------:|
| `0` | `× 1.00` |
| `15` | `× 2.00` |
| `30` | `× 3.00` |
| `100` (max) | `× ~7.67` |

This multiplier stacks **on top of** the [Blood Moon](Blood-Moon.md) horde multiplier. The final horde size is then clamped by `apocalypse-settings.max-single-horde-size` and the global `performance.max-total-zombies` cap, so scent can't spiral into a server-killing swarm.

---

## Configuration

```yaml
scent-system:
  enabled: true
  max-scent: 100.0
  sprint-add: 1.5              # per second while sprinting
  kill-add: 0.8               # per kill
  decay-amount: 1.2           # removed each decay tick
  decay-interval-seconds: 4   # how often scent decays
  scent-scale: 15.0           # higher = less impact on horde size
```

> Setting `enabled: false` turns the system off entirely — horde sizes then depend only on the base size, variance, and Blood Moon multiplier.

---

## Warnings

`messages.yml` includes optional scent warning strings (`scent.high-warning`, `scent.very-high-warning`) you can use/translate. See [Localization](Localization.md).

<p align="center"><i>← Back to <a href="Home.md">Wiki Home</a></i></p>
