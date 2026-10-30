package io.github.benjamingrall.click2whoosh

import android.content.res.ColorStateList
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.Drawable
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.RippleDrawable
import io.github.benjamingrall.click2whoosh.core.Level

/** Couleurs et formes de l'interface (thème sombre). Garder BG aligné avec res/values/colors.xml. */
internal object Ui {
    const val BG = 0xFF101114.toInt()
    const val CARD = 0xFF1B1D22.toInt()
    const val KEY = 0xFF2A2D35.toInt()
    const val LINE = 0xFF353943.toInt()
    const val TEXT = 0xFFF2F3F5.toInt()
    const val MUTED = 0xFF9AA0A6.toInt()
    const val DIM = 0xFF5F6368.toInt()
    const val ACCENT = 0xFFFF6A00.toInt()
    const val GREEN = 0xFF34C759.toInt()
    const val AMBER = 0xFFFFB020.toInt()
    const val RED = 0xFFFF453A.toInt()

    fun levelColor(level: Level) = when (level) {
        Level.OFF -> DIM
        Level.CONNECTING -> AMBER
        Level.READY -> ACCENT
        Level.WORKING -> GREEN
    }

    fun rounded(color: Int, radius: Float, stroke: Int = 0, strokeWidth: Int = 0) = GradientDrawable().apply {
        setColor(color)
        cornerRadius = radius
        if (strokeWidth > 0) setStroke(strokeWidth, stroke)
    }

    /** Effet d'appui sur [content] ; sans contenu, l'effet reste dans les limites de la vue. */
    fun ripple(content: Drawable?) =
        RippleDrawable(ColorStateList.valueOf(0x40FFFFFF), content, content ?: ColorDrawable(0xFFFFFFFF.toInt()))

    fun dot(color: Int) = GradientDrawable().apply {
        shape = GradientDrawable.OVAL
        setColor(color)
    }
}
