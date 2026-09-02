package com.example

import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.example.domain.model.SessionState
import com.example.domain.model.SharingRole
import com.example.domain.model.TransferFile
import com.example.presentation.DropSendViewModel
import com.example.presentation.components.RealtimeConnectionStatusBar
import com.example.presentation.components.rememberDropSendPermissionState
import com.example.presentation.history.HistorySheet
import com.example.presentation.home.HomeScreen
import com.example.presentation.receive.ReceiveFlowScreen
import com.example.presentation.send.SendFlowScreen
import com.example.presentation.success.SuccessScreen
import com.example.presentation.transfer.TransferScreen
import com.example.ui.theme.MyApplicationTheme

class MainActivity : ComponentActivity() {

    private val viewModel: DropSendViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        handleIncomingIntent(intent)

        setContent {
            val currentPalette by viewModel.currentPalette.collectAsStateWithLifecycle()
            val darkModePreference by viewModel.darkModePreference.collectAsStateWithLifecycle()

            MyApplicationTheme(
                palette = currentPalette,
                darkModePreference = darkModePreference
            ) {
                DropSendApp(viewModel = viewModel)
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handleIncomingIntent(intent)
    }

    private fun handleIncomingIntent(intent: Intent?) {
        intent ?: return
        when (intent.action) {
            Intent.ACTION_SEND -> {
                val uri = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                    intent.getParcelableExtra(Intent.EXTRA_STREAM, Uri::class.java)
                } else {
                    @Suppress("DEPRECATION")
                    intent.getParcelableExtra(Intent.EXTRA_STREAM)
                }
                if (uri != null) {
                    viewModel.selectFiles(listOf(uri))
                }
            }
            Intent.ACTION_SEND_MULTIPLE -> {
                val uris = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                    intent.getParcelableArrayListExtra(Intent.EXTRA_STREAM, Uri::class.java)
                } else {
                    @Suppress("DEPRECATION")
                    intent.getParcelableArrayListExtra(Intent.EXTRA_STREAM)
                }
                if (!uris.isNullOrEmpty()) {
                    viewModel.selectFiles(uris)
                }
            }
        }
    }
}

@Composable
fun DropSendApp(viewModel: DropSendViewModel) {
    val context = LocalContext.current
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    val connectivityState by viewModel.connectivityMonitor.state.collectAsStateWithLifecycle()
    val localHotspotInfo by viewModel.localHotspotInfo.collectAsStateWithLifecycle()
    val transferHistory by viewModel.transferHistory.collectAsStateWithLifecycle()

    var showHistorySheet by remember { mutableStateOf(false) }

    val requestPermissions = rememberDropSendPermissionState(context) {
        // Permissions granted
        viewModel.refreshConnectivity()
    }

    LaunchedEffect(Unit) {
        requestPermissions()
    }

    Scaffold(
        modifier = Modifier.fillMaxSize(),
        topBar = {
            RealtimeConnectionStatusBar(
                sessionState = uiState.sessionState,
                targetDeviceName = uiState.targetDevice?.name,
                incomingSenderName = uiState.incomingRequest?.senderName,
                role = uiState.role,
                transportType = uiState.transferProgress.transportType,
                isWifiOn = connectivityState.isWifiOn,
                isBluetoothOn = connectivityState.isBluetoothOn,
                isHotspotActive = localHotspotInfo.isActive,
                showRadioPills = true
            )
        }
    ) { innerPadding ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(MaterialTheme.colorScheme.background)
                .padding(innerPadding)
        ) {
            AnimatedContent(
                targetState = uiState.sessionState to uiState.role,
                transitionSpec = { fadeIn() togetherWith fadeOut() },
                label = "screen_navigation"
            ) { (state, role) ->
                when {
                    // SUCCESS SCREEN
                    state == SessionState.COMPLETED -> {
                        SuccessScreen(
                            role = role,
                            completedCount = uiState.completedFilesCount,
                            totalBytes = uiState.totalTransferredBytes,
                            files = uiState.selectedFiles,
                            onOpenFile = { file -> viewModel.openFile(file) },
                            onOpenDownloadsFolder = { viewModel.openDownloadsFolder() },
                            onSendMore = { viewModel.resetSessionState() },
                            onDone = { viewModel.resetSessionState() }
                        )
                    }

                    // ACTIVE TRANSFER SCREEN
                    state == SessionState.TRANSFERRING || state == SessionState.VERIFYING -> {
                        TransferScreen(
                            role = role ?: SharingRole.SENDER,
                            targetName = uiState.targetDevice?.name ?: uiState.incomingRequest?.senderName ?: "Nearby Device",
                            files = uiState.selectedFiles,
                            progress = uiState.transferProgress,
                            onPauseResume = {
                                if (uiState.transferProgress.isPaused) viewModel.resumeTransfer()
                                else viewModel.pauseTransfer()
                            },
                            onCancel = { viewModel.cancelTransfer() },
                            onOpenFile = { file -> viewModel.openFile(file) }
                        )
                    }

                    // RECEIVER FLOW (WAITING OR REQUEST MODAL)
                    role == SharingRole.RECEIVER -> {
                        ReceiveFlowScreen(
                            localDeviceId = uiState.localDeviceId,
                            localDeviceName = uiState.localDeviceName,
                            localIpAddresses = uiState.localIpAddresses,
                            localHotspotInfo = localHotspotInfo,
                            incomingRequest = uiState.incomingRequest,
                            incomingFiles = uiState.selectedFiles,
                            verificationCode = uiState.verificationCode,
                            onAccept = { viewModel.acceptIncomingRequest() },
                            onDecline = { viewModel.declineIncomingRequest() },
                            onCancel = { viewModel.resetSessionState() },
                            onSimulateInboundTransfer = { viewModel.launchReceiverSimulation() }
                        )
                    }

                    // SENDER FLOW (REVIEW FILES OR DISCOVERING PEERS)
                    role == SharingRole.SENDER && uiState.selectedFiles.isNotEmpty() -> {
                        SendFlowScreen(
                            files = uiState.selectedFiles,
                            nearbyDevices = uiState.nearbyDevices,
                            sessionState = state,
                            localDeviceId = uiState.localDeviceId,
                            statusMessage = uiState.statusMessage,
                            onAddMoreFiles = { uris -> viewModel.selectFiles(uiState.selectedFiles.mapNotNull { it.uri } + uris) },
                            onRemoveFile = { fileId -> viewModel.removeSelectedFile(fileId) },
                            onStartDiscovery = {
                                requestPermissions()
                                viewModel.startSenderDiscovery()
                            },
                            onSelectDevice = { device -> viewModel.connectToDevice(device) },
                            onConnectViaQrPayload = { qr -> viewModel.connectViaQrPayload(qr) },
                            onRescan = { viewModel.rescanDevices() },
                            onDirectIpConnect = { ip -> viewModel.connectToDirectIp(ip) },
                            onAddDemoPeer = { viewModel.addDemoPeer() },
                            onCancel = { viewModel.resetSessionState() }
                        )
                    }

                    // DEFAULT HOME SCREEN
                    else -> {
                        val currentPalette by viewModel.currentPalette.collectAsStateWithLifecycle()
                        val darkModePreference by viewModel.darkModePreference.collectAsStateWithLifecycle()

                        HomeScreen(
                            localDeviceId = uiState.localDeviceId,
                            connectivityState = connectivityState,
                            currentPalette = currentPalette,
                            darkModePreference = darkModePreference,
                            onPaletteSelected = { palette -> viewModel.setPalette(palette) },
                            onDarkModeSelected = { mode -> viewModel.setDarkModePreference(mode) },
                            onFilesSelected = { uris ->
                                requestPermissions()
                                viewModel.selectFiles(uris)
                            },
                            onReceiveClick = {
                                requestPermissions()
                                viewModel.startReceiverMode()
                            },
                            onShowHistory = { showHistorySheet = true },
                            onLaunchSenderSimulation = { device, speed ->
                                viewModel.launchSenderSimulation(targetDevice = device, speedMBps = speed)
                            },
                            onLaunchReceiverSimulation = { name, speed ->
                                viewModel.launchReceiverSimulation(senderName = name, speedMBps = speed)
                            },
                            onPopulateDemoPeers = {
                                viewModel.populateDemoPeers()
                            }
                        )
                    }
                }
            }

            if (showHistorySheet) {
                HistorySheet(
                    historyList = transferHistory,
                    onDismiss = { showHistorySheet = false },
                    onClearHistory = { viewModel.clearHistory() },
                    onDeleteItem = { id -> viewModel.deleteHistoryItem(id) },
                    onOpenFile = { entity ->
                        val uri = entity.fileUriString?.let { Uri.parse(it) }
                        val file = TransferFile(
                            id = entity.sessionId,
                            name = entity.fileName,
                            mimeType = entity.mimeType,
                            sizeBytes = entity.sizeBytes,
                            uri = uri
                        )
                        viewModel.openFile(file)
                    }
                )
            }

            // Structured Error / Alert Dialog (P3-B-04)
            if (uiState.errorMessage != null && uiState.sessionState != SessionState.COMPLETED) {
                val isRecoverableState = uiState.sessionState == SessionState.FAILED || uiState.sessionState == SessionState.DISCONNECTED
                AlertDialog(
                    onDismissRequest = { viewModel.resetSessionState() },
                    title = {
                        Text(
                            text = "Transfer Alert",
                            style = MaterialTheme.typography.titleMedium.copy(
                                fontWeight = FontWeight.Bold
                            )
                        )
                    },
                    text = {
                        Column(
                            verticalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            Text(
                                text = uiState.errorMessage ?: "An unexpected error occurred during transfer.",
                                style = MaterialTheme.typography.bodyMedium
                            )
                            Box(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clip(RoundedCornerShape(8.dp))
                                    .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f))
                                    .padding(8.dp)
                            ) {
                                Text(
                                    text = "Security Notice: All incomplete or unverified temporary files were safely cleaned up. No untrusted data was written to storage.",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                        }
                    },
                    confirmButton = {
                        Button(
                            onClick = {
                                if (isRecoverableState && uiState.selectedFiles.isNotEmpty()) {
                                    viewModel.resumeTransfer()
                                } else {
                                    viewModel.resetSessionState()
                                }
                            }
                        ) {
                            Text(if (isRecoverableState && uiState.selectedFiles.isNotEmpty()) "Retry" else "OK")
                        }
                    },
                    dismissButton = {
                        if (isRecoverableState && uiState.selectedFiles.isNotEmpty()) {
                            OutlinedButton(onClick = { viewModel.resetSessionState() }) {
                                Text("Dismiss")
                            }
                        }
                    }
                )
            }
        }
    }
}
