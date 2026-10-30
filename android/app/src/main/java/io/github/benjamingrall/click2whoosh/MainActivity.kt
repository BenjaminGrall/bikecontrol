package io.github.benjamingrall.click2whoosh

import android.Manifest
import android.animation.ValueAnimator
import android.app.Activity
import android.bluetooth.BluetoothManager
import android.content.ClipData
import android.content.ClipboardManager
import android.content.pm.PackageManager
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.location.LocationManager
import android.os.Build
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.view.ViewGroup.LayoutParams.MATCH_PARENT
import android.view.ViewGroup.LayoutParams.WRAP_CONTENT
import android.view.WindowInsets
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.Switch
import android.widget.TextView
import android.widget.Toast
import io.github.benjamingrall.click2whoosh.core.ButtonChange
import io.github.benjamingrall.click2whoosh.core.HalfState
import io.github.benjamingrall.click2whoosh.core.Level
import io.github.benjamingrall.click2whoosh.core.Obc
import io.github.benjamingrall.click2whoosh.core.ZwiftButton
import java.util.Locale
import kotlin.math.roundToInt

/** Écran unique, construit en code (aucune dépendance). */
class MainActivity : Activity(), Hub.Listener {
    /** Ligne d'état : pastille de couleur, titre et détail. */
    private class StatusRow(val view: View, val dot: View, val title: TextView, val detail: TextView) {
        fun set(color: Int, title: String, detail: String) {
            (dot.background as GradientDrawable).setColor(color)
            this.title.text = title
            this.title.setTextColor(if (color == Ui.DIM) Ui.MUTED else Ui.TEXT)
            this.detail.text = detail
            this.detail.visibility = if (detail.isEmpty()) View.GONE else View.VISIBLE
        }
    }

    private lateinit var startButton: Button
    private lateinit var bridgeRow: StatusRow
    private lateinit var appRow: StatusRow
    private lateinit var padRow: StatusRow
    private lateinit var pad: ClickPadView
    private lateinit var leftRow: StatusRow
    private lateinit var rightRow: StatusRow
    private lateinit var lastAction: TextView
    private lateinit var lastTarget: TextView
    private lateinit var lastBackground: GradientDrawable
    private lateinit var logToggle: Button
    private lateinit var logView: TextView
    private var logOpen = false
    private var flash: ValueAnimator? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        Hub.debug = Hub.debugPref(this)
        setContentView(buildUi())
    }

    override fun onStart() {
        super.onStart()
        Hub.addListener(this)
        onStatus(Hub.status)
        onPressed(Hub.pressed)
        renderLog()
    }

    override fun onStop() {
        Hub.removeListener(this)
        super.onStop()
    }

    // Mises à jour venant du service.

    override fun onLog(line: String) {
        if (logOpen) renderLog()
    }

    override fun onPressed(buttons: Set<ZwiftButton>) {
        pad.pressed = buttons
    }

    override fun onAction(changes: List<ButtonChange>, apps: Int) {
        val press = changes.lastOrNull { it.pressed } ?: return
        lastAction.text = Obc.buttonName(press.id).uppercase(Locale.FRANCE)
        lastAction.setTextColor(Ui.TEXT)
        if (apps > 0) {
            lastTarget.text = "envoyée à MyWhoosh"
            lastTarget.setTextColor(Ui.GREEN)
        } else {
            lastTarget.text = "aucune app connectée : MyWhoosh ne reçoit rien"
            lastTarget.setTextColor(Ui.RED)
        }
        flash?.cancel()
        flash = ValueAnimator.ofArgb(if (apps > 0) Ui.ACCENT else Ui.RED, Ui.CARD).apply {
            duration = 700
            addUpdateListener { lastBackground.setColor(it.animatedValue as Int) }
            start()
        }
    }

    override fun onStatus(status: Hub.Status) {
        startButton.text = if (status.running) "Arrêter" else "Démarrer"
        startButton.background = Ui.ripple(
            if (status.running) {
                Ui.rounded(Ui.CARD, dp(26).toFloat(), Ui.LINE, dp(2))
            } else {
                Ui.rounded(Ui.ACCENT, dp(26).toFloat())
            },
        )

        if (!status.running) {
            bridgeRow.set(Ui.DIM, "Pont arrêté", "Appuie sur Démarrer")
            appRow.set(Ui.DIM, "MyWhoosh", "pas connecté")
            padRow.set(Ui.DIM, "Manette", "pas connectée")
            pad.leftLevel = Level.OFF
            pad.rightLevel = Level.OFF
            leftRow.set(Ui.DIM, "Gauche", "pont arrêté")
            rightRow.set(Ui.DIM, "Droite", "pont arrêté")
            return
        }

        val where = listOf("port ${status.port}") + status.ips
        bridgeRow.set(Ui.GREEN, "Pont actif", where.joinToString(" · "))

        if (status.apps.isNotEmpty()) {
            appRow.set(Ui.GREEN, "MyWhoosh connecté", status.apps.joinToString())
        } else {
            appRow.set(Ui.AMBER, "En attente de MyWhoosh", "Dans MyWhoosh : Connexion, icône OpenBikeControl, « Oui »")
        }

        val best = maxOf(status.left.level, status.right.level)
        when (best) {
            Level.WORKING -> padRow.set(Ui.GREEN, "Manette OK", "boutons reçus")
            Level.READY -> padRow.set(Ui.ACCENT, "Manette connectée", "appuie sur un bouton du Click")
            Level.CONNECTING -> padRow.set(Ui.AMBER, "Connexion à la manette…", "")
            Level.OFF -> if (status.scanning) {
                padRow.set(Ui.AMBER, "Recherche de la manette…", "appuie sur un bouton du Click pour le réveiller")
            } else {
                padRow.set(Ui.RED, "Aucune manette", "ferme Zwift, puis « Rechercher les manettes »")
            }
        }

        pad.leftLevel = status.left.level
        pad.rightLevel = status.right.level
        setHalf(leftRow, "Gauche", status.left)
        setHalf(rightRow, "Droite", status.right)
    }

    private fun setHalf(row: StatusRow, name: String, half: HalfState) {
        row.set(Ui.levelColor(half.level), name, half.text)
    }

    private fun renderLog() {
        logView.text = Hub.history().takeLast(120).joinToString("\n")
    }

    // Actions.

    private fun toggle() {
        if (BridgeService.current != null) {
            BridgeService.stop(this)
            return
        }
        val missing = neededPermissions().filter { checkSelfPermission(it) != PackageManager.PERMISSION_GRANTED }
        if (missing.isEmpty()) startBridge() else requestPermissions(missing.toTypedArray(), REQUEST_PERMISSIONS)
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        if (requestCode != REQUEST_PERMISSIONS) return
        val denied = permissions.filterIndexed { i, _ -> grantResults.getOrNull(i) != PackageManager.PERMISSION_GRANTED }
        if (denied.isNotEmpty()) {
            Hub.log("Permission(s) refusée(s) : ${denied.joinToString { it.substringAfterLast('.') }}")
        }
        // Même sans Bluetooth, le serveur et les boutons de test restent utiles pour tester MyWhoosh.
        startBridge()
    }

    private fun neededPermissions(): List<String> = buildList {
        if (Build.VERSION.SDK_INT >= 31) {
            add(Manifest.permission.BLUETOOTH_SCAN)
            add(Manifest.permission.BLUETOOTH_CONNECT)
        } else {
            add(Manifest.permission.ACCESS_FINE_LOCATION)
        }
        if (Build.VERSION.SDK_INT >= 33) add(Manifest.permission.POST_NOTIFICATIONS)
    }

    private fun startBridge() {
        val adapter = getSystemService(BluetoothManager::class.java)?.adapter
        if (adapter == null) {
            Hub.log("Pas de Bluetooth sur cet appareil")
        } else if (!adapter.isEnabled) {
            Hub.log("Bluetooth désactivé : active-le pour le Click")
            Toast.makeText(this, "Active le Bluetooth pour le Click", Toast.LENGTH_LONG).show()
        }
        if (Build.VERSION.SDK_INT < 31) {
            val lm = getSystemService(LocationManager::class.java)
            val on = when {
                lm == null -> true
                Build.VERSION.SDK_INT >= 28 -> lm.isLocationEnabled
                else -> lm.isProviderEnabled(LocationManager.GPS_PROVIDER) ||
                    lm.isProviderEnabled(LocationManager.NETWORK_PROVIDER)
            }
            if (!on) Hub.log("Localisation désactivée : Android 11 et moins en ont besoin pour la recherche Bluetooth")
        }
        BridgeService.start(this)
    }

    private fun test(button: ZwiftButton) {
        BridgeService.current?.testPress(button)
            ?: Toast.makeText(this, "Démarre d'abord le pont", Toast.LENGTH_SHORT).show()
    }

    private fun copyLog() {
        getSystemService(ClipboardManager::class.java)
            ?.setPrimaryClip(ClipData.newPlainText("click2whoosh", Hub.history().joinToString("\n")))
        Toast.makeText(this, "Journal copié", Toast.LENGTH_SHORT).show()
    }

    // Interface.

    private fun buildUi(): View {
        val content = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(16), dp(16), dp(24))
        }

        content.addView(text("click2whoosh", 26f, Ui.TEXT, bold = true))
        content.addView(text("Zwift Click vers MyWhoosh", 14f, Ui.MUTED), margins(bottom = 16))

        startButton = Button(this).apply {
            isAllCaps = false
            textSize = 17f
            typeface = Typeface.DEFAULT_BOLD
            setTextColor(Ui.TEXT)
            stateListAnimator = null
            setOnClickListener { toggle() }
        }
        content.addView(startButton, LinearLayout.LayoutParams(MATCH_PARENT, dp(52)).apply { bottomMargin = dp(16) })

        // État général.
        val status = card()
        bridgeRow = statusRow().also { status.addView(it.view) }
        appRow = statusRow().also { status.addView(it.view, margins(top = 12)) }
        padRow = statusRow().also { status.addView(it.view, margins(top = 12)) }
        content.addView(status, margins(bottom = 12))

        // Manette.
        val padCard = card()
        padCard.addView(text("Manette", 13f, Ui.MUTED, bold = true), margins(bottom = 10))
        pad = ClickPadView(this).apply {
            onTap = { test(it) }
            rightOnly = Hub.rightOnly(this@MainActivity)
        }
        padCard.addView(pad)
        val halves = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        leftRow = statusRow(small = true)
        rightRow = statusRow(small = true)
        halves.addView(leftRow.view, LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f).apply { marginEnd = dp(6) })
        halves.addView(rightRow.view, LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f).apply { marginStart = dp(6) })
        padCard.addView(halves, margins(top = 12))
        padCard.addView(
            text("Touche un bouton du dessin pour le tester sans le Click.", 12f, Ui.MUTED),
            margins(top = 10),
        )
        padCard.addView(flatButton("Rechercher les manettes") {
            BridgeService.current?.rescan()
                ?: Toast.makeText(this, "Démarre d'abord le pont", Toast.LENGTH_SHORT).show()
        }, margins(top = 4))
        content.addView(padCard, margins(bottom = 12))

        // Dernière commande envoyée.
        lastBackground = Ui.rounded(Ui.CARD, dp(16).toFloat())
        val last = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            background = lastBackground
            setPadding(dp(16), dp(14), dp(16), dp(14))
        }
        last.addView(text("Dernière commande", 13f, Ui.MUTED, bold = true))
        lastAction = text("aucune pour l'instant", 26f, Ui.DIM, bold = true)
        last.addView(lastAction, margins(top = 4))
        lastTarget = text("", 13f, Ui.MUTED)
        last.addView(lastTarget)
        content.addView(last, margins(bottom = 12))

        // Réglages.
        val settings = card()
        settings.addView(text("Réglages", 13f, Ui.MUTED, bold = true))
        settings.addView(toggleRow(
            "Côté droit seul",
            "+ passe la vitesse +, B la vitesse −",
            Hub.rightOnly(this),
        ) { on ->
            Hub.prefs(this).edit().putBoolean("rightOnly", on).apply()
            BridgeService.current?.setRightOnly(on)
            pad.rightOnly = on
            Hub.log(if (on) "Côté droit seul : B = vitesse −" else "Côté droit seul désactivé : B = retour")
        }, margins(top = 8))
        settings.addView(toggleRow("Debug", "octets bruts et noms des boutons dans le journal", Hub.debug) { on ->
            Hub.debug = on
            Hub.prefs(this).edit().putBoolean("debug", on).apply()
        }, margins(top = 4))
        content.addView(settings, margins(bottom = 12))

        // Journal, replié par défaut.
        val log = card()
        val logHeader = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        logHeader.addView(text("Journal", 13f, Ui.MUTED, bold = true), LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f))
        logToggle = flatButton("Afficher") {
            logOpen = !logOpen
            logView.visibility = if (logOpen) View.VISIBLE else View.GONE
            logToggle.text = if (logOpen) "Masquer" else "Afficher"
            if (logOpen) renderLog()
        }
        logHeader.addView(logToggle)
        logHeader.addView(flatButton("Copier") { copyLog() })
        log.addView(logHeader)
        logView = text("", 11f, Ui.MUTED).apply {
            typeface = Typeface.MONOSPACE
            setTextIsSelectable(true)
            visibility = View.GONE
        }
        log.addView(logView, margins(top = 8))
        content.addView(log)

        val scroll = ScrollView(this).apply {
            setBackgroundColor(Ui.BG)
            isFillViewport = true
            clipToPadding = false
            addView(content)
        }
        // targetSdk 35 : affichage bord à bord imposé sur Android 15, on gère les marges nous-mêmes.
        if (Build.VERSION.SDK_INT >= 30) {
            @Suppress("DEPRECATION")
            if (Build.VERSION.SDK_INT < 35) window.setDecorFitsSystemWindows(false)
            scroll.setOnApplyWindowInsetsListener { v, insets ->
                val bars = insets.getInsets(WindowInsets.Type.systemBars() or WindowInsets.Type.displayCutout())
                v.setPadding(bars.left, bars.top, bars.right, bars.bottom)
                insets
            }
        }
        return scroll
    }

    private fun dp(v: Int) = (v * resources.displayMetrics.density).roundToInt()

    private fun margins(top: Int = 0, bottom: Int = 0) =
        LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT).apply {
            topMargin = dp(top)
            bottomMargin = dp(bottom)
        }

    private fun text(value: String, size: Float, color: Int, bold: Boolean = false) = TextView(this).apply {
        text = value
        textSize = size
        setTextColor(color)
        if (bold) typeface = Typeface.DEFAULT_BOLD
    }

    private fun card() = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        background = Ui.rounded(Ui.CARD, dp(16).toFloat())
        setPadding(dp(16), dp(14), dp(16), dp(14))
    }

    private fun statusRow(small: Boolean = false): StatusRow {
        val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        val size = dp(if (small) 10 else 12)
        val dot = View(this).apply { background = Ui.dot(Ui.DIM) }
        row.addView(dot, LinearLayout.LayoutParams(size, size).apply {
            topMargin = dp(if (small) 4 else 5)
            marginEnd = dp(10)
        })
        val texts = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        val title = text("", if (small) 14f else 16f, Ui.TEXT, bold = true)
        val detail = text("", if (small) 12f else 13f, Ui.MUTED)
        texts.addView(title)
        texts.addView(detail)
        row.addView(texts, LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f))
        return StatusRow(row, dot, title, detail)
    }

    private fun flatButton(label: String, onClick: () -> Unit) = Button(this).apply {
        text = label
        isAllCaps = false
        setTextColor(Ui.ACCENT)
        background = Ui.ripple(null)
        setOnClickListener { onClick() }
    }

    private fun toggleRow(title: String, detail: String, checked: Boolean, onChange: (Boolean) -> Unit): View {
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, dp(6), 0, dp(6))
        }
        val texts = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        texts.addView(text(title, 16f, Ui.TEXT))
        texts.addView(text(detail, 12f, Ui.MUTED))
        row.addView(texts, LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f))
        row.addView(Switch(this).apply {
            isChecked = checked
            setOnCheckedChangeListener { _, on -> onChange(on) }
        })
        return row
    }

    private companion object {
        const val REQUEST_PERMISSIONS = 1
    }
}
