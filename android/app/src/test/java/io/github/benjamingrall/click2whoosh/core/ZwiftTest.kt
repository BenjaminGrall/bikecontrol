package io.github.benjamingrall.click2whoosh.core

import io.github.benjamingrall.click2whoosh.core.ZwiftButton.A
import io.github.benjamingrall.click2whoosh.core.ZwiftButton.B
import io.github.benjamingrall.click2whoosh.core.ZwiftButton.SHIFT_UP_L
import io.github.benjamingrall.click2whoosh.core.ZwiftButton.SHIFT_UP_R
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ZwiftTest {
    private fun bytes(vararg v: Int) = ByteArray(v.size) { v[it].toByte() }

    /** Paquet 0x23 avec le masque en varint ; les bits à 0 sont appuyés. */
    private fun ride(mask: Long, extra: ByteArray = ByteArray(0)): ByteArray {
        val out = arrayListOf(0x23.toByte(), 0x08.toByte())
        var v = mask
        while (v >= 0x80) {
            out += ((v and 0x7F) or 0x80).toByte()
            v = v ushr 7
        }
        out += v.toByte()
        return out.toByteArray() + extra
    }

    private fun released(vararg pressed: ZwiftButton): Long =
        pressed.fold(0xFFFFFFFFL) { m, b -> m and b.mask.toLong().inv() }

    @Test
    fun deviceTypeFromManufacturerData() {
        assertEquals(ZwiftType.CLICK_V2_LEFT, Zwift.deviceType(bytes(0x0B, 0x01)))
        assertEquals(ZwiftType.CLICK, Zwift.deviceType(bytes(0x09)))
        assertNull(Zwift.deviceType(bytes(0x02)))
        assertNull(Zwift.deviceType(ByteArray(0)))
        assertNull(Zwift.deviceType(null))
    }

    @Test
    fun clickV1PlusMinus() {
        // Rien d'appuyé : les deux champs à 1.
        assertEquals(ZwiftEvent.Buttons(emptySet()), Zwift.decode(bytes(0x37, 0x08, 0x01, 0x10, 0x01)))
        // + appuyé, encodé explicitement à 0.
        assertEquals(ZwiftEvent.Buttons(setOf(SHIFT_UP_R)), Zwift.decode(bytes(0x37, 0x08, 0x00, 0x10, 0x01)))
        // - appuyé, champ 2 absent (proto3 n'écrit pas les valeurs par défaut).
        assertEquals(ZwiftEvent.Buttons(setOf(SHIFT_UP_L)), Zwift.decode(bytes(0x37, 0x08, 0x01)))
    }

    @Test
    fun rideBitmapActiveLow() {
        assertEquals(ZwiftEvent.Buttons(emptySet()), Zwift.decode(ride(released())))
        assertEquals(ZwiftEvent.Buttons(setOf(SHIFT_UP_R)), Zwift.decode(ride(released(SHIFT_UP_R))))
        assertEquals(ZwiftEvent.Buttons(setOf(A, B, SHIFT_UP_L)), Zwift.decode(ride(released(A, B, SHIFT_UP_L))))
    }

    @Test
    fun rideSkipsAnalogFieldsAndSurvivesTruncation() {
        // Champ 3 (palettes analogiques, length-delimited) après le masque.
        val analog = bytes(0x1A, 0x04, 0x08, 0x00, 0x10, 0x00)
        assertEquals(ZwiftEvent.Buttons(setOf(B)), Zwift.decode(ride(released(B), analog)))
        // Paquet coupé au milieu du champ 3 (MTU) : le masque reste lisible.
        val truncated = ride(released(B), analog).copyOf(ride(released(B)).size + 3)
        assertEquals(ZwiftEvent.Buttons(setOf(B)), Zwift.decode(truncated))
    }

    @Test
    fun rideWithoutMaskIsNotAllPressed() {
        assertTrue(Zwift.decode(bytes(0x23)) is ZwiftEvent.Unknown)
    }

    @Test
    fun handshakeLockAndIgnoredMessages() {
        assertEquals(ZwiftEvent.HandshakeOk, Zwift.decode("RideOn".toByteArray() + bytes(0x02, 0x03)))
        assertEquals(ZwiftEvent.Locked, Zwift.decode(bytes(0xFF, 0x05, 0x00, 0xEA, 0x05)))
        assertEquals(ZwiftEvent.Ignored, Zwift.decode(bytes(0x15)))
        assertEquals(ZwiftEvent.Ignored, Zwift.decode(bytes(0x19, 0x08, 0x50)))
        assertTrue(Zwift.decode(bytes(0x42, 0x00)) is ZwiftEvent.Unknown)
    }
}
