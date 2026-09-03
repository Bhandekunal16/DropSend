package com.example.data.discovery

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.example.domain.model.TransportType
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class LanDiscoveryServiceTest {

    private lateinit var context: Context
    private lateinit var lanDiscovery: LanDiscoveryService

    @Before
    fun setup() {
        context = ApplicationProvider.getApplicationContext()
        lanDiscovery = LanDiscoveryService(context)
    }

    @Test
    fun testGetLocalIpAddressesReturnsListAndCaches() {
        val ips1 = lanDiscovery.getLocalIpAddresses()
        assertNotNull(ips1)
        val ips2 = lanDiscovery.getLocalIpAddresses(forceRefresh = false)
        assertEquals("Cached IP list should match", ips1, ips2)
    }

    @Test
    fun testStartAndStopDiscoveryIdempotency() = runTest {
        lanDiscovery.startDiscovery("LOCAL_DEVICE_1")
        // Rapid second start with same ID should be clean
        lanDiscovery.startDiscovery("LOCAL_DEVICE_1")

        lanDiscovery.stopDiscovery()
        // Redundant stop should not throw
        lanDiscovery.stopDiscovery()

        assertEquals(0, lanDiscovery.discoveredDevices.value.size)
    }

    @Test
    fun testStartAndStopAdvertisingIdempotency() = runTest {
        lanDiscovery.startAdvertising("LOCAL_DEVICE_1", "Test Device", LanDiscoveryService.DEFAULT_TCP_PORT)
        lanDiscovery.startAdvertising("LOCAL_DEVICE_1", "Test Device", LanDiscoveryService.DEFAULT_TCP_PORT)

        lanDiscovery.stopAdvertising()
        lanDiscovery.stopAdvertising()
    }

    @Test
    fun testClearDevices() {
        lanDiscovery.clearDevices()
        assertTrue(lanDiscovery.discoveredDevices.value.isEmpty())
    }

    @Test
    fun testReleaseCancelsCleanly() {
        lanDiscovery.startDiscovery("LOCAL_DEVICE_1")
        lanDiscovery.startAdvertising("LOCAL_DEVICE_1", "Test Device", 8888)
        lanDiscovery.release()

        assertTrue(lanDiscovery.discoveredDevices.value.isEmpty())
    }
}
