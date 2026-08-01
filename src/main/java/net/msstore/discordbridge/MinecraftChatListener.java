package net.msstore.discordbridge;

import io.papermc.paper.event.player.AsyncChatEvent;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.EventPriority;

/**
 * Forwards Minecraft chat messages to the configured Discord chat-bridge channel.
 */
public class MinecraftChatListener implements Listener {

    private final ConfigManager config;
    private final DiscordBotHandler discordBotHandler;

    public MinecraftChatListener(ConfigManager config, DiscordBotHandler discordBotHandler) {
        this.config = config;
        this.discordBotHandler = discordBotHandler;
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onChat(AsyncChatEvent event) {
        if (!config.isChatBridgeEnabled()) {
            return;
        }
        String plainMessage = PlainTextComponentSerializer.plainText().serialize(event.message());
        if (plainMessage.isBlank()) {
            return;
        }
        String playerName = event.getPlayer().getName();

        // AsyncChatEvent already runs off the main thread, so it's fine to do
        // the (potentially network-bound) Discord lookup/send work right here.
        discordBotHandler.forwardChatToDiscord(playerName, plainMessage);
    }
}
