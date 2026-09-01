package com.example.data.connectivity

import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.net.wifi.WifiManager
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.net.Inet4Address
import java.net.NetworkInterface

data class ConnectivityState(
    val isBluetoothOn: Boolean = false,
    val isWifiOn: Boolean = false,
    val localIpAddress: String? = null
)

class ConnectivityMonitor(private val context: Context) {

    private val bluetoothManager = context.getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager
    private val connectivityManager = context.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager
    private val wifiManager = context.applicationContext.getSystemService(Context.WIFI_SERVICE) as? WifiManager

    private val _state = MutableStateFlow(checkInitialState())
    val state: StateFlow<ConnectivityState> = _state.asStateFlow()

    private var btReceiver: BroadcastReceiver? = null
    private var networkCallback: ConnectivityManager.NetworkCallback? = null

    private fun checkInitialState(): ConnectivityState {
        val isBt = bluetoothManager?.adapter?.isEnabled == true
        val isWifi = wifiManager?.isWifiEnabled == true || isConnectedToWifiNetwork()
        val ip = getLocalIpAddress()
        return ConnectivityState(isBluetoothOn = isBt, isWifiOn = isWifi, localIpAddress = ip)
    }

    fun startMonitoring() {
        updateState()

        // 1. Bluetooth receiver
        btReceiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context?, intent: Intent?) {
                if (intent?.action == BluetoothAdapter.ACTION_STATE_CHANGED) {
                    val btState = intent.getIntExtra(BluetoothAdapter.EXTRA_STATE, BluetoothAdapter.ERROR)
                    val isOn = btState == BluetoothAdapter.STATE_ON
                    _state.value = _state.value.copy(
                        isBluetoothOn = isOn,
                        localIpAddress = getLocalIpAddress()
                    )
                }
            }
        }
        val btFilter = IntentFilter(BluetoothAdapter.ACTION_STATE_CHANGED)
        try {
            context.registerReceiver(btReceiver, btFilter)
        } catch (_: Exception) {}

        // 2. Wi-Fi & Network callback
        val request = NetworkRequest.Builder()
            .addTransportType(NetworkCapabilities.TRANSPORT_WIFI)
            .addTransportType(NetworkCapabilities.TRANSPORT_CELLULAR)
            .addTransportType(NetworkCapabilities.TRANSPORT_ETHERNET)
            .build()

        networkCallback = object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: Network) {
                updateState()
            }
            override fun onLost(network: Network) {
                updateState()
            }
            override fun onCapabilitiesChanged(network: Network, capabilities: NetworkCapabilities) {
                updateState()
            }
        }
        try {
            connectivityManager?.registerNetworkCallback(request, networkCallback!!)
        } catch (_: Exception) {}
    }

    fun updateState() {
        val isBt = bluetoothManager?.adapter?.isEnabled == true
        val isWifi = wifiManager?.isWifiEnabled == true || isConnectedToWifiNetwork()
        val ip = getLocalIpAddress()
        _state.value = ConnectivityState(isBluetoothOn = isBt, isWifiOn = isWifi, localIpAddress = ip)
    }

    private fun isConnectedToWifiNetwork(): Boolean {
        val cm = connectivityManager ?: return false
        val activeNetwork = cm.activeNetwork ?: return false
        val caps = cm.getNetworkCapabilities(activeNetwork) ?: return false
        return caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)
    }

    fun getLocalIpAddress(): String? {
        try {
            val interfaces = NetworkInterface.getNetworkInterfaces()
            while (interfaces.hasMoreElements()) {
                val iface = interfaces.nextElement()
                if (iface.isLoopback || !iface.isUp) continue
                val addresses = iface.inetAddresses
                while (addresses.hasMoreElements()) {
                    val addr = addresses.nextElement()
                    if (!addr.isLoopbackAddress && addr is Inet4Address) {
                        return addr.hostAddress
                    }
                }
            }
        } catch (_: Exception) {}
        return null
    }

    fun stopMonitoring() {
        try {
            btReceiver?.let { context.unregisterReceiver(it) }
        } catch (_: Exception) {}
        btReceiver = null

        try {
            networkCallback?.let { connectivityManager?.unregisterNetworkCallback(it) }
        } catch (_: Exception) {}
        networkCallback = null
    }
}
