package ai.lily.app.discord

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class DiscordParseTest {
    private fun message(id: String, content: String = "x", type: Int = 0, files: List<String> = emptyList(), embeds: Int = 0) =
        DiscordMessage(id, "1", "someone", false, content, "2026-11-07T01:00:00.000000+00:00", null, files, embeds, type)

    @Test
    fun channelsAreLaidOutLikeTheDiscordApp() {
        val channels = DiscordParse.channels(
            """[
              {"id":"10","name":"NOTICE","type":4,"position":0,"parent_id":null},
              {"id":"11","name":"announcement","type":5,"position":0,"parent_id":"10"},
              {"id":"20","name":"SOCIAL-HUB","type":4,"position":1,"parent_id":null},
              {"id":"21","name":"voice-lounge","type":2,"position":0,"parent_id":"20"},
              {"id":"22","name":"chat","type":0,"position":1,"parent_id":"20"},
              {"id":"23","name":"friends","type":0,"position":2,"parent_id":"20"},
              {"id":"30","name":"rules","type":0,"position":0,"parent_id":null},
              {"id":"40","name":"orphan","type":0,"position":3,"parent_id":"999"}
            ]""",
        )
        val groups = groupChannels(channels)
        assertEquals(listOf<String?>(null, "NOTICE", "SOCIAL-HUB"), groups.map { it.category?.name })
        assertEquals(listOf("rules", "orphan"), groups[0].channels.map { it.name })
        assertEquals(listOf("announcement"), groups[1].channels.map { it.name })
        assertEquals(listOf("chat", "friends", "voice-lounge"), groups[2].channels.map { it.name })
    }

    @Test
    fun onlyTextAndAnnouncementChannelsAreReadable() {
        val channels = DiscordParse.channels(
            """[
              {"id":"1","name":"a","type":0},{"id":"2","name":"b","type":5},{"id":"3","name":"c","type":2},
              {"id":"4","name":"d","type":15},{"id":"5","name":"e","type":13},{"id":"6","name":"f","type":4}
            ]""",
        )
        assertEquals(listOf(true, true, false, false, false, false), channels.map { it.readable })
        assertTrue(channels[2].isVoiceLike && channels[4].isVoiceLike && channels[5].isCategory)
    }

    @Test
    fun whatOtherPeopleWroteCannotHideOrReorderText() {
        val messages = DiscordParse.messages(
            """[{"id":"7","type":0,"content":"a\r\nb\u0007c‮d","timestamp":"t","author":{"id":"1","username":"x‮y"},"attachments":[],"embeds":[]}]""",
        )
        val m = messages.single()
        assertEquals("a\nb\\u0007c\\u202ed", m.content)
        assertEquals("x\\u202ey", m.authorName)
        assertFalse(m.authorName.contains('‮') || m.content.contains('‮'))
    }

    @Test
    fun veryLongTextAndNamesAreCut() {
        val longName = "n".repeat(500)
        val longText = "t".repeat(9000)
        val m = DiscordParse.messages(
            """[{"id":"7","type":0,"content":"$longText","author":{"id":"1","username":"$longName"},"attachments":[{"filename":"$longName"}]}]""",
        ).single()
        assertEquals(4000, m.content.length)
        assertEquals(100, m.authorName.length)
        assertEquals(100, m.attachments.single().length)
    }

    @Test
    fun missingPiecesGetSafeDefaultsAndBadEntriesAreSkipped() {
        val messages = DiscordParse.messages("""[{"id":"8"},{"id":"not-a-number","content":"x"},5,{"content":"no id"}]""")
        val m = messages.single()
        assertEquals("unknown", m.authorName)
        assertEquals("", m.content)
        assertNull(m.editedTimestamp)
        assertTrue(m.attachments.isEmpty() && m.embedCount == 0 && !m.authorIsBot)
        assertTrue(m.isPlain)
    }

    @Test
    fun repliesAreNormalMessagesAndJoinsAndPinsAreNot() {
        assertTrue(message("1", type = 0).isPlain && message("1", type = 19).isPlain && message("1", type = 20).isPlain)
        assertFalse(message("1", type = 7).isPlain)
        assertFalse(message("1", type = 6).isPlain)
    }

    @Test
    fun idsCompareByTimeNotByAlphabet() {
        assertTrue(Snowflake.compare("99", "100") < 0)
        assertTrue(Snowflake.compare("100", "99") > 0)
        assertEquals(0, Snowflake.compare("123", "123"))
        assertTrue(Snowflake.compare("1234", "1235") < 0)
    }

    @Test
    fun onlyPlainNumbersAreValidIds() {
        assertTrue(Snowflake.isValid("123456789012345678"))
        assertTrue(Snowflake.isValid("1"))
        for (bad in listOf("", "0", "0123", "12a", "1/2", "1?x=1", "-5", " 12", "1".repeat(21))) {
            assertFalse("'$bad'", Snowflake.isValid(bad))
        }
    }

    @Test
    fun theFeedKeepsEachMessageOnceOldestFirstAndTheNewestCopyWins() {
        val before = listOf(message("100", "one"), message("101", "two"))
        val merged = MessageFeed.merge(before, listOf(message("102", "three"), message("101", "two (edited)"), message("99", "zero")))
        assertEquals(listOf("99", "100", "101", "102"), merged.map { it.id })
        assertEquals("two (edited)", merged.first { it.id == "101" }.content)
        assertEquals("102", MessageFeed.newestId(merged))
        assertNull(MessageFeed.newestId(emptyList()))
    }

    @Test
    fun theFeedKeepsOnlyTheNewestFiveHundred() {
        val many = (1000 until 1600).map { message(it.toString()) }
        val merged = MessageFeed.merge(emptyList(), many)
        assertEquals(MessageFeed.MAX_KEPT, merged.size)
        assertEquals("1100", merged.first().id)
        assertEquals("1599", merged.last().id)
    }

    @Test
    fun aChannelOfEmptyMessagesLooksLikeTheMessageContentIntentIsOff() {
        val blank = listOf(message("1", ""), message("2", ""), message("3", ""))
        assertTrue(MessageFeed.contentMayBeHidden(blank))
        assertFalse(MessageFeed.contentMayBeHidden(blank.take(2)))
        assertFalse(MessageFeed.contentMayBeHidden(blank + message("4", "hello")))
        assertFalse(MessageFeed.contentMayBeHidden(listOf(message("1", "", files = listOf("a.png")), message("2", ""), message("3", ""))))
        assertFalse(MessageFeed.contentMayBeHidden(listOf(message("1", "", embeds = 1), message("2", ""), message("3", ""))))
        assertFalse(MessageFeed.contentMayBeHidden(listOf(message("1", "", type = 7), message("2", "", type = 7), message("3", "", type = 7))))
    }
}
