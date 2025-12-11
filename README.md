<h1 align="center">☠️ Zombpocalypse Plugin ☣️</h1>

<p align="center">
  <img src="https://i.imgur.com/M0z9eVF.png" width="600" alt="Zombpocalypse Banner"/>
</p>

<p align="center">
  <img src="https://img.shields.io/badge/Java-21-orange?style=for-the-badge&logo=openjdk"/>
  <img src="https://img.shields.io/badge/Spigot-1.18%2B-red?style=for-the-badge&logo=spigotmc"/>
  <img src="https://img.shields.io/badge/License-GPLv3-yellow?style=for-the-badge"/>
  <img src="https://img.shields.io/badge/Version-1.2-brightgreen?style=for-the-badge"/>
</p>

<p align="center">
  <i>A brutal, customizable zombie apocalypse plugin for survival servers 🌑🧟</i><br>
  <b>Optimized. Configurable. Terrifying.</b>
</p>

---

<p align="center">
  <img src="https://img.shields.io/badge/Java-21-orange?style=for-the-badge&logo=openjdk"/>
  <img src="https://img.shields.io/badge/Spigot-1.18%2B-red?style=for-the-badge&logo=spigotmc"/>
  <img src="https://img.shields.io/badge/License-GPLv3-yellow?style=for-the-badge"/>
  <img src="https://img.shields.io/badge/Version-1.2-brightgreen?style=for-the-badge"/>
</p>

<p align="center">
  <i>A hardcore, fully customizable zombie apocalypse experience for Spigot & Paper servers.</i>
</p>

--------------- | :----------------------------- | :------------- |
| **Version**      | 1.2                            | `plugin.yml`   |
| **Server API**   | Spigot 1.18+ Compatible        | `plugin.yml`   |
| **Build Target** | Java 21                        | `pom.xml`      |
| **License**      | **GNU GPL v3.0** (Open Source) | `LICENSE` file |
| **Author**       | xDele1ed                       | `plugin.yml`   |

---

## ⚙️ Core Features

* **Dynamic Horde Spawning:**

  * Hordes spawn every **60 seconds** (1200 ticks).
  * Base horde size: **5 zombies**.

* **Enhanced Combat:**

  * **30.0 Health** per zombie.
  * **8.0 Damage**.
  * **0.35 Speed**.

* **Customizable Spawning:**

  * **25% chance** to spawn during daytime.
  * Natural mob **blacklist/whitelist** support.
  * Default blacklist: `SKELETON`, `WITHER_SKELETON`, `SPIDER`.

* **World Control:** Active only in configured worlds: `world`, `world_nether`, `world_the_end`.

* **Zombie Guts:** Grants temporary zombie immunity.

* **Bloodmoon:** Occurs every 10 days (customizable) with buffed zombie attributes and spawn rates.

* **GriefPrevention Support:** Zombies do **not** spawn inside GP claims.

---

## 🛠️ Configuration (`config.yml` Overview)

Below is a cleaned, readable version of the configuration with explanations.

```yaml
# Enables detailed console logs
debug-mode: false

# Worlds where the plugin is active
enabled-worlds:
  - world
  - world_nether
  - world_the_end

# External plugin integration
hooks:
  griefprevention:
    enabled: true
    prevent-spawning-in-claims: true

# Zombie attribute settings
zombie-settings:
  health: 30.0
  damage: 8.0
  speed: 0.35

  allow-baby-zombies: true
  allow-zombie-villagers: true

  zombie-guts:
    enabled: true

# Apocalypse and horde spawning behavior
apocalypse-settings:
  spawn-rate: 100            # Ticks between spawn attempts
  spawn-radius: 40           # Radius around players
  base-horde-size: 10
  horde-variance: 10         # +- variance
  day-spawn-chance: 1.0      # 1.0 = 100%
  ignore-light-level: false
  use-mob-blacklist: true
  mob-list:
    - SKELETON
    - WITHER_SKELETON
    - SPIDER

# Blood Moon event configuration
bloodmoon:
  enabled: true
  interval-days: 10          # Every X in-game days
  bossbar-title: "§4§l☠ BLOOD MOON ☠ §cRemaining: %time%"

  multipliers:
    health: 2.0              # Doubles zombie HP
    damage: 1.5              # 8 -> 12
    speed: 1.2
    horde-size: 2.0          # Doubles horde size
```

---

## 📸 Screenshots & Showcase

<details>
  <summary>Click to view screenshots of Zombpocalypse in action!</summary>
  <br/>

### Daytime

<img src="https://i.imgur.com/ImSWNd1.png" alt="Daytime Scene" width="800"/>

### Nighttime

<img src="https://i.imgur.com/HuBR6TI.png" alt="Nighttime Scene" width="800"/>

### Bloodmoon

<img src="https://i.imgur.com/PW2TPAS.png" alt="Bloodmoon Scene" width="800"/>

*Note: Images may not reflect the latest version.*

</details>
