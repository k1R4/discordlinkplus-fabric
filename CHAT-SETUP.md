# Add Discord text chat (Fabric 26.3, version 1.2.0)

Version 1.2.0 provides two-way public chat, player-name/avatar webhooks, and server-identity webhooks for join/leave, death, advancement, and server start/stop messages. It uses the **first token** already in `botTokens`. The same bot can keep bridging voice; no additional token is required.

Chat is disabled by default, and existing 1.0.x configurations remain valid. The mod ID and configuration filename stay unchanged. All players can use the chat relay without a client addon, online-mode authentication, or Discord account linking.

## Manual update

1. Stop your server normally. Preserve your existing configuration and voice-channel ownership registry.
2. Replace/disable the old DiscordLink+ JAR and install `DiscordLinkPlus-Voice-1.2.0-fabric.26.3.jar` in `mods/`. Keep only one enabled JAR with mod ID `discordlinkplus_voice`. The `Voice` artifact name is retained for update compatibility; this JAR includes both chat and voice.
3. In the Discord Developer Portal, open the application for the **first bot token** and enable **Message Content Intent** under Bot → Privileged Gateway Intents. This is needed for Discord-to-Minecraft chat. Only that first bot needs it. If you intentionally configure `discordToMinecraft: false`, no message-content intent is requested.
4. Give that bot **View Channel** and **Send Messages** in the chosen Discord text channel. Give it **Manage Webhooks** to show Minecraft names and head avatars. If webhook lookup/creation fails, the mod falls back to ordinary bot messages with the Minecraft name in the text. Correct permissions and reload to retry webhooks.
5. Add the following `chat` object at the top level of your existing `config/discordlinkplus-voice.json`. Insert a comma after the preceding field. Replace the channel placeholder with the **text channel ID**, not a category or voice channel ID. Preserve your existing `enabled`, `guildId`, `groupCategoryId`, and `botTokens` values.

```json
"chat": {
  "enabled": true,
  "channelId": "PASTE_TEXT_CHANNEL_ID_HERE",
  "minecraftToDiscord": true,
  "discordToMinecraft": true,
  "useWebhooks": true,
  "avatarUrl": "https://mc-heads.net/avatar/%texture%/128",
  "minecraftToDiscordFormat": "**%player%**: %message%",
  "webhookMessageFormat": "%message%",
  "discordToMinecraftFormat": "[Discord] %user%: %message%",
  "joinLeaveMessages": true,
  "deathMessages": true,
  "advancementMessages": true,
  "serverName": "Minecraft Server",
  "serverMessages": true,
  "serverStartedMessage": "Server started.",
  "serverStoppedMessage": "Server stopped."
}
```

6. Put your normal 64×64 PNG `server-icon.png` in the **server root**, beside `server.properties`. This is read on the server and uploaded as the webhook's default avatar. You can set `chat.serverName` to a name such as `Axolotl`. Restart or reload to synchronize a changed icon.
7. Start the server. `/discordlink status` reports voice state, text-channel connection mode, counters, queued/dropped messages, and delivery errors. `/discordlink reload` applies configuration changes. The old `/discordvoice status` and `/discordvoice reload` commands remain aliases. These commands require operator level 3 or the server console; reload briefly reconnects the bot/voice bridge.
8. Send a normal public message in Minecraft, then a human message in the configured Discord channel. Confirm player names/avatars, inbound text, event toggles, and existing group voice audio.

The full disabled example at [examples/discordlinkplus-voice.json](examples/discordlinkplus-voice.json) is for new installations. **Do not replace an existing working config with that blank template.**

## Offline mode and Skin Restorer

The relay reads the player's **current GameProfile textures on every chat message**. Skin Restorer 2.11.0 replaces those properties when a player changes their skin, so the next webhook message uses the updated texture hash. It does not try to map an offline UUID to a premium account. Existing Discord messages retain the avatar used when they were posted.

The default avatar URL renders ordinary Mojang-hosted skin textures, including skins applied by Skin Restorer's Mojang/MineSkin providers. A missing/unreadable texture falls back to a UUID lookup, which can produce a default avatar for an offline player. Non-Mojang-hosted textures such as some Ely.by configurations require an avatar provider/template appropriate to those skins; they are not rendered by the default hash-based URL.

`avatarUrl` supports `%texture%` (active Mojang texture hash, otherwise UUID), `%uuid%`, and `%player%` (URL-encoded username). Set it to an empty string to omit per-message avatars. Skin Restorer remains optional; no new mod dependency is required. This is the [Skin Restorer mod by Suiranoil/Lionarius](https://modrinth.com/mod/skinrestorer), distinct from the SkinsRestorer plugin.

## Scope and controls

- Normal accepted **public player chat** is relayed. Private/team messages, commands, console output, action-bar messages, and fully filtered chat are excluded. Partial filter masks are respected.
- Discord input accepts human messages from exactly the configured server and text channel. Bot and webhook messages are ignored to prevent loops. Uploads appear in order as `[Image]`, `[GIF]`, `[Video]`, or `[File: name]`, preserving accompanying text. Labels are plain text and do not expose signed upload URLs. MIME types determine the category; missing/generic types fall back to the filename extension. Filenames are cleaned and limited to 64 characters. The 900-character incoming body limit reserves room for upload labels before truncating a long caption.
- Recognized Tenor `/view/` and Giphy `/gifs/` links, plus direct media links on their known media hosts, appear as `[GIF]`. Ordinary links (including GIFs hosted elsewhere) remain intact. No links are fetched or redirects followed.
- Incoming text is sent as literal Minecraft system chat. A message beginning with `/` is displayed and never executed. Player-supplied colour/control codes are removed.
- Outgoing messages disable Discord mentions, including `@everyone`, `@here`, user and role pings. Custom message formatting does not enable mentions.
- The reused bot-owned webhook is named `MC Link Chat`. Player chat overrides its name/avatar with the player's identity. Every forwarded non-player announcement uses `chat.serverName` and the webhook's default avatar uploaded from `server-icon.png`. A preceding player message cannot change the next announcement's avatar. Webhooks are reused across restarts, not deleted/recreated for each message. If webhooks are explicitly disabled or unavailable, ordinary bot-message fallback still applies; status identifies fallback and missing/unreadable icons.
- `minecraftToDiscord` also gates event announcements. Each event category has its own toggle. `discordToMinecraft` independently controls the opposite direction.
- `minecraftToDiscordFormat` supports `%player%` and `%message%` and applies to bot fallback. `webhookMessageFormat` supports the same placeholders; the webhook already displays the player name. `discordToMinecraftFormat` supports `%user%` and `%message%`. User text is inserted once and cannot expand additional placeholders.
- Network operations run off the Minecraft thread. Both directions have bounded queues of 100 messages and discard entries older than one minute. Text is length-limited; status counters expose drops/errors. Undeliverable messages are not retried automatically because an uncertain network timeout could otherwise duplicate a message.
- Existing voice configuration remains independent: top-level `enabled` controls voice, `chat.enabled` controls text. Text-only mode uses the shared guild/token and does not require a voice category, but this combined mod still requires SVC 2.6.27 installed.
- No account-linking login gate, remote console, command relay, or automatic server deployment is included.

## Validation

The unchanged baseline has 39 automated tests covering voice, real JDA event/builder handling with in-memory interfaces, webhook identity/mentions, permission fallback, event/channel filters, bounded queues, Unicode, old-config compatibility, and changing skin properties for an offline UUID. Attachment-label regression tests additionally cover mixed and attachment-only uploads, more than five uploads, missing MIME types/extensions, long filenames/captions, GIF-picker links, punctuation, and preservation of ordinary/lookalike URLs. Run `.\gradlew.bat build --console=plain` for the current suite.

Attachment labels still require a live Discord round trip on a disposable server. Use a separate test bot/channel and private runtime config, send a caption with image/GIF/video/file uploads, an attachment-only message, Tenor/Giphy picker messages, and an ordinary link. Confirm the Minecraft text matches the labels above, then send a Minecraft player message back to Discord and check existing webhook name/avatar behavior. Check voice group creation and audio in both directions before treating this checkpoint as runtime-validated. These new attachment scenarios have only been exercised by automated tests so far.

The packaged JAR also passed local offline Fabric 26.3 startup with Skin Restorer 2.11.0 and the existing native voice probe. A disposable local test applied two skins through Skin Restorer's real skin service and verified the next chat event picked up each new hash, private/filtered chat was excluded, join announcements were captured, and literal inbound messages did not echo. No live Discord messages were sent during testing. Verify the real Discord round trip after manual installation. Axolotl was not modified or restarted for this release.

Version 1.1.1 fixes webhook creation: Discord disallows names containing `discord` or `clyde`, so the reusable webhook is now named `MC Link Chat`. Player names containing those reserved strings use bot-message fallback to preserve the displayed name. `/discordlink status` now shows the configured channel/directions, Minecraft chat event count, Discord event/channel-match counts, ignored bot/webhook counts, and sanitized Discord error codes for webhook setup or message delivery. Zero delivered-message counters alone do not identify a permission problem.

## Server startup and shutdown

With `chat.serverMessages` enabled (default), `Server started.` is queued once after actual server startup and sent when the bot connects. Reloading config or reconnecting the bot does not create another startup notice. On a normal shutdown, `Server stopped.` takes priority over queued chat and gets up to five seconds to send before the shared bot disconnects. A forced kill, crash, unreachable Discord, or an already blocked network request can prevent delivery. Config reloads do not post a stopped notice. `minecraftToDiscord: false` disables these notices as well.

Version 1.2.0 adds regression coverage for uploading the server icon on both new and existing webhooks, all selected non-player event categories using the server identity, unchanged player avatar overrides, startup/shutdown ordering, bounded queue priority, no stopped notice on ordinary reload-close, and malformed/missing icon fallback. The patch is supplied for manual installation; Axolotl was not modified.
