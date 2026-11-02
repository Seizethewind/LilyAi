package ai.lily.app.research

import ai.lily.app.gateway.ModelGateway
import ai.lily.app.gateway.ModelRequest
import ai.lily.app.memory.AskAnswer
import ai.lily.app.memory.CitationBasis
import ai.lily.app.memory.EmbeddingIndexer
import ai.lily.app.memory.KnowledgeAsker
import ai.lily.app.memory.KnowledgeIndexer
import ai.lily.app.memory.KnowledgeRepository
import kotlin.coroutines.cancellation.CancellationException

/**
 * How much one research run may do. The limits bound cost (model calls, network, storage) and time.
 *
 * [deadlineMs] covers the gathering part (searching and reading). Saving, indexing and answering always finish once they start;
 * they are bounded by [maxPages].
 */
data class ResearchBudget(
    val maxQueries: Int = 4,
    val resultsPerQuery: Int = 5,
    val maxPages: Int = 8,
    val maxFetchAttempts: Int = 24,
    val minPageChars: Int = 200,
    val maxEmbedChunks: Int = 512,
    val deadlineMs: Long = 5 * 60_000L,
) {
    init {
        require(maxQueries >= 1) { "At least one search is needed." }
        require(resultsPerQuery >= 1) { "At least one result per search is needed." }
        require(maxPages >= 1) { "At least one page is needed." }
        require(maxFetchAttempts >= maxPages) { "There must be at least as many attempts as pages." }
        require(minPageChars >= 0) { "The minimum page size cannot be negative." }
        require(maxEmbedChunks >= 0) { "The embedding limit cannot be negative." }
        require(deadlineMs > 0) { "The time budget must be positive." }
    }
}

enum class ResearchStage { PLANNING, SEARCHING, READING, INDEXING, ANSWERING, DONE }

/** A step the run has reached, for showing progress. */
data class ResearchProgress(val stage: ResearchStage, val message: String)

/** A page that was saved as a web source of the notebook. [chunks] is 0 if indexing it failed. */
data class ResearchedPage(
    val sourceId: String,
    val url: String,
    val title: String,
    val characters: Long,
    val chunks: Int,
    val truncated: Boolean,
)

/** An address that was tried and not used, and why. */
data class SkippedPage(val url: String, val reason: String)

/**
 * What a research run produced. [answer] is the cited report (null if nothing could be read or answering failed). [noteId] is the
 * AI note the report was saved as, if it could be. [notebookId] is null only when no page was read, in which case nothing was
 * created.
 */
data class ResearchReport(
    val question: String,
    val notebookId: String?,
    val queries: List<String>,
    val pages: List<ResearchedPage>,
    val skipped: List<SkippedPage>,
    val answer: AskAnswer?,
    val noteId: String?,
    val notes: List<String>,
) {
    /** The report as plain text: the cited answer, its numbered sources, the pages read, and anything worth knowing. */
    fun toText(): String = buildString {
        if (answer == null) {
            append("Research did not produce a report.")
        } else {
            append(answer.text)
            if (answer.citations.isNotEmpty()) {
                append("\n\nSources")
                answer.citations.forEach { c ->
                    if (c.basis == CitationBasis.INSIGHT) {
                        append("\n[${c.number}] ${c.sourceTitle}, AI-written insight")
                    } else {
                        append("\n[${c.number}] ${c.sourceTitle}, lines ${KnowledgeAsker.lines(c.startLine, c.endLine)}")
                    }
                    c.url?.let { append(" (").append(it).append(')') }
                }
            }
        }
        if (pages.isNotEmpty()) {
            append("\n\nPages read (${pages.size})")
            pages.forEach { append("\n- ${it.title} (${it.url})").append(if (it.truncated) " [cut at the size limit]" else "") }
        }
        if (skipped.isNotEmpty()) {
            append("\n\nNot used (${skipped.size})")
            skipped.forEach { append("\n- ${it.url}: ${it.reason}") }
        }
        if (notes.isNotEmpty()) {
            append("\n\nNotes")
            notes.forEach { append("\n- $it") }
        }
    }
}

/**
 * Research mode: take a question, search the web, read pages, keep them as sources and write a cited report.
 *
 * 1. **Plan.** The model turns the question into up to [ResearchBudget.maxQueries] searches (the question as written is always one).
 *    If planning fails, only the question is searched.
 * 2. **Search.** Each search goes to the [SearchProvider]; a failing search is noted and the rest go on. Results from the searches
 *    are interleaved so the first search cannot use every page.
 * 3. **Read.** Pages are fetched safely ([GuardedPageFetcher]) until [ResearchBudget.maxPages] are read, the attempts or the time
 *    run out. A page that cannot be fetched, is not text, or has almost no text is skipped with a reason. Nothing is written yet.
 * 4. **Keep.** If at least one page was read, a new notebook is made (or the given one is used) and each page is saved as a **web
 *    source** (a page seen before is updated), linked to the notebook and cut into chunks. Meaning vectors are made if an
 *    [EmbeddingIndexer] was given; if that fails, keyword search still works.
 * 5. **Answer.** [KnowledgeAsker] answers the question from this notebook with numbered citations that are checked against what
 *    was shown to the model. If the answer cites page text it is saved as an **AI note** that cites those sources.
 *
 * **Trust.** Page text and search results are untrusted. A search result is only an address; its snippet is never stored or shown
 * to the answering model. Page text reaches the model only as an escaped excerpt marked as data ([KnowledgeAsker]). That lowers the
 * risk of prompt injection; it does not remove it. The report is the model's words: a citation proves the number points at an
 * excerpt that was shown, not that the excerpt supports the claim, and the AI note is never evidence on its own.
 *
 * **Cancel.** Cancelling the coroutine stops the run at the next step. Before step 4 nothing has been written. After it, the sources
 * already saved stay in the notebook (they are valid library content); there is no rollback.
 *
 * Errors from the knowledge database itself are not caught and reach the caller.
 */
class ResearchRunner(
    private val gateway: ModelGateway,
    private val search: SearchProvider,
    private val fetcher: PageFetcher,
    private val repo: KnowledgeRepository,
    private val indexer: KnowledgeIndexer,
    private val asker: KnowledgeAsker,
    private val embeddings: EmbeddingIndexer? = null,
    private val model: String? = null,
    private val budget: ResearchBudget = ResearchBudget(),
    private val now: () -> Long = { System.currentTimeMillis() },
) {

    suspend fun run(
        question: String,
        notebookId: String? = null,
        onProgress: (ResearchProgress) -> Unit = {},
    ): ResearchReport {
        val asked = question.trim()
        require(asked.isNotEmpty()) { "Ask a research question." }
        if (notebookId != null) require(repo.getNotebook(notebookId) != null) { "That notebook does not exist." }
        val deadline = now() + budget.deadlineMs
        val notes = ArrayList<String>()
        val skipped = ArrayList<SkippedPage>()

        // 1. Plan
        onProgress(ResearchProgress(ResearchStage.PLANNING, "Planning the searches"))
        val queries = LinkedHashSet<String>()
        queries += asked
        try {
            val plan = gateway.complete(ModelRequest(ResearchPlanner.prompt(asked), ResearchPlanner.SYSTEM, model))
            queries += ResearchPlanner.parse(plan.text)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            notes += "Planning the searches failed (${e.message ?: e.javaClass.simpleName}); only the question as written was searched."
        }
        val planned = queries.take(budget.maxQueries)

        // 2. Search
        val results = ArrayList<List<SearchResult>>()
        for (q in planned) {
            if (now() > deadline) {
                notes += "Stopped searching: the time budget ran out."
                break
            }
            onProgress(ResearchProgress(ResearchStage.SEARCHING, q))
            try {
                results += search.search(q, budget.resultsPerQuery)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                notes += "Searching for \"$q\" failed (${e.message ?: e.javaClass.simpleName})."
            }
        }
        val candidates = interleave(results)
        if (candidates.isEmpty() && results.isNotEmpty()) notes += "The searches returned no web addresses."

        // 3. Read (nothing is written yet)
        val read = ArrayList<FetchedPage>()
        val seen = HashSet<String>()
        var attempts = 0
        for (candidate in candidates) {
            if (read.size >= budget.maxPages) break
            if (attempts >= budget.maxFetchAttempts) {
                notes += "Stopped reading: reached the limit of ${budget.maxFetchAttempts} page attempts."
                break
            }
            if (now() > deadline) {
                notes += "Stopped reading: the time budget ran out."
                break
            }
            attempts += 1
            onProgress(ResearchProgress(ResearchStage.READING, candidate.url))
            val page = try {
                fetcher.fetch(candidate.url)
            } catch (e: CancellationException) {
                throw e
            } catch (e: UnsafeUrlException) {
                skipped += SkippedPage(candidate.url, "Not fetched: ${e.message}")
                continue
            } catch (e: FetchException) {
                skipped += SkippedPage(candidate.url, e.message ?: "Could not be read.")
                continue
            } catch (e: Exception) {
                skipped += SkippedPage(candidate.url, "Failed: ${e.message ?: e.javaClass.simpleName}")
                continue
            }
            if (!seen.add(page.url)) {
                skipped += SkippedPage(candidate.url, "Same page as one already read.")
                continue
            }
            if (page.text.length < budget.minPageChars) {
                skipped += SkippedPage(candidate.url, "Too little readable text.")
                continue
            }
            read += page
        }
        if (read.isEmpty()) {
            notes += "No page could be read, so there is nothing to answer from."
            onProgress(ResearchProgress(ResearchStage.DONE, "No pages"))
            return ResearchReport(asked, null, planned, emptyList(), skipped, null, null, notes.distinct())
        }

        // 4. Keep
        onProgress(ResearchProgress(ResearchStage.INDEXING, "Saving ${read.size} page(s)"))
        val notebook = if (notebookId != null) {
            repo.getNotebook(notebookId) ?: throw IllegalArgumentException("That notebook does not exist.")
        } else {
            repo.createNotebook("Research: ${asked.take(TITLE_LENGTH)}", "Made by research mode.")
        }
        val pages = ArrayList<ResearchedPage>()
        for (page in read) {
            val source = try {
                repo.saveWebSource(page.url, page.title, page.text)
            } catch (e: IllegalArgumentException) {
                skipped += SkippedPage(page.url, "Could not be saved: ${e.message}")
                continue
            }
            repo.attachSource(notebook.id, source.info.id)
            val chunks = try {
                indexer.index(source.info.id)?.chunks ?: 0
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                notes += "Indexing ${page.url} failed (${e.message ?: e.javaClass.simpleName}); it cannot be searched."
                0
            }
            pages += ResearchedPage(
                sourceId = source.info.id,
                url = source.info.url ?: page.url,
                title = source.info.title,
                characters = source.info.characters,
                chunks = chunks,
                truncated = page.truncated,
            )
        }
        if (pages.isEmpty()) {
            notes += "No page could be saved, so there is nothing to answer from."
            return ResearchReport(asked, notebook.id, planned, emptyList(), skipped, null, null, notes.distinct())
        }
        if (embeddings != null) {
            try {
                embeddings.embedPending(budget.maxEmbedChunks)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                notes += "Meaning search could not be prepared (${e.message ?: e.javaClass.simpleName}); keyword search is still used."
            }
        }

        // 5. Answer
        onProgress(ResearchProgress(ResearchStage.ANSWERING, "Writing the report"))
        val answer = try {
            asker.ask(asked, notebook.id)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            notes += "Writing the report failed (${e.message ?: e.javaClass.simpleName}). The pages are saved in the notebook \"${notebook.name}\"; you can ask again."
            null
        }
        var noteId: String? = null
        if (answer != null) {
            notes += answer.notes
            if (answer.cited && answer.groundedInText) {
                noteId = try {
                    asker.saveAsNote(notebook.id, answer, "Research: ${asked.take(TITLE_LENGTH)}").id
                } catch (e: CancellationException) {
                    throw e
                } catch (e: IllegalArgumentException) {
                    notes += "The report was not saved as a note (${e.message})."
                    null
                }
            } else {
                notes += "The report was not saved as a note because it does not cite any page text."
            }
        }
        onProgress(ResearchProgress(ResearchStage.DONE, "Done"))
        return ResearchReport(asked, notebook.id, planned, pages, skipped, answer, noteId, notes.distinct())
    }

    /** One result from each search in turn, so an early search cannot take every page. Repeated addresses are dropped. */
    private fun interleave(lists: List<List<SearchResult>>): List<SearchResult> {
        val out = LinkedHashMap<String, SearchResult>()
        val longest = lists.maxOfOrNull { it.size } ?: 0
        for (i in 0 until longest) {
            for (list in lists) list.getOrNull(i)?.let { out.putIfAbsent(it.url, it) }
        }
        return out.values.toList()
    }

    private companion object {
        const val TITLE_LENGTH = 60
    }
}
