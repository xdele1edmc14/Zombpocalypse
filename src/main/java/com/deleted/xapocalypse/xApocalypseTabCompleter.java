package com.deleted.xapocalypse;

import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.stream.Collectors;

/**
 * Tab completion for the single root command ({@code /xa}, {@code /xapocalypse}, {@code /zombie}).
 * Suggestions are permission-aware so players only ever see sub-commands they can actually run.
 */
public class xApocalypseTabCompleter implements TabCompleter {

    @Override
    public List<String> onTabComplete(CommandSender sender, Command command, String alias, String[] args) {
        // First token: the sub-command itself.
        if (args.length == 1) {
            List<String> subs = new ArrayList<>();
            subs.add("help");
            if (sender.hasPermission("xapocalypse.command.spawn")) {
                subs.add("spawn");
            }
            if (sender.hasPermission("xapocalypse.admin")) {
                subs.add("reload");
                subs.add("item");
                subs.add("forcebloodmoon");
                subs.add("stopbloodmoon");
            }
            return filter(subs, args[0]);
        }

        String sub = args[0].toLowerCase();

        // /xa spawn <type|horde> [count] [radius]
        if (sub.equals("spawn") && sender.hasPermission("xapocalypse.command.spawn")) {
            if (args.length == 2) {
                List<String> types = new ArrayList<>();
                types.add("horde");
                types.add("MUTANT"); // MythicMobs boss type
                for (xApocalypseUtils.ZombieType type : xApocalypseUtils.ZombieType.values()) {
                    types.add(type.name());
                }
                return filter(types, args[1]);
            } else if (args.length == 3) {
                return filter(Arrays.asList("1", "5", "10", "20", "50"), args[2]);
            } else if (args.length == 4) {
                return filter(Arrays.asList("5", "10", "15", "20", "30"), args[3]);
            }
        }

        // /xa item <item> [player]
        if (sub.equals("item") && sender.hasPermission("xapocalypse.admin")) {
            if (args.length == 2) {
                return filter(Collections.singletonList("zombie_guts"), args[1]);
            } else if (args.length == 3) {
                return null; // null -> Bukkit falls back to online player-name completion
            }
        }

        // /xa forcebloodmoon|fbm [minutes]
        if ((sub.equals("forcebloodmoon") || sub.equals("fbm")) && sender.hasPermission("xapocalypse.admin")) {
            if (args.length == 2) {
                return filter(Arrays.asList("5", "10", "15", "30", "60"), args[1]);
            }
        }

        return Collections.emptyList();
    }

    /** Case-insensitive prefix filter on the player's current partial input. */
    private List<String> filter(List<String> options, String input) {
        String low = input.toLowerCase();
        return options.stream()
                .filter(s -> s.toLowerCase().startsWith(low))
                .collect(Collectors.toList());
    }
}
