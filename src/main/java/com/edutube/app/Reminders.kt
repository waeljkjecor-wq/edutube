package com.edutube.app

import android.Manifest
import android.app.AlarmManager
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import java.util.Calendar

/** جدولة تذكيرات الامتحانات كإشعارات على الهاتف. */
object Reminders {
    const val CHANNEL = "exams"

    fun ensureChannel(ctx: Context) {
        val nm = ctx.getSystemService(NotificationManager::class.java) ?: return
        nm.createNotificationChannel(
            NotificationChannel(CHANNEL, "تذكير الامتحانات", NotificationManager.IMPORTANCE_HIGH)
        )
    }

    fun triggerTime(e: Exam, code: String): Long = when (code) {
        "w" -> e.at - 7 * DAY_MS
        "d2" -> e.at - 2 * DAY_MS
        "d1" -> e.at - DAY_MS
        "m" -> {
            val c = Calendar.getInstance().apply {
                timeInMillis = e.at
                set(Calendar.HOUR_OF_DAY, 7); set(Calendar.MINUTE, 0); set(Calendar.SECOND, 0); set(Calendar.MILLISECOND, 0)
            }
            if (c.timeInMillis < e.at) c.timeInMillis else e.at - 60 * 60 * 1000L
        }
        else -> 0L
    }

    private fun pending(ctx: Context, requestCode: Int, examId: Long, code: String): PendingIntent {
        val intent = Intent(ctx, ReminderReceiver::class.java)
            .putExtra("exam_id", examId)
            .putExtra("code", code)
        return PendingIntent.getBroadcast(
            ctx, requestCode, intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
    }

    /** يلغي كل التذكيرات القديمة ويعيد جدولة الحالية. */
    fun scheduleAll(ctx: Context) {
        val app = ctx.applicationContext
        val am = app.getSystemService(AlarmManager::class.java) ?: return
        val store = Store(app)

        store.raw("alarm_codes")?.split(",")?.mapNotNull { it.toIntOrNull() }?.forEach { rc ->
            am.cancel(pending(app, rc, 0L, ""))
        }

        val codes = mutableListOf<Int>()
        val now = System.currentTimeMillis()
        for (e in store.upcomingExams()) {
            for (code in e.reminders) {
                val t = triggerTime(e, code)
                if (t <= now) continue
                val rc = (e.id.toString() + code).hashCode()
                am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, t, pending(app, rc, e.id, code))
                codes.add(rc)
            }
        }
        store.putRaw("alarm_codes", codes.joinToString(","))
    }

    fun canNotify(ctx: Context): Boolean =
        Build.VERSION.SDK_INT < 33 ||
            ContextCompat.checkSelfPermission(ctx, Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED
}

class ReminderReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val id = intent.getLongExtra("exam_id", -1L)
        val exam = Store(context).exams().firstOrNull { it.id == id } ?: return
        if (!Reminders.canNotify(context)) return
        Reminders.ensureChannel(context)

        val open = PendingIntent.getActivity(
            context, 0,
            Intent(context, MainActivity::class.java).putExtra("tab", "exams")
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val left = daysLeftText(daysUntil(exam.at))
        val type = if (exam.type.isBlank()) "امتحان" else exam.type
        val n = NotificationCompat.Builder(context, Reminders.CHANNEL)
            .setSmallIcon(R.drawable.ic_bell)
            .setContentTitle("تذكير: $type ${exam.subject} $left")
            .setContentText(fmtExamDate(exam.at))
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setContentIntent(open)
            .setAutoCancel(true)
            .build()
        try {
            NotificationManagerCompat.from(context).notify((id.toString() + intent.getStringExtra("code")).hashCode(), n)
        } catch (_: SecurityException) {
        }
    }
}

class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action == Intent.ACTION_BOOT_COMPLETED) Reminders.scheduleAll(context)
    }
}
