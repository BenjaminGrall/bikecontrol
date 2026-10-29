package io.github.benjamingrall.click2whoosh.core

import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit

class ObcTcpServerTest {
    private val events = LinkedBlockingQueue<String>()
    private val listener = object : ObcTcpServer.Listener {
        override fun onClientsChanged() {
            events += "clients"
        }

        override fun onLog(message: String) {}
        override fun onDebug(message: String) {}
    }
    private val server = ObcTcpServer(0, listener)

    @After
    fun tearDown() = server.stop()

    private fun Socket.readExactly(n: Int): ByteArray {
        val out = ByteArray(n)
        var read = 0
        while (read < n) {
            val r = getInputStream().read(out, read, n - read)
            check(r >= 0) { "fermé" }
            read += r
        }
        return out
    }

    private fun awaitClients() = assertEquals("clients", events.poll(5, TimeUnit.SECONDS))

    @Test
    fun statusOnConnectThenButtonsAndAppInfo() {
        server.start()
        Socket("127.0.0.1", server.port).use { app ->
            app.soTimeout = 5000
            assertArrayEquals(Obc.status(), app.readExactly(3))
            awaitClients()

            // App Info envoyé en deux morceaux.
            val info = byteArrayOf(0x04, 0x01, 0x08) + "mywhoosh".toByteArray() +
                byteArrayOf(0x03) + "5.7".toByteArray() + byteArrayOf(0x02, 0x01, 0x02)
            app.getOutputStream().write(info.copyOf(5))
            app.getOutputStream().write(info.copyOfRange(5, info.size))
            awaitClients()
            assertEquals(AppInfo("mywhoosh", "5.7", setOf(1, 2)), server.clientList.single().appInfo)

            val msg = Obc.buttons(listOf(ButtonChange(Obc.SHIFT_UP, true)))
            server.broadcast(msg)
            assertArrayEquals(msg, app.readExactly(3))
        }
        awaitClients()
        assertTrue(server.clientList.isEmpty())
    }

    @Test
    fun fallsBackToFreePortWhenTaken() {
        ServerSocket(0).use { taken ->
            val s = ObcTcpServer(taken.localPort, listener)
            s.start()
            try {
                assertNotEquals(taken.localPort, s.port)
                assertTrue(s.port > 0)
            } finally {
                s.stop()
            }
        }
    }
}
