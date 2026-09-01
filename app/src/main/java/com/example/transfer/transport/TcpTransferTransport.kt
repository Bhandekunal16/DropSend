package com.example.transfer.transport

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
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket

class TcpTransferTransport(
    override val transportType: TransportType = TransportType.LOCAL_WIFI
) : TransferTransport {

    companion object {
        private const val TAG = "TcpTransferTransport"
        private const val BUFFER_SIZE = 128 * 1024 // 128 KB
        private const val SOCKET_TIMEOUT_MS = 30_000
    }

    private var serverSocket: ServerSocket? = null
    private var activeSocket: Socket? = null
    private var inputStream: BufferedInputStream? = null
    private var outputStream: BufferedOutputStream? = null

    private val _incomingMessages = MutableSharedFlow<ProtocolMessage>(
        replay = 0,
        extraBufferCapacity = 64,
        onBufferOverflow = BufferOverflow.SUSPEND
    )

    @Volatile
    private var isRunning = false

    override suspend fun connect(targetAddress: String, port: Int) = withContext(Dispatchers.IO) {
        disconnect()
        Log.d(TAG, "Connecting to $targetAddress:$port ($transportType)...")
        val socket = Socket()
        socket.tcpNoDelay = true
        socket.sendBufferSize = BUFFER_SIZE
        socket.receiveBufferSize = BUFFER_SIZE
        socket.soTimeout = SOCKET_TIMEOUT_MS
        socket.connect(InetSocketAddress(targetAddress, port), 10_000)

        activeSocket = socket
        inputStream = BufferedInputStream(socket.getInputStream(), BUFFER_SIZE)
        outputStream = BufferedOutputStream(socket.getOutputStream(), BUFFER_SIZE)
        isRunning = true

        startReadLoop()
    }

    override suspend fun startServer(port: Int): Int = withContext(Dispatchers.IO) {
        disconnect()
        Log.d(TAG, "Starting TCP server on port $port...")
        val server = ServerSocket(port)
        server.reuseAddress = true
        serverSocket = server
        server.localPort
    }

    override suspend fun acceptConnection() = withContext(Dispatchers.IO) {
        val server = serverSocket ?: throw IllegalStateException("Server socket is not initialized")
        Log.d(TAG, "Waiting for client connection on port ${server.localPort}...")
        val socket = server.accept()
        socket.tcpNoDelay = true
        socket.sendBufferSize = BUFFER_SIZE
        socket.receiveBufferSize = BUFFER_SIZE
        socket.soTimeout = SOCKET_TIMEOUT_MS

        activeSocket = socket
        inputStream = BufferedInputStream(socket.getInputStream(), BUFFER_SIZE)
        outputStream = BufferedOutputStream(socket.getOutputStream(), BUFFER_SIZE)
        isRunning = true

        Log.d(TAG, "Client connected: ${socket.inetAddress.hostAddress}")
        startReadLoop()
    }

    override suspend fun send(message: ProtocolMessage) = withContext(Dispatchers.IO) {
        val out = outputStream ?: throw IllegalStateException("Socket output stream is not available")
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
                        // End of stream or connection closed
                        break
                    }
                }
            } catch (e: Exception) {
                if (isRunning) {
                    Log.w(TAG, "Read loop terminated with error: ${e.message}")
                }
            } finally {
                isRunning = false
            }
        }, "DropSend-TcpReader").start()
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
        return isRunning && activeSocket?.isConnected == true && activeSocket?.isClosed == false
    }
}
