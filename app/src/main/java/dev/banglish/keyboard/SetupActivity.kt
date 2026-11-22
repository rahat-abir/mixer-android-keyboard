package dev.banglish.keyboard

import android.app.Activity
import android.content.Intent
import android.graphics.Typeface
import android.os.Bundle
import android.provider.Settings
import android.text.InputType
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodManager
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.RadioButton
import android.widget.RadioGroup
import android.widget.ScrollView
import android.widget.SeekBar
import android.widget.Switch
import android.widget.TextView
import dev.banglish.keyboard.glide.GlideLog
import dev.banglish.keyboard.glide.GlideTuner
import java.io.File

/** One screen: setup steps, settings, and test fields for every kind of input box. */
class SetupActivity : Activity() {

    private lateinit var prefs: Prefs
    private lateinit var status: TextView
    private lateinit var enableBtn: Button
    private lateinit var chooseBtn: Button

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        prefs = Prefs(this)
        val scroll = ScrollView(this)
        val col = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(28), dp(20), dp(40))
        }
        scroll.addView(col)
        scroll.setOnApplyWindowInsetsListener { v, insets ->
            @Suppress("DEPRECATION")
            v.setPadding(0, insets.systemWindowInsetTop, 0, insets.systemWindowInsetBottom)
            insets
        }
        setContentView(scroll)

        col.addView(TextView(this).apply { text = getString(R.string.app_name); textSize = 26f; setTypeface(typeface, Typeface.BOLD) })
        col.addView(TextView(this).apply {
            text = "English with glide typing + Avro phonetic Bangla. Swipe the space bar to switch language, hold it to move the cursor. Long-press C to copy, V to paste. Nothing you type leaves the phone (no internet permission)."
            textSize = 14f; setPadding(0, dp(6), 0, dp(14))
        })

        status = TextView(this).apply { textSize = 15f; setPadding(0, 0, 0, dp(8)) }
        col.addView(status)
        enableBtn = Button(this).apply {
            text = "1. Enable Mixer"
            setOnClickListener { startActivity(Intent(Settings.ACTION_INPUT_METHOD_SETTINGS)) }
        }
        chooseBtn = Button(this).apply {
            text = "2. Switch to Mixer"
            setOnClickListener { getSystemService(InputMethodManager::class.java)?.showInputMethodPicker() }
        }
        col.addView(enableBtn); col.addView(chooseBtn)

        header(col, "Try it")
        testField(col, "Text (try glide, long-press C / V)", InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_CAP_SENTENCES or InputType.TYPE_TEXT_FLAG_AUTO_CORRECT)
        testField(col, "Message (multi-line)", InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE or InputType.TYPE_TEXT_FLAG_CAP_SENTENCES, lines = 3)
        testField(col, "Search", InputType.TYPE_CLASS_TEXT, action = EditorInfo.IME_ACTION_SEARCH)
        testField(col, "Password", InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD)
        testField(col, "Email", InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_EMAIL_ADDRESS)
        testField(col, "Website", InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_URI, action = EditorInfo.IME_ACTION_GO)
        testField(col, "Number", InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_FLAG_DECIMAL)
        testField(col, "Phone", InputType.TYPE_CLASS_PHONE, action = EditorInfo.IME_ACTION_DONE)

        header(col, "Typing")
        switch(col, "Glide typing", Prefs.KEY_GLIDE, true)
        switch(col, "Glide typing in Bangla (swipe straight to Bangla words)", Prefs.KEY_BANGLA_GLIDE, true)
        switch(col, "Autocorrect on space", Prefs.KEY_AUTOCORRECT, true)
        switch(col, "Auto-capitalise sentences", Prefs.KEY_AUTOCAP, true)
        switch(col, "Double-space types a full stop", Prefs.KEY_DOUBLE_SPACE, true)
        switch(col, "Start in Bangla", Prefs.KEY_BANGLA, false)

        header(col, "Glide word picker")
        stats = TextView(this).apply { textSize = 14f; setPadding(0, 0, 0, dp(6)) }
        col.addView(stats)
        switch(col, "Keep a log of the last 500 glides (stays on this phone)", Prefs.KEY_LOG_GLIDES, true)
        col.addView(Button(this).apply { text = "Export glide log"; setOnClickListener { exportLog() } })
        col.addView(Button(this).apply {
            text = "Clear log and stats"
            setOnClickListener { glideLog().clear(); glideLog().saveIfDirty(); refreshStats() }
        })
        col.addView(Button(this).apply {
            text = "Reset learned ranking"
            setOnClickListener { (Engines.tuner ?: GlideTuner(prefs.sp)).reset(); refreshStats() }
        })

        header(col, "Look & feel")
        switch(col, "Number row", Prefs.KEY_NUMBER_ROW, true)
        switch(col, "Key preview popup", Prefs.KEY_POPUPS, true)
        switch(col, "Key sound", Prefs.KEY_SOUND, false)
        radio(col, "Vibration", Prefs.KEY_HAPTIC, 2, listOf("Off", "Light", "Normal", "Strong"))
        radio(col, "Theme", Prefs.KEY_THEME, 0, listOf("System", "Light", "Dark"))

        header(col, "Layout (defaults match Samsung Keyboard)")
        switch(col, "Adaptive touch (taps near a key edge favour the likely next letter)", Prefs.KEY_ADAPTIVE, true)
        slider(col, "Letter key height", Prefs.KEY_HEIGHT, Prefs.DEF_KEY_HEIGHT, 30, 64)
        slider(col, "Number row height", Prefs.KEY_NUMBER_ROW_HEIGHT, Prefs.DEF_NUMBER_ROW_HEIGHT, 24, 56)
        slider(col, "Gap between keys", Prefs.KEY_KEY_GAP, Prefs.DEF_KEY_GAP, 0, 12)
        slider(col, "Gap between rows", Prefs.KEY_ROW_GAP, Prefs.DEF_ROW_GAP, 0, 16)
        slider(col, "Toolbar height", Prefs.KEY_TOOLBAR, Prefs.DEF_TOOLBAR, 32, 60)
        col.addView(Button(this).apply {
            text = "Reset layout to Samsung defaults"
            setOnClickListener { prefs.resetLayout(); recreate() }
        })

        header(col, "Credits")
        col.addView(TextView(this).apply {
            textSize = 12f
            text = "Avro Phonetic rules, Bangla dictionary and autocorrect © OmicronLab, from jsAvroPhonetic / ibus-avro (MPL-2.0).\n" +
                "English word list from the AOSP/OpenBoard dictionaries (Apache-2.0).\n" +
                "Bangla word frequencies from wordfreq by Robyn Speer et al. (CC BY-SA 4.0).\n" +
                "Glide typing uses the shape-matching approach of SHARK² as used by FlorisBoard (Apache-2.0).\n" +
                "Emoji list from Unicode emoji-test.txt."
        })
    }

    override fun onResume() {
        super.onResume()
        refreshStatus()
        refreshStats()
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus) refreshStatus() // after the IME picker closes
    }

    private fun refreshStatus() {
        val imm = getSystemService(InputMethodManager::class.java)
        val enabled = imm?.enabledInputMethodList?.any { it.packageName == packageName } == true
        val current = Settings.Secure.getString(contentResolver, Settings.Secure.DEFAULT_INPUT_METHOD) ?: ""
        val selected = current.startsWith("$packageName/")
        status.text = when {
            selected -> "✓ Mixer is on. Tap a box below to try it."
            enabled -> "Step 2: switch to Mixer."
            else -> "Step 1: enable Mixer in Settings."
        }
        enableBtn.isEnabled = !enabled
        chooseBtn.isEnabled = enabled && !selected
    }

    private var stats: TextView? = null

    private fun glideLog(): GlideLog = Engines.glideLog ?: GlideLog(File(filesDir, "glide_log.tsv"), prefs.sp)

    private fun refreshStats() {
        val log = glideLog()
        fun line(name: String, lang: String): String {
            val s = log.stats(lang)
            val total = s[0]
            if (total == 0) return "$name: no glides yet"
            fun pct(v: Int) = "${(v * 100 + total / 2) / total}%"
            return "$name: $total glides. Default pick kept ${pct(s[1])}, picked 2nd ${pct(s[2])}, 3rd ${pct(s[3])}, " +
                "other ${pct(s[4])}, deleted ${pct(s[5])}"
        }
        val w = (Engines.tuner ?: GlideTuner(prefs.sp))
        stats?.text = line("English", "en") + "\n" + line("Bangla", "bn") +
            "\nRanking weights (bn): " + w.weights("bn").w.joinToString(" ") { "%.3f".format(it) }
    }

    private fun exportLog() {
        Engines.glideLog?.saveIfDirty()
        val text = glideLog().exportText()
        val dir = getExternalFilesDir(null)
        val out = if (dir != null) File(dir, "mixer_glide_log.tsv") else null
        try { out?.writeText(text) } catch (_: Exception) {}
        startActivity(Intent.createChooser(Intent(Intent.ACTION_SEND).apply {
            type = "text/plain"
            putExtra(Intent.EXTRA_SUBJECT, "Mixer glide log")
            putExtra(Intent.EXTRA_TEXT, text)
        }, "Export glide log"))
    }

    private fun header(col: LinearLayout, title: String) {
        col.addView(TextView(this).apply {
            text = title; textSize = 18f; setTypeface(typeface, Typeface.BOLD); setPadding(0, dp(22), 0, dp(6))
        })
    }

    private fun testField(col: LinearLayout, hint: String, type: Int, action: Int = EditorInfo.IME_ACTION_UNSPECIFIED, lines: Int = 1) {
        col.addView(EditText(this).apply {
            this.hint = hint
            inputType = type
            imeOptions = action
            if (lines > 1) { minLines = lines; gravity = Gravity.TOP or Gravity.START } else isSingleLine = true
        }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
    }

    @Suppress("DEPRECATION")
    private fun switch(col: LinearLayout, label: String, key: String, def: Boolean) {
        col.addView(Switch(this).apply {
            text = label; textSize = 15f
            isChecked = prefs.sp.getBoolean(key, def)
            setPadding(0, dp(8), 0, dp(8))
            setOnCheckedChangeListener { _, v -> prefs.sp.edit().putBoolean(key, v).apply() }
        })
    }

    private fun radio(col: LinearLayout, label: String, key: String, def: Int, options: List<String>) {
        col.addView(TextView(this).apply { text = label; textSize = 15f; setPadding(0, dp(8), 0, 0) })
        val group = RadioGroup(this).apply { orientation = RadioGroup.HORIZONTAL }
        val cur = prefs.sp.getInt(key, def)
        options.forEachIndexed { i, o ->
            group.addView(RadioButton(this).apply { text = o; id = View.generateViewId(); isChecked = i == cur; tag = i })
        }
        group.setOnCheckedChangeListener { g, id -> (g.findViewById<View>(id)?.tag as? Int)?.let { prefs.sp.edit().putInt(key, it).apply() } }
        col.addView(group)
    }

    private fun slider(col: LinearLayout, label: String, key: String, def: Int, min: Int, max: Int) {
        val title = TextView(this).apply { textSize = 15f; setPadding(0, dp(8), 0, 0) }
        fun show(v: Int) { title.text = "$label: $v dp" }
        val cur = prefs.sp.getInt(key, def)
        show(cur)
        col.addView(title)
        col.addView(SeekBar(this).apply {
            this.max = max - min
            progress = cur - min
            setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(s: SeekBar?, p: Int, fromUser: Boolean) { show(p + min) }
                override fun onStartTrackingTouch(s: SeekBar?) {}
                override fun onStopTrackingTouch(s: SeekBar?) { prefs.sp.edit().putInt(key, (s?.progress ?: 0) + min).apply() }
            })
        })
    }

    private fun dp(v: Int) = (v * resources.displayMetrics.density + 0.5f).toInt()
}
