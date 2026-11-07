package ai.lily.app.gateway

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class RouterImageMessageTest {

    @Test
    fun plainTextStaysAString() {
        val m = RouterModelGateway.userMessage(ModelRequest("hello"))
        assertEquals("user", m.getString("role"))
        assertEquals("hello", m.getString("content"))
    }

    @Test
    fun aPictureBecomesTheVisionForm() {
        val m = RouterModelGateway.userMessage(ModelRequest("review", images = listOf(ModelImage("image/jpeg", "AAA"))))
        val parts = m.getJSONArray("content")
        assertEquals(2, parts.length())
        assertEquals("text", parts.getJSONObject(0).getString("type"))
        assertEquals("review", parts.getJSONObject(0).getString("text"))
        assertEquals("image_url", parts.getJSONObject(1).getString("type"))
        assertEquals("data:image/jpeg;base64,AAA", parts.getJSONObject(1).getJSONObject("image_url").getString("url"))
    }

    @Test
    fun theMessageIsValidJson() {
        val m = RouterModelGateway.userMessage(ModelRequest("say \"hi\"", images = listOf(ModelImage("image/png", "QQ=="))))
        assertTrue(JSONObject(m.toString()).getJSONArray("content").length() == 2)
    }
}
