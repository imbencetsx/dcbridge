package net.msstore.discordbridge;

import org.bukkit.configuration.file.FileConfiguration;

import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Wraps the plugin's config.yml, keeping an in-memory copy of the
 * whitelisted Discord user IDs so lookups during message handling are fast.
 */
public class ConfigManager {

    private final DiscordBridgePlugin plugin;
    private final Set<String> whitelistedUsers = ConcurrentHashMap.newKeySet();

    private String token;
    private String logChannelId;
    private int flushIntervalSeconds;
    private int maxQueuedLogLines;

    private boolean chatBridgeEnabled;
    private String chatChannelId;

    public ConfigManager(DiscordBridgePlugin plugin) {
        this.plugin = plugin;
    }

    /** Loads (or reloads) all values from config.yml into memory. */
    public void load() {
        plugin.reloadConfig();
        FileConfiguration cfg = plugin.getConfig();

        token = cfg.getString("discord.token", "");
        logChannelId = cfg.getString("discord.log-channel-id", "");
        flushIntervalSeconds = cfg.getInt("log-flush-interval-seconds", 3);
        maxQueuedLogLines = cfg.getInt("max-queued-log-lines", 150);

        chatBridgeEnabled = cfg.getBoolean("chat-bridge.enabled", false);
        chatChannelId = cfg.getString("chat-bridge.channel-id", "");

        whitelistedUsers.clear();
        List<String> ids = cfg.getStringList("whitelisted-users");
        for (String id : ids) {
            if (id != null && !id.isBlank() && !id.startsWith("REPLACE_WITH")) {
                whitelistedUsers.add(id.trim());
            }
        }
    }

    public String getToken() {
        return token;
    }

    public String getLogChannelId() {
        return logChannelId;
    }

    public int getFlushIntervalSeconds() {
        return Math.max(1, flushIntervalSeconds);
    }

    public int getMaxQueuedLogLines() {
        return maxQueuedLogLines > 0 ? maxQueuedLogLines : 150;
    }

    public boolean isChatBridgeEnabled() {
        return chatBridgeEnabled
                && chatChannelId != null
                && !chatChannelId.isBlank()
                && !chatChannelId.startsWith("REPLACE_WITH");
    }

    public String getChatChannelId() {
        return chatChannelId;
    }

    public boolean isWhitelisted(String discordUserId) {
        return whitelistedUsers.contains(discordUserId);
    }

    /**
     * Adds a Discord user ID to the whitelist, persists config.yml, and
     * updates the in-memory set. Returns false if the ID was already whitelisted.
     */
    public boolean whitelist(String discordUserId) {
        String id = discordUserId.trim();
        if (!id.chars().allMatch(Character::isDigit)) {
            throw new IllegalArgumentException("Discord user IDs are numeric (snowflake) values.");
        }
        if (whitelistedUsers.contains(id)) {
            return false;
        }
        whitelistedUsers.add(id);
        persist();
        return true;
    }

    /**
     * Removes a Discord user ID from the whitelist and persists config.yml.
     * Returns false if the ID was not present.
     */
    public boolean unwhitelist(String discordUserId) {
        String id = discordUserId.trim();
        boolean removed = whitelistedUsers.remove(id);
        if (removed) {
            persist();
        }
        return removed;
    }

    private void persist() {
        FileConfiguration cfg = plugin.getConfig();
        cfg.set("whitelisted-users", whitelistedUsers.stream().sorted().toList());
        plugin.saveConfig();
    }
}
