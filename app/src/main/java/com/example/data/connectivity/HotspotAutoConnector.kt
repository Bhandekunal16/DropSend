package com.example.data.connectivity

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.net.Uri
import android.net.wifi.WifiNetworkSpecifier
import android.os.Build
import android.util.Log
import androidx.annotation.VisibleForTesting
import kotlinx.coroutines.CancellableContinuation
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeoutOrNull
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
import kotlin.coroutines.resume

data class QrConnectionParams(
    val ssid: String,
    val passphrase: String,
    val ipAddress: String,
    val port: Int = 8888,
    val deviceName: String = "Nearby Device",
    val deviceId: String = ""
)

/**
 * Manages high-speed, low-latency automatic Wi-Fi hotspot connection and process network binding.
 *
 * Employs structured concurrency with callback-driven suspension (no polling loops),
 * deterministic callback lifecycle management, and generation-based stale attempt rejection.
 */
class HotspotAutoConnector(private val context: Context) {

    companion object {
        private const val TAG = "HotspotAutoConnector"
        private const val CONNECTION_TIMEOUT_MS = 15000L

        // Precompiled regex for IP:Port parsing across QR scan frames
        private val IP_PORT_REGEX = Regex("""^([0-9]{1,3}\.[0-9]{1,3}\.[0-9]{1,3}\.[0-9]{1,3})(?::([0-9]{1,5}))?$""")
    }

    private val connectivityManager =
        context.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager

    private val stateLock = Any()
    private val nextAttemptId = AtomicLong(0)

    @Volatile
    private var currentAttemptId = 0L

    @Volatile
    private var activeRegistration: NetworkCallbackRegistration? = null

    @Volatile
    private var activeContinuation: CancellableContinuation<Boolean>? = null

    @Volatile
    private var isProcessBound = false

    @VisibleForTesting
    internal val activeCallback: ConnectivityManager.NetworkCallback?
        get() = activeRegistration?.callback

    @VisibleForTesting
    internal val isProcessNetworkBound: Boolean
        get() = isProcessBound

    @VisibleForTesting
    internal val currentAttempt: Long
        get() = currentAttemptId

    /**
     * Parses connection parameters from QR code strings with zero unnecessary allocations.
     *
     * Supports:
     * 1. dropsend://connect?ssid=...&pass=...&ip=...&port=...&dev=...&id=...
     * 2. WIFI:S:...;T:WPA;P:...;; (standard Wi-Fi QR with escape support)
     * 3. Plain IP:Port (e.g. 192.168.43.1:8888 or 192.168.43.1)
     */
    fun parseQrCode(content: String): QrConnectionParams? {
        val trimmed = content.trim()
        if (trimmed.isEmpty()) return null

        if (trimmed.startsWith("dropsend://", ignoreCase = true)) {
            try {
                val uri = Uri.parse(trimmed)
                val ssid = uri.getQueryParameter("ssid").orEmpty()
                val pass = uri.getQueryParameter("pass").orEmpty()
                val rawIp = uri.getQueryParameter("ip")
                val ip = if (!rawIp.isNullOrBlank() && isValidIpv4(rawIp)) rawIp else "192.168.43.1"
                val rawPort = uri.getQueryParameter("port")
                val port = if (!rawPort.isNullOrBlank()) {
                    rawPort.toIntOrNull()?.takeIf { it in 1..65535 } ?: return null
                } else {
                    8888
                }
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
                Log.w(TAG, "Failed to parse dropsend URI: ${e.message}")
            }
        }

        if (trimmed.startsWith("WIFI:", ignoreCase = true)) {
            try {
                return parseWifiQr(trimmed)
            } catch (e: Exception) {
                Log.w(TAG, "Failed to parse standard WIFI QR: ${e.message}")
            }
        }

        // Direct IP:Port format
        val match = IP_PORT_REGEX.matchEntire(trimmed)
        if (match != null) {
            val ip = match.groupValues[1]
            if (isValidIpv4(ip)) {
                val rawPort = match.groupValues[2]
                val port = if (rawPort.isNotEmpty()) {
                    rawPort.toIntOrNull()?.takeIf { it in 1..65535 } ?: return null
                } else {
                    8888
                }
                return QrConnectionParams(
                    ssid = "",
                    passphrase = "",
                    ipAddress = ip,
                    port = port,
                    deviceName = "Direct IP $ip",
                    deviceId = ip.takeLast(4)
                )
            }
        }

        return null
    }

    private fun isValidIpv4(ip: String): Boolean {
        val parts = ip.split('.')
        if (parts.size != 4) return false
        for (part in parts) {
            val num = part.toIntOrNull() ?: return false
            if (num !in 0..255) return false
        }
        return true
    }

    private fun parseWifiQr(content: String): QrConnectionParams? {
        var ssid = ""
        var pass = ""
        var i = 5 // Skip "WIFI:" prefix
        val len = content.length

        while (i < len) {
            val colon = content.indexOf(':', i)
            if (colon == -1 || colon == i) break
            val key = content.substring(i, colon).uppercase()
            var end = colon + 1
            val sb = StringBuilder()
            while (end < len) {
                val c = content[end]
                if (c == '\\' && end + 1 < len) {
                    sb.append(content[end + 1])
                    end += 2
                } else if (c == ';') {
                    break
                } else {
                    sb.append(c)
                    end++
                }
            }
            val value = sb.toString()
            when (key) {
                "S" -> ssid = value
                "P" -> pass = value
            }
            i = end + 1
            while (i < len && content[i] == ';') {
                i++
            }
        }

        if (ssid.isEmpty() && pass.isEmpty()) return null

        return QrConnectionParams(
            ssid = ssid,
            passphrase = pass,
            ipAddress = "192.168.43.1",
            port = 8888,
            deviceName = ssid.ifBlank { "Nearby Hotspot" },
            deviceId = ssid.takeLast(4)
        )
    }

    /**
     * Connects to receiver's Wi-Fi hotspot network specifier on Android 10+ (API 29+),
     * binds the process network, and reports readiness immediately upon callback arrival.
     *
     * Uses coroutine suspension driven directly by [ConnectivityManager.NetworkCallback.onAvailable]
     * without polling or delays.
     */
    suspend fun connectToHotspotNetwork(
        params: QrConnectionParams,
        onStatusUpdate: (String) -> Unit = {}
    ): Boolean {
        // Direct IP mode or legacy Android: no Wi-Fi association via specifier needed
        if (params.ssid.isBlank() || Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) {
            onStatusUpdate("Connecting directly to ${params.ipAddress}:${params.port}...")
            return true
        }

        val cm = connectivityManager
        if (cm == null) {
            Log.e(TAG, "ConnectivityManager unavailable")
            return false
        }

        val attemptId: Long
        var registration: NetworkCallbackRegistration? = null
        var oldCont: CancellableContinuation<Boolean>? = null
        var oldReg: NetworkCallbackRegistration? = null

        synchronized(stateLock) {
            attemptId = nextAttemptId.incrementAndGet()
            currentAttemptId = attemptId

            // Safely supersede any previous attempt
            oldCont = activeContinuation?.takeIf { it.isActive }
            activeContinuation = null

            oldReg = activeRegistration
            activeRegistration = null
        }

        oldCont?.resume(false)
        oldReg?.cleanup()
        unbindProcessNetwork()

        var lastStatus: String? = null
        fun emitStatus(status: String) {
            if (currentAttemptId != attemptId) return
            if (lastStatus == status) return
            lastStatus = status
            try {
                onStatusUpdate(status)
            } catch (e: Exception) {
                Log.w(TAG, "Status update callback error: ${e.message}")
            }
        }

        emitStatus("Connecting to Receiver's Hotspot: ${params.ssid}...")

        // Build WifiNetworkSpecifier
        val specifierBuilder = WifiNetworkSpecifier.Builder()
            .setSsid(params.ssid)

        if (params.passphrase.isNotBlank()) {
            try {
                specifierBuilder.setWpa2Passphrase(params.passphrase)
            } catch (e: IllegalArgumentException) {
                Log.e(TAG, "Invalid WPA2 passphrase provided: ${e.message}")
                return false
            }
        }

        val specifier = specifierBuilder.build()

        val request = NetworkRequest.Builder()
            .addTransportType(NetworkCapabilities.TRANSPORT_WIFI)
            .removeCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
            .setNetworkSpecifier(specifier)
            .build()

        val isConnected = try {
            withTimeoutOrNull(CONNECTION_TIMEOUT_MS) {
                suspendCancellableCoroutine<Boolean> { continuation ->
                    val callback = object : ConnectivityManager.NetworkCallback() {
                        override fun onAvailable(network: Network) {
                            super.onAvailable(network)
                            if (currentAttemptId != attemptId) {
                                Log.d(TAG, "Ignoring onAvailable for stale attempt $attemptId (current: $currentAttemptId)")
                                return
                            }

                            Log.d(TAG, "Joined receiver hotspot network successfully: $network")
                            try {
                                val bound = cm.bindProcessToNetwork(network)
                                if (bound) {
                                    isProcessBound = true
                                } else {
                                    Log.w(TAG, "bindProcessToNetwork returned false for $network")
                                }
                            } catch (e: Exception) {
                                Log.w(TAG, "Error binding process to network: ${e.message}")
                            }

                            var resumeTarget: CancellableContinuation<Boolean>? = null
                            synchronized(stateLock) {
                                if (currentAttemptId == attemptId && continuation.isActive) {
                                    activeContinuation = null
                                    resumeTarget = continuation
                                }
                            }

                            if (resumeTarget != null) {
                                emitStatus("Connected to hotspot! Establishing secure channel...")
                                resumeTarget?.resume(true)
                            }
                        }

                        override fun onUnavailable() {
                            super.onUnavailable()
                            if (currentAttemptId != attemptId) {
                                return
                            }
                            Log.w(TAG, "Hotspot network unavailable for attempt $attemptId")

                            var resumeTarget: CancellableContinuation<Boolean>? = null
                            synchronized(stateLock) {
                                if (currentAttemptId == attemptId && continuation.isActive) {
                                    activeContinuation = null
                                    resumeTarget = continuation
                                }
                            }
                            resumeTarget?.resume(false)
                        }

                        override fun onLost(network: Network) {
                            super.onLost(network)
                            if (currentAttemptId != attemptId) return
                            Log.d(TAG, "Hotspot network lost: $network")
                            unbindProcessNetwork()
                        }
                    }

                    val reg = NetworkCallbackRegistration(attemptId, callback, cm)
                    registration = reg

                    synchronized(stateLock) {
                        if (currentAttemptId != attemptId) {
                            continuation.resume(false)
                            return@suspendCancellableCoroutine
                        }
                        activeRegistration = reg
                        activeContinuation = continuation
                    }

                    continuation.invokeOnCancellation {
                        var toCleanup: NetworkCallbackRegistration? = null
                        synchronized(stateLock) {
                            if (currentAttemptId == attemptId) {
                                activeContinuation = null
                                toCleanup = activeRegistration
                                activeRegistration = null
                            }
                        }
                        toCleanup?.cleanup()
                        unbindProcessNetwork()
                    }

                    try {
                        cm.requestNetwork(request, callback)
                    } catch (e: Exception) {
                        Log.e(TAG, "Failed requesting hotspot network: ${e.message}")
                        var resumeTarget: CancellableContinuation<Boolean>? = null
                        synchronized(stateLock) {
                            if (currentAttemptId == attemptId) {
                                activeContinuation = null
                                activeRegistration = null
                                resumeTarget = if (continuation.isActive) continuation else null
                            }
                        }
                        reg.cleanup()
                        resumeTarget?.resume(false)
                    }
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Exception during hotspot connection attempt $attemptId: ${e.message}")
            null
        }

        val success = isConnected == true

        if (!success) {
            var regToCleanup: NetworkCallbackRegistration? = null
            synchronized(stateLock) {
                if (currentAttemptId == attemptId) {
                    activeContinuation = null
                    regToCleanup = activeRegistration
                    activeRegistration = null
                }
            }
            regToCleanup?.cleanup()
            registration?.cleanup()
            unbindProcessNetwork()
        }

        return success
    }

    private fun unbindProcessNetwork() {
        if (isProcessBound) {
            try {
                connectivityManager?.bindProcessToNetwork(null)
            } catch (e: Exception) {
                Log.w(TAG, "Error clearing process network binding: ${e.message}")
            } finally {
                isProcessBound = false
            }
        }
    }

    /**
     * Releases active network callbacks, unbinds process network routing, and cancels pending attempts.
     * Safe to call multiple times (idempotent).
     */
    fun release() {
        var contToResume: CancellableContinuation<Boolean>? = null
        var regToCleanup: NetworkCallbackRegistration? = null

        synchronized(stateLock) {
            currentAttemptId = nextAttemptId.incrementAndGet()
            contToResume = activeContinuation?.takeIf { it.isActive }
            activeContinuation = null
            regToCleanup = activeRegistration
            activeRegistration = null
        }

        contToResume?.resume(false)
        regToCleanup?.cleanup()
        unbindProcessNetwork()
    }

    private class NetworkCallbackRegistration(
        val attemptId: Long,
        val callback: ConnectivityManager.NetworkCallback,
        private val cm: ConnectivityManager?
    ) {
        private val isCleanedUp = AtomicBoolean(false)

        fun cleanup() {
            if (isCleanedUp.compareAndSet(false, true)) {
                try {
                    cm?.unregisterNetworkCallback(callback)
                } catch (e: Exception) {
                    Log.w(TAG, "Error unregistering network callback for attempt $attemptId: ${e.message}")
                }
            }
        }
    }
}
