package net.msstore.discordbridge;

import net.dv8tion.jda.api.JDA;
import net.dv8tion.jda.api.JDABuilder;
import net.dv8tion.jda.api.entities.channel.middleman.MessageChannel;
import net.dv8tion.jda.api.events.message.MessageReceivedEvent;
import net.dv8tion.jda.api.hooks.ListenerAdapter;
import net.dv8tion.jda.api.requests.GatewayIntent;
import org.bukkit.Bukkit;
import org.bukkit.scheduler.BukkitTask;

import java.util.List;
import java.util.concurrent.TimeUnit;

/**
 * Owns the JDA (Discord) connection. Responsible for:
 *  - logging in the bot
 *  - periodically flushing captured console lines to the configured channel
 *  - listening for messages in that channel and, if the author is whitelisted,
 *    running the message content as a console command.
 */
public class DiscordBotHandler extends ListenerAdapter {

    private static final int DISCORD_MESSAGE_LIMIT = 2000;

    private final DiscordBridgePlugin plugin;
    private final ConfigManager config;
    private final ConsoleCaptureAppender appender;

    private JDA jda;
    private BukkitTask flushTask;

    public DiscordBotHandler(DiscordBridgePlugin plugin, ConfigManager config, ConsoleCaptureAppender appender) {
        this.plugin = plugin;
        this.config = config;
        this.appender = appender;
    }

    /** Connects to Discord. Blocks briefly until the session is ready. */
    public void start() throws InterruptedException {
        if (config.getToken() == null || config.getToken().isBlank() || config.getToken().startsWith("YOUR_BOT_TOKEN")) {
            plugin.getLogger().warning("DiscordBridge is not configured: set discord.token in config.yml, then reload/restart.");
            return;
        }

        jda = JDABuilder.createDefault(config.getToken())
                .enableIntents(GatewayIntent.MESSAGE_CONTENT, GatewayIntent.GUILD_MESSAGES)
                .addEventListeners(this)
                .build();
        jda.awaitReady();

        // Periodically flush queued console lines to Discord.
        long periodTicks = config.getFlushIntervalSeconds() * 20L;
        flushTask = Bukkit.getScheduler().runTaskTimerAsynchronously(plugin, this::flushConsoleQueue, periodTicks, periodTicks);

        plugin.getLogger().info("DiscordBridge connected to Discord as " + jda.getSelfUser().getAsTag());
    }

    /** Cleanly disconnects from Discord. */
    public void stop() {
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
        channel.sendMessage("```" + trimmed + "```").queue();
    }

    @Override
    public void onMessageReceived(MessageReceivedEvent event) {
        if (event.getAuthor().isBot() || event.getAuthor().isSystem()) {
            return;
        }
        String channelId = config.getLogChannelId();
        if (channelId == null || !channelId.equals(event.getChannel().getId())) {
            return;
        }

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
}
