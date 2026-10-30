package io.github.benjamingrall.click2whoosh.core

/**
 * Fusionne l'état de plusieurs sources (manettes, boutons de test) et le traduit en
 * changements OBC. N'émet que les transitions : les paquets répétés du Click ne renvoient rien.
 * Pas thread-safe : à appeler depuis un seul thread.
 */
class ButtonRouter {
    private val sources = HashMap<String, Set<ZwiftButton>>()
    private val active = LinkedHashSet<Int>()
    private var rightOnly = false
    private var supported: Set<Int>? = null

    fun update(source: String, pressed: Set<ZwiftButton>): List<ButtonChange> {
        sources[source] = pressed
        return diff()
    }

    fun remove(source: String): List<ButtonChange> {
        sources.remove(source)
        return diff()
    }

    /** Boutons appuyés, toutes sources confondues (pour l'affichage). */
    fun pressed(): Set<ZwiftButton> = sources.values.flatMapTo(LinkedHashSet()) { it }

    fun releaseAll(): List<ButtonChange> {
        sources.clear()
        return diff()
    }

    /** Mode « côté droit seul » : B passe la vitesse -, comme + passe la vitesse +. */
    fun setRightOnly(enabled: Boolean): List<ButtonChange> {
        rightOnly = enabled
        return diff()
    }

    /** Boutons annoncés par l'app (App Info) ; null ou vide = tous. */
    fun setSupported(ids: Set<Int>?): List<ButtonChange> {
        supported = ids?.takeIf { it.isNotEmpty() }
        return diff()
    }

    /** Premier ID accepté par l'app parmi les candidats du bouton, sinon le premier. */
    fun actionFor(button: ZwiftButton): Int {
        val candidates = candidates(button)
        val s = supported ?: return candidates.first()
        return candidates.firstOrNull { it in s } ?: candidates.first()
    }

    private fun candidates(button: ZwiftButton): List<Int> = when (button) {
        ZwiftButton.SHIFT_UP_R, ZwiftButton.SHIFT_DN_R -> listOf(Obc.SHIFT_UP)
        ZwiftButton.SHIFT_UP_L, ZwiftButton.SHIFT_DN_L -> listOf(Obc.SHIFT_DOWN)
        ZwiftButton.B -> if (rightOnly) listOf(Obc.SHIFT_DOWN) else listOf(Obc.BACK)
        ZwiftButton.A -> listOf(Obc.SELECT)
        ZwiftButton.Y -> listOf(Obc.MENU)
        ZwiftButton.Z -> listOf(Obc.EMOTE)
        ZwiftButton.LEFT -> listOf(Obc.STEER_LEFT, Obc.NAV_LEFT)
        ZwiftButton.RIGHT -> listOf(Obc.STEER_RIGHT, Obc.NAV_RIGHT)
        ZwiftButton.UP -> listOf(Obc.NAV_UP)
        ZwiftButton.DOWN -> listOf(Obc.NAV_DOWN)
        ZwiftButton.POWERUP_L, ZwiftButton.POWERUP_R -> listOf(Obc.POWERUP)
        ZwiftButton.ONOFF_L, ZwiftButton.ONOFF_R -> listOf(Obc.HUD)
    }

    private fun diff(): List<ButtonChange> {
        val desired = LinkedHashSet<Int>()
        for (pressed in sources.values) for (b in pressed) desired += actionFor(b)
        val changes = ArrayList<ButtonChange>()
        val released = active.filter { it !in desired }
        for (id in released) {
            active -= id
            changes += ButtonChange(id, false)
        }
        for (id in desired) if (active.add(id)) changes += ButtonChange(id, true)
        return changes
    }
}
