# Roadmap: Acode leftovers + the knowledge layer

Final plan, 13 phases. Each phase is one small pull request that builds on its own and leaves `main` working.
Done so far: editor core, navigation, preview and settings, and the agent bridge (see `docs/code-editor.md`, `docs/agent-editor.md`), then phases 1 to 12 below, whose code and docs are on `main` (`docs/editor-files.md`, `docs/knowledge-groundwork.md`, `docs/knowledge-data-model.md`, `docs/knowledge-search.md`, `docs/knowledge-embeddings.md`, `docs/knowledge-ask.md`, `docs/workspace-index.md`, `docs/editor-fonts-large-files.md`, `docs/knowledge-context.md`, `docs/sandbox-verify.md`, `docs/research-mode.md`, `docs/remote-workspaces.md`). Phase 13 (`docs/plugins.md`) is written on `feat/plugins` and waits for review. Earlier versions of this paragraph listed some phases as waiting on branches; that was out of date. Whether each phase has been built and its tests run is tracked in the project memory, not here: the sandbox where they were written has no Gradle build. None of the knowledge phases has a screen yet, and neither do plugins or remote workspaces.

## Rules for every phase

- Pure Kotlin logic behind interfaces, with unit tests (an in-memory store stands in for Turso in tests).
- Docs updated in the same pull request.
- The proven-facts database is never touched by the knowledge layer. A note becomes a proven fact only when the user promotes it, with a citation as the evidence. AI-written notes are labelled and are never evidence on their own.
- Knowledge lives in a third Turso database (`knowledgeDb`).

## Phases

| # | Track | Phase | What it delivers |
|---|-------|-------|------------------|
| 1 | Editor | Finish the editor | Replace across files, "Ask Lily" in Commands, new folder, rename and delete files |
| 2 | Knowledge | Groundwork | Several Turso statements in one transaction, schema versions, the `knowledgeDb` setting, a check of Turso's vector and full-text support |
| 3 | Knowledge | Data model | Notebooks, sources (shared library, linked to many notebooks), notes (human, AI, imported), pasted text and web pages as sources |
| 4 | Knowledge | Chunks and keyword search | About 400 token chunks with 15% overlap, line ranges stored, keyword search |
| 5 | Knowledge | Embeddings and hybrid search | An embedding call on the model gateway, the embedding model stored per chunk, meaning search combined with keyword search, rebuild |
| 6 | Knowledge | Ask with citations | Plan searches, retrieve, answer; citations point to a chunk and line range, ones not retrieved are removed; tapping one opens the editor at that line |
| 7 | Both | Workspace code index | Project files indexed and kept current on save; the coding worker finds relevant files beyond the open tabs |
| 8 | Editor | Fonts and large files | Font choice; files over 1 MB open without loading everything |
| 9 | Knowledge | Context levels, chat, insights | Per source: off, insights only or full text, saved; token estimate; saved prompts such as Summary and Key claims |
| 10 | Both | Verify in the sandbox | After an accepted change, run the project's checks in the sandbox and report |
| 11 | Knowledge | Research mode | The research worker searches the web, saves sources, takes notes and writes a cited report |
| 12 | Editor | Remote workspaces | SFTP first, FTPS next, plain FTP only as a last resort that the person is warned about every time. Local copy with pull, conflict-checked push, host key checking. Logic and tests only: no real client library and no screens yet (`docs/remote-workspaces.md`) |
| 13 | Editor | Plugins | Declarative plugins: one JSON manifest, no plugin code ever runs. Tools, prompt commands and snippets, each needing the person's explicit permission. Termux is the first plugin and is not built in (`docs/plugins.md`). Logic, Android adapter and tests only: no screen yet |

## Left out on purpose

Podcasts, speech to text and text to speech, a web UI and REST API, Docker and SurrealDB, a provider matrix (the router handles providers), multi-user and passwords. PDFs, audio and video come later through the sandbox, with the app storing only the extracted text. Plugins that run their own code: a plugin can only ask the app to do something from a short, checked list.

## Open questions

- Which embedding model and vector size (affects storage and which providers work).
- Whether Turso supports full-text search. Phase 4 keyword search does not need it (it ranks in the app), so a database-side path would only be an optimisation; the Check button's answer is still unrecorded.
- Whether the person's real Turso database accepts the SQL from phases 3, 4 and 9 (`CHECK` constraints, a partial unique index, `ON CONFLICT ... DO UPDATE`, multi-row `INSERT`, `INSERT ... SELECT ... WHERE EXISTS ... ON CONFLICT`). It will say so the first time the knowledge migrations and indexing run on it.
- Whether Turso's HTTP API accepts the size of a request that indexes a large source (about 1 MB for a 1 MB source). If not, indexing needs to write in several transactions.
- Whether to add screens for the knowledge layer (notebooks, sources, context levels, ask, chat) before phase 10; none exists yet.
- Which SSH and FTP libraries to add for phase 12 (the real SFTP, FTPS and FTP clients).
- The literal result-bundle key names Termux uses (`result`, `stdout`, `stderr`, `exitCode`); they live in `termux.json` and are unconfirmed (`docs/plugins.md`).
- The plugin screen: install, turn on, allow, and the approve-this-command dialog. Until it exists, plugin tools are refused.
