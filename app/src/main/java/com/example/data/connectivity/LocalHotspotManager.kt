package com.example.data.connectivity

import android.annotation.SuppressLint
import android.content.Context
import android.net.wifi.WifiManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.net.Inet4Address
import java.net.NetworkInterface
import java.net.URLEncoder
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong

data class LocalHotspotInfo(
    val isActive: Boolean = false,
    val ssid: String = "",
    val passphrase: String = "",
    val ipAddress: String = "",
    val port: Int = 8888,
    val connectionPayload: String = "",
    val standardWifiQr: String = "",
    val errorMessage: String? = null,
)

class LocalHotspotManager(
    context: Context,
) {
    companion object {
        private const val TAG = "LocalHotspotManager"

        const val DEFAULT_PORT = 8888

        private const val DEFAULT_IP = "192.168.43.1"

        // Small number of retries because the interface can appear
        // shortly after LocalOnlyHotspotCallback.onStarted().
        private const val IP_LOOKUP_ATTEMPTS = 4
        private const val IP_LOOKUP_DELAY_MS = 75L
    }

    private val appContext = context.applicationContext

    private val wifiManager =
        appContext.getSystemService(Context.WIFI_SERVICE) as? WifiManager

    private val mainHandler = Handler(Looper.getMainLooper())

    private val scope =
        CoroutineScope(
            SupervisorJob() + Dispatchers.IO,
        )

    private val _hotspotState = MutableStateFlow(LocalHotspotInfo())
    val hotspotState: StateFlow<LocalHotspotInfo> =
        _hotspotState.asStateFlow()

    /**
     * Typed reservation instead of Any?.
     */
    @Volatile
    private var reservation: WifiManager.LocalOnlyHotspotReservation? = null

    /**
     * Prevents multiple simultaneous start requests.
     */
    private val starting = AtomicBoolean(false)

    /**
     * Invalidates old asynchronous work when stop/start occurs.
     */
    private val generation = AtomicLong(0L)

    @SuppressLint("MissingPermission")
    fun startLocalHotspot(
        deviceId: String,
        deviceName: String,
        onStarted: ((LocalHotspotInfo) -> Unit)? = null,
    ) {
        // Already running.
        reservation?.let {
            val state = _hotspotState.value

            if (state.isActive) {
                mainHandler.post {
                    onStarted?.invoke(state)
                }
                return
            }
        }

        // Already starting.
        if (!starting.compareAndSet(false, true)) {
            return
        }

        val currentGeneration = generation.incrementAndGet()

        // Fast fallback for unsupported devices.
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) {
            publishFallback(
                deviceId = deviceId,
                deviceName = deviceName,
                generation = currentGeneration,
                onStarted = onStarted,
            )
            return
        }

        val wm = wifiManager

        if (wm == null) {
            publishFallback(
                deviceId = deviceId,
                deviceName = deviceName,
                generation = currentGeneration,
                onStarted = onStarted,
            )
            return
        }

        try {
            wm.startLocalOnlyHotspot(
                object : WifiManager.LocalOnlyHotspotCallback() {
                    override fun onStarted(res: WifiManager.LocalOnlyHotspotReservation) {
                        super.onStarted(res)

                        // If stopLocalHotspot() was called while Android
                        // was starting the hotspot, immediately close
                        // this stale reservation.
                        if (generation.get() != currentGeneration) {
                            res.close()
                            starting.set(false)
                            return
                        }

                        reservation = res
                        starting.set(false)

                        val credentials =
                            extractCredentials(
                                reservation = res,
                                deviceId = deviceId,
                            )

                        // IMPORTANT:
                        // IP lookup happens off the main thread.
                        scope.launch {
                            val ip = findHotspotIpAddressWithRetry()

                            if (generation.get() != currentGeneration) {
                                return@launch
                            }

                            val info =
                                createHotspotInfo(
                                    ssid = credentials.first,
                                    pass = credentials.second,
                                    ip = ip ?: DEFAULT_IP,
                                    deviceId = deviceId,
                                    deviceName = deviceName,
                                )

                            publish(
                                info = info,
                                generation = currentGeneration,
                                onStarted = onStarted,
                            )
                        }
                    }

                    override fun onStopped() {
                        super.onStopped()

                        if (generation.get() != currentGeneration) {
                            return
                        }

                        reservation = null
                        starting.set(false)

                        _hotspotState.value = LocalHotspotInfo()
                    }

                    override fun onFailed(reason: Int) {
                        super.onFailed(reason)

                        if (generation.get() != currentGeneration) {
                            return
                        }

                        reservation = null
                        starting.set(false)

                        publishFallback(
                            deviceId = deviceId,
                            deviceName = deviceName,
                            generation = currentGeneration,
                            onStarted = onStarted,
                        )
                    }
                },
                mainHandler,
            )
        } catch (e: Exception) {
            if (e is CancellationException) {
                throw e
            }

            starting.set(false)

            publishFallback(
                deviceId = deviceId,
                deviceName = deviceName,
                generation = currentGeneration,
                onStarted = onStarted,
            )
        }
    }

    private fun extractCredentials(
        reservation: WifiManager.LocalOnlyHotspotReservation,
        deviceId: String,
    ): Pair<String, String> {
        var ssid = "DropSend-$deviceId"
        var passphrase = "dp_$deviceId"

        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                val config = reservation.softApConfiguration

                config.ssid
                    ?.takeIf { it.isNotEmpty() }
                    ?.let { ssid = it }

                config.passphrase
                    ?.takeIf { it.isNotEmpty() }
                    ?.let { passphrase = it }
            } else {
                @Suppress("DEPRECATION")
                val config = reservation.wifiConfiguration

                config
                    ?.SSID
                    ?.takeIf { it.isNotEmpty() }
                    ?.let { ssid = it }

                config
                    ?.preSharedKey
                    ?.takeIf { it.isNotEmpty() }
                    ?.let { passphrase = it }
            }
        } catch (_: Exception) {
            // Keep generated fallback credentials.
        }

        return ssid.removeSurrounding("\"") to
            passphrase.removeSurrounding("\"")
    }

    /**
     * NetworkInterface scanning is potentially expensive.
     * This function must only run on Dispatchers.IO.
     */
    private suspend fun findHotspotIpAddressWithRetry(): String? {
        repeat(IP_LOOKUP_ATTEMPTS) { attempt ->

            val ip =
                withContext(Dispatchers.IO) {
                    findHotspotIpAddress()
                }

            if (ip != null) {
                return ip
            }

            if (attempt < IP_LOOKUP_ATTEMPTS - 1) {
                delay(IP_LOOKUP_DELAY_MS)
            }
        }

        return null
    }

    /**
     * Optimized interface scan.
     *
     * We only inspect interfaces that are likely to belong to
     * Wi-Fi / SoftAP / P2P.
     */
    private fun findHotspotIpAddress(): String? {
        try {
            val interfaces =
                NetworkInterface.getNetworkInterfaces()
                    ?: return null

            while (interfaces.hasMoreElements()) {
                val networkInterface = interfaces.nextElement()

                if (!networkInterface.isUp ||
                    networkInterface.isLoopback
                ) {
                    continue
                }

                val name = networkInterface.name.lowercase()

                if (!isRelevantInterface(name)) {
                    continue
                }

                val addresses = networkInterface.inetAddresses

                while (addresses.hasMoreElements()) {
                    val address = addresses.nextElement()

                    if (address !is Inet4Address ||
                        address.isLoopbackAddress ||
                        address.isLinkLocalAddress
                    ) {
                        continue
                    }

                    val host = address.hostAddress ?: continue

                    if (host.startsWith("127.")) {
                        continue
                    }

                    return host
                }
            }
        } catch (_: Exception) {
            // IP discovery is best-effort.
        }

        return null
    }

    private fun isRelevantInterface(name: String): Boolean =
        name.startsWith("wlan") ||
            name.startsWith("ap") ||
            name.startsWith("swlan") ||
            name.startsWith("p2p") ||
            name.contains("hotspot")

    private fun publishFallback(
        deviceId: String,
        deviceName: String,
        generation: Long,
        onStarted: ((LocalHotspotInfo) -> Unit)?,
    ) {
        if (this.generation.get() != generation) {
            return
        }

        val info =
            createHotspotInfo(
                ssid = "DropSend-$deviceId",
                pass = "dp_$deviceId",
                ip = DEFAULT_IP,
                deviceId = deviceId,
                deviceName = deviceName,
            )

        starting.set(false)

        publish(
            info = info,
            generation = generation,
            onStarted = onStarted,
        )
    }

    private fun publish(
        info: LocalHotspotInfo,
        generation: Long,
        onStarted: ((LocalHotspotInfo) -> Unit)?,
    ) {
        if (this.generation.get() != generation) {
            return
        }

        _hotspotState.value = info

        // Keep UI/client callback on main thread.
        mainHandler.post {
            if (this.generation.get() == generation) {
                onStarted?.invoke(info)
            }
        }
    }

    private fun createHotspotInfo(
        ssid: String,
        pass: String,
        ip: String,
        deviceId: String,
        deviceName: String,
        errorMessage: String? = null,
    ): LocalHotspotInfo {
        val encodedSsid = ssid.encodeUri()
        val encodedPass = pass.encodeUri()
        val encodedDeviceName = deviceName.encodeUri()
        val encodedDeviceId = deviceId.encodeUri()

        val dropsendPayload =
            "dropsend://connect" +
                "?ssid=$encodedSsid" +
                "&pass=$encodedPass" +
                "&ip=${ip.encodeUri()}" +
                "&port=$DEFAULT_PORT" +
                "&dev=$encodedDeviceName" +
                "&id=$encodedDeviceId"

        val wifiPayload =
            "WIFI:S:${ssid.escapeWifiQr()};" +
                "T:WPA;" +
                "P:${pass.escapeWifiQr()};;"

        return LocalHotspotInfo(
            isActive = true,
            ssid = ssid,
            passphrase = pass,
            ipAddress = ip,
            port = DEFAULT_PORT,
            connectionPayload = dropsendPayload,
            standardWifiQr = wifiPayload,
            errorMessage = errorMessage,
        )
    }

    fun stopLocalHotspot() {
        // Invalidate all asynchronous work immediately.
        generation.incrementAndGet()

        starting.set(false)

        val currentReservation = reservation
        reservation = null

        try {
            currentReservation?.close()
        } catch (_: Exception) {
            // Best effort.
        }

        _hotspotState.value = LocalHotspotInfo()
    }

    /**
     * Call this when the manager is permanently no longer needed.
     */
    fun release() {
        stopLocalHotspot()
        scope.cancel()
    }

    private fun String.encodeUri(): String = URLEncoder.encode(this, Charsets.UTF_8.name())

    /**
     * Wi-Fi QR specification requires escaping
     * \ ; , and :
     */
    private fun String.escapeWifiQr(): String =
        replace("\\", "\\\\")
            .replace(";", "\\;")
            .replace(",", "\\,")
            .replace(":", "\\:")
}
