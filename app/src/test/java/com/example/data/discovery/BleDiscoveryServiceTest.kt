package com.example.data.discovery

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.util.UUID

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class BleDiscoveryServiceTest {

    private lateinit var context: Context
    private lateinit var bleDiscoveryService: BleDiscoveryService

    @Before
    fun setup() {
        context = ApplicationProvider.getApplicationContext()
        bleDiscoveryService = BleDiscoveryService(context)
    }

    @Test
    fun `test UUID constants and initial state`() {
        val expectedUuid = UUID.fromString("0000FD88-0000-1000-8000-00805F9B34FB")
        assertEquals(expectedUuid, BleDiscoveryService.SERVICE_UUID)
        assertEquals(expectedUuid, BleDiscoveryService.PARCEL_UUID.uuid)

        assertNotNull(bleDiscoveryService.discoveredDevices)
        assertTrue(bleDiscoveryService.discoveredDevices.value.isEmpty())
    }

    @Test
    fun `test clearDevices resets state flow`() {
        bleDiscoveryService.clearDevices()
        assertTrue(bleDiscoveryService.discoveredDevices.value.isEmpty())
    }

    @Test
    fun `test startScanning and stopScanning idempotency`() {
        // Repeated calls must not crash or leak
        bleDiscoveryService.startScanning()
        bleDiscoveryService.startScanning()

        bleDiscoveryService.stopScanning()
        bleDiscoveryService.stopScanning()
    }

    @Test
    fun `test startAdvertising and stopAdvertising idempotency`() {
        bleDiscoveryService.startAdvertising("DROP-TEST1", "Test Device")
        bleDiscoveryService.startAdvertising("DROP-TEST1", "Test Device")

        bleDiscoveryService.stopAdvertising()
        bleDiscoveryService.stopAdvertising()
    }
}
