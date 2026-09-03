package com.example.data.discovery

import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothManager
import android.bluetooth.le.AdvertiseCallback
import android.bluetooth.le.AdvertiseData
import android.bluetooth.le.AdvertiseSettings
import android.bluetooth.le.BluetoothLeAdvertiser
import android.bluetooth.le.BluetoothLeScanner
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanFilter
import android.bluetooth.le.ScanResult
import android.bluetooth.le.ScanSettings
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Build
import android.os.ParcelUuid
import android.util.Log
import com.example.domain.model.DiscoveredDevice
import com.example.domain.model.TransportType
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.util.UUID
import kotlin.math.abs

class BleDiscoveryService(
    private val context: Context,
) {
    companion object {
        private const val TAG = "BleDiscoveryService"

        // DropSend 16-bit / 128-bit UUID for BLE Discovery
        val SERVICE_UUID: UUID = UUID.fromString("0000FD88-0000-1000-8000-00805F9B34FB")
        val PARCEL_UUID = ParcelUuid(SERVICE_UUID)

        // Thresholds for advertisement deduplication and update throttling
        private const val RSSI_UPDATE_THRESHOLD_DB = 10
        private const val RSSI_THROTTLE_MS = 1500L
        private const val HEARTBEAT_UPDATE_INTERVAL_MS = 3000L
    }

    private val bluetoothManager = context.getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager
    val bluetoothAdapter: BluetoothAdapter? = bluetoothManager?.adapter

    private var advertiser: BluetoothLeAdvertiser? = null
    private var scanner: BluetoothLeScanner? = null

    private val _discoveredDevices = MutableStateFlow<Map<String, DiscoveredDevice>>(emptyMap())
    val discoveredDevices: StateFlow<Map<String, DiscoveredDevice>> = _discoveredDevices.asStateFlow()

    private var advertiseCallback: AdvertiseCallback? = null
    private var scanCallback: ScanCallback? = null
    private var classicReceiver: BroadcastReceiver? = null

    // Synchronization locks for lifecycle and cache operations
    private val scanStateLock = Any()
    private val advertiseStateLock = Any()
    private val cacheLock = Any()

    @Volatile
    private var isScanning = false

    @Volatile
    private var isAdvertising = false
    private var currentAdvertisingId: String? = null
    private var currentAdvertisingName: String? = null

    // Internal mutable caches for O(1) lookup and zero allocation on deduplicated advertisements
    private val deviceCache = LinkedHashMap<String, DiscoveredDevice>()
    private val addressToId = HashMap<String, String>()
    private val deviceMeta = HashMap<String, DeviceMetadata>()

    private class DeviceMetadata(
        var lastPublishedTimestamp: Long,
        var lastPublishedRssi: Int,
        var hasMeaningfulName: Boolean,
    )

    val isBluetoothEnabled: Boolean
        get() = bluetoothAdapter?.isEnabled == true

    /**
     * Extracts the last [count] characters of a Bluetooth MAC address skipping colons,
     * avoiding regex or substring allocations.
     */
    private fun extractAddressSuffix(
        address: String,
        count: Int,
        prefix: String = "",
    ): String {
        val chars = CharArray(count)
        var filled = 0
        for (i in address.length - 1 downTo 0) {
            val c = address[i]
            if (c != ':') {
                chars[count - 1 - filled] = c
                filled++
                if (filled == count) break
            }
        }
        val suffix = if (filled == count) String(chars) else address.takeLast(count)
        return if (prefix.isEmpty()) suffix else prefix + suffix
    }

    /**
     * Publishes a thread-safe snapshot of discovered devices to StateFlow only when
     * state has actually changed.
     */
    private fun publishDevices() {
        val snapshot: Map<String, DiscoveredDevice> =
            synchronized(cacheLock) {
                HashMap(deviceCache)
            }
        _discoveredDevices.value = snapshot
    }

    @SuppressLint("MissingPermission")
    fun startAdvertising(
        localDeviceId: String,
        localDeviceName: String,
    ) {
        if (!isBluetoothEnabled) return

        synchronized(advertiseStateLock) {
            if (isAdvertising && currentAdvertisingId == localDeviceId && currentAdvertisingName == localDeviceName) {
                return // Idempotent: already advertising with identical parameters
            }
            stopAdvertisingInternal()

            advertiser = bluetoothAdapter?.bluetoothLeAdvertiser
            if (advertiser == null) {
                Log.w(TAG, "BluetoothLeAdvertiser not supported on this device")
                return
            }

            // Balanced advertise mode and medium TX power to optimize battery and radio coexistence
            val settings =
                AdvertiseSettings
                    .Builder()
                    .setAdvertiseMode(AdvertiseSettings.ADVERTISE_MODE_BALANCED)
                    .setTxPowerLevel(AdvertiseSettings.ADVERTISE_TX_POWER_MEDIUM)
                    .setConnectable(true)
                    .setTimeout(0)
                    .build()

            // Keep advertising payload compact (< 31 bytes)
            val serviceData = localDeviceId.toByteArray(Charsets.UTF_8)
            val data =
                AdvertiseData
                    .Builder()
                    .setIncludeDeviceName(false)
                    .setIncludeTxPowerLevel(false)
                    .addServiceUuid(PARCEL_UUID)
                    .addServiceData(PARCEL_UUID, serviceData)
                    .build()

            val scanResponse =
                AdvertiseData
                    .Builder()
                    .setIncludeDeviceName(true)
                    .build()

            val callback =
                object : AdvertiseCallback() {
                    override fun onStartSuccess(settingsInEffect: AdvertiseSettings?) {
                        Log.d(TAG, "BLE Advertising started successfully for $localDeviceId ($localDeviceName)")
                    }

                    override fun onStartFailure(errorCode: Int) {
                        Log.e(TAG, "BLE Advertising failed with error: $errorCode")
                        synchronized(advertiseStateLock) {
                            isAdvertising = false
                            currentAdvertisingId = null
                            currentAdvertisingName = null
                        }
                    }
                }

            advertiseCallback = callback

            try {
                advertiser?.startAdvertising(settings, data, scanResponse, callback)
                isAdvertising = true
                currentAdvertisingId = localDeviceId
                currentAdvertisingName = localDeviceName
            } catch (e: Exception) {
                Log.e(TAG, "Error starting BLE advertising", e)
                advertiseCallback = null
                advertiser = null
                isAdvertising = false
                currentAdvertisingId = null
                currentAdvertisingName = null
            }
        }
    }

    @SuppressLint("MissingPermission")
    fun stopAdvertising() {
        synchronized(advertiseStateLock) {
            stopAdvertisingInternal()
        }
    }

    @SuppressLint("MissingPermission")
    private fun stopAdvertisingInternal() {
        if (!isAdvertising && advertiseCallback == null) return
        try {
            if (advertiseCallback != null && advertiser != null) {
                advertiser?.stopAdvertising(advertiseCallback)
            }
        } catch (e: Exception) {
            Log.w(TAG, "Error stopping BLE advertising", e)
        } finally {
            advertiseCallback = null
            advertiser = null
            isAdvertising = false
            currentAdvertisingId = null
            currentAdvertisingName = null
        }
    }

    @SuppressLint("MissingPermission")
    fun startScanning() {
        if (!isBluetoothEnabled) return

        synchronized(scanStateLock) {
            if (isScanning) return // Idempotent: already scanning
            isScanning = true

            // 1. Immediately populate bonded/paired Bluetooth devices in a single batch
            populateBondedDevices()

            // 2. Start BLE Scanner with hardware filtering and balanced scan settings
            startBleScanner()

            // 3. Start Classic Bluetooth discovery as fallback
            startClassicDiscovery()
        }
    }

    @SuppressLint("MissingPermission")
    private fun populateBondedDevices() {
        try {
            val bondedDevices = bluetoothAdapter?.bondedDevices
            if (!bondedDevices.isNullOrEmpty()) {
                var addedAny = false
                val now = System.currentTimeMillis()
                synchronized(cacheLock) {
                    for (dev in bondedDevices) {
                        val address = dev.address ?: continue
                        val deviceId = extractAddressSuffix(address, 6, "BT-")
                        if (!deviceCache.containsKey(deviceId)) {
                            val devName = dev.name ?: "Paired Device (${address.takeLast(5)})"
                            val discovered =
                                DiscoveredDevice(
                                    id = deviceId,
                                    name = "$devName (Paired)",
                                    transportType = TransportType.BLUETOOTH,
                                    bluetoothAddress = address,
                                    isReadyToReceive = true,
                                    lastSeenTimestamp = now,
                                )
                            deviceCache[deviceId] = discovered
                            addressToId[address] = deviceId
                            deviceMeta[deviceId] =
                                DeviceMetadata(
                                    lastPublishedTimestamp = now,
                                    lastPublishedRssi = 0,
                                    hasMeaningfulName = true,
                                )
                            addedAny = true
                        }
                    }
                }
                if (addedAny) {
                    publishDevices()
                }
            }
        } catch (e: Exception) {
            Log.w(TAG, "Error listing bonded devices", e)
        }
    }

    @SuppressLint("MissingPermission")
    private fun startBleScanner() {
        scanner = bluetoothAdapter?.bluetoothLeScanner
        if (scanner == null) return

        // Hardware filtering using DropSend PARCEL_UUID
        val scanFilters =
            listOf(
                ScanFilter
                    .Builder()
                    .setServiceUuid(PARCEL_UUID)
                    .build(),
            )

        // Balanced scan mode and aggressive match mode for fast, battery-efficient discovery
        val scanSettings =
            ScanSettings
                .Builder()
                .setScanMode(ScanSettings.SCAN_MODE_BALANCED)
                .setCallbackType(ScanSettings.CALLBACK_TYPE_ALL_MATCHES)
                .setMatchMode(ScanSettings.MATCH_MODE_AGGRESSIVE)
                .setNumOfMatches(ScanSettings.MATCH_NUM_MAX_ADVERTISEMENT)
                .setReportDelay(0)
                .build()

        val callback =
            object : ScanCallback() {
                override fun onScanResult(
                    callbackType: Int,
                    result: ScanResult?,
                ) {
                    if (!isScanning || result == null) return
                    if (processScanResult(result)) {
                        publishDevices()
                    }
                }

                override fun onBatchScanResults(results: MutableList<ScanResult>?) {
                    if (!isScanning || results.isNullOrEmpty()) return
                    var hasChanges = false
                    for (result in results) {
                        if (!isScanning) return
                        if (processScanResult(result)) {
                            hasChanges = true
                        }
                    }
                    if (hasChanges) {
                        publishDevices()
                    }
                }

                override fun onScanFailed(errorCode: Int) {
                    Log.e(TAG, "BLE Scan failed: $errorCode")
                }
            }

        scanCallback = callback

        try {
            scanner?.startScan(scanFilters, scanSettings, callback)
            Log.d(TAG, "BLE Scanner started with PARCEL_UUID filter")
        } catch (e: Exception) {
            Log.e(TAG, "Error starting BLE scan", e)
        }
    }

    @SuppressLint("MissingPermission")
    private fun processScanResult(result: ScanResult): Boolean {
        val device = result.device ?: return false
        val address = device.address ?: return false
        val now = System.currentTimeMillis()

        // 1. Fast path: check if this device address is already in the cache
        var existingId: String?
        var existingDevice: DiscoveredDevice?
        var meta: DeviceMetadata?

        synchronized(cacheLock) {
            existingId = addressToId[address]
            if (existingId != null) {
                existingDevice = deviceCache[existingId]
                meta = deviceMeta[existingId]
            } else {
                existingDevice = null
                meta = null
            }
        }

        if (existingId != null && existingDevice != null && meta != null) {
            val rawName = result.scanRecord?.deviceName ?: device.name
            val hasNewMeaningfulName =
                !meta.hasMeaningfulName &&
                    !rawName.isNullOrBlank() &&
                    !rawName.startsWith("DROP-")

            val rssiDiff = abs(result.rssi - meta.lastPublishedRssi)
            val timeSinceLastPublish = now - meta.lastPublishedTimestamp

            val shouldUpdate =
                hasNewMeaningfulName ||
                    (rssiDiff >= RSSI_UPDATE_THRESHOLD_DB && timeSinceLastPublish >= RSSI_THROTTLE_MS) ||
                    (timeSinceLastPublish >= HEARTBEAT_UPDATE_INTERVAL_MS)

            if (!shouldUpdate) {
                // Hot path: drop repeated advertisement with zero object allocations
                return false
            }

            val updatedName = if (hasNewMeaningfulName && !rawName.isNullOrBlank()) rawName else existingDevice.name
            val updatedDevice =
                existingDevice.copy(
                    name = updatedName,
                    rssi = result.rssi,
                    lastSeenTimestamp = now,
                )

            synchronized(cacheLock) {
                deviceCache[existingId!!] = updatedDevice
                meta.lastPublishedTimestamp = now
                meta.lastPublishedRssi = result.rssi
                if (hasNewMeaningfulName) {
                    meta.hasMeaningfulName = true
                }
            }
            return true
        }

        // 2. Slow path for new or unindexed device: parse advertisement payload
        val record = result.scanRecord ?: return false
        val serviceData = record.getServiceData(PARCEL_UUID)
        val hasUuid = record.serviceUuids?.contains(PARCEL_UUID) == true
        val devName = record.deviceName ?: device.name ?: ""

        val isDropSendDevice =
            (serviceData != null && serviceData.isNotEmpty()) ||
                hasUuid ||
                devName.startsWith("DROP-") ||
                devName.contains("DropSend", ignoreCase = true)

        if (!isDropSendDevice) return false

        val deviceId =
            if (serviceData != null && serviceData.isNotEmpty()) {
                String(serviceData, Charsets.UTF_8).trim()
            } else if (devName.startsWith("DROP-")) {
                devName.substringBefore(' ')
            } else {
                extractAddressSuffix(address, 4, "DROP-")
            }

        val isMeaningfulName = devName.isNotBlank() && !devName.startsWith("DROP-")
        val displayName =
            if (isMeaningfulName) {
                devName
            } else {
                "Nearby Device (${extractAddressSuffix(address, 4, "")})"
            }

        val discovered =
            DiscoveredDevice(
                id = deviceId,
                name = displayName,
                transportType = TransportType.BLUETOOTH,
                bluetoothAddress = address,
                rssi = result.rssi,
                isReadyToReceive = true,
                lastSeenTimestamp = now,
            )

        synchronized(cacheLock) {
            addressToId[address] = deviceId
            deviceCache[deviceId] = discovered
            deviceMeta[deviceId] =
                DeviceMetadata(
                    lastPublishedTimestamp = now,
                    lastPublishedRssi = result.rssi,
                    hasMeaningfulName = isMeaningfulName,
                )
        }
        return true
    }

    @SuppressLint("MissingPermission")
    private fun startClassicDiscovery() {
        try {
            if (classicReceiver == null) {
                val receiver =
                    object : BroadcastReceiver() {
                        override fun onReceive(
                            context: Context?,
                            intent: Intent?,
                        ) {
                            if (!isScanning) return
                            if (intent?.action == BluetoothDevice.ACTION_FOUND) {
                                val device: BluetoothDevice? =
                                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                                        intent.getParcelableExtra(BluetoothDevice.EXTRA_DEVICE, BluetoothDevice::class.java)
                                    } else {
                                        @Suppress("DEPRECATION")
                                        intent.getParcelableExtra(BluetoothDevice.EXTRA_DEVICE)
                                    }
                                val address = device?.address ?: return
                                val deviceId = extractAddressSuffix(address, 6, "BT-")
                                val now = System.currentTimeMillis()

                                // Deduplication: prevent repeated processing of the same device
                                val shouldUpdate: Boolean
                                synchronized(cacheLock) {
                                    val meta = deviceMeta[deviceId]
                                    shouldUpdate = (meta == null) || (now - meta.lastPublishedTimestamp >= HEARTBEAT_UPDATE_INTERVAL_MS)
                                }
                                if (!shouldUpdate) return

                                val devName = device?.name ?: intent.getStringExtra(BluetoothDevice.EXTRA_NAME)
                                val devRssi = intent.getShortExtra(BluetoothDevice.EXTRA_RSSI, Short.MIN_VALUE).toInt()
                                val displayName = devName?.ifBlank { null } ?: "Bluetooth Device (${address.takeLast(5)})"

                                val discovered =
                                    DiscoveredDevice(
                                        id = deviceId,
                                        name = displayName,
                                        transportType = TransportType.BLUETOOTH,
                                        bluetoothAddress = address,
                                        rssi = if (devRssi != Short.MIN_VALUE.toInt()) devRssi else -65,
                                        isReadyToReceive = true,
                                        lastSeenTimestamp = now,
                                    )

                                synchronized(cacheLock) {
                                    deviceCache[deviceId] = discovered
                                    addressToId[address] = deviceId
                                    val meta = deviceMeta[deviceId]
                                    if (meta != null) {
                                        meta.lastPublishedTimestamp = now
                                        meta.lastPublishedRssi = discovered.rssi
                                    } else {
                                        deviceMeta[deviceId] =
                                            DeviceMetadata(
                                                lastPublishedTimestamp = now,
                                                lastPublishedRssi = discovered.rssi,
                                                hasMeaningfulName = devName?.isNotBlank() == true,
                                            )
                                    }
                                }
                                publishDevices()
                            }
                        }
                    }

                val filter = IntentFilter(BluetoothDevice.ACTION_FOUND)
                context.registerReceiver(receiver, filter)
                classicReceiver = receiver
            }

            // Always cancel prior discovery before initiating to avoid hardware collision
            if (bluetoothAdapter?.isDiscovering == true) {
                bluetoothAdapter.cancelDiscovery()
            }
            bluetoothAdapter?.startDiscovery()
            Log.d(TAG, "Classic Bluetooth discovery started")
        } catch (e: Exception) {
            Log.w(TAG, "Error starting classic Bluetooth discovery", e)
        }
    }

    @SuppressLint("MissingPermission")
    fun stopScanning() {
        synchronized(scanStateLock) {
            if (!isScanning) return // Idempotent: already stopped
            isScanning = false

            try {
                if (scanCallback != null && scanner != null) {
                    scanner?.stopScan(scanCallback)
                }
            } catch (e: Exception) {
                Log.w(TAG, "Error stopping BLE scan", e)
            } finally {
                scanCallback = null
                scanner = null
            }

            try {
                if (bluetoothAdapter?.isDiscovering == true) {
                    bluetoothAdapter.cancelDiscovery()
                }
                classicReceiver?.let {
                    context.unregisterReceiver(it)
                }
            } catch (e: Exception) {
                Log.w(TAG, "Error stopping classic Bluetooth discovery", e)
            } finally {
                classicReceiver = null
            }
        }
    }

    fun clearDevices() {
        synchronized(cacheLock) {
            deviceCache.clear()
            addressToId.clear()
            deviceMeta.clear()
        }
        _discoveredDevices.value = emptyMap()
    }
}
