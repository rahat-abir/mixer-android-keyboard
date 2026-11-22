package dev.banglish.keyboard

import android.content.Context
import android.content.res.Configuration

/** Keyboard colours. Picked once per input-view start, so drawing never branches on theme. */
class Theme(
    val background: Int,
    val key: Int,
    val keyPressed: Int,
    val functionKey: Int,
    val text: Int,
    val hintText: Int,
    val accent: Int,
    val accentText: Int,
    val popup: Int,
    val popupSelected: Int,
    val divider: Int,
    val trail: Int,
) {
    companion object {
        val LIGHT = Theme(
            background = 0xFFE9ECEF.toInt(),
            key = 0xFFFFFFFF.toInt(),
            keyPressed = 0xFFCDD3D9.toInt(),
            functionKey = 0xFFD3D8DE.toInt(),
            text = 0xFF1B1F23.toInt(),
            hintText = 0xFF7A838C.toInt(),
            accent = 0xFF0B8A5F.toInt(),
            accentText = 0xFFFFFFFF.toInt(),
            popup = 0xFFFFFFFF.toInt(),
            popupSelected = 0xFF0B8A5F.toInt(),
            divider = 0x22000000,
            trail = 0xCC0B8A5F.toInt(),
        )
        val DARK = Theme(
            background = 0xFF15181B.toInt(),
            key = 0xFF2B3035.toInt(),
            keyPressed = 0xFF464D54.toInt(),
            functionKey = 0xFF1F2327.toInt(),
            text = 0xFFF1F3F5.toInt(),
            hintText = 0xFF8D969E.toInt(),
            accent = 0xFF3DDC97.toInt(),
            accentText = 0xFF0B1F17.toInt(),
            popup = 0xFF3A4046.toInt(),
            popupSelected = 0xFF3DDC97.toInt(),
            divider = 0x22FFFFFF,
            trail = 0xCC3DDC97.toInt(),
        )

        fun resolve(context: Context, pref: Int): Theme = when (pref) {
            1 -> LIGHT
            2 -> DARK
            else -> {
                val night = context.resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK
                if (night == Configuration.UI_MODE_NIGHT_YES) DARK else LIGHT
            }
        }
    }
}
