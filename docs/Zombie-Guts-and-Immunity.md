# 🫀 Zombie Guts & Immunity

**Zombie Guts** is the player's one escape valve from the horde — a consumable that makes zombies **completely ignore you** for 10 minutes. But it comes at a steep cost: while immune, your maximum health is halved. It's a desperate-survival tool, not a free pass.

---

## The Item

Zombie Guts is a renamed **Rotten Flesh** carrying a hidden data-tag so it's recognized reliably even if you rename or restyle it. By default it looks like:

```
Zombie Guts
  Consume to gain temporary immunity
  Reduces Max Health to 5 Hearts for 10 minutes
```

Both the name and lore are configurable in `messages.yml` under `immunity:`.

### Getting it

| Source | Details |
|--------|---------|
| **Command** | `/xa item zombie_guts [player]` (requires `xapocalypse.admin`) |
| **Rare drop** | A slain zombie can drop it — see [The Rare Drop](#the-rare-drop) |

The whole system requires the master switch `zombie-settings.zombie-guts.enabled: true`.

---

## Using It

**Right-click** while holding Zombie Guts to activate it. (You can also actually eat it in Survival — both paths work. Right-click is the primary path because it works regardless of hunger level or game mode, which is why eating rotten flesh on a full hunger bar still triggers it.)

On activation:

1. ✅ You gain **immunity** — zombies stop targeting you, and any zombie currently chasing you immediately loses its target.
2. 💔 Your **maximum health drops to 5 hearts** (10 HP) for the duration.
3. ⏳ A **green boss bar** counts down your remaining immunity (`10:00`).
4. One Zombie Guts is consumed from your hand.

When the timer expires:

- Your **original maximum health is restored** (and a message confirms it).
- Nearby zombies that have no current target will start hunting you again.

You **cannot stack** immunity — using Zombie Guts again while already immune just tells you to wait for the current effect to wear off.

---

## Why the health trade-off matters

Reducing your max health to 5 hearts means that **while you're "safe" from zombies, almost anything else can kill you fast** — fall damage, lava, other players, fire, drowning. Immunity buys you breathing room from the horde, but turns every other hazard deadly. Use it to escape or regroup, not to go adventuring.

---

## The Rare Drop

When enabled, slain zombies have a small chance to drop Zombie Guts:

```yaml
zombie-settings:
  zombie-guts:
    enabled: true                # master switch
    drop:
      enabled: true              # toggle the rare drop
      chance: 0.02               # 0.0–1.0 → 2% per eligible kill
      require-player-kill: true  # only drop on a player's killing blow (anti-farm)
```

- `chance` is rolled per eligible zombie death. `0.02` = **2%**.
- With `require-player-kill: true`, only zombies **killed by a player** can drop guts — preventing players from farming guts with mob grinders, lava traps, or fall damage.

---

## Persistence & Reliability

Immunity state is saved to **`data.yml`** using an **absolute real-world timestamp**, so:

- An immunity **survives server restarts** — if you log back in with time left, it resumes (boss bar and all); if it expired while you were offline, your health is restored on join.
- The timer is driven by the **real-world clock**, not in-game time, so it isn't disturbed by the Blood Moon system manipulating world time.

> You should never need to edit `data.yml` by hand.

---

## Configuration Summary

```yaml
zombie-settings:
  zombie-guts:
    enabled: true
    drop:
      enabled: true
      chance: 0.02
      require-player-kill: true
```

Messages and the item's name/lore live in `messages.yml` under `immunity:`. See [Localization](Localization.md).

<p align="center"><i>← Back to <a href="Home.md">Wiki Home</a></i></p>
