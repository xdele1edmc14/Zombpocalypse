package com.deleted.zombpocalypse;

import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.EntityType;
import org.bukkit.entity.Player;
import org.bukkit.entity.Zombie;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;

import java.util.Arrays;
import java.util.concurrent.ThreadLocalRandom;

/**
 * The single {@link CommandExecutor} for Zombpocalypse, routing the six registered commands
 * (zreload, zitem, forcebloodmoon, stopbloodmoon, zspawn, help) to the relevant managers.
 * Extracted verbatim from the monolith's onCommand; all permissions, argument parsing and
 * bug-fixes (Bug M4 surface-snap, gate-bypass) are preserved.
 */
public class ZombpocalypseCommand implements CommandExecutor {

    private final Zombpocalypse plugin;
    private final MessageManager messageManager;
    private final BloodMoonManager bloodMoon;
    private final MythicMobsManager mythicMobsManager;
    private final UndeadSpawner undeadSpawner;
    private final ZombpocalypseUtils utils;

    public ZombpocalypseCommand(Zombpocalypse plugin) {
        this.plugin = plugin;
        this.messageManager = plugin.getMessages();
        this.bloodMoon = plugin.getBloodMoon();
        this.mythicMobsManager = plugin.getMythicMobsManager();
        this.undeadSpawner = plugin.getUndeadSpawner();
        this.utils = plugin.getUtils();
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (command.getName().equalsIgnoreCase("zreload")) {
            if (!sender.hasPermission("zombpocalypse.admin")) {
                sender.sendMessage(messageManager.get("no-permission"));
                return true;
            }

            try {
                plugin.reloadAll();
                sender.sendMessage(messageManager.getWithPrefix("reload-success"));
            } catch (Exception e) {
                sender.sendMessage(messageManager.getWithPrefix("reload-error", e.getMessage()));
            }
            return true;
        }

        if (command.getName().equalsIgnoreCase("zitem")) {
            if (!sender.hasPermission("zombpocalypse.admin")) {
                sender.sendMessage(messageManager.get("no-permission"));
                return true;
            }

            Player targetPlayer = null;

            // Check if command is run from console with player parameter
            if (!(sender instanceof Player)) {
                if (args.length >= 2) {
                    targetPlayer = Bukkit.getPlayer(args[1]);
                    if (targetPlayer == null) {
                        sender.sendMessage(messageManager.getWithPrefix("player-not-found", args[1]));
                        return true;
                    }
                } else {
                    sender.sendMessage(messageManager.getWithPrefix("commands.item.usage"));
                    return true;
                }
            } else {
                targetPlayer = (Player) sender;
            }

            if (args.length >= 1 && args[0].equalsIgnoreCase("zombie_guts") && plugin.isZombieGutsEnabled()) {
                ItemStack guts = new ItemStack(Material.ROTTEN_FLESH);
                ItemMeta meta = guts.getItemMeta();
                meta.setDisplayName(messageManager.get("immunity.item-name"));
                meta.setLore(messageManager.getList("immunity.item-lore"));
                guts.setItemMeta(meta);
                targetPlayer.getInventory().addItem(guts);

                if (sender instanceof Player) {
                    targetPlayer.sendMessage(messageManager.getWithPrefix("commands.item.received", messageManager.get("immunity.item-name")));
                } else {
                    sender.sendMessage(messageManager.getWithPrefix("commands.item.given", targetPlayer.getName(), messageManager.get("immunity.item-name")));
                }
                return true;
            }
            sender.sendMessage(messageManager.getWithPrefix("commands.item.unknown", args.length > 0 ? args[0] : "none"));
            return true;
        }

        if (command.getName().equalsIgnoreCase("forcebloodmoon")) {
            if (!sender.hasPermission("zombpocalypse.admin")) {
                sender.sendMessage(messageManager.get("no-permission"));
                return true;
            }

            if (Bukkit.getWorlds().isEmpty()) return true;
            // Bug M5 fix: use the first ENABLED world, not getWorlds().get(0), which on
            // Multiverse/BetterRTP is often a lobby/temp world. setTime() on the wrong world
            // left the actual gameplay world in daytime while the blood moon logic ran elsewhere.
            World world = Bukkit.getWorlds().stream()
                    .filter(plugin::isWorldEnabled)
                    .findFirst()
                    .orElse(null);
            if (world == null) {
                sender.sendMessage("§cNo enabled world is currently loaded.");
                return true;
            }

            // CRITICAL FIX: Parse duration argument
            int duration = bloodMoon.getDefaultForceDuration(); // Default from config
            if (args.length >= 1) {
                try {
                    duration = Integer.parseInt(args[0]);
                    if (duration < 1) {
                        sender.sendMessage("§cDuration must be at least 1 minute.");
                        return true;
                    }
                    if (duration > 120) {
                        sender.sendMessage("§cDuration cannot exceed 120 minutes.");
                        return true;
                    }
                } catch (NumberFormatException e) {
                    sender.sendMessage("§cInvalid duration. Usage: /forcebloodmoon [minutes]");
                    return true;
                }
            }

            bloodMoon.forceBloodMoon(sender, world, duration);

            return true;
        }

        if (command.getName().equalsIgnoreCase("stopbloodmoon")) {
            if (!sender.hasPermission("zombpocalypse.admin")) {
                sender.sendMessage(messageManager.get("no-permission"));
                return true;
            }

            bloodMoon.stopBloodMoon(sender);

            return true;
        }

        if (command.getName().equalsIgnoreCase("zspawn")) {
            if (!sender.hasPermission("zombpocalypse.command.zspawn")) {
                sender.sendMessage("§cNo permission."); return true;
            }

            if (!(sender instanceof Player player)) {
                sender.sendMessage("§cOnly players can use this command.");
                return true;
            }

            if (args.length < 1) {
                sender.sendMessage("§cUsage: /zspawn <type|horde> [count] [radius]");
                return true;
            }

            String typeArg = args[0].toUpperCase();
            int count = 1;
            int radius = 5;

            if (args.length >= 2) {
                try {
                    count = Integer.parseInt(args[1]);
                } catch (NumberFormatException e) {
                    sender.sendMessage("§cInvalid count number.");
                    return true;
                }
            }

            if (args.length >= 3) {
                try {
                    radius = Integer.parseInt(args[2]);
                } catch (NumberFormatException e) {
                    sender.sendMessage("§cInvalid radius number.");
                    return true;
                }
            }

            count = Math.min(count, plugin.getConfig().getInt("performance.max-total-zombies", 300)); // Use config value
            radius = Math.min(radius, 50); // Keep radius reasonable

            // --- MythicMobs: MUTANT type ---
            if (typeArg.equals("MUTANT")) {
                if (mythicMobsManager == null || !mythicMobsManager.isMythicMobsEnabled()) {
                    sender.sendMessage("§c[MythicMobs] MythicMobs is not available on this server.");
                    return true;
                }
                int spawned = mythicMobsManager.spawnMutantCommand(player, count, radius);
                sender.sendMessage("§aSpawned §c" + spawned + " §a" + mythicMobsManager.getMobType()
                        + "§a! (Active: §c" + mythicMobsManager.getActiveMutantCount()
                        + "§a/§c" + mythicMobsManager.getMaxGlobalCap() + "§a)");
                return true;
            }

            if (typeArg.equals("HORDE")) {
                // Spawn mixed horde
                int hordeSpawned = 0;
                for (int i = 0; i < count; i++) {
                    Location spawnLoc = player.getLocation().add(
                            ThreadLocalRandom.current().nextDouble(-radius, radius),
                            0,
                            ThreadLocalRandom.current().nextDouble(-radius, radius)
                    );

                    // Bug fix: do NOT skip claims here. The natural horde spawner ignores
                    // GriefPrevention claims entirely (zombies spawn inside claims normally), so
                    // gating the admin /zspawn command on claims was inconsistent — and at a
                    // claimed hub/main-world spawn it skipped every attempt ("horde of 0").
                    // Bug M4 fix: snap to a valid surface instead of spawning at the player's raw
                    // Y. On non-flat terrain the old code buried/suffocated zombies inside hills or
                    // dropped them into the void. Skip the slot if no valid surface is found.
                    Location surface = undeadSpawner.getSurfaceSpawnLocation(spawnLoc);
                    if (surface == null) continue;

                    // Bug fix: admin /zspawn must bypass the onEntitySpawn mob-list gate (same
                    // rationale as C1). Without the flag, an enabled world running a whitelist
                    // (or a blacklist that lists ZOMBIE) silently cancels the spawn — which is why
                    // /zspawn worked in non-enabled worlds (nether/end) but not the main world.
                    // Because the gate is bypassed, onEntitySpawn no longer assigns a type, so we
                    // assign it here ourselves.
                    plugin.setPluginSpawning(true);
                    Zombie zombie = (Zombie) player.getWorld().spawnEntity(surface, EntityType.ZOMBIE);
                    plugin.setPluginSpawning(false);
                    if (zombie != null) {
                        utils.assignZombieType(zombie);
                        hordeSpawned++;
                    }
                }
                sender.sendMessage("§aSpawned horde of " + hordeSpawned + " zombies!");
                return true;
            }

            // Specific type
            ZombpocalypseUtils.ZombieType type;
            try {
                type = ZombpocalypseUtils.ZombieType.valueOf(typeArg);
            } catch (IllegalArgumentException e) {
                sender.sendMessage("§cInvalid zombie type. Valid types: " + Arrays.toString(ZombpocalypseUtils.ZombieType.values()));
                return true;
            }

            for (int i = 0; i < count; i++) {
                Location spawnLoc = player.getLocation().add(
                        ThreadLocalRandom.current().nextDouble(-radius, radius),
                        0,
                        ThreadLocalRandom.current().nextDouble(-radius, radius)
                );

                // Bug fix: don't gate the admin command on GriefPrevention claims (the natural
                // spawner ignores them too — see HORDE branch above).
                // Bug fix: bypass the onEntitySpawn mob-list gate for admin spawns (see HORDE
                // branch above), then apply the requested type directly.
                plugin.setPluginSpawning(true);
                Zombie zombie = (Zombie) player.getWorld().spawnEntity(spawnLoc, EntityType.ZOMBIE);
                plugin.setPluginSpawning(false);
                if (zombie != null) utils.applyZombieType(zombie, type);
            }

            sender.sendMessage("§aSpawned " + count + " " + type.name() + " zombies!");
            return true;
        }

        if (command.getName().equalsIgnoreCase("help")) {
            sender.sendMessage(messageManager.get("commands.help.header"));
            sender.sendMessage(messageManager.get("commands.help.author"));
            sender.sendMessage(messageManager.get("commands.help.reload"));
            sender.sendMessage(messageManager.get("commands.help.item"));
            sender.sendMessage(messageManager.get("commands.help.bloodmoon"));
            sender.sendMessage(messageManager.get("commands.help.spawn"));
            return true;
        }
        return false;
    }
}
