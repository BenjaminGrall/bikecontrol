package io.github.benjamingrall.click2whoosh

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.graphics.Typeface
import android.view.MotionEvent
import android.view.View
import io.github.benjamingrall.click2whoosh.core.Level
import io.github.benjamingrall.click2whoosh.core.ZwiftButton
import kotlin.math.hypot
import kotlin.math.min

/**
 * Dessin des deux moitiés du Click V2 : gauche (- et pavé), droite (+ et ABYZ).
 * Le contour suit l'état de connexion, les boutons appuyés s'allument.
 * Toucher un bouton du dessin appelle [onTap] (bouton de test).
 */
class ClickPadView(context: Context) : View(context) {
    var onTap: ((ZwiftButton) -> Unit)? = null

    var leftLevel = Level.OFF
        set(v) { field = v; invalidate() }
    var rightLevel = Level.OFF
        set(v) { field = v; invalidate() }
    var pressed: Set<ZwiftButton> = emptySet()
        set(v) { field = v; invalidate() }
    /** Mode « côté droit seul » : B affiche un petit « - ». */
    var rightOnly = false
        set(v) { field = v; invalidate() }

    private enum class Shape { PILL, ROUND, UP, DOWN, LEFT, RIGHT }

    private class Key(
        val button: ZwiftButton,
        val shape: Shape,
        val label: String,
        val labelColor: Int,
        val leftHalf: Boolean,
        val rect: RectF,
    )

    private val dp = resources.displayMetrics.density
    private val bodies = arrayOf(RectF(), RectF())
    private var keys = emptyList<Key>()
    private val fill = Paint(Paint.ANTI_ALIAS_FLAG)
    private val stroke = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 2.5f * dp
    }
    private val text = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        textAlign = Paint.Align.CENTER
        typeface = Typeface.DEFAULT_BOLD
    }
    private val path = Path()

    init {
        contentDescription = "Manette Zwift Click"
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val w = MeasureSpec.getSize(widthMeasureSpec)
        val h = min(w * 0.6f, 260 * dp).toInt()
        setMeasuredDimension(w, h)
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        val gap = 12 * dp
        val bw = (w - gap) / 2
        val list = ArrayList<Key>()
        for (i in 0..1) {
            val left = i == 0
            val x0 = i * (bw + gap)
            val sw = stroke.strokeWidth / 2
            bodies[i].set(x0 + sw, sw, x0 + bw - sw, h - sw)
            val cx = x0 + bw / 2
            // Bouton de vitesse en haut.
            val pw = bw * 0.6f / 2
            val ph = h * 0.08f
            val py = h * 0.2f
            list += Key(
                if (left) ZwiftButton.SHIFT_UP_L else ZwiftButton.SHIFT_UP_R,
                Shape.PILL, if (left) "−" else "+", Ui.TEXT, left,
                RectF(cx - pw, py - ph, cx + pw, py + ph),
            )
            // Losange de 4 boutons.
            val cy = h * 0.62f
            val d = min(bw, h.toFloat()) * 0.22f
            val r = d * 0.62f
            fun round(b: ZwiftButton, shape: Shape, label: String, color: Int, x: Float, y: Float) {
                list += Key(b, shape, label, color, left, RectF(x - r, y - r, x + r, y + r))
            }
            if (left) {
                round(ZwiftButton.UP, Shape.UP, "", Ui.MUTED, cx, cy - d)
                round(ZwiftButton.LEFT, Shape.LEFT, "", Ui.MUTED, cx - d, cy)
                round(ZwiftButton.RIGHT, Shape.RIGHT, "", Ui.MUTED, cx + d, cy)
                round(ZwiftButton.DOWN, Shape.DOWN, "", Ui.MUTED, cx, cy + d)
            } else {
                round(ZwiftButton.Y, Shape.ROUND, "Y", 0xFF4FC3F7.toInt(), cx, cy - d)
                round(ZwiftButton.Z, Shape.ROUND, "Z", 0xFFFF7043.toInt(), cx - d, cy)
                round(ZwiftButton.A, Shape.ROUND, "A", 0xFF9CCC65.toInt(), cx + d, cy)
                round(ZwiftButton.B, Shape.ROUND, "B", 0xFFFF4081.toInt(), cx, cy + d)
            }
        }
        keys = list
    }

    override fun onDraw(canvas: Canvas) {
        for (i in 0..1) {
            val level = if (i == 0) leftLevel else rightLevel
            fill.color = Ui.CARD
            canvas.drawRoundRect(bodies[i], 28 * dp, 28 * dp, fill)
            stroke.color = if (level == Level.OFF) Ui.LINE else Ui.levelColor(level)
            canvas.drawRoundRect(bodies[i], 28 * dp, 28 * dp, stroke)
        }
        for (k in keys) {
            val off = (if (k.leftHalf) leftLevel else rightLevel) == Level.OFF
            val on = k.button in pressed
            val alpha = if (off && !on) 110 else 255
            fill.color = if (on) Ui.ACCENT else Ui.KEY
            fill.alpha = alpha
            val r = k.rect
            if (k.shape == Shape.PILL) {
                canvas.drawRoundRect(r, r.height() / 2, r.height() / 2, fill)
            } else {
                canvas.drawCircle(r.centerX(), r.centerY(), r.width() / 2, fill)
            }
            val labelColor = if (on) Ui.TEXT else k.labelColor
            when (k.shape) {
                Shape.PILL, Shape.ROUND -> {
                    text.color = labelColor
                    text.alpha = alpha
                    text.textSize = if (k.shape == Shape.PILL) r.height() * 0.8f else r.height() * 0.45f
                    canvas.drawText(k.label, r.centerX(), r.centerY() - (text.ascent() + text.descent()) / 2, text)
                }
                else -> {
                    fill.color = labelColor
                    fill.alpha = alpha
                    drawArrow(canvas, k.shape, r.centerX(), r.centerY(), r.width() * 0.18f)
                }
            }
            if (rightOnly && k.button == ZwiftButton.B) {
                text.color = Ui.ACCENT
                text.alpha = 255
                text.textSize = r.height() * 0.4f
                canvas.drawText("−", r.right + r.width() * 0.05f, r.top + r.height() * 0.15f, text)
            }
        }
        fill.alpha = 255
    }

    private fun drawArrow(canvas: Canvas, shape: Shape, cx: Float, cy: Float, s: Float) {
        path.reset()
        when (shape) {
            Shape.UP -> { path.moveTo(cx, cy - s); path.lineTo(cx + s, cy + s * 0.7f); path.lineTo(cx - s, cy + s * 0.7f) }
            Shape.DOWN -> { path.moveTo(cx, cy + s); path.lineTo(cx + s, cy - s * 0.7f); path.lineTo(cx - s, cy - s * 0.7f) }
            Shape.LEFT -> { path.moveTo(cx - s, cy); path.lineTo(cx + s * 0.7f, cy - s); path.lineTo(cx + s * 0.7f, cy + s) }
            else -> { path.moveTo(cx + s, cy); path.lineTo(cx - s * 0.7f, cy - s); path.lineTo(cx - s * 0.7f, cy + s) }
        }
        path.close()
        canvas.drawPath(path, fill)
    }

    @SuppressLint("ClickableViewAccessibility")
    override fun onTouchEvent(event: MotionEvent): Boolean {
        if (event.action == MotionEvent.ACTION_UP) performClick()
        if (event.action != MotionEvent.ACTION_DOWN) return super.onTouchEvent(event)
        val hit = keys.firstOrNull { k ->
            val r = k.rect
            if (k.shape == Shape.PILL) {
                r.contains(event.x, event.y)
            } else {
                hypot(event.x - r.centerX(), event.y - r.centerY()) <= r.width() * 0.6f
            }
        } ?: return false
        onTap?.invoke(hit.button)
        return true
    }

    override fun performClick(): Boolean = super.performClick()
}
