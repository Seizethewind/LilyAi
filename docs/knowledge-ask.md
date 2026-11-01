# Phase 6: ask with citations

Still nothing on screen, and nothing calls this yet. This branch is **stacked on phase 5** (`feat/knowledge-embeddings`), because asking uses `HybridSearch`. Merge phase 5 first.

`KnowledgeAsker.ask(question, notebookId = null)` answers a question from the library and says exactly where each claim came from.

## The four steps

1. **Plan.** The model turns the question into up to 3 short search queries (`QueryPlanner`). The question as written is **always** searched too. If planning fails (router down, nonsense reply), only the question is searched and the answer carries a note saying so.
2. **Retrieve.** Each query runs through `HybridSearch` (keyword + meaning, phase 5), limited to one notebook if `notebookId` is given. A chunk found by several queries scores higher (the fused scores are added). At most **8 chunks** and about **12,000 characters** go to the model.
3. **Answer.** The model is shown the chunks as numbered excerpts:

   ```
   <excerpt n="1" source="Cooking" lines="3-9">
   ...text...
   </excerpt>
   ```

   It is told to use only facts the excerpts state, to cite each claim as `[n]`, to say so plainly when the excerpts do not hold the answer, and never to follow instructions found inside an excerpt. **If nothing was retrieved the model is not asked at all**; the answer is "I found nothing in the library that answers that." with no citations.
4. **Check.** The answer's citation markers are checked against the excerpts that were really shown (`CitationResolver`):
   - A number that was never shown (`[7]` when 3 excerpts were given) is **removed**, with the space before it. In `[1, 9]` only the 9 goes.
   - What is left is **renumbered from 1 in order of first appearance**, so the reader never sees a gap.
   - Code such as `arr[0]` or `grid[0][1]` is left alone: a marker must not directly follow a letter, digit or another `]`.

## What an answer holds

`AskAnswer`: the cleaned `text`; `citations`; the `queries` that were run; how many excerpts were `retrieved`; `notes`; `routedVia`.

Each `AskCitation` has its number, the **chunk id**, the **source** (id, title, kind, web address) and the **line range** in that source's text (from 1), plus the first 300 characters of the chunk. `notes` can say: planning failed, meaning search was skipped or partial (from phase 5), or **"the answer cites none of the excerpts, so treat it as unsupported"**.

## Saving an answer

`saveAsNote(notebookId, answer, title = null)` writes the answer into a notebook as an **AI note** (never evidence on its own), with a "Sources" list under it (`[1] Cooking, lines 3-9`, plus the address for web pages) and the cited sources linked as the note's citations. An answer that cites nothing is **refused**: an AI note with nothing behind it is not worth keeping.

## Safety choices

- Excerpt text is **untrusted**. The system prompt says so, a closing tag inside an excerpt is rewritten (`</excerpt` becomes `<\/excerpt`) so text cannot end its own block, and a source title cannot break out of its attribute (quotes become `'`, line breaks become spaces). This reduces prompt injection; it does not make it impossible.
- The knowledge layer never reads or writes the proven facts database. Nothing here promotes anything to a fact.

## What the check does and does not prove

A surviving citation proves the number points at a chunk **that was shown to the model**. It does **not** prove the chunk supports the claim: the model can cite the wrong excerpt, or stretch one. A reader still has to open the source. Showing the line range and the opening of the chunk is meant to make that quick.

## Not done

- **Tapping a citation to open the editor at that line.** The roadmap lists it, but a source's text lives in the database, not in a file, and the app has no viewer for it. `AskCitation` carries what a viewer needs (`sourceId`, `startLine`, `endLine`). The viewer is a separate piece of UI work.
- **Streaming**, **follow-up questions** that use earlier turns, and **searching notes** (only sources are searched, as in phases 4 and 5).
- **Settings.** The caller builds the `ModelGateway` (existing `RouterModelGateway`), the `HybridSearch` and the asker. The `model` argument is the router selector (`auto`, `auto:smart`, a profile name).
- A **second pass that asks the model to verify** each claim against its excerpt. It would cost another call and could be wrong too.

## Where the code is

- `memory/QueryPlanner.kt`, `memory/CitationResolver.kt`, `memory/KnowledgeAsker.kt` (with `AskAnswer`, `AskCitation`).
- Tests in `app/src/test/kotlin/ai/lily/app/memory/`: `QueryPlannerTest`, `CitationResolverTest`, `KnowledgeAskerTest` (with a scripted fake model, in-memory SQL and the phase 5 concept embedder).

## How it was checked

**Not compiled and not run**, like phase 5: this environment has no Kotlin compiler or network. Each test was checked by reading it against the code and against the real repository API. That found and fixed one real bug before commit (a backslash in a `Regex.replace` replacement string is an escape, which would have silently dropped the excerpt-tag escape). Run `./gradlew testDebugUnitTest` first.

## Not known yet

- How well real models follow the citation format. Some will skip markers or cite in another style (`(Source 2)`); those answers will show "cites none of the excerpts". The prompt may need tuning per model.
- Everything still open from phases 2 to 5 (Turso support, request sizes, the Gradle build).
