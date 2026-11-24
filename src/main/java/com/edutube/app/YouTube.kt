package com.edutube.app

import android.text.Html
import android.util.Xml
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import org.xmlpull.v1.XmlPullParser
import java.io.IOException
import java.io.StringReader
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLDecoder
import java.net.URLEncoder

class UserError(message: String) : Exception(message)

/** جلب بيانات يوتيوب: بمفتاح API إن وُجد، وإلا عبر خلاصات RSS العامة. */
object YouTube {
    private const val UA =
        "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/126.0 Safari/537.36"

    private val VIDEO_ID = Regex("""(?:v=|youtu\.be/|shorts/|embed/|live/)([A-Za-z0-9_-]{11})""")
    private val BARE_VIDEO_ID = Regex("""^[A-Za-z0-9_-]{11}$""")
    private val LIST_ID = Regex("""list=([A-Za-z0-9_-]+)""")
    private val BARE_LIST_ID = Regex("""^(PL|UU|OL|FL)[A-Za-z0-9_-]{10,}$""")
    private val CHANNEL_ID = Regex("""(UC[A-Za-z0-9_-]{22})""")
    private val HANDLE = Regex("""@([\p{L}\p{N}._\-]+)""")

    fun get(url: String): String {
        val c = URL(url).openConnection() as HttpURLConnection
        c.connectTimeout = 15000
        c.readTimeout = 20000
        c.instanceFollowRedirects = true
        c.setRequestProperty("User-Agent", UA)
        c.setRequestProperty("Accept-Language", "ar,en;q=0.8")
        c.setRequestProperty("Cookie", "CONSENT=YES+cb; SOCS=CAI")
        try {
            val code = c.responseCode
            if (code !in 200..299) throw IOException("HTTP $code")
            return c.inputStream.bufferedReader().use { it.readText() }
        } finally {
            c.disconnect()
        }
    }

    private fun enc(s: String): String = URLEncoder.encode(s, "UTF-8")

    private fun api(path: String, key: String): JSONObject =
        JSONObject(get("https://www.googleapis.com/youtube/v3/$path&key=" + enc(key)))

    private fun unescape(s: String): String = Html.fromHtml(s, Html.FROM_HTML_MODE_LEGACY).toString().trim()

    fun parseVideoId(input: String): String? {
        val s = input.trim()
        VIDEO_ID.find(s)?.let { return it.groupValues[1] }
        return if (BARE_VIDEO_ID.matches(s)) s else null
    }

    fun parsePlaylistId(input: String): String? {
        val s = input.trim()
        LIST_ID.find(s)?.let { return it.groupValues[1] }
        return if (BARE_LIST_ID.matches(s)) s else null
    }

    private fun oembed(url: String): String? = runCatching {
        JSONObject(get("https://www.youtube.com/oembed?format=json&url=" + enc(url))).optString("title")
    }.getOrNull()?.takeIf { it.isNotBlank() }

    // ---------- تحويل ما يكتبه المشرف إلى مصدر ----------

    suspend fun resolve(type: SourceType, input: String, apiKey: String): Source = withContext(Dispatchers.IO) {
        when (type) {
            SourceType.VIDEO -> {
                val id = parseVideoId(input) ?: throw UserError("رابط الفيديو غير صحيح")
                val title = oembed("https://www.youtube.com/watch?v=$id") ?: "فيديو"
                Source(SourceType.VIDEO, id, title)
            }
            SourceType.PLAYLIST -> {
                val id = parsePlaylistId(input) ?: throw UserError("رابط قائمة التشغيل غير صحيح (يجب أن يحتوي على list=)")
                var title: String? = null
                if (apiKey.isNotBlank()) {
                    title = runCatching {
                        api("playlists?part=snippet&id=" + enc(id), apiKey)
                            .getJSONArray("items").getJSONObject(0).getJSONObject("snippet").getString("title")
                    }.getOrNull()
                }
                if (title == null) title = oembed("https://www.youtube.com/playlist?list=$id")
                if (title == null) title = runCatching { parseFeed(get(feedUrl(id))).first }.getOrNull()
                Source(SourceType.PLAYLIST, id, title?.takeIf { it.isNotBlank() } ?: "قائمة تشغيل")
            }
            SourceType.CHANNEL -> resolveChannel(input, apiKey)
        }
    }

    private fun resolveChannel(raw: String, apiKey: String): Source {
        val s = runCatching { URLDecoder.decode(raw.trim(), "UTF-8") }.getOrDefault(raw.trim())

        CHANNEL_ID.find(s)?.let { m ->
            val id = m.groupValues[1]
            return Source(SourceType.CHANNEL, id, channelTitle(id, apiKey) ?: id)
        }

        val handle = HANDLE.find(s)?.groupValues?.get(1)

        if (apiKey.isNotBlank()) {
            runCatching {
                val j = if (handle != null) {
                    api("channels?part=snippet&forHandle=" + enc("@$handle"), apiKey)
                } else if (!s.startsWith("http")) {
                    api("search?part=snippet&type=channel&maxResults=1&q=" + enc(s), apiKey)
                } else null
                val item = j?.optJSONArray("items")?.optJSONObject(0)
                if (item != null) {
                    val sn = item.getJSONObject("snippet")
                    val id = if (handle != null) item.getString("id") else sn.getString("channelId")
                    return Source(SourceType.CHANNEL, id, sn.optString("title", sn.optString("channelTitle", id)))
                }
            }
        }

        val pageUrl = when {
            s.startsWith("http") -> s
            handle != null -> "https://www.youtube.com/@" + enc(handle)
            else -> "https://www.youtube.com/@" + enc(s.replace(" ", ""))
        }
        val html = try {
            get(pageUrl)
        } catch (e: Exception) {
            throw UserError("لم أجد القناة. الصق رابط القناة كاملاً أو اكتب معرّفها مثل @name")
        }
        val id = Regex(""""externalId":"(UC[A-Za-z0-9_-]{22})"""").find(html)?.groupValues?.get(1)
            ?: Regex("""itemprop="identifier" content="(UC[A-Za-z0-9_-]{22})"""").find(html)?.groupValues?.get(1)
            ?: Regex("""/channel/(UC[A-Za-z0-9_-]{22})""").find(html)?.groupValues?.get(1)
            ?: Regex(""""channelId":"(UC[A-Za-z0-9_-]{22})"""").find(html)?.groupValues?.get(1)
            ?: throw UserError("لم أجد القناة. الصق رابط القناة كاملاً أو اكتب معرّفها مثل @name")

        val title = channelTitle(id, apiKey)
            ?: Regex("""<meta property="og:title" content="([^"]*)"""").find(html)?.groupValues?.get(1)?.let { unescape(it) }
            ?: id
        return Source(SourceType.CHANNEL, id, title)
    }

    private fun channelTitle(id: String, apiKey: String): String? {
        if (apiKey.isNotBlank()) {
            runCatching {
                return api("channels?part=snippet&id=" + enc(id), apiKey)
                    .getJSONArray("items").getJSONObject(0).getJSONObject("snippet").getString("title")
            }
        }
        return runCatching {
            parseFeed(get("https://www.youtube.com/feeds/videos.xml?channel_id=$id")).first
        }.getOrNull()?.takeIf { it.isNotBlank() }
    }

    // ---------- جلب قائمة الفيديوهات ----------

    suspend fun fetchVideos(s: Source, apiKey: String, hideShorts: Boolean): List<Video> = withContext(Dispatchers.IO) {
        if (s.type == SourceType.VIDEO) return@withContext listOf(Video(s.id, s.title))

        val candidates = if (s.type == SourceType.CHANNEL) {
            val base = s.id.substring(2)
            // UULF = الفيديوهات العادية فقط (بدون Shorts)، UU = كل الرفعات
            if (hideShorts) listOf("UULF$base", "UU$base") else listOf("UU$base")
        } else listOf(s.id)

        var lastError: Exception? = null
        for (pl in candidates) {
            try {
                val list = if (apiKey.isNotBlank()) apiPlaylist(pl, apiKey) else parseFeed(get(feedUrl(pl))).second
                if (list.isNotEmpty()) return@withContext list
            } catch (e: Exception) {
                lastError = e
            }
        }
        if (s.type == SourceType.CHANNEL && apiKey.isBlank()) {
            try {
                return@withContext parseFeed(get("https://www.youtube.com/feeds/videos.xml?channel_id=${s.id}")).second
            } catch (e: Exception) {
                lastError = e
            }
        }
        lastError?.let { throw it }
        emptyList()
    }

    private fun feedUrl(playlistId: String) = "https://www.youtube.com/feeds/videos.xml?playlist_id=$playlistId"

    private fun apiPlaylist(pl: String, key: String): List<Video> {
        val out = mutableListOf<Video>()
        var token = ""
        repeat(10) {
            val page = if (token.isNotEmpty()) "&pageToken=" + enc(token) else ""
            val j = api("playlistItems?part=snippet&maxResults=50&playlistId=" + enc(pl) + page, key)
            val items = j.optJSONArray("items") ?: JSONArray()
            for (i in 0 until items.length()) {
                val sn = items.optJSONObject(i)?.optJSONObject("snippet") ?: continue
                val id = sn.optJSONObject("resourceId")?.optString("videoId").orEmpty()
                val title = sn.optString("title")
                if (id.isEmpty() || title == "Private video" || title == "Deleted video") continue
                out.add(Video(id, title, sn.optString("publishedAt")))
            }
            token = j.optString("nextPageToken", "")
            if (token.isEmpty()) return out
        }
        return out
    }

    /** يعيد (عنوان الخلاصة، الفيديوهات). */
    fun parseFeed(xml: String): Pair<String, List<Video>> {
        val parser = Xml.newPullParser()
        parser.setFeature(XmlPullParser.FEATURE_PROCESS_NAMESPACES, false)
        parser.setInput(StringReader(xml))
        var feedTitle = ""
        val list = mutableListOf<Video>()
        var inEntry = false
        var vid = ""
        var title = ""
        var pub = ""
        var ev = parser.eventType
        while (ev != XmlPullParser.END_DOCUMENT) {
            if (ev == XmlPullParser.START_TAG) {
                when (parser.name) {
                    "entry" -> {
                        inEntry = true; vid = ""; title = ""; pub = ""
                    }
                    "yt:videoId" -> if (inEntry) vid = parser.nextText()
                    "title" -> {
                        val t = parser.nextText()
                        if (inEntry) title = t else if (feedTitle.isEmpty()) feedTitle = t
                    }
                    "published" -> if (inEntry) pub = parser.nextText()
                }
            } else if (ev == XmlPullParser.END_TAG && parser.name == "entry") {
                inEntry = false
                if (vid.isNotEmpty()) list.add(Video(vid, title, pub))
            }
            ev = parser.next()
        }
        return feedTitle to list
    }
}

object Repo {
    /** يجلب فيديوهات المصدر من الإنترنت ويحفظها. */
    suspend fun refresh(store: Store, s: Source): List<Video> {
        val list = YouTube.fetchVideos(s, store.apiKey, store.hideShorts)
        store.saveVideos(s.key, list)
        return list
    }
}
