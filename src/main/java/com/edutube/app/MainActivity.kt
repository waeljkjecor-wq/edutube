package com.edutube.app

import android.Manifest
import android.app.ActivityManager
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.view.View
import android.widget.EditText
import android.widget.TextView
import androidx.activity.OnBackPressedCallback
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.widget.doAfterTextChanged
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.bottomnavigation.BottomNavigationView
import kotlinx.coroutines.launch

class MainActivity : AppCompatActivity() {
    private lateinit var store: Store
    private lateinit var sourceAdapter: SourceAdapter
    private lateinit var searchAdapter: VideoAdapter
    private lateinit var search: EditText
    private lateinit var mainContent: View
    private lateinit var searchList: RecyclerView
    private lateinit var emptyText: TextView
    private lateinit var continueSection: View
    private lateinit var continueCard: View
    private lateinit var tabYt: TextView
    private lateinit var tabTg: TextView
    private lateinit var searchRow: View
    private lateinit var lessonsExtras: View
    private lateinit var tgPaneView: View
    private lateinit var examsPaneView: View
    private lateinit var schedulePaneView: View
    private lateinit var headerTitle: TextView
    private lateinit var headerSub: TextView
    private lateinit var bottomNav: BottomNavigationView

    private lateinit var examsPane: ExamsPane
    private lateinit var schedulePane: SchedulePane
    private lateinit var telegramPane: TelegramPane

    private val searchKeys = HashMap<String, String>()
    private var prefetching = false
    private var section = "lessons"
    private var lessonsTab = "yt"

    private val notifPermission = registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (!granted) toast("لن تصلك التذكيرات حتى تسمح بالإشعارات من إعدادات الهاتف")
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        store = Store(this)
        Reminders.ensureChannel(this)

        search = findViewById(R.id.search)
        mainContent = findViewById(R.id.mainContent)
        searchList = findViewById(R.id.searchList)
        emptyText = findViewById(R.id.emptyText)
        continueSection = findViewById(R.id.continueSection)
        continueCard = findViewById(R.id.continueCard)
        tabYt = findViewById(R.id.tabYt)
        tabTg = findViewById(R.id.tabTg)
        searchRow = findViewById(R.id.searchRow)
        lessonsExtras = findViewById(R.id.lessonsExtras)
        tgPaneView = findViewById(R.id.tgPane)
        examsPaneView = findViewById(R.id.examsPane)
        schedulePaneView = findViewById(R.id.schedulePane)
        headerTitle = findViewById(R.id.headerTitle)
        headerSub = findViewById(R.id.headerSub)
        bottomNav = findViewById(R.id.bottomNav)

        sourceAdapter = SourceAdapter { openSource(this, it) }
        findViewById<RecyclerView>(R.id.sourcesList).apply {
            layoutManager = GridLayoutManager(this@MainActivity, 2)
            adapter = sourceAdapter
            isNestedScrollingEnabled = false
        }

        searchAdapter = VideoAdapter { v -> openPlayer(this, v, searchKeys[v.id] ?: "") }
        searchList.layoutManager = LinearLayoutManager(this)
        searchList.adapter = searchAdapter
        search.doAfterTextChanged { runSearch(it?.toString().orEmpty()) }

        examsPane = ExamsPane(this, examsPaneView) { askNotificationPermission() }
        schedulePane = SchedulePane(this, schedulePaneView)
        telegramPane = TelegramPane(this, tgPaneView)

        tabYt.setOnClickListener { setLessonsTab("yt") }
        tabTg.setOnClickListener { setLessonsTab("tg") }

        bottomNav.setOnItemSelectedListener { item ->
            when (item.itemId) {
                R.id.nav_exams -> showSection("exams")
                R.id.nav_schedule -> showSection("schedule")
                else -> showSection("lessons")
            }
            true
        }

        findViewById<View>(R.id.btnAdmin).setOnClickListener {
            Pin.requireAdmin(this, store) { startActivity(Intent(this, AdminActivity::class.java)) }
        }

        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                if (section != "lessons") {
                    bottomNav.selectedItemId = R.id.nav_lessons
                    return
                }
                if (search.text.isNotEmpty()) {
                    search.setText("")
                    return
                }
                if (store.pinOnExit || store.lockScreen) {
                    Pin.requireAdmin(this@MainActivity, store) { exitApp() }
                } else exitApp()
            }
        })

        // تحديث قسم تيليجرام عند اكتمال الاتصال
        if (Tg.isConfigured(this)) Tg.start(this)
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                Tg.state.collect { if (it is TgState.Ready && lessonsTab == "tg") telegramPane.refresh() }
            }
        }

        setLessonsTab("yt")
        handleIntent(intent)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handleIntent(intent)
    }

    private fun handleIntent(intent: Intent?) {
        if (intent?.getStringExtra("tab") == "exams") bottomNav.selectedItemId = R.id.nav_exams
    }

    override fun onResume() {
        super.onResume()
        refresh()
        when (section) {
            "exams" -> examsPane.refresh()
            "schedule" -> schedulePane.refresh()
        }
        if (lessonsTab == "tg") telegramPane.refresh()
        applyLock()
        prefetch()
    }

    // ---------- التنقل ----------

    private fun showSection(s: String) {
        section = s
        val lessons = s == "lessons"
        lessonsExtras.visibility = if (lessons) View.VISIBLE else View.GONE
        examsPaneView.visibility = if (s == "exams") View.VISIBLE else View.GONE
        schedulePaneView.visibility = if (s == "schedule") View.VISIBLE else View.GONE
        when (s) {
            "exams" -> {
                headerTitle.text = "الامتحانات"
                headerSub.text = "تذكير تلقائي قبل كل امتحان"
                hideLessons()
                examsPane.refresh()
            }
            "schedule" -> {
                headerTitle.text = "جدول الدروس"
                headerSub.text = "الحصص الأسبوعية"
                hideLessons()
                schedulePane.refresh()
            }
            else -> {
                headerTitle.text = getString(R.string.app_name)
                headerSub.text = "يوتيوب وتيليجرام بأمان"
                setLessonsTab(lessonsTab)
            }
        }
    }

    private fun hideLessons() {
        mainContent.visibility = View.GONE
        searchList.visibility = View.GONE
        tgPaneView.visibility = View.GONE
    }

    private fun setLessonsTab(t: String) {
        lessonsTab = t
        val yt = t == "yt"
        styleTab(tabYt, yt)
        styleTab(tabTg, !yt)
        searchRow.visibility = if (yt) View.VISIBLE else View.GONE
        if (section != "lessons") return
        if (yt) {
            tgPaneView.visibility = View.GONE
            runSearch(search.text.toString())
        } else {
            mainContent.visibility = View.GONE
            searchList.visibility = View.GONE
            tgPaneView.visibility = View.VISIBLE
            telegramPane.refresh()
        }
    }

    private fun styleTab(v: TextView, on: Boolean) {
        if (on) {
            v.setBackgroundResource(R.drawable.bg_toggle_on)
            v.setTextColor(ContextCompat.getColor(this, R.color.primary_dark))
        } else {
            v.background = null
            v.setTextColor(ContextCompat.getColor(this, R.color.white))
        }
    }

    private fun askNotificationPermission() {
        if (Build.VERSION.SDK_INT >= 33 && !Reminders.canNotify(this)) {
            notifPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
    }

    // ---------- يوتيوب ----------

    private fun refresh() {
        val sources = store.sources()
        val subs = HashMap<String, String>()
        sources.forEach { s ->
            val n = store.cachedVideos(s.key).size
            subs[s.key] = when {
                s.type == SourceType.VIDEO -> "فيديو واحد"
                n > 0 -> "${s.type.label} · $n درس"
                else -> s.type.label
            }
        }
        sourceAdapter.submit(sources, subs)
        emptyText.visibility = if (sources.isEmpty()) View.VISIBLE else View.GONE

        val last = store.last()
        if (last != null && store.source(last.second) != null) {
            val (video, key) = last
            continueSection.visibility = View.VISIBLE
            bindVideoRow(continueCard, video, store.watchCount(video.id))
            continueCard.setOnClickListener { openPlayer(this, video, key) }
        } else {
            continueSection.visibility = View.GONE
        }
        if (section == "lessons" && lessonsTab == "yt" && search.text.isNotEmpty()) runSearch(search.text.toString())
    }

    /** يحدّث قوائم الفيديوهات في الخلفية حتى يعمل البحث والعدّ. */
    private fun prefetch() {
        if (prefetching) return
        val stale = store.sources().filter {
            it.type != SourceType.VIDEO && store.cacheAgeMs(it.key) > 6 * 60 * 60 * 1000L
        }
        if (stale.isEmpty()) return
        prefetching = true
        lifecycleScope.launch {
            for (s in stale) runCatching { Repo.refresh(store, s) }
            prefetching = false
            refresh()
        }
    }

    private fun runSearch(q: String) {
        if (section != "lessons" || lessonsTab != "yt") return
        val query = q.trim()
        if (query.isEmpty()) {
            searchList.visibility = View.GONE
            mainContent.visibility = View.VISIBLE
            return
        }
        searchKeys.clear()
        val results = mutableListOf<Video>()
        for (s in store.sources()) {
            for (v in store.videosOf(s)) {
                if (v.title.contains(query, ignoreCase = true) && !searchKeys.containsKey(v.id)) {
                    searchKeys[v.id] = s.key
                    results.add(v)
                }
            }
        }
        searchAdapter.submit(results, store.watchCounts())
        mainContent.visibility = View.GONE
        searchList.visibility = View.VISIBLE
    }

    // ---------- القفل ----------

    private fun inLockTask(): Boolean {
        val am = getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
        return am.lockTaskModeState != ActivityManager.LOCK_TASK_MODE_NONE
    }

    private fun applyLock() {
        try {
            if (store.lockScreen && !inLockTask()) startLockTask()
            if (!store.lockScreen && inLockTask()) stopLockTask()
        } catch (_: Exception) {
        }
    }

    private fun exitApp() {
        try {
            if (inLockTask()) stopLockTask()
        } catch (_: Exception) {
        }
        finishAffinity()
    }
}
