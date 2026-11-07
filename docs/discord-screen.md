# Discord screen: live chat and avatar profile

Step 3 of the Discord viewer. The Discord plugin's **Open** button (step 1, `docs/plugin-screens.md`) shows this screen; it draws what the backend (step 2, `docs/discord-viewer.md`) returns.

Code: `app/src/main/kotlin/ai/lily/app/editor/DiscordScreen.kt` (what is drawn), `DiscordState.kt` (what it does), `app/src/main/kotlin/ai/lily/app/discord/` (pure rules). Tests: `app/src/test/kotlin/ai/lily/app/discord/DiscordLiveAvatarTest.kt`.

## What the person sees

- **Servers and channels**, laid out like the Discord app: servers as chips (one server opens by itself), then categories in capitals with their channels under them. Text and announcement channels open; voice, stage, forum and media channels are listed greyed out.
- **Messages** of a channel, oldest at the top. Messages by the same person within five minutes share one name-and-picture line. Replies and normal messages show text; file names show as `file.png`; embeds show as a count; joins and pins show as a grey system line. **Load older messages** at the top pages back 50 at a time (the screen keeps the newest 500).
- **A composer** at the bottom. Sending always asks first (the plugin's rule); the box is emptied only when the message was posted.
- **Back** closes the open channel into the channel list; Back again leaves the screen. The top bar has the channel list, refresh and close buttons.

## Live chat

Discord's real-time connection (the Gateway) is a websocket, and the plugin may only use https to `discord.com/api/v10/`, so the screen **polls**: every 3 seconds it asks for the messages after the newest one it has (`messages(after = newestId)`, up to 100). It is live chat as far as the person can tell, with up to about 3 seconds of delay.

- **Only when reads do not ask.** Every poll is a read, and a read asks unless the person has switched on "Run without asking each time" for `discord_read_messages` on the plugin's page. If that is off the screen is **not live**: it says so, and refresh loads the newest 50 (and asks). It never turns the switch on by itself.
- **Pauses.** It waits while a question is on screen, and stops while the app is in the background (and resumes when it returns).
- **Slows down.** After 10 polls that found nothing it asks every 10 seconds, and goes back to 3 seconds when something arrives. If Discord says to slow down (429) it waits at least as long as asked.
- **Stops for what only the person can fix** (plugin turned off, permission taken back, token refused, or they said no). A temporary failure shows a banner and keeps trying.
- **New messages while scrolled up** do not pull the view down: a "N new messages" button appears instead. At the bottom, new messages scroll into view.
- **Not caught by polling:** edits and deletions of older messages. Refresh (the top bar) reloads the newest 50, which brings those up to date.

## Avatar profile

- **Pictures** are served from `cdn.discordapp.com`, not `discord.com`, so they need a **second permission**, `http:cdn.discordapp.com/avatars/`, which the Discord plugin (now 1.2.0) asks for. **Updating the plugin never turns a permission on**, so the person has to open the Discord plugin's page in Plugins and allow it once. Until then the screen shows a coloured circle with the person's first letter, and says why.
- Allowing the permission is the consent: avatar requests ask no question each time, carry **no bot token** and no header but a User-Agent, and may only fetch `/avatars/{user id}/{hash}.png`. The id and hash are checked (a hash is lowercase letters, digits and `_`), so nothing can add a path or a query. An answer must be a png of at most 256 KB, or it is dropped. Animated avatars arrive as a still picture.
- At most three pictures load at once, and the screen keeps the latest 300. A failed picture is not retried until refresh.
- **Tap a picture or a name** to open that person's **profile**: a large picture (256 px), display name and `@account name` when different, a BOT badge, their user id (selectable), the date the account was made (an id carries its creation time), how many of their messages are loaded in this channel and when the last one was.
- What a profile cannot show: roles, status, bio, join date in the server. A bot only gets what is in the messages it can read.

## Changed outside the screen

- `HttpCall.binary` and `HttpReply.Done.bytes` (both default off/null): plugin tools are unchanged. Only `DiscordApi.avatar` uses them.
- `DiscordApi`: `avatar(userId, hash, size)`, `avatarsAllowed()`, `AVATAR_PERMISSION`.
- `DiscordMessage` gains `authorAvatar` and `authorUsername` (defaulted, so older code compiles unchanged).
- `PluginServices.discord` builds the `DiscordApi` from the same registry, secrets, http and approval gate that plugin tools use.
- `PluginScreenHost` now takes `services`.
- `discord.json` is 1.2.0. Its tools are unchanged. The installed 1.1.0 is updated on the next app start, keeping what the person decided.

## Limits

- Not true push: up to 3 seconds late, and a request every 3 seconds while a channel is open and reads do not ask. A Gateway websocket would need its own, separate permission and is not part of this.
- Pictures and files in messages (attachments, embeds, custom emoji, stickers) are not shown; only avatars are. They live on other hosts.
- Message formatting (markdown, mentions, replies) is shown as plain text. Mentions look like `<@123>`.
- Threads are not listed; forum channels are not opened.
- One bot, one token, no direct messages.
- Not compiled or run when written; CI on the pull request is the first check.

## Branches

This branch (`feat/discord-screen`) is built on `feat/plugin-open-button` (step 1) and carries the step 2 files too, in their step 3 form. Merge order: step 1, then step 2, then rebase this one onto `main` (the step 2 files will show as changed only where step 3 changed them). The step 2 tests (`DiscordApiTest`, `DiscordParseTest`) live on `feat/discord-api` and still apply.
