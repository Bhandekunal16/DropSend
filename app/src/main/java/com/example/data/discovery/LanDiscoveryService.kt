package com.example.data.discovery

import android.content.Context
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import android.net.wifi.WifiManager
import android.util.Log
import com.example.domain.model.DiscoveredDevice
import com.example.domain.model.TransportType
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import org.json.JSONObject
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.Inet4Address
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.NetworkInterface
import java.net.Socket

class LanDiscoveryService(private val context: Context) {

    companion object {
        private const val TAG = "LanDiscoveryService"
        private const val SERVICE_TYPE = "_dropsend._tcp."
        const val UDP_BROADCAST_PORT = 8889
        const val DEFAULT_TCP_PORT = 8888
    }

    private val nsdManager = context.getSystemService(Context.NSD_SERVICE) as? NsdManager
    private val wifiManager = context.applicationContext.getSystemService(Context.WIFI_SERVICE) as? WifiManager
    private var multicastLock: WifiManager.MulticastLock? = null

    private val scope = CoroutineScope(Dispatchers.IO + Job())

    private val _discoveredDevices = MutableStateFlow<Map<String, DiscoveredDevice>>(emptyMap())
    val discoveredDevices: StateFlow<Map<String, DiscoveredDevice>> = _discoveredDevices.asStateFlow()

    private var registrationListener: NsdManager.RegistrationListener? = null
    private var discoveryListener: NsdManager.DiscoveryListener? = null

    private var udpBroadcastJob: Job? = null
    private var udpListenJob: Job? = null
    private var subnetProbeJob: Job? = null
    private var udpSocket: DatagramSocket? = null

    private fun acquireMulticastLock() {
        try {
            if (multicastLock == null) {
                multicastLock = wifiManager?.createMulticastLock("DropSendMulticastLock")?.apply {
                    setReferenceCounted(true)
                }
            }
            multicastLock?.let {
                if (!it.isHeld) {
                    it.acquire()
                    Log.d(TAG, "MulticastLock acquired")
                }
            }
        } catch (e: Exception) {
            Log.w(TAG, "Error acquiring MulticastLock", e)
        }
    }

    private fun releaseMulticastLock() {
        try {
            multicastLock?.let {
                if (it.isHeld) {
                    it.release()
                    Log.d(TAG, "MulticastLock released")
                }
            }
        } catch (e: Exception) {
            Log.w(TAG, "Error releasing MulticastLock", e)
        }
    }

    /**
     * Get all active local IPv4 addresses (Wi-Fi, Hotspot, etc.)
     */
    fun getLocalIpAddresses(): List<String> {
        val ips = mutableListOf<String>()
        try {
            val interfaces = NetworkInterface.getNetworkInterfaces() ?: return emptyList()
            for (intf in interfaces) {
                if (!intf.isUp || intf.isLoopback) continue
                for (addr in intf.inetAddresses) {
                    if (addr is Inet4Address && !addr.isLoopbackAddress) {
                        val host = addr.hostAddress ?: continue
                        if (!host.startsWith("127.")) {
                            ips.add(host)
                        }
                    }
                }
            }
        } catch (e: Exception) {
            Log.w(TAG, "Error getting local IP addresses", e)
        }
        return ips
    }

    /**
     * Get all broadcast targets (interface-specific broadcast addresses + 255.255.255.255)
     */
    private fun getBroadcastAddresses(): List<InetAddress> {
        val broadcastList = mutableSetOf<InetAddress>()
        try {
            val interfaces = NetworkInterface.getNetworkInterfaces() ?: return listOf(InetAddress.getByName("255.255.255.255"))
            for (networkInterface in interfaces) {
                if (networkInterface.isLoopback || !networkInterface.isUp) continue
                for (interfaceAddress in networkInterface.interfaceAddresses) {
                    val broadcast = interfaceAddress.broadcast
                    if (broadcast != null) {
                        broadcastList.add(broadcast)
                    }
                }
            }
        } catch (e: Exception) {
            Log.w(TAG, "Error getting broadcast addresses", e)
        }
        try {
            broadcastList.add(InetAddress.getByName("255.255.255.255"))
        } catch (_: Exception) {}
        return broadcastList.toList()
    }

    fun startAdvertising(localDeviceId: String, localDeviceName: String, tcpPort: Int) {
        stopAdvertising()
        acquireMulticastLock()
        Log.d(TAG, "Starting LAN advertising for $localDeviceId on port $tcpPort")

        // 1. mDNS NSD Registration
        try {
            val serviceInfo = NsdServiceInfo().apply {
                serviceName = "$localDeviceId#$localDeviceName"
                serviceType = SERVICE_TYPE
                port = tcpPort
            }

            registrationListener = object : NsdManager.RegistrationListener {
                override fun onServiceRegistered(serviceInfo: NsdServiceInfo?) {
                    Log.d(TAG, "mDNS Service registered: ${serviceInfo?.serviceName}")
                }
                override fun onRegistrationFailed(serviceInfo: NsdServiceInfo?, errorCode: Int) {
                    Log.w(TAG, "mDNS Registration failed: $errorCode")
                }
                override fun onServiceUnregistered(serviceInfo: NsdServiceInfo?) {
                    Log.d(TAG, "mDNS Service unregistered")
                }
                override fun onUnregistrationFailed(serviceInfo: NsdServiceInfo?, errorCode: Int) {
                    Log.w(TAG, "mDNS Unregistration failed: $errorCode")
                }
            }

            nsdManager?.registerService(serviceInfo, NsdManager.PROTOCOL_DNS_SD, registrationListener)
        } catch (e: Exception) {
            Log.w(TAG, "Error registering NSD", e)
        }

        // 2. Periodic UDP Broadcast Beacon + reply to discovery probes
        udpBroadcastJob = scope.launch {
            var broadcastSocket: DatagramSocket? = null
            try {
                broadcastSocket = DatagramSocket(null).apply {
                    reuseAddress = true
                    broadcast = true
                    bind(InetSocketAddress(UDP_BROADCAST_PORT))
                }

                val payloadJson = JSONObject().apply {
                    put("type", "DROPSEND_BEACON")
                    put("id", localDeviceId)
                    put("name", localDeviceName)
                    put("port", tcpPort)
                }.toString().toByteArray(Charsets.UTF_8)

                // Periodic beacon
                val beaconJob = launch {
                    while (isActive) {
                        val targets = getBroadcastAddresses()
                        for (target in targets) {
                            try {
                                val packet = DatagramPacket(payloadJson, payloadJson.size, target, UDP_BROADCAST_PORT)
                                broadcastSocket.send(packet)
                            } catch (e: Exception) {
                                // ignore transient socket errors
                            }
                        }
                        delay(1200)
                    }
                }

                // Listen for active probe queries from senders
                val listenBuffer = ByteArray(2048)
                while (isActive) {
                    try {
                        val inPacket = DatagramPacket(listenBuffer, listenBuffer.size)
                        broadcastSocket.receive(inPacket)
                        val text = String(inPacket.data, 0, inPacket.length, Charsets.UTF_8)
                        val json = JSONObject(text)
                        if (json.optString("type") == "DROPSEND_PROBE") {
                            // Senders asked who is nearby -> reply immediately to sender IP
                            val replyPacket = DatagramPacket(payloadJson, payloadJson.size, inPacket.address, UDP_BROADCAST_PORT)
                            broadcastSocket.send(replyPacket)
                        }
                    } catch (_: Exception) {
                        // ignore malformed packets or timeouts
                    }
                }
                beaconJob.cancel()
            } catch (e: Exception) {
                Log.w(TAG, "UDP broadcast advertiser socket error: ${e.message}")
            } finally {
                try {
                    broadcastSocket?.close()
                } catch (_: Exception) {}
            }
        }
    }

    fun stopAdvertising() {
        try {
            if (registrationListener != null) {
                nsdManager?.unregisterService(registrationListener)
            }
        } catch (e: Exception) {
            Log.w(TAG, "Error unregistering NSD", e)
        } finally {
            registrationListener = null
        }

        udpBroadcastJob?.cancel()
        udpBroadcastJob = null
        releaseMulticastLock()
    }

    fun startDiscovery(localDeviceId: String) {
        stopDiscovery()
        acquireMulticastLock()
        Log.d(TAG, "Starting LAN discovery...")

        // 1. mDNS Discovery
        try {
            discoveryListener = object : NsdManager.DiscoveryListener {
                override fun onDiscoveryStarted(regType: String) {
                    Log.d(TAG, "mDNS Service discovery started")
                }

                override fun onServiceFound(service: NsdServiceInfo) {
                    Log.d(TAG, "mDNS Service found: ${service.serviceName}")
                    if (service.serviceType == SERVICE_TYPE || service.serviceType == "_dropsend._tcp") {
                        try {
                            nsdManager?.resolveService(service, object : NsdManager.ResolveListener {
                                override fun onResolveFailed(serviceInfo: NsdServiceInfo?, errorCode: Int) {
                                    Log.w(TAG, "mDNS Resolve failed: $errorCode")
                                }

                                override fun onServiceResolved(serviceInfo: NsdServiceInfo) {
                                    handleResolvedService(serviceInfo, localDeviceId)
                                }
                            })
                        } catch (e: Exception) {
                            Log.w(TAG, "Error resolving NSD service", e)
                        }
                    }
                }

                override fun onServiceLost(service: NsdServiceInfo) {
                    Log.d(TAG, "mDNS service lost: ${service.serviceName}")
                }

                override fun onDiscoveryStopped(serviceType: String) {
                    Log.d(TAG, "mDNS Discovery stopped: $serviceType")
                }

                override fun onStartDiscoveryFailed(serviceType: String, errorCode: Int) {
                    Log.w(TAG, "mDNS Discovery start failed: $errorCode")
                }

                override fun onStopDiscoveryFailed(serviceType: String, errorCode: Int) {
                    Log.w(TAG, "mDNS Discovery stop failed: $errorCode")
                }
            }

            nsdManager?.discoverServices(SERVICE_TYPE, NsdManager.PROTOCOL_DNS_SD, discoveryListener)
        } catch (e: Exception) {
            Log.w(TAG, "Error starting NSD discovery", e)
        }

        // 2. UDP Broadcast Listener + Probe sender
        udpListenJob = scope.launch {
            try {
                val socket = DatagramSocket(null).apply {
                    reuseAddress = true
                    broadcast = true
                    bind(InetSocketAddress(UDP_BROADCAST_PORT))
                }
                udpSocket = socket
                val buffer = ByteArray(2048)

                // Send active probe on start
                launch {
                    val probePayload = JSONObject().apply {
                        put("type", "DROPSEND_PROBE")
                        put("id", localDeviceId)
                    }.toString().toByteArray(Charsets.UTF_8)

                    while (isActive) {
                        for (target in getBroadcastAddresses()) {
                            try {
                                val probePacket = DatagramPacket(probePayload, probePayload.size, target, UDP_BROADCAST_PORT)
                                socket.send(probePacket)
                            } catch (_: Exception) {}
                        }
                        delay(2500)
                    }
                }

                while (isActive) {
                    try {
                        val packet = DatagramPacket(buffer, buffer.size)
                        socket.receive(packet)
                        val text = String(packet.data, 0, packet.length, Charsets.UTF_8)
                        val json = JSONObject(text)
                        if (json.optString("type") == "DROPSEND_BEACON") {
                            val id = json.getString("id")
                            if (id != localDeviceId) {
                                val name = json.optString("name", "Nearby Device")
                                val port = json.optInt("port", DEFAULT_TCP_PORT)
                                val host = packet.address.hostAddress ?: ""

                                if (host.isNotBlank()) {
                                    val device = DiscoveredDevice(
                                        id = id,
                                        name = name,
                                        transportType = TransportType.LOCAL_WIFI,
                                        ipAddress = host,
                                        port = port,
                                        isReadyToReceive = true,
                                        lastSeenTimestamp = System.currentTimeMillis()
                                    )
                                    val current = _discoveredDevices.value.toMutableMap()
                                    val probedKey = current.keys.find { it.startsWith("DROP-") && current[it]?.ipAddress == host && it != id }
                                    if (probedKey != null) {
                                        current.remove(probedKey)
                                    }
                                    current[id] = device
                                    _discoveredDevices.value = current
                                }
                            }
                        }
                    } catch (_: Exception) {
                        // ignore
                    }
                }
            } catch (e: Exception) {
                if (isActive) {
                    Log.w(TAG, "UDP listener closed: ${e.message}")
                }
            }
        }

        // 3. Subnet /24 Active Sweep & Hotspot Gateway Probe (Fast port check to bypass router multicast blocks)
        subnetProbeJob = scope.launch(Dispatchers.IO) {
            val ips = getLocalIpAddresses()

            // 3a. Immediately probe Gateway / Hotspot Host IP from DHCP
            try {
                val dhcp = wifiManager?.dhcpInfo
                val gatewayInt = dhcp?.gateway ?: 0
                val gatewayIp = if (gatewayInt != 0) {
                    val b1 = gatewayInt and 0xff
                    val b2 = (gatewayInt shr 8) and 0xff
                    val b3 = (gatewayInt shr 16) and 0xff
                    val b4 = (gatewayInt shr 24) and 0xff
                    "$b1.$b2.$b3.$b4"
                } else null

                val priorityIps = listOfNotNull(gatewayIp, "192.168.43.1", "192.168.49.1", "192.168.50.1").distinct()
                for (hostIp in priorityIps) {
                    if (ips.contains(hostIp)) continue
                    launch {
                        try {
                            val testSocket = Socket()
                            testSocket.connect(InetSocketAddress(hostIp, DEFAULT_TCP_PORT), 400)
                            testSocket.close()

                            val probedId = "DROP-" + hostIp.replace(".", "").takeLast(4)
                            if (probedId != localDeviceId) {
                                val isHotspot = hostIp == gatewayIp || hostIp.startsWith("192.168.43.") || hostIp.startsWith("192.168.49.")
                                val device = DiscoveredDevice(
                                    id = probedId,
                                    name = if (isHotspot) "Hotspot Host ($hostIp)" else "Nearby Receiver ($hostIp)",
                                    transportType = if (isHotspot) TransportType.WIFI_DIRECT else TransportType.LOCAL_WIFI,
                                    ipAddress = hostIp,
                                    port = DEFAULT_TCP_PORT,
                                    isReadyToReceive = true,
                                    lastSeenTimestamp = System.currentTimeMillis()
                                )
                                val current = _discoveredDevices.value.toMutableMap()
                                current[probedId] = device
                                _discoveredDevices.value = current
                                Log.d(TAG, "Discovered Hotspot Host/Gateway: $hostIp")
                            }
                        } catch (_: Exception) {}
                    }
                }
            } catch (e: Exception) {
                Log.w(TAG, "Error probing gateway", e)
            }

            for (localIp in ips) {
                val prefix = localIp.substringBeforeLast(".") + "."
                val myLastOctet = localIp.substringAfterLast(".").toIntOrNull() ?: -1

                // Sweep local subnet
                for (i in 1..254) {
                    if (!isActive) break
                    if (i == myLastOctet) continue
                    val targetIp = "$prefix$i"

                    launch {
                        try {
                            val testSocket = Socket()
                            testSocket.connect(InetSocketAddress(targetIp, DEFAULT_TCP_PORT), 250)
                            testSocket.close()

                            // Peer TCP port is open! Register device
                            val probedId = "DROP-" + targetIp.replace(".", "").takeLast(4)
                            if (probedId != localDeviceId) {
                                val device = DiscoveredDevice(
                                    id = probedId,
                                    name = "Android ($targetIp)",
                                    transportType = TransportType.LOCAL_WIFI,
                                    ipAddress = targetIp,
                                    port = DEFAULT_TCP_PORT,
                                    isReadyToReceive = true,
                                    lastSeenTimestamp = System.currentTimeMillis()
                                )
                                val current = _discoveredDevices.value.toMutableMap()
                                current[probedId] = device
                                _discoveredDevices.value = current
                            }
                        } catch (_: Exception) {
                            // port closed / unreachable
                        }
                    }
                    if (i % 25 == 0) delay(30)
                }
            }
        }
    }

    private fun handleResolvedService(serviceInfo: NsdServiceInfo, localDeviceId: String) {
        val rawName = serviceInfo.serviceName
        val parts = rawName.split("#", limit = 2)
        val id = parts.getOrNull(0) ?: rawName
        if (id == localDeviceId) return // ignore self

        val name = parts.getOrNull(1) ?: "Nearby Android"
        val host = serviceInfo.host?.hostAddress ?: return
        val port = serviceInfo.port

        val device = DiscoveredDevice(
            id = id,
            name = name,
            transportType = TransportType.LOCAL_WIFI,
            ipAddress = host,
            port = port,
            isReadyToReceive = true,
            lastSeenTimestamp = System.currentTimeMillis()
        )

        val current = _discoveredDevices.value.toMutableMap()
        val probedKey = current.keys.find { it.startsWith("DROP-") && current[it]?.ipAddress == host && it != id }
        if (probedKey != null) {
            current.remove(probedKey)
        }
        current[id] = device
        _discoveredDevices.value = current
    }

    fun stopDiscovery() {
        try {
            if (discoveryListener != null) {
                nsdManager?.stopServiceDiscovery(discoveryListener)
            }
        } catch (e: Exception) {
            Log.w(TAG, "Error stopping NSD discovery", e)
        } finally {
            discoveryListener = null
        }

        udpListenJob?.cancel()
        udpListenJob = null
        subnetProbeJob?.cancel()
        subnetProbeJob = null
        try {
            udpSocket?.close()
        } catch (_: Exception) {}
        udpSocket = null

        releaseMulticastLock()
    }

    fun clearDevices() {
        _discoveredDevices.value = emptyMap()
    }
}
