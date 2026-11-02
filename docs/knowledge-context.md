# Phase 9: context levels, insights and chat

Still nothing on screen, and nothing calls this yet. This phase adds the logic and storage for three things: how much of each source a notebook shows the model, AI-written insights about sources, and a chat with a notebook. It builds on phases 5 and 6 (merged) and does not need phase 8.

## Context levels

Each source linked to a notebook has a level **in that notebook** (the same source can be FULL in one and OFF in another):

| Level | What the model sees |
|-------|---------------------|
| **FULL** (default) | The source's text is searched, as in phase 6. |
| **INSIGHTS** | Only its fresh insights (below). Its text is not searched. |
| **OFF** | Nothing. |

FULL is stored as *no row*, so everything that existed before this phase behaves exactly as before. A level is forgotten when the source is unlinked from the notebook, and removed with the notebook or the source.

**Where it applies:** keyword and meaning search skip non-FULL sources when a notebook is given (a SQL condition in `KeywordSearch` and `VectorSearch`, so ranking statistics are scoped too). A search or question with **no notebook** covers the whole library and ignores levels.

## Token estimate

`NotebookContextBuilder.report(notebookId)` says, before asking anything, what a notebook would show the model. Sizes are about four characters to a token, not any model's real count.

- Per source: level, text size, fresh and stale insight counts, insight size.
- `searchableTokens`: the text of the FULL sources. Only the best excerpts are sent, at most `excerptBudgetTokens` (3000, from the 12,000-character limit in phase 6).
- `sentInsightTokens`: what INSIGHTS sources add. Insights share a budget of 6,000 characters (about 1,500 tokens) and each is clipped to 2,000 characters, so they cannot crowd out excerpts.
- `perQuestionTokensAtMost`: the two parts together, without the question and instructions.
- `warnings`: an INSIGHTS source with no up-to-date insight (the model sees nothing from it), out-of-date insights, insights that do not fit the budget, a notebook where nothing can reach the model.

## Saved prompts

Four built-in prompts live in code and cannot be changed: **Summary**, **Key claims**, **Open questions**, **Key terms**. You can save your own (title up to 80 characters, text up to 4,000; titles are unique ignoring case, built-ins included). Deleting your prompt deletes the insights made with it.

## Insights

`InsightGenerator.generate(sourceId, promptId)` runs a prompt over a source and stores the answer. One insight per source and prompt; making it again replaces it.

- **Always AI.** An insight is never evidence on its own. Answers that lean on one say so (below).
- **Stale detection.** An insight keeps the hash of the source text and of the prompt it was made from. If either changes it is *stale*: still listed, but **not sent to the model** until made again.
- **Untrusted input.** The source text goes between `<source>` tags, a closing tag inside it is rewritten, and the model is told never to follow instructions in it. This reduces prompt injection; it does not make it impossible, and an injected insight would later be shown to the answering model (labelled AI-written, with the same escaping).
- **Long sources.** Only the first 24,000 characters are shown to the model. A longer source gives a *truncated* insight and a note. There is no map-reduce over long sources.
- **Per notebook.** `generateForNotebook(notebookId, promptId)` makes the insight for every source that needs it, one model call at a time. It skips OFF sources and up-to-date ones (unless `regenerateFresh`), reports a failure for one source and goes on, and stops after `maxSources` (25) calls, saying how many are left.

## Asking with insights

`KnowledgeAsker` takes an optional `NotebookContextBuilder`. With a notebook, the fresh insights of its INSIGHTS sources are added after the excerpts, numbered on from them and marked `ai-written`. The answering instructions then say insights are not the source and should be cited only when no excerpt covers the point. Without a builder, or without a notebook, nothing changes from phase 6.

- `AskCitation` now has `basis` (`TEXT` or `INSIGHT`) and `insightId`. An insight citation has an empty `chunkId` and lines 0.
- An answer that cites only insights gets a note, and **cannot be saved as a note** (`AskAnswer.groundedInText` is false). A saved note lists insight citations as *AI-written insight* and **links only the sources whose text was cited**.
- Insights are not ranked by the question; they go in source order until the budget is used.

## Chat

A notebook can have several chats (`ChatSession`); messages keep their order, the citations of each reply and any notes.

`NotebookChat.send(sessionId, message)`:

1. If the chat already has messages, the model rewrites the new one into a standalone question using the last 6 messages (`FollowUpRewriter`). A reply that is not one short line is refused. On failure or refusal the message is searched as written and a note says so. The first message is not rewritten (one call fewer).
2. The standalone question goes to `KnowledgeAsker` for that notebook, so levels, citations and insights all apply.
3. The question (as typed) and the reply are stored **together in one transaction**. If answering fails, the error reaches the caller and the chat is unchanged.

The reply's notes include `Understood as: ...` when the message was rewritten. `saveReplyAsNote(messageId)` saves a reply as an AI note with the same rules as above.

**Limits:** the answer is made only from retrieved excerpts, so an instruction about the previous answer ("shorter", "translate it") does not work. Citations stay in the history as they were: if a source is deleted later, its citation still shows its title and excerpt but cannot be opened, and saving that reply as a note fails.

## Schema v6

Only `CREATE ... IF NOT EXISTS` (no `ALTER`), so two callers migrating at once cannot break each other: `source_context`, `saved_prompts`, `source_insights`, `chat_sessions`, `chat_messages`. Chat citations and notes are JSON text. Deleting a source also deletes its insights and levels; deleting a notebook also deletes its chats and levels.

## Where the code is

- `memory/ContextModels.kt`, `memory/ContextRepository.kt`, `memory/NotebookContextBuilder.kt`, `memory/InsightGenerator.kt`, `memory/FollowUpRewriter.kt`, `memory/NotebookChat.kt`.
- Changed: `KnowledgeSchema` (v6), `KnowledgeRepository` (cleanup), `KeywordSearch` and `VectorSearch` (level filter), `KnowledgeAsker` (insights, citation basis).
- Tests in `app/src/test/kotlin/ai/lily/app/memory/`: `ContextRepositoryTest`, `ContextLevelSearchTest`, `InsightGeneratorTest`, `NotebookContextBuilderTest`, `AskWithInsightsTest`, `FollowUpRewriterTest`, `NotebookChatTest`. They use real SQLite in memory, the concept embedder and a scripted model.

## How it was checked

**Not compiled and not run**: this environment has no Kotlin compiler. Tests were checked by reading them against the code. Run `./gradlew testDebugUnitTest` first and expect small compile errors.

## Not done / not known

- **No screens.** Levels, insights, prompts and chats have no UI. Nothing in the app calls any of this.
- **Settings** for the embedding and model endpoints are still not wired (see `docs/knowledge-embeddings.md`).
- **Turso itself** has not been asked: v6 uses `INSERT ... SELECT ... WHERE EXISTS ... ON CONFLICT DO UPDATE` and `INSERT ... SELECT MAX(...)`. These are ordinary SQLite, but the first migration on a real database will say if not.
- How well real models follow the rewrite and citation formats is untested.
