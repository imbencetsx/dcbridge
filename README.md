# DiscordBridge

A simple Paper plugin that:
- Forwards all console log output to a Discord channel (batched every few seconds,
  capped so a huge backlog never builds up if Discord is unreachable)
- Lets whitelisted Discord user IDs execute console commands by typing in that channel
- Bridges Minecraft chat <-> a Discord channel, both directions
- Provides `/db console whitelist <id>` and `/db console unwhitelist <id>` in-game commands
  (permissions: `dcbridge.whitelist`, `dcbridge.unwhitelist`, default: op)

## Build

```
./gradlew build
```

The compiled plugin jar (with JDA bundled) will be at `build/libs/DiscordBridge-1.0.0.jar`.

Targets Paper API `26.2.build.+` and JDK 25 (Paper 26.2's build requirement).

## Setup

1. Create a Discord bot at https://discord.com/developers/applications
   - Under "Bot", enable the **MESSAGE CONTENT INTENT** (required to read command/chat messages)
   - Copy the bot token
   - Invite the bot to your server with `Send Messages`, `Read Message History`, and
     `Add Reactions` permissions in the relevant channels
2. Drop the built jar into your server's `plugins/` folder and start the server once to
   generate `plugins/DiscordBridge/config.yml`
3. Edit `config.yml`:
   ```yaml
   discord:
     token: "your-bot-token"
     log-channel-id: "123456789012345678"
   whitelisted-users:
     - "123456789012345678"
   chat-bridge:
     enabled: true
     channel-id: "987654321098765432"
   ```
4. Restart the server (or `/reload` at your own risk — Paper doesn't officially support it)

## In-game commands

- `/db console whitelist <discord_user_id>` — allow a Discord user to run console commands
- `/db console unwhitelist <discord_user_id>` — revoke that access

Grant these to non-ops with LuckPerms, e.g.:
```
/lp group default permission set dcbridge.whitelist true
```

## Chat bridge

- Minecraft chat -> Discord: posted as `<PlayerName> message`, in the `chat-bridge.channel-id` channel.
  If the message contains `@someusername`, the bot does a best-effort lookup for a guild member
  with that exact username and replaces it with a real `<@id>` Discord mention. If nothing is
  found (typo, timeout, etc.) the text is left exactly as typed — this never blocks or breaks
  the rest of the message.
- Discord -> Minecraft chat: broadcast as `[DC] username: message`, with `[DC]` in light purple.
  Uses the sender's Discord username, falling back to their server display name if unavailable.
- Must use a different channel than `discord.log-channel-id` (that one stays console-only).

## Reconnect behavior

If the bot can't reach Discord (login fails, or the connection drops), it does **not** use JDA's
built-in noisy auto-reconnect. Instead it logs one line:
```
[DiscordBridge] Failed to connect to Discord API, retrying in 15 seconds...
```
and retries with doubling backoff (15s, 30s, 60s, capped at 120s), resetting back to 15s once it
successfully reconnects.

## Console output cap

If more than `max-queued-log-lines` (default 150) lines build up waiting to be sent — e.g. Discord
is unreachable for a while — the oldest ones are silently dropped so only the most recent output
is kept, instead of dumping a huge backlog all at once when the connection returns.

## Notes / things worth knowing

- Console output is captured via a Log4j2 appender on the root logger — it's essentially
  everything that prints to console, from any plugin, not just this one.
- Messages are batched and flushed every `log-flush-interval-seconds` (default 3s) and
  chunked to Discord's 2000-character limit, to avoid rate limits during log spam.
- Commands typed in the log channel by a whitelisted user get a ✅ reaction once dispatched.
- Only the exact channel IDs configured are watched/posted to.
- @mention resolution uses Discord's member-search (not the full member cache), so it doesn't
  need the privileged "Server Members Intent" to work.

