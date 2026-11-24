package com.edutube.app

import android.content.Intent
import android.os.Bundle
import android.text.InputType
import android.view.View
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.google.android.material.appbar.MaterialToolbar
import com.google.android.material.button.MaterialButton
import com.google.android.material.progressindicator.LinearProgressIndicator
import com.google.android.material.textfield.TextInputEditText
import com.google.android.material.textfield.TextInputLayout
import kotlinx.coroutines.launch

/** ربط حساب تيليجرام: رقم الهاتف ← رمز التحقق ← كلمة مرور التحقق بخطوتين (إن وُجدت). */
class TgLoginActivity : AppCompatActivity() {
    private lateinit var titleView: TextView
    private lateinit var help: TextView
    private lateinit var inputLayout: TextInputLayout
    private lateinit var input: TextInputEditText
    private lateinit var btn: MaterialButton
    private lateinit var progress: LinearProgressIndicator
    private var current: TgState = TgState.Loading

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_tg_login)
        findViewById<MaterialToolbar>(R.id.toolbar).setNavigationOnClickListener { finish() }
        titleView = findViewById(R.id.loginTitle)
        help = findViewById(R.id.loginHelp)
        inputLayout = findViewById(R.id.loginInputLayout)
        input = findViewById(R.id.loginInput)
        btn = findViewById(R.id.loginBtn)
        progress = findViewById(R.id.loginProgress)

        btn.setOnClickListener { submit() }
        Tg.start(this)
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                Tg.state.collect { render(it) }
            }
        }
    }

    private fun render(s: TgState) {
        val changed = s::class != current::class
        current = s
        setBusy(false)
        when (s) {
            is TgState.WaitPhone -> {
                titleView.text = "1. رقم هاتف الطالب"
                help.text = "اكتب رقم الهاتف المسجّل في تيليجرام مع رمز الدولة، مثل +9647701234567"
                inputLayout.hint = "رقم الهاتف"
                input.inputType = InputType.TYPE_CLASS_PHONE
                showInput(true)
            }
            is TgState.WaitCode -> {
                titleView.text = "2. رمز التحقق"
                help.text = "أرسل تيليجرام رمزاً إلى ${s.phone}. يصل عادةً داخل تطبيق تيليجرام على الهاتف، أو برسالة SMS."
                inputLayout.hint = "الرمز"
                input.inputType = InputType.TYPE_CLASS_NUMBER
                showInput(true)
            }
            is TgState.WaitPassword -> {
                titleView.text = "3. كلمة مرور التحقق بخطوتين"
                help.text = "هذا الحساب محمي بكلمة مرور إضافية." + if (s.hint.isNotBlank()) " التلميح: ${s.hint}" else ""
                inputLayout.hint = "كلمة المرور"
                input.inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
                showInput(true)
            }
            is TgState.Ready -> {
                titleView.text = "✓ تم ربط الحساب"
                help.text = "الآن اختر المجموعات والقنوات التي يراها الطالب."
                showInput(false)
                btn.visibility = View.VISIBLE
                btn.text = "اختيار المجموعات"
            }
            is TgState.Error -> {
                titleView.text = "تعذّر الربط"
                help.text = s.message
                showInput(false)
                btn.visibility = View.GONE
            }
            is TgState.Off -> {
                titleView.text = "مفاتيح تيليجرام غير موجودة"
                help.text = "ارجع إلى لوحة المشرف وأدخل api_id و api_hash ثم احفظها."
                showInput(false)
                btn.visibility = View.GONE
            }
            else -> {
                titleView.text = "جارٍ الاتصال بتيليجرام…"
                help.text = ""
                showInput(false)
                btn.visibility = View.GONE
                setBusy(true)
            }
        }
        if (changed) input.setText("")
    }

    private fun showInput(show: Boolean) {
        inputLayout.visibility = if (show) View.VISIBLE else View.GONE
        btn.visibility = if (show) View.VISIBLE else View.GONE
        btn.text = "متابعة"
        if (show) input.requestFocus()
    }

    private fun setBusy(busy: Boolean) {
        progress.visibility = if (busy) View.VISIBLE else View.GONE
        btn.isEnabled = !busy
    }

    private fun submit() {
        val s = current
        if (s is TgState.Ready) {
            startActivity(Intent(this, TgPickActivity::class.java))
            finish()
            return
        }
        val text = input.text?.toString()?.trim().orEmpty()
        if (text.isEmpty()) return
        setBusy(true)
        lifecycleScope.launch {
            val r = runCatching {
                when (s) {
                    is TgState.WaitPhone -> Tg.sendPhone(text.replace(" ", ""))
                    is TgState.WaitCode -> Tg.sendCode(text)
                    is TgState.WaitPassword -> Tg.sendPassword(text)
                    else -> {}
                }
            }
            setBusy(false)
            r.onFailure { e -> toast(friendly(e.message ?: "")) }
        }
    }

    private fun friendly(msg: String): String = when {
        msg.contains("PHONE_NUMBER_INVALID") -> "رقم الهاتف غير صحيح"
        msg.contains("PHONE_CODE_INVALID") -> "الرمز غير صحيح"
        msg.contains("PHONE_CODE_EXPIRED") -> "انتهت صلاحية الرمز، ألغِ وأعد المحاولة"
        msg.contains("PASSWORD_HASH_INVALID") -> "كلمة المرور غير صحيحة"
        msg.contains("FLOOD") -> "محاولات كثيرة، انتظر قليلاً ثم أعد المحاولة"
        else -> "خطأ: $msg"
    }
}
