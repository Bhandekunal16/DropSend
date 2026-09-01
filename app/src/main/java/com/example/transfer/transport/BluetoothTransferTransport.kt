package com.example.transfer.transport

import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothServerSocket
import android.bluetooth.BluetoothSocket
import android.util.Log
import com.example.domain.model.TransportType
import com.example.transfer.protocol.ProtocolMessage
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.withContext
import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.util.UUID

class BluetoothTransferTransport(
    private val bluetoothAdapter: BluetoothAdapter?
) : TransferTransport {

    companion object {
        private const val TAG = "BluetoothTransport"
        val DROPSEND_BT_UUID: UUID = UUID.fromString("fa87c0d0-afac-11de-8a39-0800200c9a66")
        private const val BUFFER_SIZE = 32 * 1024 // 32 KB for Bluetooth RFCOMM
    }

    override val transportType: TransportType = TransportType.BLUETOOTH

    private var serverSocket: BluetoothServerSocket? = null
    private var activeSocket: BluetoothSocket? = null
    private var inputStream: BufferedInputStream? = null
    private var outputStream: BufferedOutputStream? = null

    private val _incomingMessages = MutableSharedFlow<ProtocolMessage>(
        replay = 0,
        extraBufferCapacity = 64,
        onBufferOverflow = BufferOverflow.SUSPEND
    )

    @Volatile
    private var isRunning = false

    @SuppressLint("MissingPermission")
    override suspend fun connect(targetAddress: String, port: Int) = withContext(Dispatchers.IO) {
        disconnect()
        val adapter = bluetoothAdapter ?: throw IllegalStateException("Bluetooth is not available")
        val device: BluetoothDevice = adapter.getRemoteDevice(targetAddress)
        
        Log.d(TAG, "Connecting to Bluetooth RFCOMM device ${device.address}...")
        adapter.cancelDiscovery()

        var socket: BluetoothSocket? = null
        // 1. Try Insecure RFCOMM (bypasses mandatory OS pairing popup)
        try {
            socket = device.createInsecureRfcommSocketToServiceRecord(DROPSEND_BT_UUID)
            socket.connect()
        } catch (e: Exception) {
            Log.w(TAG, "Insecure RFCOMM failed (${e.message}), trying secure RFCOMM...")
            try {
                socket?.close()
            } catch (_: Exception) {}

            // 2. Fallback to standard RFCOMM
            socket = device.createRfcommSocketToServiceRecord(DROPSEND_BT_UUID)
            socket.connect()
        }

        activeSocket = socket
        inputStream = BufferedInputStream(socket.inputStream, BUFFER_SIZE)
        outputStream = BufferedOutputStream(socket.outputStream, BUFFER_SIZE)
        isRunning = true

        startReadLoop()
    }

    @SuppressLint("MissingPermission")
    override suspend fun startServer(port: Int): Int = withContext(Dispatchers.IO) {
        disconnect()
        val adapter = bluetoothAdapter ?: throw IllegalStateException("Bluetooth is not available")
        Log.d(TAG, "Starting Bluetooth RFCOMM server...")
        try {
            serverSocket = adapter.listenUsingInsecureRfcommWithServiceRecord("DropSend", DROPSEND_BT_UUID)
        } catch (e: Exception) {
            Log.w(TAG, "Insecure server socket failed (${e.message}), trying secure RFCOMM server...")
            serverSocket = adapter.listenUsingRfcommWithServiceRecord("DropSend", DROPSEND_BT_UUID)
        }
        0
    }

    override suspend fun acceptConnection() = withContext(Dispatchers.IO) {
        val server = serverSocket ?: throw IllegalStateException("Bluetooth Server is not initialized")
        Log.d(TAG, "Waiting for incoming Bluetooth RFCOMM connection...")
        val socket = server.accept()

        activeSocket = socket
        inputStream = BufferedInputStream(socket.inputStream, BUFFER_SIZE)
        outputStream = BufferedOutputStream(socket.outputStream, BUFFER_SIZE)
        isRunning = true

        startReadLoop()
    }

    override suspend fun send(message: ProtocolMessage) = withContext(Dispatchers.IO) {
        val out = outputStream ?: throw IllegalStateException("Bluetooth output stream unavailable")
        synchronized(out) {
            message.writeToStream(out)
        }
    }

    override fun incomingMessages(): Flow<ProtocolMessage> = _incomingMessages.asSharedFlow()

    private fun startReadLoop() {
        Thread({
            val stream = inputStream
            try {
                while (isRunning && stream != null) {
                    val message = ProtocolMessage.readFromStream(stream)
                    if (message != null) {
                        _incomingMessages.tryEmit(message)
                    } else {
                        break
                    }
                }
            } catch (e: Exception) {
                if (isRunning) {
                    Log.w(TAG, "Bluetooth read loop closed: ${e.message}")
                }
            } finally {
                isRunning = false
            }
        }, "DropSend-BluetoothReader").start()
    }

    override suspend fun disconnect() = withContext(Dispatchers.IO) {
        isRunning = false
        try {
            inputStream?.close()
        } catch (_: Exception) {}
        try {
            outputStream?.close()
        } catch (_: Exception) {}
        try {
            activeSocket?.close()
        } catch (_: Exception) {}
        try {
            serverSocket?.close()
        } catch (_: Exception) {}

        inputStream = null
        outputStream = null
        activeSocket = null
        serverSocket = null
    }

    override fun isConnected(): Boolean {
        return isRunning && activeSocket?.isConnected == true
    }
}
