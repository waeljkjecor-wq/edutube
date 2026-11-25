package com.edutube.app

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.widget.ArrayAdapter
import android.widget.Spinner
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.appbar.MaterialToolbar
import com.google.android.material.bottomsheet.BottomSheetDialog
import com.google.android.material.button.MaterialButton
import com.google.android.material.button.MaterialButtonToggleGroup
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.textfield.TextInputEditText
import com.google.android.material.timepicker.MaterialTimePicker
import com.google.android.material.timepicker.TimeFormat

/** تعديل الجدول الأسبوعي (للمشرف فقط). */
class ScheduleEditActivity : AppCompatActivity() {
    private lateinit var store: Store
    private lateinit var adapter: PeriodAdapter
    private lateinit var empty: View
    private var day = 0

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_schedule_edit)
        store = Store(this)
        day = intent.getIntExtra("day", todaySchoolIndex()).coerceIn(0, 4)

        findViewById<MaterialToolbar>(R.id.toolbar).setNavigationOnClickListener { finish() }
        empty = findViewById(R.id.scheduleEmpty)

        adapter = PeriodAdapter(
            showPlay = false,
            onClick = { i, p -> showEditor(i, p) },
            onLongPress = { i, p -> confirmDelete(i, p) }
        )
        findViewById<RecyclerView>(R.id.periodsList).apply {
            layoutManager = LinearLayoutManager(this@ScheduleEditActivity)
            adapter = this@ScheduleEditActivity.adapter
        }

        val group = findViewById<MaterialButtonToggleGroup>(R.id.dayGroup)
        buildDayButtons(group) { d ->
            day = d
            refresh()
        }
        selectDay(group, day)

        findViewById<View>(R.id.fabAdd).setOnClickListener { showEditor(-1, null) }
        refresh()
    }

    private fun refresh() {
        val list = store.schedule(day)
        adapter.submit(list, -1)
        empty.visibility = if (list.isEmpty()) View.VISIBLE else View.GONE
    }

    private fun confirmDelete(index: Int, p: Period) {
        MaterialAlertDialogBuilder(this)
            .setTitle("حذف حصة ${p.subject}؟")
            .setPositiveButton("حذف") { _, _ ->
                store.saveSchedule(day, store.schedule(day).filterIndexed { i, _ -> i != index })
                refresh()
            }
            .setNegativeButton("إلغاء", null)
            .show()
    }

    private fun showEditor(index: Int, existing: Period?) {
        val dialog = BottomSheetDialog(this)
        val v = LayoutInflater.from(this).inflate(R.layout.dialog_period, null)
        dialog.setContentView(v)

        val list = store.schedule(day)
        v.findViewById<TextView>(R.id.pdTitle).text =
            (if (existing == null) "إضافة حصة · " else "تعديل حصة · ") + SCHOOL_DAYS[day]
        val subject = v.findViewById<TextInputEditText>(R.id.pdSubject)
        val teacher = v.findViewById<TextInputEditText>(R.id.pdTeacher)
        val minutes = v.findViewById<TextInputEditText>(R.id.pdMinutes)
        val btnStart = v.findViewById<MaterialButton>(R.id.pdStart)
        val spinner = v.findViewById<Spinner>(R.id.pdSource)

        // وقت البداية الافتراضي: بعد آخر حصة
        var start = existing?.start ?: list.lastOrNull()?.let { it.start + it.minutes + 5 } ?: (8 * 60)
        btnStart.text = "يبدأ " + fmtTime(start)
        existing?.let {
            subject.setText(it.subject)
            teacher.setText(it.teacher)
            minutes.setText(it.minutes.toString())
        }

        val sources = store.sources()
        val labels = listOf("بدون") + sources.map { it.display + " (" + it.type.label + ")" }
        spinner.adapter = ArrayAdapter(this, android.R.layout.simple_spinner_dropdown_item, labels)
        val selected = sources.indexOfFirst { it.key == existing?.sourceKey }
        spinner.setSelection(if (selected >= 0) selected + 1 else 0)

        btnStart.setOnClickListener {
            val picker = MaterialTimePicker.Builder()
                .setTimeFormat(TimeFormat.CLOCK_12H)
                .setHour(start / 60)
                .setMinute(start % 60)
                .setTitleText("وقت بداية الحصة")
                .build()
            picker.addOnPositiveButtonClickListener {
                start = picker.hour * 60 + picker.minute
                btnStart.text = "يبدأ " + fmtTime(start)
            }
            picker.show(supportFragmentManager, "start")
        }

        v.findViewById<View>(R.id.pdSave).setOnClickListener {
            val s = subject.text?.toString()?.trim().orEmpty()
            if (s.isEmpty()) {
                toast("اكتب اسم المادة")
                return@setOnClickListener
            }
            val mins = minutes.text?.toString()?.toIntOrNull()?.coerceIn(5, 240) ?: 45
            val pos = spinner.selectedItemPosition
            val key = if (pos > 0) sources[pos - 1].key else ""
            val p = Period(s, teacher.text?.toString()?.trim().orEmpty(), start, mins, key)
            val newList = list.toMutableList()
            if (index in newList.indices) newList[index] = p else newList.add(p)
            store.saveSchedule(day, newList)
            dialog.dismiss()
            refresh()
        }
        dialog.show()
    }
}
