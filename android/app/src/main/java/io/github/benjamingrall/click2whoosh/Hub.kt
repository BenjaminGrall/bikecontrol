package io.github.benjamingrall.click2whoosh

import android.content.Context
import android.content.SharedPreferences
import android.os.Handler
import android.os.Looper
import io.github.benjamingrall.click2whoosh.core.ButtonChange
import io.github.benjamingrall.click2whoosh.core.HalfState
import io.github.benjamingrall.click2whoosh.core.Level
import io.github.benjamingrall.click2whoosh.core.ZwiftButton
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.UUID
import java.util.concurrent.CopyOnWriteArrayList

/** Journal et état partagés entre le service et l'écran. Les listeners sont appelés sur le thread principal. */
object Hub {
    interface Listener {
        fun onLog(line: String) {}
        fun onStatus(status: Status) {}
        /** Boutons appuyés en ce moment (manettes et boutons de test). */
        fun onPressed(buttons: Set<ZwiftButton>) {}
        /** Commandes envoyées, et nombre d'apps qui les ont reçues. */
        fun onAction(changes: List<ButtonChange>, apps: Int) {}
    }

    data class Status(
        val running: Boolean = false,
        val port: Int = 0,
        val ips: List<String> = emptyList(),
        val apps: List<String> = emptyList(),
        val left: HalfState = HalfState(Level.OFF, "non trouvée"),
        val right: HalfState = HalfState(Level.OFF, "non trouvée"),
        val scanning: Boolean = false,
    )

    private const val MAX_LINES = 500
    private val main = Handler(Looper.getMainLooper())
    private val lines = ArrayDeque<String>()
    private val listeners = CopyOnWriteArrayList<Listener>()
    private val time = SimpleDateFormat("HH:mm:ss", Locale.FRANCE)

    @Volatile var debug = false

    @Volatile var status = Status()
        private set

    @Volatile var pressed: Set<ZwiftButton> = emptySet()
        private set

    fun log(message: String) {
        val line = "${synchronized(time) { time.format(Date()) }}  $message"
        synchronized(lines) {
            lines.addLast(line)
            while (lines.size > MAX_LINES) lines.removeFirstOrNull()
        }
        main.post { listeners.forEach { it.onLog(line) } }
    }

    fun debug(message: String) {
        if (debug) log(message)
    }

    fun history(): List<String> = synchronized(lines) { lines.toList() }

    fun setStatus(s: Status) {
        status = s
        main.post { listeners.forEach { it.onStatus(s) } }
    }

    fun setPressed(buttons: Set<ZwiftButton>) {
        if (buttons == pressed) return
        pressed = buttons
        main.post { listeners.forEach { it.onPressed(buttons) } }
    }

    fun action(changes: List<ButtonChange>, apps: Int) {
        main.post { listeners.forEach { it.onAction(changes, apps) } }
    }

    fun addListener(l: Listener) {
        listeners += l
    }

    fun removeListener(l: Listener) {
        listeners -= l
    }

    // Réglages persistants.

    fun prefs(context: Context): SharedPreferences =
        context.getSharedPreferences("click2whoosh", Context.MODE_PRIVATE)

    fun rightOnly(context: Context) = prefs(context).getBoolean("rightOnly", false)

    fun debugPref(context: Context) = prefs(context).getBoolean("debug", false)

    /** Identifiant stable annoncé dans le TXT mDNS. */
    fun deviceId(context: Context): String {
        val p = prefs(context)
        p.getString("id", null)?.let { return it }
        val id = UUID.randomUUID().toString().replace("-", "").take(12)
        p.edit().putString("id", id).apply()
        return id
    }
}
