package com.edutube.app

import android.content.res.ColorStateList
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.CheckBox
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.appbar.MaterialToolbar
import kotlinx.coroutines.launch

/** المشرف يختار المجموعات والقنوات المسموحة من حساب الطالب. */
class TgPickActivity : AppCompatActivity() {
    private lateinit var store: Store
    private val selected = LinkedHashMap<Long, String>()
    private var items: List<TgChatInfo> = emptyList()
    private val adapter = PickAdapter()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_tg_pick)
        store = Store(this)
        store.tgChats().forEach { selected[it.id] = it.title }

        findViewById<MaterialToolbar>(R.id.toolbar).setNavigationOnClickListener { finish() }
        findViewById<RecyclerView>(R.id.list).apply {
            layoutManager = LinearLayoutManager(this@TgPickActivity)
            adapter = this@TgPickActivity.adapter
        }
        findViewById<View>(R.id.btnSave).setOnClickListener {
            store.saveTgChats(selected.map { TgChat(it.key, it.value) })
            toast("تم الحفظ: ${selected.size}")
            finish()
        }

        val status = findViewById<TextView>(R.id.status)
        Tg.start(this)
        lifecycleScope.launch {
            val r = runCatching { Tg.groupsAndChannels() }
            r.onSuccess { list ->
                // المختارة أولاً
                items = list.sortedByDescending { it.id in selected }
                adapter.notifyDataSetChanged()
                status.visibility = if (list.isEmpty()) View.VISIBLE else View.GONE
                status.text = "لا توجد مجموعات أو قنوات في هذا الحساب."
            }.onFailure {
                status.text = "تعذّر التحميل: ${it.message}\nتأكد أن الحساب مربوط وأن الإنترنت يعمل."
            }
        }
    }

    inner class PickAdapter : RecyclerView.Adapter<PickAdapter.VH>() {
        inner class VH(v: View) : RecyclerView.ViewHolder(v) {
            val letter: TextView = v.findViewById(R.id.letter)
            val name: TextView = v.findViewById(R.id.name)
            val kind: TextView = v.findViewById(R.id.kind)
            val check: CheckBox = v.findViewById(R.id.check)
        }

        override fun getItemCount() = items.size

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int) =
            VH(LayoutInflater.from(parent.context).inflate(R.layout.item_tg_pick, parent, false))

        override fun onBindViewHolder(h: VH, position: Int) {
            val c = items[position]
            val (fg, bg) = subjectColors(c.title)
            h.letter.text = c.title.trim().take(1)
            h.letter.backgroundTintList = ColorStateList.valueOf(bg)
            h.letter.setTextColor(fg)
            h.name.text = c.title
            h.kind.text = c.kind
            h.check.isChecked = c.id in selected
            h.itemView.setOnClickListener {
                if (c.id in selected) selected.remove(c.id) else selected[c.id] = c.title
                notifyItemChanged(h.bindingAdapterPosition)
            }
        }
    }
}
