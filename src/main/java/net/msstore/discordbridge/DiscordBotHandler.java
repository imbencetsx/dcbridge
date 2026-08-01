package net.msstore.discordbridge;

import net.dv8tion.jda.api.JDA;
import net.dv8tion.jda.api.JDABuilder;
import net.dv8tion.jda.api.entities.Guild;
import net.dv8tion.jda.api.entities.Member;
import net.dv8tion.jda.api.entities.channel.concrete.TextChannel;
import net.dv8tion.jda.api.entities.channel.middleman.MessageChannel;
import net.dv8tion.jda.api.events.message.MessageReceivedEvent;
import net.dv8tion.jda.api.events.session.ReadyEvent;
import net.dv8tion.jda.api.events.session.ShutdownEvent;
import net.dv8tion.jda.api.hooks.ListenerAdapter;
import net.dv8tion.jda.api.requests.GatewayIntent;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.apache.logging.log4j.Level;
import org.apache.logging.log4j.core.config.Configurator;
import org.bukkit.Bukkit;
import org.bukkit.scheduler.BukkitTask;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Owns the JDA (Discord) connection. Responsible for:
 *  - logging in the bot, with a quiet capped-backoff retry loop instead of
 *    JDA's noisy built-in auto-reconnect if the connection drops or fails
 *  - periodically flushing captured console lines to the configured channel
 *  - listening for messages in the log channel and, if the author is
 *    whitelisted, running the message content as a console command
 *  - bridging Minecraft chat <-> a configured Discord channel
 */
public class DiscordBotHandler extends ListenerAdapter {

    private static final int DISCORD_MESSAGE_LIMIT = 2000;
    private static final int INITIAL_RECONNECT_DELAY_SECONDS = 15;
    private static final int MAX_RECONNECT_DELAY_SECONDS = 120;
    private static final int MAX_COMMAND_LENGTH = 256;
    private static final int MAX_MENTIONS_PER_MESSAGE = 10;
    private static final long RATE_WINDOW_NANOS = TimeUnit.MINUTES.toNanos(1);

    // Matches things like "@balazskokai" in a chat message.
    private static final Pattern MENTION_PATTERN = Pattern.compile("@([A-Za-z0-9_.]{2,32})");
    // Discord messages can carry control characters/newlines that would break or
    // pollute Minecraft chat or the console command parser.
    private static final Pattern CONTROL_CHARS = Pattern.compile("[\\p{Cc}\\p{Cf}&&[^\\r\\n\\t]]");

    private final DiscordBridgePlugin plugin;
    private final ConfigManager config;
    private final ConsoleCaptureAppender appender;

    // Per-Discord-user sliding window of command timestamps for rate limiting.
    private final Map<String, Deque<Long>> commandTimestamps = new ConcurrentHashMap<>();

    private JDA jda;
    private BukkitTask flushTask;
    private BukkitTask pendingReconnectTask;
    private int currentReconnectDelay = INITIAL_RECONNECT_DELAY_SECONDS;
    private volatile boolean shuttingDown = false;

    public DiscordBotHandler(DiscordBridgePlugin plugin, ConfigManager config, ConsoleCaptureAppender appender) {
        this.plugin = plugin;
        this.config = config;
        this.appender = appender;

        // JDA/okhttp log plenty of their own diagnostic noise through Log4j2,
        // which would otherwise get captured by our own appender and echoed
        // back to Discord. Keep only warnings and above from those.
        Configurator.setLevel("net.dv8tion.jda", Level.WARN);
        Configurator.setLevel("okhttp3", Level.WARN);
    }

    /** Attempts to connect to Discord. Safe to call repeatedly (e.g. for retries). */
    public synchronized void start() {
        if (shuttingDown) {
            return;
        }
        if (config.getToken() == null || config.getToken().isBlank() || config.getToken().startsWith("YOUR_BOT_TOKEN")) {
            plugin.getLogger().warning("DiscordBridge is not configured: set discord.token in config.yml, then reload/restart.");
            return;
        }
        if (jda != null && jda.getStatus() == JDA.Status.CONNECTED) {
            return;
        }

        try {
            jda = JDABuilder.createDefault(config.getToken())
                    .enableIntents(GatewayIntent.MESSAGE_CONTENT, GatewayIntent.GUILD_MESSAGES)
                    .setAutoReconnect(false) // we manage retries ourselves, quietly
                    .addEventListeners(this)
                    .build();
            // build() returns immediately; login is fully async. onReady() and
            // onShutdown() drive the success/retry paths, so the main server
            // thread is never blocked waiting on Discord.
        } catch (Exception e) {
            jda = null;
            scheduleReconnect();
        }
    }

    /** Called by JDA once the bot is connected and the guild cache is loaded. */
    @Override
    public void onReady(ReadyEvent event) {
        if (shuttingDown) {
            return;
        }
        // Connected: reset backoff and (re)start the periodic log flush.
        currentReconnectDelay = INITIAL_RECONNECT_DELAY_SECONDS;
        if (flushTask != null) {
            flushTask.cancel();
        }
        long periodTicks = config.getFlushIntervalSeconds() * 20L;
        flushTask = Bukkit.getScheduler().runTaskTimerAsynchronously(plugin, this::flushConsoleQueue, periodTicks, periodTicks);

        plugin.getLogger().info("DiscordBridge connected to Discord as " + event.getJDA().getSelfUser().getAsTag());
    }

    /** Called by JDA when the session ends (e.g. lost connection) since auto-reconnect is off. */
    @Override
    public void onShutdown(ShutdownEvent event) {
        if (shuttingDown) {
            return;
        }
        jda = null;
        if (flushTask != null) {
            flushTask.cancel();
            flushTask = null;
        }
        scheduleReconnect();
    }

    private void scheduleReconnect() {
        if (shuttingDown) {
            return;
        }
        if (pendingReconnectTask != null) {
            pendingReconnectTask.cancel();
            pendingReconnectTask = null;
        }
        int delay = currentReconnectDelay;
        plugin.getLogger().warning("[DiscordBridge] Failed to connect to Discord API, retrying in " + delay + " seconds...");
        pendingReconnectTask = Bukkit.getScheduler().runTaskLaterAsynchronously(plugin, this::start, delay * 20L);
        currentReconnectDelay = Math.min(currentReconnectDelay * 2, MAX_RECONNECT_DELAY_SECONDS);
    }

    /** Cleanly disconnects from Discord. */
    public void stop() {
        shuttingDown = true;
        if (pendingReconnectTask != null) {
            pendingReconnectTask.cancel();
            pendingReconnectTask = null;
        }
        if (flushTask != null) {
            flushTask.cancel();
            flushTask = null;
        }
        if (jda != null) {
            // Send anything still queued before shutting down.
            flushConsoleQueue();
            jda.shutdown();
            try {
                jda.awaitShutdown(10, TimeUnit.SECONDS);
            } catch (InterruptedException ignored) {
                Thread.currentThread().interrupt();
            }
            jda = null;
        }
    }

    private void flushConsoleQueue() {
        JDA current = jda;
        if (current == null || !appender.hasQueuedLines()) {
            return;
        }
        String channelId = config.getLogChannelId();
        if (channelId == null || channelId.isBlank() || channelId.startsWith("REPLACE_WITH")) {
            return;
        }
        MessageChannel channel = current.getChannelById(MessageChannel.class, channelId);
        if (channel == null) {
            return;
        }

        List<String> lines = appender.drain();
        StringBuilder batch = new StringBuilder();
        for (String line : lines) {
            // Discord code blocks reserve ~8 chars ("```\n" + "\n```")
            if (batch.length() + line.length() + 1 > DISCORD_MESSAGE_LIMIT - 10) {
                sendBatch(channel, batch.toString());
                batch.setLength(0);
            }
            batch.append(line).append('\n');
        }
        if (batch.length() > 0) {
            sendBatch(channel, batch.toString());
        }
    }

    private void sendBatch(MessageChannel channel, String content) {
        String trimmed = truncateUtf16(content, DISCORD_MESSAGE_LIMIT - 10);
        channel.sendMessage("```" + trimmed + "```").queue(s -> {}, f -> {});
    }

    /**
     * Truncates a string to at most maxChars UTF-16 code units without splitting
     * a surrogate pair, so Discord never receives a malformed/invalid message.
     */
    private static String truncateUtf16(String s, int maxChars) {
        if (s.length() <= maxChars) {
            return s;
        }
        String cut = s.substring(0, maxChars);
        if (Character.isHighSurrogate(cut.charAt(cut.length() - 1))) {
            cut = cut.substring(0, cut.length() - 1);
        }
        return cut;
    }

    // ------------------------------------------------------------------
    // Minecraft chat -> Discord
    // ------------------------------------------------------------------

    /** Called from the Minecraft chat listener with the plain-text message. */
    public void forwardChatToDiscord(String playerName, String rawMessage) {
        JDA current = jda;
        if (current == null || !config.isChatBridgeEnabled()) {
            return;
        }
        TextChannel channel = current.getTextChannelById(config.getChatChannelId());
        if (channel == null) {
            return;
        }

        String processed = resolveMentions(rawMessage, channel.getGuild());
        String content = truncateUtf16("<" + playerName + "> " + processed, DISCORD_MESSAGE_LIMIT);
        channel.sendMessage(content).queue(s -> {}, f -> {});
    }

    /**
     * Best-effort: finds "@username" tokens in the message and, if a Discord
     * guild member with that exact username can be found, replaces the token
     * with a real "<@id>" mention. Anything that can't be resolved (no match,
     * lookup timeout, missing permissions, etc.) is left exactly as typed.
     */
    private String resolveMentions(String message, Guild guild) {
        if (guild == null || !message.contains("@")) {
            return message;
        }

        Matcher matcher = MENTION_PATTERN.matcher(message);
        StringBuilder result = new StringBuilder();

        int resolved = 0;
        while (matcher.find() && resolved < MAX_MENTIONS_PER_MESSAGE) {
            resolved++;
            String candidate = matcher.group(1);
            String replacement = matcher.group(0);

            try {
                java.util.concurrent.CompletableFuture<List<Member>> future = new java.util.concurrent.CompletableFuture<>();
                guild.retrieveMembersByPrefix(candidate, 5)
                        .onSuccess(future::complete)
                        .onError(future::completeExceptionally);
                List<Member> found = future.get(3, TimeUnit.SECONDS);
                for (Member member : found) {
                    if (member.getUser().getName().equalsIgnoreCase(candidate)) {
                        replacement = "<@" + member.getId() + ">";
                        break;
                    }
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            } catch (ExecutionException | TimeoutException | RuntimeException ignored) {
                // Any failure here just means we leave the text as-is.
            }

            matcher.appendReplacement(result, Matcher.quoteReplacement(replacement));
        }
        matcher.appendTail(result);
        return result.toString();
    }

    // ------------------------------------------------------------------
    // Discord -> Minecraft (console commands + chat bridge)
    // ------------------------------------------------------------------

    @Override
    public void onMessageReceived(MessageReceivedEvent event) {
        if (event.getAuthor().isBot() || event.getAuthor().isSystem()) {
            return;
        }
        String channelId = event.getChannel().getId();

        if (channelId.equals(config.getLogChannelId())) {
            handleConsoleCommand(event);
        } else if (config.isChatBridgeEnabled() && channelId.equals(config.getChatChannelId())) {
            handleChatMessage(event);
        }
    }

    private void handleConsoleCommand(MessageReceivedEvent event) {
        String authorId = event.getAuthor().getId();
        if (!config.isWhitelisted(authorId)) {
            return;
        }
        if (!allowCommand(authorId)) {
            event.getMessage().addReaction(net.dv8tion.jda.api.entities.emoji.Emoji.fromUnicode("\ud83d\udeab")).queue(
                    success -> {}, failure -> {}
            );
            return;
        }

        String command = sanitizeCommand(event.getMessage().getContentRaw());
        if (command == null || command.isEmpty()) {
            return;
        }
        if (!config.isCommandAllowed(command)) {
            event.getMessage().addReaction(net.dv8tion.jda.api.entities.emoji.Emoji.fromUnicode("\ud83d\udeab")).queue(
                    success -> {}, failure -> {}
            );
            plugin.getLogger().warning("[DiscordBridge] Blocked disallowed command from Discord user " + authorId + ": " + command);
            return;
        }
        final String finalCommand = command;

        // Commands must run on the main server thread.
        Bukkit.getScheduler().runTask(plugin, () -> {
            plugin.getLogger().info("[DiscordBridge] Executing command from Discord user " + authorId + ": " + finalCommand);
            Bukkit.dispatchCommand(Bukkit.getConsoleSender(), finalCommand);
        });

        event.getMessage().addReaction(net.dv8tion.jda.api.entities.emoji.Emoji.fromUnicode("\u2705")).queue(
                success -> {}, failure -> {}
        );
    }

    /**
     * Normalizes a raw Discord message into a single-line console command:
     * strips leading slashes, rejects newlines/control characters (so a message
     * can't smuggle extra commands), and caps the length.
     */
    private String sanitizeCommand(String raw) {
        String command = raw == null ? "" : raw.trim();
        if (command.isEmpty()) {
            return null;
        }
        if (CONTROL_CHARS.matcher(command).find() || command.contains("\n") || command.contains("\r")) {
            plugin.getLogger().warning("[DiscordBridge] Rejected command containing control characters from Discord.");
            return null;
        }
        if (command.startsWith("/")) {
            command = command.substring(1);
        }
        if (command.length() > MAX_COMMAND_LENGTH) {
            command = command.substring(0, MAX_COMMAND_LENGTH);
        }
        return command.trim();
    }

    /**
     * Sliding-window rate limit per Discord user. Returns true if the user is
     * still allowed to dispatch a command right now.
     */
    private boolean allowCommand(String userId) {
        int limit = config.getMaxCommandsPerMinute();
        if (limit <= 0) {
            return true;
        }
        long now = System.nanoTime();
        long cutoff = now - RATE_WINDOW_NANOS;
        Deque<Long> window = commandTimestamps.computeIfAbsent(userId, k -> new ArrayDeque<>());
        synchronized (window) {
            while (!window.isEmpty() && window.peekFirst() < cutoff) {
                window.pollFirst();
            }
            if (window.size() >= limit) {
                return false;
            }
            window.addLast(now);
            return true;
        }
    }

    private void handleChatMessage(MessageReceivedEvent event) {
        // contentDisplay resolves real <@id> mentions back into readable @names for us.
        String content = event.getMessage().getContentDisplay();
        if (content.isBlank()) {
            return;
        }
        // Strip control characters (Discord messages can carry them) and cap
        // length so a single message can't flood/spam Minecraft chat.
        content = CONTROL_CHARS.matcher(content).replaceAll("");
        content = truncateUtf16(content, 256).strip();
        if (content.isEmpty()) {
            return;
        }

        String authorName = event.getAuthor().getName();
        if (authorName == null || authorName.isBlank()) {
            authorName = event.getMember() != null
                    ? event.getMember().getEffectiveName()
                    : event.getAuthor().getEffectiveName();
        }

        Component message = Component.text("[DC] ", NamedTextColor.LIGHT_PURPLE)
                .append(Component.text(authorName + ": " + content));

        Bukkit.getScheduler().runTask(plugin, () -> Bukkit.broadcast(message));
    }
}

