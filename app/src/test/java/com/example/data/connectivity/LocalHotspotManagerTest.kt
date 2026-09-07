package com.example.data.connectivity

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class LocalHotspotManagerTest {

    private lateinit var context: Context
    private lateinit var hotspotManager: LocalHotspotManager

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        hotspotManager = LocalHotspotManager(context)
    }

    @Test
    fun `test createHotspotInfo with discovered IP includes ip parameter in QR payload`() {
        val info = hotspotManager.createHotspotInfo(
            ssid = "Direct-DropSend",
            pass = "secret123",
            ip = "192.168.49.1",
            deviceId = "REV-49.1",
            deviceName = "Pixel 8",
            isActive = true,
        )

        assertEquals("192.168.49.1", info.ipAddress)
        assertTrue(info.connectionPayload.contains("&ip=192.168.49.1"))
        assertTrue(info.standardWifiQr.contains("WIFI:S:Direct-DropSend;"))
        assertFalse(info.standardWifiQr.contains("192.168"))
    }

    @Test
    fun `test createHotspotInfo when IP discovery fails does not publish fake 192_168_43_1`() {
        // When IP discovery fails, ip is empty string
        val info = hotspotManager.createHotspotInfo(
            ssid = "Direct-DropSend",
            pass = "secret123",
            ip = "",
            deviceId = "REV-HOTSPOT",
            deviceName = "Pixel 8",
            isActive = true,
            errorMessage = "IP discovery timed out",
        )

        assertEquals("", info.ipAddress)
        // Dropsend payload must NOT contain a fake 192.168.43.1
        assertFalse(info.connectionPayload.contains("&ip=192.168.43.1"))
        assertFalse(info.connectionPayload.contains("&ip="))
        assertEquals("IP discovery timed out", info.errorMessage)
    }

    @Test
    fun `test candidate IP ordering priority logic`() {
        // Simulates candidate ordering:
        // 1. gateway IP from network
        // 2. QR IP
        // 3. alternate IPs from QR
        val discoveredGateway = "192.168.49.1"
        val qrIp = "192.168.1.100"
        val alternateIps = listOf("10.0.0.4", "192.168.49.1") // duplicate candidate should be deduplicated

        val candidates = mutableListOf<String>()
        candidates.add(discoveredGateway)
        if (qrIp.isNotBlank()) candidates.add(qrIp)
        candidates.addAll(alternateIps.filter { it.isNotBlank() })

        val uniqueCandidates = candidates.distinct()

        assertEquals(3, uniqueCandidates.size)
        // Discovered gateway must be first
        assertEquals("192.168.49.1", uniqueCandidates[0])
        // QR IP must be second
        assertEquals("192.168.1.100", uniqueCandidates[1])
        // Alternate IP third
        assertEquals("10.0.0.4", uniqueCandidates[2])
    }
}
