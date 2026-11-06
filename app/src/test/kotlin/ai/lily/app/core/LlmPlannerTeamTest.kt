package ai.lily.app.core

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** The planner only knows the TEAM role when Lily has a roster to run it on. */
class LlmPlannerTeamTest {
    @Test
    fun withARosterThePlannerMayChooseTeam() {
        val prompt = LlmPlanner.systemPrompt(teamRole = true)
        assertTrue(prompt.contains("GENERAL, TEST, TEAM"))
        assertTrue(prompt.contains("create, change or dismiss"))
    }

    @Test
    fun withoutOneTeamIsNeverMentioned() {
        assertFalse(LlmPlanner.systemPrompt(teamRole = false).contains("TEAM"))
    }
}
