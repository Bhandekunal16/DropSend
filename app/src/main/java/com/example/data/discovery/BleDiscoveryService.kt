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
import android.os.ParcelUuid
import android.util.Log
import com.example.domain.model.DiscoveredDevice
import com.example.domain.model.TransportType
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.util.UUID

class BleDiscoveryService(private val context: Context) {

    companion object {
        private const val TAG = "BleDiscoveryService"
        // DropSend 16-bit / 128-bit UUID for BLE Discovery
        val SERVICE_UUID: UUID = UUID.fromString("0000FD88-0000-1000-8000-00805F9B34FB")
        val PARCEL_UUID = ParcelUuid(SERVICE_UUID)
    }

    private val bluetoothManager = context.getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager
    val bluetoothAdapter: BluetoothAdapter? = bluetoothManager?.adapter

    private var advertiser: BluetoothLeAdvertiser? = null
    private var scanner: BluetoothLeScanner? = null

    private val _discoveredDevices = MutableStateFlow<Map<String, DiscoveredDevice>>(emptyMap())
    val discoveredDevices: StateFlow<Map<String, DiscoveredDevice>> = _discoveredDevices.asStateFlow()

    private var advertiseCallback: AdvertiseCallback? = null
    private var scanCallback: ScanCallback? = null

    val isBluetoothEnabled: Boolean
        get() = bluetoothAdapter?.isEnabled == true

    @SuppressLint("MissingPermission")
    fun startAdvertising(localDeviceId: String, localDeviceName: String) {
        if (!isBluetoothEnabled) return
        stopAdvertising()

        advertiser = bluetoothAdapter?.bluetoothLeAdvertiser
        if (advertiser == null) {
            Log.w(TAG, "BluetoothLeAdvertiser not supported on this device")
            return
        }

        val settings = AdvertiseSettings.Builder()
            .setAdvertiseMode(AdvertiseSettings.ADVERTISE_MODE_LOW_LATENCY)
            .setTxPowerLevel(AdvertiseSettings.ADVERTISE_TX_POWER_HIGH)
            .setConnectable(true)
            .setTimeout(0)
            .build()

        // Keep advertising payload compact (< 31 bytes)
        val serviceData = localDeviceId.toByteArray(Charsets.UTF_8)
        val data = AdvertiseData.Builder()
            .setIncludeDeviceName(false)
            .setIncludeTxPowerLevel(false)
            .addServiceUuid(PARCEL_UUID)
            .addServiceData(PARCEL_UUID, serviceData)
            .build()

        val scanResponse = AdvertiseData.Builder()
            .setIncludeDeviceName(true)
            .build()

        advertiseCallback = object : AdvertiseCallback() {
            override fun onStartSuccess(settingsInEffect: AdvertiseSettings?) {
                Log.d(TAG, "BLE Advertising started successfully for $localDeviceId ($localDeviceName)")
            }

            override fun onStartFailure(errorCode: Int) {
                Log.e(TAG, "BLE Advertising failed with error: $errorCode")
            }
        }

        try {
            advertiser?.startAdvertising(settings, data, scanResponse, advertiseCallback)
        } catch (e: Exception) {
            Log.e(TAG, "Error starting BLE advertising", e)
        }
    }

    @SuppressLint("MissingPermission")
    fun stopAdvertising() {
        try {
            if (advertiseCallback != null && advertiser != null) {
                advertiser?.stopAdvertising(advertiseCallback)
            }
        } catch (e: Exception) {
            Log.w(TAG, "Error stopping BLE advertising", e)
        } finally {
            advertiseCallback = null
            advertiser = null
        }
    }

    private var classicReceiver: BroadcastReceiver? = null

    @SuppressLint("MissingPermission")
    fun startScanning() {
        if (!isBluetoothEnabled) return
        stopScanning()

        // 1. Immediately populate bonded/paired Bluetooth devices
        try {
            val bondedDevices = bluetoothAdapter?.bondedDevices
            if (!bondedDevices.isNullOrEmpty()) {
                val current = _discoveredDevices.value.toMutableMap()
                for (dev in bondedDevices) {
                    val address = dev.address ?: continue
                    val devName = dev.name ?: "Paired Device (${address.takeLast(5)})"
                    val deviceId = "BT-" + address.replace(":", "").takeLast(6)
                    val discovered = DiscoveredDevice(
                        id = deviceId,
                        name = "$devName (Paired)",
                        transportType = TransportType.BLUETOOTH,
                        bluetoothAddress = address,
                        isReadyToReceive = true,
                        lastSeenTimestamp = System.currentTimeMillis()
                    )
                    current[deviceId] = discovered
                }
                _discoveredDevices.value = current
            }
        } catch (e: Exception) {
            Log.w(TAG, "Error listing bonded devices", e)
        }

        // 2. Start BLE Scanner
        scanner = bluetoothAdapter?.bluetoothLeScanner
        if (scanner != null) {
            val settings = ScanSettings.Builder()
                .setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY)
                .setReportDelay(0)
                .build()

            scanCallback = object : ScanCallback() {
                override fun onScanResult(callbackType: Int, result: ScanResult?) {
                    result ?: return
                    handleScanResult(result)
                }

                override fun onBatchScanResults(results: MutableList<ScanResult>?) {
                    results?.forEach { handleScanResult(it) }
                }

                override fun onScanFailed(errorCode: Int) {
                    Log.e(TAG, "BLE Scan failed: $errorCode")
                }
            }

            try {
                scanner?.startScan(emptyList<ScanFilter>(), settings, scanCallback)
                Log.d(TAG, "BLE Scanner started")
            } catch (e: Exception) {
                Log.e(TAG, "Error starting BLE scan", e)
            }
        }

        // 3. Start Classic Bluetooth Scan
        try {
            classicReceiver = object : BroadcastReceiver() {
                override fun onReceive(context: Context?, intent: Intent?) {
                    if (intent?.action == BluetoothDevice.ACTION_FOUND) {
                        val device: BluetoothDevice? = if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.TIRAMISU) {
                            intent.getParcelableExtra(BluetoothDevice.EXTRA_DEVICE, BluetoothDevice::class.java)
                        } else {
                            @Suppress("DEPRECATION")
                            intent.getParcelableExtra(BluetoothDevice.EXTRA_DEVICE)
                        }
                        val devName = device?.name ?: intent.getStringExtra(BluetoothDevice.EXTRA_NAME)
                        val address = device?.address ?: return
                        val devRssi = intent.getShortExtra(BluetoothDevice.EXTRA_RSSI, Short.MIN_VALUE).toInt()

                        val deviceId = "BT-" + address.replace(":", "").takeLast(6)
                        val displayName = devName?.ifBlank { null } ?: "Bluetooth Device (${address.takeLast(5)})"

                        val discovered = DiscoveredDevice(
                            id = deviceId,
                            name = displayName,
                            transportType = TransportType.BLUETOOTH,
                            bluetoothAddress = address,
                            rssi = if (devRssi != Short.MIN_VALUE.toInt()) devRssi else -65,
                            isReadyToReceive = true,
                            lastSeenTimestamp = System.currentTimeMillis()
                        )

                        val current = _discoveredDevices.value.toMutableMap()
                        if (!current.containsKey(deviceId)) {
                            current[deviceId] = discovered
                            _discoveredDevices.value = current
                        }
                    }
                }
            }
            val filter = IntentFilter(BluetoothDevice.ACTION_FOUND)
            context.registerReceiver(classicReceiver, filter)
            bluetoothAdapter?.startDiscovery()
            Log.d(TAG, "Classic Bluetooth discovery started")
        } catch (e: Exception) {
            Log.w(TAG, "Error starting classic Bluetooth discovery", e)
        }
    }

    @SuppressLint("MissingPermission")
    private fun handleScanResult(result: ScanResult) {
        val record = result.scanRecord ?: return
        val serviceData = record.getServiceData(PARCEL_UUID)
        val hasUuid = record.serviceUuids?.contains(PARCEL_UUID) == true
        val devName = record.deviceName ?: result.device.name ?: ""

        val isDropSendDevice = (serviceData != null && serviceData.isNotEmpty()) ||
                hasUuid ||
                devName.startsWith("DROP-") ||
                devName.contains("DropSend", ignoreCase = true)

        if (!isDropSendDevice) return

        val deviceId = if (serviceData != null && serviceData.isNotEmpty()) {
            String(serviceData, Charsets.UTF_8).trim()
        } else if (devName.startsWith("DROP-")) {
            devName.substringBefore(" ")
        } else {
            "DROP-" + result.device.address.replace(":", "").takeLast(4)
        }

        val displayName = if (devName.isNotBlank() && !devName.startsWith("DROP-")) {
            devName
        } else {
            "Nearby Device (${deviceId.takeLast(4)})"
        }

        val device = DiscoveredDevice(
            id = deviceId,
            name = displayName,
            transportType = TransportType.BLUETOOTH,
            bluetoothAddress = result.device.address,
            rssi = result.rssi,
            isReadyToReceive = true,
            lastSeenTimestamp = System.currentTimeMillis()
        )

        val current = _discoveredDevices.value.toMutableMap()
        current[deviceId] = device
        _discoveredDevices.value = current
    }

    @SuppressLint("MissingPermission")
    fun stopScanning() {
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
            bluetoothAdapter?.cancelDiscovery()
            classicReceiver?.let {
                context.unregisterReceiver(it)
            }
        } catch (e: Exception) {
            Log.w(TAG, "Error stopping classic Bluetooth discovery", e)
        } finally {
            classicReceiver = null
        }
    }

    fun clearDevices() {
        _discoveredDevices.value = emptyMap()
    }
}
