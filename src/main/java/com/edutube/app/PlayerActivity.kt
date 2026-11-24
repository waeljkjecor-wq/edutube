package com.edutube.app

import android.annotation.SuppressLint
import android.content.pm.ActivityInfo
import android.content.res.Configuration
import android.os.Bundle
import android.os.Message
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.webkit.JavascriptInterface
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.FrameLayout
import android.widget.TextView
import androidx.activity.OnBackPressedCallback
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView

/**
 * مشغّل مقفل: يعرض الفيديو عبر مشغّل يوتيوب المضمَّن داخل WebView،
 * ويمنع أي انتقال إلى موقع يوتيوب أو فتح نوافذ جديدة.
 */
class PlayerActivity : AppCompatActivity() {
    private lateinit var store: Store
    private lateinit var web: WebView
    private lateinit var playerBox: FrameLayout
    private lateinit var fsContainer: FrameLayout
    private lateinit var topBar: View
    private lateinit var controls: View
    private lateinit var nextTitle: View
    private lateinit var nextList: RecyclerView
    private lateinit var btnExitFs: View
    private lateinit var titleText: TextView
    private lateinit var countText: TextView
    private lateinit var errorText: TextView
    private lateinit var adapter: VideoAdapter

    private var sourceKey = ""
    private var current: Video? = null
    private var countedId: String? = null
    private var ownFullscreen = false
    private var customView: View? = null
    private var customCallback: WebChromeClient.CustomViewCallback? = null

    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_player)
        store = Store(this)

        web = findViewById(R.id.web)
        playerBox = findViewById(R.id.playerBox)
        fsContainer = findViewById(R.id.fsContainer)
        topBar = findViewById(R.id.topBar)
        controls = findViewById(R.id.controls)
        nextTitle = findViewById(R.id.nextTitle)
        nextList = findViewById(R.id.nextList)
        btnExitFs = findViewById(R.id.btnExitFs)
        titleText = findViewById(R.id.title)
        countText = findViewById(R.id.count)
        errorText = findViewById(R.id.error)

        sourceKey = intent.getStringExtra("source_key").orEmpty()
        val source = store.source(sourceKey)
        findViewById<TextView>(R.id.sourceTitle).text = source?.title ?: getString(R.string.app_name)

        findViewById<View>(R.id.btnBack).setOnClickListener { finish() }
        findViewById<View>(R.id.btnFullscreen).setOnClickListener { setOwnFullscreen(true) }
        btnExitFs.setOnClickListener { setOwnFullscreen(false) }

        adapter = VideoAdapter { v ->
            play(v)
            nextList.scrollToPosition(0)
        }
        nextList.layoutManager = LinearLayoutManager(this)
        nextList.adapter = adapter

        setupWebView()
        updatePlayerHeight()

        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                when {
                    customView != null -> hideCustomView()
                    ownFullscreen -> setOwnFullscreen(false)
                    else -> finish()
                }
            }
        })

        val id = intent.getStringExtra("video_id").orEmpty()
        if (id.isEmpty()) {
            finish()
            return
        }
        play(Video(id, intent.getStringExtra("video_title").orEmpty()))
    }

    @SuppressLint("SetJavaScriptEnabled", "JavascriptInterface")
    private fun setupWebView() {
        web.settings.apply {
            javaScriptEnabled = true
            domStorageEnabled = true
            mediaPlaybackRequiresUserGesture = false
            javaScriptCanOpenWindowsAutomatically = false
            setSupportMultipleWindows(true)
            setSupportZoom(false)
        }
        web.setBackgroundColor(0xFF000000.toInt())
        web.isLongClickable = false
        web.setOnLongClickListener { true }
        web.addJavascriptInterface(Bridge(), "Android")

        web.webViewClient = object : WebViewClient() {
            // منع أي انتقال للصفحة الرئيسية (مثل الضغط على شعار يوتيوب أو العنوان)
            override fun shouldOverrideUrlLoading(view: WebView?, request: WebResourceRequest?): Boolean =
                request?.isForMainFrame ?: true
        }

        web.webChromeClient = object : WebChromeClient() {
            // منع فتح أي نافذة جديدة
            override fun onCreateWindow(view: WebView?, isDialog: Boolean, isUserGesture: Boolean, resultMsg: Message?): Boolean = false

            // زر ملء الشاشة داخل مشغّل يوتيوب
            override fun onShowCustomView(view: View?, callback: WebChromeClient.CustomViewCallback?) {
                if (view == null) return
                if (customView != null) {
                    callback?.onCustomViewHidden()
                    return
                }
                customView = view
                customCallback = callback
                fsContainer.addView(
                    view,
                    FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
                )
                fsContainer.visibility = View.VISIBLE
                enterImmersive()
            }

            override fun onHideCustomView() {
                removeCustomView()
            }
        }
    }

    private fun removeCustomView() {
        val v = customView ?: return
        fsContainer.removeView(v)
        fsContainer.visibility = View.GONE
        customView = null
        customCallback = null
        if (!ownFullscreen) exitImmersive()
    }

    private fun hideCustomView() {
        val cb = customCallback
        removeCustomView()
        cb?.onCustomViewHidden()
    }

    private fun play(v: Video) {
        current = v
        countedId = null
        titleText.text = v.title.ifBlank { "درس" }
        countText.text = viewsText(store.watchCount(v.id))
        errorText.visibility = View.GONE
        web.loadDataWithBaseURL(BASE_URL, playerHtml(v.id), "text/html", "utf-8", null)

        val source = store.source(sourceKey)
        val all = when {
            source == null -> emptyList()
            source.type == SourceType.VIDEO -> store.sources().filter { it.type == SourceType.VIDEO }.map { Video(it.id, it.title) }
            else -> store.cachedVideos(sourceKey)
        }
        val others = all.filter { it.id != v.id }
        adapter.submit(others, store.watchCounts())
        val showList = others.isNotEmpty() && !ownFullscreen
        nextTitle.visibility = if (showList) View.VISIBLE else View.GONE
        nextList.visibility = if (showList) View.VISIBLE else View.GONE
    }

    private fun onPlayerState(state: Int) {
        val v = current ?: return
        // 1 = بدأ التشغيل: نحسب مشاهدة واحدة لكل فتح للفيديو
        if (state == 1 && countedId != v.id) {
            countedId = v.id
            val n = store.incrementWatch(v)
            countText.text = viewsText(n)
            store.setLast(v, sourceKey)
        }
    }

    private fun onPlayerError(code: Int) {
        errorText.text = when (code) {
            101, 150, 152, 153 -> "صاحب هذا الفيديو لا يسمح بتشغيله خارج يوتيوب. اطلب من المعلم استبداله."
            100 -> "هذا الفيديو محذوف أو خاص."
            else -> "تعذّر تشغيل الفيديو (رمز $code). تحقق من الإنترنت."
        }
        errorText.visibility = View.VISIBLE
    }

    inner class Bridge {
        @JavascriptInterface
        fun onState(state: Int) {
            runOnUiThread { onPlayerState(state) }
        }

        @JavascriptInterface
        fun onError(code: Int) {
            runOnUiThread { onPlayerError(code) }
        }
    }

    // ---------- ملء الشاشة ----------

    private fun setOwnFullscreen(on: Boolean) {
        ownFullscreen = on
        val vis = if (on) View.GONE else View.VISIBLE
        topBar.visibility = vis
        controls.visibility = vis
        val hasList = adapter.itemCount > 0
        nextTitle.visibility = if (!on && hasList) View.VISIBLE else View.GONE
        nextList.visibility = if (!on && hasList) View.VISIBLE else View.GONE
        btnExitFs.visibility = if (on) View.VISIBLE else View.GONE
        if (on) {
            playerBox.layoutParams = playerBox.layoutParams.apply { height = ViewGroup.LayoutParams.MATCH_PARENT }
            enterImmersive()
        } else {
            exitImmersive()
            updatePlayerHeight()
        }
    }

    private fun enterImmersive() {
        requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        WindowCompat.getInsetsController(window, window.decorView).apply {
            systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
            hide(WindowInsetsCompat.Type.systemBars())
        }
    }

    private fun exitImmersive() {
        window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        WindowCompat.getInsetsController(window, window.decorView).show(WindowInsetsCompat.Type.systemBars())
        requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_PORTRAIT
    }

    private fun updatePlayerHeight() {
        if (ownFullscreen) return
        playerBox.post {
            if (ownFullscreen) return@post
            val w = if (playerBox.width > 0) playerBox.width else resources.displayMetrics.widthPixels
            playerBox.layoutParams = playerBox.layoutParams.apply { height = w * 9 / 16 }
        }
    }

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        updatePlayerHeight()
    }

    override fun onPause() {
        super.onPause()
        web.onPause()
    }

    override fun onResume() {
        super.onResume()
        web.onResume()
    }

    override fun onDestroy() {
        web.removeJavascriptInterface("Android")
        web.loadUrl("about:blank")
        web.destroy()
        super.onDestroy()
    }

    companion object {
        private const val BASE_URL = "https://com.edutube.app/"

        fun playerHtml(videoId: String): String = """
<!DOCTYPE html>
<html><head>
<meta name="viewport" content="width=device-width, initial-scale=1, maximum-scale=1">
<style>
html,body{margin:0;padding:0;background:#000;width:100%;height:100%;overflow:hidden}
#p{position:absolute;top:0;left:0;width:100%;height:100%;border:0}
</style>
</head><body>
<div id="p"></div>
<script>
  var tag = document.createElement('script');
  tag.src = "https://www.youtube.com/iframe_api";
  document.head.appendChild(tag);
  var player;
  function onYouTubeIframeAPIReady() {
    player = new YT.Player('p', {
      width: '100%', height: '100%',
      videoId: '$videoId',
      host: 'https://www.youtube-nocookie.com',
      playerVars: {
        autoplay: 1, playsinline: 1, rel: 0, fs: 1,
        modestbranding: 1, iv_load_policy: 3, disablekb: 0,
        origin: 'https://com.edutube.app'
      },
      events: {
        onReady: function (e) { e.target.playVideo(); },
        onStateChange: function (e) { Android.onState(e.data); },
        onError: function (e) { Android.onError(e.data); }
      }
    });
  }
</script>
</body></html>
"""
    }
}
