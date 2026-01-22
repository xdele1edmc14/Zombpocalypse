package com.deleted.zombpocalypse;

import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.stream.Collectors;

public class ZombpocalypseTabCompleter implements TabCompleter {

    @Override
    public List<String> onTabComplete(CommandSender sender, Command command, String alias, String[] args) {
        List<String> completions = new ArrayList<>();

        if (command.getName().equalsIgnoreCase("zspawn")) {
            if (args.length == 1) {
                // First argument: zombie type or "horde"
                List<String> types = new ArrayList<>();
                types.add("horde");
                for (ZombpocalypseUtils.ZombieType type : ZombpocalypseUtils.ZombieType.values()) {
                    types.add(type.name());
                }

                // Filter based on what player has typed
                String input = args[0].toLowerCase();
                completions = types.stream()
                        .filter(s -> s.toLowerCase().startsWith(input))
                        .collect(Collectors.toList());

            } else if (args.length == 2) {
                // Second argument: count (suggest some numbers)
                completions = Arrays.asList("1", "5", "10", "20", "50");

            } else if (args.length == 3) {
                // Third argument: radius (suggest some numbers)
                completions = Arrays.asList("5", "10", "15", "20", "30");
            }
        } else if (command.getName().equalsIgnoreCase("zitem")) {
            if (args.length == 1) {
                // Suggest available items
                completions = Arrays.asList("zombie_guts");
            }
        }

        return completions;
    }
}