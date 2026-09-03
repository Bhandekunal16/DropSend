package com.example.data.connectivity

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.os.Build
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowNetwork

@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class HotspotAutoConnectorTest {

    private lateinit var context: Context
    private lateinit var connectivityManager: ConnectivityManager
    private lateinit var connector: HotspotAutoConnector

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        connectivityManager = context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        connector = HotspotAutoConnector(context)
    }

    // ==========================================
    // parseQrCode Tests
    // ==========================================

    @Test
    fun `test parseQrCode with dropsend URI containing all parameters`() {
        val qr = "dropsend://connect?ssid=DropSend_AP&pass=SuperSecret123&ip=192.168.49.1&port=9090&dev=Pixel_Pro&id=REV-0042"
        val params = connector.parseQrCode(qr)

        assertNotNull(params)
        assertEquals("DropSend_AP", params?.ssid)
        assertEquals("SuperSecret123", params?.passphrase)
        assertEquals("192.168.49.1", params?.ipAddress)
        assertEquals(9090, params?.port)
        assertEquals("Pixel_Pro", params?.deviceName)
        assertEquals("REV-0042", params?.deviceId)
    }

    @Test
    fun `test parseQrCode with dropsend URI containing URL encoded parameters`() {
        val qr = "dropsend://connect?ssid=Drop%20Send%20Hotspot&pass=p%40ssword&ip=192.168.43.1&dev=John's%20Phone"
        val params = connector.parseQrCode(qr)

        assertNotNull(params)
        assertEquals("Drop Send Hotspot", params?.ssid)
        assertEquals("p@ssword", params?.passphrase)
        assertEquals("192.168.43.1", params?.ipAddress)
        assertEquals(8888, params?.port)
        assertEquals("John's Phone", params?.deviceName)
        assertEquals("REV-43.1", params?.deviceId)
    }

    @Test
    fun `test parseQrCode with dropsend URI default values when optional parameters missing`() {
        val qr = "dropsend://connect?ssid=DropSend_AP&pass=secret"
        val params = connector.parseQrCode(qr)

        assertNotNull(params)
        assertEquals("DropSend_AP", params?.ssid)
        assertEquals("secret", params?.passphrase)
        assertEquals("192.168.43.1", params?.ipAddress)
        assertEquals(8888, params?.port)
        assertEquals("Nearby Receiver", params?.deviceName)
        assertEquals("REV-43.1", params?.deviceId)
    }

    @Test
    fun `test parseQrCode with standard WIFI QR format`() {
        val qr = "WIFI:S:MyLocalHotspot;T:WPA;P:MySecretPass;; "
        val params = connector.parseQrCode(qr)

        assertNotNull(params)
        assertEquals("MyLocalHotspot", params?.ssid)
        assertEquals("MySecretPass", params?.passphrase)
        assertEquals("192.168.43.1", params?.ipAddress)
        assertEquals(8888, params?.port)
        assertEquals("MyLocalHotspot", params?.deviceName)
        assertEquals("spot", params?.deviceId)
    }

    @Test
    fun `test parseQrCode with standard WIFI QR containing escaped characters`() {
        val qr = "WIFI:S:DropSend\\;Special;T:WPA;P:Pass\\\\Word\\;;;"
        val params = connector.parseQrCode(qr)

        assertNotNull(params)
        assertEquals("DropSend;Special", params?.ssid)
        assertEquals("Pass\\Word;", params?.passphrase)
    }

    @Test
    fun `test parseQrCode with direct IP and port`() {
        val qr = "192.168.43.100:8080"
        val params = connector.parseQrCode(qr)

        assertNotNull(params)
        assertEquals("", params?.ssid)
        assertEquals("", params?.passphrase)
        assertEquals("192.168.43.100", params?.ipAddress)
        assertEquals(8080, params?.port)
        assertEquals("Direct IP 192.168.43.100", params?.deviceName)
        assertEquals(".100", params?.deviceId)
    }

    @Test
    fun `test parseQrCode with direct IP without port defaults to 8888`() {
        val qr = "10.0.0.5"
        val params = connector.parseQrCode(qr)

        assertNotNull(params)
        assertEquals("", params?.ssid)
        assertEquals("10.0.0.5", params?.ipAddress)
        assertEquals(8888, params?.port)
    }

    @Test
    fun `test parseQrCode rejects malformed inputs safely`() {
        assertNull(connector.parseQrCode(""))
        assertNull(connector.parseQrCode("   "))
        assertNull(connector.parseQrCode("Hello DropSend"))
        assertNull(connector.parseQrCode("999.999.999.999:8888")) // Invalid IP octets
        assertNull(connector.parseQrCode("192.168.1.1:99999")) // Port out of range
        assertNull(connector.parseQrCode("WIFI:;; ")) // Empty fields
    }

    // ==========================================
    // Connection & Concurrency Tests
    // ==========================================

    @Test
    fun `test direct IP connection succeeds immediately without network request`() = runTest {
        val params = QrConnectionParams(
            ssid = "",
            passphrase = "",
            ipAddress = "192.168.43.1",
            port = 8888
        )

        var statusReceived = ""
        val result = connector.connectToHotspotNetwork(params) { status ->
            statusReceived = status
        }

        assertTrue(result)
        assertTrue(statusReceived.contains("192.168.43.1:8888"))
        assertNull(connector.activeCallback)
    }

    @Test
    fun `test successful hotspot connection when onAvailable fires`() = runTest {
        val params = QrConnectionParams(
            ssid = "DropSend-Receiver",
            passphrase = "password123",
            ipAddress = "192.168.43.1"
        )

        val statuses = mutableListOf<String>()
        val connectionJob = async {
            connector.connectToHotspotNetwork(params) { statuses.add(it) }
        }

        runCurrent()
        assertNotNull(connector.activeCallback)
        assertEquals("Connecting to Receiver's Hotspot: DropSend-Receiver...", statuses.first())

        // Simulate Android OS firing onAvailable
        val network = ShadowNetwork.newInstance(1234)
        connector.activeCallback?.onAvailable(network)
        runCurrent()

        val result = connectionJob.await()
        assertTrue(result)
        assertTrue(connector.isProcessNetworkBound)
        assertTrue(statuses.last().contains("Connected to hotspot"))

        // Cleanup
        connector.release()
        assertFalse(connector.isProcessNetworkBound)
        assertNull(connector.activeCallback)
    }

    @Test
    fun `test hotspot unavailable returns false immediately`() = runTest {
        val params = QrConnectionParams(
            ssid = "Unavailable-Hotspot",
            passphrase = "password123",
            ipAddress = "192.168.43.1"
        )

        val connectionJob = async {
            connector.connectToHotspotNetwork(params)
        }

        runCurrent()
        val callback = connector.activeCallback
        assertNotNull(callback)

        // Simulate onUnavailable
        callback?.onUnavailable()
        runCurrent()

        val result = connectionJob.await()
        assertFalse(result)
        assertFalse(connector.isProcessNetworkBound)
    }

    @Test
    fun `test stale callback from earlier attempt is safely ignored`() = runTest {
        val params1 = QrConnectionParams(ssid = "Hotspot-1", passphrase = "pass1", ipAddress = "192.168.43.1")
        val params2 = QrConnectionParams(ssid = "Hotspot-2", passphrase = "pass2", ipAddress = "192.168.43.2")

        val job1 = async { connector.connectToHotspotNetwork(params1) }
        runCurrent()
        val callback1 = connector.activeCallback
        assertNotNull(callback1)
        val attempt1Id = connector.currentAttempt

        // Start second attempt, which supersedes the first
        val job2 = async { connector.connectToHotspotNetwork(params2) }
        runCurrent()
        val callback2 = connector.activeCallback
        assertNotNull(callback2)
        val attempt2Id = connector.currentAttempt

        assertTrue(attempt2Id > attempt1Id)

        // First job should be resumed with false because it was superseded
        val result1 = job1.await()
        assertFalse(result1)

        // Stale callback1 fires onAvailable; should be discarded and not bind process
        val network1 = ShadowNetwork.newInstance(1001)
        callback1?.onAvailable(network1)
        runCurrent()

        // Active callback2 fires onAvailable
        val network2 = ShadowNetwork.newInstance(1002)
        callback2?.onAvailable(network2)
        runCurrent()

        val result2 = job2.await()
        assertTrue(result2)
        assertTrue(connector.isProcessNetworkBound)

        connector.release()
    }

    @Test
    fun `test release during active connection cancels and cleans up`() = runTest {
        val params = QrConnectionParams(
            ssid = "DropSend-AP",
            passphrase = "password123",
            ipAddress = "192.168.43.1"
        )

        val connectionJob = async {
            connector.connectToHotspotNetwork(params)
        }

        runCurrent()
        assertNotNull(connector.activeCallback)

        // User or ViewModel calls release() while waiting
        connector.release()
        runCurrent()

        val result = connectionJob.await()
        assertFalse(result)
        assertNull(connector.activeCallback)
        assertFalse(connector.isProcessNetworkBound)
    }

    @Test
    fun `test release is idempotent`() {
        connector.release()
        connector.release()
        connector.release()
        assertNull(connector.activeCallback)
        assertFalse(connector.isProcessNetworkBound)
    }

    @Test
    fun `test coroutine cancellation cleans up callback and process binding`() = runTest {
        val params = QrConnectionParams(
            ssid = "DropSend-AP",
            passphrase = "password123",
            ipAddress = "192.168.43.1"
        )

        val job = launch {
            connector.connectToHotspotNetwork(params)
        }

        runCurrent()
        assertNotNull(connector.activeCallback)

        job.cancelAndJoin()

        assertNull(connector.activeCallback)
        assertFalse(connector.isProcessNetworkBound)
    }

    @Test
    fun `test connection timeout cleans up after 15 seconds`() = runTest {
        val params = QrConnectionParams(
            ssid = "DropSend-NonExistent",
            passphrase = "password123",
            ipAddress = "192.168.43.1"
        )

        val connectionJob = async {
            connector.connectToHotspotNetwork(params)
        }

        runCurrent()
        assertNotNull(connector.activeCallback)

        // Advance virtual time past 15000ms
        advanceTimeBy(15001)
        runCurrent()

        val result = connectionJob.await()
        assertFalse(result)
        assertNull(connector.activeCallback)
        assertFalse(connector.isProcessNetworkBound)
    }
}
