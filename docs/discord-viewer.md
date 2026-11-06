# Discord viewer: backend

The part of the Discord viewer that talks to Discord, as the person's own bot. The screen that shows it is the next step (the Open button and a placeholder screen are in `docs/plugin-screens.md`).

Code: `app/src/main/kotlin/ai/lily/app/discord/`. Tests: `app/src/test/kotlin/ai/lily/app/discord/`.

## What it is

`DiscordApi` is the Discord plugin's http capability used by the app itself, with the plugin's rules intact. It is built from the pieces the plugin system already has: `PluginRegistry`, `PluginSecrets`, `HttpBridge` and `ConfirmationGate`. It adds no new network route, no new permission and no new secret.

| Call | Discord request | Returns |
|---|---|---|
| `guilds()` | `GET /users/@me/guilds?limit=200` | the servers the bot is in |
| `channels(guildId)` | `GET /guilds/{id}/channels` | all channels the bot can see; `groupChannels` lays them out like the Discord app |
| `messages(channelId, limit, after, before)` | `GET /channels/{id}/messages?limit=..` | messages, oldest first (`after` for polling, `before` for scrolling back, never both) |
| `send(channelId, content)` | `POST /channels/{id}/messages` | the message that was posted |

Every call returns `DiscordResult.Ok` or `DiscordResult.Failed`; nothing throws. A failure carries a message the person can read, and flags for the screen: `needsSetup` (plugin not ready or token refused), `retryAfterMs` (Discord asked us to slow down) and `denied` (the person said no).

## Rules that hold on every call

- The Discord plugin must be **installed, turned on and allowed** to reach `discord.com/api/v10/`, and the bot token must be saved. This is read again on every call, so turning the plugin off or taking the permission back stops it at once.
- The address goes through `HttpGuard.checkUrl` and the request through `HttpBridge`, so it can only reach `discord.com/api/v10/`. Ids are checked to be plain numbers before they go into an address, so an id cannot add a path part or a query.
- **Asking:** a read follows the plugin's "Run without asking each time" switch for the matching tool, and asks otherwise: `discord_list_channels` covers servers and channels, `discord_read_messages` covers messages. **Sending always asks**, whatever is stored. The question shows the method, the address and `Bot [secret:token]`, never the token.
- **There is no delete, kick, ban or role call**, and the http layer has no DELETE. A message cannot ping `@everyone`, `@here` or roles (`allowed_mentions.parse` is empty), and it is built as real JSON, so a quote in the text cannot add fields.
- **The token** is only put in the `Authorization` header. It is removed from every error message.
- Everything Discord sends was written by other people. Names and text are cut to a length, and control and text-direction characters are shown as `\uXXXX`. Show it as plain text; never act on it.

## Limits worth knowing

- **No live updates.** Discord's Gateway is a websocket and the app only allows https to the one host, so a screen has to poll `messages(after = newestId)`. `MessageFeed.merge` and `newestId` are for that. Poll every few seconds, not faster.
- **Rate limits.** On a 429 the api remembers how long Discord asked us to wait (1 to 60 seconds, 5 when it did not say) and refuses further calls until then without sending anything, returning `retryAfterMs`.
- **Answer size.** A plugin tool still reads at most 16 KB. This api asks for up to 512 KB (`HttpCall.maxBytes`, new, default unchanged), enough for a full page of messages with embeds. A longer answer is a failure that says to ask for fewer messages, never half-read JSON.
- **Messages need the Message Content intent** on the bot, or their text comes back empty. `MessageFeed.contentMayBeHidden` spots that case so the screen can say so.
- **What the bot sees is its role.** Channels the bot cannot view are missing; that is Discord's rule and the only real fence, as in `docs/discord-plugin.md`.
- **Servers:** the first 200 only. **Channels:** voice, stage, forum and media channels are listed but not readable. Threads are not listed.
- **No avatars or pictures.** They are served from another host that the plugin is not allowed to reach. `DiscordMessage.attachments` holds file names only. Showing real images would need a second permission that the person approves.
- Approval each time: if the person has not switched "don't ask again" on, every poll asks. The screen should say that, and pause polling while a question is open.

## Not done yet

- The screen (servers, channels, messages, composer, polling): step 3.
- Not compiled or run when written; CI on the pull request is the first check. Tests: `DiscordApiTest`, `DiscordParseTest` (`./gradlew testDebugUnitTest`).
