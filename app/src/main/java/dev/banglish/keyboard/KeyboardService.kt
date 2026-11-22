package dev.banglish.keyboard

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.graphics.Canvas
import android.inputmethodservice.InputMethodService
import android.media.AudioManager
import android.os.Build
import android.os.Handler
import android.os.HandlerThread
import android.os.Looper
import android.os.SystemClock
import android.os.VibrationEffect
import android.os.Vibrator
import android.text.InputType
import android.view.KeyEvent
import android.view.View
import android.view.ViewGroup
import android.view.WindowInsets
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.ExtractedTextRequest
import android.view.inputmethod.InputConnection
import android.view.inputmethod.InputMethodManager
import android.widget.FrameLayout
import android.widget.LinearLayout
import dev.banglish.keyboard.clip.ClipHistory
import dev.banglish.keyboard.clip.ClipboardView
import dev.banglish.keyboard.emoji.EmojiData
import dev.banglish.keyboard.emoji.EmojiView
import dev.banglish.keyboard.glide.GlideClassifier
import dev.banglish.keyboard.glide.GlideLog
import dev.banglish.keyboard.layout.Codes
import dev.banglish.keyboard.layout.Key
import dev.banglish.keyboard.layout.Keyboard
import dev.banglish.keyboard.layout.Layouts
import dev.banglish.keyboard.suggest.Suggestions
import java.text.BreakIterator

class KeyboardService : InputMethodService(),
    KeyboardView.Listener, CandidateView.Listener, EmojiView.Listener, ClipboardView.Listener {

    private lateinit var prefs: Prefs
    private var theme = Theme.LIGHT
    private val main = Handler(Looper.getMainLooper())
    private lateinit var workerThread: HandlerThread
    private lateinit var worker: Handler
    private lateinit var clipHistory: ClipHistory
    private var vibrator: Vibrator? = null
    private var audio: AudioManager? = null

    // Views
    private var root: FrameLayout? = null
    private lateinit var column: LinearLayout
    private lateinit var strip: CandidateView
    private lateinit var content: FrameLayout
    private lateinit var kv: KeyboardView
    private lateinit var overlay: View
    private var emojiView: EmojiView? = null
    private var clipView: ClipboardView? = null
    private var bottomInset = 0

    // Editor state
    private var passwordField = false
    private var noSuggestions = false
    private var multiLine = false
    private var emailField = false
    private var urlField = false
    private var bangla = false
    private val composing = StringBuilder()
    private var glideWord = false
    /** The last glide, waiting to learn whether its default pick was kept, swapped or deleted. */
    private var pendingGlide: GlideLog.Pending? = null
    private var english: Suggestions? = null
    private var englishFor = ""
    private var banglaWords: List<String> = emptyList()
    private var banglaSelected = 0
    private var banglaFor = ""
    private var generation = 0
    private var shiftState = 0
    private var lastShiftTap = 0L
    private var lastSpaceAt = 0L
    /** Set right after "word + space" was committed by autocorrect/suggestion, for undo and punctuation. */
    private var autoSpaced = false
    private var undoAutocorrect: Pair<String, String>? = null
    private var lastWord = ""

    // ------------------------------------------------------------ lifecycle

    override fun onCreate() {
        super.onCreate()
        prefs = Prefs(this)
        workerThread = HandlerThread("banglish-suggest").also { it.start() }
        worker = Handler(workerThread.looper)
        clipHistory = ClipHistory(this)
        vibrator = getSystemService(Vibrator::class.java)
        audio = getSystemService(AudioManager::class.java)
        val unlocked = prefs.isUnlocked()
        worker.post { Engines.load(this, unlocked) }
        worker.post { EmojiData.load(this) }
        clipHistory.start(unlocked)
    }

    override fun onDestroy() {
        clipHistory.stop()
        worker.post { Engines.save() }
        workerThread.quitSafely()
        super.onDestroy()
    }

    // Never take over the whole screen in landscape: the app stays visible above the keys.
    override fun onEvaluateFullscreenMode(): Boolean = false

    override fun onCreateInputView(): View {
        theme = Theme.resolve(this, prefs.theme)
        val r = FrameLayout(this)
        column = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        strip = CandidateView(this, this)
        content = FrameLayout(this)
        kv = KeyboardView(this, this)
        content.addView(kv, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        // Transparent headroom above the strip so key previews of the top row are not clipped.
        // It is excluded from the touchable region in onComputeInsets.
        val headroom = View(this)
        column.addView(headroom, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(36)))
        column.addView(strip, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(44)))
        column.addView(content, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        overlay = object : View(this) {
            private val a = IntArray(2); private val b = IntArray(2)
            // Never ask for height of its own: otherwise the wrap_content root grows to the whole
            // window. FrameLayout then re-measures it EXACTLY to the column's height.
            override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
                val h = if (MeasureSpec.getMode(heightMeasureSpec) == MeasureSpec.EXACTLY) MeasureSpec.getSize(heightMeasureSpec) else 0
                setMeasuredDimension(MeasureSpec.getSize(widthMeasureSpec), h)
            }
            override fun onDraw(canvas: Canvas) {
                if (kv.parent == null || kv.visibility != VISIBLE) return
                kv.getLocationInWindow(a); getLocationInWindow(b)
                kv.drawOverlay(canvas, (a[1] - b[1]).toFloat())
            }
        }
        r.addView(column, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        r.addView(overlay, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
        // Keep the bottom row clear of the gesture/navigation bar (edge-to-edge on Android 15+).
        r.setOnApplyWindowInsetsListener { _, insets ->
            val b = if (Build.VERSION.SDK_INT >= 30) insets.getInsets(WindowInsets.Type.navigationBars()).bottom
            else @Suppress("DEPRECATION") insets.systemWindowInsetBottom
            if (b != bottomInset) { bottomInset = b; applyTheme() }
            insets
        }
        root = r
        applyTheme()
        return r
    }

    private fun applyTheme() {
        val r = root ?: return
        column.getChildAt(1).setBackgroundColor(theme.background)
        content.setBackgroundColor(theme.background)
        content.setPadding(0, 0, 0, bottomInset)
        strip.theme = theme
        kv.theme = theme
        emojiView?.theme = theme
        clipView?.theme = theme
        r.requestLayout()
    }

    override fun onComputeInsets(outInsets: Insets) {
        super.onComputeInsets(outInsets)
        val r = root ?: return
        if (!r.isShown) return
        val loc = IntArray(2)
        (if (strip.visibility == View.VISIBLE) strip else content).getLocationInWindow(loc)
        val top = loc[1]
        r.getLocationInWindow(loc)
        val bottom = loc[1] + r.height
        outInsets.contentTopInsets = top
        outInsets.visibleTopInsets = top
        outInsets.touchableInsets = Insets.TOUCHABLE_INSETS_REGION
        outInsets.touchableRegion.set(0, top, r.width, bottom)
    }

    override fun onStartInput(attribute: EditorInfo, restarting: Boolean) {
        super.onStartInput(attribute, restarting)
        resetComposingState()
    }

    override fun onStartInputView(info: EditorInfo, restarting: Boolean) {
        super.onStartInputView(info, restarting)
        val newTheme = Theme.resolve(this, prefs.theme)
        if (newTheme !== theme) { theme = newTheme; applyTheme() }
        val cls = info.inputType and InputType.TYPE_MASK_CLASS
        val variation = info.inputType and InputType.TYPE_MASK_VARIATION
        passwordField = (cls == InputType.TYPE_CLASS_TEXT && (variation == InputType.TYPE_TEXT_VARIATION_PASSWORD ||
            variation == InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD || variation == InputType.TYPE_TEXT_VARIATION_WEB_PASSWORD)) ||
            (cls == InputType.TYPE_CLASS_NUMBER && variation == InputType.TYPE_NUMBER_VARIATION_PASSWORD)
        emailField = cls == InputType.TYPE_CLASS_TEXT && (variation == InputType.TYPE_TEXT_VARIATION_EMAIL_ADDRESS || variation == InputType.TYPE_TEXT_VARIATION_WEB_EMAIL_ADDRESS)
        urlField = cls == InputType.TYPE_CLASS_TEXT && variation == InputType.TYPE_TEXT_VARIATION_URI
        multiLine = (info.inputType and InputType.TYPE_TEXT_FLAG_MULTI_LINE) != 0
        noSuggestions = passwordField || emailField || urlField || cls != InputType.TYPE_CLASS_TEXT ||
            (info.inputType and InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS) != 0 && (info.inputType and InputType.TYPE_TEXT_FLAG_AUTO_CORRECT) == 0 ||
            variation == InputType.TYPE_TEXT_VARIATION_FILTER
        clipHistory.paused = passwordField
        bangla = prefs.bangla && !passwordField && !emailField && !urlField && cls == InputType.TYPE_CLASS_TEXT

        val density = resources.displayMetrics.density
        kv.rowHeightPx = dp(prefs.keyHeightDp)
        kv.numberRowHeightPx = dp(prefs.numberRowHeightDp)
        kv.hGapPx = prefs.keyGapDp * density
        kv.vGapPx = prefs.rowGapDp * density
        kv.adaptiveTouch = prefs.adaptiveTouch
        // All pages share the letters page's height (number row included when it is on).
        val pitch = kv.rowHeightPx + kv.vGapPx.toInt()
        kv.minHeightPx = 4 * pitch + if (prefs.numberRow) kv.numberRowHeightPx + kv.vGapPx.toInt() else 0
        strip.layoutParams = strip.layoutParams.apply { height = dp(prefs.toolbarDp) }
        kv.glideEnabled = glideAllowed()
        kv.showPreviews = prefs.showPopups
        kv.spaceLabel = spaceLabel()
        kv.enterAction = enterActionOf(info)
        kv.keyboard = when (cls) {
            InputType.TYPE_CLASS_NUMBER, InputType.TYPE_CLASS_DATETIME -> Layouts.numpad(phone = false)
            InputType.TYPE_CLASS_PHONE -> Layouts.numpad(phone = true)
            else -> Layouts.qwerty(prefs.numberRow, emailField, urlField)
        }
        kv.relayout()
        showKeys()
        shiftState = 0
        updateShift()
        showIdleStrip()
        updateLetterPrior()
    }

    override fun onFinishInputView(finishingInput: Boolean) {
        super.onFinishInputView(finishingInput)
        if (::kv.isInitialized) kv.cancelAll()
        currentInputConnection?.finishComposingText()
        resetComposingState()
        worker.post { Engines.save() }
    }

    override fun onFinishInput() {
        super.onFinishInput()
        resetComposingState()
    }

    private fun resetComposingState() {
        finishGlide(deleted = false)
        composing.setLength(0)
        glideWord = false
        english = null; englishFor = ""
        banglaWords = emptyList(); banglaFor = ""
        autoSpaced = false
        undoAutocorrect = null
        generation++
    }

    override fun onUpdateSelection(oldSelStart: Int, oldSelEnd: Int, newSelStart: Int, newSelEnd: Int, candidatesStart: Int, candidatesEnd: Int) {
        super.onUpdateSelection(oldSelStart, oldSelEnd, newSelStart, newSelEnd, candidatesStart, candidatesEnd)
        // The user moved the cursor away from the word being composed: let go of it.
        if (composing.isNotEmpty() && (newSelStart != candidatesEnd || newSelEnd != candidatesEnd)) {
            currentInputConnection?.finishComposingText()
            resetComposingState()
            showIdleStrip()
            updateLetterPrior()
        }
        if (composing.isEmpty()) updateShift()
    }

    // ------------------------------------------------------------ KeyboardView.Listener

    override fun onKeyDown(key: Key) {
        when (prefs.haptic) {
            1 -> vibrate(if (Build.VERSION.SDK_INT >= 29) VibrationEffect.EFFECT_TICK else -1, 8)
            2 -> vibrate(if (Build.VERSION.SDK_INT >= 29) VibrationEffect.EFFECT_CLICK else -1, 14)
            3 -> vibrate(if (Build.VERSION.SDK_INT >= 29) VibrationEffect.EFFECT_HEAVY_CLICK else -1, 24)
        }
        if (prefs.sound) audio?.playSoundEffect(
            when (key.code) {
                Codes.DELETE -> AudioManager.FX_KEYPRESS_DELETE
                Codes.SPACE -> AudioManager.FX_KEYPRESS_SPACEBAR
                Codes.ENTER -> AudioManager.FX_KEYPRESS_RETURN
                else -> AudioManager.FX_KEYPRESS_STANDARD
            }, -1f
        )
    }

    private fun vibrate(effect: Int, ms: Long) {
        val v = vibrator ?: return
        try {
            if (Build.VERSION.SDK_INT >= 29 && effect >= 0) v.vibrate(VibrationEffect.createPredefined(effect))
            else v.vibrate(VibrationEffect.createOneShot(ms, VibrationEffect.DEFAULT_AMPLITUDE))
        } catch (_: Exception) {}
    }

    override fun onKey(key: Key, text: String) {
        when (key.code) {
            Codes.SHIFT -> handleShift()
            Codes.DELETE -> handleDelete()
            Codes.ENTER -> handleEnter()
            Codes.SPACE -> handleSpace()
            Codes.SYMBOLS -> { kv.keyboard = Layouts.symbols(emailField, urlField); kv.relayout() }
            Codes.SYMBOLS2 -> { kv.keyboard = Layouts.symbols2(emailField, urlField); kv.relayout() }
            Codes.ALPHA -> { kv.keyboard = Layouts.qwerty(prefs.numberRow, emailField, urlField); kv.relayout(); updateShift() }
            Codes.EMOJI -> showEmoji()
            Codes.LANG -> toggleLanguage()
            else -> if (text.isNotEmpty()) handleText(text)
        }
        updateLetterPrior()
    }

    /**
     * Next-letter likelihoods for adaptive touch: for each a–z, how many dictionary words
     * continue the word being typed with that letter (two binary searches per letter).
     */
    private fun updateLetterPrior() {
        if (!::kv.isInitialized) return
        val dict = Engines.english
        if (dict == null || bangla || noSuggestions || !prefs.adaptiveTouch || kv.keyboard?.id != Layouts.QWERTY) {
            kv.letterPrior = null; return
        }
        val prefix = if (glideWord) "" else composing.toString().lowercase()
        val prior = kv.letterPrior?.takeIf { it.size == 26 } ?: FloatArray(26)
        var total = 0
        val counts = IntArray(26)
        for (i in 0 until 26) {
            val pre = prefix + ('a' + i)
            counts[i] = dict.lowerBound(pre + MAX_CHAR) - dict.lowerBound(pre)
            total += counts[i]
        }
        if (total == 0) { kv.letterPrior = null; return }
        for (i in 0 until 26) prior[i] = kotlin.math.ln((counts[i] + 0.5f) / (total + 13f)).coerceAtLeast(-6f)
        kv.letterPrior = prior
    }

    override fun onLongPress(key: Key): Boolean {
        if (kv.keyboard?.id == Layouts.QWERTY && key.isLetter) {
            when (key.label) {
                "c" -> { copy(); return true }
                "v" -> { paste(); return true }
                "x" -> { cut(); return true }
                "a" -> { selectAll(); return true }
            }
        }
        if (key.code == Codes.LANG) {
            getSystemService(InputMethodManager::class.java)?.showInputMethodPicker()
            return true
        }
        if (key.code == Codes.ENTER) {
            // Long-press Enter opens emoji (Enter itself is not sent). Posted so the key view
            // finishes its long-press handling before it is swapped out for the emoji panel.
            main.post { showEmoji() }
            return true
        }
        return false
    }

    override fun onSpaceSwipe(direction: Int) = toggleLanguage()

    override fun onShiftLock() {
        shiftState = 2; kv.shiftState = 2
    }

    override fun requestOverlayRedraw() {
        if (::overlay.isInitialized) overlay.invalidate()
    }

    override fun onCursorMove(steps: Int) {
        val ic = currentInputConnection ?: return
        if (composing.isNotEmpty()) commitComposing(useAuto = false)
        val code = if (steps < 0) KeyEvent.KEYCODE_DPAD_LEFT else KeyEvent.KEYCODE_DPAD_RIGHT
        ic.beginBatchEdit()
        repeat(kotlin.math.abs(steps)) { sendDownUpKeyEvents(code) }
        ic.endBatchEdit()
    }

    override fun onDeleteWords(count: Int) {
        val ic = currentInputConnection ?: return
        if (composing.isNotEmpty()) { ic.setComposingText("", 1); resetComposingState() }
        val before = ic.getTextBeforeCursor(400, 0)?.toString() ?: return
        var i = before.length
        repeat(count) {
            while (i > 0 && before[i - 1].isWhitespace()) i--
            if (i > 0 && !isWordChar(before[i - 1])) i-- else while (i > 0 && isWordChar(before[i - 1])) i--
        }
        if (i < before.length) ic.deleteSurroundingText(before.length - i, 0)
        showIdleStrip(); updateShift()
    }

    private fun isWordChar(c: Char) = c.isLetterOrDigit() || c == '\'' || Character.getType(c) == Character.NON_SPACING_MARK.toInt() ||
        Character.getType(c) == Character.COMBINING_SPACING_MARK.toInt()

    // ------------------------------------------------------------ typing

    private fun handleShift() {
        val now = SystemClock.uptimeMillis()
        shiftState = when {
            shiftState == 2 -> 0
            shiftState == 1 && now - lastShiftTap < 350 -> 2
            shiftState == 1 -> 0
            else -> 1
        }
        lastShiftTap = now
        kv.shiftState = shiftState
    }

    private fun updateShift() {
        if (!::kv.isInitialized || shiftState == 2) return
        val ic = currentInputConnection
        val ei = currentInputEditorInfo
        shiftState = if (!bangla && prefs.autoCap && ic != null && ei != null && ei.inputType != 0 &&
            kv.keyboard?.id == Layouts.QWERTY && ic.getCursorCapsMode(ei.inputType) != 0
        ) 1 else 0
        kv.shiftState = shiftState
    }

    private fun consumeOneShotShift() {
        if (shiftState == 1) { shiftState = 0; kv.shiftState = 0 }
    }

    private fun isBanglaBufferChar(c: Char) = c in 'a'..'z' || c in 'A'..'Z' || c in '0'..'9' || c in "`^:$,."

    private fun handleText(raw: String) {
        val ic = currentInputConnection ?: return
        val c = raw[0]
        val text = if (raw.length == 1 && shiftState > 0 && c.isLetter()) raw.uppercase() else raw
        undoAutocorrect = null
        ic.beginBatchEdit()
        try {
            if (bangla) {
                if (glideWord) {
                    // Tapping after a glided Bangla word: keep it, and start a new word after a space.
                    val startsWord = text.length == 1 && text[0].isLetterOrDigit()
                    commitComposing(useAuto = false)
                    if (startsWord) ic.commitText(" ", 1)
                }
                if (text.length == 1 && isBanglaBufferChar(text[0]) && !(composing.isEmpty() && text[0] in ",:^`")) {
                    // "আমি␣" + "." → "আমি।" (the space was ours).
                    if (composing.isEmpty() && autoSpaced && text[0] == '.' && ic.getTextBeforeCursor(1, 0) == " ") ic.deleteSurroundingText(1, 0)
                    composing.append(text)
                    updateBanglaComposing(ic)
                } else {
                    if (composing.isNotEmpty()) commitComposing(useAuto = true)
                    ic.commitText(text, 1)
                }
                autoSpaced = false
            } else {
                val letter = text.length == 1 && (text[0].isLetter() || (text[0] == '\'' && composing.isNotEmpty() && !glideWord))
                if (glideWord) {
                    commitComposing(useAuto = false)
                    if (letter) ic.commitText(" ", 1)
                }
                if (letter && !noSuggestions) {
                    composing.append(text)
                    ic.setComposingText(composing, 1)
                    requestEnglishSuggestions()
                    autoSpaced = false
                } else {
                    if (composing.isNotEmpty()) commitComposing(useAuto = text.length == 1 && text[0] in AUTOCORRECT_TRIGGERS)
                    // "word␣" + "." → "word." (the space was ours).
                    if (autoSpaced && text.length == 1 && text[0] in ".,!?;:)") {
                        val before = ic.getTextBeforeCursor(1, 0)
                        if (before == " ") ic.deleteSurroundingText(1, 0)
                    }
                    ic.commitText(text, 1)
                    autoSpaced = false
                    if (!letter) showIdleStrip()
                }
            }
        } finally {
            ic.endBatchEdit()
        }
        consumeOneShotShift()
        if (composing.isEmpty()) updateShift()
    }

    private fun handleSpace() {
        val ic = currentInputConnection ?: return
        val now = SystemClock.uptimeMillis()
        ic.beginBatchEdit()
        try {
            if (composing.isNotEmpty()) {
                commitComposing(useAuto = true)
                ic.commitText(" ", 1)
                autoSpaced = true
                if (!bangla) showPredictions()
            } else if (prefs.doubleSpacePeriod && now - lastSpaceAt < 700 && !noSuggestions) {
                val before = ic.getTextBeforeCursor(2, 0)?.toString() ?: ""
                if (before.length == 2 && before[1] == ' ' && before[0].isLetterOrDigit()) {
                    ic.deleteSurroundingText(1, 0)
                    ic.commitText(if (bangla) "। " else ". ", 1)
                } else ic.commitText(" ", 1)
                autoSpaced = false
                undoAutocorrect = null
            } else {
                ic.commitText(" ", 1)
                autoSpaced = false
                undoAutocorrect = null
                if (!bangla && lastWord.isNotEmpty()) showPredictions() else showIdleStrip()
            }
        } finally {
            ic.endBatchEdit()
        }
        lastSpaceAt = now
        consumeOneShotShift()
        updateShift()
    }

    private fun handleDelete() {
        val ic = currentInputConnection ?: return
        val undo = undoAutocorrect
        if (undo != null && composing.isEmpty()) {
            // Backspace right after an autocorrect: put back exactly what was typed.
            undoAutocorrect = null
            val before = ic.getTextBeforeCursor(undo.second.length + 1, 0)?.toString()
            if (before == undo.second + " ") {
                ic.beginBatchEdit()
                ic.deleteSurroundingText(before.length, 0)
                ic.commitText(undo.first, 1)
                ic.endBatchEdit()
                Engines.userDict?.learnNow(undo.first)
                autoSpaced = false
                showIdleStrip()
                return
            }
        }
        undoAutocorrect = null
        autoSpaced = false
        if (composing.isNotEmpty()) {
            if (glideWord) {
                // A glided word goes in one backspace, like Samsung/Gboard. Logged as a miss.
                finishGlide(deleted = true)
                ic.setComposingText("", 1); resetComposingState(); showIdleStrip(); updateShift(); return
            }
            composing.setLength(composing.length - 1)
            if (composing.isEmpty()) {
                ic.setComposingText("", 1); resetComposingState(); showIdleStrip(); updateShift()
            } else if (bangla) updateBanglaComposing(ic)
            else { ic.setComposingText(composing, 1); requestEnglishSuggestions() }
            return
        }
        val selected = ic.getSelectedText(0)
        if (!selected.isNullOrEmpty()) { ic.commitText("", 1); updateShift(); return }
        val before = ic.getTextBeforeCursor(32, 0)
        if (before.isNullOrEmpty()) {
            // Empty field or an app that hides its text: a real key event works everywhere.
            sendDownUpKeyEvents(KeyEvent.KEYCODE_DEL)
        } else {
            val s = before.toString()
            val last = s[s.length - 1]
            val n = if (last in 'ঀ'..'৿') 1 else {
                // Delete a whole grapheme so emoji like 👨‍👩‍👧 go in one tap.
                val it = BreakIterator.getCharacterInstance()
                it.setText(s)
                val end = it.last()
                val start = it.previous()
                if (start == BreakIterator.DONE) 1 else end - start
            }
            ic.deleteSurroundingText(n, 0)
        }
        if (lastWord.isNotEmpty()) lastWord = ""
        showIdleStrip()
        updateShift()
    }

    private fun handleEnter() {
        val ic = currentInputConnection ?: return
        if (composing.isNotEmpty()) commitComposing(useAuto = true)
        autoSpaced = false; undoAutocorrect = null
        val ei = currentInputEditorInfo
        val action = (ei?.imeOptions ?: 0) and EditorInfo.IME_MASK_ACTION
        val noEnterAction = ((ei?.imeOptions ?: 0) and EditorInfo.IME_FLAG_NO_ENTER_ACTION) != 0
        if (!noEnterAction && action != EditorInfo.IME_ACTION_NONE && action != EditorInfo.IME_ACTION_UNSPECIFIED) {
            ic.performEditorAction(action)
        } else if (multiLine) {
            ic.commitText("\n", 1)
        } else {
            sendDownUpKeyEvents(KeyEvent.KEYCODE_ENTER)
        }
        showIdleStrip()
        updateShift()
    }

    private fun enterActionOf(info: EditorInfo): Int {
        if ((info.imeOptions and EditorInfo.IME_FLAG_NO_ENTER_ACTION) != 0) return 0
        return when (info.imeOptions and EditorInfo.IME_MASK_ACTION) {
            EditorInfo.IME_ACTION_GO -> 1
            EditorInfo.IME_ACTION_SEARCH -> 2
            EditorInfo.IME_ACTION_SEND -> 3
            EditorInfo.IME_ACTION_NEXT -> 4
            EditorInfo.IME_ACTION_DONE -> 5
            EditorInfo.IME_ACTION_PREVIOUS -> 6
            else -> 0
        }
    }

    /** Commit the word being composed. [useAuto] applies autocorrect / the Avro default pick. */
    private fun commitComposing(useAuto: Boolean) {
        val ic = currentInputConnection ?: return
        val typed = composing.toString()
        if (typed.isEmpty()) return
        val pick: String
        if (bangla && glideWord) {
            // A glided Bangla word is already final Bangla text.
            pick = typed
            ic.commitText(pick, 1)
            finishGlide(deleted = false)
            lastWord = ""
        } else if (bangla) {
            if (banglaFor != typed) refreshBanglaSync(typed)
            pick = banglaWords.getOrNull(banglaSelected) ?: Engines.phonetic?.parse(typed) ?: typed
            ic.commitText(pick, 1)
            val b = Engines.bangla
            if (b != null && pick != typed) worker.post { b.commitSelection(typed, pick) }
            // Tap-typed Bangla words also teach Bangla glide what you write.
            Engines.banglaUses?.let { u -> if (pick != typed) worker.post { u.add(pick) } }
            lastWord = ""
        } else {
            val auto = useAuto && prefs.autocorrect && !glideWord
            if (auto && englishFor != typed) refreshEnglishSync(typed)
            val s = english
            pick = if (auto && s != null && englishFor == typed && s.autoIndex >= 0) s.words[s.autoIndex] else typed
            ic.commitText(pick, 1)
            if (pick != typed) undoAutocorrect = typed to pick
            Engines.userDict?.let { ud -> worker.post { ud.learn(pick) } }
            if (glideWord) finishGlide(deleted = false)
            lastWord = pick
        }
        composing.setLength(0)
        glideWord = false
        generation++
    }

    // ------------------------------------------------------------ English suggestions

    private fun requestEnglishSuggestions() {
        val s = Engines.suggester ?: return
        val typed = composing.toString()
        val g = ++generation
        val auto = prefs.autocorrect
        worker.post {
            if (g != generation) return@post
            val res = s.suggest(typed, auto)
            main.post {
                if (g == generation && composing.toString() == typed && !bangla) {
                    english = res; englishFor = typed
                    strip.setSuggestions(res.words, if (auto) res.autoIndex else res.words.indexOf(typed))
                }
            }
        }
    }

    private fun refreshEnglishSync(typed: String) {
        val s = Engines.suggester ?: return
        english = s.suggest(typed, prefs.autocorrect); englishFor = typed
    }

    private fun showPredictions() {
        val s = Engines.suggester
        val w = lastWord
        if (s == null || w.isEmpty() || noSuggestions) { showIdleStrip(); return }
        val p = s.predict(w)
        if (p.isEmpty()) showIdleStrip() else strip.setSuggestions(p, -1)
        english = null; englishFor = ""
    }

    private fun showIdleStrip() {
        if (::strip.isInitialized) strip.setSuggestions(emptyList(), -1)
    }

    // ------------------------------------------------------------ Bangla

    private fun updateBanglaComposing(ic: InputConnection) {
        val typed = composing.toString()
        val conv = Engines.phonetic?.parse(typed) ?: typed
        ic.setComposingText(conv, 1)
        banglaWords = listOf(conv); banglaSelected = 0; banglaFor = ""
        val b = Engines.bangla ?: run { strip.setSuggestions(listOf(conv, typed), 0); return }
        val g = ++generation
        worker.post {
            if (g != generation) return@post
            val res = b.suggest(typed)
            main.post {
                if (g != generation || composing.toString() != typed || !bangla) return@post
                applyBangla(typed, res.words, res.selected)
                val pick = banglaWords.getOrNull(banglaSelected)
                if (pick != null && pick != conv) currentInputConnection?.setComposingText(pick, 1)
            }
        }
    }

    private fun applyBangla(typed: String, words: List<String>, selected: Int) {
        // Ridmik-style: the raw English is always offered last, to keep it as English.
        val list = if (typed in words) words else words + typed
        banglaWords = list; banglaSelected = selected; banglaFor = typed
        strip.setSuggestions(list, selected)
    }

    private fun refreshBanglaSync(typed: String) {
        val b = Engines.bangla ?: return
        val res = b.suggest(typed)
        val list = if (typed in res.words) res.words else res.words + typed
        banglaWords = list; banglaSelected = res.selected; banglaFor = typed
    }

    // ------------------------------------------------------------ glide

    private fun glideAllowed() = prefs.glide && !noSuggestions && (!bangla || prefs.banglaGlide)

    override fun onGlide(xs: FloatArray, ys: FloatArray, n: Int) {
        val bn = bangla
        val g = (if (bn) Engines.banglaGlide else Engines.glide) ?: return
        val kb = kv.keyboard ?: return
        val map = keyMap(kb) ?: return
        val px = xs.copyOf(n); val py = ys.copyOf(n)
        val gen = ++generation
        val shift = if (bn) 0 else shiftState
        val lang = if (bn) "bn" else "en"
        val weights = Engines.tuner?.weights(lang)
        worker.post {
            val uses: (String) -> Int = if (bn) { w -> Engines.banglaUses?.count(w) ?: 0 } else { w -> Engines.userDict?.count(w.lowercase()) ?: 0 }
            val results = if (weights != null) g.classify(px, py, n, map, if (bn) 8 else 5, weights, uses) else g.classify(px, py, n, map)
            val keys = swipedKeys(px, py, n, map)
            // Bangla: also offer the plain Avro conversion of the best spelling (names, rare words).
            val fallback = if (bn) results.firstOrNull()?.let { Engines.phonetic?.parse(it.path) } else null
            main.post {
                if (gen != generation || results.isEmpty()) return@post
                val words = results.map {
                    when (shift) { 2 -> it.word.uppercase(); 1 -> it.word.replaceFirstChar { c -> c.uppercaseChar() }; else -> it.word }
                }.toMutableList()
                if (fallback != null && fallback !in words) words += fallback
                commitGlide(words)
                pendingGlide = GlideLog.Pending(lang, keys, results)
            }
        }
    }

    /** The letters under the finger along the swipe, repeats merged ("hgfrtyuio" style). */
    private fun swipedKeys(xs: FloatArray, ys: FloatArray, n: Int, map: GlideClassifier.KeyMap): String {
        val sb = StringBuilder()
        var last = -1
        for (i in 0 until n) {
            var best = 0; var bd = Float.MAX_VALUE
            for (k in 0 until 26) {
                val dx = map.cx[k] - xs[i]; val dy = map.cy[k] - ys[i]
                val d = dx * dx + dy * dy
                if (d < bd) { bd = d; best = k }
            }
            if (best != last) { sb.append('a' + best); last = best }
        }
        return sb.toString()
    }

    /** Record how the last glide ended, learn from it, and tune the ranking when the user overrode it. */
    private fun finishGlide(deleted: Boolean) {
        val p = pendingGlide ?: return
        pendingGlide = null
        val log = Engines.glideLog
        val tuner = Engines.tuner
        val bn = p.lang == "bn"
        val chosen = p.chosen
        val chosenResult = p.candidates.getOrNull(chosen)
        val record = prefs.logGlides
        worker.post {
            log?.finish(p, if (deleted) GlideLog.DELETED else if (chosen == 0) GlideLog.KEPT else GlideLog.PICKED, record)
            if (!deleted && chosenResult != null) {
                if (bn) Engines.banglaUses?.add(chosenResult.word)
                if (chosen > 0) tuner?.learn(p.lang, chosenResult, p.candidates[0])
            }
        }
    }

    private fun keyMap(kb: Keyboard): GlideClassifier.KeyMap? {
        val cx = FloatArray(26); val cy = FloatArray(26)
        var w = 0f
        for (i in 0 until 26) {
            val k = kb.letterKeys['a' + i] ?: return null
            cx[i] = k.cx; cy[i] = k.cy; w += k.w
        }
        return GlideClassifier.KeyMap(cx, cy, w / 26f)
    }

    private fun commitGlide(words: List<String>) {
        val ic = currentInputConnection ?: return
        ic.beginBatchEdit()
        if (composing.isNotEmpty()) commitComposing(useAuto = !glideWord)
        val before = ic.getTextBeforeCursor(1, 0)?.toString() ?: ""
        if (before.isNotEmpty() && !before[0].isWhitespace() && before[0] !in "([{\"'¿¡/@#") ic.commitText(" ", 1)
        composing.setLength(0); composing.append(words[0])
        glideWord = true
        ic.setComposingText(composing, 1)
        ic.endBatchEdit()
        strip.setSuggestions(words, 0)
        consumeOneShotShift()
        generation++
        updateLetterPrior()
    }

    // ------------------------------------------------------------ strip

    override fun onPickSuggestion(index: Int, word: String) {
        val ic = currentInputConnection ?: return
        ic.beginBatchEdit()
        if (glideWord) {
            // Swap the glided word for the chosen alternative, keep composing.
            pendingGlide?.chosen = index
            composing.setLength(0); composing.append(word)
            ic.setComposingText(word, 1)
            ic.endBatchEdit()
            return
        }
        val typed = composing.toString()
        if (typed.isEmpty()) {
            // Next-word prediction.
            ic.commitText("$word ", 1)
            lastWord = word
            ic.endBatchEdit()
            Engines.userDict?.let { ud -> worker.post { ud.learn(word) } }
            showPredictions(); updateShift()
            return
        }
        ic.commitText(word, 1)
        if (bangla) {
            val b = Engines.bangla
            if (b != null && word != typed) worker.post { b.commitSelection(typed, word) }
            lastWord = ""
        } else {
            if (word == typed) Engines.userDict?.learnNow(word) else Engines.userDict?.let { ud -> worker.post { ud.learn(word) } }
            lastWord = word
        }
        ic.commitText(" ", 1)
        autoSpaced = true
        undoAutocorrect = null
        composing.setLength(0); glideWord = false; generation++
        ic.endBatchEdit()
        if (bangla) showIdleStrip() else showPredictions()
        consumeOneShotShift(); updateShift(); updateLetterPrior()
    }

    override fun onToolbar(action: Int) {
        when (action) {
            CandidateView.TOOL_CLIPBOARD -> showClipboard()
            CandidateView.TOOL_EMOJI -> showEmoji()
            CandidateView.TOOL_SETTINGS -> startActivity(Intent(this, SetupActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            CandidateView.TOOL_HIDE -> requestHideSelf(0)
        }
    }

    private fun toggleLanguage() {
        if (composing.isNotEmpty()) commitComposing(useAuto = true)
        prefs.bangla = !prefs.bangla
        val ei = currentInputEditorInfo
        val cls = (ei?.inputType ?: 0) and InputType.TYPE_MASK_CLASS
        bangla = prefs.bangla && !passwordField && !emailField && !urlField && cls == InputType.TYPE_CLASS_TEXT
        kv.spaceLabel = spaceLabel()
        kv.glideEnabled = glideAllowed()
        shiftState = 0
        updateShift()
        showIdleStrip()
        strip.showMessage(if (bangla) "বাংলা (Avro)" else "English")
    }

    // ------------------------------------------------------------ panels

    private fun showKeys() {
        if (!::content.isInitialized) return
        if (kv.parent == null) {
            content.removeAllViews()
            content.addView(kv, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        }
        strip.visibility = View.VISIBLE
    }

    private fun panelHeight() = kv.measuredHeight.takeIf { it > 0 } ?: kv.minHeightPx

    private fun showEmoji() {
        if (composing.isNotEmpty()) commitComposing(useAuto = false)
        val h = panelHeight()
        val ev = emojiView ?: EmojiView(this, this).also { emojiView = it }
        ev.theme = theme
        ev.recents = prefs.emojiRecents.split(' ').filter { it.isNotEmpty() }.toMutableList()
        ev.refresh()
        kv.cancelAll()
        content.removeAllViews()
        content.addView(ev, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, h + dp(prefs.toolbarDp)))
        strip.visibility = View.GONE
        if (EmojiData.groups == null) worker.post { EmojiData.load(this); main.post { ev.refresh() } }
    }

    private fun showClipboard() {
        if (composing.isNotEmpty()) commitComposing(useAuto = false)
        val h = panelHeight()
        val cv = clipView ?: ClipboardView(this, clipHistory, this).also { clipView = it }
        cv.theme = theme
        cv.refresh()
        kv.cancelAll()
        content.removeAllViews()
        content.addView(cv, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, h + dp(prefs.toolbarDp)))
        strip.visibility = View.GONE
    }

    override fun onEmoji(e: String) {
        currentInputConnection?.commitText(e, 1)
        lastWord = ""
        prefs.emojiRecents = emojiView?.recents?.joinToString(" ") ?: ""
    }

    override fun onEmojiDelete() = handleDelete()
    override fun onEmojiSpace() { currentInputConnection?.commitText(" ", 1) }
    override fun onEmojiBack() { showKeys(); updateShift() }

    override fun onClipPaste(text: String) {
        currentInputConnection?.commitText(text, 1)
        showKeys(); updateShift()
    }

    override fun onClipBack() { showKeys(); updateShift() }

    // ------------------------------------------------------------ clipboard tricks (Ridmik-style)

    private val clipboard get() = getSystemService(ClipboardManager::class.java)

    private fun fieldText(ic: InputConnection): CharSequence? {
        val ex = ic.getExtractedText(ExtractedTextRequest().apply { hintMaxChars = 200_000 }, 0)
        if (ex?.text != null && ex.partialStartOffset < 0 && ex.startOffset == 0) return ex.text
        val before = ic.getTextBeforeCursor(100_000, 0) ?: ""
        val after = ic.getTextAfterCursor(100_000, 0) ?: ""
        val sel = ic.getSelectedText(0) ?: ""
        val all = "$before$sel$after"
        return all.ifEmpty { ex?.text }
    }

    /** Long-press C: copy the selection, or the whole field if nothing is selected. */
    private fun copy() {
        val ic = currentInputConnection ?: return
        if (passwordField) { strip.showMessage("Not copying a password"); return }
        if (composing.isNotEmpty()) commitComposing(useAuto = false)
        val sel = ic.getSelectedText(0)
        val text = if (!sel.isNullOrEmpty()) sel else fieldText(ic)
        if (text.isNullOrEmpty()) { strip.showMessage("Nothing to copy"); return }
        clipboard?.setPrimaryClip(ClipData.newPlainText("Mixer", text))
        strip.showMessage(if (sel.isNullOrEmpty()) "Copied all text" else "Copied")
    }

    /** Long-press V: paste. */
    private fun paste() {
        val ic = currentInputConnection ?: return
        val clip = try { clipboard?.primaryClip } catch (_: SecurityException) { null }
        val text = clip?.takeIf { it.itemCount > 0 }?.getItemAt(0)?.coerceToText(this)
        if (text.isNullOrEmpty()) { strip.showMessage("Clipboard is empty"); return }
        if (composing.isNotEmpty()) commitComposing(useAuto = false)
        ic.commitText(text, 1)
        lastWord = ""
        showIdleStrip()
    }

    /** Long-press X: cut the selection, or the whole field if nothing is selected. */
    private fun cut() {
        val ic = currentInputConnection ?: return
        if (passwordField) return
        if (composing.isNotEmpty()) commitComposing(useAuto = false)
        val sel = ic.getSelectedText(0)
        val text = if (!sel.isNullOrEmpty()) sel else fieldText(ic)
        if (text.isNullOrEmpty()) { strip.showMessage("Nothing to cut"); return }
        clipboard?.setPrimaryClip(ClipData.newPlainText("Mixer", text))
        ic.beginBatchEdit()
        if (sel.isNullOrEmpty()) ic.performContextMenuAction(android.R.id.selectAll)
        ic.commitText("", 1)
        ic.endBatchEdit()
        strip.showMessage("Cut")
    }

    /** Long-press A: select all. */
    private fun selectAll() {
        val ic = currentInputConnection ?: return
        if (composing.isNotEmpty()) commitComposing(useAuto = false)
        ic.performContextMenuAction(android.R.id.selectAll)
    }

    /** Arrows hint that swiping the space bar switches language. */
    private fun spaceLabel() = if (bangla) "‹  বাংলা  ›" else "‹  English  ›"

    private fun dp(v: Int) = (v * resources.displayMetrics.density + 0.5f).toInt()

    companion object {
        private const val MAX_CHAR = '￿'
        private const val AUTOCORRECT_TRIGGERS =".,!?;:)\"'"
    }
}
