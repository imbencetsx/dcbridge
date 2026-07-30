package net.msstore.discordbridge;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.core.LoggerContext;
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
        appender = ConsoleCaptureAppender.create();
        LoggerContext context = (LoggerContext) LogManager.getContext(false);
        context.getRootLogger().addAppender(appender);

        discordBotHandler = new DiscordBotHandler(this, configManager, appender);
        try {
            discordBotHandler.start();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            getLogger().severe("Interrupted while connecting to Discord: " + e.getMessage());
        } catch (Exception e) {
            getLogger().severe("Failed to connect to Discord: " + e.getMessage());
        }

        DcBridgeCommand commandHandler = new DcBridgeCommand(configManager);
        getCommand("dcbridge").setExecutor(commandHandler);
        getCommand("dcbridge").setTabCompleter(commandHandler);

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
