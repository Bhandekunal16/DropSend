package com.example.data.discovery

import android.annotation.SuppressLint
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.net.NetworkInfo
import android.net.wifi.p2p.WifiP2pConfig
import android.net.wifi.p2p.WifiP2pDevice
import android.net.wifi.p2p.WifiP2pDeviceList
import android.net.wifi.p2p.WifiP2pInfo
import android.net.wifi.p2p.WifiP2pManager
import android.os.Looper
import android.util.Log
import com.example.domain.model.DiscoveredDevice
import com.example.domain.model.TransportType
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

class WifiP2pDirectManager(private val context: Context) {

    companion object {
        private const val TAG = "WifiP2pDirectManager"
        const val DEFAULT_PORT = 8888
    }

    private val wifiP2pManager = context.getSystemService(Context.WIFI_P2P_SERVICE) as? WifiP2pManager
    private var channel: WifiP2pManager.Channel? = null
    private var receiver: BroadcastReceiver? = null

    private val _isWifiP2pEnabled = MutableStateFlow(false)
    val isWifiP2pEnabled: StateFlow<Boolean> = _isWifiP2pEnabled.asStateFlow()

    private val _discoveredPeers = MutableStateFlow<List<DiscoveredDevice>>(emptyList())
    val discoveredPeers: StateFlow<List<DiscoveredDevice>> = _discoveredPeers.asStateFlow()

    private val _connectionInfo = MutableStateFlow<WifiP2pInfo?>(null)
    val connectionInfo: StateFlow<WifiP2pInfo?> = _connectionInfo.asStateFlow()

    init {
        initChannel()
    }

    private fun initChannel() {
        if (wifiP2pManager != null && channel == null) {
            channel = wifiP2pManager.initialize(context, Looper.getMainLooper(), null)
        }
    }

    fun register() {
        initChannel()
        if (receiver != null) return

        val intentFilter = IntentFilter().apply {
            addAction(WifiP2pManager.WIFI_P2P_STATE_CHANGED_ACTION)
            addAction(WifiP2pManager.WIFI_P2P_PEERS_CHANGED_ACTION)
            addAction(WifiP2pManager.WIFI_P2P_CONNECTION_CHANGED_ACTION)
            addAction(WifiP2pManager.WIFI_P2P_THIS_DEVICE_CHANGED_ACTION)
        }

        receiver = object : BroadcastReceiver() {
            @SuppressLint("MissingPermission")
            override fun onReceive(context: Context, intent: Intent) {
                when (intent.action) {
                    WifiP2pManager.WIFI_P2P_STATE_CHANGED_ACTION -> {
                        val state = intent.getIntExtra(WifiP2pManager.EXTRA_WIFI_STATE, -1)
                        _isWifiP2pEnabled.value = (state == WifiP2pManager.WIFI_P2P_STATE_ENABLED)
                    }
                    WifiP2pManager.WIFI_P2P_PEERS_CHANGED_ACTION -> {
                        wifiP2pManager?.requestPeers(channel) { peerList: WifiP2pDeviceList ->
                            val devices = peerList.deviceList.map { device ->
                                DiscoveredDevice(
                                    id = "P2P-" + device.deviceAddress.replace(":", "").takeLast(4),
                                    name = device.deviceName.ifBlank { "Nearby Wi-Fi Direct" },
                                    transportType = TransportType.WIFI_DIRECT,
                                    bluetoothAddress = device.deviceAddress,
                                    isReadyToReceive = true
                                )
                            }
                            _discoveredPeers.value = devices
                        }
                    }
                    WifiP2pManager.WIFI_P2P_CONNECTION_CHANGED_ACTION -> {
                        val networkInfo = intent.getParcelableExtra<NetworkInfo>(WifiP2pManager.EXTRA_NETWORK_INFO)
                        if (networkInfo?.isConnected == true) {
                            wifiP2pManager?.requestConnectionInfo(channel) { info ->
                                Log.d(TAG, "Wi-Fi Direct connected! Group owner: ${info.groupOwnerAddress?.hostAddress}, isOwner: ${info.isGroupOwner}")
                                _connectionInfo.value = info
                            }
                        } else {
                            _connectionInfo.value = null
                        }
                    }
                }
            }
        }

        try {
            context.registerReceiver(receiver, intentFilter)
        } catch (e: Exception) {
            Log.w(TAG, "Error registering Wi-Fi P2P receiver", e)
        }
    }

    @SuppressLint("MissingPermission")
    fun startDiscovery() {
        register()
        val mgr = wifiP2pManager ?: return
        val ch = channel ?: return

        mgr.discoverPeers(ch, object : WifiP2pManager.ActionListener {
            override fun onSuccess() {
                Log.d(TAG, "Wi-Fi Direct peer discovery initiated")
            }
            override fun onFailure(reasonCode: Int) {
                Log.w(TAG, "Wi-Fi Direct peer discovery failed: $reasonCode")
            }
        })
    }

    @SuppressLint("MissingPermission")
    fun connect(deviceAddress: String, onSuccess: () -> Unit, onFailure: (Int) -> Unit) {
        val mgr = wifiP2pManager ?: return
        val ch = channel ?: return

        val config = WifiP2pConfig().apply {
            this.deviceAddress = deviceAddress
        }

        mgr.connect(ch, config, object : WifiP2pManager.ActionListener {
            override fun onSuccess() {
                Log.d(TAG, "Wi-Fi P2P connection request sent")
                onSuccess()
            }
            override fun onFailure(reasonCode: Int) {
                Log.e(TAG, "Wi-Fi P2P connection failed: $reasonCode")
                onFailure(reasonCode)
            }
        })
    }

    @SuppressLint("MissingPermission")
    fun createGroup(onSuccess: () -> Unit, onFailure: (Int) -> Unit) {
        val mgr = wifiP2pManager ?: return
        val ch = channel ?: return

        mgr.createGroup(ch, object : WifiP2pManager.ActionListener {
            override fun onSuccess() {
                Log.d(TAG, "Wi-Fi Direct group created")
                onSuccess()
            }
            override fun onFailure(reasonCode: Int) {
                Log.w(TAG, "Wi-Fi Direct group creation failed: $reasonCode")
                onFailure(reasonCode)
            }
        })
    }

    @SuppressLint("MissingPermission")
    fun removeGroup() {
        val mgr = wifiP2pManager ?: return
        val ch = channel ?: return

        mgr.removeGroup(ch, object : WifiP2pManager.ActionListener {
            override fun onSuccess() {
                Log.d(TAG, "Wi-Fi Direct group removed")
            }
            override fun onFailure(reason: Int) {
                Log.w(TAG, "Wi-Fi Direct group remove failed: $reason")
            }
        })
        _connectionInfo.value = null
    }

    fun unregister() {
        removeGroup()
        try {
            if (receiver != null) {
                context.unregisterReceiver(receiver)
            }
        } catch (e: Exception) {
            Log.w(TAG, "Error unregistering receiver", e)
        } finally {
            receiver = null
        }
    }
}
