package net.msstore.discordbridge;

import org.apache.logging.log4j.core.Filter;
import org.apache.logging.log4j.core.Layout;
import org.apache.logging.log4j.core.LogEvent;
import org.apache.logging.log4j.core.appender.AbstractAppender;
import org.apache.logging.log4j.core.layout.PatternLayout;

import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.regex.Pattern;

/**
 * A Log4j2 appender that gets attached to the server's root logger.
 * Every log line the console prints also lands here, gets stripped of
 * color codes, and is placed on a queue that DiscordBotHandler drains
 * periodically and forwards to the configured Discord channel.
 */
public class ConsoleCaptureAppender extends AbstractAppender {

    // Matches Minecraft "§x" color codes and raw ANSI escape sequences.
    private static final Pattern SECTION_COLOR = Pattern.compile("(?i)\u00A7[0-9A-FK-OR]");
    private static final Pattern ANSI_ESCAPE = Pattern.compile("\u001B\\[[;\\d]*m");

    private final ConcurrentLinkedQueue<String> queue = new ConcurrentLinkedQueue<>();

    protected ConsoleCaptureAppender(String name, Filter filter, Layout<String> layout) {
        super(name, filter, layout, false, null);
    }

    public static ConsoleCaptureAppender create() {
        PatternLayout layout = PatternLayout.newBuilder()
                .withPattern("[%d{HH:mm:ss} %level] %msg")
                .build();
        ConsoleCaptureAppender appender = new ConsoleCaptureAppender("DiscordBridgeAppender", null, layout);
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
        formatted = formatted.strip();

        if (!formatted.isEmpty()) {
            queue.add(formatted);
        }
    }

    /** Drains and returns everything currently queued, in order. */
    public java.util.List<String> drain() {
        java.util.List<String> lines = new java.util.ArrayList<>();
        String line;
        while ((line = queue.poll()) != null) {
            lines.add(line);
        }
        return lines;
    }

    public boolean hasQueuedLines() {
        return !queue.isEmpty();
    }
}
