package com.example.data.connectivity

import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.net.ConnectivityManager
import android.net.LinkProperties
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.net.wifi.WifiManager
import android.os.Build
import android.os.Looper
import android.util.Log
import androidx.annotation.VisibleForTesting
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.net.Inet4Address
import java.net.InetAddress
import java.net.NetworkInterface

data class ConnectivityState(
    val isBluetoothOn: Boolean = false,
    val isWifiOn: Boolean = false,
    val localIpAddress: String? = null,
)

class ConnectivityMonitor(
    private val context: Context,
) {
    companion object {
        private const val TAG = "ConnectivityMonitor"
    }

    private val bluetoothManager = context.getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager
    private val connectivityManager = context.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager
    private val wifiManager = context.applicationContext.getSystemService(Context.WIFI_SERVICE) as? WifiManager

    private val _state = MutableStateFlow(computeInitialState())
    val state: StateFlow<ConnectivityState> = _state.asStateFlow()

    private val monitoringLock = Any()
    private val stateLock = Any()

    @Volatile
    private var isMonitoring = false

    @Volatile
    private var cachedBluetoothOn: Boolean? = null

    @Volatile
    private var cachedWifiOn: Boolean? = null

    @Volatile
    private var cachedNetwork: Network? = null

    @Volatile
    private var cachedIpAddress: String? = null

    @Volatile
    private var currentWifiNetwork: Network? = null

    private var btAndWifiReceiver: BroadcastReceiver? = null
    private var defaultNetworkCallback: ConnectivityManager.NetworkCallback? = null
    private var wifiNetworkCallback: ConnectivityManager.NetworkCallback? = null

    private val updateScope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private var pendingAsyncUpdate: Job? = null

    @VisibleForTesting
    internal var testIpLookup: (() -> String?)? = null

    @VisibleForTesting
    internal var testBluetoothAdapterEnabled: (() -> Boolean)? = null

    @VisibleForTesting
    internal var interfaceScanCount = 0

    private fun computeInitialState(): ConnectivityState {
        val bluetoothOn = queryBluetoothEnabled()
        val wifiOn = isWifiEnabled()
        val ip = if (wifiOn) getLocalIpAddress() else null
        cachedNetwork = connectivityManager?.activeNetwork
        cachedIpAddress = ip
        cachedBluetoothOn = bluetoothOn
        return ConnectivityState(
            isBluetoothOn = bluetoothOn,
            isWifiOn = wifiOn,
            localIpAddress = ip,
        )
    }

    /**
     * Checks whether Bluetooth adapter is currently powered on and enabled.
     * Uses BluetoothManager.adapter (never BluetoothAdapter.getDefaultAdapter)
     * and safely handles SecurityException on Android 12+.
     */
    fun isBluetoothEnabled(): Boolean {
        val cached = cachedBluetoothOn
        if (cached != null && isMonitoring) {
            return cached
        }
        return queryBluetoothEnabled().also {
            if (isMonitoring) cachedBluetoothOn = it
        }
    }

    private fun queryBluetoothEnabled(): Boolean =
        try {
            testBluetoothAdapterEnabled?.invoke() ?: (bluetoothManager?.adapter?.isEnabled == true)
        } catch (_: SecurityException) {
            false
        } catch (_: Exception) {
            false
        }

    /**
     * Checks whether Wi-Fi radio is enabled or connected to a Wi-Fi/Ethernet/P2P/Hotspot network.
     * Evaluates cheapest reliable checks first:
     * 1. WifiManager state
     * 2. ConnectivityManager active network capabilities
     * 3. Fallback to active local network interfaces (Wi-Fi Direct, AP, etc.)
     */
    fun isWifiEnabled(): Boolean {
        val cached = cachedWifiOn
        if (cached != null && isMonitoring) {
            if (!cached) {
                return hasActiveHotspotOrP2pInterface()
            }
            return true
        }

        // 1. WifiManager radio switch check (cheapest)
        try {
            val wm = wifiManager
            if (wm != null) {
                val state = wm.wifiState
                if (state == WifiManager.WIFI_STATE_ENABLED) {
                    return true
                }
                if (state == WifiManager.WIFI_STATE_DISABLED || state == WifiManager.WIFI_STATE_DISABLING) {
                    // Wi-Fi radio is explicitly powered off.
                    // Check if Hotspot or Wi-Fi Direct (P2P) interface is active
                    return hasActiveHotspotOrP2pInterface()
                }
                if (wm.isWifiEnabled) {
                    return true
                }
            }
        } catch (_: SecurityException) {
        } catch (_: Exception) {
        }

        // 2. ConnectivityManager active network check (Wi-Fi or Ethernet)
        try {
            val cm = connectivityManager
            val active = cm?.activeNetwork
            if (active != null) {
                val caps = cm.getNetworkCapabilities(active)
                if (caps != null && (
                        caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) ||
                            caps.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET)
                    )
                ) {
                    return true
                }
            }
        } catch (_: SecurityException) {
        } catch (_: Exception) {
        }

        // 3. Fallback to local hotspot or P2P interfaces
        return hasActiveHotspotOrP2pInterface()
    }

    /**
     * Fast-path Wi-Fi check without enumerating network interfaces.
     */
    private fun isWifiEnabledFast(): Boolean {
        val cached = cachedWifiOn
        if (cached != null && isMonitoring) {
            return cached
        }

        try {
            val wm = wifiManager
            if (wm != null) {
                val state = wm.wifiState
                if (state == WifiManager.WIFI_STATE_ENABLED) {
                    return true
                }
                if (state == WifiManager.WIFI_STATE_DISABLED || state == WifiManager.WIFI_STATE_DISABLING) {
                    return false
                }
                if (wm.isWifiEnabled) {
                    return true
                }
            }
        } catch (_: Exception) {
        }

        try {
            val cm = connectivityManager
            val active = cm?.activeNetwork
            if (active != null) {
                val caps = cm.getNetworkCapabilities(active)
                if (caps != null && (
                        caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) ||
                            caps.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET)
                    )
                ) {
                    return true
                }
            }
        } catch (_: Exception) {
        }

        return false
    }

    /**
     * Checks whether any local P2P or AP interface is up and has an IPv4 address.
     * Only invoked when cheap radio and active network checks return false.
     */
    private fun hasActiveHotspotOrP2pInterface(): Boolean {
        try {
            val interfaces = NetworkInterface.getNetworkInterfaces() ?: return false
            while (interfaces.hasMoreElements()) {
                val iface = interfaces.nextElement()
                if (!iface.isUp || iface.isLoopback) continue
                val name = iface.name
                val isHotspotOrP2p = name.startsWith("softap", ignoreCase = true) ||
                    name.startsWith("p2p", ignoreCase = true) ||
                    (name.startsWith("ap", ignoreCase = true) && name.length > 2 && name[2].isDigit())
                if (isHotspotOrP2p) {
                    val addrs = iface.inetAddresses
                    while (addrs.hasMoreElements()) {
                        val addr = addrs.nextElement()
                        if (isValidIpv4(addr)) {
                            return true
                        }
                    }
                }
            }
        } catch (_: Exception) {
        }
        return false
    }

    /**
     * Finds the current primary local IPv4 address across active Wi-Fi, Ethernet, Hotspot, and P2P interfaces.
     * Uses ConnectivityManager LinkProperties as the fast path, falling back to a single-pass
     * prioritized interface scan when LinkProperties is unavailable or refers to cellular.
     */
    fun getLocalIpAddress(): String? {
        testIpLookup?.let { return it.invoke() }

        // 1. Fast path: check activeNetwork LinkProperties
        try {
            val cm = connectivityManager
            val active = cm?.activeNetwork
            if (active != null) {
                val caps = cm.getNetworkCapabilities(active)
                if (caps != null && (
                        caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) ||
                            caps.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET)
                    )
                ) {
                    val linkProps = cm.getLinkProperties(active)
                    val ip = extractIpv4FromLinkProperties(linkProps)
                    if (!ip.isNullOrBlank()) {
                        return ip
                    }
                }
            }
        } catch (_: Exception) {
        }

        // 2. Wi-Fi callback network (if activeNetwork is Cellular or null)
        currentWifiNetwork?.let { wifiNet ->
            try {
                val linkProps = connectivityManager?.getLinkProperties(wifiNet)
                val ip = extractIpv4FromLinkProperties(linkProps)
                if (!ip.isNullOrBlank()) {
                    return ip
                }
            } catch (_: Exception) {
            }
        }

        // 3. Fallback: single-pass prioritized scan across network interfaces (Wi-Fi Direct, AP, etc.)
        return scanInterfacesForLocalIp()
    }

    private fun extractIpv4FromLinkProperties(linkProperties: LinkProperties?): String? {
        if (linkProperties == null) return null
        for (linkAddress in linkProperties.linkAddresses) {
            val address = linkAddress.address
            if (isValidIpv4(address)) {
                val host = address.hostAddress
                if (!host.isNullOrBlank() && host != "0.0.0.0") {
                    return host
                }
            }
        }
        return null
    }

    /**
     * Single-pass prioritized interface scan:
     * Priority: wlan (5) > ap (4) > p2p (3) > eth (2) > rndis (1).
     * Early terminates immediately on discovering a valid wlan IPv4 address.
     * Avoids collection allocations, sorting, and lowercase string copies.
     */
    private fun scanInterfacesForLocalIp(): String? {
        interfaceScanCount++
        try {
            val interfaces = NetworkInterface.getNetworkInterfaces() ?: return null
            var bestIp: String? = null
            var bestPriority = 0

            while (interfaces.hasMoreElements()) {
                val iface = interfaces.nextElement()
                if (!iface.isUp || iface.isLoopback) continue
                val name = iface.name
                val priority = when {
                    name.startsWith("wlan", ignoreCase = true) -> 5
                    name.startsWith("ap", ignoreCase = true) -> 4
                    name.startsWith("p2p", ignoreCase = true) -> 3
                    name.startsWith("eth", ignoreCase = true) -> 2
                    name.startsWith("rndis", ignoreCase = true) -> 1
                    else -> 0
                }
                if (priority <= 0 || priority <= bestPriority) continue

                val addrs = iface.inetAddresses
                while (addrs.hasMoreElements()) {
                    val addr = addrs.nextElement()
                    if (isValidIpv4(addr)) {
                        val host = addr.hostAddress
                        if (!host.isNullOrBlank() && host != "0.0.0.0") {
                            if (priority == 5) {
                                // WLAN is the highest priority - early return immediately
                                return host
                            }
                            bestPriority = priority
                            bestIp = host
                            break
                        }
                    }
                }
            }
            return bestIp
        } catch (_: Exception) {
            return null
        }
    }

    private fun isValidIpv4(addr: InetAddress?): Boolean =
        addr is Inet4Address && !addr.isLoopbackAddress && !addr.isAnyLocalAddress

    /**
     * Updates the current state and emits to [state] if changed.
     * Thread-safe: can be called from any thread.
     */
    fun updateState() {
        val fastResolved = tryUpdateStateFast()
        if (!fastResolved) {
            if (Looper.myLooper() == Looper.getMainLooper()) {
                scheduleAsyncUpdate()
            } else {
                performFullStateUpdate()
            }
        }
    }

    private fun tryUpdateStateFast(): Boolean {
        val bluetoothOn = isBluetoothEnabled()

        if (testIpLookup != null) {
            val ip = testIpLookup!!.invoke()
            cachedIpAddress = ip
            val isWifi = isWifiEnabled() || ip != null
            synchronized(stateLock) {
                val newState = ConnectivityState(
                    isBluetoothOn = bluetoothOn,
                    isWifiOn = isWifi,
                    localIpAddress = ip,
                )
                if (_state.value != newState) {
                    _state.value = newState
                }
            }
            return true
        }

        val isWifiFast = isWifiEnabledFast()
        if (!isWifiFast) {
            cachedNetwork = null
            cachedIpAddress = null
            synchronized(stateLock) {
                val newState = ConnectivityState(
                    isBluetoothOn = bluetoothOn,
                    isWifiOn = false,
                    localIpAddress = null,
                )
                if (_state.value != newState) {
                    _state.value = newState
                }
            }
            return true
        }

        val activeNetwork = connectivityManager?.activeNetwork
        val cached = cachedIpAddress

        val resolvedIp = if (cached != null && activeNetwork != null && activeNetwork == cachedNetwork) {
            cached
        } else if (activeNetwork != null) {
            val linkProps = connectivityManager?.getLinkProperties(activeNetwork)
            val ip = extractIpv4FromLinkProperties(linkProps)
            if (ip != null) {
                cachedNetwork = activeNetwork
                cachedIpAddress = ip
                ip
            } else {
                return false
            }
        } else {
            return false
        }

        synchronized(stateLock) {
            val newState = ConnectivityState(
                isBluetoothOn = bluetoothOn,
                isWifiOn = isWifiFast,
                localIpAddress = resolvedIp,
            )
            if (_state.value != newState) {
                _state.value = newState
            }
        }
        return true
    }

    private fun scheduleAsyncUpdate() {
        synchronized(stateLock) {
            pendingAsyncUpdate?.cancel()
            pendingAsyncUpdate = updateScope.launch {
                performFullStateUpdate()
            }
        }
    }

    private fun performFullStateUpdate() {
        val bluetoothOn = isBluetoothEnabled()
        val wifiOn = isWifiEnabled()
        val activeNetwork = connectivityManager?.activeNetwork

        val ip = if (!wifiOn) {
            cachedNetwork = null
            cachedIpAddress = null
            null
        } else {
            val cached = cachedIpAddress
            if (cached != null && activeNetwork != null && activeNetwork == cachedNetwork) {
                cached
            } else {
                val freshIp = getLocalIpAddress()
                cachedNetwork = activeNetwork
                cachedIpAddress = freshIp
                freshIp
            }
        }

        synchronized(stateLock) {
            val newState = ConnectivityState(
                isBluetoothOn = bluetoothOn,
                isWifiOn = wifiOn,
                localIpAddress = ip,
            )
            if (_state.value != newState) {
                _state.value = newState
            }
        }
    }

    fun startMonitoring() {
        synchronized(monitoringLock) {
            if (isMonitoring) return
            isMonitoring = true
        }

        updateState()

        // 1. Broadcast Receiver for radio, P2P, and hotspot state changes
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context?, intent: Intent?) {
                if (!isMonitoring || intent == null) return
                when (intent.action) {
                    BluetoothAdapter.ACTION_STATE_CHANGED -> {
                        val state = intent.getIntExtra(BluetoothAdapter.EXTRA_STATE, BluetoothAdapter.ERROR)
                        val isEnabled = when (state) {
                            BluetoothAdapter.STATE_ON -> true
                            BluetoothAdapter.STATE_OFF, BluetoothAdapter.STATE_TURNING_OFF -> false
                            else -> queryBluetoothEnabled()
                        }
                        cachedBluetoothOn = isEnabled
                        synchronized(stateLock) {
                            _state.update { current ->
                                if (current.isBluetoothOn != isEnabled) current.copy(isBluetoothOn = isEnabled) else current
                            }
                        }
                    }
                    BluetoothAdapter.ACTION_CONNECTION_STATE_CHANGED -> {
                        // External accessory connected; power state unaffected
                    }
                    WifiManager.WIFI_STATE_CHANGED_ACTION -> {
                        val wifiState = intent.getIntExtra(WifiManager.EXTRA_WIFI_STATE, WifiManager.WIFI_STATE_UNKNOWN)
                        if (wifiState == WifiManager.WIFI_STATE_DISABLED) {
                            cachedWifiOn = false
                            cachedNetwork = null
                            cachedIpAddress = null
                            updateState()
                        } else if (wifiState == WifiManager.WIFI_STATE_ENABLED) {
                            cachedWifiOn = true
                            updateState()
                        }
                    }
                    "android.net.wifi.p2p.CONNECTION_STATE_CHANGE",
                    "android.net.wifi.p2p.STATE_CHANGED",
                    "android.net.wifi.WIFI_AP_STATE_CHANGED" -> {
                        // Wi-Fi Direct or Local Hotspot interface state changed
                        cachedIpAddress = null
                        updateState()
                    }
                    WifiManager.NETWORK_STATE_CHANGED_ACTION,
                    @Suppress("DEPRECATION")
                    ConnectivityManager.CONNECTIVITY_ACTION -> {
                        cachedIpAddress = null
                        updateState()
                    }
                    WifiManager.SUPPLICANT_STATE_CHANGED_ACTION -> {
                        // Handshake in progress; no action needed
                    }
                    else -> {
                        updateState()
                    }
                }
            }
        }
        btAndWifiReceiver = receiver

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
                context.registerReceiver(receiver, filter, Context.RECEIVER_EXPORTED)
            } else {
                context.registerReceiver(receiver, filter)
            }
        } catch (_: Exception) {
            try {
                context.registerReceiver(receiver, filter)
            } catch (ignored: Exception) {
                Log.w(TAG, "Failed to register broadcast receiver: ${ignored.message}")
            }
        }

        // 2. Default Network Callback (Fires whenever default primary network changes)
        try {
            val callback = object : ConnectivityManager.NetworkCallback() {
                override fun onAvailable(network: Network) {
                    if (!isMonitoring) return
                    if (network != cachedNetwork) {
                        cachedNetwork = network
                        cachedIpAddress = null
                    }
                    updateState()
                }

                override fun onLost(network: Network) {
                    if (!isMonitoring) return
                    if (network == cachedNetwork) {
                        cachedNetwork = null
                        cachedIpAddress = null
                    }
                    updateState()
                }

                override fun onLinkPropertiesChanged(network: Network, linkProperties: LinkProperties) {
                    if (!isMonitoring) return
                    val ip = extractIpv4FromLinkProperties(linkProperties)
                    if (ip != null) {
                        cachedNetwork = network
                        cachedIpAddress = ip
                        synchronized(stateLock) {
                            val current = _state.value
                            val newState = current.copy(isWifiOn = isWifiEnabledFast(), localIpAddress = ip)
                            if (current != newState) {
                                _state.value = newState
                            }
                        }
                    } else {
                        if (network == cachedNetwork) {
                            cachedIpAddress = null
                        }
                        updateState()
                    }
                }

                override fun onCapabilitiesChanged(network: Network, capabilities: NetworkCapabilities) {
                    if (!isMonitoring) return
                    val isWifiOrEth = capabilities.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) ||
                        capabilities.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET)
                    if (isWifiOrEth != _state.value.isWifiOn) {
                        updateState()
                    }
                }
            }
            defaultNetworkCallback = callback
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
                connectivityManager?.registerDefaultNetworkCallback(callback)
            }
        } catch (e: Exception) {
            Log.w(TAG, "Default network callback registration error: ${e.message}")
        }

        // 3. Specific Wi-Fi Network Callback (Tracks Wi-Fi even when default network is Cellular)
        try {
            val wifiRequest = NetworkRequest.Builder()
                .addTransportType(NetworkCapabilities.TRANSPORT_WIFI)
                .build()
            val callback = object : ConnectivityManager.NetworkCallback() {
                override fun onAvailable(network: Network) {
                    if (!isMonitoring) return
                    currentWifiNetwork = network
                    if (cachedNetwork == null || cachedNetwork != network) {
                        cachedIpAddress = null
                    }
                    updateState()
                }

                override fun onLost(network: Network) {
                    if (!isMonitoring) return
                    if (currentWifiNetwork == network) {
                        currentWifiNetwork = null
                    }
                    if (cachedNetwork == network) {
                        cachedNetwork = null
                        cachedIpAddress = null
                    }
                    updateState()
                }

                override fun onLinkPropertiesChanged(network: Network, linkProperties: LinkProperties) {
                    if (!isMonitoring) return
                    val ip = extractIpv4FromLinkProperties(linkProperties)
                    if (ip != null) {
                        val active = connectivityManager?.activeNetwork
                        if (active == null || active == network || cachedNetwork == null || cachedNetwork == network) {
                            cachedNetwork = network
                            cachedIpAddress = ip
                            synchronized(stateLock) {
                                val current = _state.value
                                val newState = current.copy(isWifiOn = true, localIpAddress = ip)
                                if (current != newState) {
                                    _state.value = newState
                                }
                            }
                        }
                    } else {
                        if (cachedNetwork == network) {
                            cachedIpAddress = null
                            updateState()
                        }
                    }
                }

                override fun onCapabilitiesChanged(network: Network, capabilities: NetworkCapabilities) {
                    // Static transport, no-op to avoid redundant work
                }
            }
            wifiNetworkCallback = callback
            connectivityManager?.registerNetworkCallback(wifiRequest, callback)
        } catch (e: Exception) {
            Log.w(TAG, "Wi-Fi network callback registration error: ${e.message}")
        }
    }

    fun stopMonitoring() {
        synchronized(monitoringLock) {
            if (!isMonitoring) return
            isMonitoring = false
        }

        synchronized(stateLock) {
            pendingAsyncUpdate?.cancel()
            pendingAsyncUpdate = null
        }

        try {
            btAndWifiReceiver?.let { context.unregisterReceiver(it) }
        } catch (_: Exception) {
        }
        btAndWifiReceiver = null

        try {
            defaultNetworkCallback?.let { connectivityManager?.unregisterNetworkCallback(it) }
        } catch (_: Exception) {
        }
        defaultNetworkCallback = null

        try {
            wifiNetworkCallback?.let { connectivityManager?.unregisterNetworkCallback(it) }
        } catch (_: Exception) {
        }
        wifiNetworkCallback = null

        currentWifiNetwork = null
        cachedNetwork = null
        cachedIpAddress = null
        cachedBluetoothOn = null
        cachedWifiOn = null
    }
}
