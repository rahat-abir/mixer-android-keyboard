package dev.banglish.keyboard.layout

/** Special key codes. Printable keys use their character's code point. */
object Codes {
    const val SHIFT = -1
    const val SYMBOLS = -2
    const val ALPHA = -3
    const val ENTER = -4
    const val DELETE = -5
    const val SYMBOLS2 = -6
    const val EMOJI = -7
    const val LANG = -8
    const val SPACE = 32
}

/**
 * One key. [x], [y], [w], [h] are filled in by [Keyboard.layout] in pixels so the view never
 * computes geometry while drawing.
 */
class Key(
    val code: Int,
    val label: String,
    val weight: Float = 1f,
    /** Characters offered on long-press, first one is the default selection. */
    val popup: List<String> = emptyList(),
    /** Small corner hint (e.g. the number on the top letter row). */
    val hint: String? = null,
    /** Text committed on tap; defaults to [label]. */
    val text: String = label,
    val isFunction: Boolean = code < 0 || code == Codes.SPACE,
) {
    var x = 0f; var y = 0f; var w = 0f; var h = 0f
    val cx get() = x + w / 2f
    val cy get() = y + h / 2f
    val isLetter = code > 0 && label.length == 1 && label[0].isLetter()
    val upper: String = label.uppercase()
    fun contains(px: Float, py: Float) = px >= x && px < x + w && py >= y && py < y + h
}

/** [short] rows (the number row) use the shorter number-row height. */
class Row(val keys: List<Key>, val leftPad: Float = 0f, val rightPad: Float = 0f, val short: Boolean = false)

class Keyboard(val id: Int, val rows: List<Row>) {
    val keys: List<Key> = rows.flatMap { it.keys }
    var width = 0; private set
    var height = 0; private set
    /** Letter -> key, used by glide typing to build ideal word paths. */
    val letterKeys = HashMap<Char, Key>()

    /**
     * [pitch] / [shortPitch] = row height including the row gap (touch areas meet edge to edge;
     * the gap is only drawn). If [minHeight] is larger than the natural height, rows stretch so
     * every page (letters, symbols, number pad) is the same height and nothing jumps.
     */
    fun layout(width: Int, pitch: Int, shortPitch: Int = pitch, minHeight: Int = 0) {
        this.width = width
        val natural = rows.sumOf { if (it.short) shortPitch else pitch }
        val scale = if (natural in 1 until minHeight) minHeight.toFloat() / natural else 1f
        val rowY = FloatArray(rows.size); val rowH = FloatArray(rows.size)
        var yAcc = 0f
        rows.forEachIndexed { i, r -> rowY[i] = yAcc; rowH[i] = (if (r.short) shortPitch else pitch) * scale; yAcc += rowH[i] }
        this.height = Math.round(yAcc)
        // The widest row defines the unit so every row's keys share one key width.
        val units = rows.maxOf { r -> r.keys.sumOf { it.weight.toDouble() }.toFloat() + r.leftPad + r.rightPad }
        val unit = width / units
        rows.forEachIndexed { ri, row ->
            val rowUnits = row.keys.sumOf { it.weight.toDouble() }.toFloat() + row.leftPad + row.rightPad
            // Rows narrower than the widest are centred (e.g. the a–l row).
            var x = (width - rowUnits * unit) / 2f + row.leftPad * unit
            for (k in row.keys) {
                k.x = x; k.y = rowY[ri]; k.w = k.weight * unit; k.h = rowH[ri]
                x += k.w
            }
        }
        // Centred rows (a–l) keep the same key width as every other row, like Samsung's layout;
        // a touch in the half-key margin still lands on the nearest key via keyAt().
        letterKeys.clear()
        for (k in keys) if (k.isLetter) letterKeys[k.label[0].lowercaseChar()] = k
    }

    /** Nearest key to a point; touches in gaps still hit something. */
    fun keyAt(px: Float, py: Float): Key? {
        for (k in keys) if (k.contains(px, py)) return k
        var best: Key? = null; var bestD = Float.MAX_VALUE
        for (k in keys) {
            val dx = k.cx - px; val dy = k.cy - py
            val d = dx * dx + dy * dy
            if (d < bestD) { bestD = d; best = k }
        }
        return best
    }
}

object Layouts {
    const val QWERTY = 1
    const val SYMBOLS = 2
    const val SYMBOLS2 = 3
    const val NUMPAD = 4
    const val PHONE = 5

    private val accents = mapOf(
        'a' to "à á â ä æ ã å ā", 'c' to "ç ć č", 'e' to "è é ê ë ē ę", 'i' to "ì í î ï ī",
        'n' to "ñ ń", 'o' to "ò ó ô ö õ œ ø ō", 's' to "ß ś š", 'u' to "ù ú û ü ū",
        'y' to "ý ÿ", 'z' to "ž ź ż", 'l' to "ł", 'g' to "ğ", 'd' to "ð",
    )

    private fun letter(c: Char, hint: String? = null): Key {
        val pop = mutableListOf<String>()
        if (hint != null) pop += hint
        accents[c]?.split(' ')?.let { pop += it }
        return Key(c.code, c.toString(), popup = pop, hint = hint, isFunction = false)
    }

    private fun sym(s: String, popup: String = "", weight: Float = 1f) =
        Key(s.codePointAt(0), s, weight, popup = if (popup.isEmpty()) emptyList() else popup.split(' '), isFunction = false)

    /** The bottom row shared by letters and symbols. [modeKey] is ?123 or ABC. */
    private fun bottomRow(modeKey: Key, email: Boolean, url: Boolean): Row {
        val comma = when {
            email -> sym("@", ", _ -")
            url -> sym("/", ", : _ -")
            else -> sym(",", "! ? ; : ' \" -")
        }
        val period = if (url || email) sym(".", ".com .net .org .bd ? ! :") else sym(".", "? ! , ' \" : ; - … ।")
        // Samsung-style: mode key, comma, wide space (swipe it to switch language), period, enter.
        // Emoji lives in the strip above the keys.
        return Row(
            listOf(
                modeKey, comma,
                Key(Codes.SPACE, " ", 5f, text = " "),
                period,
                Key(Codes.ENTER, "⏎", 1.5f),
            )
        )
    }

    fun qwerty(numberRow: Boolean, email: Boolean = false, url: Boolean = false): Keyboard {
        val rows = mutableListOf<Row>()
        val digits = "1234567890"
        if (numberRow) rows += Row(digits.map { d ->
            Key(d.code, d.toString(), popup = listOf(numberPopup(d)).filter { it.isNotEmpty() }.flatMap { it.split(' ') }, isFunction = false)
        }, short = true)
        rows += Row("qwertyuiop".mapIndexed { i, c -> letter(c, if (numberRow) null else digits[i].toString()) })
        rows += Row("asdfghjkl".map { letter(it) }, leftPad = 0.5f, rightPad = 0.5f)
        rows += Row(
            listOf(Key(Codes.SHIFT, "⇧", 1.5f)) + "zxcvbnm".map { letter(it) } + Key(Codes.DELETE, "⌫", 1.5f)
        )
        rows += bottomRow(Key(Codes.SYMBOLS, "!#1", 1.5f), email, url)
        return Keyboard(QWERTY, rows)
    }

    private fun numberPopup(d: Char) = when (d) {
        '1' -> "¹ ½ ⅓ ¼"; '2' -> "² ⅔"; '3' -> "³ ¾"; '0' -> "° ∅"; else -> ""
    }

    fun symbols(email: Boolean = false, url: Boolean = false): Keyboard = Keyboard(
        SYMBOLS, listOf(
            Row("1234567890".map { sym(it.toString(), numberPopup(it)) }),
            Row(listOf(
                sym("@"), sym("#"), sym("৳", "$ € £ ₹ ¥ ¢"), sym("_"), sym("&"), sym("-", "– — ·"),
                sym("+", "±"), sym("(", "< [ {"), sym(")", "> ] }"), sym("/", "\\ |"),
            )),
            Row(
                listOf(Key(Codes.SYMBOLS2, "=\\<", 1.5f)) +
                    listOf(sym("*", "★ †"), sym("\"", "“ ” « »"), sym("'", "‘ ’ ‹ ›"), sym(":"), sym(";"), sym("!", "¡"), sym("?", "¿")) +
                    Key(Codes.DELETE, "⌫", 1.5f)
            ),
            bottomRow(Key(Codes.ALPHA, "ABC", 1.5f), email, url),
        )
    )

    fun symbols2(email: Boolean = false, url: Boolean = false): Keyboard = Keyboard(
        SYMBOLS2, listOf(
            Row("~ ` | • √ π ÷ × ¶ ∆".split(' ').map { sym(it) }),
            Row("£ € $ ¢ ^ ° = { } \\".split(' ').map { sym(it) }),
            Row(
                listOf(Key(Codes.SYMBOLS, "?123", 1.5f)) +
                    "% © ® ™ ✓ [ ]".split(' ').map { sym(it) } +
                    Key(Codes.DELETE, "⌫", 1.5f)
            ),
            bottomRow(Key(Codes.ALPHA, "ABC", 1.5f), email, url),
        )
    )

    fun numpad(phone: Boolean): Keyboard {
        fun n(s: String, popup: String = "") = sym(s, popup, 1f)
        val fourth = if (phone) n("+", "* # ( ) - , ; /") else n("-", "+ / , ( )")
        return Keyboard(
            if (phone) PHONE else NUMPAD, listOf(
                Row(listOf(n("1"), n("2"), n("3"), fourth)),
                Row(listOf(n("4"), n("5"), n("6"), Key(Codes.SPACE, "␣", text = " "))),
                Row(listOf(n("7"), n("8"), n("9"), Key(Codes.DELETE, "⌫"))),
                Row(listOf(Key(Codes.SYMBOLS, "?123"), n("0", if (phone) "+ * #" else ""), n(if (phone) "*" else ".", if (phone) "# ," else ","), Key(Codes.ENTER, "⏎"))),
            )
        )
    }
}
