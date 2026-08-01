package net.msstore.discordbridge;

import org.apache.logging.log4j.core.Filter;
import org.apache.logging.log4j.core.Layout;
import org.apache.logging.log4j.core.LogEvent;
import org.apache.logging.log4j.core.appender.AbstractAppender;
import org.apache.logging.log4j.core.layout.PatternLayout;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.regex.Pattern;

/**
 * A Log4j2 appender that gets attached to the server's root logger.
 * Every log line the console prints also lands here, gets stripped of
 * color codes, and is placed on a bounded queue that DiscordBotHandler
 * drains periodically and forwards to the configured Discord channel.
 *
 * The queue is capped at maxQueueSize: if lines pile up faster than they
 * can be sent (e.g. Discord is unreachable), the oldest queued lines are
 * silently dropped so we always keep the most recent output instead of
 * one huge backlog.
 */
public class ConsoleCaptureAppender extends AbstractAppender {

    // Matches Minecraft "§x" color codes and raw ANSI escape sequences.
    private static final Pattern SECTION_COLOR = Pattern.compile("(?i)\u00A7[0-9A-FK-OR]");
    private static final Pattern ANSI_ESCAPE = Pattern.compile("\u001B\\[[;\\d]*m");

    private final Deque<String> queue = new ArrayDeque<>();
    private final int maxQueueSize;
    private final ConfigManager config;

    protected ConsoleCaptureAppender(String name, Filter filter, Layout<String> layout, int maxQueueSize, ConfigManager config) {
        super(name, filter, layout, false, null);
        this.maxQueueSize = Math.max(1, maxQueueSize);
        this.config = config;
    }

    public static ConsoleCaptureAppender create(int maxQueueSize, ConfigManager config) {
        PatternLayout layout = PatternLayout.newBuilder()
                .withPattern("[%d{HH:mm:ss} %level] %msg")
                .build();
        ConsoleCaptureAppender appender = new ConsoleCaptureAppender("DiscordBridgeAppender", null, layout, maxQueueSize, config);
        appender.start();
        return appender;
    }

    @Override
    public void append(LogEvent event) {
        String formatted;
        Layout<String> layout = (Layout<String>) getLayout();
        if (layout != null) {
            formatted = layout.toSerializable(event);
        } else {
            formatted = event.getMessage().getFormattedMessage();
        }

        formatted = SECTION_COLOR.matcher(formatted).replaceAll("");
        formatted = ANSI_ESCAPE.matcher(formatted).replaceAll("");
        if (config != null) {
            formatted = config.redact(formatted);
        }
        formatted = formatted.strip();

        if (formatted.isEmpty()) {
            return;
        }

        synchronized (queue) {
            // Drop the oldest backlog once we hit the cap, so we never build
            // up an unbounded/huge queue while Discord is unreachable - we
            // just keep the most recent lines and carry on.
            while (queue.size() >= maxQueueSize) {
                queue.pollFirst();
            }
            queue.addLast(formatted);
        }
    }

    /** Drains and returns everything currently queued, in order. */
    public List<String> drain() {
        synchronized (queue) {
            List<String> lines = new ArrayList<>(queue);
            queue.clear();
            return lines;
        }
    }

    public boolean hasQueuedLines() {
        synchronized (queue) {
            return !queue.isEmpty();
        }
    }
}
