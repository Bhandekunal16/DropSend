package com.example.data.discovery

import android.content.Context
import android.util.Log
import com.example.domain.model.DiscoveredDevice
import com.example.domain.model.TransportType
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

class DeviceDiscoveryManager(
    private val context: Context,
) {
    companion object {
        private const val TAG = "DeviceDiscoveryManager"
        private const val DEBUG = false // Lightweight debug logging toggle
        const val PEER_EXPIRY_MS = 15_000L // Remove peers not heard from for 15s
        private const val CLEANUP_INTERVAL_MS = 4_000L
    }

    val bleDiscovery = BleDiscoveryService(context)
    val lanDiscovery = LanDiscoveryService(context)
    val wifiP2pManager = WifiP2pDirectManager(context)

    // Lifecycle-safe CoroutineScope using SupervisorJob to prevent cancellation cascading
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    private val stateLock = Any()
    private val cacheLock = Any()

    private var discoveryGeneration = 0L

    val currentGeneration: Long
        get() = synchronized(stateLock) { discoveryGeneration }

    @Volatile
    private var isDiscovering = false

    @Volatile
    private var isAdvertising = false

    @Volatile
    private var isReleased = false

    private var currentAdvertisedId: String? = null
    private var currentAdvertisedName: String? = null
    private var currentAdvertisedPort: Int = -1

    private val _nearbyDevices = MutableStateFlow<List<DiscoveredDevice>>(emptyList())
    val nearbyDevices: StateFlow<List<DiscoveredDevice>> = _nearbyDevices.asStateFlow()

    private var combineJob: Job? = null
    private var cleanupJob: Job? = null

    // Cache to track the last emitted list and precomputed sort keys
    private var lastEmittedList: List<DiscoveredDevice> = emptyList()

    init {
        startCombining()
        startPeriodicCleanup()
    }

    private fun startPeriodicCleanup() {
        synchronized(stateLock) {
            if (isReleased) return
            cleanupJob?.cancel()
            cleanupJob =
                scope.launch {
                    while (isActive) {
                        delay(CLEANUP_INTERVAL_MS)
                        val now = System.currentTimeMillis()
                        val currentList = _nearbyDevices.value
                        if (currentList.isEmpty()) continue

                        // Fast check: is any peer expired? Avoid collection allocations if nothing is expired.
                        var hasExpired = false
                        for (i in currentList.indices) {
                            if (now - currentList[i].lastSeenTimestamp >= PEER_EXPIRY_MS) {
                                hasExpired = true
                                break
                            }
                        }

                        if (hasExpired) {
                            val fresh = ArrayList<DiscoveredDevice>(currentList.size)
                            for (i in currentList.indices) {
                                val dev = currentList[i]
                                if (now - dev.lastSeenTimestamp < PEER_EXPIRY_MS) {
                                    fresh.add(dev)
                                } else if (DEBUG) {
                                    Log.d(TAG, "[PEER_EXPIRED] deviceId=${dev.id} transport=${dev.transportType}")
                                }
                            }

                            synchronized(cacheLock) {
                                lastEmittedList = fresh
                                _nearbyDevices.value = fresh
                            }
                        }
                    }
                }
        }
    }

    private fun startCombining() {
        synchronized(stateLock) {
            if (isReleased) return
            combineJob?.cancel()
            combineJob =
                scope.launch {
                    combine(
                        lanDiscovery.discoveredDevices,
                        bleDiscovery.discoveredDevices,
                        wifiP2pManager.discoveredPeers,
                    ) { lanMap, bleMap, p2pList ->
                        val now = System.currentTimeMillis()

                        // Estimate capacity to minimize reallocation: LAN + P2P + BLE
                        val capacity = (lanMap.size + p2pList.size + bleMap.size).coerceAtLeast(16)
                        val merged = LinkedHashMap<String, DiscoveredDevice>(capacity)

                        // Auxiliary index: ipAddress -> placeholder device ID (e.g. "DROP-xxxx")
                        // Allows O(1) placeholder removal when a real device ID with the same IP is discovered
                        val ipToPlaceholderId = HashMap<String, String>()

                        // Helper to merge a device into the working collection
                        fun mergeDevice(dev: DiscoveredDevice) {
                            if (now - dev.lastSeenTimestamp >= PEER_EXPIRY_MS) return

                            val ip = dev.ipAddress
                            val isProbedPlaceholder = dev.id.startsWith("DROP-")

                            // 1. If this is a real device ID with an IP, supersede any earlier probed placeholder
                            if (ip != null) {
                                if (!isProbedPlaceholder) {
                                    val placeholderId = ipToPlaceholderId.remove(ip)
                                    if (placeholderId != null) {
                                        merged.remove(placeholderId)
                                    }
                                } else {
                                    ipToPlaceholderId[ip] = dev.id
                                }
                            }

                            val existing = merged[dev.id]
                            if (existing == null) {
                                merged[dev.id] = dev
                            } else {
                                // Transport priority: LOCAL_WIFI > WIFI_DIRECT > BLUETOOTH
                                val preferredTransport =
                                    when {
                                        dev.transportType == TransportType.LOCAL_WIFI || existing.transportType == TransportType.LOCAL_WIFI -> TransportType.LOCAL_WIFI

                                        dev.transportType == TransportType.WIFI_DIRECT ||
                                            existing.transportType == TransportType.WIFI_DIRECT -> TransportType.WIFI_DIRECT

                                        else -> existing.transportType
                                    }

                                val mergedName = if (dev.name.isNotBlank() && !dev.name.startsWith("Nearby")) dev.name else existing.name
                                val mergedIp = dev.ipAddress ?: existing.ipAddress
                                val mergedPort = if (dev.port > 0) dev.port else existing.port
                                val mergedBtAddress = dev.bluetoothAddress ?: existing.bluetoothAddress
                                val mergedLastSeen = maxOf(existing.lastSeenTimestamp, dev.lastSeenTimestamp)
                                val mergedSessionId = dev.sessionId ?: existing.sessionId
                                val mergedGen = maxOf(existing.discoveryGeneration, dev.discoveryGeneration)
                                val mergedIsReady = existing.isReadyToReceive || dev.isReadyToReceive
                                val mergedRssi = if (dev.rssi != 0) dev.rssi else existing.rssi

                                // Reuse existing instance if nothing changed
                                if (existing.name == mergedName &&
                                    existing.ipAddress == mergedIp &&
                                    existing.port == mergedPort &&
                                    existing.bluetoothAddress == mergedBtAddress &&
                                    existing.transportType == preferredTransport &&
                                    existing.sessionId == mergedSessionId &&
                                    existing.discoveryGeneration == mergedGen &&
                                    existing.isReadyToReceive == mergedIsReady &&
                                    existing.rssi == mergedRssi &&
                                    (mergedLastSeen - existing.lastSeenTimestamp < 2000L)
                                ) {
                                    // No meaningful change; retain existing
                                } else {
                                    val updated =
                                        existing.copy(
                                            name = mergedName,
                                            ipAddress = mergedIp,
                                            port = mergedPort,
                                            bluetoothAddress = mergedBtAddress,
                                            transportType = preferredTransport,
                                            lastSeenTimestamp = mergedLastSeen,
                                            sessionId = mergedSessionId,
                                            discoveryGeneration = mergedGen,
                                            isReadyToReceive = mergedIsReady,
                                            rssi = mergedRssi,
                                        )
                                    merged[dev.id] = updated
                                    if (DEBUG) {
                                        Log.d(TAG, "[PEER_UPDATED] deviceId=${dev.id} transport=${updated.transportType}")
                                    }
                                }
                            }
                        }

                        // 1. LAN Wi-Fi devices (Highest priority for direct IP)
                        if (lanMap.isNotEmpty()) {
                            for (dev in lanMap.values) {
                                mergeDevice(dev)
                            }
                        }

                        // 2. Wi-Fi Direct devices
                        if (p2pList.isNotEmpty()) {
                            for (i in p2pList.indices) {
                                mergeDevice(p2pList[i])
                            }
                        }

                        // 3. BLE devices (Bluetooth fallback or discovery trigger)
                        if (bleMap.isNotEmpty()) {
                            for (dev in bleMap.values) {
                                mergeDevice(dev)
                            }
                        }

                        if (merged.isEmpty()) {
                            emptyList<DiscoveredDevice>()
                        } else {
                            // Sort by: LOCAL_WIFI (0) > WIFI_DIRECT (1) > BLUETOOTH (2), then newest timestamp
                            val sortedList = ArrayList<DiscoveredDevice>(merged.values)
                            sortedList.sortWith(
                                Comparator { a, b ->
                                    val transportRankA =
                                        when (a.transportType) {
                                            TransportType.LOCAL_WIFI -> 0
                                            TransportType.WIFI_DIRECT -> 1
                                            TransportType.BLUETOOTH -> 2
                                        }
                                    val transportRankB =
                                        when (b.transportType) {
                                            TransportType.LOCAL_WIFI -> 0
                                            TransportType.WIFI_DIRECT -> 1
                                            TransportType.BLUETOOTH -> 2
                                        }
                                    val rankDiff = transportRankA.compareTo(transportRankB)
                                    if (rankDiff != 0) {
                                        rankDiff
                                    } else {
                                        b.lastSeenTimestamp.compareTo(a.lastSeenTimestamp)
                                    }
                                },
                            )
                            sortedList
                        }
                    }.collect { candidateList ->
                        // Change detection: emit to _nearbyDevices only if the list has meaningfully changed
                        if (hasMeaningfulChanges(lastEmittedList, candidateList)) {
                            synchronized(cacheLock) {
                                lastEmittedList = candidateList
                                _nearbyDevices.value = candidateList
                            }
                        }
                    }
                }
        }
    }

    /**
     * Compare two lists for semantic changes (size, ID, name, endpoints, transport, session, generation).
     * Ignores minor timestamp jitters to prevent UI recomposition storms.
     */
    private fun hasMeaningfulChanges(
        oldList: List<DiscoveredDevice>,
        newList: List<DiscoveredDevice>,
    ): Boolean {
        if (oldList === newList) return false
        if (oldList.size != newList.size) return true

        for (i in oldList.indices) {
            val a = oldList[i]
            val b = newList[i]
            if (a.id != b.id ||
                a.name != b.name ||
                a.ipAddress != b.ipAddress ||
                a.port != b.port ||
                a.bluetoothAddress != b.bluetoothAddress ||
                a.transportType != b.transportType ||
                a.sessionId != b.sessionId ||
                a.discoveryGeneration != b.discoveryGeneration ||
                a.isReadyToReceive != b.isReadyToReceive
            ) {
                return true
            }
        }
        return false
    }

    /**
     * Start discovery as a sender looking for nearby receivers.
     * Idempotent: repeated calls do not duplicate receivers or scanners.
     */
    fun startDiscovery(localDeviceId: String): Long {
        val gen: Long
        synchronized(stateLock) {
            if (isReleased) return discoveryGeneration
            gen = ++discoveryGeneration
            isDiscovering = true
        }

        clear()
        if (DEBUG) {
            Log.d(TAG, "[DISCOVERY_START] generation=$gen transport=ALL localDeviceId=$localDeviceId")
        }

        // Parallel / non-blocking startup across transports
        lanDiscovery.startDiscovery(localDeviceId)
        bleDiscovery.startScanning()
        wifiP2pManager.startDiscovery(gen)

        return gen
    }

    /**
     * Stop all discovery sessions.
     * Idempotent: safe to call repeatedly.
     */
    fun stopDiscovery() {
        val gen: Long
        synchronized(stateLock) {
            if (isReleased) return
            gen = ++discoveryGeneration
            isDiscovering = false
        }

        if (DEBUG) {
            Log.d(TAG, "[DISCOVERY_STOP] generation=$gen")
        }

        lanDiscovery.stopDiscovery()
        bleDiscovery.stopScanning()
        wifiP2pManager.stopDiscovery()
        clear()
    }

    /**
     * Start advertising as a receiver waiting for senders.
     * Idempotent: identical parameters will not restart running advertisements.
     */
    fun startAdvertising(
        localDeviceId: String,
        localDeviceName: String,
        tcpPort: Int,
    ) {
        synchronized(stateLock) {
            if (isReleased) return
            if (isAdvertising &&
                currentAdvertisedId == localDeviceId &&
                currentAdvertisedName == localDeviceName &&
                currentAdvertisedPort == tcpPort
            ) {
                return // Already advertising with same configuration
            }
            stopAdvertisingInternal()

            isAdvertising = true
            currentAdvertisedId = localDeviceId
            currentAdvertisedName = localDeviceName
            currentAdvertisedPort = tcpPort
        }

        lanDiscovery.startAdvertising(localDeviceId, localDeviceName, tcpPort)
        bleDiscovery.startAdvertising(localDeviceId, localDeviceName)
    }

    /**
     * Stop advertising.
     * Idempotent: safe to call repeatedly.
     */
    fun stopAdvertising() {
        synchronized(stateLock) {
            stopAdvertisingInternal()
        }
    }

    private fun stopAdvertisingInternal() {
        if (!isAdvertising && currentAdvertisedId == null) return
        isAdvertising = false
        currentAdvertisedId = null
        currentAdvertisedName = null
        currentAdvertisedPort = -1

        lanDiscovery.stopAdvertising()
        bleDiscovery.stopAdvertising()
    }

    /**
     * Clear all cached discovered peers across sub-services.
     * Idempotent: does not publish if the list is already empty.
     */
    fun clear() {
        lanDiscovery.clearDevices()
        bleDiscovery.clearDevices()
        wifiP2pManager.clearPeers()

        synchronized(cacheLock) {
            if (lastEmittedList.isNotEmpty() || _nearbyDevices.value.isNotEmpty()) {
                lastEmittedList = emptyList()
                _nearbyDevices.value = emptyList()
            }
        }
    }

    /**
     * Permanently release all discovery resources, cancel active coroutines,
     * and unregister Wi-Fi Direct and Bluetooth listeners.
     */
    fun release() {
        synchronized(stateLock) {
            if (isReleased) return
            isReleased = true
            isDiscovering = false
            stopAdvertisingInternal()
        }

        lanDiscovery.stopDiscovery()
        bleDiscovery.stopScanning()
        wifiP2pManager.stopDiscovery()
        wifiP2pManager.unregister()

        combineJob?.cancel()
        cleanupJob?.cancel()
        scope.cancel()

        clear()
    }
}
