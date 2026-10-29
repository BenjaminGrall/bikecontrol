package io.github.benjamingrall.click2whoosh.core

import io.github.benjamingrall.click2whoosh.core.ZwiftButton.B
import io.github.benjamingrall.click2whoosh.core.ZwiftButton.LEFT
import io.github.benjamingrall.click2whoosh.core.ZwiftButton.SHIFT_UP_L
import io.github.benjamingrall.click2whoosh.core.ZwiftButton.SHIFT_UP_R
import org.junit.Assert.assertEquals
import org.junit.Test

class ButtonRouterTest {
    private fun press(id: Int) = ButtonChange(id, true)
    private fun release(id: Int) = ButtonChange(id, false)

    @Test
    fun sendsOnlyTransitions() {
        val r = ButtonRouter()
        assertEquals(listOf(press(Obc.SHIFT_UP)), r.update("click", setOf(SHIFT_UP_R)))
        assertEquals(emptyList<ButtonChange>(), r.update("click", setOf(SHIFT_UP_R)))
        assertEquals(listOf(release(Obc.SHIFT_UP)), r.update("click", emptySet()))
        assertEquals(emptyList<ButtonChange>(), r.update("click", emptySet()))
    }

    @Test
    fun releasesBeforePresses() {
        val r = ButtonRouter()
        r.update("click", setOf(SHIFT_UP_R))
        assertEquals(listOf(release(Obc.SHIFT_UP), press(Obc.SHIFT_DOWN)), r.update("click", setOf(SHIFT_UP_L)))
    }

    @Test
    fun mergesSources() {
        val r = ButtonRouter()
        r.update("gauche", setOf(SHIFT_UP_R))
        // La droite envoie le même bouton (relais par la gauche + connexion directe) : rien de neuf.
        assertEquals(emptyList<ButtonChange>(), r.update("droite", setOf(SHIFT_UP_R)))
        assertEquals(emptyList<ButtonChange>(), r.update("gauche", emptySet()))
        assertEquals(listOf(release(Obc.SHIFT_UP)), r.remove("droite"))
    }

    @Test
    fun rightOnlyMapsBToShiftDown() {
        val r = ButtonRouter()
        assertEquals(listOf(press(Obc.BACK)), r.update("click", setOf(B)))
        // Changer de mode pendant l'appui relâche l'ancien ID.
        assertEquals(listOf(release(Obc.BACK), press(Obc.SHIFT_DOWN)), r.setRightOnly(true))
        assertEquals(listOf(release(Obc.SHIFT_DOWN)), r.update("click", emptySet()))
    }

    @Test
    fun followsAppSupportedButtons() {
        val r = ButtonRouter()
        assertEquals(Obc.STEER_LEFT, r.actionFor(LEFT))
        r.setSupported(setOf(Obc.SHIFT_UP, Obc.SHIFT_DOWN, Obc.NAV_LEFT))
        assertEquals(Obc.NAV_LEFT, r.actionFor(LEFT))
        // Aucun candidat accepté : on envoie quand même le premier.
        assertEquals(Obc.BACK, r.actionFor(B))
        r.setSupported(emptySet())
        assertEquals(Obc.STEER_LEFT, r.actionFor(LEFT))
    }

    @Test
    fun releaseAll() {
        val r = ButtonRouter()
        r.update("a", setOf(SHIFT_UP_R))
        r.update("b", setOf(SHIFT_UP_L))
        assertEquals(listOf(release(Obc.SHIFT_UP), release(Obc.SHIFT_DOWN)), r.releaseAll())
    }
}
