# Settings and the knowledge services (step after phase 13)

This closes the first gap in `docs/GAPS.md`: the knowledge layer, research and verify had no settings and nothing that built them. There is still **no screen** for notebooks, sources, chat or asking, so the person cannot use them yet; this work gives those screens one place to get everything from.

## New settings (Settings screen, saved encrypted)

- **Embeddings (search by meaning)**: model id, tag, dimensions, router address and key, and an optional fallback router (address, key, model id if it names the model differently). All empty means keyword search only.
  - `auto` is refused: it can change the vector size between calls.
  - Both routers share one tag, so one can stand in for the other. Give the fallback only when it serves the same model.
  - The tag defaults to the model id, with `@dimensions` if set. Changing the model or tag later does not delete old vectors; chunks just count as not yet embedded for the new tag.
  - A half-filled form is refused on Save with a message, not silently dropped.
- **Verify in the sandbox**: check commands, one per line (at most 20, 500 characters each). Empty means detect them from the project. Read with `settings.verifyChecks` and passed to `SandboxVerifier.verify(customChecks = ...)`.
- **Research mode** (off by default): research tasks read web pages, save them as sources in the knowledge database and write a cited report.

## One place that builds things

- `services/KnowledgeServices.build(db, gateway, embedding, model, searchProvider, fetcher)` builds the repository, context repository, indexer, embedding indexer, hybrid search, context builder, asker, insight generator, chat and research runner from one database, one gateway and one pinned tag. Nothing talks to a network when it is built; tables are created the first time something uses them.
- **No embedding setup**: search stays on keywords. Answers carry the note "Meaning search was skipped: No embedding model is set up in Settings...", and `indexAll` says so.
- `indexAll()` cuts new or changed sources into chunks and gives chunks without a vector one. A failure to make vectors is reported in the result and leaves the chunks in place.
- `services/ServiceBuilder.knowledge(settings, gateway)` returns `Ready`, `NotConfigured` (no knowledge database) or `Problem(message)` (knowledge database equals another database, or an unusable embedding setting). `ServiceBuilder.verifier(settings, root)` returns a `SandboxVerifier` when a Pandastack key is set.

## What changed in the app

- `LilyFactory.create` takes an optional `knowledge`. When `settings.researchMode` is on, the research worker is given the research runner (built from the settings if `knowledge` is not passed). With research mode off, or no usable knowledge database, nothing changes from before. `EditorActivity` is untouched.
- Be aware: with research mode on, **every research task becomes a full run** (up to 8 pages, 5 minutes, written to the knowledge database), not the old `web_search` call.

## Not done

- The `Ready` path of `ServiceBuilder.knowledge` is not unit tested, because it creates a real `TursoClient`. `KnowledgeServices.build` is tested on real SQLite.
- No screens: notebooks, adding sources (which should call `indexAll`), ask and chat with citations, source viewer, context levels, insights. No button that runs the verifier after Accept.
- Embedding batch size and the vector scan limit are not settings.
- Nothing was compiled or run. Run `./gradlew testDebugUnitTest`.

## Tests

`settings/EmbeddingSettingsTest` (form rules, tags, fallback, check commands), `services/KnowledgeServicesTest` (keyword-only, meaning search, failing vectors, research on the same stack, all on real SQLite in memory), `services/ServiceBuilderTest`.
