package com.edutube.app

import android.os.Bundle
import android.util.TypedValue
import android.view.View
import android.widget.LinearLayout
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.appbar.MaterialToolbar
import com.google.android.material.button.MaterialButton
import com.google.android.material.button.MaterialButtonToggleGroup
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.materialswitch.MaterialSwitch
import com.google.android.material.progressindicator.LinearProgressIndicator
import com.google.android.material.textfield.TextInputEditText
import com.google.android.material.textfield.TextInputLayout
import kotlinx.coroutines.launch

class AdminActivity : AppCompatActivity() {
    private lateinit var store: Store
    private lateinit var sourcesAdapter: AdminSourceAdapter
    private lateinit var typeGroup: MaterialButtonToggleGroup
    private lateinit var input: TextInputEditText
    private lateinit var inputLayout: TextInputLayout
    private lateinit var inputHelp: TextView
    private lateinit var btnAdd: MaterialButton
    private lateinit var progress: LinearProgressIndicator
    private lateinit var adminEmpty: TextView
    private lateinit var sourcesTitle: TextView
    private lateinit var statsBox: LinearLayout
    private lateinit var statsTitle: TextView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_admin)
        store = Store(this)

        findViewById<MaterialToolbar>(R.id.toolbar).setNavigationOnClickListener { finish() }

        typeGroup = findViewById(R.id.typeGroup)
        input = findViewById(R.id.input)
        inputLayout = findViewById(R.id.inputLayout)
        inputHelp = findViewById(R.id.inputHelp)
        btnAdd = findViewById(R.id.btnAdd)
        progress = findViewById(R.id.addProgress)
        adminEmpty = findViewById(R.id.adminEmpty)
        sourcesTitle = findViewById(R.id.sourcesTitle)
        statsBox = findViewById(R.id.statsBox)
        statsTitle = findViewById(R.id.statsTitle)

        typeGroup.addOnButtonCheckedListener { _, _, isChecked -> if (isChecked) updateHint() }
        updateHint()
        btnAdd.setOnClickListener { addSource() }

        sourcesAdapter = AdminSourceAdapter(
            onEdit = { s ->
                askName(this, "الاسم الظاهر للطالب", s.name, s.title) { name ->
                    store.renameSource(s.key, name)
                    refreshSources()
                    toast(if (name.isBlank()) "عاد الاسم الأصلي" else "تم تغيير الاسم")
                }
            },
            onDelete = { s -> confirmDelete(s) }
        )
        findViewById<RecyclerView>(R.id.adminSources).apply {
            layoutManager = LinearLayoutManager(this@AdminActivity)
            adapter = sourcesAdapter
            isNestedScrollingEnabled = false
        }

        findViewById<MaterialSwitch>(R.id.switchLock).apply {
            isChecked = store.lockScreen
            setOnCheckedChangeListener { _, c ->
                store.lockScreen = c
                if (c) toast("سيُثبَّت التطبيق على الشاشة عند العودة للرئيسية")
            }
        }
        findViewById<MaterialSwitch>(R.id.switchPinExit).apply {
            isChecked = store.pinOnExit
            setOnCheckedChangeListener { _, c -> store.pinOnExit = c }
        }
        findViewById<MaterialSwitch>(R.id.switchShorts).apply {
            isChecked = store.hideShorts
            setOnCheckedChangeListener { _, c ->
                store.hideShorts = c
                store.clearVideoCaches()
            }
        }

        findViewById<View>(R.id.btnChangePin).setOnClickListener {
            Pin.ask(this, "رمز PIN الجديد (4 أرقام على الأقل)") { p ->
                if (p.length >= 4) {
                    store.pin = p
                    toast("تم تغيير الرمز")
                } else toast("الرمز قصير جداً")
            }
        }

        val apiKey = findViewById<TextInputEditText>(R.id.apiKey)
        apiKey.setText(store.apiKey)
        findViewById<View>(R.id.btnSaveKey).setOnClickListener {
            store.apiKey = apiKey.text?.toString().orEmpty()
            store.clearVideoCaches()
            toast(if (store.apiKey.isEmpty()) "تم حذف المفتاح" else "تم حفظ المفتاح")
        }

        findViewById<View>(R.id.btnResetStats).setOnClickListener {
            MaterialAlertDialogBuilder(this)
                .setTitle("تصفير عداد المشاهدات؟")
                .setMessage("ستُحذف كل أعداد المشاهدات المسجلة في التطبيق.")
                .setPositiveButton("تصفير") { _, _ ->
                    store.resetWatch()
                    renderStats()
                }
                .setNegativeButton("إلغاء", null)
                .show()
        }

        setupTelegram()
        setupWhatsApp()
        refreshSources()
        renderStats()
    }

    override fun onResume() {
        super.onResume()
        renderTgStatus(Tg.state.value)
    }

    // ---------- تيليجرام ----------

    private lateinit var tgStatus: TextView
    private lateinit var tgAllowed: TextView
    private lateinit var btnTgLogin: View
    private lateinit var btnTgPick: View
    private lateinit var btnTgLogout: View

    private fun setupTelegram() {
        tgStatus = findViewById(R.id.tgStatus)
        tgAllowed = findViewById(R.id.tgAllowed)
        btnTgLogin = findViewById(R.id.btnTgLogin)
        btnTgPick = findViewById(R.id.btnTgPick)
        btnTgLogout = findViewById(R.id.btnTgLogout)
        val apiId = findViewById<TextInputEditText>(R.id.tgApiId)
        val apiHash = findViewById<TextInputEditText>(R.id.tgApiHash)
        if (store.tgApiId > 0) apiId.setText(store.tgApiId.toString())
        apiHash.setText(store.tgApiHash)

        findViewById<View>(R.id.btnTgSaveKeys).setOnClickListener {
            val id = apiId.text?.toString()?.trim()?.toIntOrNull() ?: 0
            val hash = apiHash.text?.toString()?.trim().orEmpty()
            if (id <= 0 || hash.length < 20) {
                toast("تأكد من api_id (أرقام) و api_hash (32 حرفاً)")
                return@setOnClickListener
            }
            store.tgApiId = id
            store.tgApiHash = hash
            Tg.restart(this)
            toast("تم حفظ المفاتيح")
        }
        val proxyInput = findViewById<TextInputEditText>(R.id.tgProxy)
        proxyInput.setText(store.tgProxy)
        findViewById<View>(R.id.btnTgSaveProxy).setOnClickListener {
            val link = proxyInput.text?.toString()?.trim().orEmpty()
            if (link.isNotEmpty() && Tg.parseProxy(link) == null) {
                toast("رابط البروكسي غير صحيح. يجب أن يحتوي server و port")
                return@setOnClickListener
            }
            store.tgProxy = link
            if (link.isEmpty()) {
                Tg.reapplyProxy()
                toast("أُلغي البروكسي")
                return@setOnClickListener
            }
            if (!Tg.isConfigured(this)) {
                toast("حُفظ البروكسي. احفظ مفاتيح تيليجرام ليُستخدم.")
                return@setOnClickListener
            }
            Tg.start(this)
            toast("جارٍ اختبار البروكسي…")
            lifecycleScope.launch {
                val err = Tg.testProxy(link)
                Tg.reapplyProxy()
                toast(if (err == null) "✓ البروكسي يعمل وتم تفعيله" else "✗ البروكسي لا يعمل: $err")
            }
        }

        btnTgLogin.setOnClickListener {
            if (!Tg.isConfigured(this)) {
                toast("احفظ مفاتيح تيليجرام أولاً (api_id و api_hash)")
                return@setOnClickListener
            }
            startActivity(android.content.Intent(this, TgLoginActivity::class.java))
        }
        btnTgPick.setOnClickListener { startActivity(android.content.Intent(this, TgPickActivity::class.java)) }
        btnTgLogout.setOnClickListener {
            MaterialAlertDialogBuilder(this)
                .setTitle("إلغاء ربط تيليجرام؟")
                .setMessage("سيخرج التطبيق من حساب تيليجرام، وتبقى قائمة المجموعات محفوظة.")
                .setPositiveButton("إلغاء الربط") { _, _ ->
                    lifecycleScope.launch { runCatching { Tg.logOut() } }
                }
                .setNegativeButton("رجوع", null)
                .show()
        }

        if (Tg.isConfigured(this)) Tg.start(this)
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                Tg.state.collect { renderTgStatus(it) }
            }
        }
    }

    private fun renderTgStatus(s: TgState) {
        val n = store.tgChats().size
        tgAllowed.text = if (n == 0) "لم تُختر أي مجموعة بعد" else "المجموعات والقنوات المسموحة: $n"
        val ready = s is TgState.Ready
        tgStatus.text = when (s) {
            is TgState.Off -> "غير مفعّل: أدخل مفاتيح تيليجرام بالأسفل"
            is TgState.Loading -> "جارٍ الاتصال…"
            is TgState.Ready -> "✓ الحساب مربوط"
            is TgState.Error -> "خطأ: ${s.message}"
            else -> "الحساب غير مربوط"
        }
        btnTgLogin.visibility = if (ready) View.GONE else View.VISIBLE
        btnTgPick.visibility = if (ready) View.VISIBLE else View.GONE
        btnTgLogout.visibility = if (ready) View.VISIBLE else View.GONE
    }

    // ---------- واتساب ----------

    private fun setupWhatsApp() {
        val name = findViewById<TextInputEditText>(R.id.waName)
        val link = findViewById<TextInputEditText>(R.id.waLink)
        findViewById<View>(R.id.btnWaAdd).setOnClickListener {
            val n = name.text?.toString()?.trim().orEmpty()
            val l = link.text?.toString()?.trim().orEmpty()
            if (n.isEmpty() || !l.contains("whatsapp.com")) {
                toast("اكتب اسماً ورابط دعوة صحيحاً (chat.whatsapp.com/...)")
                return@setOnClickListener
            }
            store.saveWaLinks(store.waLinks() + WaLink(n, l))
            name.setText("")
            link.setText("")
            renderWa()
            toast("أُضيف الاختصار")
        }
        renderWa()
    }

    private fun renderWa() {
        val box = findViewById<LinearLayout>(R.id.waList)
        box.removeAllViews()
        store.waLinks().forEachIndexed { i, w ->
            val row = layoutInflater.inflate(R.layout.item_admin_source, box, false)
            row.findViewById<TextView>(R.id.tag).text = "واتساب"
            row.findViewById<TextView>(R.id.name).text = w.name
            row.findViewById<View>(R.id.btnEdit).visibility = View.GONE
            row.findViewById<View>(R.id.btnDelete).setOnClickListener {
                store.saveWaLinks(store.waLinks().filterIndexed { j, _ -> j != i })
                renderWa()
            }
            box.addView(row)
        }
    }

    private fun selectedType(): SourceType = when (typeGroup.checkedButtonId) {
        R.id.typePlaylist -> SourceType.PLAYLIST
        R.id.typeVideo -> SourceType.VIDEO
        else -> SourceType.CHANNEL
    }

    private fun updateHint() {
        when (selectedType()) {
            SourceType.CHANNEL -> {
                inputLayout.hint = "رابط القناة أو @اسمها"
                inputHelp.text = "مثال: youtube.com/@name أو @name. يمكنك أيضاً لصق رابط أي فيديو من القناة."
            }
            SourceType.PLAYLIST -> {
                inputLayout.hint = "رابط قائمة التشغيل"
                inputHelp.text = "مثال: youtube.com/playlist?list=PL..."
            }
            SourceType.VIDEO -> {
                inputLayout.hint = "رابط الفيديو"
                inputHelp.text = "مثال: youtu.be/abc123xyz00"
            }
        }
    }

    private fun addSource() {
        val text = input.text?.toString()?.trim().orEmpty()
        if (text.isEmpty()) {
            toast("اكتب الرابط أو الاسم أولاً")
            return
        }
        val type = selectedType()
        btnAdd.isEnabled = false
        progress.visibility = View.VISIBLE
        lifecycleScope.launch {
            val result = runCatching { YouTube.resolve(type, text, store.apiKey) }
            btnAdd.isEnabled = true
            progress.visibility = View.GONE
            result.onSuccess { found ->
                val custom = findViewById<TextInputEditText>(R.id.customName).text?.toString()?.trim().orEmpty()
                val s = if (custom.isNotEmpty()) found.copy(name = custom) else found
                store.addSource(s)
                input.setText("")
                findViewById<TextInputEditText>(R.id.customName).setText("")
                refreshSources()
                toast("أُضيف: ${s.display}")
                if (s.type != SourceType.VIDEO) {
                    launch { runCatching { Repo.refresh(store, s) } }
                }
            }.onFailure { e ->
                val msg = if (e is UserError) e.message else "تعذّر الإضافة. تحقق من الإنترنت والرابط."
                toast(msg ?: "تعذّر الإضافة")
            }
        }
    }

    private fun confirmDelete(s: Source) {
        MaterialAlertDialogBuilder(this)
            .setTitle("حذف «${s.display}»؟")
            .setMessage("لن يظهر هذا المحتوى للطالب بعد الآن.")
            .setPositiveButton("حذف") { _, _ ->
                store.removeSource(s.key)
                refreshSources()
            }
            .setNegativeButton("إلغاء", null)
            .show()
    }

    private fun refreshSources() {
        val list = store.sources()
        sourcesAdapter.submit(list)
        sourcesTitle.text = "المحتوى المسموح (${list.size})"
        adminEmpty.visibility = if (list.isEmpty()) View.VISIBLE else View.GONE
    }

    private fun dp(v: Int) = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, v.toFloat(), resources.displayMetrics).toInt()

    private fun renderStats() {
        statsBox.removeAllViews()
        val stats = store.watchStats()
        val total = stats.sumOf { it.count }
        statsTitle.text = "المشاهدات عبر التطبيق ($total)"
        if (stats.isEmpty()) {
            statsBox.addView(TextView(this).apply {
                text = "لا توجد مشاهدات بعد."
                setTextColor(ContextCompat.getColor(this@AdminActivity, R.color.muted))
                setPadding(dp(4), dp(10), dp(4), dp(10))
            })
            return
        }
        stats.take(50).forEach { st ->
            val row = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                layoutDirection = View.LAYOUT_DIRECTION_RTL
                setPadding(0, dp(8), 0, dp(8))
            }
            row.addView(TextView(this).apply {
                text = store.videoName(st.id) ?: st.title
                maxLines = 2
                setTextColor(ContextCompat.getColor(this@AdminActivity, R.color.ink))
                textSize = 13.5f
                layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
            })
            row.addView(TextView(this).apply {
                text = viewsText(st.count).removePrefix("شوهد ")
                setTextColor(ContextCompat.getColor(this@AdminActivity, R.color.accent_dark))
                textSize = 13f
                setTypeface(typeface, android.graphics.Typeface.BOLD)
                setPadding(dp(10), 0, 0, 0)
            })
            statsBox.addView(row)
        }
    }
}
