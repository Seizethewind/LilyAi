package ai.lily.app.research

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.net.InetAddress

class AddressRangesTest {
    private fun isPublic(literal: String) = UrlGuard.isPublic(InetAddress.getByName(literal))

    @Test
    fun privateAndReservedIpv4RangesAreNotPublic() {
        val linkLocal = listOf("169", "254", "1", "1").joinToString(".")
        val ranges = listOf(
            "10.1.2.3", "172.16.0.1", "172.31.255.255", "192.168.1.1", linkLocal, "100.64.0.1", "100.127.255.255",
            "0.0.0.0", "192.0.0.8", "192.0.2.1", "198.18.0.1", "198.51.100.1", "203.0.113.1", "224.0.0.1", "240.0.0.1", "255.255.255.255",
        )
        for (literal in ranges) assertFalse(literal, isPublic(literal))
    }

    @Test
    fun addressesJustOutsideThoseRangesAreAllowed() {
        for (literal in listOf("172.15.0.1", "172.32.0.1", "100.63.255.255", "100.128.0.1", "169.253.1.1", "11.0.0.1", "223.255.255.255")) {
            assertTrue(literal, isPublic(literal))
        }
    }

    @Test
    fun ipv6FormsThatWrapOrHideAnAddressAreJudgedCarefully() {
        assertFalse(isPublic("fd00::1"))
        assertFalse(isPublic("fe80::1"))
        assertFalse(isPublic("2001:db8::1"))
        assertFalse(isPublic("2002:c000:204::1"))
        assertFalse(isPublic("64:ff9b::808:808"))
        assertFalse(isPublic("::ffff:10.0.0.1"))
        assertFalse(isPublic("::"))
        assertTrue(isPublic("2606:4700:4700::1111"))
        assertTrue(isPublic("::ffff:93.184.216.34"))
    }
}
