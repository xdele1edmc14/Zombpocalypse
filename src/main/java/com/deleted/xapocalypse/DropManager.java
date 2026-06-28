package com.deleted.xapocalypse;

import org.bukkit.Material;
import org.bukkit.entity.Zombie;
import org.bukkit.event.entity.EntityDeathEvent;
import org.bukkit.inventory.ItemStack;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ThreadLocalRandom;

/**
 * Owns the configurable custom death-drop system: extra items a slain zombie can drop on top of the
 * vanilla rotten flesh and the Zombie Guts rare drop (which lives in {@link ImmunityManager}).
 *
 * Two independent drop tables are read from {@code zombie-settings.custom-drops}: {@code normal}
 * (a regular kill) and {@code bloodmoon} (a kill while a blood moon is active). The active table is
 * chosen per-death by {@link xApocalypse#isBloodMoonActive}. Config is parsed once on load/reload
 * (cheap per-death iteration), matching the manager pattern used elsewhere in the plugin.
 */
public class DropManager {

    private final xApocalypse plugin;

    private boolean enabled;
    private boolean requirePlayerKill;
    private final List<DropEntry> normalDrops = new ArrayList<>();
    private final List<DropEntry> bloodMoonDrops = new ArrayList<>();

    /** One configured drop: an item, an inclusive amount range, and a per-death roll chance (0–1). */
    private record DropEntry(Material material, int min, int max, double chance) {}

    public DropManager(xApocalypse plugin) {
        this.plugin = plugin;
        loadConfig();
    }

    /** Re-reads the custom-drops config. Called from onEnable and on /xa reload. */
    public void loadConfig() {
        enabled = plugin.getConfig().getBoolean("zombie-settings.custom-drops.enabled", true);
        requirePlayerKill = plugin.getConfig().getBoolean("zombie-settings.custom-drops.require-player-kill", true);

        normalDrops.clear();
        bloodMoonDrops.clear();
        parseTable("zombie-settings.custom-drops.normal", normalDrops);
        parseTable("zombie-settings.custom-drops.bloodmoon", bloodMoonDrops);

        plugin.debugLog("Custom drops loaded: " + normalDrops.size() + " normal, "
                + bloodMoonDrops.size() + " blood-moon entries (enabled=" + enabled + ").");
    }

    private void parseTable(String path, List<DropEntry> into) {
        List<?> raw = plugin.getConfig().getList(path);
        if (raw == null) return;

        for (Object obj : raw) {
            if (!(obj instanceof java.util.Map<?, ?> map)) continue;

            Object matObj = map.get("material");
            if (matObj == null) continue;
            Material material = Material.matchMaterial(String.valueOf(matObj).trim().toUpperCase());
            if (material == null || material.isAir()) {
                plugin.getLogger().warning("[CustomDrops] Skipping invalid material '" + matObj + "' in " + path);
                continue;
            }

            int[] range = parseAmount(map.get("amount"), path);
            double chance = parseChance(map.get("chance"));
            if (chance <= 0.0) continue;
            if (chance > 1.0) {
                plugin.getLogger().warning("[CustomDrops] chance " + chance + " in " + path
                        + " is above the documented 0.0-1.0 range; treating as guaranteed (1.0)."
                        + " For a percent, use a fraction (e.g. 50% -> 0.5).");
                chance = 1.0;
            }

            into.add(new DropEntry(material, range[0], range[1], chance));
        }
    }

    /** Accepts a single int ("3"/3) or a "min-max" range; clamps to at least 1 and min &lt;= max. */
    private int[] parseAmount(Object amountObj, String path) {
        int min = 1, max = 1;
        if (amountObj != null) {
            String s = String.valueOf(amountObj).trim();
            try {
                if (s.contains("-")) {
                    String[] parts = s.split("-", 2);
                    min = Integer.parseInt(parts[0].trim());
                    max = Integer.parseInt(parts[1].trim());
                } else {
                    min = max = Integer.parseInt(s);
                }
            } catch (NumberFormatException e) {
                plugin.getLogger().warning("[CustomDrops] Invalid amount '" + amountObj + "' in " + path
                        + "; defaulting to 1. Use a number (\"3\") or an inclusive range (\"1-2\").");
                min = max = 1;
            }
        }
        min = Math.max(1, min);
        max = Math.max(min, max);
        return new int[]{min, max};
    }

    private double parseChance(Object chanceObj) {
        if (chanceObj == null) return 0.0;
        try {
            return Double.parseDouble(String.valueOf(chanceObj).trim());
        } catch (NumberFormatException e) {
            return 0.0;
        }
    }

    /**
     * Rolls the configured custom drops for a slain zombie and appends any winners to the death drops
     * (so they flow through vanilla / other-plugin drop handling). The blood-moon table is used when a
     * blood moon is active in the zombie's world, otherwise the normal table.
     */
    public void applyDrops(EntityDeathEvent event, Zombie zombie) {
        if (!enabled) return;
        if (requirePlayerKill && zombie.getKiller() == null) return;

        boolean bloodMoon = plugin.isBloodMoonActive(zombie.getWorld());
        List<DropEntry> table = bloodMoon ? bloodMoonDrops : normalDrops;
        if (table.isEmpty()) return;

        ThreadLocalRandom rng = ThreadLocalRandom.current();
        for (DropEntry entry : table) {
            if (rng.nextDouble() >= entry.chance()) continue;
            int amount = entry.min() == entry.max()
                    ? entry.min()
                    : rng.nextInt(entry.min(), entry.max() + 1);
            event.getDrops().add(new ItemStack(entry.material(), amount));
        }
    }
}
