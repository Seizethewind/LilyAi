package ai.lily.app.research

import ai.lily.app.memory.ScriptedGateway
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

class SearchProviderTest {

    @Test
    fun theJsonListIsReadEvenWithWordsAroundIt() {
        val reply = """Here you go: [{"title":"A","url":"https://a.example/x#frag","snippet":"first"},""" +
            """{"title":"B","url":"HTTP://B.example/"}] hope it helps"""

        val results = ModelSearchProvider.parse(reply, 10)

        assertEquals(
            listOf(
                SearchResult("A", "https://a.example/x", "first"),
                SearchResult("B", "http://b.example", ""),
            ),
            results,
        )
    }

    @Test
    fun addressesThatCannotBeSavedAreDropped() {
        val withLogin = "https://" + "someone" + ":" + "word" + "@" + "x.example/"
        val reply = """[{"url":"javascript:alert(1)"},{"url":"ftp://x.example/"},{"url":"$withLogin"},{"url":""},{"title":"none"},""" +
            """{"url":"not a url"},{"url":"https://ok.example/p","title":"  "}]"""

        val results = ModelSearchProvider.parse(reply, 10)

        assertEquals(listOf(SearchResult("https://ok.example/p", "https://ok.example/p", "")), results)
    }

    @Test
    fun repeatsAreDroppedAndTheLimitApplies() {
        val reply = """[{"url":"https://a.example/1"},{"url":"https://a.example/1#again"},{"url":"https://a.example/2"},{"url":"https://a.example/3"}]"""

        assertEquals(listOf("https://a.example/1", "https://a.example/2"), ModelSearchProvider.parse(reply, 2).map { it.url })
    }

    @Test
    fun aReplyInProseFallsBackToTheAddressesInIt() {
        val reply = "1. https://a.example/one.\n2. See (https://b.example/two), and https://a.example/one"

        assertEquals(listOf("https://a.example/one", "https://b.example/two"), ModelSearchProvider.parse(reply, 10).map { it.url })
    }

    @Test
    fun aListOfPlainStringsStillYieldsItsAddresses() {
        assertEquals(listOf("https://x.example/p"), ModelSearchProvider.parse("""["https://x.example/p"]""", 10).map { it.url })
    }

    @Test
    fun nothingUsableGivesAnEmptyList() {
        assertTrue(ModelSearchProvider.parse("I could not search.", 10).isEmpty())
        assertTrue(ModelSearchProvider.parse("[]", 10).isEmpty())
        assertTrue(ModelSearchProvider.parse("[{broken", 10).isEmpty())
    }

    @Test
    fun searchAsksTheChosenModelAndRefusesABlankQuery() = runBlocking<Unit> {
        val gateway = ScriptedGateway("""[{"url":"https://a.example/1","title":"One"}]""")
        val provider = ModelSearchProvider(gateway, "search-model")

        val results = provider.search(" spaghetti ", 3)

        assertEquals(listOf("One"), results.map { it.title })
        val request = gateway.requests.single()
        assertEquals("search-model", request.model)
        assertEquals(ModelSearchProvider.SYSTEM, request.system)
        assertTrue(request.prompt.contains("spaghetti"))

        try {
            provider.search("   ", 3)
            fail("should have thrown")
        } catch (e: IllegalArgumentException) {
            assertEquals(1, gateway.requests.size)
        }
    }

    @Test
    fun theResearchPlannerReadsAtMostFourDistinctQueries() {
        val reply = """Sure: ["a", " a ", "b", 7, "", "c", "d", "e"]"""

        assertEquals(listOf("a", "b", "c", "d"), ResearchPlanner.parse(reply))
        assertTrue(ResearchPlanner.parse("no list here").isEmpty())
        assertTrue(ResearchPlanner.parse("[oops").isEmpty())
    }
}
