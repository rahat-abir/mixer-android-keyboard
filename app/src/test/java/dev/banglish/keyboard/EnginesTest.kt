package dev.banglish.keyboard

import dev.banglish.keyboard.bangla.AvroPhonetic
import dev.banglish.keyboard.bangla.BanglaSuggester
import dev.banglish.keyboard.glide.BanglaGlideLexicon
import dev.banglish.keyboard.glide.GlideClassifier
import dev.banglish.keyboard.glide.GlideTuner
import dev.banglish.keyboard.suggest.EnglishDictionary
import dev.banglish.keyboard.suggest.Suggester
import dev.banglish.keyboard.suggest.UserDictionary
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.BeforeClass
import org.junit.Test
import java.io.File

class EnginesTest {

    companion object {
        private val assets = File("src/main/assets")
        private fun text(p: String) = File(assets, p).readText()
        lateinit var phonetic: AvroPhonetic
        lateinit var dict: EnglishDictionary
        lateinit var bangla: BanglaSuggester

        @BeforeClass @JvmStatic fun load() {
            phonetic = AvroPhonetic(text("avro/phonetic.json"), regexMode = false)
            dict = EnglishDictionary.load(File(assets, "en_words.txt").inputStream(), File(assets, "en_shortcuts.txt").inputStream(), File(assets, "en_bigrams.txt").inputStream())
            bangla = BanglaSuggester(
                phonetic, AvroPhonetic(text("avro/regex.json"), regexMode = true),
                BanglaSuggester.loadTables(File(assets, "avro/dict.txt").inputStream()),
                BanglaSuggester.loadMap(text("avro/suffix.json")), BanglaSuggester.loadMap(text("avro/autocorrect.json")), null,
            )
        }
    }

    @Test fun avroPhonetic() {
        val cases = mapOf(
            "ami" to "আমি", "tumi" to "তুমি", "bhalo" to "ভাল", "bhalO" to "ভালো", "bangla" to "বাংলা",
            "amar sOnar bangla" to "আমার সোনার বাংলা", "kkhoma" to "ক্ষমা", "rri" to "ঋ",
            "ami." to "আমি।", "2024" to "২০২৪", "ki khobor" to "কি খবর", "Dhaka" to "ঢাকা",
            "bhaShA" to "ভাষা", "ekuSe" to "একুশে", "ca^d" to "চাঁদ", "cha^d" to "ছাঁদ", "du:kh" to "দুঃখ",
        )
        for ((en, bn) in cases) assertEquals(en, bn, phonetic.parse(en))
    }

    @Test fun banglaDictionarySuggestions() {
        val s = bangla.suggest("ami")
        assertTrue(s.words.toString(), "আমি" in s.words)
        val k = bangla.suggest("kemon")
        assertTrue(k.words.toString(), "কেমন" in k.words)
        val t0 = System.nanoTime()
        bangla.suggest("bhalobasha")
        val ms = (System.nanoTime() - t0) / 1e6
        println("bangla suggest: ${"%.1f".format(ms)} ms -> ${bangla.suggest("bhalobasha").words}")
    }

    @Test fun englishAutocorrect() {
        val s = Suggester(dict, UserDictionary(null))
        fun auto(w: String) = s.suggest(w, true).let { if (it.autoIndex >= 0) it.words[it.autoIndex] else w }
        assertEquals("the", auto("teh"))
        assertEquals("hello", auto("hwllo"))
        assertEquals("I'm", auto("im"))
        assertEquals("don't", auto("dont"))
        assertEquals("hello", auto("hello")) // known word stays
        assertEquals("Thanks", auto("Thsnks"))
        val t0 = System.nanoTime(); s.suggest("beautifull", true)
        println("english suggest: ${"%.1f".format((System.nanoTime() - t0) / 1e6)} ms")
    }

    /** Synthetic QWERTY: 100px keys. */
    private fun keyMap(): GlideClassifier.KeyMap {
        val cx = FloatArray(26); val cy = FloatArray(26)
        val rows = arrayOf("qwertyuiop", "asdfghjkl", "zxcvbnm"); val offs = floatArrayOf(0f, 50f, 150f)
        rows.forEachIndexed { r, row -> row.forEachIndexed { i, c -> cx[c - 'a'] = offs[r] + i * 100f + 50f; cy[c - 'a'] = r * 120f + 60f } }
        return GlideClassifier.KeyMap(cx, cy, 100f)
    }

    /** A wobbly finger path through the given letters. */
    private fun path(word: String, km: GlideClassifier.KeyMap): Pair<FloatArray, FloatArray> {
        val xs = ArrayList<Float>(); val ys = ArrayList<Float>()
        val rnd = java.util.Random(word.hashCode().toLong())
        val pts = word.map { km.cx[it - 'a'] to km.cy[it - 'a'] }
        for (i in 0 until pts.size - 1) {
            val (x0, y0) = pts[i]; val (x1, y1) = pts[i + 1]
            for (s in 0 until 12) {
                val t = s / 12f
                xs += x0 + (x1 - x0) * t + (rnd.nextFloat() - 0.5f) * 30f
                ys += y0 + (y1 - y0) * t + (rnd.nextFloat() - 0.5f) * 30f
            }
        }
        xs += pts.last().first; ys += pts.last().second
        return xs.toFloatArray() to ys.toFloatArray()
    }

    @Test fun glide() {
        val g = GlideClassifier(dict)
        val km = keyMap()
        var hits = 0
        val words = listOf("hello", "the", "keyboard", "thanks", "where", "good", "morning", "people", "about", "typing", "you", "what")
        for (w in words) {
            val (xs, ys) = path(w, km)
            val t0 = System.nanoTime()
            val res = g.classify(xs, ys, xs.size, km)
            val ms = (System.nanoTime() - t0) / 1e6
            println("glide $w -> ${res.take(3).map { it.word }} (${"%.1f".format(ms)} ms)")
            if (res.firstOrNull()?.word == w) hits++
        }
        assertTrue("glide top-1 hits $hits/${words.size}", hits >= words.size - 2)
    }

    /** Swipe = the user's natural Banglish; expect the Bangla word in the top 3. */
    @Test fun banglaGlide() {
        val g = GlideClassifier(BanglaGlideLexicon.load(File(assets, "bn_glide.txt").inputStream()))
        val km = keyMap()
        val cases = listOf(
            "ami" to "আমি", "tumi" to "তুমি", "kemon" to "কেমন", "valo" to "ভালো", "bhalo" to "ভালো",
            "bangladesh" to "বাংলাদেশ", "dhonobad" to "ধন্যবাদ", "taka" to "টাকা", "korchi" to "করছি",
            "amader" to "আমাদের", "ki" to "কি", "kotha" to "কথা", "bolo" to "বলো", "ache" to "আছে",
            "jabo" to "যাবো", "khabar" to "খাবার", "bhai" to "ভাই", "apni" to "আপনি", "tomar" to "তোমার",
            "kintu" to "কিন্তু", "ekhon" to "এখন", "kal" to "কাল", "shob" to "সব", "valobasi" to "ভালোবাসি",
            "kaj" to "কাজ", "school" to "স্কুল", "pani" to "পানি", "bari" to "বাড়ি", "hoy" to "হয়", "jay" to "যায়", "niye" to "নিয়ে", "dekho" to "দেখো", "keno" to "কেন",
        )
        var top1 = 0; var top3 = 0
        for ((sw, bn) in cases) {
            val (xs, ys) = path(sw, km)
            val t0 = System.nanoTime()
            val res = g.classify(xs, ys, xs.size, km, 8)
            val ms = (System.nanoTime() - t0) / 1e6
            val words = res.map { it.word }
            if (words.firstOrNull() == bn) top1++
            if (bn in words.take(3)) top3++
            println("bn glide $sw -> ${words.take(4)} (${"%.1f".format(ms)} ms)${if (bn !in words.take(3)) "  MISS want $bn" else ""}")
        }
        println("bangla glide top1 $top1/${cases.size}, top3 $top3/${cases.size}")
        assertTrue("bangla glide top3 $top3/${cases.size}", top3 >= cases.size * 0.8)
    }

    @Test fun tunerPrefersPickedWord() {
        val sp = MapPrefs()
        val t = GlideTuner(sp)
        val a = GlideClassifier.Result("a", "a", 0f, floatArrayOf(0.3f, 0.1f, 5f, 0f, 4f))
        val b = GlideClassifier.Result("b", "b", 0f, floatArrayOf(0.35f, 0.1f, 3f, 2f, 4f))
        repeat(30) { t.learn("bn", b, a) }
        val w = t.weights("bn")
        assertTrue(w.cost(b.features) < w.cost(a.features))
    }
}
