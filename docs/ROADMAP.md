# Roadmap: Acode leftovers + the knowledge layer

Final plan, 13 phases. Each phase is one small pull request that builds on its own and leaves `main` working.
Done so far: editor core, navigation, preview and settings, and the agent bridge (see `docs/code-editor.md`, `docs/agent-editor.md`).

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
| 12 | Editor | Remote workspaces | SFTP and FTP |
| 13 | Editor | Plugins | A small extension system |

## Left out on purpose

Podcasts, speech to text and text to speech, a web UI and REST API, Docker and SurrealDB, a provider matrix (the router handles providers), multi-user and passwords. PDFs, audio and video come later through the sandbox, with the app storing only the extracted text.

## Open questions

- Which embedding model and vector size (affects storage and which providers work).
- Whether Turso supports full-text search; if not, keyword search is scored in the app (phase 2 checks this).
