# Plugins (phase 13)

A small extension system. Termux is the first plugin and is **not built in**: the app has no Termux code, and nothing Termux-related is active until you install the plugin, turn it on and allow it.

This phase is the logic, the Android adapter and their tests. It has no screen yet (see "Not done"), so until the screen exists every command a plugin tries to run is refused.

## What a plugin is

One JSON manifest. **The app never loads or runs code from a plugin.** A manifest can contribute:

- **tools** that the workers can call (this is what Termux uses),
- **prompt commands** (a saved prompt with `{selection}`, `{file}`, `{language}`),
- **snippets** (prefix and body).

Prompt commands and snippets are read, checked and stored, but nothing in the editor uses them yet. Themes and fonts are not part of this phase.

## Why Termux can be a plugin

The app offers one generic ability, `ServiceBridge`: start a service in another installed app with extras the plugin names, and optionally wait for the answer. Which app, class, action, extras and answer keys is all in the manifest, so `app/src/main/assets/plugins/termux.json` is the only place that mentions Termux. The old `TerminalTool` (an unfinished stub that no worker called) is removed and `LilyFactory` no longer registers it.

Three things stay in the app because Android needs them at build time. They are inert without the plugin:

- `uses-permission com.termux.permission.RUN_COMMAND` in `AndroidManifest.xml`,
- the `<queries>` entry for `com.termux` (Android 11+ hides other apps otherwise); a plugin for some other app needs its own `<queries>` entry,
- the unexported `PluginResultReceiver`.

## Manifest

| Field | Rule |
|---|---|
| `schema` | must be `1` |
| `id` | 3 to 64 characters: lowercase letters, digits, dots, hyphens |
| `name`, `description` | one line, no hidden or direction-changing characters |
| `version` | `1.2.3` |
| `author` | optional, up to 60 characters, same character rules as `name` |
| `icon` | optional, one emoji or one or two letters, shown on the plugin's card. No spaces or invisible characters, so a joined emoji is refused. Without it the card shows the first letter of the name |
| `accent` | optional, `#RRGGBB`, the colour of the icon tile. Without it the app's petal colour is used |
| `permissions` | `external-app:<package>` (packages of Lily itself and of Android, `android.*` and `com.android.*`, are refused) or `http:<host>[/<pinned path>]` (see the http capability below) |
| `settings[]` | optional values the person fills in, for the http capability (see below) |
| `tools[]` | `name`, `description`, `params[]` (`name`, `description`, `required`, `maxLength`), `action` |
| `tools[].action` | `kind` is `android-service` or `http`. For `android-service`: `package` (must be in `permissions`), `class`, `intentAction`, `extras`, optional `result`, `timeoutMs` (1 to 300 seconds) |
| `extras` values | text, text list, true/false or a whole number. Text may contain `{param}`; an unknown `{name}` is an error |
| `result` | `pendingIntentExtra`, `bundleExtra`, `outputKey`, and optionally `errorOutputKey`, `exitCodeKey`, `errorCodeKey`, `errorCodeOk`, `errorMessageKey`. Leave it out for fire and forget |

Limits: 64 KB per manifest, 20 tools, 8 params per tool, 20 extras per action. Anything not understood is an error, and every error is listed.

A catalog entry (`plugins/index.json`) may carry the same `author`, `icon` and `accent`. They are only cosmetic, so a bad one is dropped and the entry is still listed.

## The Plugins screen

- **Installed** lists what you have. **All** adds the plugins that ship in the app and the ones the catalog offers, so a plugin you removed can be installed again. The catalog loads when the section opens; its address can be changed on the All tab.
- Each card shows the icon tile, name, version, author and description. The switch turns the plugin on or off. Install and Uninstall are on the card; Uninstall asks first.
- Tapping a card opens its page: what the plugin can do, what it is allowed to, its settings, and the "don't ask again" switches. A plugin that is not installed shows its facts and an Install button.
- Installing always adds the plugin **off with nothing allowed**, from the app, the catalog or pasted JSON.
- Search looks at name, description, author and id.

## What keeps you in charge

- A new plugin is **off and has nothing allowed**. A plugin asks; only you allow.
- An update **never adds** an allowance and drops ones it no longer asks for. "Don't ask again" for a tool survives an update only if that tool is exactly the same and still allowed.
- Every call **checks again**: plugin on, permission allowed, arguments valid. Turning a plugin off, taking a permission back or updating it works immediately, even for tools already handed to the workers.
- **Each run is shown to you first** with every value that will be sent. Hidden and text-direction characters are shown as `\uXXXX`, so what you read is what runs. "Don't ask again" is your choice per tool; a manifest cannot set it. With no confirmation screen, the default gate denies everything.
- Arguments are strict: unknown names are refused, each has a length limit, and a null character is refused. `{param}` values are filled in one pass, so a value that looks like a placeholder stays literal. An optional value that was not given leaves its extra out instead of sending it blank.
- A plugin can never replace one of the app's own tools: a name already taken is skipped.
- Output is capped at 16,000 characters per stream; the end is kept because that is where errors are.
- Plugin files are checked again every time they load. A damaged, oversized, renamed or invalid file is skipped (listed in `DiskPluginStore.problems`) and the others still load. Stored allowances are cut down to what the manifest asks for.

## Termux plugin: what to set up on the phone

1. Install Termux from F-Droid or GitHub (the old Play Store build is out of date).
2. In Termux add `allow-external-apps=true` to `~/.termux/termux.properties`, then restart Termux.
3. Android Settings, Apps, Lily, Permissions, Additional permissions: allow **Run commands in Termux environment**.
4. Keep Lily open on screen while a command runs. Android refuses to start another app's service from the background; Lily reports that clearly.

The plugin runs commands as background commands (`bash -c`), which is what gives separate stdout, stderr and an exit code. Termux itself cuts results to about 100 KB from the start. The answer travels in a `PendingIntent`, which **must be mutable** (Termux adds the result to it); an immutable one comes back with no result.

## Http capability (phase C)

A plugin can send https requests, so a service such as GitHub or Discord can be a plugin. The app writes and sends the request; the plugin only describes it.

- **Permission** `http:<host>` or `http:<host>/<pinned path>`, for example `http:api.github.com` or `http:discord.com/api/v10/`. One exact lowercase host: no port, no wildcard, no IP address, nothing that names a local network. A pinned path means whole path parts: `/api/v10` allows `/api/v10/x` but not `/api/v100`.
- **Tool action** `{"kind":"http","method":"GET|POST|PUT|PATCH","url":"https://...","headers":{...},"body":{...},"timeoutMs":..,"untrusted":false}`. There is no DELETE. The address must be https, on a host the plugin asks for, and its fixed start must be inside the pinned path. The host itself can never contain a placeholder.
- **Placeholders** `{param}` (a tool argument; address and body only), `{setting.name}` and `{secret.name}` (values the person filled in; address, headers and body). A value put in the address is percent-encoded, so it cannot add a path part, a query or another host; an encoded slash, an encoded dot or `..` in the path is refused. The body is a JSON tree the app writes itself, so a quote in a value cannot break out of its string. An optional argument left out drops its field instead of sending it blank. Values are filled in one pass, so a value that looks like a placeholder stays literal.
- **Settings** `settings[]` with `name`, `label`, `description`, `secret`, `required`, `maxLength`. They are filled in on the plugin's page and kept in encrypted storage (`lily_plugin_secrets`), never in the plugin's file. A saved secret is never shown again (a new value replaces it) and is never given to Lily. Uninstalling forgets them.
- **Asking** every request is shown first with the method, the address, the headers and the body, and every `{secret.x}` shown as `[secret:x]`. Only a GET can be set to "don't ask again". Any other method asks every time: the switch is not offered, the registry refuses it, and a hand-edited file that sets it is ignored when the tool runs.
- **Sending** headers `Host`, `Content-Length`, `Transfer-Encoding`, `Connection`, `Cookie` and similar are refused. The connection refuses a host that resolves to a private, local or link-local address, follows no redirect off the allowed host or path (a request that changes something follows none), reads at most 16 KB of the answer and times out (1 to 60 seconds).
- **Answer** starts with `http <status>` (`error: http <status>` when not 2xx). Every secret the plugin holds is removed from the answer and from error messages, in plain and percent-encoded form (a secret under 4 characters is not scrubbed). `"untrusted": true` marks the answer as text written by other people, so Lily treats it as data.

Limits: 10 settings, 10 headers, 100 body values, 4 levels of nesting.

Known limit: the address is checked and the connection then resolves the name again, so a host that answers with a public address first and a private one next could slip past the private-address check. The person has allowed that host by name.

Not verified: nothing here was compiled or run (no Android SDK or reachable Maven repository in my sandbox). Run `./gradlew testDebugUnitTest`; the tests are `HttpGuardTest`, `HttpManifestTest` and `HttpToolTest`.

## Not verified

- **Nothing here was compiled or run.** There is no Gradle build in my sandbox: run `./gradlew testDebugUnitTest`.
- The Termux docs confirm the service class, the action, the `PATH`, `ARGUMENTS`, `WORKDIR` and `BACKGROUND` extras, `RUN_COMMAND_PENDING_INTENT`, and the result keys `err` and `errmsg`. They did **not** give the literal names of the result bundle (`result`) and of `stdout`, `stderr`, `exitCode`; I used the values I remember from Termux's source. They are in the manifest, so if the first real run returns "answered without a result" or an empty output, fix them in `termux.json` and reinstall: no code change.

## Not done

- **The screen**: install from the bundled file or a pasted manifest, list plugins (with `DiskPluginStore.problems`), turn on and off, allow and take back permissions, the "approve this command" dialog, the "don't ask again" switch.
- **Wiring**: create `PluginHost(PluginRegistry(DiskPluginStore(filesDir/plugins)), AndroidServiceBridge(context), gate)` and pass it to `LilyFactory.create(plugins = ...)`. The hook exists; nothing calls it yet. Plugin tools appear the next time Lily is created.
- `Tool` has only a name, so a tool's description and params are not yet given to the planner; until they are, the model only knows a tool exists by name.
- Install from a URL, signing and updates from a store. A manifest is installed from text.
- Prompt commands and snippets have no place in the editor yet.

## Tests

`app/src/test/kotlin/ai/lily/app/plugins/`: the manifest parser (including the bundled Termux manifest and every rejection rule), the registry and stores (install, update rules, revoking, damaged and hand-edited files), and plugin tools against a fake bridge and fake gate (asking, denying, re-checking on every call, argument and placeholder rules, output, failures, registering). The http capability has `HttpGuardTest` (host, path, address, header, redirect and private-address rules), `HttpManifestTest` (the http manifest rules) and `HttpToolTest` (asking, secrets hidden and scrubbed, encoding, JSON body, caps, registering).
