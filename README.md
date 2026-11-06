# LilyAi

LilyAi is a native Android app (Kotlin, Jetpack Compose) that puts an AI agent next to a code editor. You describe what you want; **Lily** plans it, hands the parts to **workers**, and shows you each proposed change to accept or reject. Models come from your own self-hosted router, so no provider key lives in the app.

## What it does

The app has six sections, in the bottom bar (or a side bar on wide screens):

| Section | What is there |
|---|---|
| **Work** | The code editor with **Ask Lily**. Lily sees your open tabs (including unsaved typing) and the project, proposes edits as diffs, and nothing is written until you accept. |
| **Chat** | Free chat with Lily, and a **task queue**: add tasks and Lily works through them in the background (a foreground service keeps them running), stopping when something needs you. |
| **Browse** | A built-in browser: search or type an address, with a start page. |
| **Plugins** | Install, turn on and allow plugins. Cards, a detail page, settings, and an approval dialog before any plugin acts. |
| **AI** | Lily's profile (name, photo, instructions, planning model), her workers, router configuration and the model list. |
| **Settings** | Router, Turso databases, Pandastack and editor preferences, stored encrypted on the device. |

### The editor

- Tabs with unsaved-typing markers, undo and redo per file, find and replace (regex optional), go to line, smart indent and bracket pairing, and quick tool rows above the keyboard.
- Syntax colours for about 25 languages, quick open, a command palette, search and replace across the project.
- Live preview for HTML, SVG, Markdown and JavaScript with a console, six colour themes, font choice, large-file viewing, and recovery of unsaved typing after the app is killed.
- Remote workspaces over SFTP, FTPS or (as a warned last resort) plain FTP, with a local copy, conflict-checked push and host key checking.

### What Lily can do

- **Edit code.** The coding and file workers ask the model for exact find-and-replace edit blocks, apply them all-or-nothing against what you see, and hand you proposals. Accepting puts a change in the tab as one undo step.
- **Find relevant files.** A workspace index (BM25 over code chunks) adds up to four related files beyond your open tabs.
- **Verify.** After a change, the project's own checks (npm scripts, Gradle, pytest, `go test`, `cargo test`, `make test`) can run in a Pandastack sandbox and report back.
- **Research.** Search the web, read pages through a guarded fetcher, keep them as sources and write a cited report.
- **Remember.** A Journal of requests and results, a Proven facts store that only takes verified statements, and a knowledge layer (notebooks, sources, keyword and meaning search, answers with checked citations).
- **Manage a team.** Lily can create and dismiss workers, each with its own role, instructions, router and model.
- **Use git.** The Git worker runs `git_*` tools from the Git control plugin (through Termux); you approve each command.

## Architecture

```
You -> Lily -> Planner -> Worker -> Tools / Model Gateway -> Result
```

- **Lily** (`core/`) orchestrates. `LlmPlanner` splits a request into tasks; `WorkerManager` hands each to a worker.
- **Workers** (`workers/`): Coding, File, Research, Build, Git, General, and a manager role that creates other workers. You can define your own workers in the AI section. A worker never calls a model API itself.
- **Model Gateway** (`gateway/`) is the only way to a model. It talks to your router (FreeLLMAPI or 9Router; two routers can be set up) using selectors such as `auto`, `auto:fast` and `auto:smart`. The router chooses the model and handles web search.
- **Tools** (`tools/`): web search, sandbox, the editor bridge, and any tool a plugin adds. A plugin can never replace one of the app's own tools.
- **Memory** (`memory/`): three Turso databases over its HTTP API. **Journal** (raw history), **Proven facts** (verified statements with evidence) and **Knowledge** (notebooks, sources, chunks, vectors). A note becomes a proven fact only when you promote it with a citation.
- **Sandbox** (`sandbox/`): Pandastack client, fact verifier and project verifier.
- **Plugins** (`plugins/`): a plugin is one JSON manifest and the app never runs plugin code. Everything a plugin can do goes through a bridge the app provides (start a service in another app, or send an https request), only after you turn the plugin on and allow each permission, and each call is shown to you first. Termux and Git control ship with the app, off by default.
- **Shell and UI** (`shell/`, `editor/`, `ui/`): the sections, the chat and task queue, the editor screens and the shared design components.

Rules the code holds to:

- Workers only reach models through the Model Gateway.
- Workers never write files: they hand proposals to the editor, and you accept or reject.
- Only verified statements enter Proven facts.
- A plugin is data. It starts off with nothing allowed, secrets are kept encrypted and never shown to Lily, and any request that changes something asks every time.

## Project structure

```
LilyAi/
|- app/src/main/kotlin/ai/lily/app/
|   |- core/       Lily, planner, tasks, worker manager
|   |- workers/    Coding, File, Research, Build, Git, General, team manager, worker profiles
|   |- tools/      Tool system, web search, sandbox, editor bridge
|   |- gateway/    Model Gateway, router endpoints, embeddings, model list
|   |- memory/     Turso client, Journal, Proven facts, knowledge layer, search
|   |- codeindex/  Workspace code index
|   |- research/   Research mode, guarded page fetcher
|   |- sandbox/    Pandastack client, fact and project verification
|   |- remote/     Remote workspaces (SFTP, FTPS, FTP)
|   |- plugins/    Manifest parser, registry, host, http capability, secrets
|   |- editor/     Editor screens, plugin screens, remote dialogs
|   |- shell/      Sections, chat, task queue, browser, AI pages
|   |- ui/         Theme and shared components
|   |- settings/   Encrypted app settings
|   |- lib/editor/ Editor logic in plain Kotlin
|- app/src/main/assets/plugins/   Bundled plugins (Termux, Git control)
|- app/src/test/                  Unit tests
|- docs/        One page per feature
|- .github/workflows/             build.yml, release.yml
```

## Setup

Open the app, go to **Settings**, and enter:

- Router URL (ending in `/v1`) and your router's unified key (a second router is optional)
- Turso URL and token for the Journal and Proven facts databases, and for the Knowledge database if you use it
- Pandastack API key (and URL if self-hosted)

Keys are stored encrypted on the device and never committed to Git. Plugins are off until you turn them on in **Plugins** and allow what they ask for.

## Build and release

Builds run on GitHub Actions.

- Every push to `main` or `design/**` and every pull request runs `gradle assembleDebug testDebugUnitTest`.
- Push a tag like `v0.1.0` to build a release APK and attach it to a GitHub release.
- To sign releases, add the repository secrets `KEYSTORE_BASE64`, `KEYSTORE_PASSWORD`, `KEY_ALIAS` and `KEY_PASSWORD`.

## Status

Under active development. CI builds the app and runs the unit tests. Not everything has a screen yet: the knowledge layer (notebooks, ask with citations) and research mode are logic with tests but no screens, and the knowledge, research and remote docs record what has not been tried against real services. Planned next: GitHub and Discord plugins. See [docs/ROADMAP.md](docs/ROADMAP.md) and [PLAN.md](PLAN.md).

## Docs

[ARCHITECTURE.md](ARCHITECTURE.md) · [docs/plugins.md](docs/plugins.md) · [docs/agent-editor.md](docs/agent-editor.md) · [docs/code-editor.md](docs/code-editor.md) · [docs/router.md](docs/router.md) · [docs/memory.md](docs/memory.md) · [docs/pandastack.md](docs/pandastack.md) · [docs/sandbox-verify.md](docs/sandbox-verify.md) · [docs/workspace-index.md](docs/workspace-index.md) · [docs/research-mode.md](docs/research-mode.md) · [docs/remote-workspaces.md](docs/remote-workspaces.md) · [docs/knowledge-ask.md](docs/knowledge-ask.md)

## License

See [LICENSE](LICENSE).
