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
import java.util.concurrent.atomic.AtomicLong

class TcpTransferTransport(
    override val transportType: TransportType = TransportType.LOCAL_WIFI,
) : TransferTransport {
    companion object {
        private const val TAG = "TcpTransferTransport"
        private const val BUFFER_SIZE = 128 * 1024 // 128 KB
        private const val SOCKET_TIMEOUT_MS = 30_000
        private const val CONNECT_TIMEOUT_MS = 5_000
    }

    private var serverSocket: ServerSocket? = null
    private var activeSocket: Socket? = null
    private var inputStream: BufferedInputStream? = null
    private var outputStream: BufferedOutputStream? = null

    private val _incomingMessages =
        MutableSharedFlow<ProtocolMessage>(
            replay = 0,
            extraBufferCapacity = 64,
            onBufferOverflow = BufferOverflow.SUSPEND,
        )

    @Volatile
    private var isRunning = false

    private val connectionGeneration = AtomicLong(0)

    override suspend fun connect(
        targetAddress: String,
        port: Int,
    ) = withContext(Dispatchers.IO) {
        disconnect()
        val generation = connectionGeneration.incrementAndGet()
        Log.d(TAG, "Attempting TCP connection to $targetAddress:$port ($transportType)...")

        val socket = Socket()
        try {
            socket.tcpNoDelay = true
            socket.keepAlive = true
            socket.sendBufferSize = BUFFER_SIZE
            socket.receiveBufferSize = BUFFER_SIZE
            socket.soTimeout = SOCKET_TIMEOUT_MS
            socket.connect(InetSocketAddress(targetAddress, port), CONNECT_TIMEOUT_MS)
            Log.d(TAG, "TCP connection successfully established to $targetAddress:$port")

            activeSocket = socket
            val inStream = BufferedInputStream(socket.getInputStream(), BUFFER_SIZE)
            val outStream = BufferedOutputStream(socket.getOutputStream(), BUFFER_SIZE)
            inputStream = inStream
            outputStream = outStream
            isRunning = true

            startReadLoop(socket, inStream, generation)
        } catch (e: Exception) {
            Log.e(TAG, "TCP connection failed to $targetAddress:$port", e)
            try {
                socket.close()
            } catch (_: Exception) {
            }
            cleanupConnectionResources()
            throw e
        }
    }

    override suspend fun startServer(port: Int): Int =
        withContext(Dispatchers.IO) {
            disconnect()
            Log.d(TAG, "Starting TCP server on port $port...")
            val server = ServerSocket()
            server.reuseAddress = true
            server.bind(InetSocketAddress(port))
            serverSocket = server
            Log.d(TAG, "TCP server bound to local port ${server.localPort}")
            server.localPort
        }

    override suspend fun acceptConnection() =
        withContext(Dispatchers.IO) {
            val server = serverSocket ?: throw IllegalStateException("Server socket is not initialized")
            Log.d(TAG, "Waiting for client connection on port ${server.localPort}...")
            val socket = server.accept()
            val generation = connectionGeneration.incrementAndGet()
            try {
                socket.tcpNoDelay = true
                socket.keepAlive = true
                socket.sendBufferSize = BUFFER_SIZE
                socket.receiveBufferSize = BUFFER_SIZE
                socket.soTimeout = SOCKET_TIMEOUT_MS

                activeSocket = socket
                val inStream = BufferedInputStream(socket.getInputStream(), BUFFER_SIZE)
                val outStream = BufferedOutputStream(socket.getOutputStream(), BUFFER_SIZE)
                inputStream = inStream
                outputStream = outStream
                isRunning = true

                Log.d(TAG, "Client connected: ${socket.inetAddress?.hostAddress}:${socket.port}")
                startReadLoop(socket, inStream, generation)
            } catch (e: Exception) {
                Log.e(TAG, "Failed initializing accepted client connection", e)
                try {
                    socket.close()
                } catch (_: Exception) {
                }
                cleanupConnectionResources()
                throw e
            }
        }

    override suspend fun send(message: ProtocolMessage): Unit =
        withContext(Dispatchers.IO) {
            val out = outputStream ?: throw IllegalStateException("Socket output stream is not available")
            val messageType = message.javaClass.simpleName
            Log.d(TAG, "Sending protocol message: $messageType")
            synchronized(out) {
                message.writeToStream(out)
                out.flush()
            }
            Log.d(TAG, "Flushed protocol message: $messageType")
            Unit
        }

    override fun incomingMessages(): Flow<ProtocolMessage> = _incomingMessages.asSharedFlow()

    private fun startReadLoop(
        socket: Socket,
        stream: BufferedInputStream,
        generation: Long,
    ) {
        Thread({
            try {
                while (isRunning && connectionGeneration.get() == generation && !socket.isClosed) {
                    val message = ProtocolMessage.readFromStream(stream)
                    if (message != null) {
                        Log.d(TAG, "Received protocol message: ${message.javaClass.simpleName}")
                        _incomingMessages.tryEmit(message)
                    } else {
                        Log.d(TAG, "Read loop reached EOF (remote connection closed)")
                        break
                    }
                }
            } catch (e: Exception) {
                if (isRunning && connectionGeneration.get() == generation && !socket.isClosed) {
                    Log.w(TAG, "Read loop terminated unexpectedly with exception", e)
                } else {
                    Log.d(TAG, "Read loop closed after disconnect (${e.javaClass.simpleName}: ${e.message})")
                }
            } finally {
                if (connectionGeneration.get() == generation) {
                    isRunning = false
                }
            }
        }, "DropSend-TcpReader-$generation").apply { isDaemon = true }.start()
    }

    override suspend fun disconnect() =
        withContext(Dispatchers.IO) {
            connectionGeneration.incrementAndGet()
            isRunning = false
            cleanupConnectionResources()
            try {
                serverSocket?.close()
            } catch (_: Exception) {
            }
            serverSocket = null
        }

    private fun cleanupConnectionResources() {
        try {
            inputStream?.close()
        } catch (_: Exception) {
        }
        try {
            outputStream?.close()
        } catch (_: Exception) {
        }
        try {
            activeSocket?.close()
        } catch (_: Exception) {
        }

        inputStream = null
        outputStream = null
        activeSocket = null
    }

    override fun isConnected(): Boolean = isRunning && activeSocket?.isConnected == true && activeSocket?.isClosed == false
}
