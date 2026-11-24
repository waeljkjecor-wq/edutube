package com.edutube.app

import android.app.Activity
import android.app.ActivityManager
import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.net.Uri
import org.json.JSONArray
import org.json.JSONObject
import java.util.Calendar

// ======================= النماذج =======================

data class Exam(
    val id: Long,
    val subject: String,
    val type: String,
    val at: Long,
    val reminders: Set<String>
)

/** حصة في الجدول. start = دقائق من منتصف الليل. */
data class Period(
    val subject: String,
    val teacher: String,
    val start: Int,
    val minutes: Int,
    val sourceKey: String
)

data class WaLink(val name: String, val link: String)

data class TgChat(val id: Long, val title: String)

val REMINDER_OPTIONS = listOf(
    "w" to "قبل أسبوع",
    "d2" to "قبل يومين",
    "d1" to "قبل يوم",
    "m" to "صباح يوم الامتحان"
)

/** أيام الدوام: الأحد إلى الخميس. */
val SCHOOL_DAYS = listOf("الأحد", "الإثنين", "الثلاثاء", "الأربعاء", "الخميس")

private val WEEK_NAMES = listOf("الأحد", "الإثنين", "الثلاثاء", "الأربعاء", "الخميس", "الجمعة", "السبت")
private val MONTHS = listOf(
    "كانون الثاني", "شباط", "آذار", "نيسان", "أيار", "حزيران",
    "تموز", "آب", "أيلول", "تشرين الأول", "تشرين الثاني", "كانون الأول"
)

const val DAY_MS = 24L * 60 * 60 * 1000

// ======================= تنسيق =======================

fun fmtTime(minutesOfDay: Int): String {
    val h = minutesOfDay / 60
    val m = minutesOfDay % 60
    val h12 = if (h % 12 == 0) 12 else h % 12
    val suffix = if (h < 12) "ص" else "م"
    return "$h12:" + m.toString().padStart(2, '0') + " " + suffix
}

fun fmtExamDate(at: Long): String {
    val c = Calendar.getInstance().apply { timeInMillis = at }
    val day = WEEK_NAMES[c.get(Calendar.DAY_OF_WEEK) - 1]
    val mins = c.get(Calendar.HOUR_OF_DAY) * 60 + c.get(Calendar.MINUTE)
    return "$day ${c.get(Calendar.DAY_OF_MONTH)}/${c.get(Calendar.MONTH) + 1} · ${fmtTime(mins)}"
}

fun monthName(at: Long): String {
    val c = Calendar.getInstance().apply { timeInMillis = at }
    return MONTHS[c.get(Calendar.MONTH)]
}

fun dayOfMonth(at: Long): Int = Calendar.getInstance().apply { timeInMillis = at }.get(Calendar.DAY_OF_MONTH)

/** عدد الأيام المتبقية حسب التقويم (اليوم = 0). */
fun daysUntil(at: Long): Int {
    fun startOfDay(t: Long) = Calendar.getInstance().apply {
        timeInMillis = t
        set(Calendar.HOUR_OF_DAY, 0); set(Calendar.MINUTE, 0); set(Calendar.SECOND, 0); set(Calendar.MILLISECOND, 0)
    }.timeInMillis
    return ((startOfDay(at) - startOfDay(System.currentTimeMillis())) / DAY_MS).toInt()
}

fun daysLeftText(d: Int): String = when {
    d < 0 -> "انتهى"
    d == 0 -> "اليوم"
    d == 1 -> "غداً"
    d == 2 -> "بعد يومين"
    d in 3..10 -> "بعد $d أيام"
    else -> "بعد $d يوماً"
}

/** فهرس يوم الدوام الحالي (0 = الأحد)، والجمعة والسبت يعرضان الأحد. */
fun todaySchoolIndex(): Int {
    val dow = Calendar.getInstance().get(Calendar.DAY_OF_WEEK) // الأحد = 1
    val i = dow - 1
    return if (i in 0..4) i else 0
}

fun nowMinutes(): Int {
    val c = Calendar.getInstance()
    return c.get(Calendar.HOUR_OF_DAY) * 60 + c.get(Calendar.MINUTE)
}

fun isTodaySchoolDay(): Boolean = (Calendar.getInstance().get(Calendar.DAY_OF_WEEK) - 1) in 0..4

// ======================= التخزين =======================

private fun Store.arr(key: String): JSONArray =
    runCatching { JSONArray(raw(key) ?: "[]") }.getOrElse { JSONArray() }

fun Store.exams(): List<Exam> {
    val a = arr("exams")
    val out = mutableListOf<Exam>()
    for (i in 0 until a.length()) {
        val o = a.optJSONObject(i) ?: continue
        val rem = mutableSetOf<String>()
        val r = o.optJSONArray("rem") ?: JSONArray()
        for (j in 0 until r.length()) rem.add(r.optString(j))
        out.add(Exam(o.optLong("id"), o.optString("subject"), o.optString("type"), o.optLong("at"), rem))
    }
    return out.sortedBy { it.at }
}

fun Store.saveExams(list: List<Exam>) {
    val a = JSONArray()
    list.forEach { e ->
        a.put(
            JSONObject().put("id", e.id).put("subject", e.subject).put("type", e.type).put("at", e.at)
                .put("rem", JSONArray(e.reminders.toList()))
        )
    }
    putRaw("exams", a.toString())
}

/** يحذف الامتحانات التي مضى عليها أكثر من يوم. */
fun Store.upcomingExams(): List<Exam> {
    val all = exams()
    val keep = all.filter { it.at > System.currentTimeMillis() - DAY_MS }
    if (keep.size != all.size) saveExams(keep)
    return keep
}

fun Store.schedule(day: Int): List<Period> {
    val a = arr("schedule_$day")
    val out = mutableListOf<Period>()
    for (i in 0 until a.length()) {
        val o = a.optJSONObject(i) ?: continue
        out.add(
            Period(
                o.optString("subject"), o.optString("teacher"), o.optInt("start"),
                o.optInt("minutes", 45), o.optString("source")
            )
        )
    }
    return out.sortedBy { it.start }
}

fun Store.saveSchedule(day: Int, list: List<Period>) {
    val a = JSONArray()
    list.sortedBy { it.start }.forEach { p ->
        a.put(
            JSONObject().put("subject", p.subject).put("teacher", p.teacher).put("start", p.start)
                .put("minutes", p.minutes).put("source", p.sourceKey)
        )
    }
    putRaw("schedule_$day", a.toString())
}

/** أسماء المواد من الجدول (لاقتراحها عند إضافة امتحان). */
fun Store.subjects(): List<String> {
    val set = linkedSetOf<String>()
    for (d in 0..4) schedule(d).forEach { if (it.subject.isNotBlank()) set.add(it.subject.trim()) }
    return set.toList()
}

fun Store.waLinks(): List<WaLink> {
    val a = arr("wa_links")
    val out = mutableListOf<WaLink>()
    for (i in 0 until a.length()) {
        val o = a.optJSONObject(i) ?: continue
        out.add(WaLink(o.optString("name"), o.optString("link")))
    }
    return out
}

fun Store.saveWaLinks(list: List<WaLink>) {
    val a = JSONArray()
    list.forEach { a.put(JSONObject().put("name", it.name).put("link", it.link)) }
    putRaw("wa_links", a.toString())
}

fun Store.tgChats(): List<TgChat> {
    val a = arr("tg_chats")
    val out = mutableListOf<TgChat>()
    for (i in 0 until a.length()) {
        val o = a.optJSONObject(i) ?: continue
        out.add(TgChat(o.optLong("id"), o.optString("title")))
    }
    return out
}

fun Store.saveTgChats(list: List<TgChat>) {
    val a = JSONArray()
    list.forEach { a.put(JSONObject().put("id", it.id).put("title", it.title)) }
    putRaw("tg_chats", a.toString())
}

var Store.tgApiId: Int
    get() = raw("tg_api_id")?.toIntOrNull() ?: 0
    set(value) {
        putRaw("tg_api_id", value.toString())
    }

var Store.tgApiHash: String
    get() = raw("tg_api_hash") ?: ""
    set(value) {
        putRaw("tg_api_hash", value.trim())
    }

// ======================= واتساب =======================

/** يفتح مجموعة واتساب مباشرة. يوقف تثبيت الشاشة مؤقتاً لأن أندرويد يمنع فتح تطبيق آخر أثناءه. */
fun openWhatsApp(activity: Activity, link: String) {
    val url = if (link.startsWith("http")) link else "https://$link"
    try {
        val am = activity.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
        if (am.lockTaskModeState != ActivityManager.LOCK_TASK_MODE_NONE) activity.stopLockTask()
    } catch (_: Exception) {
    }
    try {
        activity.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)))
    } catch (_: ActivityNotFoundException) {
        activity.toast("واتساب غير مثبّت على هذا الهاتف")
    }
}
