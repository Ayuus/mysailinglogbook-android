package com.ayuus.mysailinglogbook

import java.net.Inet4Address
import java.net.InetAddress
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * HotspotDetector.isPrivateIpv4() is the one piece of this app's own (non-nmea2log) logic that's
 * pure enough to unit-test directly -- detectSubnetPrefix()/isHotspotUp() themselves need real
 * NetworkInterfaces, which this project doesn't have Robolectric (or similar) set up to fake.
 *
 * InetAddress.getByName() with a dotted-quad literal never does a real DNS lookup (no network
 * I/O), so these are safe to run offline, same as any other pure unit test.
 */
class HotspotDetectorTest {

    private fun ipv4(address: String): Inet4Address = InetAddress.getByName(address) as Inet4Address

    @Test
    fun `10 dot x dot x dot x is private regardless of the second octet`() {
        assertTrue(HotspotDetector.isPrivateIpv4(ipv4("10.0.0.1")))
        assertTrue(HotspotDetector.isPrivateIpv4(ipv4("10.255.255.255")))
        assertTrue(HotspotDetector.isPrivateIpv4(ipv4("10.190.25.4"))) // the S23's own ap_br_swlan0, see the doc comment
    }

    @Test
    fun `172 dot 16 through 172 dot 31 is private`() {
        assertTrue(HotspotDetector.isPrivateIpv4(ipv4("172.16.0.1")))
        assertTrue(HotspotDetector.isPrivateIpv4(ipv4("172.31.255.255")))
        assertTrue(HotspotDetector.isPrivateIpv4(ipv4("172.20.5.5")))
    }

    @Test
    fun `172 dot 15 and 172 dot 32 are just outside the private range`() {
        assertFalse(HotspotDetector.isPrivateIpv4(ipv4("172.15.255.255")))
        assertFalse(HotspotDetector.isPrivateIpv4(ipv4("172.32.0.0")))
    }

    @Test
    fun `192 dot 168 dot x dot x is private`() {
        assertTrue(HotspotDetector.isPrivateIpv4(ipv4("192.168.0.1")))
        assertTrue(HotspotDetector.isPrivateIpv4(ipv4("192.168.255.255")))
    }

    @Test
    fun `192 dot 167 and 192 dot 169 are not private`() {
        assertFalse(HotspotDetector.isPrivateIpv4(ipv4("192.167.0.1")))
        assertFalse(HotspotDetector.isPrivateIpv4(ipv4("192.169.0.1")))
    }

    @Test
    fun `public addresses are not private`() {
        assertFalse(HotspotDetector.isPrivateIpv4(ipv4("8.8.8.8")))
        assertFalse(HotspotDetector.isPrivateIpv4(ipv4("1.1.1.1")))
    }

    @Test
    fun `loopback is not private by this check -- callers filter it separately`() {
        // detectSubnetPrefix()/isHotspotUp() both already exclude loopback via
        // NetworkInterface.isLoopback before ever reaching isPrivateIpv4() -- this confirms
        // isPrivateIpv4() itself doesn't also (redundantly, or wrongly) treat it as private.
        assertFalse(HotspotDetector.isPrivateIpv4(ipv4("127.0.0.1")))
    }
}
