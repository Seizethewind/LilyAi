package ai.lily.app.workers

import ai.lily.app.core.WorkerType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class WorkerLookTest {
    @Test
    fun `every role has a default icon and colour from the preset lists`() {
        for (role in WorkerType.values()) {
            assertTrue(WorkerLook.defaultIcon(role) in WorkerLook.icons)
            assertTrue(WorkerLook.defaultColor(role) in WorkerLook.colors)
        }
    }

    @Test
    fun `a blank or unknown key falls back to the role default and a known key is kept`() {
        assertEquals("check", WorkerLook.icon(WorkerType.TEST, ""))
        assertEquals("check", WorkerLook.icon(WorkerType.TEST, "no-such-icon"))
        assertEquals("star", WorkerLook.icon(WorkerType.TEST, "star"))
        assertEquals("pad", WorkerLook.color(WorkerType.TEST, "purple"))
        assertEquals("coral", WorkerLook.color(WorkerType.TEST, "coral"))
    }

    @Test
    fun `old saved json without an icon or colour still loads with the role look`() {
        val old = """[{"id":"1","name":"Old","role":"GIT","router":1,"model":"m","instructions":"i"}]"""
        val p = WorkerProfiles.fromJson(old).single()
        assertEquals("", p.iconKey)
        assertEquals("", p.colorKey)
        assertEquals("share", WorkerLook.icon(p.role, p.iconKey))
    }

    @Test
    fun `the chosen icon and colour survive saving and reading`() {
        val p = WorkerProfile("1", "T", WorkerType.TEST, 0, "m", "i", iconKey = "star", colorKey = "coral")
        assertEquals(listOf(p), WorkerProfiles.fromJson(WorkerProfiles.toJson(listOf(p))))
    }
}
