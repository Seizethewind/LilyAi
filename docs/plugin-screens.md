# Plugin screens and the Open button

A manifest may carry one optional field, `screen`, naming one of the app's own screens:

```json
"screen": "discord"
```

The names the app knows are in `ManifestParser.SCREENS` (only `discord` for now). Any other value, or a value that is not text, makes the manifest invalid. The field adds no code, no permission and no ability: it only says which screen the Open button shows. A plugin cannot bring its own screen.

## The Open button

It appears on the plugin's card and on its page when the plugin is **installed, turned on, and names a screen**. It shows that screen in place of the plugin list, and Back (or the phone's back) returns to the list. Turning the plugin off hides the button. The `discord` screen is a placeholder until the channel viewer is built (see below); `PluginScreens.kt` is where each screen is chosen.

## Bundled plugins are kept up to date

A plugin that ships in `assets/plugins` is installed once, off, with nothing allowed. Until now an installed copy was never touched again, so a new field in a bundled manifest could not reach a phone that already had the plugin. Now `PluginServices.installBundled` also replaces an installed bundled plugin when the app ships a **higher version** of the same id (`BundledUpdate.isNewer`; both versions must look like `1.2.3`, anything else counts as not newer).

The replacement is the registry's ordinary update, so the rules in `docs/plugins.md` hold: what the person decided is kept (on or off, allowed permissions), an update never adds an allowance, and "don't ask again" survives only for a tool that is exactly the same and still allowed. Saved settings such as the Discord bot token are not touched. A plugin the person uninstalled is still not put back.

One side effect to know: a copy of a bundled plugin that the person pasted in by hand with the same id and a lower version is replaced by the bundled one.

The Discord manifest is `1.1.0` for this reason: it adds `screen`. Its tools are unchanged, so "Run without asking each time" settings stay.

## Not done yet

- The Discord viewer itself (server and channel lists, messages, posting). Planned as two more steps: a backend that talks to Discord with the saved bot token, then the screen.
- Not compiled or run when written; CI on the pull request is the first check. Tests: `PluginScreenTest` (`./gradlew testDebugUnitTest`).
