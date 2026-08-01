package net.msstore.discordbridge;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.core.LoggerContext;
import org.bukkit.Bukkit;
import org.bukkit.plugin.java.JavaPlugin;

public class DiscordBridgePlugin extends JavaPlugin {

    private ConfigManager configManager;
    private ConsoleCaptureAppender appender;
    private DiscordBotHandler discordBotHandler;

    @Override
    public void onEnable() {
        saveDefaultConfig();

        configManager = new ConfigManager(this);
        configManager.load();

        // Attach a Log4j2 appender to the root logger so every console line
        // (from this plugin, other plugins, and vanilla server logs) is captured.
        appender = ConsoleCaptureAppender.create(configManager.getMaxQueuedLogLines(), configManager);
        LoggerContext context = (LoggerContext) LogManager.getContext(false);
        context.getRootLogger().addAppender(appender);

        discordBotHandler = new DiscordBotHandler(this, configManager, appender);
        discordBotHandler.start();

        DcBridgeCommand commandHandler = new DcBridgeCommand(configManager);
        getCommand("db").setExecutor(commandHandler);
        getCommand("db").setTabCompleter(commandHandler);

        Bukkit.getPluginManager().registerEvents(
                new MinecraftChatListener(configManager, discordBotHandler), this);

        getLogger().info("DiscordBridge enabled.");
    }

    @Override
    public void onDisable() {
        if (discordBotHandler != null) {
            discordBotHandler.stop();
        }
        if (appender != null) {
            LoggerContext context = (LoggerContext) LogManager.getContext(false);
            context.getRootLogger().removeAppender(appender);
            appender.stop();
        }
        getLogger().info("DiscordBridge disabled.");
    }

    public ConfigManager getConfigManager() {
        return configManager;
    }
}
