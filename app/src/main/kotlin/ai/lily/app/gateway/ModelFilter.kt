package ai.lily.app.gateway

/** One model id offered by one router. [router] is 0 or 1, the same index the settings use. */
data class ModelRow(val router: Int, val id: String)

/** Turns the models each router offers into the rows the Model list page shows. Pure, so it is tested without Android. */
object ModelFilter {
    /**
     * The rows of the routers chosen by [source] (null means every router) whose id contains every word of [query], in any order and
     * ignoring case. A model offered by both routers stays two rows. Rows are sorted by id, then router, so those twins sit together.
     */
    fun rows(models: Map<Int, List<String>>, source: Int?, query: String): List<ModelRow> {
        val words = query.lowercase().split(Regex("\\s+")).filter { it.isNotEmpty() }
        return all(models, source)
            .filter { row -> words.all { row.id.lowercase().contains(it) } }
            .sortedWith(compareBy({ it.id.lowercase() }, { it.id }, { it.router }))
    }

    /** How many models the chosen routers offer before any text filter. */
    fun count(models: Map<Int, List<String>>, source: Int?): Int = all(models, source).size

    // A router's list can repeat an id; the page keys rows by router and id, so each shows once.
    private fun all(models: Map<Int, List<String>>, source: Int?): List<ModelRow> =
        models.entries.filter { source == null || it.key == source }.flatMap { (router, ids) -> ids.distinct().map { ModelRow(router, it) } }
}
