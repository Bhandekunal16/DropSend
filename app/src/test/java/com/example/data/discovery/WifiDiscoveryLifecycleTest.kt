package com.example.data.discovery

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.example.domain.model.DiscoveredDevice
import com.example.domain.model.TransportType
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
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
class WifiDiscoveryLifecycleTest {

    private lateinit var context: Context
    private lateinit var wifiP2pManager: WifiP2pDirectManager
    private lateinit var deviceDiscoveryManager: DeviceDiscoveryManager

    @Before
    fun setup() {
        context = ApplicationProvider.getApplicationContext()
        wifiP2pManager = WifiP2pDirectManager(context)
        deviceDiscoveryManager = DeviceDiscoveryManager(context)
    }

    @Test
    fun `test startDiscovery is idempotent and preserves generation while active`() {
        val gen1 = wifiP2pManager.startDiscovery()
        assertTrue("First generation should be positive", gen1 > 0)
        wifiP2pManager.setLifecycleStateForTesting(DiscoveryLifecycleState.ACTIVE)
        assertEquals(gen1, wifiP2pManager.currentGeneration)
        val session1 = wifiP2pManager.activeSession
        assertNotNull("Active session should exist", session1)
        assertEquals(gen1, session1?.generation)

        // Calling startDiscovery again while active should be a no-op returning the same generation
        val gen2 = wifiP2pManager.startDiscovery()
        assertEquals("Idempotent startDiscovery must return existing generation", gen1, gen2)
        assertEquals("Session must not change on duplicate startDiscovery", session1?.sessionId, wifiP2pManager.activeSession?.sessionId)
    }

    @Test
    fun `test stale callback from generation N minus 1 is discarded in generation N`() {
        // Start first discovery session (gen1)
        val gen1 = wifiP2pManager.startDiscovery()
        wifiP2pManager.setLifecycleStateForTesting(DiscoveryLifecycleState.ACTIVE)

        val peerGen1 = DiscoveredDevice(
            id = "P2P-1111",
            name = "Old Peer Device",
            transportType = TransportType.WIFI_DIRECT,
            lastSeenTimestamp = System.currentTimeMillis()
        )
        val accepted1 = wifiP2pManager.injectPeersForTesting(gen1, listOf(peerGen1))
        assertTrue("Peers matching current generation should be accepted", accepted1)
        assertEquals(1, wifiP2pManager.discoveredPeers.value.size)
        assertEquals("P2P-1111", wifiP2pManager.discoveredPeers.value.first().id)

        // Advance to a new discovery session (gen2)
        wifiP2pManager.stopDiscovery()
        val gen2 = wifiP2pManager.startDiscovery()
        wifiP2pManager.setLifecycleStateForTesting(DiscoveryLifecycleState.ACTIVE)
        assertNotEquals(gen1, gen2)
        assertTrue("New discovery immediately purges peer list", wifiP2pManager.discoveredPeers.value.isEmpty())

        // Simulate a late/stale callback from gen1 arriving during gen2
        val acceptedLate = wifiP2pManager.injectPeersForTesting(gen1, listOf(peerGen1))
        assertFalse("Stale generation callback must be rejected", acceptedLate)
        assertTrue("Peer list must remain empty after rejecting stale callback", wifiP2pManager.discoveredPeers.value.isEmpty())

        // Delivering peers with gen2 should be accepted
        val peerGen2 = DiscoveredDevice(
            id = "P2P-2222",
            name = "New Peer Device",
            transportType = TransportType.WIFI_DIRECT,
            lastSeenTimestamp = System.currentTimeMillis()
        )
        val accepted2 = wifiP2pManager.injectPeersForTesting(gen2, listOf(peerGen2))
        assertTrue("Peers matching new generation should be accepted", accepted2)
        assertEquals(1, wifiP2pManager.discoveredPeers.value.size)
        assertEquals("P2P-2222", wifiP2pManager.discoveredPeers.value.first().id)
    }

    @Test
    fun `test restarting discovery purges stale peers immediately`() {
        val gen1 = wifiP2pManager.startDiscovery()
        wifiP2pManager.setLifecycleStateForTesting(DiscoveryLifecycleState.ACTIVE)
        wifiP2pManager.injectPeersForTesting(
            gen1,
            listOf(
                DiscoveredDevice(
                    id = "P2P-ABCD",
                    name = "Cached Peer",
                    transportType = TransportType.WIFI_DIRECT
                )
            )
        )
        assertEquals(1, wifiP2pManager.discoveredPeers.value.size)

        // Restart discovery
        wifiP2pManager.stopDiscovery()
        assertEquals("Stop discovery clears discovered peers", 0, wifiP2pManager.discoveredPeers.value.size)
        assertEquals(DiscoveryLifecycleState.STOPPED, wifiP2pManager.currentLifecycleState)

        wifiP2pManager.startDiscovery()
        assertEquals("New start discovery starts with empty discovered peers", 0, wifiP2pManager.discoveredPeers.value.size)
    }

    @Test
    fun `test unregister resets state and closes discovery`() {
        val gen = wifiP2pManager.startDiscovery()
        wifiP2pManager.setLifecycleStateForTesting(DiscoveryLifecycleState.ACTIVE)
        wifiP2pManager.injectPeersForTesting(
            gen,
            listOf(
                DiscoveredDevice(
                    id = "P2P-9999",
                    name = "Temporary Device",
                    transportType = TransportType.WIFI_DIRECT
                )
            )
        )
        assertEquals(1, wifiP2pManager.discoveredPeers.value.size)

        wifiP2pManager.unregister()
        assertEquals(DiscoveryLifecycleState.STOPPED, wifiP2pManager.currentLifecycleState)
        assertTrue("Peers must be cleared on unregister", wifiP2pManager.discoveredPeers.value.isEmpty())
    }

    @Test
    fun `test device discovery manager clear empties all discovery flows`() {
        deviceDiscoveryManager.clear()
        assertTrue(deviceDiscoveryManager.nearbyDevices.value.isEmpty())
        assertTrue(deviceDiscoveryManager.lanDiscovery.discoveredDevices.value.isEmpty())
        assertTrue(deviceDiscoveryManager.bleDiscovery.discoveredDevices.value.isEmpty())
        assertTrue(deviceDiscoveryManager.wifiP2pManager.discoveredPeers.value.isEmpty())
    }

    @Test
    fun `test stopDiscovery on device discovery manager halts sub managers and clears state`() {
        val gen1 = deviceDiscoveryManager.startDiscovery("DROP-LOCAL")
        assertTrue(gen1 > 0)
        assertEquals(gen1, deviceDiscoveryManager.currentGeneration)

        deviceDiscoveryManager.stopDiscovery()
        val gen2 = deviceDiscoveryManager.currentGeneration
        assertTrue("Generation increments on stop to invalidate in-flight callbacks", gen2 > gen1)
        assertTrue(deviceDiscoveryManager.nearbyDevices.value.isEmpty())
        assertTrue(deviceDiscoveryManager.wifiP2pManager.discoveredPeers.value.isEmpty())
    }

    @Test
    fun `test peer deduplication by deviceId across updates`() {
        val now = System.currentTimeMillis()
        val peerInitial = DiscoveredDevice(
            id = "DROP-A1B2",
            name = "Pixel 8",
            transportType = TransportType.BLUETOOTH,
            bluetoothAddress = "AA:BB:CC:DD:EE:FF",
            lastSeenTimestamp = now,
            sessionId = "sess-1",
            discoveryGeneration = 1L
        )

        // Simulate incoming update for the same device_id with a new session and Wi-Fi Direct endpoint
        val peerUpdated = DiscoveredDevice(
            id = "DROP-A1B2",
            name = "Pixel 8 Pro",
            transportType = TransportType.WIFI_DIRECT,
            ipAddress = "192.168.49.1",
            port = 8888,
            bluetoothAddress = "AA:BB:CC:DD:EE:FF",
            lastSeenTimestamp = now + 5000,
            sessionId = "sess-2",
            discoveryGeneration = 2L
        )

        val map = mutableMapOf<String, DiscoveredDevice>()
        map[peerInitial.id] = peerInitial
        assertEquals(1, map.size)

        // Updating by device_id replaces/updates the existing peer
        val existing = map[peerUpdated.id]
        assertNotNull(existing)
        val merged = existing!!.copy(
            name = peerUpdated.name,
            transportType = peerUpdated.transportType,
            ipAddress = peerUpdated.ipAddress,
            port = peerUpdated.port,
            sessionId = peerUpdated.sessionId,
            discoveryGeneration = peerUpdated.discoveryGeneration,
            lastSeenTimestamp = peerUpdated.lastSeenTimestamp
        )
        map[peerUpdated.id] = merged

        assertEquals("Map size must remain 1 without duplicates", 1, map.size)
        val result = map["DROP-A1B2"]
        assertNotNull(result)
        assertEquals("Pixel 8 Pro", result?.name)
        assertEquals(TransportType.WIFI_DIRECT, result?.transportType)
        assertEquals("192.168.49.1", result?.ipAddress)
        assertEquals("sess-2", result?.sessionId)
        assertEquals(2L, result?.discoveryGeneration)
    }

    @Test
    fun `test peer expiry drops peers older than 15 seconds`() {
        val now = System.currentTimeMillis()
        val freshPeer = DiscoveredDevice(
            id = "DROP-FRESH",
            name = "Fresh Device",
            transportType = TransportType.LOCAL_WIFI,
            lastSeenTimestamp = now - 5000 // 5s ago
        )
        val stalePeer = DiscoveredDevice(
            id = "DROP-STALE",
            name = "Stale Device",
            transportType = TransportType.WIFI_DIRECT,
            lastSeenTimestamp = now - 16000 // 16s ago (> 15s)
        )

        val list = listOf(freshPeer, stalePeer)
        val filtered = list.filter { (now - it.lastSeenTimestamp) < DeviceDiscoveryManager.PEER_EXPIRY_MS }

        assertEquals(1, filtered.size)
        assertEquals("DROP-FRESH", filtered.first().id)
    }
}
