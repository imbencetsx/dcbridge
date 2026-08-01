package net.msstore.discordbridge;

import net.dv8tion.jda.api.JDA;
import net.dv8tion.jda.api.JDABuilder;
import net.dv8tion.jda.api.entities.Guild;
import net.dv8tion.jda.api.entities.Member;
import net.dv8tion.jda.api.entities.channel.concrete.TextChannel;
import net.dv8tion.jda.api.entities.channel.middleman.MessageChannel;
import net.dv8tion.jda.api.events.message.MessageReceivedEvent;
import net.dv8tion.jda.api.events.session.ShutdownEvent;
import net.dv8tion.jda.api.hooks.ListenerAdapter;
import net.dv8tion.jda.api.requests.GatewayIntent;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.apache.logging.log4j.Level;
import org.apache.logging.log4j.core.config.Configurator;
import org.bukkit.Bukkit;
import org.bukkit.scheduler.BukkitTask;

import java.util.List;
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

    // Matches things like "@balazskokai" in a chat message.
    private static final Pattern MENTION_PATTERN = Pattern.compile("@([A-Za-z0-9_.]{2,32})");

    private final DiscordBridgePlugin plugin;
    private final ConfigManager config;
    private final ConsoleCaptureAppender appender;

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
    public void start() {
        if (shuttingDown) {
            return;
        }
        if (config.getToken() == null || config.getToken().isBlank() || config.getToken().startsWith("YOUR_BOT_TOKEN")) {
            plugin.getLogger().warning("DiscordBridge is not configured: set discord.token in config.yml, then reload/restart.");
            return;
        }

        try {
            jda = JDABuilder.createDefault(config.getToken())
                    .enableIntents(GatewayIntent.MESSAGE_CONTENT, GatewayIntent.GUILD_MESSAGES)
                    .setAutoReconnect(false) // we manage retries ourselves, quietly
                    .addEventListeners(this)
                    .build();
            jda.awaitReady();

            // Connected: reset backoff and (re)start the periodic log flush.
            currentReconnectDelay = INITIAL_RECONNECT_DELAY_SECONDS;
            if (flushTask != null) {
                flushTask.cancel();
            }
            long periodTicks = config.getFlushIntervalSeconds() * 20L;
            flushTask = Bukkit.getScheduler().runTaskTimerAsynchronously(plugin, this::flushConsoleQueue, periodTicks, periodTicks);

            plugin.getLogger().info("DiscordBridge connected to Discord as " + jda.getSelfUser().getAsTag());
        } catch (Exception e) {
            jda = null;
            scheduleReconnect();
        }
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
        if (jda == null || !appender.hasQueuedLines()) {
            return;
        }
        String channelId = config.getLogChannelId();
        if (channelId == null || channelId.isBlank() || channelId.startsWith("REPLACE_WITH")) {
            return;
        }
        MessageChannel channel = jda.getChannelById(MessageChannel.class, channelId);
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
        String trimmed = content.length() > DISCORD_MESSAGE_LIMIT - 10
                ? content.substring(0, DISCORD_MESSAGE_LIMIT - 10)
                : content;
        channel.sendMessage("```" + trimmed + "```").queue(s -> {}, f -> {});
    }

    // ------------------------------------------------------------------
    // Minecraft chat -> Discord
    // ------------------------------------------------------------------

    /** Called from the Minecraft chat listener with the plain-text message. */
    public void forwardChatToDiscord(String playerName, String rawMessage) {
        if (jda == null || !config.isChatBridgeEnabled()) {
            return;
        }
        TextChannel channel = jda.getTextChannelById(config.getChatChannelId());
        if (channel == null) {
            return;
        }

        String processed = resolveMentions(rawMessage, channel.getGuild());
        String content = "<" + playerName + "> " + processed;
        if (content.length() > DISCORD_MESSAGE_LIMIT) {
            content = content.substring(0, DISCORD_MESSAGE_LIMIT);
        }
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

        while (matcher.find()) {
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

        String command = event.getMessage().getContentRaw().trim();
        if (command.isEmpty()) {
            return;
        }
        // Strip a leading slash if the user typed one, since console commands don't need it.
        if (command.startsWith("/")) {
            command = command.substring(1);
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

    private void handleChatMessage(MessageReceivedEvent event) {
        // contentDisplay resolves real <@id> mentions back into readable @names for us.
        String content = event.getMessage().getContentDisplay();
        if (content.isBlank()) {
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

