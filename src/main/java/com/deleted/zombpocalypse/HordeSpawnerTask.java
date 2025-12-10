package com.deleted.zombpocalypse;

import org.bukkit.Bukkit;
import org.bukkit.World;
import org.bukkit.entity.Player;
import org.bukkit.scheduler.BukkitRunnable;

import java.util.concurrent.ThreadLocalRandom;

public class HordeSpawnerTask extends BukkitRunnable {

    private final Zombpocalypse plugin;

    public HordeSpawnerTask(Zombpocalypse plugin) {
        this.plugin = plugin;
    }

    @Override
    public void run() {
        try {
            plugin.getLogger().info("TASK: Running scheduled spawner check. (Tick appearing!)");

            if (Bukkit.getWorlds().isEmpty()) {
                plugin.getLogger().severe("TASK ERROR: No worlds are loaded! Skipping spawn attempt.");
                return;
            }

            World firstWorld = Bukkit.getWorlds().get(0);
            long time = firstWorld.getTime();
            boolean isDay = time > 0 && time < 13000;

            // Flag is TRUE only if it's DAYTIME AND the random chance passed.
            boolean isDayHordeSpawn = false;

            if (isDay) {
                double daySpawnChance = plugin.getConfig().getDouble("apocalypse-settings.day-spawn-chance", 0.0);
                double randomValue = ThreadLocalRandom.current().nextDouble();

                if (randomValue > daySpawnChance) {
                    plugin.getLogger().info("TASK: Skipped spawn due to daySpawnChance (" + daySpawnChance + ").");
                    return;
                }
                // If we reach here, it's a forced DAYTIME horde.
                isDayHordeSpawn = true;
            }

            for (Player player : Bukkit.getOnlinePlayers()) {
                if (!plugin.isWorldEnabled(player.getWorld())) {
                    plugin.getLogger().info("TASK: Skipping player " + player.getName() + " - World not enabled.");
                    continue;
                }
                if (player.getGameMode().toString().equals("CREATIVE") || player.getGameMode().toString().equals("SPECTATOR")) {
                    plugin.getLogger().info("TASK: Skipping player " + player.getName() + " - Game mode skip.");
                    continue;
                }
                // Pass the flag indicating if this is a forced daytime horde spawn
                plugin.spawnZombiesNearPlayer(player, isDayHordeSpawn);
            }

        } catch (Throwable t) {
            plugin.getLogger().severe("FATAL TASK ERROR: The repeating spawn task crashed! Check stack trace below.");
            t.printStackTrace();
        }
    }
}