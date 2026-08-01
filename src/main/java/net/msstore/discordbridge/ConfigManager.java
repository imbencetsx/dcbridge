package net.msstore.discordbridge;

import org.bukkit.configuration.file.FileConfiguration;

import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Pattern;
import java.util.regex.PatternSyntaxException;

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

    private List<String> commandAllowlist;
    private int maxCommandsPerMinute;
    private boolean redactSensitiveLines;
    private List<Pattern> redactPatterns = List.of();

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

        commandAllowlist = cfg.getStringList("discord.command-whitelist");
        commandAllowlist.removeIf(s -> s == null || s.isBlank());
        commandAllowlist.replaceAll(String::trim);

        maxCommandsPerMinute = cfg.getInt("discord.max-commands-per-minute", 20);

        redactSensitiveLines = cfg.getBoolean("discord.redact-sensitive-lines", true);
        List<String> rawPatterns = cfg.getStringList("discord.redact-patterns");
        List<Pattern> compiled = new java.util.ArrayList<>();
        for (String p : rawPatterns) {
            if (p == null || p.isBlank()) {
                continue;
            }
            try {
                compiled.add(Pattern.compile(p));
            } catch (PatternSyntaxException e) {
                plugin.getLogger().warning("DiscordBridge: invalid redact-pattern ignored: " + p);
            }
        }
        redactPatterns = compiled;

        validateChannelIds();

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

    public List<String> getCommandAllowlist() {
        return commandAllowlist;
    }

    public boolean isCommandAllowed(String command) {
        if (commandAllowlist.isEmpty()) {
            return true;
        }
        String cmd = command.trim();
        for (String allowed : commandAllowlist) {
            if (cmd.equals(allowed) || cmd.startsWith(allowed + " ")) {
                return true;
            }
        }
        return false;
    }

    public int getMaxCommandsPerMinute() {
        return Math.max(0, maxCommandsPerMinute);
    }

    public boolean isRedactSensitiveLines() {
        return redactSensitiveLines;
    }

    /**
     * Applies configured secret-redaction regexes to a single log line, and
     * always scrubs the literal configured bot token. Lines are only redacted
     * if discord.redact-sensitive-lines is enabled.
     */
    public String redact(String line) {
        if (!redactSensitiveLines || line == null) {
            return line;
        }
        String out = line;
        for (Pattern p : redactPatterns) {
            out = p.matcher(out).replaceAll("***REDACTED***");
        }
        if (token != null && token.length() >= 20) {
            out = out.replace(token, "***REDACTED***");
        }
        return out;
    }

    private static final Pattern SNOWFLAKE = Pattern.compile("\\d{17,20}");

    private void validateChannelIds() {
        if (logChannelId != null && !logChannelId.isBlank() && !logChannelId.startsWith("REPLACE_WITH")
                && !SNOWFLAKE.matcher(logChannelId).matches()) {
            plugin.getLogger().warning("DiscordBridge: discord.log-channel-id is not a valid Discord channel ID: " + logChannelId);
        }
        if (chatChannelId != null && !chatChannelId.isBlank() && !chatChannelId.startsWith("REPLACE_WITH")
                && !SNOWFLAKE.matcher(chatChannelId).matches()) {
            plugin.getLogger().warning("DiscordBridge: chat-bridge.channel-id is not a valid Discord channel ID: " + chatChannelId);
        }
        if (logChannelId != null && logChannelId.equals(chatChannelId)
                && !logChannelId.isBlank() && !logChannelId.startsWith("REPLACE_WITH")) {
            plugin.getLogger().warning("DiscordBridge: log-channel-id and chat-bridge.channel-id are the same; "
                    + "chat bridge and console-command handling share a channel, which is unsafe. Use different channels.");
        }
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
