package dev.banglish.keyboard

import android.content.Context
import android.content.SharedPreferences
import android.os.UserManager

/**
 * Settings live in device-protected storage so they are readable on the lock screen
 * (the service is directBootAware). Anything personal — learned words, clipboard
 * history — goes to normal storage and is only touched once the user has unlocked.
 */
class Prefs(context: Context) {
    private val app = context.applicationContext
    val sp: SharedPreferences =
        app.createDeviceProtectedStorageContext().getSharedPreferences("settings", Context.MODE_PRIVATE)

    fun isUnlocked(): Boolean = app.getSystemService(UserManager::class.java)?.isUserUnlocked ?: true

    var bangla: Boolean
        get() = sp.getBoolean(KEY_BANGLA, false)
        set(v) = sp.edit().putBoolean(KEY_BANGLA, v).apply()

    val numberRow get() = sp.getBoolean(KEY_NUMBER_ROW, true)
    val glide get() = sp.getBoolean(KEY_GLIDE, true)
    val banglaGlide get() = sp.getBoolean(KEY_BANGLA_GLIDE, true)
    /** Keep the rolling glide log (what was swiped / picked) for tuning. Counters are always kept. */
    val logGlides get() = sp.getBoolean(KEY_LOG_GLIDES, true)
    val autocorrect get() = sp.getBoolean(KEY_AUTOCORRECT, true)
    val autoCap get() = sp.getBoolean(KEY_AUTOCAP, true)
    val doubleSpacePeriod get() = sp.getBoolean(KEY_DOUBLE_SPACE, true)
    val sound get() = sp.getBoolean(KEY_SOUND, false)
    /** 0 = off, 1 = light, 2 = normal, 3 = strong */
    val haptic get() = sp.getInt(KEY_HAPTIC, 2)
    // Layout, in dp. Defaults follow Samsung Keyboard on a 360dp-wide phone: 43dp letter keys,
    // 35dp number row, ~5dp between keys, 8dp between rows, 42dp toolbar.
    val keyHeightDp get() = sp.getInt(KEY_HEIGHT, DEF_KEY_HEIGHT)
    val numberRowHeightDp get() = sp.getInt(KEY_NUMBER_ROW_HEIGHT, DEF_NUMBER_ROW_HEIGHT)
    val keyGapDp get() = sp.getInt(KEY_KEY_GAP, DEF_KEY_GAP)
    val rowGapDp get() = sp.getInt(KEY_ROW_GAP, DEF_ROW_GAP)
    val toolbarDp get() = sp.getInt(KEY_TOOLBAR, DEF_TOOLBAR)
    val adaptiveTouch get() = sp.getBoolean(KEY_ADAPTIVE, true)

    /** Back to the Samsung-style defaults. */
    fun resetLayout() {
        sp.edit().remove(KEY_HEIGHT).remove(KEY_NUMBER_ROW_HEIGHT).remove(KEY_KEY_GAP).remove(KEY_ROW_GAP)
            .remove(KEY_TOOLBAR).remove(KEY_NUMBER_ROW).remove(KEY_ADAPTIVE).apply()
    }
    val showPopups get() = sp.getBoolean(KEY_POPUPS, true)
    /** 0 = follow system, 1 = light, 2 = dark */
    val theme get() = sp.getInt(KEY_THEME, 0)

    var emojiRecents: String
        get() = sp.getString(KEY_EMOJI_RECENTS, "") ?: ""
        set(v) = sp.edit().putString(KEY_EMOJI_RECENTS, v).apply()

    companion object {
        const val KEY_BANGLA = "bangla"
        const val KEY_NUMBER_ROW = "number_row"
        const val KEY_GLIDE = "glide"
        const val KEY_BANGLA_GLIDE = "bangla_glide"
        const val KEY_LOG_GLIDES = "log_glides"
        const val KEY_AUTOCORRECT = "autocorrect"
        const val KEY_AUTOCAP = "autocap"
        const val KEY_DOUBLE_SPACE = "double_space"
        const val KEY_SOUND = "sound"
        const val KEY_HAPTIC = "haptic"
        const val KEY_HEIGHT = "key_height_dp"
        const val KEY_NUMBER_ROW_HEIGHT = "number_row_height_dp"
        const val KEY_KEY_GAP = "key_gap_dp"
        const val KEY_ROW_GAP = "row_gap_dp"
        const val KEY_TOOLBAR = "toolbar_dp"
        const val KEY_ADAPTIVE = "adaptive_touch"
        const val DEF_KEY_HEIGHT = 43
        const val DEF_NUMBER_ROW_HEIGHT = 35
        const val DEF_KEY_GAP = 5
        const val DEF_ROW_GAP = 8
        const val DEF_TOOLBAR = 42
        const val KEY_POPUPS = "popups"
        const val KEY_THEME = "theme"
        const val KEY_EMOJI_RECENTS = "emoji_recents"
    }
}
