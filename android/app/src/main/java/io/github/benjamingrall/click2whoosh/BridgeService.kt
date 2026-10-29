package io.github.benjamingrall.click2whoosh

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.bluetooth.BluetoothAdapter
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.ServiceInfo
import android.graphics.drawable.Icon
import android.net.wifi.WifiManager
import android.os.Build
import android.os.Handler
import android.os.HandlerThread
import android.os.IBinder
import android.os.PowerManager
import io.github.benjamingrall.click2whoosh.core.ButtonChange
import io.github.benjamingrall.click2whoosh.core.ButtonRouter
import io.github.benjamingrall.click2whoosh.core.Obc
import io.github.benjamingrall.click2whoosh.core.ObcTcpServer
import io.github.benjamingrall.click2whoosh.core.ZwiftButton
import java.io.IOException
import java.net.Inet4Address
import java.net.NetworkInterface

/**
 * Service de premier plan (type connectedDevice) qui fait tourner le pont, pour qu'Android
 * ne le tue pas quand MyWhoosh passe au premier plan sur le même téléphone.
 * Toute la logique du pont tourne sur un seul thread ([handler]).
 */
class BridgeService : Service() {

    companion object {
        @Volatile var current: BridgeService? = null
            private set

        const val SERVICE_NAME = "click2whoosh"
        private const val CHANNEL = "bridge"
        private const val NOTIFICATION_ID = 1
        private const val ACTION_STOP = "io.github.benjamingrall.click2whoosh.STOP"
        private const val TEST_SOURCE = "test"
        private const val TEST_PRESS_MS = 150L
        private const val WAKE_LOCK_MS = 6 * 60 * 60 * 1000L

        fun start(context: Context) {
            context.startForegroundService(Intent(context, BridgeService::class.java))
        }

        fun stop(context: Context) {
            context.stopService(Intent(context, BridgeService::class.java))
        }
    }

    private lateinit var thread: HandlerThread
    private lateinit var handler: Handler
    private val router = ButtonRouter()
    private var server: ObcTcpServer? = null
    private var mdns: MdnsAdvertiser? = null
    private var ble: ZwiftBle? = null
    private val lastButtons = HashMap<String, Set<ZwiftButton>>()
    private var wakeLock: PowerManager.WakeLock? = null
    private var multicastLock: WifiManager.MulticastLock? = null
    private var notificationText = ""

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        thread = HandlerThread("bridge").apply { start() }
        handler = Handler(thread.looper)
        val filter = IntentFilter(BluetoothAdapter.ACTION_STATE_CHANGED)
        if (Build.VERSION.SDK_INT >= 33) {
            registerReceiver(bluetoothState, filter, Context.RECEIVER_NOT_EXPORTED)
        } else {
            registerReceiver(bluetoothState, filter)
        }
        getSystemService(NotificationManager::class.java)?.createNotificationChannel(
            NotificationChannel(CHANNEL, "Pont Click vers MyWhoosh", NotificationManager.IMPORTANCE_LOW),
        )
        try {
            val n = buildNotification("démarrage…")
            if (Build.VERSION.SDK_INT >= 29) {
                startForeground(NOTIFICATION_ID, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE)
            } else {
                startForeground(NOTIFICATION_ID, n)
            }
        } catch (e: RuntimeException) {
            Hub.log("Android refuse le service de premier plan : ${e.message}")
            stopSelf()
            return
        }
        current = this
        acquireLocks()
        handler.post { startBridge() }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            stopSelf()
            return START_NOT_STICKY
        }
        return START_STICKY
    }

    override fun onDestroy() {
        current = null
        unregisterReceiver(bluetoothState)
        handler.post { stopBridge() }
        thread.quitSafely()
        releaseLocks()
        super.onDestroy()
    }

    // Appelés depuis l'écran.

    fun testPress(button: ZwiftButton) {
        handler.post {
            val apps = server?.clientList?.size ?: 0
            val target = if (apps == 0) "aucune app connectée" else "$apps app(s)"
            Hub.log("Test « ${Obc.buttonName(router.actionFor(button))} » envoyé à : $target")
            send(router.update(TEST_SOURCE, setOf(button)))
            handler.postDelayed({ send(router.update(TEST_SOURCE, emptySet())) }, TEST_PRESS_MS)
        }
    }

    fun setRightOnly(enabled: Boolean) {
        handler.post { send(router.setRightOnly(enabled)) }
    }

    fun rescan() {
        handler.post { ble?.rescan() }
    }

    // Pont (thread handler).

    private fun startBridge() {
        router.setRightOnly(Hub.rightOnly(this))
        val srv = ObcTcpServer(Obc.DEFAULT_PORT, serverListener)
        try {
            srv.start()
        } catch (e: IOException) {
            Hub.log("Impossible d'ouvrir le serveur TCP : ${e.message}")
            stopSelf()
            return
        }
        server = srv
        Hub.log("Serveur OpenBikeControl ouvert sur le port ${srv.port}")
        mdns = MdnsAdvertiser(this).also { it.register(SERVICE_NAME, srv.port, Hub.deviceId(this)) }
        ble = ZwiftBle(this, handler, bleListener).also { it.start() }
        updateStatus()
    }

    private fun stopBridge() {
        send(router.releaseAll())
        // server à null d'abord : updateStatus ne republie plus la notification pendant l'arrêt.
        val srv = server
        server = null
        ble?.stop()
        ble = null
        mdns?.unregister()
        mdns = null
        srv?.stop()
        Hub.setStatus(Hub.Status())
        Hub.log("Pont arrêté")
    }

    private fun send(changes: List<ButtonChange>) {
        if (changes.isEmpty()) return
        val srv = server ?: return
        srv.broadcast(Obc.buttons(changes))
        Hub.debug("-> app (${srv.clientList.size}) : ${changes.joinToString()}")
    }

    private val serverListener = object : ObcTcpServer.Listener {
        override fun onClientsChanged() {
            handler.post { onClientsChanged() }
        }

        override fun onLog(message: String) = Hub.log(message)

        override fun onDebug(message: String) = Hub.debug(message)
    }

    private fun onClientsChanged() {
        val infos = server?.clientList.orEmpty().map { it.appInfo }
        // Une app sans App Info (ou avec une liste vide) accepte tout.
        val supported = if (infos.isEmpty() || infos.any { it == null || it.buttons.isEmpty() }) {
            null
        } else {
            infos.flatMapTo(HashSet()) { it!!.buttons }
        }
        send(router.setSupported(supported))
        updateStatus()
    }

    private val bleListener = object : ZwiftBle.Listener {
        override fun onButtons(address: String, label: String, pressed: Set<ZwiftButton>) {
            if (Hub.debug && lastButtons[address] != pressed) {
                Hub.debug("$label : " + if (pressed.isEmpty()) "aucun bouton" else pressed.joinToString { it.label })
            }
            lastButtons[address] = pressed
            send(router.update(address, pressed))
        }

        override fun onDevicesChanged() = updateStatus()
    }

    private val bluetoothState = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            when (intent.getIntExtra(BluetoothAdapter.EXTRA_STATE, -1)) {
                BluetoothAdapter.STATE_ON -> handler.post {
                    Hub.log("Bluetooth activé")
                    ble?.stop()
                    ble?.start()
                }
                BluetoothAdapter.STATE_OFF -> handler.post {
                    Hub.log("Bluetooth coupé")
                    send(router.releaseAll())
                    ble?.stop()
                }
            }
        }
    }

    private fun updateStatus() {
        val srv = server ?: return
        val clients = srv.clientList
        val ips = localIps()
        Hub.setStatus(
            Hub.Status(
                running = true,
                server = "« ${mdns?.registeredName ?: SERVICE_NAME} », port ${srv.port}" +
                    if (ips.isEmpty()) "" else " (${ips.joinToString()})",
                apps = if (clients.isEmpty()) {
                    "aucune"
                } else {
                    clients.joinToString("\n") { c ->
                        (c.appInfo?.let { "${it.appId} ${it.appVersion}" } ?: "app") + " (${c.address})"
                    }
                },
                controllers = ble?.summary().orEmpty(),
            ),
        )
        val text = "${clients.size} app(s), ${ble?.readyCount() ?: 0} manette(s)"
        if (text != notificationText) {
            notificationText = text
            getSystemService(NotificationManager::class.java)?.notify(NOTIFICATION_ID, buildNotification(text))
        }
    }

    private fun buildNotification(text: String): Notification {
        val open = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE,
        )
        val stop = PendingIntent.getService(
            this, 1, Intent(this, BridgeService::class.java).setAction(ACTION_STOP), PendingIntent.FLAG_IMMUTABLE,
        )
        return Notification.Builder(this, CHANNEL)
            .setSmallIcon(android.R.drawable.stat_sys_data_bluetooth)
            .setContentTitle("click2whoosh actif")
            .setContentText(text)
            .setContentIntent(open)
            .setOngoing(true)
            .addAction(Notification.Action.Builder(null as Icon?, "Arrêter", stop).build())
            .build()
    }

    private fun acquireLocks() {
        wakeLock = getSystemService(PowerManager::class.java)
            ?.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "click2whoosh:bridge")
            ?.apply {
                setReferenceCounted(false)
                acquire(WAKE_LOCK_MS)
            }
        multicastLock = applicationContext.getSystemService(WifiManager::class.java)
            ?.createMulticastLock("click2whoosh")
            ?.apply {
                setReferenceCounted(false)
                acquire()
            }
    }

    private fun releaseLocks() {
        wakeLock?.takeIf { it.isHeld }?.release()
        wakeLock = null
        multicastLock?.takeIf { it.isHeld }?.release()
        multicastLock = null
    }

    private fun localIps(): List<String> = try {
        NetworkInterface.getNetworkInterfaces().toList()
            .filter { it.isUp && !it.isLoopback }
            .flatMap { it.inetAddresses.toList() }
            .filterIsInstance<Inet4Address>()
            .mapNotNull { it.hostAddress }
    } catch (_: Exception) {
        emptyList()
    }
}
