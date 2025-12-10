# 🧟 Zombpocalypse
### A Hardcore Zombie Horde Plugin for Spigot/Paper Servers

**Zombpocalypse** redefines survival by turning the standard Minecraft night into a relentless, terrifying ordeal. This plugin introduces customizable, super-powered zombie hordes that dynamically stalk players, dramatically raising the difficulty and realism of your server's survival experience.

---

## 💻 Project Status & Information

| Status | Value | Source |
| :--- | :--- | :--- |
| **Version** | 1.0 | |
| **Server API** | Spigot 1.18+ Compatible | |
| **Build Target** | Java 21 | |
| **License** | **GNU GPL v3.0** (Open Source) | |
| **Author** | xDele1ed | |

---

## ⚙️ Core Features

* **Dynamic Horde Spawning:** Hordes of zombies periodically spawn and hunt players.
    * Spawn attempts currently run every **60 seconds** (1200 ticks).
    * The base horde size is **5** zombies per spawn.
* **Enhanced Combat:** Zombies are significantly buffed for a true survival challenge.
    * Zombies have **30.0 Health** (15 hearts).
    * They deal **8.0 Damage** (4 hearts) per hit.
    * They have an increased base speed of **0.35**.
* **Customizable Spawning:** Control when, where, and what mobs appear.
    * Hordes have a **25% chance** to spawn even during the day.
    * Mobs can be **BLACKLISTED** or **WHITELISTED** for natural spawning.
    * Currently, **SKELETON**, **WITHER\_SKELETON**, and **SPIDER** are Blacklisted.
* **World Control:** Plugin activity is limited to configured worlds, currently `world`, `world_nether`, and `world_the_end`.

---

## 🛠️ Configuration (`config.yml` Highlights)

The plugin is designed to be highly configurable. Here are some of the critical settings found in the `config.yml`:

```yaml
zombie-settings:
  health: 30.0 # Standard zombie health (Default is 20.0)
  damage: 8.0  # Standard zombie attack damage (Default is 3.0)
  speed: 0.35  # Standard zombie movement speed (Default is 0.23)

apocalypse-settings:
  spawn-rate: 1200     # Ticks between each spawn attempt (20 ticks = 1 second)
  base-horde-size: 5   # The guaranteed number of zombies in a horde
  day-spawn-chance: 0.25 # Chance for a horde to spawn during daylight hours (0.0 - 1.0)
  use-mob-blacklist: true # True = Blacklist mode, False = Whitelist mode