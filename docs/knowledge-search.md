# Phase 4: chunks and keyword search

Still nothing new on screen, and nothing calls this yet: a source is **not** chunked when it is added. Whoever adds sources (the phase 6 "ask" flow, or later a settings button) calls `KnowledgeIndexer.index(sourceId)` for one source, or `indexStale()` to catch up on everything that is missing or out of date.

## Chunks

Each library source is cut into **chunks** of about 400 tokens that overlap by 15%. A token is estimated as four characters (`KnowledgeTokens.estimate`); that sizes the chunks and is not any model's real count.

- Chunks are made of **whole lines**, and each stores its **start and end line** (counted from 1 in the source's text), so a citation can open the editor at the right place.
- Neighbouring chunks **share their last lines**, as many as fit in 15% of 400 tokens (60 tokens). If the last line alone is longer than that, there is no overlap at that point, and the text still always moves forward.
- A **line too long for one chunk** is cut at a space (or, with no spaces, at a hard limit) and its pieces share that line number. A character that takes two UTF-16 units is never cut in half.
- Windows (`\r\n`) and old Mac (`\r`) line endings count as one line break; a final newline is not an extra line. Chunks that are only blank lines are dropped.

## Keeping chunks current

Two tables (schema **v4**) hold the result:

- `chunks`: id, source, position, line range, text, token estimate.
- `source_index`: for each source, the **hash of the text** and the **chunk settings** it was last chunked with (`v1/400/15`).

A source's chunks are current when both still match. `index` then does nothing and writes nothing. If the text changed (for example a web page was saved again) or the settings or `Chunker.VERSION` changed, the chunks are rebuilt: old chunks deleted, new ones inserted in batches of 50 rows, and the record updated, all in **one transaction**, so a failure leaves the old chunks in place. Deleting a source (`KnowledgeRepository.deleteSource`) also deletes its chunks and its record.

## Keyword search

`KeywordSearch.search(query, notebookId = null, limit = 10)` returns the best chunks first, each with its source (title, kind, address for web pages), line range, text and score. Pass a notebook id to search only the sources linked to that notebook; leave it out to search the whole library.

It works the same whether or not the knowledge database supports full-text search (FTS5), so the open question from phase 2 does not block it:

1. The query is split into words (letters and digits in any language, lower-cased, one-letter English words dropped, at most 12).
2. The database finds chunks containing any word as text, with a plain `LIKE`, taking the 500 with the most matching words.
3. The app ranks those with **BM25** on whole words. A chunk only scores for a word it really contains, so `past` does not find `pasta`, and a word that is rare in the library counts for more than a common one.

**Limits, on purpose:**

- Case is ignored for English letters only; the database's `LIKE` does not fold other alphabets (an accented capital will not match its lower-case form).
- Only 500 candidate chunks are ranked, so in a very large library a weak match can be missed.
- How common a word is counts a word even inside longer words, which slightly softens the weight of rare words.
- Notes are not searched yet, only sources. Searching the chunks of a source never reads the proven facts database.
- Meaning search (embeddings) is phase 5.

If the Check button later says full-text search is available, an FTS5 path can replace steps 2 and 3 behind `KeywordSearch` without changing what callers see.

## Where the code is

- `memory/Chunker.kt`: `Chunker`, `Chunk`, `KnowledgeTokens`.
- `memory/KnowledgeTokenizer.kt`: words and query terms.
- `memory/KnowledgeIndexer.kt`: `index`, `indexStale`, `IndexResult`.
- `memory/KeywordSearch.kt`: `KeywordSearch`, `SearchHit`.
- `memory/SchemaGate.kt`: runs the migrations once on first use; shared by the repository, indexer and search.
- `memory/KnowledgeSchema.kt`: migration v4. `memory/KnowledgeRepository.kt`: `deleteSource` now also removes chunks.
- Tests in `app/src/test/kotlin/ai/lily/app/memory/`: `ChunkerTest`, `KnowledgeTokenizerTest`, `KnowledgeIndexerTest`, `KeywordSearchTest`.

## How it was checked

The knowledge code and all its tests (81, covering phases 3 and 4) were compiled with Kotlin 2.0.21 and run with JUnit 4. Because no SQLite JDBC driver could be downloaded where this was written, the database tests ran against Python's SQLite through a small stand-in for `JdbcSqlDatabase`; the real test helper (`org.xerial:sqlite-jdbc`, added in phase 3) has not been run yet. The tests were also checked by breaking the code on purpose (no overlap, substring matching, leaving chunks behind on delete); each break was caught.

## Not known yet

- **Request size.** A source of 1 MB is about 650 chunks, which is one request of roughly 1 MB to Turso. Whether Turso's HTTP API accepts that has not been tried; if it does not, indexing a large source will fail with Turso's own message and the old chunks stay.
- **Gradle build.** The Android build and the `testDebugUnitTest` task were not run.
- **The Check result.** Whether the real Turso supports FTS5 and vector search is still unrecorded.
