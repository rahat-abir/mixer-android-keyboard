package dev.banglish.keyboard.clip

import android.content.ClipDescription
import android.content.ClipboardManager
import android.content.Context
import android.os.Build
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

class ClipItem(val text: String, var pinned: Boolean, val time: Long)

/**
 * Remembers recent copies while the keyboard is alive. Kept in normal (credential-protected)
 * storage and only after unlock. Anything the source app marks sensitive is never stored.
 */
class ClipHistory(private val context: Context) {
    val items = ArrayList<ClipItem>()
    private val file get() = File(context.filesDir, "clipboard.json")
    private var loaded = false
    var paused = false // true while typing in a password field

    private val cm = context.getSystemService(ClipboardManager::class.java)
    private val listener = ClipboardManager.OnPrimaryClipChangedListener { capture() }

    fun start(unlocked: Boolean) {
        if (!unlocked) return
        if (!loaded) { load(); loaded = true }
        cm?.removePrimaryClipChangedListener(listener)
        cm?.addPrimaryClipChangedListener(listener)
    }

    fun stop() { cm?.removePrimaryClipChangedListener(listener) }

    private fun capture() {
        if (paused || !loaded) return
        val clip = try { cm?.primaryClip } catch (_: SecurityException) { null } ?: return
        val desc = clip.description
        val extras = desc?.extras
        if (extras != null && (
                extras.getBoolean("android.content.extra.IS_SENSITIVE") ||
                    (Build.VERSION.SDK_INT >= 33 && extras.getBoolean(ClipDescription.EXTRA_IS_SENSITIVE)))
        ) return
        if (clip.itemCount == 0) return
        val text = clip.getItemAt(0).coerceToText(context)?.toString() ?: return
        add(text)
    }

    fun add(text: String) {
        if (text.isBlank() || text.length > 5000) return
        val existing = items.indexOfFirst { it.text == text }
        val pinned = existing >= 0 && items[existing].pinned
        if (existing >= 0) items.removeAt(existing)
        items.add(0, ClipItem(text, pinned, System.currentTimeMillis()))
        // Keep 20 unpinned items; pinned ones stay forever.
        var unpinned = 0
        val it = items.iterator()
        while (it.hasNext()) { val c = it.next(); if (!c.pinned && ++unpinned > 20) it.remove() }
        save()
    }

    fun togglePin(i: Int) { items.getOrNull(i)?.let { it.pinned = !it.pinned; save() } }
    fun remove(i: Int) { if (i in items.indices) { items.removeAt(i); save() } }
    fun clearUnpinned() { items.removeAll { !it.pinned }; save() }

    private fun load() {
        try {
            if (!file.exists()) return
            val arr = JSONArray(file.readText())
            for (i in 0 until arr.length()) {
                val o = arr.getJSONObject(i)
                items += ClipItem(o.getString("t"), o.optBoolean("p"), o.optLong("ts"))
            }
        } catch (_: Exception) { items.clear() }
    }

    private fun save() {
        if (!loaded) return
        val arr = JSONArray()
        for (c in items) arr.put(JSONObject().put("t", c.text).put("p", c.pinned).put("ts", c.time))
        val snapshot = arr.toString()
        Thread { try { file.writeText(snapshot) } catch (_: Exception) {} }.start()
    }
}
