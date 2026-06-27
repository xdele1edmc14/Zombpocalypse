# 🎮 Commands & Permissions

xApocalypse exposes **one root command** with everything dispatched as sub-commands. The root command is `/xapocalypse`, with two interchangeable aliases:

```
/xapocalypse   ≡   /xa   ≡   /zombie
```

Running `/xa` with no arguments (or `/xa help`) opens a **permission-aware** help screen — players only see the sub-commands they're actually allowed to run.

---

## Sub-Command Reference

| Sub-command | Aliases | Arguments | Permission | Description |
|-------------|---------|-----------|------------|-------------|
| `help` | — | — | *(none)* | Show the command list |
| `spawn` | — | `<type\|horde\|mutant> [count] [radius]` | `xapocalypse.command.spawn` | Spawn zombies, a mixed horde, or the Mutant boss |
| `item` | — | `<item> [player]` | `xapocalypse.admin` | Give a special item (currently `zombie_guts`) |
| `forcebloodmoon` | `fbm` | `[minutes]` | `xapocalypse.admin` | Force a Blood Moon to begin |
| `stopbloodmoon` | `sbm` | — | `xapocalypse.admin` | End the active Blood Moon |
| `reload` | — | — | `xapocalypse.admin` | Reload `config.yml` and `messages.yml` |

---

## `/xa spawn <type|horde|mutant> [count] [radius]`

Spawns zombies around **you** (must be run by a player). Tab-completion suggests every valid value.

- **`<type>`** — a specific [zombie class](Zombie-Classes.md): `SWARMER`, `MINER`, `NURSE`, `PSYCHOPATH`, `SCORCHED`, `TANK`, `RUNNER`, `SPITTER`, `BUILDER`, `VETERAN`, `WEBBER`, `BURSTER`, `FROST`, or `NORMAL`.
- **`horde`** — spawns a *mixed* horde, each zombie randomly rolled from the configured spawn weights.
- **`mutant`** — spawns the [MythicMobs Mutant](MythicMobs-Integration.md) boss (only if MythicMobs is installed and the mob type is valid).
- **`[count]`** — how many to spawn (default `1`). Clamped to `performance.max-total-zombies`.
- **`[radius]`** — how far around you they appear, in blocks (default `5`). Clamped to `50`.

**Examples:**
```
/xa spawn horde 15          → 15 mixed zombies within 5 blocks
/xa spawn TANK 3 10         → 3 Tank zombies within 10 blocks
/xa spawn mutant 1 30       → 1 Mutant boss ~30 blocks away
```

> **Admin spawns bypass the gates.** Unlike natural spawns, `/xa spawn` ignores GriefPrevention claims and the mob blacklist/whitelist, and snaps each zombie to a valid surface so they don't suffocate inside terrain. This is intentional so the command always works, even at a claimed hub spawn.

---

## `/xa item <item> [player]`

Gives a special item. Currently the only item is **`zombie_guts`** (requires `zombie-settings.zombie-guts.enabled: true`).

- Run by a **player** with no target → you receive the item.
- Run from **console** → a target player name is required: `/xa item zombie_guts Steve`.

See [Zombie Guts & Immunity](Zombie-Guts-and-Immunity.md) for what the item does.

---

## `/xa forcebloodmoon [minutes]` *(alias `/xa fbm`)*

Immediately starts a [Blood Moon](Blood-Moon.md).

- **`[minutes]`** — duration (default comes from `bloodmoon.force-duration-minutes`, normally `10`).
- Valid range: **1–120** minutes. Out-of-range or non-numeric input is rejected.
- If the world is currently in daytime, the time is set to night (`13000`) so the event begins visibly.

```
/xa fbm        → force a Blood Moon for the default duration
/xa fbm 30     → force a 30-minute Blood Moon
```

## `/xa stopbloodmoon` *(alias `/xa sbm`)*

Ends the active Blood Moon (whether natural or forced), clears the boss bar, resets persistence, and sets the world to daytime (`1000`) so it doesn't immediately re-trigger. If no Blood Moon is active, it tells you so.

---

## `/xa reload`

Reloads `config.yml` and `messages.yml` and restarts all timed tasks (spawner, scent, blood moon, immunity, performance watchdog, MythicMobs loop) without a server restart. Newly edited values take effect immediately.

> If a Blood Moon is active across a reload, the MythicMobs spawn loop is correctly resumed (no duplicate guaranteed Mutant).

---

## Permission Nodes

| Node | Default | Grants |
|------|---------|--------|
| `xapocalypse.admin` | `op` | `reload`, `item`, `forcebloodmoon`/`fbm`, `stopbloodmoon`/`sbm` |
| `xapocalypse.command.spawn` | `op` | `spawn` |

Both default to **op**, so by default only operators can run any sub-command except `help`. Grant these nodes through your permissions plugin (LuckPerms, etc.) to give specific ranks access.

> **Note:** the help screen and tab-completion are both permission-aware, so a player without a node won't even see the corresponding sub-command.

<p align="center"><i>← Back to <a href="Home.md">Wiki Home</a></i></p>
