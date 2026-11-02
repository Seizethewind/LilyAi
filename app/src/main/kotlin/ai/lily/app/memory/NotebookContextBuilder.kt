package ai.lily.app.memory

/** One source in a notebook with what its level means for the model. Token numbers are estimates (about four characters each). */
data class ContextLine(
    val source: SourceInfo,
    val level: ContextLevel,
    /** Size of the source's whole text. Only searched, and only in part, when the level is FULL. */
    val textTokens: Int,
    val freshInsights: Int,
    val staleInsights: Int,
    /** Size of the fresh insights as they would be sent. */
    val insightTokens: Int,
)

/**
 * What a notebook would show the model, before asking anything.
 * [searchableTokens] is the text of the FULL sources, which questions search; only the best excerpts go to the model, at most
 * [excerptBudgetTokens]. [sentInsightTokens] is what the INSIGHTS sources add, always sent, within [insightBudgetTokens].
 * [perQuestionTokensAtMost] is the sum of the two parts that reach the model for one question, without the question and instructions.
 */
data class ContextReport(
    val notebookId: String,
    val lines: List<ContextLine>,
    val searchableTokens: Int,
    val sentInsightTokens: Int,
    val excerptBudgetTokens: Int,
    val insightBudgetTokens: Int,
    val perQuestionTokensAtMost: Int,
    val warnings: List<String>,
)

/** An insight chosen to go to the model, with its source and the text that will be sent (clipped to [NotebookContextBuilder.INSIGHT_MAX_CHARS]). */
data class InsightContextItem(val insight: Insight, val source: SourceInfo, val text: String)

/** [omitted] fresh insights did not fit the budget. */
data class InsightSelection(val items: List<InsightContextItem>, val omitted: Int)

/**
 * Works out what each source's context level means for a notebook.
 *
 * - OFF: nothing. INSIGHTS: its fresh insights are sent with every question. FULL: its text is searched (see the searches).
 * - Only fresh insights are sent. One made from an older text or an older prompt is left out until it is made again.
 * - Insights are not ranked by the question: they go in source order until [INSIGHT_BUDGET_CHARS] is used up, and the rest are
 *   counted in [InsightSelection.omitted].
 */
class NotebookContextBuilder(
    private val repo: KnowledgeRepository,
    private val context: ContextRepository,
) {
    suspend fun report(notebookId: String): ContextReport {
        val sources = repo.listSources(notebookId)
        val levels = context.levels(notebookId)
        fun levelOf(s: SourceInfo) = levels[s.id] ?: ContextLevel.FULL
        val byId = context.insightsFor(sources.filter { levelOf(it) != ContextLevel.OFF }.map { it.id })

        val lines = sources.map { s ->
            val all = byId[s.id].orEmpty()
            val fresh = all.filter { !it.stale }
            ContextLine(
                source = s,
                level = levelOf(s),
                textTokens = tokensOf(s.characters),
                freshInsights = fresh.size,
                staleInsights = all.size - fresh.size,
                insightTokens = fresh.sumOf { KnowledgeTokens.estimate(clipInsight(it.body)) },
            )
        }
        val selection = select(sources.filter { levelOf(it) == ContextLevel.INSIGHTS }, byId)
        val searchable = lines.filter { it.level == ContextLevel.FULL }.sumOf { it.textTokens }
        val sent = selection.items.sumOf { KnowledgeTokens.estimate(it.text) }
        val excerptBudget = KnowledgeAsker.MAX_CONTEXT_CHARS / 4

        val warnings = ArrayList<String>()
        lines.forEach { l ->
            if (l.level == ContextLevel.INSIGHTS && l.freshInsights == 0) {
                warnings += "\"${l.source.title}\" is set to insights only but has no up-to-date insight, so the model sees nothing from it."
            }
            if (l.level != ContextLevel.OFF && l.staleInsights > 0) {
                warnings += "\"${l.source.title}\" has ${l.staleInsights} out-of-date insight(s) (its text or the prompt changed); they are not sent until made again."
            }
        }
        if (selection.omitted > 0) {
            warnings += "${selection.omitted} insight(s) do not fit the insight budget (about ${INSIGHT_BUDGET_CHARS / 4} tokens) and are left out."
        }
        if (searchable == 0 && selection.items.isEmpty()) {
            warnings += "Nothing in this notebook can reach the model right now."
        }
        return ContextReport(
            notebookId = notebookId,
            lines = lines,
            searchableTokens = searchable,
            sentInsightTokens = sent,
            excerptBudgetTokens = excerptBudget,
            insightBudgetTokens = INSIGHT_BUDGET_CHARS / 4,
            perQuestionTokensAtMost = minOf(searchable, excerptBudget) + sent,
            warnings = warnings,
        )
    }

    /** The insights of the notebook's INSIGHTS sources that go to the model with a question. */
    suspend fun insightsFor(notebookId: String): InsightSelection {
        val levels = context.levels(notebookId)
        val wanted = repo.listSources(notebookId).filter { levels[it.id] == ContextLevel.INSIGHTS }
        if (wanted.isEmpty()) return InsightSelection(emptyList(), 0)
        return select(wanted, context.insightsFor(wanted.map { it.id }))
    }

    private fun select(sources: List<SourceInfo>, byId: Map<String, List<Insight>>): InsightSelection {
        val items = ArrayList<InsightContextItem>()
        var used = 0
        var omitted = 0
        for (s in sources) {
            for (i in byId[s.id].orEmpty()) {
                if (i.stale) continue
                val text = clipInsight(i.body)
                if (used + text.length > INSIGHT_BUDGET_CHARS) {
                    omitted++
                    continue
                }
                items += InsightContextItem(i, s, text)
                used += text.length
            }
        }
        return InsightSelection(items, omitted)
    }

    private fun tokensOf(characters: Long): Int = ((characters + 3) / 4).coerceAtMost(Int.MAX_VALUE.toLong()).toInt()

    private fun clipInsight(body: String): String =
        if (body.length <= INSIGHT_MAX_CHARS) body else clipText(body, INSIGHT_MAX_CHARS - 1).trimEnd() + "\u2026"

    companion object {
        /** All insights sent with one question share this many characters, so they cannot crowd out the source excerpts. */
        const val INSIGHT_BUDGET_CHARS = 6_000
        const val INSIGHT_MAX_CHARS = 2_000
    }
}
