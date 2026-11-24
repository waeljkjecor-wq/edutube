package com.edutube.app

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.content.res.ColorStateList
import android.graphics.Color
import android.text.InputFilter
import android.text.InputType
import android.view.Gravity
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.ImageButton
import android.widget.ImageView
import android.widget.TextView
import android.widget.Toast
import androidx.recyclerview.widget.RecyclerView
import coil.load
import com.google.android.material.dialog.MaterialAlertDialogBuilder

fun Context.toast(msg: String) = Toast.makeText(this, msg, Toast.LENGTH_SHORT).show()

fun openPlayer(ctx: Context, video: Video, sourceKey: String) {
    ctx.startActivity(
        Intent(ctx, PlayerActivity::class.java)
            .putExtra("video_id", video.id)
            .putExtra("video_title", video.title)
            .putExtra("source_key", sourceKey)
    )
}

fun openSource(ctx: Context, s: Source) {
    if (s.type == SourceType.VIDEO) {
        openPlayer(ctx, Video(s.id, s.title), s.key)
    } else {
        ctx.startActivity(Intent(ctx, VideosActivity::class.java).putExtra("source_key", s.key))
    }
}

private val PALETTE = listOf(
    "#E6E9FB" to "#2A379A",
    "#E3F4EC" to "#14633F",
    "#FDEFD9" to "#8A4B08",
    "#FBE4EC" to "#8E1C47",
    "#E4F1FB" to "#0B5588",
    "#EFE7FB" to "#5B2A9A"
)

/** يملأ بطاقة فيديو (تُستخدم في القوائم وفي «أكمل من حيث توقفت»). */
fun bindVideoRow(view: View, v: Video, count: Int) {
    val thumb = view.findViewById<ImageView>(R.id.thumb)
    thumb.clipToOutline = true
    thumb.load(v.thumb) {
        crossfade(true)
        placeholder(R.drawable.bg_thumb)
        error(R.drawable.bg_thumb)
    }
    view.findViewById<TextView>(R.id.title).text = v.title
    val meta = view.findViewById<TextView>(R.id.meta)
    val date = v.published.take(10)
    meta.text = date
    meta.visibility = if (date.isEmpty()) View.GONE else View.VISIBLE
    view.findViewById<TextView>(R.id.watched).text = viewsText(count)
}

class VideoAdapter(private val onClick: (Video) -> Unit) : RecyclerView.Adapter<VideoAdapter.VH>() {
    private var items: List<Video> = emptyList()
    private var counts: Map<String, Int> = emptyMap()

    class VH(v: View) : RecyclerView.ViewHolder(v)

    fun submit(list: List<Video>, counts: Map<String, Int>) {
        items = list
        this.counts = counts
        notifyDataSetChanged()
    }

    fun updateCounts(counts: Map<String, Int>) {
        this.counts = counts
        notifyDataSetChanged()
    }

    override fun getItemCount() = items.size

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int) =
        VH(LayoutInflater.from(parent.context).inflate(R.layout.item_video, parent, false))

    override fun onBindViewHolder(h: VH, position: Int) {
        val v = items[position]
        bindVideoRow(h.itemView, v, counts[v.id] ?: 0)
        h.itemView.setOnClickListener { onClick(v) }
    }
}

class SourceAdapter(private val onClick: (Source) -> Unit) : RecyclerView.Adapter<SourceAdapter.VH>() {
    private var items: List<Source> = emptyList()
    private var subs: Map<String, String> = emptyMap()

    class VH(v: View) : RecyclerView.ViewHolder(v) {
        val letter: TextView = v.findViewById(R.id.letter)
        val name: TextView = v.findViewById(R.id.name)
        val sub: TextView = v.findViewById(R.id.sub)
    }

    fun submit(list: List<Source>, subs: Map<String, String>) {
        items = list
        this.subs = subs
        notifyDataSetChanged()
    }

    override fun getItemCount() = items.size

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int) =
        VH(LayoutInflater.from(parent.context).inflate(R.layout.item_source, parent, false))

    override fun onBindViewHolder(h: VH, position: Int) {
        val s = items[position]
        val (bg, fg) = PALETTE[position % PALETTE.size]
        h.letter.text = s.title.trim().take(1)
        h.letter.backgroundTintList = ColorStateList.valueOf(Color.parseColor(bg))
        h.letter.setTextColor(Color.parseColor(fg))
        h.name.text = s.title
        h.sub.text = subs[s.key] ?: s.type.label
        h.itemView.setOnClickListener { onClick(s) }
    }
}

class AdminSourceAdapter(private val onDelete: (Source) -> Unit) : RecyclerView.Adapter<AdminSourceAdapter.VH>() {
    private var items: List<Source> = emptyList()

    class VH(v: View) : RecyclerView.ViewHolder(v) {
        val tag: TextView = v.findViewById(R.id.tag)
        val name: TextView = v.findViewById(R.id.name)
        val del: ImageButton = v.findViewById(R.id.btnDelete)
    }

    fun submit(list: List<Source>) {
        items = list
        notifyDataSetChanged()
    }

    override fun getItemCount() = items.size

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int) =
        VH(LayoutInflater.from(parent.context).inflate(R.layout.item_admin_source, parent, false))

    override fun onBindViewHolder(h: VH, position: Int) {
        val s = items[position]
        h.tag.text = s.type.label
        h.name.text = s.title
        h.del.setOnClickListener { onDelete(s) }
    }
}

/** نوافذ رمز PIN. */
object Pin {
    fun ask(activity: Activity, title: String, onOk: (String) -> Unit) {
        val input = EditText(activity).apply {
            inputType = InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_VARIATION_PASSWORD
            gravity = Gravity.CENTER
            textSize = 24f
            hint = "••••"
            filters = arrayOf(InputFilter.LengthFilter(8))
        }
        val box = FrameLayout(activity).apply {
            val pad = (24 * resources.displayMetrics.density).toInt()
            setPadding(pad, pad / 2, pad, 0)
            addView(input)
        }
        val dialog = MaterialAlertDialogBuilder(activity)
            .setTitle(title)
            .setView(box)
            .setPositiveButton("موافق") { _, _ -> onOk(input.text.toString()) }
            .setNegativeButton("إلغاء", null)
            .create()
        dialog.window?.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_STATE_ALWAYS_VISIBLE)
        dialog.show()
        input.requestFocus()
    }

    /** يطلب رمز المشرف (وينشئه أول مرة) ثم ينفّذ [onSuccess]. */
    fun requireAdmin(activity: Activity, store: Store, onSuccess: () -> Unit) {
        val pin = store.pin
        if (pin.isNullOrEmpty()) {
            ask(activity, "أنشئ رمز PIN للمشرف (4 أرقام على الأقل)") { p ->
                if (p.length >= 4) {
                    store.pin = p
                    activity.toast("تم حفظ الرمز. لا تعطه للطالب.")
                    onSuccess()
                } else activity.toast("الرمز قصير جداً")
            }
        } else {
            ask(activity, "أدخل رمز PIN للمشرف") { p ->
                if (p == pin) onSuccess() else activity.toast("رمز غير صحيح")
            }
        }
    }
}
