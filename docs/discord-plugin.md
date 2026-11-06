# Discord plugin

Ships in the app as `assets/plugins/discord.json`. Like every plugin it arrives **off with nothing allowed**: turn it on, allow `http:discord.com/api/v10/`, then fill in the bot token.

## Set up

1. Discord developer portal: New Application, then the **Bot** page, then Reset Token and copy it.
2. On the same page turn on **Message Content Intent**, or message text comes back empty.
3. Invite the bot to your server (OAuth2, URL generator, scope `bot`). Give its role only what you want Lily to reach: View Channels, Read Message History, Send Messages.
4. In Lily: Plugins, Discord, paste the token into "Bot token". It is stored encrypted and never shown again or given to Lily.
5. Server and channel ids: Discord, Settings, Advanced, Developer Mode, then right click and Copy ID.

## Tools

| Tool | Does | Asks |
|---|---|---|
| `discord_list_channels` | channels of a server | can be set to "don't ask again" |
| `discord_read_messages` | newest 10 messages of a channel | can be set to "don't ask again" |
| `discord_send_message` | posts a message as the bot (2000 characters) | every time |

The tool set is fixed: there is no delete, kick, ban or role call, and the http layer has no DELETE, so a bot with more rights than needed still cannot be used for those through this plugin. Posted messages cannot ping `@everyone`, `@here` or roles (`allowed_mentions` is sent empty).

## Limits

- **Access is the bot's role.** The plugin has no channel allowlist: the app cannot enforce a setting, so the only real fence is the bot's permissions in Discord. Keep the role small.
- Messages come from server members, so they are untrusted text. Every read is marked as data for Lily, and `discord_send_message` still asks you first.
- Answers are cut at 16 KB, so a long channel list or message history may be cut short.
- No reactions and no server list tool: give Lily the server id.
- The bot token is a high-value secret. If it leaks, reset it on the Bot page.
- Not compiled or run when written. Test: `DiscordPluginTest` (`./gradlew testDebugUnitTest`).
