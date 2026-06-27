# 🌍 Worlds & Integrations

This page covers how xApocalypse decides **where** it operates, and how it cooperates with **GriefPrevention**.

---

## Enabled Worlds

```yaml
enabled-worlds:
  - world
  - world_nether
  - world_the_end
```

`enabled-worlds` is the list of worlds where the apocalypse is **fully active** — horde spawning, custom-zombie AI, Blood Moons, and natural-mob filtering all apply here. Worlds not in this list are ignored entirely by the spawner and AI systems.

> Throughout the plugin, "the main world" is resolved as the **first enabled world that is loaded**, not simply the server's primary world. This matters on setups using Multiverse or BetterRTP, where the index-0 world is often a lobby or staging world. Blood Moon timing, force/stop commands, and the MythicMobs loop all key off the first enabled world.

---

## Lobby Worlds

```yaml
lobby-worlds:
  - lobby
```

Lobby worlds are a special case: **zombie spawning and MythicMobs boss spawning are suppressed**, but the rest of the plugin still works for players there:

- The **Blood Moon boss bar** still shows (so lobby players know an event is happening).
- **Immunity**, **scent tracking**, and other systems still function.

A lobby world does **not** need to be in `enabled-worlds`. Use this for hub/spawn worlds where you want players to feel the atmosphere (boss bars, broadcasts) without being attacked.

| | `enabled-worlds` | `lobby-worlds` | neither |
|---|:---:|:---:|:---:|
| Horde spawning | ✅ | ❌ | ❌ |
| Mutant boss spawning | ✅ | ❌ | ❌ |
| Custom zombie AI | ✅ | — | ❌ |
| Blood Moon boss bar | ✅ | ✅ | ❌ |
| Natural-mob filtering | ✅ | ❌ | ❌ |

---

## GriefPrevention

If [GriefPrevention](https://www.spigotmc.org/resources/griefprevention.1884/) is installed and the hook is enabled, xApocalypse respects player **claims**:

```yaml
hooks:
  griefprevention:
    enabled: true
    prevent-spawning-in-claims: true   # zombies won't spawn inside claims
```

When enabled:

- **Natural horde zombies** won't spawn inside protected claims.
- **Miner** zombies won't break blocks inside claims.
- **MythicMobs Mutants** won't spawn inside claims.

This lets players build safe havens that the apocalypse respects.

> ⚠️ **Admin `/xa spawn` is exempt.** The manual spawn command intentionally **ignores** claim protection so admins can always spawn zombies anywhere (including at a claimed hub) for testing or events. Only *natural/automatic* spawning honors claims.

The hook is a **soft dependency** — without GriefPrevention installed, claim checks simply always return "not in a claim" and everything else works normally.

---

## MythicMobs

The Mutant boss integration is documented separately — see **[MythicMobs Integration](MythicMobs-Integration.md)**. Like GriefPrevention, it's a soft dependency and stays dormant if MythicMobs isn't present.

<p align="center"><i>← Back to <a href="Home.md">Wiki Home</a></i></p>
