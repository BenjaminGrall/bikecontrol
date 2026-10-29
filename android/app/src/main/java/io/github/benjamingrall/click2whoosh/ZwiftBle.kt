package io.github.benjamingrall.click2whoosh

import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCallback
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothGattDescriptor
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothProfile
import android.bluetooth.BluetoothStatusCodes
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanResult
import android.bluetooth.le.ScanSettings
import android.content.Context
import android.os.Build
import android.os.Handler
import android.os.SystemClock
import io.github.benjamingrall.click2whoosh.core.Zwift
import io.github.benjamingrall.click2whoosh.core.ZwiftButton
import io.github.benjamingrall.click2whoosh.core.ZwiftEvent
import io.github.benjamingrall.click2whoosh.core.ZwiftType
import io.github.benjamingrall.click2whoosh.core.hex
import java.util.UUID

/**
 * Trouve les manettes Zwift, s'y connecte et remonte l'état de leurs boutons.
 * Toutes les méthodes et tous les callbacks tournent sur le thread de [handler].
 *
 * On se connecte à toutes les manettes trouvées : sur le Click V2, la gauche relaie
 * aussi les boutons de la droite, mais la droite seule marche aussi (mode « côté droit seul »).
 *
 * Un Click V2 non activé dans Zwift refuse le RideOn. On passe alors en « mode sans Zwift »,
 * comme BikeControl sans déverrouillage : plus de RideOn, et reconnexion immédiate chaque
 * fois que la manette décroche (environ toutes les minutes).
 */
@SuppressLint("MissingPermission")
class ZwiftBle(
    private val context: Context,
    private val handler: Handler,
    private val listener: Listener,
) {
    interface Listener {
        /** État complet des boutons d'une manette (vide = tout relâché, par exemple à la déconnexion). */
        fun onButtons(address: String, label: String, pressed: Set<ZwiftButton>)
        fun onDevicesChanged()
    }

    private val adapter: BluetoothAdapter? = context.getSystemService(BluetoothManager::class.java)?.adapter
    private val links = LinkedHashMap<String, Link>()
    private val ignored = HashSet<String>()
    private var scanning = false
    private var running = false

    private val stopScanTask = Runnable {
        stopScan()
        Hub.log("Recherche BLE terminée")
        listener.onDevicesChanged()
    }

    fun start() {
        running = true
        startScan()
        listener.onDevicesChanged()
    }

    fun stop() {
        running = false
        stopScan()
        links.values.forEach { it.close() }
        links.clear()
        listener.onDevicesChanged()
    }

    /** Relance une recherche, et retente les manettes abandonnées. */
    fun rescan() {
        if (!running) return
        links.values.filter { it.abandoned }.forEach { links.remove(it.address) }
        ignored.clear()
        startScan()
        if (scanning && links.values.any { it.ready }) handler.postDelayed(stopScanTask, RESCAN_MS)
        listener.onDevicesChanged()
    }

    fun readyCount() = links.values.count { it.ready }

    fun summary(): String = when {
        links.isNotEmpty() -> links.values.joinToString("\n") { "${it.type.label} : ${it.state}" }
        scanning -> "recherche en cours…"
        else -> "aucune"
    }

    private fun startScan() {
        val a = adapter
        if (a == null || !a.isEnabled) {
            Hub.log("Bluetooth désactivé : active-le pour trouver le Click")
            return
        }
        handler.removeCallbacks(stopScanTask)
        if (scanning) return
        val scanner = a.bluetoothLeScanner ?: return
        val settings = ScanSettings.Builder().setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY).build()
        try {
            // Sans filtre : on teste nous-mêmes les manufacturer data 0x094A.
            scanner.startScan(null, settings, scanCallback)
            scanning = true
            Hub.log("Recherche des manettes Zwift : appuie sur un bouton du Click pour le réveiller")
        } catch (e: SecurityException) {
            Hub.log("Permission Bluetooth refusée (${e.message})")
        }
    }

    private fun stopScan() {
        handler.removeCallbacks(stopScanTask)
        if (!scanning) return
        scanning = false
        try {
            adapter?.bluetoothLeScanner?.stopScan(scanCallback)
        } catch (_: RuntimeException) {
            // Bluetooth coupé entre-temps, ou permission retirée.
        }
    }

    private val scanCallback = object : ScanCallback() {
        override fun onScanResult(callbackType: Int, result: ScanResult) {
            handler.post { onFound(result) }
        }

        override fun onScanFailed(errorCode: Int) {
            handler.post {
                scanning = false
                Hub.log("Recherche BLE en échec (code $errorCode)")
                listener.onDevicesChanged()
            }
        }
    }

    private fun onFound(r: ScanResult) {
        if (!running || !scanning) return
        val address = r.device.address
        if (address in links) return
        val data = r.scanRecord?.getManufacturerSpecificData(Zwift.MANUFACTURER_ID) ?: return
        val type = Zwift.deviceType(data)
        if (type == null) {
            if (ignored.add(address)) Hub.debug("Appareil Zwift ignoré : $address, données ${data.hex()}")
            return
        }
        Hub.log("Trouvé : ${type.label} ($address)")
        links[address] = Link(r.device, type).also { it.connect(autoConnect = false) }
        listener.onDevicesChanged()
    }

    private fun onLinkReady() {
        // Laisse le temps de trouver l'autre moitié d'un Click V2, puis arrête de scanner.
        if (scanning) {
            handler.removeCallbacks(stopScanTask)
            handler.postDelayed(stopScanTask, AFTER_READY_MS)
        }
    }

    private enum class Op { MTU, SERVICES, DESCRIPTOR, WRITE }

    /** Une manette : connexion GATT, file d'opérations (une à la fois) et reconnexion auto. */
    private inner class Link(val device: BluetoothDevice, val type: ZwiftType) : BluetoothGattCallback() {
        val address: String = device.address
        var state = ""
            private set
        var ready = false
            private set
        var abandoned = false
            private set

        private var gatt: BluetoothGatt? = null
        private var closed = false
        private var inputSeen = false
        private var wasConnected = false
        private var noZwift = false
        private var readyAt = 0L
        private var quickLocks = 0
        private val ops = ArrayDeque<Pair<Op, () -> Boolean>>()
        private var waiting: Op? = null
        private val name get() = type.label

        private val opTimeout = Runnable {
            val op = waiting
            Hub.debug("$name : pas de réponse à $op")
            if (op == Op.SERVICES) gatt?.disconnect() else next()
        }

        fun connect(autoConnect: Boolean) {
            if (closed) return
            inputSeen = false
            wasConnected = false
            setState(if (autoConnect) "en attente, se reconnectera dès qu'elle se réveille" else "connexion…")
            gatt = try {
                device.connectGatt(context, autoConnect, this, BluetoothDevice.TRANSPORT_LE)
            } catch (e: SecurityException) {
                Hub.log("$name : permission Bluetooth refusée")
                null
            }
        }

        fun close() {
            closed = true
            reset()
        }

        private fun reset() {
            ready = false
            ops.clear()
            waiting = null
            handler.removeCallbacks(opTimeout)
            gatt?.let {
                try {
                    it.disconnect()
                    it.close()
                } catch (_: SecurityException) {
                }
            }
            gatt = null
        }

        private fun setState(s: String) {
            state = s
            listener.onDevicesChanged()
        }

        private fun lost(status: Int) {
            // Mode sans Zwift : la manette décroche régulièrement, on la reprend tout de suite
            // par une connexion directe (bien plus rapide que l'autoConnect).
            val fast = noZwift && wasConnected
            reset()
            listener.onButtons(address, name, emptySet())
            if (closed || abandoned) return
            if (fast) {
                Hub.debug("$name a décroché (code $status), reconnexion immédiate")
            } else {
                Hub.log("$name déconnecté (code $status), reconnexion auto dès qu'il se réveille")
            }
            setState("déconnecté")
            handler.postDelayed({
                if (!closed && !abandoned && gatt == null) connect(autoConnect = !fast)
            }, if (fast) FAST_RECONNECT_MS else RECONNECT_DELAY_MS)
        }

        private fun giveUp(state: String, message: String) {
            abandoned = true
            reset()
            listener.onButtons(address, name, emptySet())
            setState(state)
            Hub.log(message)
        }

        // File d'opérations GATT : Android n'en accepte qu'une à la fois.

        private fun enqueue(op: Op, start: () -> Boolean) {
            ops.addLast(op to start)
            if (waiting == null) next()
        }

        private fun next() {
            handler.removeCallbacks(opTimeout)
            waiting = null
            while (true) {
                val (op, start) = ops.removeFirstOrNull() ?: return
                val started = try {
                    start()
                } catch (_: SecurityException) {
                    false
                }
                if (started) {
                    waiting = op
                    handler.postDelayed(opTimeout, OP_TIMEOUT_MS)
                    return
                }
                Hub.debug("$name : $op refusé par Android")
                if (op == Op.SERVICES) {
                    gatt?.disconnect()
                    return
                }
            }
        }

        private fun complete(op: Op) {
            if (waiting == op) next()
        }

        private fun markReady() {
            ready = true
            readyAt = SystemClock.elapsedRealtime()
            if (noZwift) {
                setState("prête (mode sans Zwift)")
                Hub.debug("$name reconnecté (mode sans Zwift)")
            } else {
                setState("prête : appuie sur un bouton")
                Hub.log("$name connecté, RideOn envoyé")
            }
            onLinkReady()
        }

        // Callbacks GATT (threads binder) : tout est renvoyé sur le thread du pont.

        override fun onConnectionStateChange(g: BluetoothGatt, status: Int, newState: Int) {
            handler.post {
                if (g !== gatt) return@post
                if (status == BluetoothGatt.GATT_SUCCESS && newState == BluetoothProfile.STATE_CONNECTED) {
                    wasConnected = true
                    setState("connectée, préparation…")
                    enqueue(Op.MTU) { g.requestMtu(MTU) }
                    enqueue(Op.SERVICES) { g.discoverServices() }
                } else {
                    lost(status)
                }
            }
        }

        override fun onMtuChanged(g: BluetoothGatt, mtu: Int, status: Int) {
            handler.post {
                if (g !== gatt) return@post
                Hub.debug("$name : MTU $mtu")
                complete(Op.MTU)
            }
        }

        override fun onServicesDiscovered(g: BluetoothGatt, status: Int) {
            handler.post {
                if (g !== gatt) return@post
                val service = Zwift.SERVICE_UUIDS.firstNotNullOfOrNull { g.getService(UUID.fromString(it)) }
                val async = service?.getCharacteristic(ASYNC)
                val rx = service?.getCharacteristic(SYNC_RX)
                val tx = service?.getCharacteristic(SYNC_TX)
                if (status != BluetoothGatt.GATT_SUCCESS || async == null || rx == null) {
                    giveUp(
                        "service Zwift introuvable",
                        "$name : service Zwift absent. Ferme Zwift et BikeControl s'ils tournent " +
                            "(une seule app à la fois), ou mets à jour le firmware, puis « Rechercher ».",
                    )
                    return@post
                }
                enqueue(Op.DESCRIPTOR) { subscribe(g, async, preferIndicate = false) }
                if (tx != null) enqueue(Op.DESCRIPTOR) { subscribe(g, tx, preferIndicate = true) }
                if (noZwift) {
                    markReady()
                } else {
                    enqueue(Op.WRITE) { write(g, rx, Zwift.RIDE_ON).also { if (it) markReady() } }
                }
                complete(Op.SERVICES)
            }
        }

        override fun onDescriptorWrite(g: BluetoothGatt, d: BluetoothGattDescriptor, status: Int) {
            handler.post {
                if (g !== gatt) return@post
                if (status != BluetoothGatt.GATT_SUCCESS) Hub.log("$name : abonnement refusé (code $status)")
                complete(Op.DESCRIPTOR)
            }
        }

        override fun onCharacteristicWrite(g: BluetoothGatt, c: BluetoothGattCharacteristic, status: Int) {
            handler.post {
                if (g !== gatt) return@post
                if (status != BluetoothGatt.GATT_SUCCESS) Hub.log("$name : écriture refusée (code $status)")
                complete(Op.WRITE)
            }
        }

        @Deprecated("Remplacé par la version avec valeur à partir d'Android 13")
        @Suppress("DEPRECATION")
        override fun onCharacteristicChanged(g: BluetoothGatt, c: BluetoothGattCharacteristic) {
            if (Build.VERSION.SDK_INT < 33) received(g, c, c.value ?: return)
        }

        override fun onCharacteristicChanged(g: BluetoothGatt, c: BluetoothGattCharacteristic, value: ByteArray) {
            received(g, c, value)
        }

        private fun received(g: BluetoothGatt, c: BluetoothGattCharacteristic, value: ByteArray) {
            val bytes = value.copyOf()
            val uuid = c.uuid
            handler.post { if (g === gatt) onPacket(uuid, bytes) }
        }

        private fun onPacket(uuid: UUID, v: ByteArray) {
            if (Hub.debug) Hub.debug("$name ${uuid.toString().substring(4, 8)} <- ${v.hex()}")
            when (val e = Zwift.decode(v)) {
                is ZwiftEvent.Buttons -> {
                    if (!inputSeen) {
                        inputSeen = true
                        setState("prête, boutons reçus")
                    }
                    listener.onButtons(address, name, e.pressed)
                }
                ZwiftEvent.HandshakeOk -> Hub.debug("$name : RideOn accepté")
                ZwiftEvent.Locked -> {
                    listener.onButtons(address, name, emptySet())
                    // Garde-fou : s'il refuse aussi sans RideOn, ne pas boucler en reconnexion.
                    if (noZwift && SystemClock.elapsedRealtime() - readyAt < QUICK_LOCK_MS) {
                        if (++quickLocks >= 3) {
                            return giveUp(
                                "verrouillée",
                                "$name refuse aussi la connexion sans RideOn : il faudra l'activer dans Zwift " +
                                    "(voir README), ou utiliser « Côté droit seul ».",
                            )
                        }
                    } else {
                        quickLocks = 0
                    }
                    if (!noZwift) {
                        noZwift = true
                        Hub.log(
                            "$name non activé par Zwift : mode sans Zwift (pas de RideOn, reconnexion " +
                                "immédiate quand il décroche, coupure d'une seconde ou deux possible)",
                        )
                    }
                    // Relance une connexion propre : sans RideOn, ou pour repartir pour une minute.
                    gatt?.disconnect()
                }
                ZwiftEvent.Ignored -> Unit
                is ZwiftEvent.Unknown -> Hub.debug("$name : message ignoré (${e.reason})")
            }
        }
    }

    @Suppress("DEPRECATION")
    private fun subscribe(g: BluetoothGatt, c: BluetoothGattCharacteristic, preferIndicate: Boolean): Boolean {
        val cccd = c.getDescriptor(CCCD) ?: return false
        val notify = c.properties and BluetoothGattCharacteristic.PROPERTY_NOTIFY != 0
        val indicate = c.properties and BluetoothGattCharacteristic.PROPERTY_INDICATE != 0
        val value = when {
            indicate && (preferIndicate || !notify) -> BluetoothGattDescriptor.ENABLE_INDICATION_VALUE
            notify -> BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE
            else -> return false
        }
        if (!g.setCharacteristicNotification(c, true)) return false
        return if (Build.VERSION.SDK_INT >= 33) {
            g.writeDescriptor(cccd, value) == BluetoothStatusCodes.SUCCESS
        } else {
            cccd.value = value
            g.writeDescriptor(cccd)
        }
    }

    @Suppress("DEPRECATION")
    private fun write(g: BluetoothGatt, c: BluetoothGattCharacteristic, data: ByteArray): Boolean {
        val type = if (c.properties and BluetoothGattCharacteristic.PROPERTY_WRITE_NO_RESPONSE != 0) {
            BluetoothGattCharacteristic.WRITE_TYPE_NO_RESPONSE
        } else {
            BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT
        }
        return if (Build.VERSION.SDK_INT >= 33) {
            g.writeCharacteristic(c, data, type) == BluetoothStatusCodes.SUCCESS
        } else {
            c.writeType = type
            c.value = data
            g.writeCharacteristic(c)
        }
    }

    private companion object {
        val ASYNC: UUID = UUID.fromString(Zwift.ASYNC_UUID)
        val SYNC_RX: UUID = UUID.fromString(Zwift.SYNC_RX_UUID)
        val SYNC_TX: UUID = UUID.fromString(Zwift.SYNC_TX_UUID)
        val CCCD: UUID = UUID.fromString(Zwift.CCCD_UUID)
        const val MTU = 247
        const val OP_TIMEOUT_MS = 5_000L
        const val RECONNECT_DELAY_MS = 2_000L
        const val FAST_RECONNECT_MS = 200L
        const val QUICK_LOCK_MS = 5_000L
        const val AFTER_READY_MS = 30_000L
        const val RESCAN_MS = 60_000L
    }
}
