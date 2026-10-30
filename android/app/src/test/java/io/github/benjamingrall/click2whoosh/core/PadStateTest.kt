package io.github.benjamingrall.click2whoosh.core

import org.junit.Assert.assertEquals
import org.junit.Test

class PadStateTest {
    private fun info(type: ZwiftType, ready: Boolean = true, noZwift: Boolean = false) =
        ControllerInfo(type, if (ready) "prête" else "connexion…", ready, noZwift)

    private fun level(left: Boolean, list: List<ControllerInfo>, seen: Boolean = false, scanning: Boolean = false) =
        PadState.half(left, list, seen, scanning).level

    @Test
    fun nothingFound() {
        assertEquals(Level.OFF, level(true, emptyList()))
        assertEquals("recherche… appuie sur un bouton du Click", PadState.half(true, emptyList(), false, true).text)
    }

    @Test
    fun leftRelaysRight() {
        val list = listOf(info(ZwiftType.CLICK_V2_LEFT))
        assertEquals(Level.READY, level(true, list))
        assertEquals("via la gauche, appuie sur un bouton", PadState.half(false, list, false, false).text)
        assertEquals(Level.WORKING, level(false, list, seen = true))
    }

    @Test
    fun rightAloneDoesNotLightLeft() {
        val list = listOf(info(ZwiftType.CLICK_V2_RIGHT))
        assertEquals(Level.OFF, level(true, list))
        assertEquals(Level.WORKING, level(false, list, seen = true))
    }

    @Test
    fun clickV1CoversBothHalves() {
        val list = listOf(info(ZwiftType.CLICK))
        assertEquals(Level.READY, level(true, list))
        assertEquals(Level.READY, level(false, list))
    }

    @Test
    fun connectingAndNoZwiftMode() {
        val connecting = listOf(info(ZwiftType.CLICK_V2_LEFT, ready = false))
        assertEquals(HalfState(Level.CONNECTING, "connexion…"), PadState.half(true, connecting, true, false))
        val noZwift = listOf(info(ZwiftType.CLICK_V2_LEFT, noZwift = true))
        assertEquals(HalfState(Level.WORKING, "OK, boutons reçus (mode sans Zwift)"), PadState.half(true, noZwift, true, false))
    }
}
