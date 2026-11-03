package ai.lily.app.settings

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class EmbeddingSettingsTest {
    private fun fields(
        model: String = "",
        tag: String = "",
        dims: String = "",
        url: String = "",
        key: String = "",
        url2: String = "",
        model2: String = "",
        key2: String = "",
    ) = EmbeddingSettings.fromFields(model, tag, dims, url, key, url2, model2, key2)

    @Test
    fun nothingTypedMeansKeywordSearchOnly() {
        val f = fields()
        assertNull(f.settings)
        assertNull(f.problem)
        assertNull(fields(model = "  ", url = " ").settings)
    }

    @Test
    fun aModelAndAnAddressAreBothNeeded() {
        assertTrue(fields(model = "m").problem!!.contains("router address"))
        assertTrue(fields(key = "k").problem!!.contains("router address"))
        assertTrue(fields(url = "http://h:20128/v1").problem!!.contains("model id"))
    }

    @Test
    fun autoIsRefusedBecauseTheVectorSizeCouldChange() {
        val f = fields(model = "auto", url = "http://h/v1")
        assertNull(f.settings)
        assertTrue(f.problem!!.contains("auto"))
        assertTrue(fields(model = "auto:smart", url = "http://h/v1").problem!!.contains("auto"))
    }

    @Test
    fun dimensionsMustBeAWholeNumber() {
        assertEquals("Dimensions must be a whole number.", fields(model = "m", url = "http://h/v1", dims = "big").problem)
        assertEquals("Dimensions must be positive.", fields(model = "m", url = "http://h/v1", dims = "0").problem)
        assertEquals(256, fields(model = "m", url = "http://h/v1", dims = "256").settings!!.dimensions)
    }

    @Test
    fun theTagDefaultsToTheModelAndItsDimensions() {
        val plain = fields(model = " openai/te3 ", url = "http://h/v1").settings!!
        assertEquals("openai/te3", plain.effectiveTag)
        assertEquals("openai/te3", plain.endpoints().single().tag)

        val sized = fields(model = "te3", url = "http://h/v1", dims = "256").settings!!
        assertEquals("te3@256", sized.effectiveTag)

        val named = fields(model = "te3", tag = "  mine ", url = "http://h/v1").settings!!
        assertEquals("mine", named.effectiveTag)
    }

    @Test
    fun aFallbackSharesTheTagAndMayNameTheModelDifferently() {
        val s = fields(
            model = "openai/text-embedding-3-small",
            tag = "te3-small",
            url = "http://nine:20128/v1",
            key = "k1",
            url2 = "http://free:3001/v1",
            model2 = "text-embedding-3-small",
            key2 = "k2",
        ).settings!!

        val endpoints = s.endpoints()
        assertEquals(listOf("primary", "fallback"), endpoints.map { it.name })
        assertEquals(listOf("te3-small", "te3-small"), endpoints.map { it.tag })
        assertEquals(listOf("openai/text-embedding-3-small", "text-embedding-3-small"), endpoints.map { it.model })
        assertEquals(listOf("k1", "k2"), endpoints.map { it.apiKey })
    }

    @Test
    fun aFallbackWithoutAModelUsesTheMainOne() {
        val s = fields(model = "m", url = "http://a/v1", url2 = "http://b/v1").settings!!
        assertEquals(listOf("m", "m"), s.endpoints().map { it.model })
    }

    @Test
    fun aFallbackNeedsAnAddress() {
        assertEquals(
            "The fallback router needs an address.",
            fields(model = "m", url = "http://a/v1", key2 = "k").problem,
        )
        assertEquals(
            "The fallback router needs an address.",
            fields(model = "m", url = "http://a/v1", model2 = "other").problem,
        )
    }

    @Test
    fun problemIsNullForUsableSettingsAndTextOtherwise() {
        assertNull(EmbeddingSettings(model = "m", primary = EmbeddingServer("http://a/v1")).problem())
        assertNotNull(EmbeddingSettings(model = " ", primary = EmbeddingServer("http://a/v1")).problem())
        assertNotNull(EmbeddingSettings(model = "m", primary = EmbeddingServer(" ")).problem())
    }

    @Test
    fun checkCommandsAreOnePerLineAndBounded() {
        assertEquals(listOf("npm test", "npm run lint"), CheckCommands.parse("  npm test \n\n npm run lint\r\n"))
        assertTrue(CheckCommands.parse("").isEmpty())
        assertEquals(CheckCommands.MAX_LENGTH, CheckCommands.parse("x".repeat(CheckCommands.MAX_LENGTH + 50)).single().length)
        val many = (1..CheckCommands.MAX_COMMANDS + 5).joinToString("\n") { "cmd$it" }
        assertEquals(CheckCommands.MAX_COMMANDS, CheckCommands.parse(many).size)
        assertEquals("a\nb", CheckCommands.format(listOf("a", "b")))
        assertEquals(listOf("a", "b"), CheckCommands.parse(CheckCommands.format(listOf("a", "b"))))
    }
}
