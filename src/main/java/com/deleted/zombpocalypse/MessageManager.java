package com.deleted.zombpocalypse;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.minimessage.MiniMessage;
import net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer;
import org.bukkit.ChatColor;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.plugin.java.JavaPlugin;

import java.io.File;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * Lazy-loaded message manager with MiniMessage + Legacy color code support
 * Zero GC overhead with aggressive caching
 */
public class MessageManager {

    private final JavaPlugin plugin;
    private File messagesFile;
    private FileConfiguration messages;

    // MiniMessage parser (supports <gradient>, <rainbow>, etc.)
    private final MiniMessage miniMessage = MiniMessage.miniMessage();

    // Legacy serializer (converts Component back to legacy format)
    private final LegacyComponentSerializer legacySerializer = LegacyComponentSerializer.legacySection();

    // Cache for frequently accessed messages (prevents YAML lookups + parsing)
    private final Map<String, String> cache = new HashMap<>(64);

    public MessageManager(JavaPlugin plugin) {
        this.plugin = plugin;
        loadMessages();
    }

    public void loadMessages() {
        messagesFile = new File(plugin.getDataFolder(), "messages.yml");

        // Create default messages.yml if it doesn't exist
        if (!messagesFile.exists()) {
            plugin.saveResource("messages.yml", false);
        }

        messages = YamlConfiguration.loadConfiguration(messagesFile);
        cache.clear(); // Clear cache on reload

        plugin.getLogger().info("Loaded " + countKeys(messages) + " message keys with MiniMessage support");
    }

    private int countKeys(FileConfiguration config) {
        return config.getKeys(true).size();
    }

    /**
     * Get message with placeholder replacement
     * Supports both MiniMessage (<gradient>, <rainbow>) and Legacy (&, §)
     * Supports {0}, {1}, etc. for arguments
     */
    public String get(String path, Object... args) {
        String message = cache.get(path);

        if (message == null) {
            String rawMessage = messages.getString(path, "&cMissing message: " + path);

            // Parse the message (auto-detects MiniMessage vs Legacy)
            message = parseMessage(rawMessage);

            // Cache for next access
            cache.put(path, message);
        }

        // Replace placeholders
        if (args.length > 0) {
            for (int i = 0; i < args.length; i++) {
                message = message.replace("{" + i + "}", String.valueOf(args[i]));
            }
        }

        return message;
    }

    /**
     * Parse message - auto-detects format and converts to legacy string
     */
    private String parseMessage(String raw) {
        // Check if message uses MiniMessage syntax
        if (raw.contains("<") && (raw.contains("gradient") || raw.contains("rainbow") ||
                raw.contains("hover") || raw.contains("click") || raw.contains("#"))) {

            try {
                // Parse as MiniMessage and convert to legacy format
                Component component = miniMessage.deserialize(raw);
                return legacySerializer.serialize(component);
            } catch (Exception e) {
                // If parsing fails, fall back to legacy
                plugin.getLogger().warning("Failed to parse MiniMessage: " + raw);
                return ChatColor.translateAlternateColorCodes('&', raw);
            }
        } else {
            // Parse as legacy color codes
            return ChatColor.translateAlternateColorCodes('&', raw);
        }
    }

    /**
     * Get message with prefix
     */
    public String getWithPrefix(String path, Object... args) {
        return get("prefix") + " " + get(path, args);
    }

    /**
     * Get list of strings (for lore, multi-line messages)
     */
    public List<String> getList(String path) {
        List<String> list = messages.getStringList(path);
        return list.stream()
                .map(this::parseMessage)
                .collect(Collectors.toList());
    }

    /**
     * Get raw Component for advanced usage (titles, action bars, etc.)
     */
    public Component getComponent(String path, Object... args) {
        String rawMessage = messages.getString(path, "&cMissing message: " + path);

        // Replace placeholders first
        if (args.length > 0) {
            for (int i = 0; i < args.length; i++) {
                rawMessage = rawMessage.replace("{" + i + "}", String.valueOf(args[i]));
            }
        }

        // Parse as MiniMessage if it uses MiniMessage syntax
        if (rawMessage.contains("<") && (rawMessage.contains("gradient") || rawMessage.contains("rainbow") ||
                rawMessage.contains("hover") || rawMessage.contains("click") || rawMessage.contains("#"))) {
            return miniMessage.deserialize(rawMessage);
        } else {
            // Convert legacy to Component
            String legacy = ChatColor.translateAlternateColorCodes('&', rawMessage);
            return legacySerializer.deserialize(legacy);
        }
    }

    /**
     * Reload messages.yml without restarting
     */
    public void reload() {
        try {
            messages = YamlConfiguration.loadConfiguration(messagesFile);
            cache.clear();
            plugin.getLogger().info("Messages reloaded successfully with MiniMessage support");
        } catch (Exception e) {
            plugin.getLogger().severe("Failed to reload messages: " + e.getMessage());
        }
    }
}