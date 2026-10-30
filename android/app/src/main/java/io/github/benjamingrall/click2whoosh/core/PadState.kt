package io.github.benjamingrall.click2whoosh.core

/** État d'une manette, tel que remonté par la couche Bluetooth. */
data class ControllerInfo(val type: ZwiftType, val state: String, val ready: Boolean, val noZwift: Boolean)

enum class Level { OFF, CONNECTING, READY, WORKING }

data class HalfState(val level: Level, val text: String)

/** Ce que l'écran affiche pour chaque moitié du Click (gauche : pavé et -, droite : ABYZ et +). */
object PadState {
    /**
     * [seen] : un bouton de cette moitié a déjà été reçu, preuve qu'elle marche vraiment.
     * La moitié droite peut passer par la gauche, qui relaie ses boutons.
     */
    fun half(left: Boolean, controllers: List<ControllerInfo>, seen: Boolean, scanning: Boolean): HalfState {
        val direct = controllers.filter { if (left) it.type.leftHalf else it.type.rightHalf }
        val relays = if (left) emptyList() else controllers.filter { it.ready && it.type.relaysRight }
        val directReady = direct.any { it.ready }
        val suffix = if ((direct + relays).any { it.noZwift }) " (mode sans Zwift)" else ""
        return when {
            seen && (directReady || relays.isNotEmpty()) -> HalfState(Level.WORKING, "OK, boutons reçus$suffix")
            directReady -> HalfState(Level.READY, "connectée, appuie sur un bouton$suffix")
            relays.isNotEmpty() -> HalfState(Level.READY, "via la gauche, appuie sur un bouton$suffix")
            direct.isNotEmpty() -> HalfState(Level.CONNECTING, direct.first().state)
            scanning -> HalfState(Level.OFF, "recherche… appuie sur un bouton du Click")
            else -> HalfState(Level.OFF, "non trouvée")
        }
    }
}
