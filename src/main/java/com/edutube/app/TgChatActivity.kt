package com.edutube.app

import android.app.ActivityManager
import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.media.MediaPlayer
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.webkit.MimeTypeMap
import android.widget.ImageButton
import android.widget.ImageView
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.FileProvider
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import coil.load
import com.google.android.material.appbar.MaterialToolbar
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import java.io.File

/** عرض مجموعة/قناة تيليجرام مسموحة للقراءة فقط. */
class TgChatActivity : AppCompatActivity() {
    private var chatId = 0L
    private val items = mutableListOf<TgItem>() // من الأقدم إلى الأحدث
    private val paths = HashMap<Int, String>()
    private val pending = HashSet<Int>()
    private val adapter = MsgAdapter()
    private lateinit var list: RecyclerView
    private lateinit var lm: LinearLayoutManager
    private lateinit var progress: View
    private lateinit var status: TextView
    private var loadingOlder = false
    private var hasMore = true
    private var player: MediaPlayer? = null
    private var playingId = 0L
    private var opened = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_tg_chat)
        chatId = intent.getLongExtra("chat_id", 0L)
        val allowed = Store(this).tgChats().firstOrNull { it.id == chatId }
        if (allowed == null) {
            finish()
            return
        }
        findViewById<MaterialToolbar>(R.id.toolbar).apply {
            title = allowed.title
            subtitle = "للقراءة فقط"
            setNavigationOnClickListener { finish() }
        }
        progress = findViewById(R.id.progress)
        status = findViewById(R.id.status)
        list = findViewById(R.id.messages)
        lm = LinearLayoutManager(this).apply { stackFromEnd = true }
        list.layoutManager = lm
        list.adapter = adapter
        list.addOnScrollListener(object : RecyclerView.OnScrollListener() {
            override fun onScrolled(recyclerView: RecyclerView, dx: Int, dy: Int) {
                if (dy < 0 && lm.findFirstVisibleItemPosition() <= 2) loadOlder()
            }
        })

        Tg.start(this)
        lifecycleScope.launch {
            val ready = withTimeoutOrNull(25_000L) { Tg.state.first { it is TgState.Ready } }
            if (ready == null) {
                progress.visibility = View.GONE
                status.visibility = View.VISIBLE
                status.text = "تعذّر الاتصال بتيليجرام.\nتحقق من الإنترنت ثم افتح المجموعة مجدداً."
                return@launch
            }
            Tg.open(chatId)
            opened = true
            loadInitial()
            loadPinned()
            Tg.newMessages.collect { m ->
                if (m.chatId == chatId && items.none { it.id == m.id }) {
                    val atBottom = lm.findLastVisibleItemPosition() >= items.size - 2
                    items.add(Tg.toItem(m))
                    adapter.notifyItemInserted(items.size - 1)
                    if (atBottom) list.scrollToPosition(items.size - 1)
                    status.visibility = View.GONE
                    Tg.markRead(chatId, listOf(m.id))
                }
            }
        }
    }

    private suspend fun loadInitial() {
        val ms = runCatching { Tg.history(chatId, 0L, 40) }.getOrElse { emptyList() }
        items.clear()
        items.addAll(ms.reversed().map { Tg.toItem(it) })
        adapter.notifyDataSetChanged()
        if (items.isNotEmpty()) list.scrollToPosition(items.size - 1)
        progress.visibility = View.GONE
        hasMore = ms.isNotEmpty()
        if (items.isEmpty()) {
            status.visibility = View.VISIBLE
            status.text = "لا توجد رسائل بعد."
        }
        Tg.markRead(chatId, ms.map { it.id })
    }

    private suspend fun loadPinned() {
        val pm = runCatching { Tg.pinned(chatId) }.getOrNull() ?: return
        val bar = findViewById<View>(R.id.pinnedBar)
        bar.visibility = View.VISIBLE
        findViewById<TextView>(R.id.pinnedText).text = Tg.preview(pm)
        bar.setOnClickListener {
            val idx = items.indexOfFirst { it.id == pm.id }
            if (idx >= 0) list.scrollToPosition(idx) else toast("الرسالة المثبّتة أقدم، اسحب للأعلى لتحميلها")
        }
    }

    private fun loadOlder() {
        if (loadingOlder || !hasMore || items.isEmpty()) return
        loadingOlder = true
        val oldest = items.first().id
        lifecycleScope.launch {
            val ms = runCatching { Tg.history(chatId, oldest, 40) }.getOrElse { emptyList() }
            val fresh = ms.map { Tg.toItem(it) }.filter { n -> items.none { it.id == n.id } }.reversed()
            if (fresh.isEmpty()) {
                hasMore = false
            } else {
                items.addAll(0, fresh)
                adapter.notifyItemRangeInserted(0, fresh.size)
            }
            loadingOlder = false
        }
    }

    // ---------- الملفات ----------

    private fun ensureDownload(fileId: Int) {
        if (fileId == 0 || fileId in pending || paths.containsKey(fileId)) return
        pending.add(fileId)
        lifecycleScope.launch {
            val p = runCatching { Tg.download(fileId) }.getOrNull()
            pending.remove(fileId)
            if (p != null) {
                paths[fileId] = p
                items.forEachIndexed { i, it ->
                    if (it.fileId == fileId || it.thumbFileId == fileId) adapter.notifyItemChanged(i)
                }
            }
        }
    }

    private fun downloadThen(item: TgItem, action: (String) -> Unit) {
        val cached = paths[item.fileId]
        if (cached != null) {
            action(cached)
            return
        }
        toast("جارٍ التنزيل…")
        lifecycleScope.launch {
            val p = runCatching { Tg.download(item.fileId) }.getOrNull()
            if (p == null) {
                toast("تعذّر تنزيل الملف")
            } else {
                paths[item.fileId] = p
                action(p)
            }
        }
    }

    private fun openItem(item: TgItem) {
        downloadThen(item) { path ->
            val lower = item.fileName.lowercase()
            when {
                item.kind == TgKind.VIDEO -> openMedia("video", path)
                item.kind == TgKind.PHOTO -> openMedia("image", path)
                item.isPdf -> startActivity(
                    Intent(this, PdfActivity::class.java).putExtra("path", path).putExtra("title", item.fileName)
                )
                lower.endsWith(".jpg") || lower.endsWith(".jpeg") || lower.endsWith(".png") || lower.endsWith(".webp") ->
                    openMedia("image", path)
                lower.endsWith(".mp4") || lower.endsWith(".mkv") || lower.endsWith(".3gp") -> openMedia("video", path)
                else -> openExternal(path, item.fileName)
            }
        }
    }

    private fun openMedia(type: String, path: String) {
        startActivity(Intent(this, MediaActivity::class.java).putExtra("type", type).putExtra("path", path))
    }

    private fun openExternal(path: String, name: String) {
        val am = getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
        if (am.lockTaskModeState != ActivityManager.LOCK_TASK_MODE_NONE) {
            toast("لا يمكن فتح هذا النوع من الملفات أثناء قفل الشاشة")
            return
        }
        try {
            val uri = FileProvider.getUriForFile(this, "$packageName.files", File(path))
            val ext = name.substringAfterLast('.', "").lowercase()
            val mime = MimeTypeMap.getSingleton().getMimeTypeFromExtension(ext) ?: "*/*"
            startActivity(
                Intent(Intent.ACTION_VIEW).setDataAndType(uri, mime).addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            )
        } catch (_: ActivityNotFoundException) {
            toast("لا يوجد تطبيق على الهاتف يفتح هذا الملف")
        } catch (_: IllegalArgumentException) {
            toast("تعذّر فتح الملف")
        }
    }

    // ---------- الصوت ----------

    private fun toggleAudio(item: TgItem) {
        if (playingId == item.id) {
            stopAudio()
            return
        }
        stopAudio()
        downloadThen(item) { path ->
            try {
                player = MediaPlayer().apply {
                    setDataSource(path)
                    setOnCompletionListener { stopAudio() }
                    prepare()
                    start()
                }
                playingId = item.id
                adapter.notifyDataSetChanged()
            } catch (_: Exception) {
                toast("تعذّر تشغيل الصوت")
                stopAudio()
            }
        }
    }

    private fun stopAudio() {
        player?.release()
        player = null
        if (playingId != 0L) {
            playingId = 0L
            adapter.notifyDataSetChanged()
        }
    }

    override fun onPause() {
        super.onPause()
        stopAudio()
    }

    override fun onDestroy() {
        super.onDestroy()
        player?.release()
        if (opened) Tg.closeLater(chatId)
    }

    // ---------- العرض ----------

    inner class MsgAdapter : RecyclerView.Adapter<MsgAdapter.VH>() {
        inner class VH(v: View) : RecyclerView.ViewHolder(v) {
            val photo: ImageView = v.findViewById(R.id.photo)
            val videoBox: View = v.findViewById(R.id.videoBox)
            val videoThumb: ImageView = v.findViewById(R.id.videoThumb)
            val videoDur: TextView = v.findViewById(R.id.videoDur)
            val fileRow: View = v.findViewById(R.id.fileRow)
            val fileIcon: TextView = v.findViewById(R.id.fileIcon)
            val fileName: TextView = v.findViewById(R.id.fileName)
            val fileMeta: TextView = v.findViewById(R.id.fileMeta)
            val fileOpen: View = v.findViewById(R.id.fileOpen)
            val audioRow: View = v.findViewById(R.id.audioRow)
            val audioPlay: ImageButton = v.findViewById(R.id.audioPlay)
            val audioLabel: TextView = v.findViewById(R.id.audioLabel)
            val text: TextView = v.findViewById(R.id.text)
            val time: TextView = v.findViewById(R.id.time)
        }

        override fun getItemCount() = items.size

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int) =
            VH(LayoutInflater.from(parent.context).inflate(R.layout.item_tg_msg, parent, false))

        override fun onBindViewHolder(h: VH, position: Int) {
            val it = items[position]
            h.photo.visibility = View.GONE
            h.videoBox.visibility = View.GONE
            h.fileRow.visibility = View.GONE
            h.audioRow.visibility = View.GONE
            h.photo.clipToOutline = true
            h.videoThumb.clipToOutline = true

            h.text.text = it.text
            h.text.visibility = if (it.text.isBlank()) View.GONE else View.VISIBLE
            h.time.text = shortDate(it.date)

            when (it.kind) {
                TgKind.PHOTO -> {
                    h.photo.visibility = View.VISIBLE
                    val p = paths[it.fileId]
                    if (p != null) h.photo.load(File(p)) else {
                        h.photo.setImageDrawable(null)
                        ensureDownload(it.fileId)
                    }
                    h.photo.setOnClickListener { _ -> openItem(it) }
                }
                TgKind.VIDEO -> {
                    h.videoBox.visibility = View.VISIBLE
                    val t = paths[it.thumbFileId]
                    if (t != null) h.videoThumb.load(File(t)) else {
                        h.videoThumb.setImageDrawable(null)
                        ensureDownload(it.thumbFileId)
                    }
                    h.videoDur.text = fmtDuration(it.duration)
                    h.videoBox.setOnClickListener { _ -> openItem(it) }
                }
                TgKind.DOCUMENT -> {
                    h.fileRow.visibility = View.VISIBLE
                    h.fileIcon.text = if (it.isPdf) "PDF" else it.fileName.substringAfterLast('.', "ملف").uppercase().take(4)
                    h.fileName.text = it.fileName
                    h.fileMeta.text = readableSize(it.size)
                    h.fileOpen.setOnClickListener { _ -> openItem(it) }
                }
                TgKind.VOICE, TgKind.AUDIO -> {
                    h.audioRow.visibility = View.VISIBLE
                    val label = if (it.kind == TgKind.VOICE) "رسالة صوتية" else it.fileName
                    h.audioLabel.text = label + " · " + fmtDuration(it.duration)
                    h.audioPlay.setImageResource(if (playingId == it.id) R.drawable.ic_pause else R.drawable.ic_play)
                    h.audioPlay.setOnClickListener { _ -> toggleAudio(it) }
                }
                else -> {}
            }
        }
    }
}

fun fmtDuration(sec: Int): String = "${sec / 60}:" + (sec % 60).toString().padStart(2, '0')

fun readableSize(bytes: Long): String = when {
    bytes >= 1024L * 1024 -> String.format(java.util.Locale.US, "%.1f ميغابايت", bytes / 1048576.0)
    bytes >= 1024L -> "${bytes / 1024} كيلوبايت"
    else -> "$bytes بايت"
}
