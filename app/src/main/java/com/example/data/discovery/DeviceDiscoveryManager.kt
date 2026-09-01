package com.example.data.discovery

import android.content.Context
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

    val bleDiscovery = BleDiscoveryService(context)
    val lanDiscovery = LanDiscoveryService(context)
    val wifiP2pManager = WifiP2pDirectManager(context)

    private val scope = CoroutineScope(Dispatchers.IO + Job())

    private val _nearbyDevices = MutableStateFlow<List<DiscoveredDevice>>(emptyList())
    val nearbyDevices: StateFlow<List<DiscoveredDevice>> = _nearbyDevices.asStateFlow()

    private var combineJob: Job? = null

    init {
        startCombining()
    }

    private fun startCombining() {
        combineJob = scope.launch {
            combine(
                lanDiscovery.discoveredDevices,
                bleDiscovery.discoveredDevices,
                wifiP2pManager.discoveredPeers
            ) { lanMap, bleMap, p2pList ->
                val merged = mutableMapOf<String, DiscoveredDevice>()

                // 1. LAN Wi-Fi devices (Highest priority for direct IP)
                lanMap.values.forEach { dev ->
                    merged[dev.id] = dev
                }

                // 2. Wi-Fi Direct devices
                p2pList.forEach { dev ->
                    if (!merged.containsKey(dev.id)) {
                        merged[dev.id] = dev
                    }
                }

                // 3. BLE devices (Bluetooth fallback or discovery trigger)
                bleMap.values.forEach { dev ->
                    val existing = merged[dev.id]
                    if (existing == null) {
                        merged[dev.id] = dev
                    } else if (existing.bluetoothAddress == null && dev.bluetoothAddress != null) {
                        // Enrich existing LAN device with Bluetooth info if available
                        merged[dev.id] = existing.copy(bluetoothAddress = dev.bluetoothAddress)
                    }
                }

                merged.values.toList().sortedByDescending { it.transportType == TransportType.LOCAL_WIFI || it.transportType == TransportType.WIFI_DIRECT }
            }.collect { list ->
                _nearbyDevices.value = list
            }
        }
    }

    /**
     * Start discovery as a sender looking for nearby receivers
     */
    fun startDiscovery(localDeviceId: String) {
        clear()
        lanDiscovery.startDiscovery(localDeviceId)
        bleDiscovery.startScanning()
        wifiP2pManager.startDiscovery()
    }

    /**
     * Stop all discovery
     */
    fun stopDiscovery() {
        lanDiscovery.stopDiscovery()
        bleDiscovery.stopScanning()
    }

    /**
     * Start advertising as a receiver waiting for senders
     */
    fun startAdvertising(localDeviceId: String, localDeviceName: String, tcpPort: Int) {
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
        _nearbyDevices.value = emptyList()
    }

    fun release() {
        stopDiscovery()
        stopAdvertising()
        combineJob?.cancel()
        wifiP2pManager.unregister()
    }
}
