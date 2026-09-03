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

class HotspotAutoConnector(
    context: Context
) {

    companion object {
        private const val TAG = "HotspotAutoConnector"
        private const val CONNECTION_TIMEOUT_MS = 15_000L
        private const val DEFAULT_PORT = 8888
        private const val DEFAULT_IP = "192.168.43.1"

        private val IP_PORT_REGEX =
            Regex(
                """^([0-9]{1,3}\.[0-9]{1,3}\.[0-9]{1,3}\.[0-9]{1,3})(?::([0-9]{1,5}))?$"""
            )
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
     * Parses:
     *
     * dropsend://connect?ssid=...&pass=...&ip=...&port=...&dev=...&id=...
     * WIFI:S:...;T:WPA;P:...;;
     * 192.168.43.1:8888
     */
    fun parseQrCode(content: String): QrConnectionParams? {
        val trimmed = content.trim()

        if (trimmed.isEmpty()) {
            return null
        }

        if (trimmed.startsWith("dropsend://", ignoreCase = true)) {
            return parseDropSendUri(trimmed)
        }

        if (trimmed.startsWith("WIFI:", ignoreCase = true)) {
            return try {
                parseWifiQr(trimmed)
            } catch (e: Exception) {
                Log.w(TAG, "Failed to parse WIFI QR: ${e.message}")
                null
            }
        }

        return parseDirectIp(trimmed)
    }

    private fun parseDropSendUri(content: String): QrConnectionParams? {
        return try {
            val uri = Uri.parse(content)

            val ssid = uri.getQueryParameter("ssid").orEmpty()
            val passphrase = uri.getQueryParameter("pass").orEmpty()

            val ip = uri.getQueryParameter("ip")
                ?.takeIf(::isValidIpv4)
                ?: DEFAULT_IP

            val port = uri.getQueryParameter("port")
                ?.let { value ->
                    value.toIntOrNull()?.takeIf { it in 1..65_535 }
                }
                ?: DEFAULT_PORT

            val deviceName =
                uri.getQueryParameter("dev") ?: "Nearby Receiver"

            val deviceId =
                uri.getQueryParameter("id")
                    ?: "REV-${ip.takeLast(4)}"

            QrConnectionParams(
                ssid = ssid,
                passphrase = passphrase,
                ipAddress = ip,
                port = port,
                deviceName = deviceName,
                deviceId = deviceId
            )
        } catch (e: Exception) {
            Log.w(TAG, "Failed to parse dropsend URI: ${e.message}")
            null
        }
    }

    private fun parseDirectIp(content: String): QrConnectionParams? {
        val match = IP_PORT_REGEX.matchEntire(content) ?: return null

        val ip = match.groupValues[1]

        if (!isValidIpv4(ip)) {
            return null
        }

        val rawPort = match.groupValues[2]

        val port = if (rawPort.isEmpty()) {
            DEFAULT_PORT
        } else {
            rawPort.toIntOrNull()?.takeIf { it in 1..65_535 }
                ?: return null
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

    private fun isValidIpv4(ip: String): Boolean {
        var start = 0
        var segments = 0

        for (i in 0..ip.length) {
            if (i == ip.length || ip[i] == '.') {
                if (i == start) {
                    return false
                }

                val value = ip.substring(start, i).toIntOrNull()
                    ?: return false

                if (value !in 0..255) {
                    return false
                }

                segments++
                start = i + 1
            }
        }

        return segments == 4
    }

    private fun parseWifiQr(content: String): QrConnectionParams? {
        var ssid = ""
        var passphrase = ""

        var index = 5
        val length = content.length

        while (index < length) {
            val colonIndex = content.indexOf(':', index)

            if (colonIndex <= index) {
                break
            }

            val key = content.substring(index, colonIndex)

            var valueEnd = colonIndex + 1
            var escaped = false

            while (valueEnd < length) {
                val char = content[valueEnd]

                if (escaped) {
                    escaped = false
                    valueEnd++
                    continue
                }

                if (char == '\\') {
                    escaped = true
                    valueEnd++
                    continue
                }

                if (char == ';') {
                    break
                }

                valueEnd++
            }

            val value = unescapeWifiValue(
                content,
                colonIndex + 1,
                valueEnd
            )

            when (key.uppercase()) {
                "S" -> ssid = value
                "P" -> passphrase = value
            }

            index = valueEnd + 1

            while (index < length && content[index] == ';') {
                index++
            }
        }

        if (ssid.isEmpty() && passphrase.isEmpty()) {
            return null
        }

        return QrConnectionParams(
            ssid = ssid,
            passphrase = passphrase,
            ipAddress = DEFAULT_IP,
            port = DEFAULT_PORT,
            deviceName = ssid.ifBlank { "Nearby Hotspot" },
            deviceId = ssid.takeLast(4)
        )
    }

    private fun unescapeWifiValue(
        content: String,
        start: Int,
        end: Int
    ): String {
        var requiresUnescape = false

        for (i in start until end) {
            if (content[i] == '\\') {
                requiresUnescape = true
                break
            }
        }

        if (!requiresUnescape) {
            return content.substring(start, end)
        }

        val result = StringBuilder(end - start)

        var i = start

        while (i < end) {
            val char = content[i]

            if (char == '\\' && i + 1 < end) {
                result.append(content[i + 1])
                i += 2
            } else {
                result.append(char)
                i++
            }
        }

        return result.toString()
    }

    /**
     * Connects to the receiver hotspot on Android 10+.
     *
     * The coroutine is suspended until:
     * - the network becomes available,
     * - Android reports the request unavailable,
     * - the request times out,
     * - the coroutine is cancelled,
     * - another connection attempt supersedes this one.
     */
    suspend fun connectToHotspotNetwork(
        params: QrConnectionParams,
        onStatusUpdate: (String) -> Unit = {}
    ): Boolean {

        /*
         * Direct IP mode does not require a WifiNetworkSpecifier.
         */
        if (
            params.ssid.isBlank() ||
            Build.VERSION.SDK_INT < Build.VERSION_CODES.Q
        ) {
            onStatusUpdate(
                "Connecting directly to ${params.ipAddress}:${params.port}..."
            )
            return true
        }

        val cm = connectivityManager

        if (cm == null) {
            Log.e(TAG, "ConnectivityManager unavailable")
            return false
        }

        val attemptId = nextAttemptId.incrementAndGet()

        /*
         * Supersede the previous connection attempt before creating
         * the new network request.
         */
        val previousContinuation: CancellableContinuation<Boolean>?
        val previousRegistration: NetworkCallbackRegistration?

        synchronized(stateLock) {
            currentAttemptId = attemptId

            previousContinuation =
                activeContinuation?.takeIf { it.isActive }

            previousRegistration = activeRegistration

            activeContinuation = null
            activeRegistration = null
        }

        /*
         * Perform potentially blocking cleanup outside the lock.
         */
        previousContinuation?.resume(false)
        previousRegistration?.cleanup()

        unbindProcessNetwork()

        var lastStatus: String? = null

        fun emitStatus(status: String) {
            if (currentAttemptId != attemptId) {
                return
            }

            if (lastStatus == status) {
                return
            }

            lastStatus = status

            try {
                onStatusUpdate(status)
            } catch (e: Exception) {
                Log.w(
                    TAG,
                    "Status callback failed: ${e.message}"
                )
            }
        }

        emitStatus(
            "Connecting to Receiver's Hotspot: ${params.ssid}..."
        )

        val specifierBuilder =
            WifiNetworkSpecifier.Builder()
                .setSsid(params.ssid)

        if (params.passphrase.isNotBlank()) {
            try {
                specifierBuilder.setWpa2Passphrase(
                    params.passphrase
                )
            } catch (e: IllegalArgumentException) {
                Log.e(
                    TAG,
                    "Invalid WPA2 passphrase: ${e.message}"
                )
                return false
            }
        }

        val request =
            NetworkRequest.Builder()
                .addTransportType(
                    NetworkCapabilities.TRANSPORT_WIFI
                )
                /*
                 * Receiver hotspots frequently have no Internet.
                 */
                .removeCapability(
                    NetworkCapabilities.NET_CAPABILITY_INTERNET
                )
                .setNetworkSpecifier(
                    specifierBuilder.build()
                )
                .build()

        val success = withTimeoutOrNull(
            CONNECTION_TIMEOUT_MS
        ) {
            suspendCancellableCoroutine { continuation ->

                val callback =
                    createNetworkCallback(
                        cm = cm,
                        attemptId = attemptId,
                        continuation = continuation,
                        emitStatus = ::emitStatus
                    )

                val registration =
                    NetworkCallbackRegistration(
                        attemptId = attemptId,
                        callback = callback,
                        cm = cm
                    )

                /*
                 * Publish registration before requesting the network
                 * so release()/a newer attempt can see it.
                 */
                synchronized(stateLock) {
                    if (currentAttemptId != attemptId) {
                        continuation.resume(false)
                        return@suspendCancellableCoroutine
                    }

                    activeRegistration = registration
                    activeContinuation = continuation
                }

                continuation.invokeOnCancellation {
                    cleanupAttempt(
                        attemptId = attemptId,
                        registration = registration,
                        continuation = continuation
                    )
                }

                /*
                 * Register safely even if release() races with this call.
                 */
                if (!registration.request(request)) {
                    cleanupAttempt(
                        attemptId = attemptId,
                        registration = registration,
                        continuation = continuation
                    )

                    if (continuation.isActive) {
                        continuation.resume(false)
                    }
                }
            }
        } == true

        if (!success) {
            cleanupCurrentAttempt(
                attemptId = attemptId
            )

            unbindProcessNetwork()
        }

        return success
    }

    private fun createNetworkCallback(
        cm: ConnectivityManager,
        attemptId: Long,
        continuation: CancellableContinuation<Boolean>,
        emitStatus: (String) -> Unit
    ): ConnectivityManager.NetworkCallback {

        return object : ConnectivityManager.NetworkCallback() {

            override fun onAvailable(network: Network) {
                if (!isCurrentAttempt(attemptId)) {
                    Log.d(
                        TAG,
                        "Ignoring stale onAvailable: $attemptId"
                    )
                    return
                }

                var bound = false

                try {
                    bound = cm.bindProcessToNetwork(network)
                } catch (e: Exception) {
                    Log.w(
                        TAG,
                        "Failed binding process to network: ${e.message}"
                    )
                }

                if (!bound) {
                    Log.w(
                        TAG,
                        "Unable to bind process to network: $network"
                    )

                    resumeAttempt(
                        attemptId = attemptId,
                        continuation = continuation,
                        success = false
                    )

                    return
                }

                isProcessBound = true

                Log.d(
                    TAG,
                    "Receiver hotspot connected: $network"
                )

                emitStatus(
                    "Connected to hotspot! Establishing secure channel..."
                )

                resumeAttempt(
                    attemptId = attemptId,
                    continuation = continuation,
                    success = true
                )
            }

            override fun onUnavailable() {
                if (!isCurrentAttempt(attemptId)) {
                    return
                }

                Log.w(
                    TAG,
                    "Receiver hotspot unavailable: $attemptId"
                )

                resumeAttempt(
                    attemptId = attemptId,
                    continuation = continuation,
                    success = false
                )
            }

            override fun onLost(network: Network) {
                if (!isCurrentAttempt(attemptId)) {
                    return
                }

                Log.d(
                    TAG,
                    "Receiver hotspot lost: $network"
                )

                unbindProcessNetwork()
            }
        }
    }

    private fun resumeAttempt(
        attemptId: Long,
        continuation: CancellableContinuation<Boolean>,
        success: Boolean
    ) {
        var target: CancellableContinuation<Boolean>? = null

        synchronized(stateLock) {
            if (
                currentAttemptId == attemptId &&
                activeContinuation === continuation &&
                continuation.isActive
            ) {
                activeContinuation = null
                target = continuation
            }
        }

        target?.resume(success)
    }

    private fun cleanupAttempt(
        attemptId: Long,
        registration: NetworkCallbackRegistration,
        continuation: CancellableContinuation<Boolean>
    ) {
        synchronized(stateLock) {
            if (
                currentAttemptId == attemptId &&
                activeRegistration === registration
            ) {
                activeRegistration = null
                activeContinuation = null
            }
        }

        registration.cleanup()

        if (isCurrentAttempt(attemptId)) {
            unbindProcessNetwork()
        }
    }

    private fun cleanupCurrentAttempt(
        attemptId: Long
    ) {
        var registration: NetworkCallbackRegistration? = null

        synchronized(stateLock) {
            if (currentAttemptId == attemptId) {
                activeContinuation = null
                registration = activeRegistration
                activeRegistration = null
            }
        }

        registration?.cleanup()
    }

    private fun isCurrentAttempt(
        attemptId: Long
    ): Boolean {
        return currentAttemptId == attemptId
    }

    private fun unbindProcessNetwork() {
        if (!isProcessBound) {
            return
        }

        try {
            connectivityManager?.bindProcessToNetwork(null)
        } catch (e: Exception) {
            Log.w(
                TAG,
                "Failed clearing process network: ${e.message}"
            )
        } finally {
            isProcessBound = false
        }
    }

    /**
     * Releases all active resources.
     *
     * Safe to call repeatedly.
     */
    fun release() {
        val continuation: CancellableContinuation<Boolean>?
        val registration: NetworkCallbackRegistration?

        synchronized(stateLock) {
            currentAttemptId =
                nextAttemptId.incrementAndGet()

            continuation =
                activeContinuation?.takeIf { it.isActive }

            registration = activeRegistration

            activeContinuation = null
            activeRegistration = null
        }

        continuation?.resume(false)
        registration?.cleanup()

        unbindProcessNetwork()
    }

    private class NetworkCallbackRegistration(
        private val attemptId: Long,
        val callback: ConnectivityManager.NetworkCallback,
        private val cm: ConnectivityManager
    ) {

        private val cleanedUp = AtomicBoolean(false)
        private val registered = AtomicBoolean(false)

        /**
         * Requests the network while handling a cancellation/release race.
         */
        fun request(
            request: NetworkRequest
        ): Boolean {

            if (cleanedUp.get()) {
                return false
            }

            return try {
                cm.requestNetwork(
                    request,
                    callback
                )

                registered.set(true)

                /*
                 * release()/cleanup() may have happened while
                 * requestNetwork() was executing.
                 */
                if (cleanedUp.get()) {
                    unregister()
                    false
                } else {
                    true
                }
            } catch (e: Exception) {
                Log.e(
                    TAG,
                    "Network request failed for attempt $attemptId: ${e.message}"
                )

                false
            }
        }

        fun cleanup() {
            if (!cleanedUp.compareAndSet(false, true)) {
                return
            }

            unregister()
        }

        private fun unregister() {
            if (!registered.compareAndSet(true, false)) {
                return
            }

            try {
                cm.unregisterNetworkCallback(callback)
            } catch (e: Exception) {
                Log.w(
                    TAG,
                    "Failed unregistering callback for attempt $attemptId: ${e.message}"
                )
            }
        }
    }
}