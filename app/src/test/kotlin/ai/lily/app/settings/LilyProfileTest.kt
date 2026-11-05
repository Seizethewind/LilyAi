package ai.lily.app.settings

import org.junit.Assert.assertEquals
import org.junit.Test

class LilyProfileTest {
    @Test
    fun `settings saved before the profile existed load as the defaults`() {
        assertEquals(LilyProfile(), LilyProfile.read { "" })
        assertEquals("Lily", LilyProfile.read { "" }.name)
    }

    @Test
    fun `saved values are read back and a bad router falls back to the first`() {
        val saved = mapOf(
            "lily_name" to " Mira ", "lily_instructions" to "Answer briefly.", "lily_router" to "1",
            "lily_model" to " auto:smart ", "lily_photo" to "/data/lily/photo_1.jpg",
        )
        assertEquals(
            LilyProfile("Mira", "Answer briefly.", 1, "auto:smart", "/data/lily/photo_1.jpg"),
            LilyProfile.read { saved[it].orEmpty() },
        )
        assertEquals(0, LilyProfile.read { if (it == "lily_router") "7" else "" }.router)
        assertEquals("Lily", LilyProfile.read { if (it == "lily_name") "   " else "" }.name)
    }
}
