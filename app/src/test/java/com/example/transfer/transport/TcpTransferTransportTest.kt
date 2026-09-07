package com.example.transfer.transport

import com.example.domain.model.TransportType
import com.example.transfer.protocol.ProtocolMessage
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.BufferedInputStream
import java.io.IOException
import java.net.ServerSocket
import java.net.Socket

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class TcpTransferTransportTest {

    private var serverSocket: ServerSocket? = null
    private var clientTransport: TcpTransferTransport? = null

    @After
    fun tearDown() = runBlocking {
        clientTransport?.disconnect()
        serverSocket?.close()
        serverSocket = null
        clientTransport = null
    }

    @Test
    fun `test send flushes BufferedOutputStream immediately for small messages`() = runBlocking {
        // A small message (like Ping or Handshake) is well below the 128KB buffer.
        // If out.flush() is NOT called, the bytes remain buffered in memory and the server
        // will block waiting on readFromStream.
        val server = ServerSocket(0)
        serverSocket = server
        val port = server.localPort

        val transport = TcpTransferTransport(TransportType.LOCAL_WIFI)
        clientTransport = transport

        val serverAcceptedDeferred = async(Dispatchers.IO) {
            val socket = server.accept()
            val stream = BufferedInputStream(socket.getInputStream())
            val receivedMsg = withTimeout(3000) {
                ProtocolMessage.readFromStream(stream)
            }
            socket.close()
            receivedMsg
        }

        transport.connect("127.0.0.1", port)
        assertTrue(transport.isConnected())

        // Send a small message
        transport.send(ProtocolMessage.SessionClose)

        val received = serverAcceptedDeferred.await()
        assertNotNull(received)
        assertTrue(received is ProtocolMessage.SessionClose)
    }

    @Test
    fun `test handshake messages are flushed immediately across transport`() = runBlocking {
        val server = ServerSocket(0)
        serverSocket = server
        val port = server.localPort

        val transport = TcpTransferTransport(TransportType.LOCAL_WIFI)
        clientTransport = transport

        val serverAcceptedDeferred = async(Dispatchers.IO) {
            val socket = server.accept()
            val stream = BufferedInputStream(socket.getInputStream())
            val received = withTimeout(3000) {
                ProtocolMessage.readFromStream(stream)
            }
            socket.close()
            received
        }

        transport.connect("127.0.0.1", port)

        val handshake = ProtocolMessage.AuthHandshake(
            senderId = "REV-TEST",
            sessionToken = "token-12345",
            publicKeyBase64 = "pk_test_base64",
        )
        transport.send(handshake)

        val received = serverAcceptedDeferred.await()
        assertNotNull(received)
        assertTrue(received is ProtocolMessage.AuthHandshake)
        assertEquals("REV-TEST", (received as ProtocolMessage.AuthHandshake).senderId)
        assertEquals("token-12345", received.sessionToken)
    }

    @Test
    fun `test TCP connection failure cleans up resources`() = runBlocking {
        // Pick an unused port without binding a server
        val tempServer = ServerSocket(0)
        val unusedPort = tempServer.localPort
        tempServer.close()

        val transport = TcpTransferTransport(TransportType.LOCAL_WIFI)
        clientTransport = transport

        try {
            transport.connect("127.0.0.1", unusedPort)
            fail("Expected IOException when connecting to closed port")
        } catch (e: IOException) {
            // Expected
        }

        assertFalse(transport.isConnected())
    }

    @Test
    fun `test reconnect and disconnect lifecycle cleans up and restarts read loop`() = runBlocking {
        val server1 = ServerSocket(0)
        val port1 = server1.localPort

        val transport = TcpTransferTransport(TransportType.LOCAL_WIFI)
        clientTransport = transport

        val server1Job = async(Dispatchers.IO) {
            val socket = server1.accept()
            socket.close()
        }

        transport.connect("127.0.0.1", port1)
        assertTrue(transport.isConnected())
        server1Job.await()
        server1.close()

        transport.disconnect()
        assertFalse(transport.isConnected())

        // Reconnect to a second server
        val server2 = ServerSocket(0)
        val port2 = server2.localPort
        val server2Job = async(Dispatchers.IO) {
            val socket = server2.accept()
            socket.close()
        }

        transport.connect("127.0.0.1", port2)
        assertTrue(transport.isConnected())
        server2Job.await()
        server2.close()

        transport.disconnect()
        assertFalse(transport.isConnected())
    }

    @Test
    fun `test concurrent send calls do not interleave or corrupt framing`() = runBlocking {
        val server = ServerSocket(0)
        serverSocket = server
        val port = server.localPort

        val transport = TcpTransferTransport(TransportType.LOCAL_WIFI)
        clientTransport = transport

        val messageCount = 50
        val serverReceiverJob = async(Dispatchers.IO) {
            val socket = server.accept()
            val stream = BufferedInputStream(socket.getInputStream())
            val received = mutableListOf<ProtocolMessage>()
            repeat(messageCount) {
                val msg = ProtocolMessage.readFromStream(stream)
                if (msg != null) received.add(msg)
            }
            socket.close()
            received
        }

        transport.connect("127.0.0.1", port)

        // Launch concurrent sends
        val sendJobs = (1..messageCount).map { i ->
            async(Dispatchers.IO) {
                transport.send(ProtocolMessage.SessionReject(reason = "Message-$i"))
            }
        }
        sendJobs.awaitAll()

        val receivedMessages = serverReceiverJob.await()
        assertEquals(messageCount, receivedMessages.size)
        assertTrue(receivedMessages.all { it is ProtocolMessage.SessionReject })
    }
}
