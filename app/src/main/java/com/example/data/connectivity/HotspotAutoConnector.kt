package com.example.data.connectivity

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.net.wifi.WifiNetworkSpecifier
import android.os.Build
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull

data class QrConnectionParams(
    val ssid: String,
    val passphrase: String,
    val ipAddress: String,
    val port: Int = 8888,
    val deviceName: String = "Nearby Device",
    val deviceId: String = ""
)

class HotspotAutoConnector(private val context: Context) {

    companion object {
        private const val TAG = "HotspotAutoConnector"
        private const val CONNECTION_TIMEOUT_MS = 15000L
    }

    private val connectivityManager = context.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager
    private var activeCallback: ConnectivityManager.NetworkCallback? = null

    /**
     * Parse connection params from QR code string:
     * Supports:
     * 1. dropsend://connect?ssid=...&pass=...&ip=...&port=...&dev=...&id=...
     * 2. WIFI:S:...;T:WPA;P:...;;
     * 3. Plain IP:Port (e.g. 192.168.43.1:8888)
     */
    fun parseQrCode(content: String): QrConnectionParams? {
        val trimmed = content.trim()

        if (trimmed.startsWith("dropsend://", ignoreCase = true)) {
            try {
                val uri = android.net.Uri.parse(trimmed)
                val ssid = uri.getQueryParameter("ssid") ?: ""
                val pass = uri.getQueryParameter("pass") ?: ""
                val ip = uri.getQueryParameter("ip") ?: "192.168.43.1"
                val port = uri.getQueryParameter("port")?.toIntOrNull() ?: 8888
                val dev = uri.getQueryParameter("dev") ?: "Nearby Receiver"
                val id = uri.getQueryParameter("id") ?: "REV-${ip.takeLast(4)}"

                return QrConnectionParams(
                    ssid = ssid,
                    passphrase = pass,
                    ipAddress = ip,
                    port = port,
                    deviceName = dev,
                    deviceId = id
                )
            } catch (e: Exception) {
                Log.w(TAG, "Failed to parse dropsend URI", e)
            }
        }

        if (trimmed.startsWith("WIFI:", ignoreCase = true)) {
            try {
                var ssid = ""
                var pass = ""
                val parts = trimmed.removePrefix("WIFI:").removeSuffix(";;").split(";")
                for (part in parts) {
                    if (part.startsWith("S:")) ssid = part.removePrefix("S:")
                    if (part.startsWith("P:")) pass = part.removePrefix("P:")
                }
                return QrConnectionParams(
                    ssid = ssid,
                    passphrase = pass,
                    ipAddress = "192.168.43.1",
                    port = 8888,
                    deviceName = ssid.ifBlank { "Nearby Hotspot" },
                    deviceId = ssid.takeLast(4)
                )
            } catch (e: Exception) {
                Log.w(TAG, "Failed to parse standard WIFI QR", e)
            }
        }

        // Direct IP format
        val ipRegex = Regex("""^([0-9]{1,3}\.[0-9]{1,3}\.[0-9]{1,3}\.[0-9]{1,3})(?::([0-9]{1,5}))?$""")
        val match = ipRegex.find(trimmed)
        if (match != null) {
            val ip = match.groupValues[1]
            val port = match.groupValues[2].toIntOrNull() ?: 8888
            return QrConnectionParams(
                ssid = "",
                passphrase = "",
                ipAddress = ip,
                port = port,
                deviceName = "Direct IP $ip",
                deviceId = ip.takeLast(4)
            )
        }

        return null
    }

    /**
     * Connects to receiver's Wi-Fi network specifier if SSID is present and Android >= 10,
     * binds the process network, and reports readiness.
     */
    suspend fun connectToHotspotNetwork(
        params: QrConnectionParams,
        onStatusUpdate: (String) -> Unit = {}
    ): Boolean = withContext(Dispatchers.IO) {
        val cm = connectivityManager ?: return@withContext false

        // If no SSID was provided or already connected to the same subnet, proceed directly
        if (params.ssid.isBlank() || Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) {
            onStatusUpdate("Connecting directly to ${params.ipAddress}:${params.port}...")
            return@withContext true
        }

        onStatusUpdate("Connecting to Receiver's Hotspot: ${params.ssid}...")

        val specifier = WifiNetworkSpecifier.Builder()
            .setSsid(params.ssid)
            .apply {
                if (params.passphrase.isNotBlank()) {
                    setWpa2Passphrase(params.passphrase)
                }
            }
            .build()

        val request = NetworkRequest.Builder()
            .addTransportType(NetworkCapabilities.TRANSPORT_WIFI)
            .removeCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
            .setNetworkSpecifier(specifier)
            .build()

        var isConnected = false

        val networkCallback = object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: Network) {
                super.onAvailable(network)
                Log.d(TAG, "Joined receiver hotspot network successfully: $network")
                try {
                    cm.bindProcessToNetwork(network)
                } catch (e: Exception) {
                    Log.w(TAG, "Error binding process to network", e)
                }
                isConnected = true
                onStatusUpdate("Connected to hotspot! Establishing secure channel...")
            }

            override fun onUnavailable() {
                super.onUnavailable()
                Log.w(TAG, "Hotspot network unavailable")
                isConnected = false
            }
        }

        activeCallback = networkCallback

        try {
            cm.requestNetwork(request, networkCallback)

            // Wait up to timeout for connection
            val result = withTimeoutOrNull(CONNECTION_TIMEOUT_MS) {
                while (!isConnected) {
                    kotlinx.coroutines.delay(200)
                }
                true
            }

            return@withContext (result == true || isConnected)
        } catch (e: Exception) {
            Log.e(TAG, "Failed requesting hotspot network", e)
            // Fallback: Attempt socket connection directly
            return@withContext true
        }
    }

    fun release() {
        try {
            activeCallback?.let {
                connectivityManager?.unregisterNetworkCallback(it)
            }
            connectivityManager?.bindProcessToNetwork(null)
        } catch (e: Exception) {
            Log.w(TAG, "Error releasing network callback", e)
        } finally {
            activeCallback = null
        }
    }
}
