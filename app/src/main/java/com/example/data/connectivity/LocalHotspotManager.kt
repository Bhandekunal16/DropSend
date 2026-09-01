package com.example.data.connectivity

import android.annotation.SuppressLint
import android.content.Context
import android.net.wifi.WifiManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.util.Log
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.net.Inet4Address
import java.net.NetworkInterface

data class LocalHotspotInfo(
    val isActive: Boolean = false,
    val ssid: String = "",
    val passphrase: String = "",
    val ipAddress: String = "",
    val port: Int = 8888,
    val connectionPayload: String = "",
    val standardWifiQr: String = "",
    val errorMessage: String? = null
)

class LocalHotspotManager(private val context: Context) {

    companion object {
        private const val TAG = "LocalHotspotManager"
        const val DEFAULT_PORT = 8888
    }

    private val wifiManager = context.applicationContext.getSystemService(Context.WIFI_SERVICE) as? WifiManager
    private var reservation: Any? = null // WifiManager.LocalOnlyHotspotReservation on API 26+

    private val _hotspotState = MutableStateFlow(LocalHotspotInfo())
    val hotspotState: StateFlow<LocalHotspotInfo> = _hotspotState.asStateFlow()

    @SuppressLint("MissingPermission")
    fun startLocalHotspot(
        deviceId: String,
        deviceName: String,
        onStarted: ((LocalHotspotInfo) -> Unit)? = null
    ) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) {
            // Pre-Oreo fallback
            val fallbackIp = getHotspotIpAddress() ?: "192.168.43.1"
            val fallbackInfo = createHotspotInfo("DropSend-$deviceId", "dp_$deviceId", fallbackIp, deviceId, deviceName)
            _hotspotState.value = fallbackInfo
            onStarted?.invoke(fallbackInfo)
            return
        }

        if (reservation != null) {
            _hotspotState.value.let { onStarted?.invoke(it) }
            return
        }

        val wm = wifiManager
        if (wm == null) {
            val fallbackIp = getHotspotIpAddress() ?: "192.168.43.1"
            val fallbackInfo = createHotspotInfo("DropSend-$deviceId", "dp_$deviceId", fallbackIp, deviceId, deviceName)
            _hotspotState.value = fallbackInfo
            onStarted?.invoke(fallbackInfo)
            return
        }

        try {
            wm.startLocalOnlyHotspot(
                object : WifiManager.LocalOnlyHotspotCallback() {
                    override fun onStarted(res: WifiManager.LocalOnlyHotspotReservation) {
                        super.onStarted(res)
                        reservation = res
                        Log.d(TAG, "LocalOnlyHotspot started successfully!")

                        var ssid = "DropSend-$deviceId"
                        var pass = "dp_$deviceId"

                        try {
                            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                                val config = res.softApConfiguration
                                ssid = config.ssid ?: ssid
                                pass = config.passphrase ?: pass
                            } else {
                                @Suppress("DEPRECATION")
                                val config = res.wifiConfiguration
                                ssid = config?.SSID ?: ssid
                                pass = config?.preSharedKey ?: pass
                            }
                        } catch (e: Exception) {
                            Log.w(TAG, "Could not extract exact credentials, using assigned identifiers", e)
                        }

                        // Strip outer quotes if any
                        ssid = ssid.removeSurrounding("\"")
                        pass = pass.removeSurrounding("\"")

                        val ip = getHotspotIpAddress() ?: "192.168.43.1"
                        val info = createHotspotInfo(ssid, pass, ip, deviceId, deviceName)
                        _hotspotState.value = info
                        onStarted?.invoke(info)
                    }

                    override fun onStopped() {
                        super.onStopped()
                        Log.d(TAG, "LocalOnlyHotspot stopped")
                        reservation = null
                        _hotspotState.value = LocalHotspotInfo()
                    }

                    override fun onFailed(reason: Int) {
                        super.onFailed(reason)
                        Log.w(TAG, "LocalOnlyHotspot failed with reason: $reason. Using ad-hoc configuration fallback.")
                        val fallbackIp = getHotspotIpAddress() ?: "192.168.43.1"
                        val fallbackInfo = createHotspotInfo(
                            "DropSend-$deviceId",
                            "dp_$deviceId",
                            fallbackIp,
                            deviceId,
                            deviceName,
                            errorMessage = null
                        )
                        _hotspotState.value = fallbackInfo
                        onStarted?.invoke(fallbackInfo)
                    }
                },
                Handler(Looper.getMainLooper())
            )
        } catch (e: Exception) {
            Log.e(TAG, "Exception starting LocalOnlyHotspot", e)
            val fallbackIp = getHotspotIpAddress() ?: "192.168.43.1"
            val fallbackInfo = createHotspotInfo("DropSend-$deviceId", "dp_$deviceId", fallbackIp, deviceId, deviceName)
            _hotspotState.value = fallbackInfo
            onStarted?.invoke(fallbackInfo)
        }
    }

    private fun createHotspotInfo(
        ssid: String,
        pass: String,
        ip: String,
        deviceId: String,
        deviceName: String,
        errorMessage: String? = null
    ): LocalHotspotInfo {
        // Structured DropSend payload
        val dropsendPayload = "dropsend://connect?ssid=${ssid.encodeUri()}&pass=${pass.encodeUri()}&ip=$ip&port=$DEFAULT_PORT&dev=${deviceName.encodeUri()}&id=$deviceId"
        // Standard Wi-Fi QR format (readable by any camera or scanner)
        val wifiPayload = "WIFI:S:$ssid;T:WPA;P:$pass;;"

        return LocalHotspotInfo(
            isActive = true,
            ssid = ssid,
            passphrase = pass,
            ipAddress = ip,
            port = DEFAULT_PORT,
            connectionPayload = dropsendPayload,
            standardWifiQr = wifiPayload,
            errorMessage = errorMessage
        )
    }

    fun stopLocalHotspot() {
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O && reservation != null) {
                (reservation as? WifiManager.LocalOnlyHotspotReservation)?.close()
            }
        } catch (e: Exception) {
            Log.w(TAG, "Error stopping LocalOnlyHotspot", e)
        } finally {
            reservation = null
            _hotspotState.value = LocalHotspotInfo()
        }
    }

    fun getHotspotIpAddress(): String? {
        try {
            val interfaces = NetworkInterface.getNetworkInterfaces() ?: return null
            for (intf in interfaces) {
                val name = intf.name.lowercase()
                // Check hotspot, softap, p2p, or local wlan interfaces
                if (name.contains("ap") || name.contains("wlan") || name.contains("p2p") || name.contains("swlan") || name.contains("hotspot")) {
                    for (addr in intf.inetAddresses) {
                        if (!addr.isLoopbackAddress && addr is Inet4Address) {
                            val host = addr.hostAddress
                            if (host != null && !host.startsWith("127.")) {
                                return host
                            }
                        }
                    }
                }
            }
        } catch (e: Exception) {
            Log.w(TAG, "Error determining hotspot IP", e)
        }
        return null
    }

    private fun String.encodeUri(): String {
        return java.net.URLEncoder.encode(this, "UTF-8")
    }
}
