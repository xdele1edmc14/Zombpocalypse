# Zombpocalypse Plugin Showcase

<img src="https://cdn.discordapp.com/attachments/1255505079549562982/1448218045448458415/minecraft_title.png?ex=693a75a8&is=69392428&hm=de327af9d48529822a668ba4fb3d7e586365e00141f979ba54c8f0101e411758" alt="Zombpocalypse" width="500"/>

## A Hardcore Zombie Horde Plugin for Spigot/Paper Servers

**Zombpocalypse** redefines survival by turning the standard Minecraft night into a relentless, terrifying ordeal. This plugin introduces customizable, super-powered zombie hordes that dynamically stalk players, dramatically raising the difficulty and realism of your server's survival experience.

---

## 💻 Project Status & Information

| Status           | Value                          | Source         |
| :--------------- | :----------------------------- | :------------- |
| **Version**      | 1.2                            | `plugin.yml`   |
| **Server API**   | Spigot 1.18+ Compatible        | `plugin.yml`   |
| **Build Target** | Java 21                        | `pom.xml`      |
| **License**      | **GNU GPL v3.0** (Open Source) | `LICENSE` file |
| **Author**       | xDele1ed                       | `plugin.yml`   |

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
  * Currently, **SKELETON**, **WITHER_SKELETON**, and **SPIDER** are Blacklisted.
* **World Control:** Plugin activity is limited to configured worlds, currently `world`, `world_nether`, and `world_the_end`.
* **Zombie Guts:** Consume Zombie guts to gain a temporary immunity from zombies.
* **Bloodmoon:** Every 10 days (Customizable), A Server-wide BloodMoon Occurs. During This Time Period, Zombies Will Get Buffed Health, Buffed Damage, And their spawn rates wiill increase  (All Customizeable)
* **Griefprevention Support:** The Plugin has complete greifprevention support, So that zombies cannot spawn inside player's bases.

---

## 🛠️ Configuration (`config.yml` Highlights)

yaml
# Toggles console debug messages (spammy logs)
debug-mode: false

enabled-worlds:
  - world
  - world_nether
  - world_the_end

# NEW: External Plugin Hooks
hooks:
  griefprevention:
    enabled: true
    prevent-spawning-in-claims: true

zombie-settings:
  health: 30.0
  damage: 8.0
  speed: 0.35

  allow-baby-zombies: true
  allow-zombie-villagers: true

  zombie-guts:
    enabled: true

apocalypse-settings:
  spawn-rate: 100
  spawn-radius: 40
  base-horde-size: 10
  horde-variance: 10
  day-spawn-chance: 1.0
  ignore-light-level: false
  use-mob-blacklist: true
  mob-list:
    - SKELETON
    - WITHER_SKELETON
    - SPIDER

# NEW: Blood Moon Feature
bloodmoon:
  enabled: true
  interval-days: 10 # Happens every X in-game days
  bossbar-title: "§4§l☠ BLOOD MOON ☠ §cRemaining: %time%"

  # Multipliers applied to zombies during the event
  multipliers:
    health: 2.0  # 30 * 2.0 = 60 HP
    damage: 1.5  # 8 * 1.5 = 12 Dmg
    speed: 1.2   # Speed buff
    horde-size: 2.0 # 10 zombies becomes 20

---

## 📸 Screenshots & Showcase

<details>
  <summary>Click to view screenshots of Zombpocalypse in action!</summary>
  <br/>

### Daytime

  <img src="https://i.imgur.com/ImSWNd1.png" alt="Daytime Scene" width="800"/>

  <br/>

### Nighttime

  <img src="https://i.imgur.com/HuBR6TI.png" alt="Nighttime Scene" width="800"/>

  <br/>

### Bloodmoon

  <img src="https://i.imgur.com/PW2TPAS.png" alt="Bloodmoon Scene" width="800"/>

  <br/> 

*Note: Images are for demonstration and may not reflect the latest version.*

</details>
