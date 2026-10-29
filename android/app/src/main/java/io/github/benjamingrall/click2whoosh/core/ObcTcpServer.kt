package io.github.benjamingrall.click2whoosh.core

import java.io.IOException
import java.net.BindException
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.RejectedExecutionException
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.TimeUnit
import kotlin.concurrent.thread

/**
 * Serveur TCP OpenBikeControl : l'app (MyWhoosh) s'y connecte après l'avoir trouvé par mDNS.
 * Un thread de lecture par client, écritures via un executor par client, statut toutes les 30 s.
 * Les callbacks du [Listener] arrivent sur les threads du serveur.
 */
class ObcTcpServer(private val preferredPort: Int, private val listener: Listener) {

    interface Listener {
        /** Connexion, déconnexion ou App Info reçue. */
        fun onClientsChanged()
        fun onLog(message: String)
        fun onDebug(message: String)
    }

    class Client internal constructor(internal val socket: Socket) {
        val address: String = socket.inetAddress?.hostAddress ?: "?"
        @Volatile var appInfo: AppInfo? = null
            internal set
        internal val writer: ExecutorService = Executors.newSingleThreadExecutor()
    }

    private val clients = CopyOnWriteArrayList<Client>()
    @Volatile private var server: ServerSocket? = null
    private var heartbeat: ScheduledExecutorService? = null

    val clientList: List<Client> get() = clients.toList()

    /** Port réellement ouvert (celui demandé, ou un port libre s'il était pris). */
    val port: Int get() = server?.localPort ?: -1

    fun start() {
        val s = try {
            bind(preferredPort)
        } catch (e: BindException) {
            listener.onLog("Port $preferredPort occupé (BikeControl ouvert ?), port libre utilisé à la place")
            bind(0)
        }
        server = s
        thread(name = "obc-accept", isDaemon = true) { acceptLoop(s) }
        heartbeat = Executors.newSingleThreadScheduledExecutor().also {
            it.scheduleWithFixedDelay({ broadcast(Obc.status()) }, STATUS_PERIOD_S, STATUS_PERIOD_S, TimeUnit.SECONDS)
        }
    }

    fun stop() {
        heartbeat?.shutdownNow()
        heartbeat = null
        val s = server
        server = null
        try {
            s?.close()
        } catch (_: IOException) {
        }
        clients.forEach { close(it) }
    }

    fun broadcast(message: ByteArray) {
        for (c in clients) send(c, message)
    }

    private fun bind(port: Int) = ServerSocket().apply {
        reuseAddress = true
        try {
            bind(InetSocketAddress(port))
        } catch (e: IOException) {
            close()
            throw e
        }
    }

    private fun send(c: Client, message: ByteArray) {
        try {
            c.writer.execute {
                try {
                    c.socket.getOutputStream().apply {
                        write(message)
                        flush()
                    }
                } catch (_: IOException) {
                    close(c)
                }
            }
        } catch (_: RejectedExecutionException) {
            // Client déjà fermé.
        }
    }

    private fun acceptLoop(s: ServerSocket) {
        while (server === s) {
            val socket = try {
                s.accept()
            } catch (e: IOException) {
                if (server === s) listener.onLog("Serveur TCP arrêté : ${e.message}")
                return
            }
            socket.tcpNoDelay = true
            val c = Client(socket)
            clients += c
            listener.onLog("App connectée depuis ${c.address}")
            send(c, Obc.status())
            listener.onClientsChanged()
            thread(name = "obc-client", isDaemon = true) { readLoop(c) }
        }
    }

    private fun readLoop(c: Client) {
        val parser = AppStreamParser()
        val buf = ByteArray(512)
        try {
            val input = c.socket.getInputStream()
            while (true) {
                val n = input.read(buf)
                if (n < 0) break
                listener.onDebug("App -> ${buf.hex(n)}")
                for (m in parser.feed(buf, n)) handle(c, m)
            }
        } catch (_: IOException) {
        }
        close(c)
    }

    private fun handle(c: Client, m: AppMessage) {
        when (m) {
            is AppMessage.Info -> {
                c.appInfo = m.info
                listener.onLog("App Info : ${m.info}")
                listener.onClientsChanged()
            }
            is AppMessage.Haptic -> listener.onDebug("Haptique ignoré (motif ${m.pattern})")
            is AppMessage.Unknown -> listener.onDebug("Message inconnu ignoré : ${m.bytes.hex()}")
        }
    }

    private fun close(c: Client) {
        if (!clients.remove(c)) return
        c.writer.shutdown()
        try {
            c.socket.close()
        } catch (_: IOException) {
        }
        listener.onLog("App déconnectée (${c.address})")
        listener.onClientsChanged()
    }

    companion object {
        const val STATUS_PERIOD_S = 30L
    }
}
