# Phase 11: research mode

Nothing on screen yet, and nothing in the app calls it by default. `ResearchRunner.run(question)` takes a question, searches the web, reads pages, keeps them as sources in a notebook and writes a cited report. `ResearchWorker` uses it when it is given a runner; without one it behaves exactly as before.

## The five steps

1. **Plan.** The model turns the question into up to 4 short searches (`ResearchPlanner`). The question as written is always the first one. If planning fails, only the question is searched and a note says so.
2. **Search.** Each search goes to a `SearchProvider`. The real one, `ModelSearchProvider`, asks the router's web-search model (`RouterConfig.webSearchModel`) for a JSON list of `{title, url, snippet}`; if the model answers in prose, the web addresses in the prose are used. Every address is normalised (`KnowledgeText.normaliseUrl`) so only http and https addresses without a username or password survive. A search that fails is noted and the others go on. Results are **interleaved** (one from each search in turn) so the first search cannot use every page.
3. **Read.** Pages are fetched until `maxPages` (8) are read, `maxFetchAttempts` (24) are tried or the time budget (5 minutes) runs out. A page that cannot be fetched, is not text or HTML, is the same page as one already read, or has fewer than `minPageChars` (200) of text is **skipped with a reason**. **Nothing is written to the database in this step.**
4. **Keep.** If at least one page was read, a new notebook `Research: <question>` is made (or the notebook you pass in is used). Each page is saved as a **web source** (a page saved before is updated, not copied), linked to the notebook, cut into chunks (phase 4) and, if an `EmbeddingIndexer` was given, given meaning vectors (phase 5). If vectors cannot be made, keyword search still works and a note says so.
5. **Answer.** `KnowledgeAsker` (phase 6) answers the question from that notebook with numbered citations that are checked against what the model was shown. If the answer cites page text it is saved as an **AI note** that cites those sources. An answer that cites nothing is not saved as a note (the pages stay).

`ResearchReport` holds the answer, the pages read (with chunks and whether they were cut), the pages skipped and why, the note id, and notes. `toText()` renders it as plain text.

## Safe fetching

`GuardedPageFetcher` is the only way research reads the web. It exists because the address can come from a model or a page, so it must not be able to point the app at the phone's own network.

- **`UrlGuard`** allows only http and https, no username or password, ports 80, 443, 8080 and 8443. It refuses `localhost`, names ending in `.local`, `.internal`, `.lan` and similar, and single-word names. It then resolves the host and refuses it unless **every** address is public: not loopback, private (10/8, 172.16/12, 192.168/16), link-local (169.254/16, where cloud metadata lives), carrier-grade NAT (100.64/10), multicast, reserved, documentation or benchmarking ranges, IPv6 unique-local, or an IPv6 form that wraps an IPv4 address (6to4, Teredo, NAT64, IPv4-mapped or -compatible). Number-only hosts (`2130706433`, `0x7f.1`) are refused.
- **Redirects** are followed by hand, at most 4, and the guard runs on **every hop** before it is requested.
- **Size.** The body is cut at 1.5 MB and the extracted text at 60,000 characters (`truncated` says so).
- **Type.** Only HTML, XHTML and plain text are read. A PDF, image or download is refused. (PDFs and the like are meant to come later through the sandbox.)
- **`HtmlExtractor`** removes scripts, styles, `noscript`, `template`, `svg`, `iframe`, `object`, `head`, `title` and comments, turns block tags into lines (so line ranges stay meaningful), decodes entities and tidies whitespace.

### What the guard does not stop

- **DNS rebinding.** The guard asks the resolver, then `HttpURLConnection` asks again when it connects. A hostile DNS server could answer differently the second time. Closing this needs an `HttpTransport` that connects to the address that was checked; the seam is there, the transport is not written.
- **Pages that need JavaScript** come out empty and are skipped.
- **Hidden text.** The extractor does not know what CSS hides, so text hidden from a reader (a common place for injected instructions) can still reach the stored source. That is why page text is treated as untrusted everywhere below.
- **A model-made address list.** `ModelSearchProvider` is not a search index. Addresses can be wrong or not say what the model claims. Only what was actually fetched is kept: the snippet is never stored or shown to the answering model, and the saved source is the page's own text.

## Trust

Page text and search results are untrusted. Page text reaches the answering model only as an escaped `<excerpt>` marked as data (phase 6). That lowers the risk of prompt injection and does not remove it. The report is the model's words: a citation proves the number points at an excerpt that was shown, not that the excerpt supports the claim. The AI note is labelled AI and is never evidence on its own; nothing here touches the proven facts database.

## Budget, progress and cancel

`ResearchBudget` bounds queries (4), results per search (5), pages (8), page attempts (24), minimum page size (200 characters), vectors made per run (512) and the time for **searching and reading** (5 minutes). Saving, indexing and answering always finish once they start; they are bounded by `maxPages`.

`run(question, notebookId = null, onProgress)` reports `PLANNING`, `SEARCHING`, `READING`, `INDEXING`, `ANSWERING` and `DONE`.

**Cancel** by cancelling the coroutine. Before step 4 nothing has been written. After it, sources already saved stay in the notebook (they are valid library content); there is no rollback. Errors from the knowledge database itself are not caught and reach the caller.

## What the roadmap said and what this does

The roadmap line says the research worker "takes notes". This phase writes **one AI note: the cited report**. Per-source notes are not made here; per-source analyses already exist as insights (phase 9) and can be generated for the new notebook with `InsightGenerator.generateForNotebook`.

## Where the code is

- `research/UrlGuard.kt`, `research/HtmlExtractor.kt`, `research/PageFetcher.kt` (`GuardedPageFetcher`, `UrlConnectionTransport`), `research/SearchProvider.kt` (`ModelSearchProvider`, `ResearchPlanner`), `research/ResearchRunner.kt`.
- Changed: `workers/ResearchWorker.kt` (optional runner).
- Tests in `app/src/test/kotlin/ai/lily/app/research/`: `AddressRangesTest`, `UrlGuardTest`, `UrlGuardBlockedTest`, `UrlGuardSchemeTest`, `HtmlExtractorTest`, `PageFetcherTest`, `SearchProviderTest`, `ResearchRunnerTest`. They use real SQLite in memory, the concept embedder, a scripted model and fake search, fetch and transport.

## How it was checked

**Not compiled and not run**, like phases 5 to 10: this environment has no Kotlin compiler or network. Tests were checked by reading them against the code and the repository API. Run `./gradlew testDebugUnitTest` first and expect small compile errors. The real network path (`UrlConnectionTransport`, `SystemHostResolver`, `ModelSearchProvider` against a real router) has no test at all.

## Not done / not known

- **Not wired in `LilyFactory`.** A runner needs a knowledge database, an embedder and its model tag; the settings for the embedding endpoint still do not exist (see `docs/knowledge-embeddings.md`). `ResearchWorker(gateway, tools, research)` is ready for when they do.
- **No screens**, no streaming of the report, no per-source notes, no PDFs.
- **How well the router's search model returns usable addresses** is unknown. If most are dead, runs will end with "No page could be read" and a list of skipped addresses.
- **Request size.** A page of 60,000 characters is about 60 KB, so a run saves under 0.5 MB, below the 1 MB indexing size that is still an open question for Turso.
- Everything still open from phases 2 to 10 (Turso support for the SQL, the Gradle build).
