package com.edutube.app

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

enum class SourceType(val label: String) {
    CHANNEL("قناة"),
    PLAYLIST("قائمة تشغيل"),
    VIDEO("فيديو")
}

data class Source(val type: SourceType, val id: String, val title: String, val name: String = "") {
    val key: String get() = "${type.name}:$id"

    /** الاسم الظاهر للطالب: الاسم المخصص إن وُجد، وإلا عنوان يوتيوب. */
    val display: String get() = name.ifBlank { title }
}

data class Video(
    val id: String,
    val title: String,
    val published: String = ""
) {
    val thumb: String get() = "https://i.ytimg.com/vi/$id/mqdefault.jpg"
}

data class WatchStat(val id: String, val title: String, val count: Int)

/** كل بيانات التطبيق محفوظة على الجهاز فقط. */
class Store(context: Context) {
    private val p = context.applicationContext.getSharedPreferences("edutube", Context.MODE_PRIVATE)

    /** قراءة/كتابة نص خام (تستخدمها الأقسام الإضافية في Data.kt). */
    fun raw(key: String): String? = p.getString(key, null)

    fun putRaw(key: String, value: String?) {
        p.edit().putString(key, value).apply()
    }

    var pin: String?
        get() = p.getString("pin", null)
        set(v) {
            p.edit().putString("pin", v).apply()
        }

    var apiKey: String
        get() = p.getString("api_key", "") ?: ""
        set(v) {
            p.edit().putString("api_key", v.trim()).apply()
        }

    var lockScreen: Boolean
        get() = p.getBoolean("lock_screen", false)
        set(v) {
            p.edit().putBoolean("lock_screen", v).apply()
        }

    var pinOnExit: Boolean
        get() = p.getBoolean("pin_on_exit", true)
        set(v) {
            p.edit().putBoolean("pin_on_exit", v).apply()
        }

    var hideShorts: Boolean
        get() = p.getBoolean("hide_shorts", true)
        set(v) {
            p.edit().putBoolean("hide_shorts", v).apply()
        }

    // ---------- المصادر المسموحة ----------

    fun sources(): List<Source> {
        val arr = runCatching { JSONArray(p.getString("sources", "[]")) }.getOrElse { JSONArray() }
        val out = mutableListOf<Source>()
        for (i in 0 until arr.length()) {
            val o = arr.optJSONObject(i) ?: continue
            val type = runCatching { SourceType.valueOf(o.getString("type")) }.getOrNull() ?: continue
            val id = o.optString("id")
            if (id.isEmpty()) continue
            out.add(Source(type, id, o.optString("title", id), o.optString("name")))
        }
        return out
    }

    fun source(key: String): Source? = sources().firstOrNull { it.key == key }

    private fun saveSources(list: List<Source>) {
        val arr = JSONArray()
        list.forEach {
            arr.put(JSONObject().put("type", it.type.name).put("id", it.id).put("title", it.title).put("name", it.name))
        }
        p.edit().putString("sources", arr.toString()).apply()
    }

    fun addSource(s: Source) {
        saveSources(sources().filter { it.key != s.key } + s)
    }

    /** تغيير الاسم الظاهر (فارغ = العودة لعنوان يوتيوب). */
    fun renameSource(key: String, name: String) {
        saveSources(sources().map { if (it.key == key) it.copy(name = name.trim()) else it })
    }

    fun removeSource(key: String) {
        saveSources(sources().filter { it.key != key })
        p.edit().remove("videos_$key").remove("videos_time_$key").apply()
    }

    // ---------- ذاكرة الفيديوهات ----------

    // ---------- أسماء مخصصة للدروس ----------

    private fun videoNamesJson(): JSONObject =
        runCatching { JSONObject(p.getString("video_names", "{}") ?: "{}") }.getOrElse { JSONObject() }

    fun videoName(id: String): String? = videoNamesJson().optString(id, "").ifBlank { null }

    /** فارغ = العودة لعنوان يوتيوب. */
    fun setVideoName(id: String, name: String) {
        val o = videoNamesJson()
        if (name.isBlank()) o.remove(id) else o.put(id, name.trim())
        p.edit().putString("video_names", o.toString()).apply()
    }

    /** يطبّق الأسماء المخصصة على قائمة دروس. */
    fun named(list: List<Video>): List<Video> {
        val o = videoNamesJson()
        if (o.length() == 0) return list
        return list.map { v -> o.optString(v.id, "").ifBlank { null }?.let { v.copy(title = it) } ?: v }
    }

    fun cachedVideos(key: String): List<Video> = named(rawCachedVideos(key))

    private fun rawCachedVideos(key: String): List<Video> {
        val arr = runCatching { JSONArray(p.getString("videos_$key", "[]")) }.getOrElse { JSONArray() }
        val out = mutableListOf<Video>()
        for (i in 0 until arr.length()) {
            val o = arr.optJSONObject(i) ?: continue
            out.add(Video(o.optString("id"), o.optString("title"), o.optString("published")))
        }
        return out
    }

    fun saveVideos(key: String, list: List<Video>) {
        val arr = JSONArray()
        list.forEach { arr.put(JSONObject().put("id", it.id).put("title", it.title).put("published", it.published)) }
        p.edit()
            .putString("videos_$key", arr.toString())
            .putLong("videos_time_$key", System.currentTimeMillis())
            .apply()
    }

    fun cacheAgeMs(key: String): Long = System.currentTimeMillis() - p.getLong("videos_time_$key", 0L)

    fun clearVideoCaches() {
        val e = p.edit()
        p.all.keys.filter { it.startsWith("videos_") }.forEach { e.remove(it) }
        e.apply()
    }

    /** فيديوهات مصدر معيّن: الفيديو المفرد يعيد نفسه. */
    fun videosOf(s: Source): List<Video> =
        if (s.type == SourceType.VIDEO) listOf(Video(s.id, s.display)) else cachedVideos(s.key)

    // ---------- عدّاد المشاهدات عبر التطبيق ----------

    private fun watchJson(): JSONObject =
        runCatching { JSONObject(p.getString("watch", "{}") ?: "{}") }.getOrElse { JSONObject() }

    fun watchCounts(): Map<String, Int> {
        val c = watchJson()
        val out = HashMap<String, Int>()
        c.keys().forEach { k -> out[k] = c.optJSONObject(k)?.optInt("n", 0) ?: 0 }
        return out
    }

    fun watchCount(id: String): Int = watchJson().optJSONObject(id)?.optInt("n", 0) ?: 0

    fun incrementWatch(v: Video): Int {
        val c = watchJson()
        val o = c.optJSONObject(v.id) ?: JSONObject()
        val n = o.optInt("n", 0) + 1
        o.put("n", n).put("t", v.title).put("last", System.currentTimeMillis())
        c.put(v.id, o)
        p.edit().putString("watch", c.toString()).apply()
        return n
    }

    fun watchStats(): List<WatchStat> {
        val c = watchJson()
        val out = mutableListOf<WatchStat>()
        c.keys().forEach { k ->
            val o = c.optJSONObject(k) ?: return@forEach
            out.add(WatchStat(k, o.optString("t", k), o.optInt("n", 0)))
        }
        return out.sortedByDescending { it.count }
    }

    fun resetWatch() {
        p.edit().remove("watch").apply()
    }

    // ---------- آخر فيديو ----------

    fun setLast(v: Video, sourceKey: String) {
        p.edit().putString(
            "last",
            JSONObject().put("id", v.id).put("title", v.title).put("key", sourceKey).toString()
        ).apply()
    }

    fun last(): Pair<Video, String>? {
        val s = p.getString("last", null) ?: return null
        return runCatching {
            val o = JSONObject(s)
            val id = o.getString("id")
            Video(id, videoName(id) ?: o.optString("title")) to o.getString("key")
        }.getOrNull()
    }
}

/** نص عدد المشاهدات بالعربية الصحيحة. */
fun viewsText(n: Int): String = when {
    n <= 0 -> "لم يُشاهَد بعد"
    n == 1 -> "شوهد مرة واحدة"
    n == 2 -> "شوهد مرتين"
    n in 3..10 -> "شوهد $n مرات"
    else -> "شوهد $n مرة"
}
