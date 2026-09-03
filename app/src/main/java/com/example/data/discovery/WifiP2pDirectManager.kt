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
import java.util.UUID

data class WifiDiscoverySession(
    val generation: Long,
    val sessionId: String,
    val startedAt: Long
)

enum class DiscoveryLifecycleState {
    STOPPED,
    STARTING,
    ACTIVE,
    STOPPING
}

class WifiP2pDirectManager(private val context: Context) {

    companion object {
        private const val TAG = "WifiP2pDirectManager"
        const val DEFAULT_PORT = 8888
    }

    private val wifiP2pManager = context.getSystemService(Context.WIFI_P2P_SERVICE) as? WifiP2pManager
    private var channel: WifiP2pManager.Channel? = null
    private var receiver: BroadcastReceiver? = null

    private val stateLock = Any()
    private var discoveryGeneration = 0L
    private var currentSession: WifiDiscoverySession? = null
    private var lifecycleState = DiscoveryLifecycleState.STOPPED
    private var isGroupCreatedByApp = false

    val currentGeneration: Long
        get() = synchronized(stateLock) { discoveryGeneration }

    val activeSession: WifiDiscoverySession?
        get() = synchronized(stateLock) { currentSession }

    val currentLifecycleState: DiscoveryLifecycleState
        get() = synchronized(stateLock) { lifecycleState }

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
            try {
                channel = wifiP2pManager.initialize(context, Looper.getMainLooper(), null)
            } catch (e: Exception) {
                Log.w(TAG, "Error initializing Wi-Fi P2P Channel", e)
            }
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
                        val activeGen: Long
                        val activeSessionId: String
                        synchronized(stateLock) {
                            if (lifecycleState != DiscoveryLifecycleState.ACTIVE && lifecycleState != DiscoveryLifecycleState.STARTING) {
                                Log.d(TAG, "[DISCOVERY_CALLBACK_IGNORED] reason=not_active state=$lifecycleState")
                                return
                            }
                            activeGen = discoveryGeneration
                            activeSessionId = currentSession?.sessionId ?: ""
                        }

                        try {
                            wifiP2pManager?.requestPeers(channel) { peerList: WifiP2pDeviceList ->
                                synchronized(stateLock) {
                                    if (activeGen != discoveryGeneration || currentSession?.sessionId != activeSessionId) {
                                        Log.d(
                                            TAG,
                                            "[DISCOVERY_CALLBACK_IGNORED] reason=stale_generation callbackGen=$activeGen currentGen=$discoveryGeneration"
                                        )
                                        return@requestPeers
                                    }

                                    val now = System.currentTimeMillis()
                                    val devices = peerList.deviceList.map { device ->
                                        DiscoveredDevice(
                                            id = "P2P-" + device.deviceAddress.replace(":", "").takeLast(4),
                                            name = device.deviceName.ifBlank { "Nearby Wi-Fi Direct" },
                                            transportType = TransportType.WIFI_DIRECT,
                                            bluetoothAddress = device.deviceAddress,
                                            isReadyToReceive = true,
                                            lastSeenTimestamp = now,
                                            sessionId = activeSessionId,
                                            discoveryGeneration = activeGen
                                        )
                                    }
                                    _discoveredPeers.value = devices
                                    Log.d(TAG, "[PEER_LIST_UPDATED] generation=$activeGen peerCount=${devices.size}")
                                }
                            }
                        } catch (e: SecurityException) {
                            Log.w(TAG, "Missing permission in requestPeers", e)
                        } catch (e: Exception) {
                            Log.w(TAG, "Exception in requestPeers", e)
                        }
                    }
                    WifiP2pManager.WIFI_P2P_CONNECTION_CHANGED_ACTION -> {
                        val activeGen: Long
                        synchronized(stateLock) {
                            activeGen = discoveryGeneration
                        }
                        val networkInfo = intent.getParcelableExtra<NetworkInfo>(WifiP2pManager.EXTRA_NETWORK_INFO)
                        if (networkInfo?.isConnected == true) {
                            try {
                                wifiP2pManager?.requestConnectionInfo(channel) { info ->
                                    synchronized(stateLock) {
                                        if (activeGen != discoveryGeneration) {
                                            Log.d(TAG, "[DISCOVERY_CALLBACK_IGNORED] reason=stale_generation connection callback ignored")
                                            return@requestConnectionInfo
                                        }
                                        Log.d(TAG, "Wi-Fi Direct connected! Group owner: ${info?.groupOwnerAddress?.hostAddress}, isOwner: ${info?.isGroupOwner}")
                                        _connectionInfo.value = info
                                    }
                                }
                            } catch (e: SecurityException) {
                                Log.w(TAG, "Missing permission in requestConnectionInfo", e)
                            } catch (e: Exception) {
                                Log.w(TAG, "Exception in requestConnectionInfo", e)
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
    fun startDiscovery(targetGeneration: Long? = null): Long {
        synchronized(stateLock) {
            if (lifecycleState == DiscoveryLifecycleState.ACTIVE || lifecycleState == DiscoveryLifecycleState.STARTING) {
                Log.d(TAG, "[DISCOVERY_START_IDEMPOTENT] Already active or starting in generation=$discoveryGeneration")
                return discoveryGeneration
            }
            lifecycleState = DiscoveryLifecycleState.STARTING
            val gen = targetGeneration ?: (++discoveryGeneration)
            discoveryGeneration = gen
            val session = WifiDiscoverySession(
                generation = gen,
                sessionId = UUID.randomUUID().toString(),
                startedAt = System.currentTimeMillis()
            )
            currentSession = session
            _discoveredPeers.value = emptyList() // clear transient peers immediately on new session
            Log.d(TAG, "[DISCOVERY_START] generation=$gen sessionId=${session.sessionId} transport=WIFI_DIRECT")

            register()
            val mgr = wifiP2pManager
            val ch = channel
            if (mgr == null || ch == null) {
                lifecycleState = DiscoveryLifecycleState.STOPPED
                Log.w(TAG, "[DISCOVERY_FAILED] WifiP2pManager or Channel unavailable")
                return gen
            }

            try {
                mgr.discoverPeers(ch, object : WifiP2pManager.ActionListener {
                    override fun onSuccess() {
                        synchronized(stateLock) {
                            if (discoveryGeneration == gen) {
                                lifecycleState = DiscoveryLifecycleState.ACTIVE
                                Log.d(TAG, "[DISCOVERY_ACTIVE] generation=$gen Wi-Fi Direct peer discovery initiated")
                            } else {
                                Log.d(TAG, "[DISCOVERY_CALLBACK_IGNORED] reason=stale_generation callbackGen=$gen currentGen=$discoveryGeneration")
                            }
                        }
                    }

                    override fun onFailure(reasonCode: Int) {
                        synchronized(stateLock) {
                            if (discoveryGeneration == gen) {
                                lifecycleState = DiscoveryLifecycleState.STOPPED
                                Log.w(TAG, "[DISCOVERY_FAILED] generation=$gen reasonCode=$reasonCode")
                            }
                        }
                    }
                })
            } catch (e: SecurityException) {
                Log.e(TAG, "[DISCOVERY_PERMISSION_DENIED] Missing permissions for Wi-Fi Direct discovery", e)
                lifecycleState = DiscoveryLifecycleState.STOPPED
            } catch (e: Exception) {
                Log.e(TAG, "[DISCOVERY_ERROR] Exception initiating Wi-Fi Direct discovery", e)
                lifecycleState = DiscoveryLifecycleState.STOPPED
            }
            return gen
        }
    }

    @SuppressLint("MissingPermission")
    fun stopDiscovery() {
        val genToStop: Long
        synchronized(stateLock) {
            genToStop = discoveryGeneration
            discoveryGeneration++
            lifecycleState = DiscoveryLifecycleState.STOPPING
            currentSession = null
            _discoveredPeers.value = emptyList()
            Log.d(TAG, "[DISCOVERY_STOP] generation=$genToStop")
        }

        val mgr = wifiP2pManager
        val ch = channel
        if (mgr != null && ch != null) {
            try {
                mgr.stopPeerDiscovery(ch, object : WifiP2pManager.ActionListener {
                    override fun onSuccess() {
                        Log.d(TAG, "Wi-Fi Direct peer discovery stopped successfully")
                    }
                    override fun onFailure(reasonCode: Int) {
                        Log.w(TAG, "Wi-Fi Direct stopPeerDiscovery failed: $reasonCode")
                    }
                })
            } catch (e: SecurityException) {
                Log.w(TAG, "SecurityException stopping peer discovery", e)
            } catch (e: Exception) {
                Log.w(TAG, "Exception stopping peer discovery", e)
            }
        }

        synchronized(stateLock) {
            lifecycleState = DiscoveryLifecycleState.STOPPED
        }
    }

    fun clearPeers() {
        _discoveredPeers.value = emptyList()
    }

    @SuppressLint("MissingPermission")
    fun connect(deviceAddress: String, onSuccess: () -> Unit, onFailure: (Int) -> Unit) {
        val mgr = wifiP2pManager ?: return
        val ch = channel ?: return

        val config = WifiP2pConfig().apply {
            this.deviceAddress = deviceAddress
        }

        try {
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
        } catch (e: SecurityException) {
            Log.e(TAG, "SecurityException connecting to Wi-Fi P2P peer", e)
            onFailure(-1)
        } catch (e: Exception) {
            Log.e(TAG, "Exception connecting to Wi-Fi P2P peer", e)
            onFailure(-2)
        }
    }

    @SuppressLint("MissingPermission")
    fun createGroup(onSuccess: () -> Unit, onFailure: (Int) -> Unit) {
        val mgr = wifiP2pManager ?: return
        val ch = channel ?: return

        try {
            mgr.createGroup(ch, object : WifiP2pManager.ActionListener {
                override fun onSuccess() {
                    synchronized(stateLock) {
                        isGroupCreatedByApp = true
                    }
                    Log.d(TAG, "[WIFI_GROUP_CREATED] Wi-Fi Direct group created by DropSend")
                    onSuccess()
                }
                override fun onFailure(reasonCode: Int) {
                    Log.w(TAG, "Wi-Fi Direct group creation failed: $reasonCode")
                    onFailure(reasonCode)
                }
            })
        } catch (e: SecurityException) {
            Log.e(TAG, "SecurityException creating group", e)
            onFailure(-1)
        } catch (e: Exception) {
            Log.e(TAG, "Exception creating group", e)
            onFailure(-2)
        }
    }

    @SuppressLint("MissingPermission")
    fun removeGroup(onlyIfOwned: Boolean = true) {
        synchronized(stateLock) {
            if (onlyIfOwned && !isGroupCreatedByApp) {
                Log.d(TAG, "Skipping removeGroup as group is not owned by DropSend")
                _connectionInfo.value = null
                return
            }
        }
        val mgr = wifiP2pManager ?: return
        val ch = channel ?: return

        try {
            mgr.removeGroup(ch, object : WifiP2pManager.ActionListener {
                override fun onSuccess() {
                    synchronized(stateLock) {
                        isGroupCreatedByApp = false
                    }
                    Log.d(TAG, "[WIFI_GROUP_REMOVED] Wi-Fi Direct group removed")
                }
                override fun onFailure(reason: Int) {
                    Log.w(TAG, "Wi-Fi Direct group remove failed: $reason")
                }
            })
        } catch (e: SecurityException) {
            Log.w(TAG, "SecurityException removing group", e)
        } catch (e: Exception) {
            Log.w(TAG, "Exception removing group", e)
        }
        _connectionInfo.value = null
    }

    fun unregister() {
        stopDiscovery()
        removeGroup(onlyIfOwned = true)
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

    /**
     * Testing/verification hook: simulate peer delivery for a specific generation.
     * Returns true if accepted, false if discarded due to stale generation or inactive state.
     */
    fun injectPeersForTesting(generation: Long, peers: List<DiscoveredDevice>): Boolean {
        synchronized(stateLock) {
            if (generation != discoveryGeneration || (lifecycleState != DiscoveryLifecycleState.ACTIVE && lifecycleState != DiscoveryLifecycleState.STARTING)) {
                Log.d(
                    TAG,
                    "[DISCOVERY_CALLBACK_IGNORED] reason=stale_generation callbackGen=$generation currentGen=$discoveryGeneration"
                )
                return false
            }
            _discoveredPeers.value = peers
            return true
        }
    }

    /**
     * Testing hook to set internal lifecycle state directly.
     */
    fun setLifecycleStateForTesting(state: DiscoveryLifecycleState) {
        synchronized(stateLock) {
            lifecycleState = state
        }
    }
}
