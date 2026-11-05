package ai.lily.app.gateway

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ModelFilterTest {
    private val models = mapOf(
        0 to listOf("gpt-4o", "Llama-3-70b", "free/mistral"),
        1 to listOf("gpt-4o", "claude-sonnet"),
    )

    @Test
    fun `all routers are listed by id and a model on both routers stays two rows`() {
        val rows = ModelFilter.rows(models, null, "")
        assertEquals(5, rows.size)
        assertEquals(listOf(ModelRow(0, "gpt-4o"), ModelRow(1, "gpt-4o")), rows.filter { it.id == "gpt-4o" })
        assertEquals(rows.map { it.id.lowercase() }, rows.map { it.id.lowercase() }.sorted())
    }

    @Test
    fun `one router can be chosen`() {
        assertTrue(ModelFilter.rows(models, 1, "").all { it.router == 1 })
        assertEquals(2, ModelFilter.rows(models, 1, "").size)
        assertTrue(ModelFilter.rows(models, 2, "").isEmpty())
    }

    @Test
    fun `the text matches every word in any order and ignores case`() {
        assertEquals(listOf(ModelRow(0, "Llama-3-70b")), ModelFilter.rows(models, null, "70B  llama"))
        assertEquals(2, ModelFilter.rows(models, null, "GPT").size)
        assertTrue(ModelFilter.rows(models, null, "gpt claude").isEmpty())
    }

    @Test
    fun `a repeated id in one router shows once and the count is before the text filter`() {
        val dup = mapOf(0 to listOf("a", "a", "b"))
        assertEquals(2, ModelFilter.rows(dup, null, "").size)
        assertEquals(2, ModelFilter.count(dup, null))
        assertEquals(5, ModelFilter.count(models, null))
        assertEquals(3, ModelFilter.count(models, 0))
        assertEquals(0, ModelFilter.count(emptyMap(), null))
    }
}
