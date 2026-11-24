package com.edutube.app

import android.content.pm.ActivityInfo
import android.graphics.Color
import android.os.Bundle
import android.view.Gravity
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.ImageButton
import android.widget.ImageView
import android.widget.MediaController
import android.widget.VideoView
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import coil.load
import java.io.File

/** عرض صورة أو فيديو من تيليجرام داخل التطبيق بملء الشاشة. */
class MediaActivity : AppCompatActivity() {
    private var video: VideoView? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val path = intent.getStringExtra("path")
        if (path.isNullOrEmpty() || !File(path).exists()) {
            finish()
            return
        }
        requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_FULL_SENSOR
        WindowCompat.getInsetsController(window, window.decorView).apply {
            systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
            hide(WindowInsetsCompat.Type.systemBars())
        }

        val root = FrameLayout(this).apply { setBackgroundColor(Color.BLACK) }
        val full = FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)

        if (intent.getStringExtra("type") == "video") {
            val v = VideoView(this)
            val controller = MediaController(this)
            controller.setAnchorView(v)
            v.setMediaController(controller)
            v.setVideoPath(path)
            v.setOnPreparedListener { it.start() }
            root.addView(v, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.CENTER))
            video = v
        } else {
            val img = ImageView(this).apply { scaleType = ImageView.ScaleType.FIT_CENTER }
            img.load(File(path))
            root.addView(img, full)
        }

        val size = (44 * resources.displayMetrics.density).toInt()
        val margin = (16 * resources.displayMetrics.density).toInt()
        val close = ImageButton(this).apply {
            setImageResource(R.drawable.ic_back)
            setColorFilter(Color.WHITE)
            setBackgroundResource(R.drawable.bg_icon_btn_dark)
            contentDescription = "رجوع"
            setOnClickListener { finish() }
        }
        root.addView(close, FrameLayout.LayoutParams(size, size, Gravity.TOP or Gravity.START).apply {
            setMargins(margin, margin, margin, margin)
        })
        setContentView(root)
    }

    override fun onPause() {
        super.onPause()
        video?.pause()
    }
}
