package io.github.benjamingrall.click2whoosh

import android.Manifest
import android.app.Activity
import android.bluetooth.BluetoothManager
import android.content.ClipData
import android.content.ClipboardManager
import android.content.pm.PackageManager
import android.graphics.Typeface
import android.location.LocationManager
import android.os.Build
import android.os.Bundle
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
import io.github.benjamingrall.click2whoosh.core.ZwiftButton
import kotlin.math.roundToInt

/** Écran unique, construit en code (aucune dépendance). */
class MainActivity : Activity(), Hub.Listener {
    private lateinit var statusView: TextView
    private lateinit var startButton: Button
    private lateinit var logView: TextView
    private lateinit var logScroll: ScrollView
    private var logLines = 0

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        Hub.debug = Hub.debugPref(this)
        setContentView(buildUi())
    }

    override fun onStart() {
        super.onStart()
        Hub.addListener(this)
        showHistory()
        onStatus(Hub.status)
    }

    override fun onStop() {
        Hub.removeListener(this)
        super.onStop()
    }

    override fun onLog(line: String) {
        if (++logLines > 600) {
            showHistory()
        } else {
            logView.append(line + "\n")
            scrollToEnd()
        }
    }

    override fun onStatus(status: Hub.Status) {
        startButton.text = if (status.running) "Arrêter" else "Démarrer"
        statusView.text = if (!status.running) {
            "Pont arrêté."
        } else {
            "Annonce : ${status.server}\nApp(s) : ${status.apps}\nManette(s) :\n${status.controllers}"
        }
    }

    private fun showHistory() {
        val lines = Hub.history()
        logLines = lines.size
        logView.text = lines.joinToString("") { it + "\n" }
        scrollToEnd()
    }

    private fun scrollToEnd() {
        logScroll.post { logScroll.fullScroll(View.FOCUS_DOWN) }
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
        BridgeService.current?.testPress(button) ?: Hub.log("Démarre d'abord le pont")
    }

    private fun copyLog() {
        getSystemService(ClipboardManager::class.java)
            ?.setPrimaryClip(ClipData.newPlainText("click2whoosh", Hub.history().joinToString("\n")))
        Toast.makeText(this, "Journal copié", Toast.LENGTH_SHORT).show()
    }

    // Interface.

    private fun buildUi(): View {
        val base = dp(16)
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(base, base, base, base)
        }
        root.addView(TextView(this).apply {
            text = "click2whoosh"
            textSize = 22f
            setTypeface(typeface, Typeface.BOLD)
        })
        root.addView(TextView(this).apply {
            text = "1. Démarrer.  2. Dans MyWhoosh : Connexion, icône OpenBikeControl, « Oui ».  " +
                "3. Teste « Vitesse + ».  4. Appuie sur un bouton du Click pour le réveiller."
            textSize = 13f
            setPadding(0, dp(4), 0, dp(8))
        })
        statusView = TextView(this).apply {
            textSize = 14f
            setPadding(0, 0, 0, dp(8))
        }
        root.addView(statusView)

        startButton = button("Démarrer") { toggle() }
        root.addView(row(startButton, button("Rechercher les manettes") {
            BridgeService.current?.rescan() ?: Hub.log("Démarre d'abord le pont")
        }))
        root.addView(row(
            button("Vitesse −") { test(ZwiftButton.SHIFT_UP_L) },
            button("Vitesse +") { test(ZwiftButton.SHIFT_UP_R) },
        ))
        root.addView(toggleRow("Côté droit seul : + = vitesse +, B = vitesse −", Hub.rightOnly(this)) { on ->
            Hub.prefs(this).edit().putBoolean("rightOnly", on).apply()
            BridgeService.current?.setRightOnly(on)
            Hub.log(if (on) "Côté droit seul : B = vitesse −" else "Côté droit seul désactivé : B = retour")
        })
        root.addView(toggleRow("Debug : octets bruts et noms des boutons", Hub.debug) { on ->
            Hub.debug = on
            Hub.prefs(this).edit().putBoolean("debug", on).apply()
        })
        root.addView(button("Copier le journal") { copyLog() })

        logView = TextView(this).apply {
            typeface = Typeface.MONOSPACE
            textSize = 11f
            setTextIsSelectable(true)
        }
        logScroll = ScrollView(this).apply { addView(logView) }
        root.addView(logScroll, LinearLayout.LayoutParams(MATCH_PARENT, 0, 1f))

        // targetSdk 35 : affichage bord à bord imposé sur Android 15, on gère les marges nous-mêmes.
        if (Build.VERSION.SDK_INT >= 30) {
            @Suppress("DEPRECATION")
            if (Build.VERSION.SDK_INT < 35) window.setDecorFitsSystemWindows(false)
            root.setOnApplyWindowInsetsListener { v, insets ->
                val bars = insets.getInsets(WindowInsets.Type.systemBars() or WindowInsets.Type.displayCutout())
                v.setPadding(base + bars.left, base + bars.top, base + bars.right, base + bars.bottom)
                insets
            }
        }
        return root
    }

    private fun dp(v: Int) = (v * resources.displayMetrics.density).roundToInt()

    private fun button(label: String, onClick: () -> Unit) = Button(this).apply {
        text = label
        isAllCaps = false
        setOnClickListener { onClick() }
    }

    private fun row(vararg views: View) = LinearLayout(this).apply {
        orientation = LinearLayout.HORIZONTAL
        views.forEach { addView(it, LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f)) }
    }

    private fun toggleRow(label: String, checked: Boolean, onChange: (Boolean) -> Unit) = Switch(this).apply {
        text = label
        isChecked = checked
        setPadding(0, dp(6), 0, dp(6))
        setOnCheckedChangeListener { _, on -> onChange(on) }
    }

    private companion object {
        const val REQUEST_PERMISSIONS = 1
    }
}
