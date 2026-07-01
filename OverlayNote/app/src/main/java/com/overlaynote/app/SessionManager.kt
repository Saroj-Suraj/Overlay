package com.overlaynote.app

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream

data class Session(
    val id      : String,
    val subject : String,
    val topic   : String,
    val date    : String,
    val created : String,
    val pages   : MutableList<String>   // list of page IDs
)

object SessionManager {

    private const val PREF_FILE = "overlaynote_sessions"
    private const val KEY_LIST  = "session_list"

    // ── Session CRUD ─────────────────────────────────────────────
    fun getSessions(context: Context): List<Session> {
        val prefs = context.getSharedPreferences(PREF_FILE, Context.MODE_PRIVATE)
        val json  = prefs.getString(KEY_LIST, "[]") ?: "[]"
        return parseSessionList(json).sortedByDescending { it.created }
    }

    fun getSession(context: Context, id: String): Session? =
        getSessions(context).find { it.id == id }

    fun saveSession(context: Context, session: Session) {
        val list = getSessions(context).toMutableList()
        val idx  = list.indexOfFirst { it.id == session.id }
        if (idx >= 0) list[idx] = session else list.add(0, session)
        persist(context, list)
    }

    fun deleteSession(context: Context, id: String) {
        val list = getSessions(context).filter { it.id != id }
        persist(context, list)
        // Delete page bitmaps
        val dir = pageDir(context, id)
        dir.deleteRecursively()
    }

    private fun persist(context: Context, list: List<Session>) {
        val arr = JSONArray()
        list.forEach { arr.put(sessionToJson(it)) }
        context.getSharedPreferences(PREF_FILE, Context.MODE_PRIVATE)
            .edit().putString(KEY_LIST, arr.toString()).apply()
    }

    // ── Page bitmap storage ──────────────────────────────────────
    fun savePages(context: Context, sessionId: String, pages: List<Bitmap?>) {
        val session = getSession(context, sessionId) ?: return
        val dir     = pageDir(context, sessionId)
        dir.mkdirs()

        val pageIds = mutableListOf<String>()
        pages.forEachIndexed { i, bmp ->
            val pageId = if (i < session.pages.size) session.pages[i]
                         else "page_${System.currentTimeMillis()}_$i"
            pageIds.add(pageId)
            bmp?.let { saveBitmap(dir, pageId, it) }
        }
        val updated = session.copy(pages = pageIds.toMutableList())
        saveSession(context, updated)
    }

    fun loadPageBitmap(context: Context, sessionId: String, pageId: String): Bitmap? {
        val file = File(pageDir(context, sessionId), "$pageId.png")
        return if (file.exists()) BitmapFactory.decodeFile(file.absolutePath) else null
    }

    private fun saveBitmap(dir: File, pageId: String, bmp: Bitmap) {
        val file = File(dir, "$pageId.png")
        FileOutputStream(file).use { bmp.compress(Bitmap.CompressFormat.PNG, 100, it) }
    }

    private fun pageDir(context: Context, sessionId: String) =
        File(context.filesDir, "sessions/$sessionId")

    // ── JSON helpers ─────────────────────────────────────────────
    private fun sessionToJson(s: Session): JSONObject = JSONObject().apply {
        put("id",      s.id)
        put("subject", s.subject)
        put("topic",   s.topic)
        put("date",    s.date)
        put("created", s.created)
        put("pages",   JSONArray(s.pages))
    }

    private fun parseSessionList(json: String): List<Session> {
        val arr  = JSONArray(json)
        val list = mutableListOf<Session>()
        for (i in 0 until arr.length()) {
            val o = arr.getJSONObject(i)
            val pages = mutableListOf<String>()
            val pArr  = o.optJSONArray("pages")
            if (pArr != null) for (j in 0 until pArr.length()) pages.add(pArr.getString(j))
            list.add(Session(
                id      = o.getString("id"),
                subject = o.getString("subject"),
                topic   = o.getString("topic"),
                date    = o.getString("date"),
                created = o.getString("created"),
                pages   = pages
            ))
        }
        return list
    }
}
