package com.example.data.connectivity

import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothManager
import android.content.Context
import android.content.Intent
import android.net.ConnectivityManager
import android.net.wifi.WifiManager
import androidx.test.core.app.ApplicationProvider
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
import org.robolectric.shadows.ShadowLooper

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class ConnectivityMonitorTest {

    private lateinit var context: Context
    private lateinit var connectivityMonitor: ConnectivityMonitor
    private lateinit var wifiManager: WifiManager
    private lateinit var bluetoothManager: BluetoothManager

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        wifiManager = context.applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager
        bluetoothManager = context.getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager
        connectivityMonitor = ConnectivityMonitor(context)
    }

    @Test
    fun `test bluetooth enabled and disabled updates state`() {
        connectivityMonitor.startMonitoring()

        // Simulate Bluetooth ON broadcast
        val intentOn = Intent(BluetoothAdapter.ACTION_STATE_CHANGED).apply {
            putExtra(BluetoothAdapter.EXTRA_STATE, BluetoothAdapter.STATE_ON)
        }
        context.sendBroadcast(intentOn)
        ShadowLooper.idleMainLooper()
        assertTrue(connectivityMonitor.state.value.isBluetoothOn)
        assertTrue(connectivityMonitor.isBluetoothEnabled())

        // Simulate Bluetooth OFF broadcast
        val intentOff = Intent(BluetoothAdapter.ACTION_STATE_CHANGED).apply {
            putExtra(BluetoothAdapter.EXTRA_STATE, BluetoothAdapter.STATE_OFF)
        }
        context.sendBroadcast(intentOff)
        ShadowLooper.idleMainLooper()
        assertFalse(connectivityMonitor.state.value.isBluetoothOn)
        assertFalse(connectivityMonitor.isBluetoothEnabled())

        connectivityMonitor.stopMonitoring()
    }

    @Test
    fun `test wifi enabled and disabled updates state`() {
        connectivityMonitor.startMonitoring()

        val shadowWifi = Shadows.shadowOf(wifiManager)
        shadowWifi.setWifiState(WifiManager.WIFI_STATE_ENABLED)

        val intentOn = Intent(WifiManager.WIFI_STATE_CHANGED_ACTION).apply {
            putExtra(WifiManager.EXTRA_WIFI_STATE, WifiManager.WIFI_STATE_ENABLED)
        }
        context.sendBroadcast(intentOn)
        ShadowLooper.idleMainLooper()
        assertTrue(connectivityMonitor.isWifiEnabled())

        shadowWifi.setWifiState(WifiManager.WIFI_STATE_DISABLED)
        val intentOff = Intent(WifiManager.WIFI_STATE_CHANGED_ACTION).apply {
            putExtra(WifiManager.EXTRA_WIFI_STATE, WifiManager.WIFI_STATE_DISABLED)
        }
        context.sendBroadcast(intentOff)
        ShadowLooper.idleMainLooper()
        assertFalse(connectivityMonitor.isWifiEnabled())
        assertNull(connectivityMonitor.state.value.localIpAddress)

        connectivityMonitor.stopMonitoring()
    }

    @Test
    fun `test wifi to wifi transition prevents stale IP`() {
        connectivityMonitor.startMonitoring()
        connectivityMonitor.testIpLookup = { "192.168.1.100" }

        connectivityMonitor.updateState()
        assertEquals("192.168.1.100", connectivityMonitor.state.value.localIpAddress)

        // Wi-Fi network switch broadcast
        connectivityMonitor.testIpLookup = { "10.0.0.45" }
        val netSwitchIntent = Intent(WifiManager.NETWORK_STATE_CHANGED_ACTION)
        context.sendBroadcast(netSwitchIntent)
        ShadowLooper.idleMainLooper()

        assertEquals("10.0.0.45", connectivityMonitor.state.value.localIpAddress)
        connectivityMonitor.stopMonitoring()
    }

    @Test
    fun `test wifi to hotspot transition refreshes IP`() {
        connectivityMonitor.startMonitoring()
        connectivityMonitor.testIpLookup = { "192.168.1.50" }
        connectivityMonitor.updateState()
        assertEquals("192.168.1.50", connectivityMonitor.state.value.localIpAddress)

        // Hotspot broadcast fires
        connectivityMonitor.testIpLookup = { "192.168.43.1" }
        val hotspotIntent = Intent("android.net.wifi.WIFI_AP_STATE_CHANGED")
        context.sendBroadcast(hotspotIntent)
        ShadowLooper.idleMainLooper()

        assertEquals("192.168.43.1", connectivityMonitor.state.value.localIpAddress)
        connectivityMonitor.stopMonitoring()
    }

    @Test
    fun `test wifi to wifi direct p2p transition and back`() {
        connectivityMonitor.startMonitoring()
        connectivityMonitor.testIpLookup = { "192.168.1.50" }
        connectivityMonitor.updateState()
        assertEquals("192.168.1.50", connectivityMonitor.state.value.localIpAddress)

        // Wi-Fi Direct connection established
        connectivityMonitor.testIpLookup = { "192.168.49.1" }
        val p2pIntent = Intent("android.net.wifi.p2p.CONNECTION_STATE_CHANGE")
        context.sendBroadcast(p2pIntent)
        ShadowLooper.idleMainLooper()

        assertEquals("192.168.49.1", connectivityMonitor.state.value.localIpAddress)

        // Wi-Fi Direct disconnects and returns to Wi-Fi
        connectivityMonitor.testIpLookup = { "192.168.1.75" }
        val p2pBackIntent = Intent("android.net.wifi.p2p.CONNECTION_STATE_CHANGE")
        context.sendBroadcast(p2pBackIntent)
        ShadowLooper.idleMainLooper()

        assertEquals("192.168.1.75", connectivityMonitor.state.value.localIpAddress)
        connectivityMonitor.stopMonitoring()
    }

    @Test
    fun `test network loss and reconnect`() {
        connectivityMonitor.startMonitoring()
        connectivityMonitor.testIpLookup = { "192.168.1.20" }
        connectivityMonitor.updateState()
        assertEquals("192.168.1.20", connectivityMonitor.state.value.localIpAddress)

        // Wi-Fi disabled / network lost
        val shadowWifi = Shadows.shadowOf(wifiManager)
        shadowWifi.setWifiState(WifiManager.WIFI_STATE_DISABLED)
        connectivityMonitor.testIpLookup = { null }

        val wifiLostIntent = Intent(WifiManager.WIFI_STATE_CHANGED_ACTION).apply {
            putExtra(WifiManager.EXTRA_WIFI_STATE, WifiManager.WIFI_STATE_DISABLED)
        }
        context.sendBroadcast(wifiLostIntent)
        ShadowLooper.idleMainLooper()

        assertNull(connectivityMonitor.state.value.localIpAddress)
        assertFalse(connectivityMonitor.state.value.isWifiOn)

        // Reconnect
        shadowWifi.setWifiState(WifiManager.WIFI_STATE_ENABLED)
        connectivityMonitor.testIpLookup = { "192.168.1.99" }
        val wifiReconnectedIntent = Intent(WifiManager.WIFI_STATE_CHANGED_ACTION).apply {
            putExtra(WifiManager.EXTRA_WIFI_STATE, WifiManager.WIFI_STATE_ENABLED)
        }
        context.sendBroadcast(wifiReconnectedIntent)
        ShadowLooper.idleMainLooper()

        assertEquals("192.168.1.99", connectivityMonitor.state.value.localIpAddress)
        assertTrue(connectivityMonitor.state.value.isWifiOn)

        connectivityMonitor.stopMonitoring()
    }

    @Test
    fun `test ip address change or DHCP renewal`() {
        connectivityMonitor.startMonitoring()
        connectivityMonitor.testIpLookup = { "192.168.1.10" }
        connectivityMonitor.updateState()
        assertEquals("192.168.1.10", connectivityMonitor.state.value.localIpAddress)

        // DHCP leases a new IP
        connectivityMonitor.testIpLookup = { "192.168.1.250" }
        @Suppress("DEPRECATION")
        val connectivityChangeIntent = Intent(ConnectivityManager.CONNECTIVITY_ACTION)
        context.sendBroadcast(connectivityChangeIntent)
        ShadowLooper.idleMainLooper()

        assertEquals("192.168.1.250", connectivityMonitor.state.value.localIpAddress)
        connectivityMonitor.stopMonitoring()
    }

    @Test
    fun `test null activeNetwork does not crash and resolves fallback IP`() {
        val shadowWifi = Shadows.shadowOf(wifiManager)
        shadowWifi.setWifiState(WifiManager.WIFI_STATE_ENABLED)
        connectivityMonitor.testIpLookup = { "192.168.49.1" }

        connectivityMonitor.startMonitoring()
        connectivityMonitor.updateState()

        assertNotNull(connectivityMonitor.state.value)
        assertEquals("192.168.49.1", connectivityMonitor.state.value.localIpAddress)

        connectivityMonitor.stopMonitoring()
    }

    @Test
    fun `test SecurityException handled gracefully without crash`() {
        connectivityMonitor.testBluetoothAdapterEnabled = {
            throw SecurityException("BLUETOOTH_CONNECT permission missing")
        }
        // Should catch SecurityException and return false safely
        assertFalse(connectivityMonitor.isBluetoothEnabled())
    }

    @Test
    fun `test repeated startMonitoring and stopMonitoring is idempotent`() {
        // Repeated starts must be idempotent and safe
        connectivityMonitor.startMonitoring()
        connectivityMonitor.startMonitoring()
        connectivityMonitor.startMonitoring()

        assertNotNull(connectivityMonitor.state.value)

        // Repeated stops must be idempotent and safe
        connectivityMonitor.stopMonitoring()
        connectivityMonitor.stopMonitoring()
        connectivityMonitor.stopMonitoring()

        // Restarting after stop should work cleanly
        connectivityMonitor.startMonitoring()
        connectivityMonitor.stopMonitoring()
    }

    @Test
    fun `test interface enumeration is not executed on normal cached state update`() {
        val shadowWifi = Shadows.shadowOf(wifiManager)
        shadowWifi.setWifiState(WifiManager.WIFI_STATE_ENABLED)

        // Reset scan counter
        connectivityMonitor.interfaceScanCount = 0

        // In isWifiEnabled(), WifiManager returns true on step 1, so interfaceScanCount should stay 0
        assertTrue(connectivityMonitor.isWifiEnabled())
        assertEquals(0, connectivityMonitor.interfaceScanCount)
    }
}
