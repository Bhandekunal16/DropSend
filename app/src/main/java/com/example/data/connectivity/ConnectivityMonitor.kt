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
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancelChildren
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.net.Inet4Address
import java.net.InetAddress
import java.net.NetworkInterface
import java.util.concurrent.atomic.AtomicLong

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

    @Volatile
    private var isMonitoring = false

    @Volatile
    private var cachedBluetoothOn: Boolean? = null

    @Volatile
    private var cachedWifiOn: Boolean? = null

    @Volatile
    private var cachedP2pOrHotspotActive = false

    @Volatile
    private var currentNetwork: Network? = null

    @Volatile
    private var currentWifiNetwork: Network? = null

    @Volatile
    private var cachedIpAddress: String? = null

    private val updateGeneration = AtomicLong(0)

    private var btAndWifiReceiver: BroadcastReceiver? = null
    private var defaultNetworkCallback: ConnectivityManager.NetworkCallback? = null
    private var wifiNetworkCallback: ConnectivityManager.NetworkCallback? = null

    private val updateJob = SupervisorJob()
    private val updateScope = CoroutineScope(Dispatchers.IO + updateJob)

    @VisibleForTesting
    internal var testIpLookup: (() -> String?)? = null

    @VisibleForTesting
    internal var testBluetoothAdapterEnabled: (() -> Boolean)? = null

    @VisibleForTesting
    internal var interfaceScanCount = 0

    /**
     * Computes the initial connectivity state on object construction.
     * Uses fast-path lookups only to ensure instant initialization without blocking the main thread.
     */
    private fun computeInitialState(): ConnectivityState {
        val bluetoothOn = queryBluetoothEnabled()
        val wifiOn = isWifiEnabledFast()
        val ip = if (wifiOn) getFastLocalIpAddress() else null
        val active = connectivityManager?.activeNetwork
        currentNetwork = active
        cachedIpAddress = ip
        cachedBluetoothOn = bluetoothOn
        cachedWifiOn = wifiOn
        return ConnectivityState(
            isBluetoothOn = bluetoothOn,
            isWifiOn = wifiOn,
            localIpAddress = ip,
        )
    }

    /**
     * Checks whether Bluetooth adapter is currently powered on and enabled.
     * Uses BluetoothManager.adapter (never deprecated BluetoothAdapter.getDefaultAdapter)
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
     * Evaluates cached states first (O(1)), then WifiManager radio state, then active network capabilities,
     * falling back to checking for active local interfaces (P2P/Hotspot) only when necessary.
     */
    fun isWifiEnabled(): Boolean {
        val cached = cachedWifiOn
        if (cached != null && isMonitoring) {
            return cached || cachedP2pOrHotspotActive
        }
        if (isWifiEnabledFast()) {
            return true
        }
        return hasActiveHotspotOrP2pInterface()
    }

    /**
     * Fast-path Wi-Fi check without enumerating network interfaces.
     * Evaluates cached state, WifiManager radio switch, and ConnectivityManager transport capabilities.
     */
    private fun isWifiEnabledFast(): Boolean {
        val cached = cachedWifiOn
        if (cached != null && isMonitoring) {
            return cached
        }
        return queryWifiRadioOrNetworkActive()
    }

    private fun queryWifiRadioOrNetworkActive(): Boolean {
        try {
            val wm = wifiManager
            if (wm != null) {
                val state = wm.wifiState
                if (state == WifiManager.WIFI_STATE_ENABLED) return true
                if (state == WifiManager.WIFI_STATE_DISABLED || state == WifiManager.WIFI_STATE_DISABLING) return false
                if (wm.isWifiEnabled) return true
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
     * Checks whether any local P2P or AP interface is up and has a valid IPv4 address.
     * Only executed as a fallback when cheap radio and active network checks return false.
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
     * Fast-path local IPv4 lookup via LinkProperties only (zero interface enumeration).
     */
    private fun getFastLocalIpAddress(): String? {
        testIpLookup?.let { return it.invoke() }

        val cm = connectivityManager ?: return null
        val active = cm.activeNetwork ?: return null
        try {
            val caps = cm.getNetworkCapabilities(active)
            if (caps != null && (
                    caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) ||
                        caps.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET)
                )
            ) {
                val linkProps = cm.getLinkProperties(active)
                return extractIpv4FromLinkProperties(linkProps)
            }
        } catch (_: Exception) {
        }
        return null
    }

    /**
     * Finds the current primary local IPv4 address across active Wi-Fi, Ethernet, Hotspot, and P2P interfaces.
     * Uses ConnectivityManager LinkProperties as the fast path, falling back to a single-pass
     * prioritized interface scan when LinkProperties is unavailable or refers to cellular.
     */
    fun getLocalIpAddress(): String? {
        testIpLookup?.let { return it.invoke() }

        // 1. Check cached IP if network identity is still current
        val active = connectivityManager?.activeNetwork
        val current = currentNetwork ?: active
        val cached = cachedIpAddress
        if (cached != null && current != null && current == currentNetwork) {
            return cached
        }

        // 2. Fast path: check activeNetwork LinkProperties
        val cm = connectivityManager
        if (current != null && cm != null) {
            try {
                val caps = cm.getNetworkCapabilities(current)
                if (caps != null && (
                        caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) ||
                            caps.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET)
                    )
                ) {
                    val linkProps = cm.getLinkProperties(current)
                    val ip = extractIpv4FromLinkProperties(linkProps)
                    if (ip != null) {
                        currentNetwork = current
                        cachedIpAddress = ip
                        return ip
                    }
                }
            } catch (_: Exception) {
            }
        }

        // 3. Wi-Fi callback network (if activeNetwork is Cellular or null)
        val wifiNet = currentWifiNetwork
        if (wifiNet != null && cm != null) {
            try {
                val linkProps = cm.getLinkProperties(wifiNet)
                val ip = extractIpv4FromLinkProperties(linkProps)
                if (ip != null) {
                    currentNetwork = wifiNet
                    cachedIpAddress = ip
                    return ip
                }
            } catch (_: Exception) {
            }
        }

        // 4. Fallback: single-pass prioritized scan across network interfaces (Wi-Fi Direct, AP, etc.)
        return scanInterfacesForLocalIp().also {
            if (it != null) {
                cachedIpAddress = it
            }
        }
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
                                return host // WLAN is highest priority - early return immediately
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
     * Publishes a new connectivity state to [state] atomically if different from current value.
     */
    private fun publishState(isBluetoothOn: Boolean, isWifiOn: Boolean, localIpAddress: String?) {
        val newState = ConnectivityState(
            isBluetoothOn = isBluetoothOn,
            isWifiOn = isWifiOn,
            localIpAddress = localIpAddress,
        )
        _state.update { current ->
            if (current == newState) current else newState
        }
    }

    /**
     * Updates the current state and emits to [state] if changed.
     * Performs fast evaluation first. If expensive interface scans are needed:
     * - Dispatches asynchronously if invoked on the main thread (avoids UI jank).
     * - Executes synchronously if invoked on a background/worker thread.
     */
    fun updateState() {
        val bluetoothOn = isBluetoothEnabled()

        if (testIpLookup != null) {
            val ip = testIpLookup!!.invoke()
            cachedIpAddress = ip
            val isWifi = isWifiEnabledFast() || isWifiEnabled() || ip != null
            publishState(bluetoothOn, isWifi, ip)
            return
        }

        val isWifiFast = isWifiEnabledFast()
        if (!isWifiFast && !cachedP2pOrHotspotActive) {
            currentNetwork = null
            cachedIpAddress = null
            publishState(bluetoothOn, false, null)
            return
        }

        // Fast path: attempt to resolve IP from current or active network LinkProperties
        val cm = connectivityManager
        val active = cm?.activeNetwork
        val current = currentNetwork ?: active
        if (current != null && cm != null) {
            val caps = try { cm.getNetworkCapabilities(current) } catch (_: Exception) { null }
            if (caps != null && (
                    caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) ||
                        caps.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET)
                )
            ) {
                val linkProps = try { cm.getLinkProperties(current) } catch (_: Exception) { null }
                val ip = extractIpv4FromLinkProperties(linkProps)
                if (ip != null) {
                    currentNetwork = current
                    cachedIpAddress = ip
                    publishState(bluetoothOn, true, ip)
                    return
                }
            }
        }

        // Secondary fast check: currentWifiNetwork (if activeNetwork was cellular or null)
        val wifiNet = currentWifiNetwork
        if (wifiNet != null && cm != null) {
            val linkProps = try { cm.getLinkProperties(wifiNet) } catch (_: Exception) { null }
            val ip = extractIpv4FromLinkProperties(linkProps)
            if (ip != null) {
                currentNetwork = wifiNet
                cachedIpAddress = ip
                publishState(bluetoothOn, true, ip)
                return
            }
        }

        // Fallback: interface enumeration required (e.g. Wi-Fi Direct or Local Hotspot)
        if (Looper.myLooper() == Looper.getMainLooper()) {
            scheduleAsyncFallbackUpdate()
        } else {
            performFallbackScanUpdate()
        }
    }

    private fun scheduleAsyncFallbackUpdate() {
        val gen = updateGeneration.incrementAndGet()
        updateScope.launch {
            val ip = scanInterfacesForLocalIp()
            if (updateGeneration.get() != gen) {
                return@launch // Discard stale scan result from older generation
            }
            val bluetoothOn = isBluetoothEnabled()
            val isWifi = isWifiEnabled() || ip != null
            if (ip != null) {
                cachedIpAddress = ip
            }
            publishState(bluetoothOn, isWifi, ip)
        }
    }

    private fun performFallbackScanUpdate() {
        val gen = updateGeneration.incrementAndGet()
        val ip = scanInterfacesForLocalIp()
        if (updateGeneration.get() != gen) {
            return // Discard stale scan result from older generation
        }
        val bluetoothOn = isBluetoothEnabled()
        val isWifi = isWifiEnabled() || ip != null
        if (ip != null) {
            cachedIpAddress = ip
        }
        publishState(bluetoothOn, isWifi, ip)
    }

    /**
     * Starts active connectivity monitoring. Idempotent.
     */
    fun startMonitoring() {
        synchronized(monitoringLock) {
            if (isMonitoring) return
            isMonitoring = true
        }

        updateState()

        // 1. Broadcast Receiver for radio state and local AP/P2P transitions
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context?, intent: Intent?) {
                if (!isMonitoring || intent == null) return
                when (intent.action) {
                    BluetoothAdapter.ACTION_STATE_CHANGED -> {
                        val extraState = intent.getIntExtra(BluetoothAdapter.EXTRA_STATE, BluetoothAdapter.ERROR)
                        val isEnabled = when (extraState) {
                            BluetoothAdapter.STATE_ON -> true
                            BluetoothAdapter.STATE_OFF, BluetoothAdapter.STATE_TURNING_OFF -> false
                            else -> queryBluetoothEnabled()
                        }
                        cachedBluetoothOn = isEnabled
                        _state.update { current ->
                            if (current.isBluetoothOn != isEnabled) current.copy(isBluetoothOn = isEnabled) else current
                        }
                    }
                    BluetoothAdapter.ACTION_CONNECTION_STATE_CHANGED -> {
                        // External accessory connected; power state unaffected
                    }
                    WifiManager.WIFI_STATE_CHANGED_ACTION -> {
                        val wifiState = intent.getIntExtra(WifiManager.EXTRA_WIFI_STATE, WifiManager.WIFI_STATE_UNKNOWN)
                        if (wifiState == WifiManager.WIFI_STATE_DISABLED) {
                            cachedWifiOn = false
                            currentNetwork = null
                            cachedIpAddress = null
                            if (!cachedP2pOrHotspotActive) {
                                publishState(isBluetoothEnabled(), false, null)
                            } else {
                                updateState()
                            }
                        } else if (wifiState == WifiManager.WIFI_STATE_ENABLED) {
                            cachedWifiOn = true
                            updateState()
                        }
                    }
                    "android.net.wifi.WIFI_AP_STATE_CHANGED" -> {
                        val apState = intent.getIntExtra("wifi_state", 14)
                        cachedP2pOrHotspotActive = (apState == 13) // WIFI_AP_STATE_ENABLED = 13
                        cachedIpAddress = null
                        updateGeneration.incrementAndGet()
                        updateState()
                    }
                    "android.net.wifi.p2p.CONNECTION_STATE_CHANGE" -> {
                        cachedIpAddress = null
                        updateGeneration.incrementAndGet()
                        updateState()
                    }
                    "android.net.wifi.p2p.STATE_CHANGED" -> {
                        val p2pState = intent.getIntExtra("wifi_p2p_state", 1)
                        cachedP2pOrHotspotActive = (p2pState == 2) // WIFI_P2P_STATE_ENABLED = 2
                        cachedIpAddress = null
                        updateState()
                    }
                    WifiManager.NETWORK_STATE_CHANGED_ACTION,
                    @Suppress("DEPRECATION")
                    ConnectivityManager.CONNECTIVITY_ACTION -> {
                        cachedIpAddress = null
                        updateGeneration.incrementAndGet()
                        updateState()
                    }
                    @Suppress("DEPRECATION")
                    WifiManager.SUPPLICANT_STATE_CHANGED_ACTION -> {
                        // Handshake in progress; ignore
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
            @Suppress("DEPRECATION")
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

        // 2. Default Network Callback (Tracks active internet network transitions)
        try {
            val defaultCallback = object : ConnectivityManager.NetworkCallback() {
                override fun onAvailable(network: Network) {
                    if (!isMonitoring) return
                    if (network != currentNetwork) {
                        currentNetwork = network
                        cachedIpAddress = null
                        updateGeneration.incrementAndGet()
                    }
                    updateState()
                }

                override fun onLost(network: Network) {
                    if (!isMonitoring) return
                    if (network == currentNetwork) {
                        currentNetwork = null
                        cachedIpAddress = null
                        updateGeneration.incrementAndGet()
                        updateState()
                    }
                }

                override fun onLinkPropertiesChanged(network: Network, linkProperties: LinkProperties) {
                    if (!isMonitoring) return
                    val ip = extractIpv4FromLinkProperties(linkProperties)
                    if (ip != null) {
                        val prevIp = cachedIpAddress
                        currentNetwork = network
                        cachedIpAddress = ip
                        if (ip != prevIp || !_state.value.isWifiOn) {
                            publishState(isBluetoothEnabled(), isWifiEnabledFast(), ip)
                        }
                    } else if (network == currentNetwork) {
                        cachedIpAddress = null
                        updateGeneration.incrementAndGet()
                        updateState()
                    }
                }

                override fun onCapabilitiesChanged(network: Network, capabilities: NetworkCapabilities) {
                    if (!isMonitoring) return
                    val isWifiOrEth = capabilities.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) ||
                        capabilities.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET)
                    if (isWifiOrEth != _state.value.isWifiOn) {
                        cachedWifiOn = isWifiOrEth
                        updateState()
                    }
                }
            }
            defaultNetworkCallback = defaultCallback
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
                connectivityManager?.registerDefaultNetworkCallback(defaultCallback)
            }
        } catch (e: Exception) {
            Log.w(TAG, "Default network callback registration error: ${e.message}")
        }

        // 3. Wi-Fi Specific Network Callback (Maintains Wi-Fi link when default network is Cellular)
        try {
            val wifiRequest = NetworkRequest.Builder()
                .addTransportType(NetworkCapabilities.TRANSPORT_WIFI)
                .build()
            val wifiCallback = object : ConnectivityManager.NetworkCallback() {
                override fun onAvailable(network: Network) {
                    if (!isMonitoring) return
                    currentWifiNetwork = network
                    val active = connectivityManager?.activeNetwork
                    if (active == null || active != network) {
                        cachedIpAddress = null
                        updateGeneration.incrementAndGet()
                        updateState()
                    }
                }

                override fun onLost(network: Network) {
                    if (!isMonitoring) return
                    if (currentWifiNetwork == network) {
                        currentWifiNetwork = null
                    }
                    if (currentNetwork == network) {
                        currentNetwork = null
                        cachedIpAddress = null
                        updateGeneration.incrementAndGet()
                        updateState()
                    }
                }

                override fun onLinkPropertiesChanged(network: Network, linkProperties: LinkProperties) {
                    if (!isMonitoring) return
                    val ip = extractIpv4FromLinkProperties(linkProperties)
                    if (ip != null) {
                        val active = connectivityManager?.activeNetwork
                        if (active == null || currentNetwork == null || currentNetwork == network) {
                            val prevIp = cachedIpAddress
                            currentNetwork = network
                            cachedIpAddress = ip
                            if (ip != prevIp || !_state.value.isWifiOn) {
                                publishState(isBluetoothEnabled(), true, ip)
                            }
                        }
                    } else if (currentNetwork == network) {
                        cachedIpAddress = null
                        updateGeneration.incrementAndGet()
                        updateState()
                    }
                }

                override fun onCapabilitiesChanged(network: Network, capabilities: NetworkCapabilities) {
                    // Static Wi-Fi transport; no-op to prevent redundant processing
                }
            }
            wifiNetworkCallback = wifiCallback
            connectivityManager?.registerNetworkCallback(wifiRequest, wifiCallback)
        } catch (e: Exception) {
            Log.w(TAG, "Wi-Fi network callback registration error: ${e.message}")
        }
    }

    /**
     * Stops active monitoring, releases all callbacks, receivers, and cancels pending coroutines. Idempotent.
     */
    fun stopMonitoring() {
        synchronized(monitoringLock) {
            if (!isMonitoring) return
            isMonitoring = false
        }

        updateJob.cancelChildren()

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

        currentNetwork = null
        currentWifiNetwork = null
        cachedIpAddress = null
        cachedBluetoothOn = null
        cachedWifiOn = null
        cachedP2pOrHotspotActive = false
    }
}
