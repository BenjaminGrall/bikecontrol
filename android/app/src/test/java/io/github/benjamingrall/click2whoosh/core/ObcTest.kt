package io.github.benjamingrall.click2whoosh.core

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ObcTest {
    private fun bytes(vararg v: Int) = ByteArray(v.size) { v[it].toByte() }

    // Exemple de PROTOCOL.md : "zwift" 1.52.0, boutons 0x01 0x02 0x10 0x14.
    private val zwiftInfo = bytes(0x04, 0x01, 0x05) + "zwift".toByteArray() +
        bytes(0x06) + "1.52.0".toByteArray() + bytes(0x04, 0x01, 0x02, 0x10, 0x14)

    @Test
    fun encodesButtonMessages() {
        assertArrayEquals(bytes(0x01, 0x01, 0x01), Obc.buttons(listOf(ButtonChange(Obc.SHIFT_UP, true))))
        assertArrayEquals(
            bytes(0x01, 0x01, 0x00, 0x02, 0x01),
            Obc.buttons(listOf(ButtonChange(Obc.SHIFT_UP, false), ButtonChange(Obc.SHIFT_DOWN, true))),
        )
    }

    @Test
    fun encodesStatus() {
        assertArrayEquals(bytes(0x02, 0xFF, 0x01), Obc.status())
    }

    @Test
    fun parsesSpecAppInfo() {
        val msgs = AppStreamParser().feed(zwiftInfo)
        assertEquals(listOf(AppMessage.Info(AppInfo("zwift", "1.52.0", setOf(0x01, 0x02, 0x10, 0x14)))), msgs)
    }

    @Test
    fun parsesFragmentedAndCoalescedMessages() {
        val parser = AppStreamParser()
        val stream = zwiftInfo + bytes(0x03, 0x01, 0x00, 0x00) + zwiftInfo
        val out = ArrayList<AppMessage>()
        for (b in stream) out += parser.feed(byteArrayOf(b))
        assertEquals(3, out.size)
        assertTrue(out[1] is AppMessage.Haptic)

        val all = AppStreamParser().feed(stream)
        assertEquals(out, all)
    }

    @Test
    fun appInfoWithNoButtonsMeansAll() {
        val msg = bytes(0x04, 0x01, 0x08) + "mywhoosh".toByteArray() + bytes(0x00, 0x00)
        val info = (AppStreamParser().feed(msg).single() as AppMessage.Info).info
        assertEquals(AppInfo("mywhoosh", "", emptySet()), info)
    }

    @Test
    fun unknownTypeIsDroppedAndParserRecovers() {
        val parser = AppStreamParser()
        assertTrue(parser.feed(bytes(0x7F, 0x01, 0x02)).single() is AppMessage.Unknown)
        assertTrue(parser.feed(bytes(0x03, 0x01, 0x00, 0x00)).single() is AppMessage.Haptic)
    }
}
