package io.github.benjamingrall.click2whoosh.core

/**
 * Protocole OpenBikeControl en réseau (mDNS + TCP).
 * Spec MIT : github.com/OpenBikeControl/openbikecontrol-protocol (MDNS.md, PROTOCOL.md).
 */
object Obc {
    const val SERVICE_TYPE = "_openbikecontrol._tcp"
    const val SERVICE_UUID = "d273f680-d548-419d-b9d1-fa0472345229"
    const val DEFAULT_PORT = 21587

    const val MSG_BUTTONS = 0x01
    const val MSG_STATUS = 0x02
    const val MSG_HAPTIC = 0x03
    const val MSG_APP_INFO = 0x04

    const val SHIFT_UP = 0x01
    const val SHIFT_DOWN = 0x02
    const val NAV_UP = 0x10
    const val NAV_DOWN = 0x11
    const val NAV_LEFT = 0x12
    const val NAV_RIGHT = 0x13
    const val SELECT = 0x14
    const val BACK = 0x15
    const val MENU = 0x16
    const val STEER_LEFT = 0x18
    const val STEER_RIGHT = 0x19
    const val EMOTE = 0x20
    const val CAMERA = 0x40
    const val HUD = 0x44
    const val POWERUP = 0x50

    fun buttonName(id: Int): String = when (id) {
        SHIFT_UP -> "vitesse +"
        SHIFT_DOWN -> "vitesse -"
        NAV_UP -> "haut"
        NAV_DOWN -> "bas"
        NAV_LEFT -> "gauche"
        NAV_RIGHT -> "droite"
        SELECT -> "valider"
        BACK -> "retour"
        MENU -> "menu"
        STEER_LEFT -> "tourner à gauche"
        STEER_RIGHT -> "tourner à droite"
        EMOTE -> "emote"
        CAMERA -> "caméra"
        HUD -> "HUD"
        POWERUP -> "power-up"
        else -> "0x%02x".format(id)
    }

    /** [0x01, id, état, id, état, ...] avec 0 = relâché, 1 = appuyé. */
    fun buttons(changes: List<ButtonChange>): ByteArray {
        val out = ByteArray(1 + 2 * changes.size)
        out[0] = MSG_BUTTONS.toByte()
        changes.forEachIndexed { i, c ->
            out[1 + 2 * i] = c.id.toByte()
            out[2 + 2 * i] = if (c.pressed) 1 else 0
        }
        return out
    }

    /** [0x02, batterie (0xFF = inconnue), prêt]. */
    fun status(battery: Int = 0xFF, ready: Boolean = true): ByteArray =
        byteArrayOf(MSG_STATUS.toByte(), battery.toByte(), if (ready) 1 else 0)
}

data class ButtonChange(val id: Int, val pressed: Boolean) {
    override fun toString() = "${Obc.buttonName(id)} ${if (pressed) "appuyé" else "relâché"}"
}

/** Message App Info (0x04). Une liste de boutons vide veut dire « tous ». */
data class AppInfo(val appId: String, val appVersion: String, val buttons: Set<Int>) {
    override fun toString(): String {
        val list = if (buttons.isEmpty()) "tous" else buttons.sorted().joinToString { Obc.buttonName(it) }
        return "$appId $appVersion (boutons : $list)"
    }
}

sealed interface AppMessage {
    data class Haptic(val pattern: Int, val duration: Int, val intensity: Int) : AppMessage
    data class Info(val info: AppInfo) : AppMessage
    class Unknown(val bytes: ByteArray) : AppMessage
}

/** Découpe le flux TCP app -> appareil, qui peut arriver fragmenté ou collé. */
class AppStreamParser {
    private var buf = ByteArray(0)

    fun feed(data: ByteArray, len: Int = data.size): List<AppMessage> {
        buf += data.copyOf(len)
        val out = ArrayList<AppMessage>()
        while (buf.isNotEmpty()) {
            val size = when (buf.u(0)) {
                Obc.MSG_HAPTIC -> 4
                Obc.MSG_APP_INFO -> appInfoSize() ?: break
                else -> {
                    // Impossible de se recaler sans connaître la longueur : on jette ce qui reste.
                    out += AppMessage.Unknown(buf)
                    buf = ByteArray(0)
                    break
                }
            }
            if (buf.size < size) break
            out += decode(buf.copyOfRange(0, size))
            buf = buf.copyOfRange(size, buf.size)
        }
        if (buf.size > 4096) buf = ByteArray(0)
        return out
    }

    /** [0x04, version, len, app_id..., len, app_version..., n, ids...] ; null si incomplet. */
    private fun appInfoSize(): Int? {
        var i = 2
        if (buf.size <= i) return null
        i += 1 + buf.u(i)
        if (buf.size <= i) return null
        i += 1 + buf.u(i)
        if (buf.size <= i) return null
        return i + 1 + buf.u(i)
    }

    private fun decode(m: ByteArray): AppMessage = when (m.u(0)) {
        Obc.MSG_HAPTIC -> AppMessage.Haptic(m.u(1), m.u(2), m.u(3))
        else -> {
            val idLen = m.u(2)
            val appId = String(m, 3, idLen, Charsets.UTF_8)
            val verAt = 3 + idLen
            val verLen = m.u(verAt)
            val version = String(m, verAt + 1, verLen, Charsets.UTF_8)
            val countAt = verAt + 1 + verLen
            val ids = (0 until m.u(countAt)).mapTo(LinkedHashSet()) { m.u(countAt + 1 + it) }
            AppMessage.Info(AppInfo(appId, version, ids))
        }
    }
}

internal fun ByteArray.u(i: Int): Int = this[i].toInt() and 0xFF

fun ByteArray.hex(len: Int = size): String =
    (0 until len).joinToString(" ") { "%02x".format(this[it].toInt() and 0xFF) }
