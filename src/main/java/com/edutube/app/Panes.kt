package com.edutube.app

import android.content.Intent
import android.content.res.ColorStateList
import android.graphics.Color
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.bottomsheet.BottomSheetDialog
import com.google.android.material.button.MaterialButton
import com.google.android.material.button.MaterialButtonToggleGroup
import com.google.android.material.chip.Chip
import com.google.android.material.chip.ChipGroup
import com.google.android.material.datepicker.MaterialDatePicker
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.textfield.MaterialAutoCompleteTextView
import com.google.android.material.textfield.TextInputEditText
import com.google.android.material.timepicker.MaterialTimePicker
import com.google.android.material.timepicker.TimeFormat
import kotlinx.coroutines.launch
import java.util.Calendar
import java.util.TimeZone

private val SUBJECT_COLORS = listOf(
    "#3B4CCA" to "#E6E9FB", "#14633F" to "#E3F4EC", "#B45309" to "#FDEFD9",
    "#8E1C47" to "#FBE4EC", "#0B5588" to "#E4F1FB", "#5B2A9A" to "#EFE7FB"
)

/** لون ثابت لكل مادة حسب اسمها. */
fun subjectColors(subject: String): Pair<Int, Int> {
    val (fg, bg) = SUBJECT_COLORS[Math.floorMod(subject.trim().hashCode(), SUBJECT_COLORS.size)]
    return Color.parseColor(fg) to Color.parseColor(bg)
}

// ============================ الامتحانات ============================

class ExamsPane(private val activity: AppCompatActivity, root: View, private val askNotify: () -> Unit) {
    private val store = Store(activity)
    private val card: View = root.findViewById(R.id.nextExamCard)
    private val nextSubject: TextView = root.findViewById(R.id.nextSubject)
    private val nextMeta: TextView = root.findViewById(R.id.nextMeta)
    private val nextDays: TextView = root.findViewById(R.id.nextDays)
    private val nextDaysLabel: TextView = root.findViewById(R.id.nextDaysLabel)
    private val nextReminders: TextView = root.findViewById(R.id.nextReminders)
    private val empty: View = root.findViewById(R.id.examsEmpty)
    private val upcomingTitle: View = root.findViewById(R.id.upcomingTitle)
    private val adapter = ExamAdapter { confirmDelete(it) }

    init {
        root.findViewById<RecyclerView>(R.id.examsList).apply {
            layoutManager = LinearLayoutManager(activity)
            adapter = this@ExamsPane.adapter
            isNestedScrollingEnabled = false
        }
        root.findViewById<View>(R.id.fabAddExam).setOnClickListener { showAdd() }
    }

    fun refresh() {
        val exams = store.upcomingExams()
        empty.visibility = if (exams.isEmpty()) View.VISIBLE else View.GONE
        val next = exams.firstOrNull()
        if (next == null) {
            card.visibility = View.GONE
        } else {
            card.visibility = View.VISIBLE
            nextSubject.text = next.subject
            nextMeta.text = (if (next.type.isBlank()) "" else next.type + " · ") + fmtExamDate(next.at)
            val d = daysUntil(next.at)
            when {
                d <= 0 -> { nextDays.text = "اليوم"; nextDaysLabel.text = "" }
                d == 1 -> { nextDays.text = "غداً"; nextDaysLabel.text = "" }
                else -> { nextDays.text = d.toString(); nextDaysLabel.text = if (d <= 10) "أيام" else "يوماً" }
            }
            val rem = REMINDER_OPTIONS.filter { it.first in next.reminders }.joinToString(" · ") { it.second }
            nextReminders.text = if (rem.isEmpty()) "بدون تذكير" else rem
        }
        val rest = exams.drop(1)
        upcomingTitle.visibility = if (rest.isEmpty()) View.GONE else View.VISIBLE
        adapter.submit(rest)
    }

    private fun confirmDelete(e: Exam) {
        MaterialAlertDialogBuilder(activity)
            .setTitle("حذف امتحان ${e.subject}؟")
            .setPositiveButton("حذف") { _, _ ->
                store.saveExams(store.exams().filter { it.id != e.id })
                Reminders.scheduleAll(activity)
                refresh()
            }
            .setNegativeButton("إلغاء", null)
            .show()
    }

    private fun showAdd() {
        val dialog = BottomSheetDialog(activity)
        val v = LayoutInflater.from(activity).inflate(R.layout.dialog_exam, null)
        dialog.setContentView(v)

        val subject = v.findViewById<MaterialAutoCompleteTextView>(R.id.exSubject)
        val type = v.findViewById<TextInputEditText>(R.id.exType)
        val btnDate = v.findViewById<MaterialButton>(R.id.exDate)
        val btnTime = v.findViewById<MaterialButton>(R.id.exTime)
        val chips = v.findViewById<ChipGroup>(R.id.exReminders)

        val suggestions = (store.subjects() + listOf("الرياضيات", "العلوم", "اللغة العربية", "الإنكليزي", "الإسلامية", "الاجتماعيات")).distinct()
        subject.setSimpleItems(suggestions.toTypedArray())

        val cal = Calendar.getInstance().apply {
            add(Calendar.DAY_OF_YEAR, 1)
            set(Calendar.HOUR_OF_DAY, 8); set(Calendar.MINUTE, 0); set(Calendar.SECOND, 0); set(Calendar.MILLISECOND, 0)
        }
        fun updateButtons() {
            btnDate.text = "${cal.get(Calendar.DAY_OF_MONTH)}/${cal.get(Calendar.MONTH) + 1}/${cal.get(Calendar.YEAR)}"
            btnTime.text = fmtTime(cal.get(Calendar.HOUR_OF_DAY) * 60 + cal.get(Calendar.MINUTE))
        }
        updateButtons()

        btnDate.setOnClickListener {
            val utc = Calendar.getInstance(TimeZone.getTimeZone("UTC")).apply {
                clear()
                set(cal.get(Calendar.YEAR), cal.get(Calendar.MONTH), cal.get(Calendar.DAY_OF_MONTH))
            }
            val picker = MaterialDatePicker.Builder.datePicker()
                .setTitleText("تاريخ الامتحان")
                .setSelection(utc.timeInMillis)
                .build()
            picker.addOnPositiveButtonClickListener { sel ->
                val u = Calendar.getInstance(TimeZone.getTimeZone("UTC")).apply { timeInMillis = sel }
                cal.set(u.get(Calendar.YEAR), u.get(Calendar.MONTH), u.get(Calendar.DAY_OF_MONTH))
                updateButtons()
            }
            picker.show(activity.supportFragmentManager, "date")
        }
        btnTime.setOnClickListener {
            val picker = MaterialTimePicker.Builder()
                .setTimeFormat(TimeFormat.CLOCK_12H)
                .setHour(cal.get(Calendar.HOUR_OF_DAY))
                .setMinute(cal.get(Calendar.MINUTE))
                .setTitleText("وقت الامتحان")
                .build()
            picker.addOnPositiveButtonClickListener {
                cal.set(Calendar.HOUR_OF_DAY, picker.hour)
                cal.set(Calendar.MINUTE, picker.minute)
                updateButtons()
            }
            picker.show(activity.supportFragmentManager, "time")
        }

        val defaults = setOf("d2", "d1", "m")
        REMINDER_OPTIONS.forEach { (code, label) ->
            chips.addView(Chip(activity).apply {
                text = label
                tag = code
                isCheckable = true
                isChecked = code in defaults
            })
        }

        v.findViewById<View>(R.id.exSave).setOnClickListener {
            val subj = subject.text?.toString()?.trim().orEmpty()
            if (subj.isEmpty()) {
                activity.toast("اكتب اسم المادة")
                return@setOnClickListener
            }
            if (cal.timeInMillis < System.currentTimeMillis()) {
                activity.toast("الموعد في الماضي، اختر تاريخاً قادماً")
                return@setOnClickListener
            }
            val rem = mutableSetOf<String>()
            for (i in 0 until chips.childCount) {
                val c = chips.getChildAt(i) as Chip
                if (c.isChecked) rem.add(c.tag as String)
            }
            val exam = Exam(System.currentTimeMillis(), subj, type.text?.toString()?.trim().orEmpty(), cal.timeInMillis, rem)
            store.saveExams(store.exams() + exam)
            Reminders.scheduleAll(activity)
            dialog.dismiss()
            refresh()
            if (rem.isNotEmpty()) askNotify()
            activity.toast("تم الحفظ، سيصلك التذكير في موعده")
        }
        dialog.show()
    }
}

class ExamAdapter(private val onLongPress: (Exam) -> Unit) : RecyclerView.Adapter<ExamAdapter.VH>() {
    private var items: List<Exam> = emptyList()

    class VH(v: View) : RecyclerView.ViewHolder(v) {
        val box: View = v.findViewById(R.id.dateBox)
        val day: TextView = v.findViewById(R.id.examDay)
        val month: TextView = v.findViewById(R.id.examMonth)
        val subject: TextView = v.findViewById(R.id.examSubject)
        val meta: TextView = v.findViewById(R.id.examMeta)
        val left: TextView = v.findViewById(R.id.examLeft)
    }

    fun submit(list: List<Exam>) {
        items = list
        notifyDataSetChanged()
    }

    override fun getItemCount() = items.size

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int) =
        VH(LayoutInflater.from(parent.context).inflate(R.layout.item_exam, parent, false))

    override fun onBindViewHolder(h: VH, position: Int) {
        val e = items[position]
        val (fg, bg) = subjectColors(e.subject)
        h.box.backgroundTintList = ColorStateList.valueOf(bg)
        h.day.setTextColor(fg)
        h.month.setTextColor(fg)
        h.day.text = dayOfMonth(e.at).toString()
        h.month.text = monthName(e.at)
        h.subject.text = e.subject
        h.meta.text = (if (e.type.isBlank()) "" else e.type + " · ") + fmtExamDate(e.at)
        h.left.text = daysLeftText(daysUntil(e.at))
        h.itemView.setOnLongClickListener { onLongPress(e); true }
    }
}

// ============================ الجدول ============================

/** يبني أزرار أيام الأسبوع داخل مجموعة تبديل. */
fun buildDayButtons(group: MaterialButtonToggleGroup, onPick: (Int) -> Unit) {
    val ctx = group.context
    SCHOOL_DAYS.forEachIndexed { i, name ->
        val b = MaterialButton(ctx, null, com.google.android.material.R.attr.materialButtonOutlinedStyle).apply {
            id = View.generateViewId()
            text = name
            textSize = 11.5f
            setPadding(0, 0, 0, 0)
            insetTop = 0
            insetBottom = 0
            minWidth = 0
            minimumWidth = 0
            tag = i
        }
        group.addView(b, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
    }
    group.addOnButtonCheckedListener { g, checkedId, isChecked ->
        if (isChecked) onPick(g.findViewById<View>(checkedId).tag as Int)
    }
}

fun selectDay(group: MaterialButtonToggleGroup, day: Int) {
    group.check(group.getChildAt(day).id)
}

class PeriodAdapter(
    private val showPlay: Boolean,
    private val onClick: (Int, Period) -> Unit,
    private val onLongPress: (Int, Period) -> Unit
) : RecyclerView.Adapter<PeriodAdapter.VH>() {
    private var items: List<Period> = emptyList()
    private var nowIndex = -1

    class VH(v: View) : RecyclerView.ViewHolder(v) {
        val time: TextView = v.findViewById(R.id.pTime)
        val num: TextView = v.findViewById(R.id.pNum)
        val card: View = v.findViewById(R.id.pCard)
        val color: View = v.findViewById(R.id.pColor)
        val subject: TextView = v.findViewById(R.id.pSubject)
        val teacher: TextView = v.findViewById(R.id.pTeacher)
        val now: View = v.findViewById(R.id.pNow)
        val play: View = v.findViewById(R.id.pPlay)
    }

    fun submit(list: List<Period>, nowIndex: Int) {
        items = list
        this.nowIndex = nowIndex
        notifyDataSetChanged()
    }

    override fun getItemCount() = items.size

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int) =
        VH(LayoutInflater.from(parent.context).inflate(R.layout.item_period, parent, false))

    override fun onBindViewHolder(h: VH, position: Int) {
        val p = items[position]
        h.time.text = fmtTime(p.start)
        h.num.text = "الحصة ${position + 1}"
        h.subject.text = p.subject
        h.teacher.text = if (p.teacher.isBlank()) "${p.minutes} دقيقة" else "${p.teacher} · ${p.minutes} دقيقة"
        h.color.backgroundTintList = ColorStateList.valueOf(subjectColors(p.subject).first)
        val isNow = position == nowIndex
        h.now.visibility = if (isNow) View.VISIBLE else View.GONE
        h.card.setBackgroundResource(if (isNow) R.drawable.bg_period_now else R.drawable.bg_period)
        h.play.visibility = if (showPlay && p.sourceKey.isNotEmpty()) View.VISIBLE else View.GONE
        h.play.setOnClickListener { onClick(position, p) }
        h.card.setOnClickListener { onClick(position, p) }
        h.card.setOnLongClickListener { onLongPress(position, p); true }
    }
}

class SchedulePane(private val activity: AppCompatActivity, root: View) {
    private val store = Store(activity)
    private val group: MaterialButtonToggleGroup = root.findViewById(R.id.dayGroup)
    private val empty: View = root.findViewById(R.id.scheduleEmpty)
    private var day = todaySchoolIndex()
    private val adapter = PeriodAdapter(
        showPlay = true,
        onClick = { _, p -> openPeriod(p) },
        onLongPress = { _, _ -> }
    )

    init {
        root.findViewById<RecyclerView>(R.id.periodsList).apply {
            layoutManager = LinearLayoutManager(activity)
            adapter = this@SchedulePane.adapter
        }
        buildDayButtons(group) { d ->
            day = d
            refresh()
        }
        selectDay(group, day)
        root.findViewById<View>(R.id.btnEditSchedule).setOnClickListener {
            Pin.requireAdmin(activity, store) {
                activity.startActivity(Intent(activity, ScheduleEditActivity::class.java).putExtra("day", day))
            }
        }
    }

    private fun openPeriod(p: Period) {
        if (p.sourceKey.isEmpty()) return
        val s = store.source(p.sourceKey)
        if (s == null) activity.toast("الدروس المرتبطة بهذه المادة لم تعد موجودة") else openSource(activity, s)
    }

    fun refresh() {
        val list = store.schedule(day)
        var nowIdx = -1
        if (isTodaySchoolDay() && day == todaySchoolIndex()) {
            val n = nowMinutes()
            nowIdx = list.indexOfFirst { n >= it.start && n < it.start + it.minutes }
        }
        adapter.submit(list, nowIdx)
        empty.visibility = if (list.isEmpty()) View.VISIBLE else View.GONE
    }
}

// ============================ تيليجرام + واتساب ============================

class TgChatAdapter(private val onClick: (TgChatInfo) -> Unit) : RecyclerView.Adapter<TgChatAdapter.VH>() {
    private var items: List<TgChatInfo> = emptyList()

    class VH(v: View) : RecyclerView.ViewHolder(v) {
        val letter: TextView = v.findViewById(R.id.letter)
        val name: TextView = v.findViewById(R.id.name)
        val time: TextView = v.findViewById(R.id.time)
        val last: TextView = v.findViewById(R.id.last)
        val unread: TextView = v.findViewById(R.id.unread)
    }

    fun submit(list: List<TgChatInfo>) {
        items = list
        notifyDataSetChanged()
    }

    override fun getItemCount() = items.size

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int) =
        VH(LayoutInflater.from(parent.context).inflate(R.layout.item_tg_chat, parent, false))

    override fun onBindViewHolder(h: VH, position: Int) {
        val c = items[position]
        val (fg, bg) = subjectColors(c.title)
        h.letter.text = c.title.trim().take(1)
        h.letter.backgroundTintList = ColorStateList.valueOf(bg)
        h.letter.setTextColor(fg)
        h.name.text = c.title
        h.last.text = if (c.last.isEmpty()) c.kind else c.last
        h.time.text = if (c.lastDate > 0) shortDate(c.lastDate) else ""
        h.unread.visibility = if (c.unread > 0) View.VISIBLE else View.GONE
        h.unread.text = if (c.unread > 99) "99+" else c.unread.toString()
        h.itemView.setOnClickListener { onClick(c) }
    }
}

/** تاريخ مختصر لرسالة تيليجرام (ثوانٍ منذ 1970). */
fun shortDate(unixSeconds: Int): String {
    val t = unixSeconds * 1000L
    val c = Calendar.getInstance().apply { timeInMillis = t }
    val mins = c.get(Calendar.HOUR_OF_DAY) * 60 + c.get(Calendar.MINUTE)
    return when (val d = -daysUntil(t)) {
        0 -> fmtTime(mins)
        1 -> "أمس"
        in 2..6 -> "قبل $d أيام"
        else -> "${c.get(Calendar.DAY_OF_MONTH)}/${c.get(Calendar.MONTH) + 1}"
    }
}

class TelegramPane(private val activity: AppCompatActivity, root: View) {
    private val store = Store(activity)
    private val hint: TextView = root.findViewById(R.id.tgHint)
    private val listBox: View = root.findViewById(R.id.tgListBox)
    private val tgTitle: View = root.findViewById(R.id.tgTitle)
    private val waTitle: View = root.findViewById(R.id.waTitle)
    private val waBox: LinearLayout = root.findViewById(R.id.waBox)
    private val adapter = TgChatAdapter { c ->
        activity.startActivity(
            Intent(activity, TgChatActivity::class.java).putExtra("chat_id", c.id).putExtra("title", c.title)
        )
    }

    init {
        root.findViewById<RecyclerView>(R.id.tgList).apply {
            layoutManager = LinearLayoutManager(activity)
            adapter = this@TelegramPane.adapter
            isNestedScrollingEnabled = false
        }
    }

    fun refresh() {
        val chats = store.tgChats()
        // اعرض الأسماء المحفوظة فوراً ثم حدّثها من تيليجرام
        adapter.submit(chats.map { TgChatInfo(it.id, it.title, "مجموعة", "", 0, 0) })
        tgTitle.visibility = if (chats.isEmpty() && store.waLinks().isNotEmpty()) View.GONE else View.VISIBLE
        listBox.visibility = if (chats.isEmpty()) View.GONE else View.VISIBLE

        when {
            !Tg.isConfigured(activity) || chats.isEmpty() -> {
                hint.visibility = if (store.waLinks().isEmpty() || chats.isNotEmpty()) View.VISIBLE else View.GONE
                hint.text = "لم تُضف مجموعات تيليجرام بعد.\nيضيفها المشرف من لوحة المشرف ← تيليجرام."
            }
            Tg.isReady -> {
                hint.visibility = View.GONE
                activity.lifecycleScope.launch {
                    val infos = chats.map { c -> runCatching { Tg.chatInfo(c.id) }.getOrNull() ?: TgChatInfo(c.id, c.title, "مجموعة", "", 0, 0) }
                    adapter.submit(infos)
                }
            }
            else -> {
                hint.visibility = View.VISIBLE
                hint.text = "جارٍ الاتصال بتيليجرام…"
            }
        }

        waBox.removeAllViews()
        val links = store.waLinks()
        waTitle.visibility = if (links.isEmpty()) View.GONE else View.VISIBLE
        links.forEach { w ->
            val v = LayoutInflater.from(activity).inflate(R.layout.item_wa, waBox, false)
            v.findViewById<TextView>(R.id.waName).text = w.name
            v.setOnClickListener { openWhatsApp(activity, w.link) }
            waBox.addView(v)
        }
    }
}
