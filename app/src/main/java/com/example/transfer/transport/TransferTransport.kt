package com.example.transfer.transport

import com.example.domain.model.TransportType
import com.example.transfer.protocol.ProtocolMessage
import kotlinx.coroutines.flow.Flow

interface TransferTransport {
    val transportType: TransportType

    /**
     * Connect as a sender/client to the target host and port or Bluetooth device
     */
    suspend fun connect(targetAddress: String, port: Int = 8888)

    /**
     * Start listening as a receiver/server on the local network/socket
     */
    suspend fun startServer(port: Int = 8888): Int

    /**
     * Accept incoming client connection
     */
    suspend fun acceptConnection()

    /**
     * Send a single protocol message
     */
    suspend fun send(message: ProtocolMessage)

    /**
     * Read stream of incoming protocol messages
     */
    fun incomingMessages(): Flow<ProtocolMessage>

    /**
     * Disconnect and release all underlying sockets and resources
     */
    suspend fun disconnect()

    /**
     * Check if currently connected
     */
    fun isConnected(): Boolean
}
