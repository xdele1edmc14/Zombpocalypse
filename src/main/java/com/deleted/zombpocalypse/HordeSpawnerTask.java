package com.deleted.zombpocalypse;

import org.bukkit.Bukkit;
import org.bukkit.GameMode;
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
            plugin.debugLog("TASK: Running scheduled spawner check.");

            // Minor fix: exit early if TPS is critically low (PerformanceWatchdog sets this flag)
            if (plugin.getPerformanceWatchdog() != null && plugin.getPerformanceWatchdog().isSpawningPaused()) {
                plugin.debugLog("TASK: Spawning paused due to low TPS, skipping.");
                return;
            }

            // Bug 12 fix: use the first world that is actually enabled by the plugin,
            // not Bukkit.getWorlds().get(0) which may be an untracked world (e.g. nether/end).
            World targetWorld = Bukkit.getWorlds().stream()
                    .filter(plugin::isWorldEnabled)
                    .findFirst()
                    .orElse(null);

            if (targetWorld == null) {
                plugin.debugLog("TASK: No enabled worlds loaded, skipping spawn attempt.");
                return;
            }

            long time = targetWorld.getTime();
            boolean isDay = time > 0 && time < 13000;
            boolean isDayHordeSpawn = false;

            if (isDay) {
                double daySpawnChance = plugin.getConfig().getDouble("apocalypse-settings.day-spawn-chance", 0.0);
                if (ThreadLocalRandom.current().nextDouble() > daySpawnChance) return;
                isDayHordeSpawn = true;
            }

            for (Player player : Bukkit.getOnlinePlayers()) {
                if (!plugin.isWorldEnabled(player.getWorld())) continue;
                // Minor fix: use enum constants instead of string comparison
                if (player.getGameMode() == GameMode.CREATIVE || player.getGameMode() == GameMode.SPECTATOR) continue;
                plugin.spawnZombiesNearPlayer(player, isDayHordeSpawn);
            }

        } catch (Throwable t) {
            plugin.getLogger().severe("FATAL TASK ERROR: The repeating spawn task crashed! Check stack trace below.");
            t.printStackTrace();
        }
    }
}