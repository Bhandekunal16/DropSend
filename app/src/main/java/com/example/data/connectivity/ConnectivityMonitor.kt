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
    private var cachedP2pOrHotspotInterfaceState: Boolean? = null

    @Volatile
    private var currentNetwork: Network? = null

    @Volatile
    private var currentWifiNetwork: Network? = null

    @Volatile
    private var cachedIpAddress: String? = null

    private val updateGeneration = AtomicLong(0)
    private val isFallbackRunning =
        java.util.concurrent.atomic
            .AtomicBoolean(false)
    private val pendingFallbackUpdate =
        java.util.concurrent.atomic
            .AtomicBoolean(false)

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

    fun isWifiEnabled(): Boolean = checkWifiEnabled(allowInterfaceScan = true)

    private fun isWifiEnabledFast(): Boolean = checkWifiEnabled(allowInterfaceScan = false)

    private fun checkWifiEnabled(allowInterfaceScan: Boolean): Boolean {
        val cached = cachedWifiOn
        if (cached != null && isMonitoring) {
            return if (allowInterfaceScan) (cached || cachedP2pOrHotspotActive || (cachedP2pOrHotspotInterfaceState == true)) else cached
        }
        if (queryWifiRadioOrNetworkActive()) {
            return true
        }
        return if (allowInterfaceScan) hasActiveHotspotOrP2pInterface() else false
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

    private fun hasActiveHotspotOrP2pInterface(): Boolean {
        val cached = cachedP2pOrHotspotInterfaceState
        if (cached != null && isMonitoring) {
            return cached
        }
        val active = scanForHotspotOrP2pInterface()
        if (isMonitoring) {
            cachedP2pOrHotspotInterfaceState = active
        }
        return active
    }

    private fun scanForHotspotOrP2pInterface(): Boolean {
        try {
            val interfaces = NetworkInterface.getNetworkInterfaces() ?: return false
            while (interfaces.hasMoreElements()) {
                val iface = interfaces.nextElement()
                if (!iface.isUp || iface.isLoopback) continue
                val name = iface.name
                val isHotspotOrP2p =
                    name.startsWith("softap", ignoreCase = true) ||
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

    fun getLocalIpAddress(): String? {
        testIpLookup?.let { return it.invoke() }

        val active = connectivityManager?.activeNetwork
        val current = currentNetwork ?: active
        val cached = cachedIpAddress
        if (cached != null && current != null && current == currentNetwork) {
            return cached
        }

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
                val priority =
                    when {
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

    private fun isValidIpv4(addr: InetAddress?): Boolean = addr is Inet4Address && !addr.isLoopbackAddress && !addr.isAnyLocalAddress

    private fun publishState(
        isBluetoothOn: Boolean,
        isWifiOn: Boolean,
        localIpAddress: String?,
    ) {
        val newState =
            ConnectivityState(
                isBluetoothOn = isBluetoothOn,
                isWifiOn = isWifiOn,
                localIpAddress = localIpAddress,
            )
        _state.update { current ->
            if (current == newState) current else newState
        }
    }

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
        if (!isWifiFast && !cachedP2pOrHotspotActive && (cachedP2pOrHotspotInterfaceState != true)) {
            currentNetwork = null
            cachedIpAddress = null
            publishState(bluetoothOn, false, null)
            return
        }

        val cm = connectivityManager
        val active = cm?.activeNetwork
        val current = currentNetwork ?: active
        if (current != null && cm != null) {
            val caps =
                try {
                    cm.getNetworkCapabilities(current)
                } catch (_: Exception) {
                    null
                }
            if (caps != null && (
                    caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) ||
                        caps.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET)
                )
            ) {
                val linkProps =
                    try {
                        cm.getLinkProperties(current)
                    } catch (_: Exception) {
                        null
                    }
                val ip = extractIpv4FromLinkProperties(linkProps)
                if (ip != null) {
                    currentNetwork = current
                    cachedIpAddress = ip
                    publishState(bluetoothOn, true, ip)
                    return
                }
            }
        }

        val wifiNet = currentWifiNetwork
        if (wifiNet != null && cm != null) {
            val linkProps =
                try {
                    cm.getLinkProperties(wifiNet)
                } catch (_: Exception) {
                    null
                }
            val ip = extractIpv4FromLinkProperties(linkProps)
            if (ip != null) {
                currentNetwork = wifiNet
                cachedIpAddress = ip
                publishState(bluetoothOn, true, ip)
                return
            }
        }

        if (Looper.myLooper() == Looper.getMainLooper()) {
            scheduleAsyncFallbackUpdate()
        } else {
            performFallbackScanUpdate()
        }
    }

    private fun scheduleAsyncFallbackUpdate() {
        val gen = updateGeneration.incrementAndGet()
        if (isFallbackRunning.get()) {
            pendingFallbackUpdate.set(true)
            return
        }
        updateScope.launch {
            runFallbackWorker(gen)
        }
    }

    private suspend fun runFallbackWorker(initialGen: Long) {
        if (!isFallbackRunning.compareAndSet(false, true)) {
            pendingFallbackUpdate.set(true)
            return
        }
        try {
            var currentGen = initialGen
            while (isMonitoring) {
                val ip = scanInterfacesForLocalIp()
                if (updateGeneration.get() == currentGen && isMonitoring) {
                    val bluetoothOn = isBluetoothEnabled()
                    val isWifi = isWifiEnabled() || ip != null
                    if (updateGeneration.get() == currentGen && isMonitoring) {
                        if (ip != null) {
                            cachedIpAddress = ip
                        }
                        publishState(bluetoothOn, isWifi, ip)
                    }
                }
                if (pendingFallbackUpdate.compareAndSet(true, false) && isMonitoring) {
                    currentGen = updateGeneration.incrementAndGet()
                } else {
                    break
                }
            }
        } finally {
            isFallbackRunning.set(false)
            if (pendingFallbackUpdate.get() && isMonitoring) {
                scheduleAsyncFallbackUpdate()
            }
        }
    }

    private fun performFallbackScanUpdate() {
        val gen = updateGeneration.incrementAndGet()
        val ip = scanInterfacesForLocalIp()
        if (updateGeneration.get() != gen || !isMonitoring) {
            return
        }
        val bluetoothOn = isBluetoothEnabled()
        val isWifi = isWifiEnabled() || ip != null
        if (updateGeneration.get() == gen && isMonitoring) {
            if (ip != null) {
                cachedIpAddress = ip
            }
            publishState(bluetoothOn, isWifi, ip)
        }
    }

    fun startMonitoring() {
        synchronized(monitoringLock) {
            if (isMonitoring) return
            isMonitoring = true
        }

        updateState()

        val receiver =
            object : BroadcastReceiver() {
                override fun onReceive(
                    context: Context?,
                    intent: Intent?,
                ) {
                    if (!isMonitoring || intent == null) return
                    when (intent.action) {
                        BluetoothAdapter.ACTION_STATE_CHANGED -> {
                            val extraState = intent.getIntExtra(BluetoothAdapter.EXTRA_STATE, BluetoothAdapter.ERROR)
                            val isEnabled =
                                when (extraState) {
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
                            val isApOn = (apState == 13)
                            cachedP2pOrHotspotActive = isApOn
                            cachedP2pOrHotspotInterfaceState = if (isApOn) true else null
                            cachedIpAddress = null
                            updateGeneration.incrementAndGet()
                            updateState()
                        }

                        "android.net.wifi.p2p.CONNECTION_STATE_CHANGE" -> {
                            cachedP2pOrHotspotInterfaceState = null
                            cachedIpAddress = null
                            updateGeneration.incrementAndGet()
                            updateState()
                        }

                        "android.net.wifi.p2p.STATE_CHANGED" -> {
                            val p2pState = intent.getIntExtra("wifi_p2p_state", 1)
                            val isP2pOn = (p2pState == 2)
                            cachedP2pOrHotspotActive = isP2pOn
                            cachedP2pOrHotspotInterfaceState = if (isP2pOn) null else false
                            cachedIpAddress = null
                            updateState()
                        }

                        WifiManager.NETWORK_STATE_CHANGED_ACTION,
                        @Suppress("DEPRECATION")
                        ConnectivityManager.CONNECTIVITY_ACTION,
                        -> {
                            cachedIpAddress = null
                            updateGeneration.incrementAndGet()
                            updateState()
                        }

                        @Suppress("DEPRECATION")
                        WifiManager.SUPPLICANT_STATE_CHANGED_ACTION,
                        -> {
                        }

                        else -> {
                            updateState()
                        }
                    }
                }
            }
        btAndWifiReceiver = receiver

        val filter =
            IntentFilter().apply {
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

        try {
            val defaultCallback =
                object : ConnectivityManager.NetworkCallback() {
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

                    override fun onLinkPropertiesChanged(
                        network: Network,
                        linkProperties: LinkProperties,
                    ) {
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

                    override fun onCapabilitiesChanged(
                        network: Network,
                        capabilities: NetworkCapabilities,
                    ) {
                        if (!isMonitoring) return
                        val isWifiOrEth =
                            capabilities.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) ||
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

        try {
            val wifiRequest =
                NetworkRequest
                    .Builder()
                    .addTransportType(NetworkCapabilities.TRANSPORT_WIFI)
                    .build()
            val wifiCallback =
                object : ConnectivityManager.NetworkCallback() {
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

                    override fun onLinkPropertiesChanged(
                        network: Network,
                        linkProperties: LinkProperties,
                    ) {
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

                    override fun onCapabilitiesChanged(
                        network: Network,
                        capabilities: NetworkCapabilities,
                    ) {
                    }
                }
            wifiNetworkCallback = wifiCallback
            connectivityManager?.registerNetworkCallback(wifiRequest, wifiCallback)
        } catch (e: Exception) {
            Log.w(TAG, "Wi-Fi network callback registration error: ${e.message}")
        }
    }

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
        cachedP2pOrHotspotInterfaceState = null
        isFallbackRunning.set(false)
        pendingFallbackUpdate.set(false)
    }
}
