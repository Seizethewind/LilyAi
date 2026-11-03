# Gap audit, phases 1 to 13 (2026-11-03)

Built from each phase's own doc, the project memory, and a read of `main` (`LilyFactory`, `EditorActivity`, `AppSettings`, the folder listings, `.github/workflows/build.yml`). I did not read `SettingsActivity` or the worker files beyond what the docs say. Nothing was compiled or run.

## The main finding

Phases 3, 4, 5, 6, 9, 10, 11, 12 and 13 are **logic and tests with no way in**. Only phases 1, 2 (settings), 7 and 8 reach the person. Concretely, on `main`:

- `LilyFactory.create` builds the router gateway, `WebSearchTool`, `SandboxTool`, the workers, the workspace code index (phase 7) and Turso memory. It builds **none** of: the knowledge repository, indexer, embedder, hybrid search, asker, context builder, chat, research runner, sandbox verifier, remote mirror, plugin host.
- `ResearchWorker` accepts a runner, but the factory never gives it one, so it behaves as before phase 11.
- `EditorActivity` fixes the workspace to `filesDir/workspace`, so a remote workspace cannot be opened.
- `AppSettings` holds the router, journal, facts, Pandastack and knowledge databases. It has **no** embedding endpoint or tag, no check commands for verify, no remote profiles, no plugin state.
- No screen exists for notebooks, sources, context levels, insights, chat, asking with citations, research, verify results, remote profiles or plugins.

## Read first: is `main` green?

`build.yml` runs `gradle assembleDebug testDebugUnitTest` on pushes to `main` and on pull requests. Docs for phases 4 to 11 each say "not compiled, expect small compile errors", and the project memory still lists phases 5 to 12 as "not merged" although their code is on `main`. I cannot read GitHub Actions. **Check the Actions tab for `main`.** If it is red, fixing it comes before everything else, because every later phase stacks on it. Note that a branch with no pull request is not built (the workflow has no branch trigger), so `feat/plugins` and `docs/gaps` are unbuilt until a PR is opened; I have no PR tool.

## Per phase

| # | Phase | What exists | What is missing or unproven |
|---|-------|-------------|------------------------------|
| 1 | Editor finish | Replace across files, new folder, rename, delete, Ask Lily in Commands. | Never run on a device. No change here needs new work unless CI or testing finds bugs. |
| 2 | Groundwork | Transactions over Turso, schema migrator, `knowledgeDb` setting, Check button. | **The Check result (vector search, full text, transactions) has never been recorded.** It decides whether meaning search can move into the database. |
| 3 | Data model | Notebooks, sources, links, notes, citations, repository, real-SQLite tests. | Not tried on real Turso: `CHECK`, partial unique index, `ON CONFLICT DO UPDATE`, `INSERT OR IGNORE ... SELECT`. First migration on the real database will say. No screen. |
| 4 | Chunks and keyword search | Chunker (400 tokens, 15% overlap, line ranges), indexer, BM25. | Nothing calls `KnowledgeIndexer`: sources are not chunked when added. A 1 MB source is about one 1 MB request, untested against Turso. Accent-folding and 500-candidate limits are known and deliberate. Notes are not searched. |
| 5 | Embeddings and hybrid search | Embedding client for two routers with tags, vector storage, indexer, hybrid search with rank fusion. | **Endpoint settings are not wired** (address, key, model id, tag), not in `AppSettings`, not in the encrypted store, no screen. Never sent to a real 9Router or FreeLLMAPI. Embedding model id and vector size not chosen. Meaning search compares at most 1,500 vectors in the app; a larger library needs in-database vector search. A 32-vector batch (about 260 KB) untested against Turso. |
| 6 | Ask with citations | Query planning, retrieval, numbered excerpts, citation checking, save as AI note. | **No viewer for a source's text**, so tapping a citation cannot open anything (the roadmap promised this). No streaming, no follow-ups (phase 9 adds them), notes are not searched. Real models may not follow the citation format. |
| 7 | Workspace code index | In-memory BM25 index of the project, used by the coding worker, wired in `LilyFactory`. | Not persisted: each editor session rebuilds it (untimed on a phone for 3,000 files). Keyword only. Files over 8,000 characters are never added. |
| 8 | Fonts and large files | Font choice and import, read-only viewer for files up to 512 MB, wired in `EditorActivity`. | **No fonts ship with the app** (only the phone's three families plus ones you add). Large files are read-only. The viewer, the font section and the picker have **no tests** and are the likeliest place for layout bugs. Variable fonts untested. |
| 9 | Context levels, chat, insights | Per-notebook source levels (off, insights, full), token estimate, saved prompts, insights with stale detection, chat with follow-up rewriting. | **No screens** for any of it, and nothing calls it. Long sources are cut at 24,000 characters (no map-reduce). "Shorter" or "translate it" does not work on a previous answer. Real-model behaviour untested. |
| 10 | Verify in the sandbox | Check detection, secret-safe upload as base64 over exec, one-sandbox run, report. | **No button or hook** after Accept or Save, no settings field for custom commands. No retry of a failed fix, no recording a pass as a proven fact. Unproven: Pandastack accepts a 100,000-character exec, and the image has sh, base64, timeout and the toolchains. **This Android project itself cannot be verified there** (no SDK in the default template), so its Gradle check would fail. |
| 11 | Research mode | Planner, model-based search, guarded fetch (SSRF rules, redirect checks, size caps), extractor, notebook, cited report. | **Not wired in `LilyFactory`** (needs knowledge database, embedder, tag). No screens, no PDFs, no per-source notes. **DNS rebinding is not closed** (the guard and the connection resolve separately). Hidden page text can still reach stored sources. The network path (`UrlConnectionTransport`, `SystemHostResolver`, `ModelSearchProvider`) has no test. Quality of addresses from the router's search model unknown. |
| 12 | Remote workspaces | Protocol order (SFTP, then FTPS, plain FTP only as a warned last resort), host key policy, mirror with pull, conflict-checked push, path safety. | **No real SFTP, FTPS or FTP client**: it needs an SSH library and an FTP library with TLS (a decision for you). No screens (profile, password, host key dialog, FTP warning, banner, conflicts, pull and push). Host keys and profiles are not saved on disk. Save does not push. Rename, new folder, delete are not sent to the server. `EditorActivity` cannot switch workspace. |
| 13 | Plugins | Manifest, strict parser, registry and stores, plugin tools with per-call checks, Android service bridge, bundled Termux manifest, tests. | **No screen** (install, on/off, allow, approve-this-command), and `PluginHost` is not created anywhere, so every plugin command is refused. Termux result-bundle key names are unconfirmed. `Tool` has only a name, so the planner never sees a plugin tool's description or params. Prompt commands and snippets are stored but unused. No themes, fonts, URL install or signing. Branch `feat/plugins` is unbuilt. |

## Gaps that cut across phases

1. **No composition root.** Several phases each say "the caller builds X from the same tag and settings". One place should build the knowledge stack, research runner, verifier, remote mirror and plugin host from settings, and hand them to `LilyFactory` and the screens.
2. **Settings are incomplete** for phases 5, 10, 12 and 13 (see above), and the encrypted store has no home yet for host keys, remote profiles or plugin state beyond what phase 13 keeps in its own folder.
3. **No screens for the knowledge layer at all**, and no source viewer. This is why phases 3 to 6, 9 and 11 are not usable.
4. **Turso facts still unknown**: the Check result, whether 1 MB and 260 KB requests are accepted, whether the SQL in phases 3, 4, 9 is accepted. One session of running the real migration and Check would answer most of these.
5. **Nothing has been seen running on a phone**, including the editor itself.
6. **`BuildWorker` is still a stub** (its own comment: "TODO: read workflow runs"); it only asks the model. Builds run in GitHub Actions but Lily cannot read results.
7. **Stale docs**: `docs/agent-editor.md` still lists sandbox checks, rename and delete, and Ask in the palette as "not done"; `docs/code-editor.md` still lists fonts, large files, SFTP and plugins as not done; the project memory still lists phases 5 to 12 as unmerged. `docs/ROADMAP.md` is corrected in the plugins PR.
8. **`main` has moved** since the plugins branch was cut (`101b3d7`, now `4f22e7c`). Check `feat/plugins` for conflicts, especially `LilyFactory.kt`, `AndroidManifest.xml`, `ARCHITECTURE.md` and `docs/ROADMAP.md`.

## Decisions only you can make

- Embedding model id, vector size and the shared tag for the two routers.
- Which SSH library (SFTP) and FTP-with-TLS library to add for phase 12.
- Whether to approve extra phases for screens and wiring (see order below).
- Whether Pandastack should get a template with the Android SDK, or this project's verify uses custom commands.
- Whether to ship a few open-licence fonts (check each licence).

## Suggested order

0. Check CI on `main`; open a PR for `feat/plugins` and read its build; fix real compile or test failures first.
1. **Settings and composition root**: embedding endpoints (phase 5), check commands (10), plugin host, one factory for the knowledge stack. Small, unblocks everything.
2. **Plugin screen and wiring** (13): install the Termux manifest, allow it, approve commands, run one real command. Smallest path to a working feature the person asked for, and it proves the Termux keys.
3. **Run Check and the real migrations** against your Turso, record the answers in the roadmap.
4. **Knowledge screens** (3, 4, 5, 6, 9): notebooks, add source (which calls the indexer and embedder), ask with citations, a source text viewer so citations open, then levels, insights and chat.
5. **Verify button** (10) after Accept, then **research screen** (11) with the DNS rebinding fix.
6. **Remote workspaces** (12): library choice, real clients, screens, workspace switching, push on save.
7. Polish: ship fonts, persist the code index, make planner see tool descriptions, fix stale docs.
