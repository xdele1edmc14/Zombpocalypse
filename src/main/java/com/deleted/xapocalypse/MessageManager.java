package com.deleted.xapocalypse;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.minimessage.MiniMessage;
import net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer;
import org.bukkit.ChatColor;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.plugin.java.JavaPlugin;

import java.io.File;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
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

        // Fall back to the defaults bundled in the jar. Without this, any key the admin's
        // (possibly older) messages.yml is missing renders in-game as "Missing message: <path>".
        // Registering the jar copy as the config's defaults lets every shipped key resolve to its
        // bundled value even when the on-disk file predates it — so plugin updates never require a
        // manual messages.yml delete. (This mirrors what Bukkit already does for config.yml.)
        applyBundledDefaults();

        cache.clear(); // Clear cache on reload

        plugin.getLogger().info("Loaded " + countKeys(messages) + " message keys with MiniMessage support");
    }

    /** Registers the jar's bundled messages.yml as the in-memory default source (not written to disk). */
    private void applyBundledDefaults() {
        try (InputStream defStream = plugin.getResource("messages.yml")) {
            if (defStream != null) {
                YamlConfiguration defaults = YamlConfiguration.loadConfiguration(
                        new InputStreamReader(defStream, StandardCharsets.UTF_8));
                messages.setDefaults(defaults);
            }
        } catch (Exception e) {
            plugin.getLogger().warning("Could not load bundled messages.yml defaults: " + e.getMessage());
        }
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
            // Single-arg getString consults the bundled defaults registered in applyBundledDefaults();
            // only fall back to the marker when the key is absent from BOTH the on-disk file and the jar.
            String rawMessage = messages.getString(path);
            if (rawMessage == null) {
                rawMessage = "&cMissing message: " + path;
            }

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
        String rawMessage = messages.getString(path);
        if (rawMessage == null) {
            rawMessage = "&cMissing message: " + path;
        }

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
            applyBundledDefaults();
            cache.clear();
            plugin.getLogger().info("Messages reloaded successfully with MiniMessage support");
        } catch (Exception e) {
            plugin.getLogger().severe("Failed to reload messages: " + e.getMessage());
        }
    }
}