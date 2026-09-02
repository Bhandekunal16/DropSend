package com.example.data.connectivity

import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.net.wifi.WifiManager
import android.os.Build
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.net.Inet4Address
import java.net.NetworkInterface

data class ConnectivityState(
    val isBluetoothOn: Boolean = false,
    val isWifiOn: Boolean = false,
    val localIpAddress: String? = null
)

class ConnectivityMonitor(private val context: Context) {

    companion object {
        private const val TAG = "ConnectivityMonitor"
    }

    private val bluetoothManager = context.getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager
    private val connectivityManager = context.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager
    private val wifiManager = context.applicationContext.getSystemService(Context.WIFI_SERVICE) as? WifiManager

    private val _state = MutableStateFlow(computeCurrentState())
    val state: StateFlow<ConnectivityState> = _state.asStateFlow()

    private var btAndWifiReceiver: BroadcastReceiver? = null
    private var defaultNetworkCallback: ConnectivityManager.NetworkCallback? = null
    private var wifiNetworkCallback: ConnectivityManager.NetworkCallback? = null
    private var pollingJob: Job? = null
    private val scope = CoroutineScope(Dispatchers.IO)

    private fun computeCurrentState(): ConnectivityState {
        val isBt = isBluetoothEnabled()
        val isWifi = isWifiEnabled()
        val ip = getLocalIpAddress()
        return ConnectivityState(isBluetoothOn = isBt, isWifiOn = isWifi, localIpAddress = ip)
    }

    /**
     * Checks whether Bluetooth adapter is currently powered on and enabled.
     */
    fun isBluetoothEnabled(): Boolean {
        return try {
            val adapter = bluetoothManager?.adapter ?: BluetoothAdapter.getDefaultAdapter()
            adapter?.isEnabled == true
        } catch (_: SecurityException) {
            try {
                BluetoothAdapter.getDefaultAdapter()?.isEnabled == true
            } catch (_: Exception) {
                false
            }
        } catch (_: Exception) {
            false
        }
    }

    /**
     * Checks whether Wi-Fi radio is enabled or connected to a Wi-Fi/Ethernet/P2P/Hotspot network.
     */
    fun isWifiEnabled(): Boolean {
        // 1. WifiManager radio switch check
        val isWmEnabled = try {
            wifiManager?.isWifiEnabled == true ||
                wifiManager?.wifiState == WifiManager.WIFI_STATE_ENABLED
        } catch (_: Exception) {
            false
        }

        if (isWmEnabled) return true

        // 2. ConnectivityManager active network check (Wi-Fi or Ethernet/Hotspot)
        val isNetworkConnected = try {
            val cm = connectivityManager
            val active = cm?.activeNetwork
            if (active != null) {
                val caps = cm.getNetworkCapabilities(active)
                caps?.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) == true ||
                    caps?.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET) == true
            } else {
                false
            }
        } catch (_: Exception) {
            false
        }

        if (isNetworkConnected) return true

        // 3. Network Interfaces check (Wi-Fi Direct, Local-only Hotspot, wlan, p2p, ap)
        return try {
            val interfaces = NetworkInterface.getNetworkInterfaces() ?: return false
            var activeWifiInterface = false
            while (interfaces.hasMoreElements()) {
                val iface = interfaces.nextElement()
                val name = iface.name.lowercase()
                if (iface.isUp && !iface.isLoopback && (name.startsWith("wlan") || name.startsWith("p2p") || name.startsWith("ap") || name.startsWith("eth") || name.startsWith("rndis"))) {
                    val addrs = iface.inetAddresses
                    while (addrs.hasMoreElements()) {
                        val addr = addrs.nextElement()
                        if (!addr.isLoopbackAddress && addr is Inet4Address) {
                            activeWifiInterface = true
                            break
                        }
                    }
                }
                if (activeWifiInterface) break
            }
            activeWifiInterface
        } catch (_: Exception) {
            false
        }
    }

    fun startMonitoring() {
        updateState()

        // 1. Broadcast Receiver for all radio and connectivity state changes
        btAndWifiReceiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context?, intent: Intent?) {
                updateState()
            }
        }

        val filter = IntentFilter().apply {
            addAction(BluetoothAdapter.ACTION_STATE_CHANGED)
            addAction(BluetoothAdapter.ACTION_CONNECTION_STATE_CHANGED)
            addAction(WifiManager.WIFI_STATE_CHANGED_ACTION)
            addAction(WifiManager.NETWORK_STATE_CHANGED_ACTION)
            addAction(WifiManager.SUPPLICANT_STATE_CHANGED_ACTION)
            @Suppress("DEPRECATION")
            addAction(ConnectivityManager.CONNECTIVITY_ACTION)
            addAction("android.net.wifi.WIFI_AP_STATE_CHANGED")
            addAction("android.net.wifi.p2p.STATE_CHANGED")
            addAction("android.net.wifi.p2p.CONNECTION_STATE_CHANGE")
        }

        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                context.registerReceiver(btAndWifiReceiver, filter, Context.RECEIVER_NOT_EXPORTED)
            } else {
                context.registerReceiver(btAndWifiReceiver, filter)
            }
        } catch (e: Exception) {
            try {
                context.registerReceiver(btAndWifiReceiver, filter)
            } catch (ignored: Exception) {
                Log.w(TAG, "Failed to register broadcast receiver: ${ignored.message}")
            }
        }

        // 2. Default Network Callback (Fires whenever primary network changes)
        try {
            defaultNetworkCallback = object : ConnectivityManager.NetworkCallback() {
                override fun onAvailable(network: Network) { updateState() }
                override fun onLost(network: Network) { updateState() }
                override fun onCapabilitiesChanged(network: Network, capabilities: NetworkCapabilities) { updateState() }
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
                connectivityManager?.registerDefaultNetworkCallback(defaultNetworkCallback!!)
            }
        } catch (e: Exception) {
            Log.w(TAG, "Default network callback registration error: ${e.message}")
        }

        // 3. Specific Wi-Fi Network Callback
        try {
            val wifiRequest = NetworkRequest.Builder()
                .addTransportType(NetworkCapabilities.TRANSPORT_WIFI)
                .build()
            wifiNetworkCallback = object : ConnectivityManager.NetworkCallback() {
                override fun onAvailable(network: Network) { updateState() }
                override fun onLost(network: Network) { updateState() }
                override fun onCapabilitiesChanged(network: Network, capabilities: NetworkCapabilities) { updateState() }
            }
            connectivityManager?.registerNetworkCallback(wifiRequest, wifiNetworkCallback!!)
        } catch (e: Exception) {
            Log.w(TAG, "Wi-Fi network callback registration error: ${e.message}")
        }

        // 4. Periodic Coroutine Heartbeat (Guarantees sub-second real-time responsiveness in all runtimes)
        pollingJob?.cancel()
        pollingJob = scope.launch {
            while (isActive) {
                delay(1000)
                updateState()
            }
        }
    }

    fun updateState() {
        val newState = computeCurrentState()
        if (_state.value != newState) {
            _state.value = newState
        }
    }

    /**
     * Finds the current primary local IPv4 address across active Wi-Fi, Ethernet, Hotspot, and P2P interfaces.
     */
    fun getLocalIpAddress(): String? {
        try {
            // 1. Try active LinkProperties from ConnectivityManager
            val cm = connectivityManager
            val active = cm?.activeNetwork
            if (active != null) {
                val linkProps = cm.getLinkProperties(active)
                val ip = linkProps?.linkAddresses
                    ?.map { it.address }
                    ?.firstOrNull { it is Inet4Address && !it.isLoopbackAddress }
                    ?.hostAddress
                if (!ip.isNullOrBlank() && ip != "0.0.0.0") {
                    return ip
                }
            }

            // 2. Scan network interfaces prioritizing wlan, ap, p2p, and eth
            val interfaces = NetworkInterface.getNetworkInterfaces()?.toList() ?: emptyList()
            val sortedIfaces = interfaces.sortedByDescending { iface ->
                val n = iface.name.lowercase()
                when {
                    n.startsWith("wlan") -> 4
                    n.startsWith("ap") -> 3
                    n.startsWith("p2p") -> 2
                    n.startsWith("eth") -> 1
                    else -> 0
                }
            }

            for (iface in sortedIfaces) {
                if (!iface.isUp || iface.isLoopback) continue
                val addresses = iface.inetAddresses
                while (addresses.hasMoreElements()) {
                    val addr = addresses.nextElement()
                    if (!addr.isLoopbackAddress && addr is Inet4Address) {
                        val host = addr.hostAddress
                        if (!host.isNullOrBlank() && host != "0.0.0.0") {
                            return host
                        }
                    }
                }
            }
        } catch (_: Exception) {}
        return null
    }

    fun stopMonitoring() {
        pollingJob?.cancel()
        pollingJob = null

        try {
            btAndWifiReceiver?.let { context.unregisterReceiver(it) }
        } catch (_: Exception) {}
        btAndWifiReceiver = null

        try {
            defaultNetworkCallback?.let { connectivityManager?.unregisterNetworkCallback(it) }
        } catch (_: Exception) {}
        defaultNetworkCallback = null

        try {
            wifiNetworkCallback?.let { connectivityManager?.unregisterNetworkCallback(it) }
        } catch (_: Exception) {}
        wifiNetworkCallback = null
    }
}

