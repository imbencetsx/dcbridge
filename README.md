# DiscordBridge

A simple Paper plugin that:
- Forwards all console log output to a Discord channel (batched every few seconds)
- Lets whitelisted Discord user IDs execute console commands by typing in that channel
- Provides `/dcbridge whitelist <id>` and `/dcbridge unwhitelist <id>` in-game commands
  (permissions: `dcbridge.whitelist`, `dcbridge.unwhitelist`, default: op)

## Build

```
./gradlew build
```

The compiled plugin jar (with JDA bundled) will be at `build/libs/DiscordBridge-1.0.0.jar`.

**Before building**, open `build.gradle.kts` and check the `paper-api` version matches the
Paper build you actually run (e.g. `1.21.4-R0.1-SNAPSHOT`). "PaperMC 26.2" isn't a version
string PaperMC uses (Paper versions track Minecraft versions like 1.21.x) — I assumed you
meant a current 1.21.x build; adjust if not.

## Setup

1. Create a Discord bot at https://discord.com/developers/applications
   - Under "Bot", enable the **MESSAGE CONTENT INTENT** (required to read command messages)
   - Copy the bot token
   - Invite the bot to your server with `Send Messages`, `Read Message History`, and
     `Add Reactions` permissions in the target channel
2. Drop the built jar into your server's `plugins/` folder and start the server once to
   generate `plugins/DiscordBridge/config.yml`
3. Edit `config.yml`:
   ```yaml
   discord:
     token: "your-bot-token"
     log-channel-id: "123456789012345678"
   whitelisted-users:
     - "123456789012345678"
   ```
4. Restart the server (or `/reload` at your own risk — Paper doesn't officially support it)

## In-game commands

- `/dcbridge whitelist <discord_user_id>` — allow a Discord user to run console commands
- `/dcbridge unwhitelist <discord_user_id>` — revoke that access

Grant these to non-ops with LuckPerms, e.g.:
```
/lp group default permission set dcbridge.whitelist true
```

## Notes / things worth knowing

- Console output is captured via a Log4j2 appender on the root logger — it's essentially
  everything that prints to console, from any plugin, not just this one.
- Messages are batched and flushed every `log-flush-interval-seconds` (default 3s) and
  chunked to Discord's 2000-character limit, to avoid rate limits during log spam.
- Commands typed in the log channel by a whitelisted user get a ✅ reaction once dispatched.
- Only the exact channel ID in `discord.log-channel-id` is watched/posted to.
- This is a lean implementation for a single log/command channel — if you want per-command
  permission mapping, multiple channels, or webhook-based (vs bot-based) log posting, that's
  a reasonable follow-up but adds real complexity, so I kept this version simple as asked.
