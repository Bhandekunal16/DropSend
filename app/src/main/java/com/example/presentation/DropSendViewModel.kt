package com.example.presentation

import android.app.Application
import android.net.Uri
import android.os.Build
import android.util.Base64
import android.util.Log
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.example.DropSendApplication
import com.example.data.connectivity.LocalHotspotInfo
import com.example.data.db.TransferHistoryEntity
import com.example.data.storage.StorageValidationResult
import com.example.domain.model.DiscoveredDevice
import com.example.domain.model.DropSendError
import com.example.domain.model.FileTransferStatus
import com.example.domain.model.SessionState
import com.example.domain.model.SharingRole
import com.example.domain.model.TransferFile
import com.example.domain.model.TransferProgress
import com.example.domain.model.TransportType
import com.example.security.SessionCrypto
import com.example.service.DropSendTransferService
import com.example.transfer.protocol.ProtocolMessage
import com.example.transfer.transport.BluetoothTransferTransport
import com.example.transfer.transport.TcpTransferTransport
import com.example.transfer.transport.TransferTransport
import com.example.ui.util.HapticFeedbackHelper
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream
import java.io.InputStream
import java.io.RandomAccessFile
import java.security.KeyPair
import java.util.UUID
import kotlin.coroutines.coroutineContext

data class UiState(
    val sessionState: SessionState = SessionState.IDLE,
    val role: SharingRole? = null,
    val localDeviceId: String = "",
    val localDeviceName: String = "",
    val localIpAddresses: List<String> = emptyList(),
    val selectedFiles: List<TransferFile> = emptyList(),
    val nearbyDevices: List<DiscoveredDevice> = emptyList(),
    val targetDevice: DiscoveredDevice? = null,
    val incomingRequest: ProtocolMessage.SessionRequest? = null,
    val verificationCode: String = "",
    val transferProgress: TransferProgress = TransferProgress(),
    val errorMessage: String? = null,
    val statusMessage: String? = null,
    val completedFilesCount: Int = 0,
    val totalTransferredBytes: Long = 0L,
    val sessionDurationSeconds: Long = 0L
)

class DropSendViewModel(application: Application) : AndroidViewModel(application) {

    companion object {
        private const val TAG = "DropSendViewModel"
        private const val CHUNK_SIZE_WIFI = 128 * 1024 // 128 KB
        private const val CHUNK_SIZE_BT = 32 * 1024   // 32 KB
        const val DEFAULT_PORT = 8888
        private const val MAX_RECONNECT_ATTEMPTS = 3
    }

    private val app = application as DropSendApplication
    private val storageManager = app.storageManager
    private val discoveryManager = app.discoveryManager
    private val themePreferences = app.themePreferences
    private val historyRepository = app.transferHistoryRepository
    val connectivityMonitor = app.connectivityMonitor
    val localHotspotManager = app.localHotspotManager
    val hotspotAutoConnector = app.hotspotAutoConnector

    val localHotspotInfo: StateFlow<LocalHotspotInfo> = localHotspotManager.hotspotState
    val transferHistory: StateFlow<List<TransferHistoryEntity>> = historyRepository.allHistory
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    private val hapticHelper = HapticFeedbackHelper.getInstance(application)

    val currentPalette: StateFlow<com.example.ui.theme.ThemePalette> = themePreferences.currentPalette
    val darkModePreference: StateFlow<com.example.ui.theme.DarkModePreference> = themePreferences.darkModePreference

    fun setPalette(palette: com.example.ui.theme.ThemePalette) {
        themePreferences.setPalette(palette)
    }

    fun setDarkModePreference(mode: com.example.ui.theme.DarkModePreference) {
        themePreferences.setDarkModePreference(mode)
    }

    fun refreshConnectivity() {
        connectivityMonitor.updateState()
    }

    private val _uiState = MutableStateFlow(
        UiState(
            localDeviceId = SessionCrypto.generateTemporaryIdentity(),
            localDeviceName = "${Build.MANUFACTURER.replaceFirstChar { it.uppercase() }} ${Build.MODEL}"
        )
    )
    val uiState: StateFlow<UiState> = _uiState.asStateFlow()

    private var activeTransport: TransferTransport? = null
    private var sessionKey: ByteArray = SessionCrypto.generateSessionKey()
    private var sessionToken: String = SessionCrypto.generateSessionToken()
    private var ecKeyPair: KeyPair = SessionCrypto.generateEcKeyPair()

    private var transferJob: Job? = null
    private var messageListenerJob: Job? = null
    private var speedCalcJob: Job? = null
    private var serverListenJob: Job? = null
    private var reconnectJob: Job? = null

    // Speed tracking
    private var lastMeasuredBytes = 0L
    private var lastMeasuredTime = System.currentTimeMillis()
    private var smoothedSpeedBps: Double = 0.0

    // Resume tracking
    private var currentTransferFileId: String = ""
    private var currentTransferOffset: Long = 0L

    init {
        // Collect nearby devices from discovery manager
        viewModelScope.launch {
            var previousDeviceIds = emptySet<String>()
            discoveryManager.nearbyDevices.collect { devices ->
                val currentIds = devices.map { it.id }.toSet()
                val newlyDiscovered = currentIds - previousDeviceIds
                if (newlyDiscovered.isNotEmpty() && _uiState.value.sessionState == SessionState.DISCOVERING) {
                    hapticHelper.performDeviceDiscovered()
                }
                previousDeviceIds = currentIds
                _uiState.update { it.copy(nearbyDevices = devices) }
            }
        }

        // Listen for foreground service notification cancel actions
        viewModelScope.launch {
            DropSendTransferService.cancelEvents.collect {
                cancelTransfer()
            }
        }
    }

    /**
     * Start the Send Flow: User selects files
     */
    fun selectFiles(uris: List<Uri>) {
        viewModelScope.launch {
            val resolved = uris.map { storageManager.resolveFile(it) }
            _uiState.update {
                it.copy(
                    role = SharingRole.SENDER,
                    selectedFiles = resolved,
                    sessionState = SessionState.IDLE
                )
            }
        }
    }

    fun removeSelectedFile(fileId: String) {
        _uiState.update { state ->
            val updated = state.selectedFiles.filterNot { it.id == fileId }
            state.copy(selectedFiles = updated)
        }
    }

    /**
     * Sender starts searching for nearby devices
     */
    fun startSenderDiscovery() {
        if (_uiState.value.selectedFiles.isEmpty()) return

        val ips = discoveryManager.lanDiscovery.getLocalIpAddresses()
        _uiState.update {
            it.copy(
                sessionState = SessionState.DISCOVERING,
                role = SharingRole.SENDER,
                localIpAddresses = ips,
                errorMessage = null
            )
        }
        discoveryManager.startDiscovery(_uiState.value.localDeviceId)
    }

    /**
     * Force refresh/rescan all discovery protocols
     */
    fun rescanDevices() {
        discoveryManager.clear()
        val ips = discoveryManager.lanDiscovery.getLocalIpAddresses()
        _uiState.update { it.copy(localIpAddresses = ips) }
        discoveryManager.startDiscovery(_uiState.value.localDeviceId)
    }

    /**
     * Receiver enters Receive mode: starts listening for incoming transfers
     */
    fun startReceiverMode() {
        resetSessionState()
        val deviceId = SessionCrypto.generateTemporaryIdentity()
        sessionToken = SessionCrypto.generateSessionToken()
        ecKeyPair = SessionCrypto.generateEcKeyPair()
        val ips = discoveryManager.lanDiscovery.getLocalIpAddresses().toMutableList()

        _uiState.update {
            it.copy(
                role = SharingRole.RECEIVER,
                localDeviceId = deviceId,
                localIpAddresses = ips,
                sessionState = SessionState.DISCOVERING,
                errorMessage = null
            )
        }

        // 1. Activate Local Hotspot for direct offline QR pairing
        localHotspotManager.startLocalHotspot(deviceId, _uiState.value.localDeviceName) { info ->
            if (info.ipAddress.isNotBlank() && !ips.contains(info.ipAddress)) {
                ips.add(0, info.ipAddress)
                _uiState.update { it.copy(localIpAddresses = ips) }
            }
        }

        // 2. Start Dual Server (TCP for Wi-Fi / Hotspot + RFCOMM for Bluetooth)
        serverListenJob = viewModelScope.launch(Dispatchers.IO) {
            val connectedGuard = java.util.concurrent.atomic.AtomicBoolean(false)
            val tcpTransport = TcpTransferTransport(TransportType.LOCAL_WIFI)
            val btTransport = BluetoothTransferTransport(discoveryManager.bleDiscovery.bluetoothAdapter)

            val tcpPort = try {
                tcpTransport.startServer(DEFAULT_PORT)
            } catch (e: Exception) {
                Log.w(TAG, "Failed starting TCP server: ${e.message}")
                DEFAULT_PORT
            }

            val hasBtServer = try {
                if (discoveryManager.bleDiscovery.isBluetoothEnabled) {
                    btTransport.startServer(0)
                    true
                } else false
            } catch (e: Exception) {
                Log.w(TAG, "Failed starting Bluetooth RFCOMM server: ${e.message}")
                false
            }

            // Start advertising via LAN mDNS / UDP and BLE
            discoveryManager.startAdvertising(deviceId, _uiState.value.localDeviceName, tcpPort)

            // TCP Receiver Listener
            launch {
                try {
                    tcpTransport.acceptConnection()
                    if (connectedGuard.compareAndSet(false, true)) {
                        activeTransport = tcpTransport
                        try { btTransport.disconnect() } catch (_: Exception) {}
                        withContext(Dispatchers.Main) {
                            _uiState.update { it.copy(sessionState = SessionState.AUTHENTICATING) }
                            discoveryManager.stopAdvertising()
                            discoveryManager.stopDiscovery()
                            listenToIncomingMessages(tcpTransport)
                        }
                    } else {
                        tcpTransport.disconnect()
                    }
                } catch (e: Exception) {
                    if (!connectedGuard.get()) {
                        Log.w(TAG, "TCP listen ended: ${e.message}")
                    }
                }
            }

            // Bluetooth RFCOMM Receiver Listener
            if (hasBtServer) {
                launch {
                    try {
                        btTransport.acceptConnection()
                        if (connectedGuard.compareAndSet(false, true)) {
                            activeTransport = btTransport
                            try { tcpTransport.disconnect() } catch (_: Exception) {}
                            withContext(Dispatchers.Main) {
                                _uiState.update { it.copy(sessionState = SessionState.AUTHENTICATING) }
                                discoveryManager.stopAdvertising()
                                discoveryManager.stopDiscovery()
                                listenToIncomingMessages(btTransport)
                            }
                        } else {
                            btTransport.disconnect()
                        }
                    } catch (e: Exception) {
                        if (!connectedGuard.get()) {
                            Log.w(TAG, "Bluetooth RFCOMM listen ended: ${e.message}")
                        }
                    }
                }
            }
        }
    }

    /**
     * Connect via QR Code
     */
    fun connectViaQrPayload(qrContent: String) {
        val params = hotspotAutoConnector.parseQrCode(qrContent)
        if (params == null) {
            _uiState.update {
                it.copy(
                    sessionState = SessionState.FAILED,
                    errorMessage = "Unrecognized QR code format. Please scan the QR code from DropSend receiver."
                )
            }
            return
        }

        discoveryManager.stopDiscovery()

        val directDevice = DiscoveredDevice(
            id = params.deviceId.ifBlank { "QR-" + params.ipAddress.takeLast(4) },
            name = params.deviceName,
            transportType = TransportType.WIFI_DIRECT,
            ipAddress = params.ipAddress,
            port = params.port,
            isReadyToReceive = true
        )

        _uiState.update {
            it.copy(
                targetDevice = directDevice,
                sessionState = SessionState.CONNECTING,
                statusMessage = "Connecting to Receiver's Direct Hotspot...",
                errorMessage = null
            )
        }

        viewModelScope.launch(Dispatchers.IO) {
            if (params.ssid.isNotBlank()) {
                hotspotAutoConnector.connectToHotspotNetwork(params) { status ->
                    _uiState.update { it.copy(statusMessage = status) }
                }
            }

            try {
                val transport = TcpTransferTransport(TransportType.WIFI_DIRECT)
                activeTransport = transport
                transport.connect(params.ipAddress, params.port)

                withContext(Dispatchers.Main) {
                    _uiState.update {
                        it.copy(
                            sessionState = SessionState.AUTHENTICATING,
                            statusMessage = "Connected! Establishing encrypted handshake..."
                        )
                    }
                    listenToIncomingMessages(transport)
                    performSenderHandshake(transport, directDevice)
                }
            } catch (e: Exception) {
                Log.e(TAG, "Failed connecting to QR target ${params.ipAddress}:${params.port}", e)
                withContext(Dispatchers.Main) {
                    _uiState.update {
                        it.copy(
                            sessionState = SessionState.FAILED,
                            errorMessage = "Could not connect to ${params.deviceName}. Make sure both devices are nearby."
                        )
                    }
                }
            }
        }
    }

    /**
     * Direct IP Connect
     */
    fun connectToDirectIp(ipOrHost: String, port: Int = DEFAULT_PORT) {
        val trimmed = ipOrHost.trim()
        val targetIp = if (trimmed.contains(":")) trimmed.substringBefore(":") else trimmed
        val targetPort = if (trimmed.contains(":")) trimmed.substringAfter(":").toIntOrNull() ?: port else port

        val directDevice = DiscoveredDevice(
            id = "DROP-" + targetIp.replace(".", "").takeLast(4),
            name = "Direct Peer ($targetIp:$targetPort)",
            transportType = TransportType.LOCAL_WIFI,
            ipAddress = targetIp,
            port = targetPort,
            isReadyToReceive = true
        )
        connectToDevice(directDevice)
    }

    /**
     * Add a simulated peer for single-device test or verification
     */
    fun addDemoPeer() {
        val demoDevice = DiscoveredDevice(
            id = "DROP-DEMO",
            name = "Demo Android Peer",
            transportType = TransportType.LOCAL_WIFI,
            ipAddress = "127.0.0.1",
            port = DEFAULT_PORT,
            isReadyToReceive = true
        )
        val current = _uiState.value.nearbyDevices.toMutableList()
        if (current.none { it.id == "DROP-DEMO" }) {
            current.add(demoDevice)
            hapticHelper.performDeviceDiscovered()
            _uiState.update { it.copy(nearbyDevices = current) }
        }
    }

    /**
     * Sender selects a target device to connect and send files
     */
    fun connectToDevice(device: DiscoveredDevice) {
        val isSimulated = device.id in setOf("DROP-DEMO", "DROP-9A14", "DROP-3B88", "DROP-7F20", "DROP-4C61") ||
                device.name.contains("Simulated", ignoreCase = true) ||
                device.name.contains("Virtual", ignoreCase = true) ||
                device.name.contains("Demo", ignoreCase = true)

        if (isSimulated) {
            launchSenderSimulation(targetDevice = device)
            return
        }

        discoveryManager.stopDiscovery()
        _uiState.update {
            it.copy(
                targetDevice = device,
                sessionState = SessionState.CONNECTING,
                errorMessage = null
            )
        }

        viewModelScope.launch(Dispatchers.IO) {
            try {
                val transport: TransferTransport = when {
                    device.ipAddress != null && device.ipAddress.isNotBlank() -> {
                        TcpTransferTransport(device.transportType)
                    }
                    device.bluetoothAddress != null -> {
                        BluetoothTransferTransport(discoveryManager.bleDiscovery.bluetoothAdapter)
                    }
                    else -> {
                        TcpTransferTransport(TransportType.LOCAL_WIFI)
                    }
                }
                activeTransport = transport

                val targetHost = device.ipAddress ?: device.bluetoothAddress ?: "127.0.0.1"
                transport.connect(targetHost, device.port)

                withContext(Dispatchers.Main) {
                    _uiState.update { it.copy(sessionState = SessionState.AUTHENTICATING) }
                    listenToIncomingMessages(transport)
                    performSenderHandshake(transport, device)
                }
            } catch (e: Exception) {
                Log.e(TAG, "Failed to connect to ${device.name}", e)
                withContext(Dispatchers.Main) {
                    _uiState.update {
                        it.copy(
                            sessionState = SessionState.FAILED,
                            errorMessage = "Couldn't connect to ${device.name}. Ensure Wi-Fi or Bluetooth is active."
                        )
                    }
                }
            }
        }
    }

    private suspend fun performSenderHandshake(transport: TransferTransport, device: DiscoveredDevice) {
        val myPubKeyBase64 = Base64.encodeToString(ecKeyPair.public.encoded, Base64.NO_WRAP)
        sessionToken = SessionCrypto.generateSessionToken()

        // Send AuthHandshake
        transport.send(
            ProtocolMessage.AuthHandshake(
                senderId = _uiState.value.localDeviceId,
                sessionToken = sessionToken,
                publicKeyBase64 = myPubKeyBase64
            )
        )
    }

    /**
     * Receiver accepts the incoming transfer request
     */
    fun acceptIncomingRequest() {
        val request = _uiState.value.incomingRequest ?: return
        val transport = activeTransport

        if (transport == null) {
            hapticHelper.performTransferStart()
            startSimulatedReceiverTransfer()
            return
        }

        viewModelScope.launch(Dispatchers.IO) {
            try {
                // Storage Pre-Flight Validation Check
                val storageCheck = storageManager.validateStorageAvailable(request.totalSize)
                if (storageCheck is StorageValidationResult.Insufficient) {
                    transport.send(ProtocolMessage.SessionReject(storageCheck.message))
                    withContext(Dispatchers.Main) {
                        _uiState.update {
                            it.copy(
                                sessionState = SessionState.FAILED,
                                errorMessage = storageCheck.message
                            )
                        }
                    }
                    return@launch
                }

                hapticHelper.performTransferStart()
                transport.send(
                    ProtocolMessage.SessionAccept(
                        receiverId = _uiState.value.localDeviceId,
                        receiverName = _uiState.value.localDeviceName,
                        verificationCode = request.verificationCode
                    )
                )
                withContext(Dispatchers.Main) {
                    _uiState.update {
                        it.copy(
                            sessionState = SessionState.TRANSFERRING,
                            verificationCode = request.verificationCode,
                            transferProgress = TransferProgress(
                                totalFiles = request.files.size,
                                totalSizeBytes = request.totalSize,
                                verificationCode = request.verificationCode,
                                transportType = transport.transportType
                            )
                        )
                    }
                    startSpeedTracker()
                    DropSendTransferService.start(getApplication())
                }
            } catch (e: Exception) {
                Log.e(TAG, "Error accepting request", e)
            }
        }
    }

    /**
     * Receiver declines the incoming transfer request
     */
    fun declineIncomingRequest() {
        val transport = activeTransport ?: return
        viewModelScope.launch(Dispatchers.IO) {
            try {
                transport.send(ProtocolMessage.SessionReject("Declined by user"))
            } catch (_: Exception) {}
            withContext(Dispatchers.Main) {
                resetSessionState()
            }
        }
    }

    /**
     * Listens to protocol messages received from the peer
     */
    private fun listenToIncomingMessages(transport: TransferTransport) {
        messageListenerJob?.cancel()
        messageListenerJob = viewModelScope.launch(Dispatchers.IO) {
            try {
                transport.incomingMessages().collect { message ->
                    handleProtocolMessage(message, transport)
                }
            } catch (e: Exception) {
                Log.w(TAG, "Transport stream interrupted: ${e.message}")
                handleConnectionLoss()
            }
        }
    }

    private suspend fun handleProtocolMessage(message: ProtocolMessage, transport: TransferTransport) {
        when (message) {
            is ProtocolMessage.AuthHandshake -> {
                // Receiver receives sender's public key
                val peerPubKeyBytes = Base64.decode(message.publicKeyBase64, Base64.NO_WRAP)
                sessionToken = message.sessionToken
                sessionKey = SessionCrypto.deriveSharedSessionKey(
                    ecKeyPair.private,
                    peerPubKeyBytes,
                    salt = sessionToken.toByteArray(Charsets.UTF_8)
                )
                val myPubKeyBase64 = Base64.encodeToString(ecKeyPair.public.encoded, Base64.NO_WRAP)

                val verificationCode = SessionCrypto.deriveVerificationCode(
                    sessionId = sessionToken,
                    sharedKeyBytes = sessionKey,
                    additionalContext = message.senderId + _uiState.value.localDeviceId
                )

                transport.send(
                    ProtocolMessage.AuthHandshakeAck(
                        receiverId = _uiState.value.localDeviceId,
                        sessionToken = sessionToken,
                        publicKeyBase64 = myPubKeyBase64,
                        verificationCode = verificationCode
                    )
                )

                withContext(Dispatchers.Main) {
                    _uiState.update {
                        it.copy(
                            verificationCode = verificationCode,
                            sessionState = SessionState.AUTHENTICATING
                        )
                    }
                }
            }

            is ProtocolMessage.AuthHandshakeAck -> {
                // Sender completes ECDH exchange
                val peerPubKeyBytes = Base64.decode(message.publicKeyBase64, Base64.NO_WRAP)
                sessionKey = SessionCrypto.deriveSharedSessionKey(
                    ecKeyPair.private,
                    peerPubKeyBytes,
                    salt = sessionToken.toByteArray(Charsets.UTF_8)
                )

                val expectedCode = SessionCrypto.deriveVerificationCode(
                    sessionId = sessionToken,
                    sharedKeyBytes = sessionKey,
                    additionalContext = _uiState.value.localDeviceId + message.receiverId
                )

                if (expectedCode != message.verificationCode) {
                    Log.w(TAG, "Verification code mismatch: potential MITM attack or key desync!")
                    withContext(Dispatchers.Main) {
                        _uiState.update {
                            it.copy(
                                sessionState = SessionState.FAILED,
                                errorMessage = "Security verification failed. Session could not be verified securely."
                            )
                        }
                    }
                    cleanupTransport()
                    return
                }

                val totalSize = _uiState.value.selectedFiles.sumOf { it.sizeBytes }
                val metadataList = _uiState.value.selectedFiles.map { f ->
                    val checksum = storageManager.calculateFileChecksum(f)
                    ProtocolMessage.FileMetadata(
                        id = f.id,
                        name = f.name,
                        mimeType = f.mimeType,
                        size = f.sizeBytes,
                        checksum = checksum
                    )
                }

                withContext(Dispatchers.Main) {
                    _uiState.update {
                        it.copy(
                            verificationCode = message.verificationCode,
                            sessionState = SessionState.WAITING_FOR_ACCEPT
                        )
                    }
                }

                transport.send(
                    ProtocolMessage.SessionRequest(
                        senderId = _uiState.value.localDeviceId,
                        senderName = _uiState.value.localDeviceName,
                        totalSize = totalSize,
                        verificationCode = message.verificationCode,
                        files = metadataList,
                        sessionToken = sessionToken
                    )
                )
            }

            is ProtocolMessage.SessionRequest -> {
                withContext(Dispatchers.Main) {
                    val incomingFiles = message.files.map {
                        TransferFile(
                            id = it.id,
                            name = it.name,
                            mimeType = it.mimeType,
                            sizeBytes = it.size,
                            checksumSha256 = it.checksum
                        )
                    }
                    _uiState.update {
                        it.copy(
                            sessionState = SessionState.WAITING_FOR_ACCEPT,
                            incomingRequest = message,
                            selectedFiles = incomingFiles,
                            verificationCode = message.verificationCode
                        )
                    }
                }
            }

            is ProtocolMessage.SessionAccept -> {
                withContext(Dispatchers.Main) {
                    hapticHelper.performTransferStart()
                    _uiState.update {
                        it.copy(
                            sessionState = SessionState.TRANSFERRING,
                            transferProgress = TransferProgress(
                                totalFiles = it.selectedFiles.size,
                                totalSizeBytes = it.selectedFiles.sumOf { f -> f.sizeBytes },
                                verificationCode = message.verificationCode,
                                transportType = transport.transportType
                            )
                        )
                    }
                    startSpeedTracker()
                    DropSendTransferService.start(getApplication())
                    startSendingFiles(transport)
                }
            }

            is ProtocolMessage.SessionReject -> {
                withContext(Dispatchers.Main) {
                    _uiState.update {
                        it.copy(
                            sessionState = SessionState.FAILED,
                            errorMessage = message.reason
                        )
                    }
                    recordSessionHistory(status = "FAILED", errorMessage = message.reason)
                    cleanupTransport()
                }
            }

            is ProtocolMessage.SessionResumeRequest -> {
                // Receiver checks offset of existing part file
                val existingOffset = storageManager.getExistingPartOffset(
                    message.lastFileId,
                    currentReceivingFile?.name ?: ""
                )
                transport.send(
                    ProtocolMessage.SessionResumeAck(
                        accepted = true,
                        resumeFileId = message.lastFileId,
                        resumeOffset = existingOffset
                    )
                )
            }

            is ProtocolMessage.FileStart -> {
                handleReceiverFileStart(message)
            }

            is ProtocolMessage.Chunk -> {
                handleReceiverChunk(message, transport)
            }

            is ProtocolMessage.ChunkAck -> {
                currentTransferOffset = message.bytesReceived
            }

            is ProtocolMessage.FileComplete -> {
                handleReceiverFileComplete(message, transport)
            }

            is ProtocolMessage.TransferComplete -> {
                handleTransferComplete()
            }

            is ProtocolMessage.TransferCancel -> {
                withContext(Dispatchers.Main) {
                    _uiState.update {
                        it.copy(
                            sessionState = SessionState.CANCELLED,
                            errorMessage = "Transfer was cancelled by the other device."
                        )
                    }
                    recordSessionHistory(status = "CANCELLED", errorMessage = "Cancelled by peer")
                    cleanupTransport()
                }
            }
            else -> {}
        }
    }

    // Receiver: active file write state
    private var currentReceivingFile: ProtocolMessage.FileStart? = null
    private var currentTempFile: File? = null
    private var currentFileBytesReceived: Long = 0L

    private suspend fun handleReceiverFileStart(fileStart: ProtocolMessage.FileStart) {
        currentReceivingFile = fileStart
        currentTransferFileId = fileStart.fileId
        val resume = fileStart.startOffset > 0
        val temp = storageManager.createTempFileForReceiving(fileStart.fileId, fileStart.name, resume = resume)
        currentTempFile = temp
        currentFileBytesReceived = fileStart.startOffset

        withContext(Dispatchers.Main) {
            _uiState.update { state ->
                val updatedProgress = state.transferProgress.copy(
                    currentFileIndex = fileStart.fileIndex,
                    currentFileName = fileStart.name,
                    currentFileSize = fileStart.size,
                    currentFileBytes = fileStart.startOffset
                )
                state.copy(transferProgress = updatedProgress)
            }
        }
    }

    private suspend fun handleReceiverChunk(chunk: ProtocolMessage.Chunk, transport: TransferTransport) {
        val temp = currentTempFile ?: return
        val receiving = currentReceivingFile ?: return

        // Validate chunk belongs to current active file
        if (chunk.fileId != receiving.fileId) {
            Log.w(TAG, "Rejected chunk for mismatching fileId: ${chunk.fileId} vs ${receiving.fileId}")
            return
        }

        // Validate chunk payload size against wire safety bounds
        if (chunk.payload.size > ProtocolMessage.MAX_CHUNK_SIZE) {
            Log.e(TAG, "Chunk payload ${chunk.payload.size} exceeds maximum allowable chunk size")
            return
        }

        // Validate offset range
        if (chunk.offset < 0 || chunk.offset > receiving.size + 1024) {
            Log.e(TAG, "Chunk offset out of range: offset=${chunk.offset}, fileSize=${receiving.size}")
            return
        }

        val decrypted = try {
            SessionCrypto.decryptChunk(chunk.payload, sessionKey)
        } catch (_: Exception) {
            chunk.payload
        }

        try {
            storageManager.writeChunkToTempFile(temp, chunk.offset, decrypted)
        } catch (e: Exception) {
            Log.e(TAG, "Disk write failure for chunk at offset ${chunk.offset}", e)
            withContext(Dispatchers.Main) {
                _uiState.update {
                    it.copy(
                        sessionState = SessionState.FAILED,
                        errorMessage = DropSendError.StorageWriteFailed(receiving.name, e).userMessage
                    )
                }
            }
            return
        }

        currentFileBytesReceived = maxOf(currentFileBytesReceived, chunk.offset + decrypted.size)

        withContext(Dispatchers.Main) {
            _uiState.update { state ->
                val prevTotal = state.transferProgress.totalBytesTransferred
                val newProgress = state.transferProgress.copy(
                    currentFileBytes = currentFileBytesReceived,
                    totalBytesTransferred = maxOf(prevTotal, prevTotal + decrypted.size)
                )
                state.copy(transferProgress = newProgress)
            }
        }

        // Send ACK with confirmed monotonic offset
        transport.send(
            ProtocolMessage.ChunkAck(
                fileId = chunk.fileId,
                sequence = chunk.sequence,
                bytesReceived = currentFileBytesReceived
            )
        )
    }

    private suspend fun handleReceiverFileComplete(msg: ProtocolMessage.FileComplete, transport: TransferTransport) {
        val temp = currentTempFile
        val receiving = currentReceivingFile

        if (temp != null && receiving != null) {
            withContext(Dispatchers.Main) {
                _uiState.update { it.copy(sessionState = SessionState.VERIFYING) }
            }

            val savedUri = storageManager.finalizeReceivedFile(
                tempFile = temp,
                targetFileName = receiving.name,
                mimeType = receiving.mimeType,
                expectedChecksum = msg.checksum
            )
            val success = savedUri != null

            transport.send(ProtocolMessage.FileVerifyAck(fileId = msg.fileId, success = success))

            withContext(Dispatchers.Main) {
                _uiState.update { state ->
                    val updatedFiles = state.selectedFiles.map { f ->
                        if (f.id == msg.fileId) f.copy(
                            status = if (success) FileTransferStatus.COMPLETED else FileTransferStatus.FAILED,
                            uri = savedUri ?: f.uri
                        )
                        else f
                    }
                    state.copy(
                        sessionState = SessionState.TRANSFERRING,
                        selectedFiles = updatedFiles,
                        completedFilesCount = state.completedFilesCount + (if (success) 1 else 0)
                    )
                }
            }
        }

        currentTempFile = null
        currentReceivingFile = null
    }

    private fun startSendingFiles(transport: TransferTransport, resumeIndex: Int = 0, resumeOffset: Long = 0L) {
        transferJob?.cancel()
        transferJob = viewModelScope.launch(Dispatchers.IO) {
            val files = _uiState.value.selectedFiles
            val chunkSize = if (transport.transportType == TransportType.BLUETOOTH) CHUNK_SIZE_BT else CHUNK_SIZE_WIFI
            var totalBytesSent = _uiState.value.transferProgress.totalBytesTransferred

            for (index in resumeIndex until files.size) {
                if (!isActive) break
                val file = files[index]
                currentTransferFileId = file.id
                val startOffset = if (index == resumeIndex) resumeOffset else 0L

                withContext(Dispatchers.Main) {
                    _uiState.update { state ->
                        val updatedFiles = state.selectedFiles.mapIndexed { idx, f ->
                            if (idx == index) f.copy(status = FileTransferStatus.TRANSFERRING) else f
                        }
                        val updatedProgress = state.transferProgress.copy(
                            currentFileIndex = index,
                            currentFileName = file.name,
                            currentFileSize = file.sizeBytes,
                            currentFileBytes = startOffset
                        )
                        state.copy(selectedFiles = updatedFiles, transferProgress = updatedProgress)
                    }
                }

                // Send FILE_START with startOffset
                transport.send(
                    ProtocolMessage.FileStart(
                        fileIndex = index,
                        totalFiles = files.size,
                        fileId = file.id,
                        name = file.name,
                        mimeType = file.mimeType,
                        size = file.sizeBytes,
                        checksum = file.checksumSha256,
                        startOffset = startOffset
                    )
                )

                // Stream file chunks
                val uri = file.uri
                if (uri != null) {
                    var stream: InputStream? = null
                    try {
                        stream = storageManager.openFileForReading(uri)
                        if (stream != null) {
                            if (startOffset > 0) {
                                stream.skip(startOffset)
                            }
                            val buffer = ByteArray(chunkSize)
                            var sequence = startOffset / chunkSize
                            var offset = startOffset
                            var bytesRead: Int

                            while (stream.read(buffer).also { bytesRead = it } != -1 && isActive) {
                                val chunkPayload = if (bytesRead == buffer.size) buffer else buffer.copyOf(bytesRead)
                                val encryptedPayload = SessionCrypto.encryptChunk(chunkPayload, sessionKey, sequence)

                                transport.send(
                                    ProtocolMessage.Chunk(
                                        fileId = file.id,
                                        sequence = sequence,
                                        offset = offset,
                                        payload = encryptedPayload
                                    )
                                )

                                sequence++
                                offset += bytesRead
                                totalBytesSent += bytesRead
                                currentTransferOffset = offset

                                val currentOffset = offset
                                val totalSent = totalBytesSent
                                withContext(Dispatchers.Main) {
                                    _uiState.update { state ->
                                        val newProgress = state.transferProgress.copy(
                                            currentFileBytes = currentOffset,
                                            totalBytesTransferred = totalSent
                                        )
                                        state.copy(transferProgress = newProgress)
                                    }
                                }
                            }
                        }
                    } catch (e: Exception) {
                        Log.e(TAG, "Error streaming file ${file.name}", e)
                    } finally {
                        stream?.close()
                    }
                }

                // Send FILE_COMPLETE
                transport.send(
                    ProtocolMessage.FileComplete(
                        fileId = file.id,
                        checksum = file.checksumSha256
                    )
                )

                withContext(Dispatchers.Main) {
                    _uiState.update { state ->
                        val updatedFiles = state.selectedFiles.mapIndexed { idx, f ->
                            if (idx == index) f.copy(status = FileTransferStatus.COMPLETED) else f
                        }
                        state.copy(
                            selectedFiles = updatedFiles,
                            completedFilesCount = index + 1
                        )
                    }
                }
            }

            // All files sent!
            transport.send(ProtocolMessage.TransferComplete)
            withContext(Dispatchers.Main) {
                handleTransferComplete()
            }
        }
    }

    /**
     * Automatic Connection Loss & Resumption Engine
     */
    private fun handleConnectionLoss() {
        if (_uiState.value.sessionState != SessionState.TRANSFERRING) return

        _uiState.update {
            it.copy(
                transferProgress = it.transferProgress.copy(isReconnecting = true),
                statusMessage = "Connection lost. Attempting to resume transfer..."
            )
        }

        reconnectJob?.cancel()
        reconnectJob = viewModelScope.launch(Dispatchers.IO) {
            val target = _uiState.value.targetDevice
            val role = _uiState.value.role

            if (target == null || role != SharingRole.SENDER) {
                // Receiver waits in background or cleans up on timeout
                delay(15_000)
                if (_uiState.value.transferProgress.isReconnecting) {
                    withContext(Dispatchers.Main) {
                        _uiState.update {
                            it.copy(
                                sessionState = SessionState.FAILED,
                                errorMessage = "Transfer connection timed out."
                            )
                        }
                        recordSessionHistory(status = "FAILED", errorMessage = "Connection timed out")
                    }
                }
                return@launch
            }

            var reconnected = false
            for (attempt in 1..MAX_RECONNECT_ATTEMPTS) {
                _uiState.update {
                    it.copy(statusMessage = "Reconnecting to ${target.name} (Attempt $attempt/$MAX_RECONNECT_ATTEMPTS)...")
                }
                delay(attempt * 2000L) // Exponential backoff

                try {
                    val newTransport = TcpTransferTransport(target.transportType)
                    newTransport.connect(target.ipAddress ?: "127.0.0.1", target.port)
                    activeTransport = newTransport
                    listenToIncomingMessages(newTransport)

                    // Send Resume Request
                    newTransport.send(
                        ProtocolMessage.SessionResumeRequest(
                            sessionToken = sessionToken,
                            lastFileId = currentTransferFileId,
                            confirmedOffset = currentTransferOffset
                        )
                    )

                    reconnected = true
                    withContext(Dispatchers.Main) {
                        _uiState.update {
                            it.copy(
                                statusMessage = "Reconnected! Resuming transfer...",
                                transferProgress = it.transferProgress.copy(isReconnecting = false)
                            )
                        }
                    }

                    val currentIndex = _uiState.value.transferProgress.currentFileIndex
                    startSendingFiles(newTransport, resumeIndex = currentIndex, resumeOffset = currentTransferOffset)
                    break
                } catch (e: Exception) {
                    Log.w(TAG, "Reconnect attempt $attempt failed: ${e.message}")
                }
            }

            if (!reconnected) {
                withContext(Dispatchers.Main) {
                    _uiState.update {
                        it.copy(
                            sessionState = SessionState.FAILED,
                            errorMessage = DropSendError.ReconnectFailed().userMessage,
                            transferProgress = it.transferProgress.copy(isReconnecting = false)
                        )
                    }
                    recordSessionHistory(status = "FAILED", errorMessage = "Failed reconnecting")
                }
            }
        }
    }

    private fun handleTransferComplete() {
        speedCalcJob?.cancel()
        DropSendTransferService.stop(getApplication())
        hapticHelper.performTransferComplete()
        _uiState.update {
            it.copy(
                sessionState = SessionState.COMPLETED,
                totalTransferredBytes = it.transferProgress.totalBytesTransferred
            )
        }
        recordSessionHistory(status = "COMPLETED")
    }

    private fun recordSessionHistory(status: String, errorMessage: String? = null) {
        val state = _uiState.value
        val isSending = state.role == SharingRole.SENDER
        val peerName = state.targetDevice?.name ?: state.incomingRequest?.senderName ?: "Nearby Device"
        val peerDeviceId = state.targetDevice?.id ?: state.incomingRequest?.senderId ?: ""
        val transport = activeTransport?.transportType?.displayName ?: TransportType.LOCAL_WIFI.displayName

        viewModelScope.launch(Dispatchers.IO) {
            state.selectedFiles.forEach { file ->
                historyRepository.recordTransfer(
                    TransferHistoryEntity(
                        sessionId = sessionToken,
                        fileName = file.name,
                        mimeType = file.mimeType,
                        sizeBytes = file.sizeBytes,
                        isSending = isSending,
                        peerName = peerName,
                        peerDeviceId = peerDeviceId,
                        transportType = transport,
                        status = status,
                        durationSeconds = state.sessionDurationSeconds,
                        averageSpeedBps = state.transferProgress.speedBytesPerSec,
                        fileUriString = file.uri?.toString(),
                        errorMessage = errorMessage
                    )
                )
            }
        }
    }

    fun deleteHistoryItem(id: Long) {
        viewModelScope.launch(Dispatchers.IO) {
            historyRepository.deleteEntry(id)
        }
    }

    fun clearHistory() {
        viewModelScope.launch(Dispatchers.IO) {
            historyRepository.clearHistory()
        }
    }

    private fun startSpeedTracker() {
        lastMeasuredBytes = 0L
        lastMeasuredTime = System.currentTimeMillis()
        smoothedSpeedBps = 0.0

        speedCalcJob?.cancel()
        speedCalcJob = viewModelScope.launch {
            while (isActive) {
                delay(1000)
                val currentBytes = _uiState.value.transferProgress.totalBytesTransferred
                val currentTime = System.currentTimeMillis()
                val elapsedSec = ((currentTime - lastMeasuredTime) / 1000.0).coerceAtLeast(0.1)
                val bytesDiff = (currentBytes - lastMeasuredBytes).coerceAtLeast(0)

                val instantSpeed = bytesDiff / elapsedSec
                smoothedSpeedBps = if (smoothedSpeedBps == 0.0) instantSpeed else (0.35 * instantSpeed + 0.65 * smoothedSpeedBps)
                val speedBps = smoothedSpeedBps.toLong().coerceAtLeast(0L)

                val totalBytes = _uiState.value.transferProgress.totalSizeBytes
                val remainingBytes = (totalBytes - currentBytes).coerceAtLeast(0)
                val eta = if (speedBps > 0) remainingBytes / speedBps else 0L

                lastMeasuredBytes = currentBytes
                lastMeasuredTime = currentTime

                _uiState.update { state ->
                    val updated = state.transferProgress.copy(
                        speedBytesPerSec = speedBps,
                        etaSeconds = eta
                    )
                    state.copy(
                        transferProgress = updated,
                        sessionDurationSeconds = state.sessionDurationSeconds + 1
                    )
                }

                DropSendTransferService.start(getApplication())
            }
        }
    }

    fun pauseTransfer() {
        viewModelScope.launch(Dispatchers.IO) {
            activeTransport?.send(ProtocolMessage.TransferPause)
            withContext(Dispatchers.Main) {
                _uiState.update {
                    it.copy(transferProgress = it.transferProgress.copy(isPaused = true))
                }
            }
        }
    }

    fun resumeTransfer() {
        viewModelScope.launch(Dispatchers.IO) {
            activeTransport?.send(ProtocolMessage.TransferResume)
            withContext(Dispatchers.Main) {
                _uiState.update {
                    it.copy(transferProgress = it.transferProgress.copy(isPaused = false))
                }
            }
        }
    }

    fun cancelTransfer() {
        viewModelScope.launch(Dispatchers.IO) {
            try {
                activeTransport?.send(ProtocolMessage.TransferCancel)
            } catch (_: Exception) {}
            cleanupTransport()
            storageManager.clearTempFiles()
            hapticHelper.performTransferFailed()
            recordSessionHistory(status = "CANCELLED", errorMessage = "Cancelled by user")
            withContext(Dispatchers.Main) {
                _uiState.update {
                    it.copy(
                        sessionState = SessionState.CANCELLED,
                        errorMessage = "Transfer cancelled"
                    )
                }
            }
        }
    }

    // Simulation Engine
    private var simulationJob: Job? = null

    fun generateMockFiles(): List<TransferFile> {
        return listOf(
            TransferFile(
                id = "mock-1",
                name = "4K_Vacation_Drone_Clip.mp4",
                mimeType = "video/mp4",
                sizeBytes = 54_800_000L,
                checksumSha256 = "e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855"
            ),
            TransferFile(
                id = "mock-2",
                name = "Project_Assets_2026.zip",
                mimeType = "application/zip",
                sizeBytes = 22_400_000L,
                checksumSha256 = "8f434346648f6b96df89dda901c5176b10a6d83961dd3c1ac88b59b2dc327aa4"
            ),
            TransferFile(
                id = "mock-3",
                name = "Quarterly_Financial_Report.pdf",
                mimeType = "application/pdf",
                sizeBytes = 3_750_000L,
                checksumSha256 = "ca978112ca1bbdcafac231b39a23dc4da786eff8147c4e72b9807785afee48bb"
            ),
            TransferFile(
                id = "mock-4",
                name = "HDR_Nature_Photos.tar",
                mimeType = "application/x-tar",
                sizeBytes = 14_200_000L,
                checksumSha256 = "185f8db32271fe25f561a6fc938b2e264306ec304eda518007d1764826381969"
            )
        )
    }

    fun populateDemoPeers() {
        val peers = listOf(
            DiscoveredDevice(
                id = "DROP-9A14",
                name = "Pixel 9 Pro (Wi-Fi Direct)",
                transportType = TransportType.WIFI_DIRECT,
                ipAddress = "192.168.43.1",
                port = 8888,
                isReadyToReceive = true
            ),
            DiscoveredDevice(
                id = "DROP-3B88",
                name = "Galaxy S24 Ultra (Local LAN)",
                transportType = TransportType.LOCAL_WIFI,
                ipAddress = "192.168.1.105",
                port = 8888,
                isReadyToReceive = true
            ),
            DiscoveredDevice(
                id = "DROP-7F20",
                name = "Nothing Phone 2 (Bluetooth)",
                transportType = TransportType.BLUETOOTH,
                bluetoothAddress = "00:11:22:33:AA:BB",
                isReadyToReceive = true
            ),
            DiscoveredDevice(
                id = "DROP-4C61",
                name = "MacBook Pro M3 (LAN Bridge)",
                transportType = TransportType.LOCAL_WIFI,
                ipAddress = "192.168.1.42",
                port = 8888,
                isReadyToReceive = true
            )
        )
        _uiState.update { it.copy(nearbyDevices = peers) }
    }

    fun launchSenderSimulation(
        targetDevice: DiscoveredDevice? = null,
        speedMBps: Float = 50f,
        customFiles: List<TransferFile>? = null
    ) {
        resetSessionState()

        val device = targetDevice ?: DiscoveredDevice(
            id = "DROP-9A14",
            name = "Pixel 9 Pro (Virtual Peer)",
            transportType = TransportType.WIFI_DIRECT,
            ipAddress = "192.168.43.1",
            port = 8888
        )

        val files = if (!customFiles.isNullOrEmpty()) {
            customFiles
        } else if (_uiState.value.selectedFiles.isNotEmpty()) {
            _uiState.value.selectedFiles
        } else {
            generateMockFiles()
        }

        val totalBytes = files.sumOf { it.sizeBytes }
        val code = SessionCrypto.deriveVerificationCode(device.id, sessionKey)

        _uiState.update {
            it.copy(
                role = SharingRole.SENDER,
                targetDevice = device,
                selectedFiles = files,
                verificationCode = code,
                sessionState = SessionState.CONNECTING,
                statusMessage = "Connecting to ${device.name}..."
            )
        }

        simulationJob?.cancel()
        simulationJob = viewModelScope.launch(Dispatchers.Default) {
            delay(500)
            _uiState.update {
                it.copy(
                    sessionState = SessionState.AUTHENTICATING,
                    statusMessage = "Handshake verified! Code: $code"
                )
            }
            delay(500)
            hapticHelper.performTransferStart()
            _uiState.update {
                it.copy(
                    sessionState = SessionState.TRANSFERRING,
                    statusMessage = null,
                    transferProgress = TransferProgress(
                        totalFiles = files.size,
                        totalSizeBytes = totalBytes,
                        verificationCode = code,
                        transportType = device.transportType
                    )
                )
            }

            runSimulatedStreamingTransfer(files, speedMBps)
        }
    }

    fun launchReceiverSimulation(
        senderName: String = "Pixel 9 Pro (Simulated)",
        speedMBps: Float = 50f,
        customFiles: List<TransferFile>? = null
    ) {
        resetSessionState()

        val files = customFiles ?: generateMockFiles()
        val totalBytes = files.sumOf { it.sizeBytes }
        val code = SessionCrypto.deriveVerificationCode("DROP-9A14", sessionKey)

        val mockRequest = ProtocolMessage.SessionRequest(
            senderId = "DROP-9A14",
            senderName = senderName,
            files = files.map { f ->
                ProtocolMessage.FileMetadata(
                    id = f.id,
                    name = f.name,
                    size = f.sizeBytes,
                    mimeType = f.mimeType,
                    checksum = f.checksumSha256
                )
            },
            totalSize = totalBytes,
            verificationCode = code
        )

        _uiState.update {
            it.copy(
                role = SharingRole.RECEIVER,
                sessionState = SessionState.WAITING_FOR_ACCEPT,
                incomingRequest = mockRequest,
                selectedFiles = files,
                verificationCode = code,
                targetDevice = DiscoveredDevice(
                    id = "DROP-9A14",
                    name = senderName,
                    transportType = TransportType.WIFI_DIRECT
                )
            )
        }
    }

    fun startSimulatedReceiverTransfer(speedMBps: Float = 50f) {
        val files = _uiState.value.selectedFiles.ifEmpty { generateMockFiles() }
        val totalBytes = files.sumOf { it.sizeBytes }
        val code = _uiState.value.verificationCode.ifBlank { SessionCrypto.deriveVerificationCode("DROP-9A14", sessionKey) }

        _uiState.update {
            it.copy(
                sessionState = SessionState.TRANSFERRING,
                transferProgress = TransferProgress(
                    totalFiles = files.size,
                    totalSizeBytes = totalBytes,
                    verificationCode = code,
                    transportType = TransportType.WIFI_DIRECT
                )
            )
        }

        simulationJob?.cancel()
        simulationJob = viewModelScope.launch(Dispatchers.Default) {
            runSimulatedStreamingTransfer(files, speedMBps)
        }
    }

    private suspend fun runSimulatedStreamingTransfer(files: List<TransferFile>, speedMBps: Float) {
        val totalBytes = files.sumOf { it.sizeBytes }
        var totalBytesTransferred = 0L
        val bytesPerSec = (speedMBps * 1024 * 1024).toLong().coerceAtLeast(512 * 1024)
        val tickIntervalMs = 60L
        val bytesPerTick = (bytesPerSec * (tickIntervalMs / 1000.0)).toLong().coerceAtLeast(16 * 1024)

        for (index in files.indices) {
            val file = files[index]
            var fileBytesTransferred = 0L

            _uiState.update { state ->
                val updatedProgress = state.transferProgress.copy(
                    currentFileIndex = index,
                    currentFileName = file.name,
                    currentFileSize = file.sizeBytes,
                    currentFileBytes = 0L,
                    speedBytesPerSec = bytesPerSec
                )
                val updatedFiles = state.selectedFiles.mapIndexed { i, f ->
                    if (i == index) f.copy(status = FileTransferStatus.TRANSFERRING) else f
                }
                state.copy(
                    transferProgress = updatedProgress,
                    selectedFiles = updatedFiles
                )
            }

            while (fileBytesTransferred < file.sizeBytes && coroutineContext.isActive) {
                if (_uiState.value.transferProgress.isPaused) {
                    delay(100)
                    continue
                }
                if (_uiState.value.sessionState == SessionState.CANCELLED) {
                    return
                }

                val increment = bytesPerTick.coerceAtMost(file.sizeBytes - fileBytesTransferred)
                fileBytesTransferred += increment
                totalBytesTransferred += increment

                val remainingTotal = (totalBytes - totalBytesTransferred).coerceAtLeast(0)
                val etaSec = if (bytesPerSec > 0) remainingTotal / bytesPerSec else 0L

                val currentFBytes = fileBytesTransferred
                val currentTBytes = totalBytesTransferred

                _uiState.update { state ->
                    val newProgress = state.transferProgress.copy(
                        currentFileBytes = currentFBytes,
                        totalBytesTransferred = currentTBytes,
                        speedBytesPerSec = bytesPerSec,
                        etaSeconds = etaSec
                    )
                    state.copy(transferProgress = newProgress)
                }

                delay(tickIntervalMs)
            }

            val accessibleUri = file.uri ?: storageManager.createAndSaveDemoFile(file.name, file.mimeType)
            _uiState.update { state ->
                val updatedFiles = state.selectedFiles.mapIndexed { i, f ->
                    if (i == index) f.copy(
                        status = FileTransferStatus.COMPLETED,
                        bytesTransferred = f.sizeBytes,
                        uri = accessibleUri ?: f.uri
                    ) else f
                }
                state.copy(
                    selectedFiles = updatedFiles,
                    completedFilesCount = index + 1
                )
            }
            delay(120)
        }

        _uiState.update { it.copy(sessionState = SessionState.VERIFYING) }
        delay(400)

        hapticHelper.performTransferComplete()
        _uiState.update {
            it.copy(
                sessionState = SessionState.COMPLETED,
                totalTransferredBytes = totalBytes,
                sessionDurationSeconds = (totalBytes / bytesPerSec).coerceAtLeast(2L)
            )
        }
        recordSessionHistory(status = "COMPLETED")
    }

    fun openFile(file: TransferFile) {
        viewModelScope.launch {
            storageManager.openFile(file)
        }
    }

    fun openDownloadsFolder() {
        viewModelScope.launch {
            storageManager.openDownloadsFolder()
        }
    }

    fun resetSessionState() {
        simulationJob?.cancel()
        transferJob?.cancel()
        messageListenerJob?.cancel()
        speedCalcJob?.cancel()
        serverListenJob?.cancel()
        reconnectJob?.cancel()
        cleanupTransport()
        discoveryManager.clear()
        discoveryManager.stopDiscovery()
        discoveryManager.stopAdvertising()
        localHotspotManager.stopLocalHotspot()
        hotspotAutoConnector.release()
        storageManager.clearTempFiles()
        DropSendTransferService.stop(getApplication())

        SessionCrypto.wipeKey(sessionKey)
        sessionKey = SessionCrypto.generateSessionKey()
        sessionToken = SessionCrypto.generateSessionToken()
        ecKeyPair = SessionCrypto.generateEcKeyPair()
        val newId = SessionCrypto.generateTemporaryIdentity()

        _uiState.value = UiState(
            sessionState = SessionState.IDLE,
            role = null,
            localDeviceId = newId,
            localDeviceName = "${Build.MANUFACTURER.replaceFirstChar { it.uppercase() }} ${Build.MODEL}",
            selectedFiles = emptyList(),
            nearbyDevices = emptyList(),
            targetDevice = null,
            incomingRequest = null,
            verificationCode = "",
            transferProgress = TransferProgress(),
            errorMessage = null,
            completedFilesCount = 0,
            totalTransferredBytes = 0L,
            sessionDurationSeconds = 0L
        )
    }

    private fun cleanupTransport() {
        viewModelScope.launch(Dispatchers.IO) {
            try {
                activeTransport?.disconnect()
            } catch (_: Exception) {}
            activeTransport = null
        }
    }

    override fun onCleared() {
        super.onCleared()
        resetSessionState()
    }
}
