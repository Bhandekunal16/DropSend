package com.example.data.discovery

import android.content.Context
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import android.net.wifi.WifiManager
import android.util.Log
import com.example.domain.model.DiscoveredDevice
import com.example.domain.model.TransportType
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import org.json.JSONObject
import java.net.ConnectException
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.Inet4Address
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.NetworkInterface
import java.net.NoRouteToHostException
import java.net.Socket
import java.net.SocketException
import java.net.SocketTimeoutException
import java.util.concurrent.ConcurrentHashMap

/**
 * High-performance, production-grade LAN discovery service for DropSend.
 *
 * Implements three cooperative discovery mechanisms:
 * 1. mDNS / NSD Service Registration & Discovery (Zero-config LAN discovery)
 * 2. UDP Broadcast Beacon + Active Probe (Fast discovery across subnets & router multicast barriers)
 * 3. Bounded TCP Subnet /24 Scanner + Hotspot Gateway Probe (Fast fallback for restricted networks)
 *
 * Fully optimized for low CPU usage, minimal object allocation, battery preservation,
 * bounded concurrency, and high-speed peer detection.
 */
class LanDiscoveryService(
    private val context: Context,
) {
    companion object {
        private const val TAG = "LanDiscoveryService"
        private const val DEBUG = false // Set to false in production for zero logging overhead

        private const val SERVICE_TYPE = "_dropsend._tcp."
        const val UDP_BROADCAST_PORT = 8889
        const val DEFAULT_TCP_PORT = 8888

        // Subnet /24 Bounded Concurrency Configuration
        // 8 workers balance discovery latency (~3-5s for /24) with low socket table contention and radio queue health
        private const val DEFAULT_SUBNET_CONCURRENCY = 8
        private const val TCP_PROBE_TIMEOUT_MS = 200 // Aggressive timeout for fast rejection of unreachable hosts
        private const val GATEWAY_PROBE_TIMEOUT_MS = 350 // Slightly higher timeout for hotspot host/gateway

        // UDP Broadcast Beaconing & Probing Intervals (Adaptive)
        private const val BEACON_INITIAL_BURST_INTERVAL_MS = 400L
        private const val BEACON_FAST_INTERVAL_MS = 1500L
        private const val BEACON_STEADY_INTERVAL_MS = 3000L
        private const val BEACON_FAST_WINDOW_MS = 10_000L

        private const val PROBE_INITIAL_BURST_INTERVAL_MS = 600L
        private const val PROBE_STEADY_INTERVAL_MS = 3500L
        private const val PROBE_FAST_WINDOW_MS = 8_000L

        // Caching and Rate-limiting Thresholds
        private const val IP_CACHE_TTL_MS = 4_000L
        private const val NSD_RESOLVE_CACHE_TTL_MS = 6_000L
        private const val HEARTBEAT_EMIT_INTERVAL_MS = 5_000L
    }

    private val nsdManager = context.getSystemService(Context.NSD_SERVICE) as? NsdManager
    private val wifiManager = context.applicationContext.getSystemService(Context.WIFI_SERVICE) as? WifiManager

    // Pre-allocated global broadcast address
    private val globalBroadcastAddress: InetAddress by lazy {
        InetAddress.getByName("255.255.255.255")
    }

    // Structured coroutine lifecycle: SupervisorJob prevents individual worker failures from killing scope
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    // Public StateFlow of discovered devices
    private val _discoveredDevices = MutableStateFlow<Map<String, DiscoveredDevice>>(emptyMap())
    val discoveredDevices: StateFlow<Map<String, DiscoveredDevice>> = _discoveredDevices.asStateFlow()

    // Thread-safety locks
    private val stateLock = Any()
    private val cacheLock = Any()

    // Internal High-Performance Device Cache
    private val deviceCache = LinkedHashMap<String, DiscoveredDevice>()
    private val ipToPlaceholderId = HashMap<String, String>() // hostIp -> "DROP-xxxx"
    private val knownIps = HashSet<String>() // hostIp -> true for O(1) probe skips
    private val lastPublishedTimestamps = HashMap<String, Long>()

    // In-flight NSD resolution tracker to prevent redundant/overlapping NsdManager resolveService calls
    private val resolvingServices = ConcurrentHashMap<String, Long>()

    // Network Interface & Broadcast Address Cache
    @Volatile private var cachedLocalIps: List<String> = emptyList()

    @Volatile private var cachedBroadcastAddrs: List<InetAddress> = emptyList()

    @Volatile private var lastInterfaceQueryTime: Long = 0L

    // Multicast Lock
    private var multicastLock: WifiManager.MulticastLock? = null

    // Lifecycle state & session generation tracking (guards against stale callbacks)
    private var discoveryGeneration: Long = 0L
    private var advertisingGeneration: Long = 0L
    private var isDiscovering: Boolean = false
    private var isAdvertising: Boolean = false
    private var currentLocalDeviceId: String = ""
    private var isReleased: Boolean = false

    // Parent Jobs
    private var discoveryJob: Job? = null
    private var advertisingJob: Job? = null

    // Sockets
    private var discoveryUdpSocket: DatagramSocket? = null
    private var advertisingUdpSocket: DatagramSocket? = null

    // NSD Listeners
    private var registrationListener: NsdManager.RegistrationListener? = null
    private var discoveryListener: NsdManager.DiscoveryListener? = null

    // =========================================================================
    // MulticastLock Management
    // =========================================================================

    private fun updateMulticastLockState() {
        val shouldHold = isDiscovering || isAdvertising
        try {
            if (shouldHold) {
                if (multicastLock == null) {
                    multicastLock =
                        wifiManager?.createMulticastLock("DropSendMulticastLock")?.apply {
                            setReferenceCounted(false)
                        }
                }
                multicastLock?.let {
                    if (!it.isHeld) {
                        it.acquire()
                        if (DEBUG) Log.d(TAG, "MulticastLock acquired")
                    }
                }
            } else {
                multicastLock?.let {
                    if (it.isHeld) {
                        it.release()
                        if (DEBUG) Log.d(TAG, "MulticastLock released")
                    }
                }
            }
        } catch (e: Exception) {
            Log.w(TAG, "Error updating MulticastLock: ${e.message}")
        }
    }

    // =========================================================================
    // Network Interface & Broadcast Address Resolution (Cached)
    // =========================================================================

    /**
     * Returns all active local IPv4 addresses (Wi-Fi, Hotspot, etc.).
     * Results are cached for [IP_CACHE_TTL_MS] to avoid repeated costly interface enumerations.
     */
    fun getLocalIpAddresses(forceRefresh: Boolean = false): List<String> {
        val now = System.currentTimeMillis()
        if (!forceRefresh && (now - lastInterfaceQueryTime < IP_CACHE_TTL_MS) && cachedLocalIps.isNotEmpty()) {
            return cachedLocalIps
        }
        refreshNetworkInterfaces()
        return cachedLocalIps
    }

    private fun getBroadcastAddressesCached(): List<InetAddress> {
        val now = System.currentTimeMillis()
        if ((now - lastInterfaceQueryTime < IP_CACHE_TTL_MS) && cachedBroadcastAddrs.isNotEmpty()) {
            return cachedBroadcastAddrs
        }
        refreshNetworkInterfaces()
        return cachedBroadcastAddrs
    }

    private fun refreshNetworkInterfaces() {
        val ips = ArrayList<String>(4)
        val broadcasts = LinkedHashSet<InetAddress>()
        try {
            val interfaces = NetworkInterface.getNetworkInterfaces()
            if (interfaces != null) {
                for (intf in interfaces) {
                    if (!intf.isUp || intf.isLoopback) continue
                    val name = intf.name.lowercase()
                    // Filter dummy or virtual non-routable interfaces
                    if (name.startsWith("dummy") || name.startsWith("lo")) continue

                    for (addr in intf.inetAddresses) {
                        if (addr is Inet4Address && !addr.isLoopbackAddress) {
                            val host = addr.hostAddress ?: continue
                            if (!host.startsWith("127.")) {
                                ips.add(host)
                            }
                        }
                    }

                    for (interfaceAddress in intf.interfaceAddresses) {
                        val bcast = interfaceAddress.broadcast
                        if (bcast != null) {
                            broadcasts.add(bcast)
                        }
                    }
                }
            }
        } catch (e: Exception) {
            if (DEBUG) Log.w(TAG, "Error enumerating network interfaces", e)
        }

        // Always ensure standard global broadcast is included as a fallback
        broadcasts.add(globalBroadcastAddress)

        cachedLocalIps = ips.distinct()
        cachedBroadcastAddrs = broadcasts.toList()
        lastInterfaceQueryTime = System.currentTimeMillis()
    }

    // =========================================================================
    // LAN Advertising (mDNS NSD + UDP Beacon + Probe Responder)
    // =========================================================================

    fun startAdvertising(
        localDeviceId: String,
        localDeviceName: String,
        tcpPort: Int,
    ) {
        val currentGen: Long
        synchronized(stateLock) {
            if (isReleased) return
            stopAdvertisingInternal()

            isAdvertising = true
            currentGen = ++advertisingGeneration
            currentLocalDeviceId = localDeviceId
            updateMulticastLockState()
        }

        refreshNetworkInterfaces()
        Log.d(TAG, "Starting LAN advertising (gen=$currentGen) for $localDeviceId on port $tcpPort")

        // 1. mDNS NSD Registration
        startNsdRegistration(localDeviceId, localDeviceName, tcpPort, currentGen)

        // 2. Precompute Advertising Beacon Payload once
        val beaconPayload =
            try {
                JSONObject()
                    .apply {
                        put("type", "DROPSEND_BEACON")
                        put("id", localDeviceId)
                        put("name", localDeviceName)
                        put("port", tcpPort)
                    }.toString()
                    .toByteArray(Charsets.UTF_8)
            } catch (e: Exception) {
                Log.e(TAG, "Failed to serialize beacon JSON", e)
                return
            }

        // 3. UDP Broadcast Beacon + Active Probe Responder
        synchronized(stateLock) {
            if (!isAdvertising || currentGen != advertisingGeneration) return

            advertisingJob =
                scope.launch {
                    var socket: DatagramSocket? = null
                    try {
                        socket =
                            DatagramSocket(null).apply {
                                reuseAddress = true
                                broadcast = true
                                bind(InetSocketAddress(UDP_BROADCAST_PORT))
                            }
                        synchronized(stateLock) {
                            advertisingUdpSocket = socket
                        }

                        // Child coroutine: Adaptive beacon transmission
                        launch {
                            runAdaptiveBeaconLoop(beaconPayload, socket, currentGen)
                        }

                        // Child coroutine: Probe responder (replies directly to discovery senders)
                        val listenBuffer = ByteArray(1024)
                        while (isActive) {
                            try {
                                val inPacket = DatagramPacket(listenBuffer, listenBuffer.size)
                                socket.receive(inPacket)
                                if (inPacket.length <= 0) continue

                                val text = String(inPacket.data, 0, inPacket.length, Charsets.UTF_8)
                                if (text.contains("DROPSEND_PROBE")) {
                                    val json = JSONObject(text)
                                    val senderId = json.optString("id")
                                    if (senderId != localDeviceId && inPacket.address != null) {
                                        // Sender asked who is nearby -> reply immediately to sender IP
                                        val replyPacket =
                                            DatagramPacket(
                                                beaconPayload,
                                                beaconPayload.size,
                                                inPacket.address,
                                                UDP_BROADCAST_PORT,
                                            )
                                        socket.send(replyPacket)
                                    }
                                }
                            } catch (e: SocketException) {
                                // Normal termination on socket close
                                break
                            } catch (_: Exception) {
                                // Ignore malformed packets
                            }
                        }
                    } catch (e: Exception) {
                        if (isActive && DEBUG) {
                            Log.w(TAG, "UDP advertising socket error: ${e.message}")
                        }
                    } finally {
                        try {
                            socket?.close()
                        } catch (_: Exception) {
                        }
                        synchronized(stateLock) {
                            if (advertisingUdpSocket === socket) {
                                advertisingUdpSocket = null
                            }
                        }
                    }
                }
        }
    }

    private suspend fun runAdaptiveBeaconLoop(
        beaconPayload: ByteArray,
        socket: DatagramSocket,
        generation: Long,
    ) {
        val targets = getBroadcastAddressesCached()
        // Fast initial bursts to announce presence immediately to listening senders
        sendUdpToTargets(socket, beaconPayload, targets)
        delay(BEACON_INITIAL_BURST_INTERVAL_MS)
        if (generation != advertisingGeneration) return
        sendUdpToTargets(socket, beaconPayload, targets)

        val startTime = System.currentTimeMillis()
        while (scope.isActive) {
            val elapsed = System.currentTimeMillis() - startTime
            val interval =
                if (elapsed < BEACON_FAST_WINDOW_MS) {
                    BEACON_FAST_INTERVAL_MS
                } else {
                    BEACON_STEADY_INTERVAL_MS
                }

            delay(interval)
            if (generation != advertisingGeneration) break

            val currentTargets = getBroadcastAddressesCached()
            sendUdpToTargets(socket, beaconPayload, currentTargets)
        }
    }

    private fun sendUdpToTargets(
        socket: DatagramSocket,
        payload: ByteArray,
        targets: List<InetAddress>,
    ) {
        for (target in targets) {
            try {
                val packet = DatagramPacket(payload, payload.size, target, UDP_BROADCAST_PORT)
                socket.send(packet)
            } catch (_: Exception) {
                // Ignore transient send failures
            }
        }
    }

    private fun startNsdRegistration(
        localDeviceId: String,
        localDeviceName: String,
        tcpPort: Int,
        generation: Long,
    ) {
        try {
            val serviceInfo =
                NsdServiceInfo().apply {
                    serviceName = "$localDeviceId#$localDeviceName"
                    serviceType = SERVICE_TYPE
                    port = tcpPort
                }

            val listener =
                object : NsdManager.RegistrationListener {
                    override fun onServiceRegistered(serviceInfo: NsdServiceInfo?) {
                        if (DEBUG) Log.d(TAG, "mDNS Service registered: ${serviceInfo?.serviceName}")
                    }

                    override fun onRegistrationFailed(
                        serviceInfo: NsdServiceInfo?,
                        errorCode: Int,
                    ) {
                        Log.w(TAG, "mDNS Registration failed: $errorCode")
                    }

                    override fun onServiceUnregistered(serviceInfo: NsdServiceInfo?) {
                        if (DEBUG) Log.d(TAG, "mDNS Service unregistered")
                    }

                    override fun onUnregistrationFailed(
                        serviceInfo: NsdServiceInfo?,
                        errorCode: Int,
                    ) {
                        Log.w(TAG, "mDNS Unregistration failed: $errorCode")
                    }
                }

            synchronized(stateLock) {
                if (!isAdvertising || generation != advertisingGeneration) return
                registrationListener = listener
            }

            nsdManager?.registerService(serviceInfo, NsdManager.PROTOCOL_DNS_SD, listener)
        } catch (e: Exception) {
            Log.w(TAG, "Error registering NSD service", e)
        }
    }

    fun stopAdvertising() {
        synchronized(stateLock) {
            stopAdvertisingInternal()
        }
    }

    private fun stopAdvertisingInternal() {
        isAdvertising = false
        advertisingGeneration++

        advertisingJob?.cancel()
        advertisingJob = null

        try {
            advertisingUdpSocket?.close()
        } catch (_: Exception) {
        }
        advertisingUdpSocket = null

        registrationListener?.let { listener ->
            try {
                nsdManager?.unregisterService(listener)
            } catch (_: Exception) {
            }
        }
        registrationListener = null

        updateMulticastLockState()
    }

    // =========================================================================
    // LAN Discovery (mDNS + UDP Beacon/Probe + Bounded /24 Subnet Sweep)
    // =========================================================================

    fun startDiscovery(localDeviceId: String) {
        val currentGen: Long
        synchronized(stateLock) {
            if (isReleased) return
            stopDiscoveryInternal()

            isDiscovering = true
            currentGen = ++discoveryGeneration
            currentLocalDeviceId = localDeviceId
            updateMulticastLockState()
        }

        refreshNetworkInterfaces()
        Log.d(TAG, "Starting LAN discovery (gen=$currentGen)")

        // 1. mDNS Service Discovery
        startNsdDiscovery(localDeviceId, currentGen)

        // 2. Precompute Probe Payload once
        val probePayload =
            try {
                JSONObject()
                    .apply {
                        put("type", "DROPSEND_PROBE")
                        put("id", localDeviceId)
                    }.toString()
                    .toByteArray(Charsets.UTF_8)
            } catch (e: Exception) {
                Log.e(TAG, "Failed to serialize probe JSON", e)
                return
            }

        // 3. Parent Discovery Job: UDP Listener, Probe Sender, and Bounded Subnet Scanner
        synchronized(stateLock) {
            if (!isDiscovering || currentGen != discoveryGeneration) return

            discoveryJob =
                scope.launch {
                    // 3a. UDP Listener & Probe Sender
                    launch {
                        runUdpDiscovery(probePayload, localDeviceId, currentGen)
                    }

                    // 3b. Bounded Subnet /24 & Hotspot Gateway Scanner
                    launch {
                        runBoundedSubnetScanner(localDeviceId, currentGen)
                    }
                }
        }
    }

    private fun startNsdDiscovery(
        localDeviceId: String,
        generation: Long,
    ) {
        try {
            val listener =
                object : NsdManager.DiscoveryListener {
                    override fun onDiscoveryStarted(regType: String) {
                        if (DEBUG) Log.d(TAG, "mDNS Service discovery started")
                    }

                    override fun onServiceFound(service: NsdServiceInfo) {
                        if (!isDiscovering || generation != discoveryGeneration) return
                        val serviceType = service.serviceType
                        if (serviceType == SERVICE_TYPE || serviceType == "_dropsend._tcp") {
                            resolveNsdService(service, localDeviceId, generation)
                        }
                    }

                    override fun onServiceLost(service: NsdServiceInfo) {
                        resolvingServices.remove(service.serviceName)
                        if (DEBUG) Log.d(TAG, "mDNS service lost: ${service.serviceName}")
                    }

                    override fun onDiscoveryStopped(serviceType: String) {
                        if (DEBUG) Log.d(TAG, "mDNS Discovery stopped: $serviceType")
                    }

                    override fun onStartDiscoveryFailed(
                        serviceType: String,
                        errorCode: Int,
                    ) {
                        Log.w(TAG, "mDNS Discovery start failed: $errorCode")
                    }

                    override fun onStopDiscoveryFailed(
                        serviceType: String,
                        errorCode: Int,
                    ) {
                        Log.w(TAG, "mDNS Discovery stop failed: $errorCode")
                    }
                }

            synchronized(stateLock) {
                if (!isDiscovering || generation != discoveryGeneration) return
                discoveryListener = listener
            }

            nsdManager?.discoverServices(SERVICE_TYPE, NsdManager.PROTOCOL_DNS_SD, listener)
        } catch (e: Exception) {
            Log.w(TAG, "Error starting NSD discovery", e)
        }
    }

    private fun resolveNsdService(
        service: NsdServiceInfo,
        localDeviceId: String,
        generation: Long,
    ) {
        val sName = service.serviceName ?: return
        val now = System.currentTimeMillis()

        // Quick parse to reject self before initiating resolution
        val (parsedId, _) = parseNsdServiceName(sName)
        if (parsedId == localDeviceId) return

        // In-flight resolution deduplication: skip if already resolving within cache TTL
        val lastResolve = resolvingServices[sName]
        if (lastResolve != null && (now - lastResolve < NSD_RESOLVE_CACHE_TTL_MS)) {
            return
        }
        resolvingServices[sName] = now

        try {
            nsdManager?.resolveService(
                service,
                object : NsdManager.ResolveListener {
                    override fun onResolveFailed(
                        serviceInfo: NsdServiceInfo?,
                        errorCode: Int,
                    ) {
                        resolvingServices.remove(sName)
                        if (DEBUG) Log.w(TAG, "mDNS Resolve failed for $sName: $errorCode")
                    }

                    override fun onServiceResolved(serviceInfo: NsdServiceInfo) {
                        resolvingServices.remove(sName)
                        if (!isDiscovering || generation != discoveryGeneration) return
                        handleResolvedNsdService(serviceInfo, localDeviceId, generation)
                    }
                },
            )
        } catch (e: Exception) {
            resolvingServices.remove(sName)
            if (DEBUG) Log.w(TAG, "Error invoking NSD resolveService: ${e.message}")
        }
    }

    private fun handleResolvedNsdService(
        serviceInfo: NsdServiceInfo,
        localDeviceId: String,
        generation: Long,
    ) {
        val rawName = serviceInfo.serviceName ?: return
        val (id, name) = parseNsdServiceName(rawName)
        if (id == localDeviceId) return

        val host = serviceInfo.host?.hostAddress ?: return
        val port = serviceInfo.port
        if (port <= 0) return

        recordDiscoveredDevice(
            id = id,
            name = name,
            host = host,
            port = port,
            transportType = TransportType.LOCAL_WIFI,
            generation = generation,
        )
    }

    /**
     * Efficient single-pass service name parser avoiding regex split allocations.
     * Format: "<deviceId>#<deviceName>"
     */
    private fun parseNsdServiceName(rawName: String): Pair<String, String> {
        val hashIdx = rawName.indexOf('#')
        return if (hashIdx >= 0) {
            Pair(rawName.substring(0, hashIdx), rawName.substring(hashIdx + 1).ifBlank { "Nearby Android" })
        } else {
            Pair(rawName, "Nearby Android")
        }
    }

    private suspend fun runUdpDiscovery(
        probePayload: ByteArray,
        localDeviceId: String,
        generation: Long,
    ) {
        var socket: DatagramSocket? = null
        try {
            socket =
                DatagramSocket(null).apply {
                    reuseAddress = true
                    broadcast = true
                    bind(InetSocketAddress(UDP_BROADCAST_PORT))
                }
            synchronized(stateLock) {
                discoveryUdpSocket = socket
            }

            // Probe sender coroutine: initial burst then periodic retry
            scope.launch {
                sendUdpToTargets(socket, probePayload, getBroadcastAddressesCached())
                delay(PROBE_INITIAL_BURST_INTERVAL_MS)
                if (generation != discoveryGeneration) return@launch
                sendUdpToTargets(socket, probePayload, getBroadcastAddressesCached())

                val startTime = System.currentTimeMillis()
                while (isActive && isDiscovering && generation == discoveryGeneration) {
                    val elapsed = System.currentTimeMillis() - startTime
                    val interval = if (elapsed < PROBE_FAST_WINDOW_MS) 1500L else PROBE_STEADY_INTERVAL_MS
                    delay(interval)
                    if (generation != discoveryGeneration) break
                    sendUdpToTargets(socket, probePayload, getBroadcastAddressesCached())
                }
            }

            // Listener loop: process beacons from advertisers
            val buffer = ByteArray(2048)
            val packet = DatagramPacket(buffer, buffer.size)

            while (scope.isActive && isDiscovering && generation == discoveryGeneration) {
                try {
                    socket.receive(packet)
                    if (packet.length <= 0) continue

                    val text = String(packet.data, 0, packet.length, Charsets.UTF_8)
                    if (!text.contains("DROPSEND_BEACON")) continue

                    val json = JSONObject(text)
                    val id = json.optString("id")
                    if (id.isEmpty() || id == localDeviceId) continue

                    val host = packet.address?.hostAddress ?: continue
                    if (host.isBlank()) continue

                    val name = json.optString("name", "Nearby Device")
                    val port = json.optInt("port", DEFAULT_TCP_PORT)

                    recordDiscoveredDevice(
                        id = id,
                        name = name,
                        host = host,
                        port = port,
                        transportType = TransportType.LOCAL_WIFI,
                        generation = generation,
                    )
                } catch (e: SocketException) {
                    // Normal exit when socket is closed on stopDiscovery
                    break
                } catch (_: Exception) {
                    // Ignore transient malformed packets
                }
            }
        } catch (e: Exception) {
            if (scope.isActive && DEBUG) {
                Log.w(TAG, "UDP discovery socket closed: ${e.message}")
            }
        } finally {
            try {
                socket?.close()
            } catch (_: Exception) {
            }
            synchronized(stateLock) {
                if (discoveryUdpSocket === socket) {
                    discoveryUdpSocket = null
                }
            }
        }
    }

    // =========================================================================
    // Bounded Concurrent Subnet /24 & Hotspot Gateway Scanner
    // =========================================================================

    private suspend fun runBoundedSubnetScanner(
        localDeviceId: String,
        generation: Long,
    ) {
        val localIps = getLocalIpAddresses(forceRefresh = true)
        if (localIps.isEmpty() || !isDiscovering || generation != discoveryGeneration) return

        // 1. Identify Priority IPs (DHCP Gateway / Hotspot Host)
        val gatewayIp: String? =
            try {
                val dhcp = wifiManager?.dhcpInfo
                val gatewayInt = dhcp?.gateway ?: 0
                if (gatewayInt != 0) {
                    val b1 = gatewayInt and 0xff
                    val b2 = (gatewayInt shr 8) and 0xff
                    val b3 = (gatewayInt shr 16) and 0xff
                    val b4 = (gatewayInt shr 24) and 0xff
                    "$b1.$b2.$b3.$b4"
                } else {
                    null
                }
            } catch (_: Exception) {
                null
            }

        val priorityIps =
            listOfNotNull(
                gatewayIp,
                "192.168.43.1",
                "192.168.49.1",
                "192.168.50.1",
            ).distinct()

        // 2. Extract unique /24 Subnet Prefixes from active local interfaces
        val subnetPrefixes =
            localIps
                .map { it.substringBeforeLast(".") + "." }
                .distinct()

        // 3. Set up Bounded Concurrency Work Queue via Channel
        val ipWorkChannel = Channel<String>(capacity = 64)

        // Launch controlled worker pool (8 concurrent workers)
        val workers =
            List(DEFAULT_SUBNET_CONCURRENCY) {
                scope.launch(Dispatchers.IO) {
                    for (targetIp in ipWorkChannel) {
                        if (!isActive || !isDiscovering || generation != discoveryGeneration) break
                        probeHost(targetIp, DEFAULT_TCP_PORT, gatewayIp, localDeviceId, generation)
                    }
                }
            }

        // Producer: Enqueue target IPs in priority order
        try {
            // A. Priority IPs probed immediately
            for (priorityIp in priorityIps) {
                if (!scope.isActive || !isDiscovering || generation != discoveryGeneration) break
                if (!localIps.contains(priorityIp) && !isIpAlreadyKnown(priorityIp)) {
                    ipWorkChannel.send(priorityIp)
                }
            }

            // B. Full /24 subnet addresses
            for (prefix in subnetPrefixes) {
                for (hostNum in 1..254) {
                    if (!scope.isActive || !isDiscovering || generation != discoveryGeneration) break
                    val targetIp = "$prefix$hostNum"
                    if (localIps.contains(targetIp) || isIpAlreadyKnown(targetIp)) continue
                    ipWorkChannel.send(targetIp)
                }
            }
        } catch (_: Exception) {
            // Channel closed or coroutine cancelled
        } finally {
            ipWorkChannel.close()
        }

        // Wait for workers to drain remaining tasks
        workers.forEach { it.join() }
    }

    private fun probeHost(
        targetIp: String,
        port: Int,
        gatewayIp: String?,
        localDeviceId: String,
        generation: Long,
    ) {
        // Skip if already discovered via UDP or NSD while queued
        if (isIpAlreadyKnown(targetIp)) return

        val isGatewayOrHotspot =
            targetIp == gatewayIp ||
                targetIp.startsWith("192.168.43.") ||
                targetIp.startsWith("192.168.49.")

        val timeoutMs = if (isGatewayOrHotspot) GATEWAY_PROBE_TIMEOUT_MS else TCP_PROBE_TIMEOUT_MS

        var socket: Socket? = null
        try {
            socket = Socket()
            socket.tcpNoDelay = true
            socket.connect(InetSocketAddress(targetIp, port), timeoutMs)

            // TCP Port open: peer verified!
            val probedId = "DROP-" + targetIp.replace(".", "").takeLast(4)
            if (probedId == localDeviceId) return

            val deviceName =
                if (isGatewayOrHotspot) {
                    "Hotspot Host ($targetIp)"
                } else {
                    "Nearby Receiver ($targetIp)"
                }

            val transport =
                if (isGatewayOrHotspot) {
                    TransportType.WIFI_DIRECT
                } else {
                    TransportType.LOCAL_WIFI
                }

            recordDiscoveredDevice(
                id = probedId,
                name = deviceName,
                host = targetIp,
                port = port,
                transportType = transport,
                generation = generation,
            )
            if (DEBUG) Log.d(TAG, "Discovered peer via TCP probe: $targetIp:$port")
        } catch (_: SocketTimeoutException) {
            // Normal unreachable host - fast fail without stack trace
        } catch (_: ConnectException) {
            // Port closed / connection refused - normal host rejection
        } catch (_: NoRouteToHostException) {
            // Host unreachable - normal
        } catch (_: Exception) {
            // Other transient socket errors - ignore
        } finally {
            try {
                socket?.close()
            } catch (_: Exception) {
            }
        }
    }

    private fun isIpAlreadyKnown(ip: String): Boolean {
        synchronized(cacheLock) {
            return knownIps.contains(ip)
        }
    }

    // =========================================================================
    // High-Performance Thread-Safe Device Cache & StateFlow Emission
    // =========================================================================

    private fun recordDiscoveredDevice(
        id: String,
        name: String,
        host: String,
        port: Int,
        transportType: TransportType,
        generation: Long,
    ) {
        if (id == currentLocalDeviceId) return
        val now = System.currentTimeMillis()
        var shouldPublish = false

        synchronized(cacheLock) {
            if (generation != discoveryGeneration) return

            // 1. O(1) Placeholder Replacement:
            // If this host IP previously had a placeholder "DROP-xxxx", supersede it with the real id
            val placeholderId = ipToPlaceholderId[host]
            if (placeholderId != null && placeholderId != id) {
                deviceCache.remove(placeholderId)
                ipToPlaceholderId.remove(host)
                shouldPublish = true
            }

            val existing = deviceCache[id]
            if (existing == null) {
                val newDevice =
                    DiscoveredDevice(
                        id = id,
                        name = name,
                        transportType = transportType,
                        ipAddress = host,
                        port = port,
                        isReadyToReceive = true,
                        lastSeenTimestamp = now,
                    )
                deviceCache[id] = newDevice
                knownIps.add(host)
                if (id.startsWith("DROP-")) {
                    ipToPlaceholderId[host] = id
                }
                lastPublishedTimestamps[id] = now
                shouldPublish = true
            } else {
                // Deduplicate updates: check if externally visible properties changed
                val nameChanged =
                    name.isNotBlank() &&
                        name != existing.name &&
                        !name.startsWith("Nearby") &&
                        existing.name.startsWith("Nearby")
                val ipChanged = host != existing.ipAddress
                val portChanged = port > 0 && port != existing.port
                val transportChanged =
                    transportType != existing.transportType &&
                        existing.transportType != TransportType.LOCAL_WIFI

                if (nameChanged || ipChanged || portChanged || transportChanged) {
                    val updated =
                        existing.copy(
                            name = if (nameChanged) name else existing.name,
                            ipAddress = if (ipChanged) host else existing.ipAddress,
                            port = if (portChanged) port else existing.port,
                            transportType = if (transportChanged) transportType else existing.transportType,
                            lastSeenTimestamp = now,
                            isReadyToReceive = true,
                        )
                    deviceCache[id] = updated
                    knownIps.add(host)
                    lastPublishedTimestamps[id] = now
                    shouldPublish = true
                } else {
                    // Only timestamp updated: keep cache reference fresh for expiration logic
                    val lastPublished = lastPublishedTimestamps[id] ?: 0L
                    if (now - lastPublished >= HEARTBEAT_EMIT_INTERVAL_MS) {
                        deviceCache[id] = existing.copy(lastSeenTimestamp = now)
                        lastPublishedTimestamps[id] = now
                        shouldPublish = true
                    } else {
                        // In-place refresh without triggering StateFlow recomposition storm
                        deviceCache[id] = existing.copy(lastSeenTimestamp = now)
                    }
                }
            }
        }

        if (shouldPublish) {
            publishSnapshot()
        }
    }

    private fun publishSnapshot() {
        val snapshot: Map<String, DiscoveredDevice> =
            synchronized(cacheLock) {
                HashMap(deviceCache)
            }
        _discoveredDevices.value = snapshot
    }

    fun stopDiscovery() {
        synchronized(stateLock) {
            stopDiscoveryInternal()
        }
    }

    private fun stopDiscoveryInternal() {
        isDiscovering = false
        discoveryGeneration++

        discoveryJob?.cancel()
        discoveryJob = null

        try {
            discoveryUdpSocket?.close()
        } catch (_: Exception) {
        }
        discoveryUdpSocket = null

        discoveryListener?.let { listener ->
            try {
                nsdManager?.stopServiceDiscovery(listener)
            } catch (_: Exception) {
            }
        }
        discoveryListener = null

        resolvingServices.clear()
        updateMulticastLockState()
    }

    fun clearDevices() {
        synchronized(cacheLock) {
            deviceCache.clear()
            ipToPlaceholderId.clear()
            knownIps.clear()
            lastPublishedTimestamps.clear()
        }
        _discoveredDevices.value = emptyMap()
    }

    /**
     * Permanently release all resources, cancel all active coroutines, and close all sockets.
     */
    fun release() {
        synchronized(stateLock) {
            if (isReleased) return
            isReleased = true
            stopDiscoveryInternal()
            stopAdvertisingInternal()
        }
        clearDevices()
        scope.cancel()
    }
}
