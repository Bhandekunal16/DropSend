package com.example.data.discovery

import android.content.Context
import android.util.Log
import com.example.domain.model.DiscoveredDevice
import com.example.domain.model.TransportType
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch

class DeviceDiscoveryManager(private val context: Context) {

    companion object {
        private const val TAG = "DeviceDiscoveryManager"
        const val PEER_EXPIRY_MS = 15_000L // Remove peers not heard from for 15s
    }

    val bleDiscovery = BleDiscoveryService(context)
    val lanDiscovery = LanDiscoveryService(context)
    val wifiP2pManager = WifiP2pDirectManager(context)

    private val scope = CoroutineScope(Dispatchers.IO + Job())

    private val stateLock = Any()
    private var discoveryGeneration = 0L

    val currentGeneration: Long
        get() = synchronized(stateLock) { discoveryGeneration }

    private val _nearbyDevices = MutableStateFlow<List<DiscoveredDevice>>(emptyList())
    val nearbyDevices: StateFlow<List<DiscoveredDevice>> = _nearbyDevices.asStateFlow()

    private var combineJob: Job? = null
    private var cleanupJob: Job? = null

    init {
        startCombining()
        startPeriodicCleanup()
    }

    private fun startPeriodicCleanup() {
        cleanupJob?.cancel()
        cleanupJob = scope.launch {
            while (true) {
                kotlinx.coroutines.delay(4000)
                val now = System.currentTimeMillis()
                val current = _nearbyDevices.value
                val (fresh, expired) = current.partition { (now - it.lastSeenTimestamp) < PEER_EXPIRY_MS }
                if (expired.isNotEmpty()) {
                    expired.forEach { dev ->
                        Log.d(TAG, "[PEER_EXPIRED] deviceId=${dev.id} transport=${dev.transportType}")
                    }
                    _nearbyDevices.value = fresh
                }
            }
        }
    }

    private fun startCombining() {
        combineJob = scope.launch {
            combine(
                lanDiscovery.discoveredDevices,
                bleDiscovery.discoveredDevices,
                wifiP2pManager.discoveredPeers
            ) { lanMap, bleMap, p2pList ->
                val now = System.currentTimeMillis()
                val merged = mutableMapOf<String, DiscoveredDevice>()

                // Helper to update or insert peer with deduplication
                fun processDevice(dev: DiscoveredDevice) {
                    if (now - dev.lastSeenTimestamp >= PEER_EXPIRY_MS) return

                    // Check if an existing probed placeholder with the same IP should be superseded by a real device_id
                    if (dev.ipAddress != null && !dev.id.startsWith("DROP-")) {
                        val probedKey = merged.keys.find { key ->
                            key.startsWith("DROP-") && merged[key]?.ipAddress == dev.ipAddress
                        }
                        if (probedKey != null) {
                            merged.remove(probedKey)
                        }
                    }

                    val existing = merged[dev.id]
                    if (existing == null) {
                        merged[dev.id] = dev
                    } else {
                        // Update existing peer with freshest session, endpoint, and transport capabilities
                        val preferFasterTransport = when {
                            dev.transportType == TransportType.LOCAL_WIFI -> dev.transportType
                            existing.transportType == TransportType.LOCAL_WIFI -> existing.transportType
                            dev.transportType == TransportType.WIFI_DIRECT -> dev.transportType
                            else -> existing.transportType
                        }
                        val updated = existing.copy(
                            name = if (dev.name.isNotBlank() && !dev.name.startsWith("Nearby")) dev.name else existing.name,
                            ipAddress = dev.ipAddress ?: existing.ipAddress,
                            port = if (dev.port > 0) dev.port else existing.port,
                            bluetoothAddress = dev.bluetoothAddress ?: existing.bluetoothAddress,
                            transportType = preferFasterTransport,
                            lastSeenTimestamp = maxOf(existing.lastSeenTimestamp, dev.lastSeenTimestamp),
                            sessionId = dev.sessionId ?: existing.sessionId,
                            discoveryGeneration = maxOf(existing.discoveryGeneration, dev.discoveryGeneration)
                        )
                        merged[dev.id] = updated
                        Log.d(TAG, "[PEER_UPDATED] deviceId=${dev.id} transport=${updated.transportType}")
                    }
                }

                // 1. LAN Wi-Fi devices (Highest priority for direct IP)
                lanMap.values.forEach { dev -> processDevice(dev) }

                // 2. Wi-Fi Direct devices
                p2pList.forEach { dev -> processDevice(dev) }

                // 3. BLE devices (Bluetooth fallback or discovery trigger)
                bleMap.values.forEach { dev -> processDevice(dev) }

                merged.values.toList().sortedWith(
                    compareByDescending<DiscoveredDevice> { it.transportType == TransportType.LOCAL_WIFI }
                        .thenByDescending { it.transportType == TransportType.WIFI_DIRECT }
                        .thenByDescending { it.lastSeenTimestamp }
                )
            }.collect { list ->
                _nearbyDevices.value = list
            }
        }
    }

    /**
     * Start discovery as a sender looking for nearby receivers
     */
    fun startDiscovery(localDeviceId: String): Long {
        val gen = synchronized(stateLock) { ++discoveryGeneration }
        clear()
        Log.d(TAG, "[DISCOVERY_START] generation=$gen transport=ALL localDeviceId=$localDeviceId")
        lanDiscovery.startDiscovery(localDeviceId)
        bleDiscovery.startScanning()
        wifiP2pManager.startDiscovery(gen)
        return gen
    }

    /**
     * Stop all discovery
     */
    fun stopDiscovery() {
        val gen = synchronized(stateLock) { ++discoveryGeneration }
        Log.d(TAG, "[DISCOVERY_STOP] generation=$gen")
        lanDiscovery.stopDiscovery()
        bleDiscovery.stopScanning()
        wifiP2pManager.stopDiscovery()
        clear()
    }

    /**
     * Start advertising as a receiver waiting for senders
     */
    fun startAdvertising(localDeviceId: String, localDeviceName: String, tcpPort: Int) {
        stopAdvertising() // Ensure any prior advertisement is stopped first
        lanDiscovery.startAdvertising(localDeviceId, localDeviceName, tcpPort)
        bleDiscovery.startAdvertising(localDeviceId, localDeviceName)
    }

    /**
     * Stop advertising
     */
    fun stopAdvertising() {
        lanDiscovery.stopAdvertising()
        bleDiscovery.stopAdvertising()
    }

    fun clear() {
        lanDiscovery.clearDevices()
        bleDiscovery.clearDevices()
        wifiP2pManager.clearPeers()
        _nearbyDevices.value = emptyList()
    }

    fun release() {
        stopDiscovery()
        stopAdvertising()
        combineJob?.cancel()
        cleanupJob?.cancel()
        wifiP2pManager.unregister()
    }
}
