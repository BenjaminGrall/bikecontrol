package io.github.benjamingrall.click2whoosh.core

/**
 * Protocole BLE des manettes Zwift (Click, Click V2, Ride).
 * Constantes recoupées avec la version GPL-3.0 de BikeControl (tag "gpl3") ; code écrit pour ce projet.
 */
object Zwift {
    const val MANUFACTURER_ID = 0x094A

    val SERVICE_UUIDS = listOf(
        "00000001-19ca-4651-86e5-fa29dcdd09d1",
        "0000fc82-0000-1000-8000-00805f9b34fb",
    )
    /** Notify : boutons. */
    const val ASYNC_UUID = "00000002-19ca-4651-86e5-fa29dcdd09d1"
    /** Write sans réponse : on y écrit "RideOn". */
    const val SYNC_RX_UUID = "00000003-19ca-4651-86e5-fa29dcdd09d1"
    /** Indicate : réponses au handshake. */
    const val SYNC_TX_UUID = "00000004-19ca-4651-86e5-fa29dcdd09d1"
    const val CCCD_UUID = "00002902-0000-1000-8000-00805f9b34fb"

    val RIDE_ON = "RideOn".toByteArray(Charsets.US_ASCII)

    const val MSG_RIDE = 0x23
    const val MSG_CLICK = 0x37
    const val MSG_EMPTY = 0x15
    const val MSG_BATTERY = 0x19

    /** Le type est le 1er octet des manufacturer data (après l'ID 0x094A). */
    fun deviceType(manufacturerData: ByteArray?): ZwiftType? {
        val code = manufacturerData?.firstOrNull()?.toInt()?.and(0xFF) ?: return null
        return ZwiftType.entries.firstOrNull { it.code == code }
    }

    fun decode(p: ByteArray): ZwiftEvent {
        if (p.isEmpty()) return ZwiftEvent.Ignored
        if (p.size >= RIDE_ON.size && p.copyOf(RIDE_ON.size).contentEquals(RIDE_ON)) return ZwiftEvent.HandshakeOk
        if (p.size >= 3 && p.u(0) == 0xFF && p.u(1) == 0x05 && p.u(2) == 0x00) return ZwiftEvent.Locked
        return when (p.u(0)) {
            MSG_CLICK -> {
                // Click V1 : champ 1 = plus, champ 2 = moins ; 0 = appuyé (un champ absent vaut 0).
                val f = Protobuf.varints(p, 1)
                val pressed = LinkedHashSet<ZwiftButton>()
                if ((f[1] ?: 0L) == 0L) pressed += ZwiftButton.SHIFT_UP_R
                if ((f[2] ?: 0L) == 0L) pressed += ZwiftButton.SHIFT_UP_L
                ZwiftEvent.Buttons(pressed)
            }
            MSG_RIDE -> {
                // Click V2 / Ride : champ 1 = masque, bit à 0 = appuyé.
                val mask = Protobuf.varints(p, 1)[1] ?: return ZwiftEvent.Unknown("0x23 sans masque")
                ZwiftEvent.Buttons(ZwiftButton.entries.filterTo(LinkedHashSet()) { mask and it.mask.toLong() == 0L })
            }
            MSG_EMPTY, MSG_BATTERY -> ZwiftEvent.Ignored
            else -> ZwiftEvent.Unknown("type 0x%02x".format(p.u(0)))
        }
    }
}

/** [leftHalf] / [rightHalf] : moitiés dont la manette porte les boutons (le Click V1 a les deux). */
enum class ZwiftType(val code: Int, val label: String, val leftHalf: Boolean, val rightHalf: Boolean) {
    CLICK(0x09, "Click", true, true),
    CLICK_V2_RIGHT(0x0A, "Click V2 droit", false, true),
    CLICK_V2_LEFT(0x0B, "Click V2 gauche", true, false),
    RIDE_RIGHT(0x07, "Ride droit", false, true),
    RIDE_LEFT(0x08, "Ride gauche", true, false),
    ;

    /** La moitié gauche du Click V2 / Ride relaie aussi les boutons de la droite. */
    val relaysRight get() = leftHalf && !rightHalf
}

/**
 * Masques du message 0x23. Le Click V1 est ramené dessus : + = SHIFT_UP_R, - = SHIFT_UP_L.
 * [left] : bouton de la moitié gauche (pavé directionnel et -).
 */
enum class ZwiftButton(val mask: Int, val label: String, val left: Boolean) {
    LEFT(0x1, "gauche", true),
    UP(0x2, "haut", true),
    RIGHT(0x4, "droite", true),
    DOWN(0x8, "bas", true),
    A(0x10, "A", false),
    B(0x20, "B", false),
    Y(0x40, "Y", false),
    Z(0x80, "Z", false),
    SHIFT_UP_L(0x100, "- (shift_up_l)", true),
    SHIFT_DN_L(0x200, "shift_dn_l", true),
    POWERUP_L(0x400, "powerup_l", true),
    ONOFF_L(0x800, "onoff_l", true),
    SHIFT_UP_R(0x1000, "+ (shift_up_r)", false),
    SHIFT_DN_R(0x2000, "shift_dn_r", false),
    POWERUP_R(0x4000, "powerup_r", false),
    ONOFF_R(0x8000, "onoff_r", false),
}

sealed interface ZwiftEvent {
    data class Buttons(val pressed: Set<ZwiftButton>) : ZwiftEvent
    /** Réponse "RideOn..." de la manette. */
    data object HandshakeOk : ZwiftEvent
    /** Réponse ff 05 00... : manette verrouillée (Click V2 non activé dans Zwift). */
    data object Locked : ZwiftEvent
    data object Ignored : ZwiftEvent
    data class Unknown(val reason: String) : ZwiftEvent
}

internal object Protobuf {
    /**
     * Champs varint d'un message protobuf à partir de [start] ; les autres types sont sautés.
     * S'arrête sans erreur sur un paquet tronqué en gardant les champs déjà lus.
     */
    fun varints(b: ByteArray, start: Int): Map<Int, Long> {
        val out = HashMap<Int, Long>()
        var i = start

        fun varint(): Long? {
            var value = 0L
            var shift = 0
            while (i < b.size && shift < 64) {
                val x = b[i++].toInt() and 0xFF
                value = value or ((x and 0x7F).toLong() shl shift)
                if (x and 0x80 == 0) return value
                shift += 7
            }
            return null
        }

        while (i < b.size) {
            val key = varint() ?: break
            val field = (key ushr 3).toInt()
            when ((key and 7).toInt()) {
                0 -> out[field] = varint() ?: break
                1 -> i += 8
                2 -> {
                    val len = varint() ?: break
                    if (len < 0 || len > b.size - i) break
                    i += len.toInt()
                }
                5 -> i += 4
                else -> break
            }
        }
        return out
    }
}
