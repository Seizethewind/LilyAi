# LilyAi

An Android codebase ready to be built: a Kotlin-native AI agent app that runs on your phone and talks to your own self-hosted router.

Project layout mirrors [Acode](https://github.com/Acode-Foundation/Acode), rebuilt for native Android.

## How it works

User → Lily → Planner → Worker → Tools / Model Gateway → Result

- **Lily** orchestrates. The **Planner** splits a request into tasks, and the **Worker Manager** hands each task to a worker (Coding, Research, Build, Git, File, General).
- Workers never call model APIs. They use the **Model Gateway**, which talks to the self-hosted router ([FreeLLMAPI](https://github.com/Seizethewind/freellmapi)). The router picks the model and handles web search.
- **Memory** is two Turso databases: a **Journal** (raw history) and **Proven facts** (only statements verified with evidence).
- **Pandastack** test-runs code in a sandbox. A fact is promoted only if the run passes.
- **Termux** is used as an external terminal (stub for now).

Full details: [ARCHITECTURE.md](ARCHITECTURE.md) · [PLAN.md](PLAN.md) · [docs/router.md](docs/router.md) · [docs/memory.md](docs/memory.md) · [docs/pandastack.md](docs/pandastack.md)

## Project structure

```
LilyAi/
|- app/src/main/kotlin/ai/lily/app/
|   |- core/      Lily, Planner, Context, Memory, WorkerManager
|   |- workers/   Coding, Research, Build, Git, File, General
|   |- tools/     Tool system: web search, sandbox, terminal
|   |- gateway/   Model Gateway to the router
|   |- memory/    Turso client, Journal and Proven facts stores
|   |- sandbox/   Pandastack client, fact verifier
|   |- settings/  Encrypted app settings
|- app/src/main/res/      Layouts, strings, icons
|- app/src/main/assets/www/
|- docs/        Router, memory and Pandastack notes
|- utils/ hooks/ tests/ fastlane/
|- .github/workflows/     build.yml, release.yml
```

## Setup

Open the app, go to **Settings**, and enter:

- Router URL (ending in `/v1`) and your router's unified key
- Turso URL and token for the Journal database, and the same for the Proven facts database
- Pandastack API key (and URL if self-hosted)

Keys are stored encrypted on the device and never committed to Git.

## Build and release

Builds run on GitHub Actions.

- Every push to `main` and every pull request runs a debug build and unit tests.
- Push a tag like `v0.1.0` to build a release APK and attach it to a GitHub release.
- To sign releases, add the repository secrets `KEYSTORE_BASE64`, `KEYSTORE_PASSWORD`, `KEY_ALIAS` and `KEY_PASSWORD`.

## Status

Early scaffold. The core, workers, gateway, memory and sandbox clients are in place but untested. Still to do: streaming, Termux terminal tool, file and git tools, and the chat UI. See [PLAN.md](PLAN.md).

## License

See [LICENSE](LICENSE).
