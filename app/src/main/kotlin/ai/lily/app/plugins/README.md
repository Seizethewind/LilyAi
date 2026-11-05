# plugins

Plugin system (counterpart of Acode's `src/plugins`), phase 13. See `docs/plugins.md`.

- `PluginManifest.kt`, `ManifestParser.kt`: what a plugin declares and the strict check of it. Plugins are data only; no plugin code ever runs.
- `PluginStore.kt`, `PluginRegistry.kt`: installed plugins and the person's decisions (off by default, nothing allowed by default).
- `PluginHost.kt`: puts allowed plugin tools into the tool system; every call re-checks, asks the person, and goes through a `ServiceBridge`.
- `AndroidServiceBridge.kt`: the one Android class. It starts another app's service as the manifest says and waits for the answer.
- `PluginCatalog.kt`, `PluginListing.kt`: the catalog file, and the merge of bundled, catalog and hand-added plugins that the Plugins screen lists.
- `Visible.kt`: makes hidden characters visible so the person sees what really runs.

The optional Termux plugin is `app/src/main/assets/plugins/termux.json`. Nothing in this folder mentions Termux.

A manifest and a catalog entry may also carry `author`, `icon` and `accent`. They only change how the plugin's card looks.
