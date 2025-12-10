# Zombpocalypse Plugin Showcase

<img src="https://cdn.discordapp.com/attachments/1255505079549562982/1448218045448458415/minecraft_title.png?ex=693a75a8&is=69392428&hm=de327af9d48529822a668ba4fb3d7e586365e00141f979ba54c8f0101e411758" alt="Zombpocalypse" width="500"/>

## A Hardcore Zombie Horde Plugin for Spigot/Paper Servers

**Zombpocalypse** redefines survival by turning the standard Minecraft night into a relentless, terrifying ordeal. This plugin introduces customizable, super-powered zombie hordes that dynamically stalk players, dramatically raising the difficulty and realism of your server's survival experience.

---

## 💻 Project Status & Information

| Status           | Value                          | Source         |
| :--------------- | :----------------------------- | :------------- |
| **Version**      | 1.0                            | `plugin.yml`   |
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

---

## 🛠️ Configuration (`config.yml` Highlights)

yaml
zombie-settings:
health: 30.0
damage: 8.0
speed: 0.35

apocalypse-settings:
spawn-rate: 1200
base-horde-size: 5
day-spawn-chance: 0.25
use-mob-blacklist: true

---

## 📸 Screenshots & Showcase

<details>
  <summary>Click to view screenshots of Zombpocalypse in action!</summary>
  <br/>

### Daytime

  <img src="https://cdn.discordapp.com/attachments/1255505079549562982/1448227342332989504/2025-12-10_14.05.27.png?ex=693a7e51&is=69392cd1&hm=a3cc8aa31293fc5cc9ef7224e57a9aa9cc3ac2e540c4095de4606a455e426da6&" alt="Daytime Scene" width="800"/>

  <br/>

### Nighttime

  <img src="https://cdn.discordapp.com/attachments/1255505079549562982/1448227342844428288/2025-12-10_14.13.29.png?ex=693a7e51&is=69392cd1&hm=87230db5571029bfc5c64b9e42f38d1ca152e75648d297dcac81c62efbce3fa4&" alt="Nighttime Scene" width="800"/>

  <br/>

*Note: Images are for demonstration and may not reflect the latest version.*

</details>
