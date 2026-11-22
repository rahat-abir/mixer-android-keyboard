package dev.banglish.keyboard

import android.content.Context
import android.os.SystemClock
import android.util.Log
import dev.banglish.keyboard.bangla.AvroPhonetic
import dev.banglish.keyboard.bangla.BanglaSuggester
import dev.banglish.keyboard.glide.BanglaGlideLexicon
import dev.banglish.keyboard.glide.GlideClassifier
import dev.banglish.keyboard.glide.GlideLog
import dev.banglish.keyboard.glide.GlideTuner
import dev.banglish.keyboard.glide.WordUses
import dev.banglish.keyboard.suggest.EnglishDictionary
import dev.banglish.keyboard.suggest.Suggester
import dev.banglish.keyboard.suggest.UserDictionary
import java.io.File

/**
 * Dictionaries and engines, loaded once per process on the background thread. The keyboard is
 * fully usable before they finish; suggestions/glide simply start working when ready.
 */
object Engines {
    @Volatile var english: EnglishDictionary? = null; private set
    @Volatile var suggester: Suggester? = null; private set
    @Volatile var glide: GlideClassifier? = null; private set
    @Volatile var bangla: BanglaSuggester? = null; private set
    @Volatile var phonetic: AvroPhonetic? = null; private set
    @Volatile var userDict: UserDictionary? = null; private set
    @Volatile var banglaGlide: GlideClassifier? = null; private set
    @Volatile var banglaUses: WordUses? = null; private set
    @Volatile var glideLog: GlideLog? = null; private set
    @Volatile var tuner: GlideTuner? = null; private set
    @Volatile private var personalLoaded = false

    /** Run on the worker thread. Safe to call repeatedly. */
    fun load(context: Context, unlocked: Boolean) {
        val app = context.applicationContext
        val a = app.assets
        val t0 = SystemClock.uptimeMillis()
        if (phonetic == null) {
            phonetic = AvroPhonetic(a.open("avro/phonetic.json").bufferedReader().readText(), regexMode = false)
        }
        val personalNow = unlocked && !personalLoaded
        if (english == null || personalNow) {
            val dict = english ?: EnglishDictionary.load(a.open("en_words.txt"), a.open("en_shortcuts.txt"), a.open("en_bigrams.txt"))
            val ud = UserDictionary(if (unlocked) File(app.filesDir, "user_words.txt") else null)
            english = dict
            userDict = ud
            suggester = Suggester(dict, ud)
            if (glide == null) glide = GlideClassifier(dict).also { dict.byFirstLetter }
        }
        if (bangla == null || personalNow) {
            bangla = BanglaSuggester(
                phonetic!!,
                AvroPhonetic(a.open("avro/regex.json").bufferedReader().readText(), regexMode = true),
                BanglaSuggester.loadTables(a.open("avro/dict.txt")),
                BanglaSuggester.loadMap(a.open("avro/suffix.json").bufferedReader().readText()),
                BanglaSuggester.loadMap(a.open("avro/autocorrect.json").bufferedReader().readText()),
                if (unlocked) File(app.filesDir, "bangla_choices.txt") else null,
            )
        }
        if (banglaGlide == null) {
            banglaGlide = GlideClassifier(BanglaGlideLexicon.load(a.open("bn_glide.txt")).also { it.byFirstLetter })
        }
        if (banglaUses == null || personalNow) {
            banglaUses = WordUses(if (unlocked) File(app.filesDir, "bangla_uses.txt") else null)
        }
        val sp = Prefs(app).sp
        if (tuner == null) tuner = GlideTuner(sp)
        if (glideLog == null || personalNow) glideLog = GlideLog(if (unlocked) File(app.filesDir, "glide_log.tsv") else null, sp)
        if (unlocked) personalLoaded = true
        Log.i("BanglishKeyboard", "engines ready in ${SystemClock.uptimeMillis() - t0} ms")
    }

    fun save() {
        userDict?.saveIfDirty()
        bangla?.saveIfDirty()
        banglaUses?.saveIfDirty()
        glideLog?.saveIfDirty()
    }
}
