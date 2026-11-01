# Phase 5: embeddings and hybrid search

Still nothing new on screen, and nothing calls this yet. Whoever wires the "ask" flow (phase 6) or a settings screen builds a `RouterEmbedder`, an `EmbeddingIndexer` and a `HybridSearch` from the same pinned **tag**.

## What it does

1. **Embedding client** (`gateway/RouterEmbedder`). Sends `POST {baseUrl}/embeddings` to OpenAI-compatible routers: the 9Router (`http://host:20128/v1`) and the FreeLLMAPI router (`http://host:3001/v1`).
2. **Vector storage** (schema **v5**, table `chunk_embeddings`). One vector per chunk, stored as base64 text of little-endian float32 numbers (`memory/VectorCodec`), because the Turso wire in this app has no blob type. A 1536-number vector is 8192 characters.
3. **Embedding indexer** (`memory/EmbeddingIndexer`). Gives every chunk a vector from the pinned model.
4. **Meaning search** (`memory/VectorSearch`) and **hybrid search** (`memory/HybridSearch`), which joins keyword and meaning results.

## Two routers at once

`RouterEmbedder` takes a list of `EmbeddingEndpoint`. They are tried in order; the first is preferred, the rest are fallbacks. An endpoint that fails on any part of a request (network error, HTTP 429/5xx, an answer that makes no sense) is skipped for that whole request and the next one starts again from the first text, so **one request never mixes vectors from two endpoints**. If every endpoint fails, the error names each one and why.

Vectors from different models cannot be compared, so every endpoint carries a **tag**, and only endpoints with the asked-for tag may answer:

- The tag defaults to the model id (plus `@N` if `dimensions` is set), for example `openai/text-embedding-3-small`.
- Two endpoints back each other up **only if they have the same tag**. If the 9Router calls a model `openai/text-embedding-3-small` and FreeLLMAPI calls it `text-embedding-3-small`, give both `tag = "te3-small"`. Do that only when they really serve the same model; the app cannot check.
- `model` must be a real id, never `auto` (or `auto:...`): `auto` can change the vector size between calls. An endpoint with `auto` is refused when it is created.
- Find ids with `GET {router}/v1/models/embedding` on the 9Router and `GET {router}/v1/models` on FreeLLMAPI. `dimensions` only works on OpenAI `text-embedding-3-*`.
- Texts go out in batches (default 32; some providers cap it). Vectors are requested as plain floats; a router that answers in base64 is understood too.

## Keeping vectors current

A chunk is **pending** when it has no vector from the pinned tag. That covers new chunks, chunks rebuilt after their source changed (new ids), and chunks whose old vector came from another model (the new vector replaces it, one per chunk).

- `EmbeddingIndexer.embedPending(maxChunks = 512)` embeds pending chunks in batches and returns how many it did and how many remain. Each batch is written in **one transaction**, so a failure keeps what was done and the next run carries on.
- Vectors are stored scaled to length 1, so similarity is a dot product.
- Vectors whose chunk no longer exists are deleted at the start of each run. Until then they are harmless: searches join to `chunks`.
- If the model returns a different tag, a different number of vectors, an invalid vector, or a different vector size than the ones already stored, the run stops with an error. For a size change, `reset()` deletes this tag's vectors so they can be made again.
- Call `KnowledgeIndexer.indexStale()` (phase 4) first, then `embedPending()`.

## Hybrid search

`HybridSearch.search(query, notebookId = null, limit = 10, minSimilarity = 0.0)`:

1. Keyword search (phase 4, BM25) finds up to 3x `limit` chunks, at least 10.
2. The query is embedded with the pinned tag and compared with the stored vectors (cosine).
3. The two ranked lists are joined with **reciprocal rank fusion**: a chunk scores `1 / (60 + rank)` in each list that has it, and the scores are added. It uses ranks, not raw scores, because BM25 scores and similarities are on different scales. A chunk both lists like beats one that only one likes.

Each `HybridHit` says its keyword rank, meaning rank and similarity (null where that list did not have it). The fused `score` is only for ordering. `minSimilarity` drops weak meaning matches (those at or below it); it does not remove a chunk that the keyword search found.

**If the query cannot be embedded** (router down, wrong model), the search still returns the keyword hits, with `semantic = false` and a `note` saying why. The same goes for a library with no vectors yet.

## Limits, on purpose

- **Meaning search runs in the app and does not scale.** Every vector it compares is downloaded (about 8 KB each at 1536 numbers). It compares at most **1500** vectors per search, in pages of 100, and when the library is bigger it says so (`complete = false`, and a note) because a closer chunk may have been missed. A big library needs the database to do the comparison, which depends on the real Turso supporting vector search. That is still unknown (see below).
- The scan order is by chunk id, so which vectors are compared in an incomplete scan is arbitrary, not "the most likely".
- Chunks are about 400 tokens, within every embedding model's input limit. No cap on request size is applied beyond the batch size.
- Notes are not embedded, only sources, like keyword search.
- Settings for the endpoints (addresses, keys, model, tag) are **not wired into the app's settings screen or encrypted storage yet**; the code takes them as plain objects. Keys must come from the encrypted settings, never from source.

## Where the code is

- `memory/Embedder.kt`: `Embedder`, `EmbeddingBatch`, `EmbeddingException`.
- `gateway/EmbeddingEndpoint.kt`, `gateway/RouterEmbedder.kt`.
- `memory/VectorCodec.kt`, `memory/EmbeddingIndexer.kt`, `memory/VectorSearch.kt`, `memory/HybridSearch.kt`.
- `memory/KnowledgeSchema.kt`: migration v5.
- Tests in `app/src/test/kotlin/ai/lily/app/memory/`: `VectorCodecTest`, `RouterEmbedderTest`, `EmbeddingIndexerTest`, `HybridSearchTest`, and the `ConceptEmbedder` fake (words grouped into concepts, one axis each, so "spaghetti" and "noodles" match without sharing a letter).

## How it was checked

**Not compiled and not run.** The environment this was written in has no Kotlin compiler and no network, so neither the code nor the tests have been built. The tests were checked by reading them against the code, line by line, which found and fixed four mistakes before this was committed; that is not a substitute for `./gradlew testDebugUnitTest`. Run that first and expect to fix small compile errors.

## Not known yet

- **The Check result.** Whether the real Turso supports vector search and FTS5 is still unrecorded; it decides whether meaning search can move into the database.
- **Request size.** One batch of 32 vectors written to Turso is about 260 KB of text for 1536 numbers. Whether Turso's HTTP API accepts that, and the ~1 MB chunk write from phase 4, has not been tried.
- **Real routers.** Nothing has been sent to the 9Router or FreeLLMAPI. The request and answer shapes follow the 9Router `embeddings` skill document; provider quirks (batch caps, Gemini conversion) are untested.
- **Gradle build** and the **sqlite-jdbc test helper** have still never been run.
