package com.edutube.app

import android.os.Bundle
import android.view.View
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import androidx.swiperefreshlayout.widget.SwipeRefreshLayout
import com.google.android.material.appbar.MaterialToolbar
import kotlinx.coroutines.launch

class VideosActivity : AppCompatActivity() {
    private lateinit var store: Store
    private lateinit var source: Source
    private lateinit var adapter: VideoAdapter
    private lateinit var swipe: SwipeRefreshLayout
    private lateinit var status: TextView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_videos)
        store = Store(this)
        val key = intent.getStringExtra("source_key").orEmpty()
        val s = store.source(key)
        if (s == null) {
            finish()
            return
        }
        source = s

        findViewById<MaterialToolbar>(R.id.toolbar).apply {
            title = source.title
            subtitle = source.type.label
            setSubtitleTextColor(0xFFE6E9FB.toInt())
            setNavigationOnClickListener { finish() }
        }

        swipe = findViewById(R.id.swipe)
        status = findViewById(R.id.status)
        adapter = VideoAdapter { openPlayer(this, it, source.key) }
        findViewById<RecyclerView>(R.id.list).apply {
            layoutManager = LinearLayoutManager(this@VideosActivity)
            adapter = this@VideosActivity.adapter
        }
        swipe.setOnRefreshListener { load() }

        val cached = store.cachedVideos(source.key)
        adapter.submit(cached, store.watchCounts())
        if (cached.isEmpty() || store.cacheAgeMs(source.key) > 30 * 60 * 1000L) load()
    }

    override fun onResume() {
        super.onResume()
        if (::adapter.isInitialized) adapter.updateCounts(store.watchCounts())
    }

    private fun load() {
        swipe.isRefreshing = true
        status.visibility = View.GONE
        lifecycleScope.launch {
            val result = runCatching { Repo.refresh(store, source) }
            swipe.isRefreshing = false
            result.onSuccess { list ->
                adapter.submit(list, store.watchCounts())
                if (list.isEmpty()) {
                    status.text = "لا توجد فيديوهات هنا بعد."
                    status.visibility = View.VISIBLE
                }
            }.onFailure {
                if (adapter.itemCount == 0) {
                    status.text = "تعذّر تحميل الدروس.\nتحقق من الإنترنت ثم اسحب للأسفل لإعادة المحاولة."
                    status.visibility = View.VISIBLE
                } else {
                    toast("تعذّر التحديث، تُعرض النسخة المحفوظة")
                }
            }
        }
    }
}
