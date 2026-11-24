package com.edutube.app

import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.pdf.PdfRenderer
import android.os.Bundle
import android.os.ParcelFileDescriptor
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.HorizontalScrollView
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.button.MaterialButton
import java.io.File

/** قارئ PDF داخل التطبيق (حتى لا يخرج الطالب إلى تطبيق آخر). */
class PdfActivity : AppCompatActivity() {
    private var pfd: ParcelFileDescriptor? = null
    private var renderer: PdfRenderer? = null
    private var scale = 1f
    private lateinit var list: RecyclerView
    private var screenW = 0

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val path = intent.getStringExtra("path")
        val file = path?.let { File(it) }
        if (file == null || !file.exists()) {
            finish()
            return
        }
        try {
            pfd = ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY)
            renderer = PdfRenderer(pfd!!)
        } catch (_: Exception) {
            toast("تعذّر فتح ملف PDF")
            finish()
            return
        }
        screenW = resources.displayMetrics.widthPixels
        val d = resources.displayMetrics.density

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Color.parseColor("#E6E8F0"))
            layoutDirection = View.LAYOUT_DIRECTION_RTL
        }
        val bar = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setBackgroundColor(ContextCompat.getColor(this@PdfActivity, R.color.ink))
            setPadding((8 * d).toInt(), (6 * d).toInt(), (8 * d).toInt(), (6 * d).toInt())
        }
        val title = TextView(this).apply {
            text = intent.getStringExtra("title") ?: "ملف PDF"
            setTextColor(Color.WHITE)
            textSize = 15f
            maxLines = 1
            setPadding((8 * d).toInt(), 0, (8 * d).toInt(), 0)
        }
        val zoomOut = MaterialButton(this).apply { text = "−"; setOnClickListener { zoom(-0.25f) } }
        val zoomIn = MaterialButton(this).apply { text = "+"; setOnClickListener { zoom(0.25f) } }
        val close = MaterialButton(this).apply { text = "إغلاق"; setOnClickListener { finish() } }
        bar.addView(close)
        bar.addView(title, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        bar.addView(zoomOut)
        bar.addView(zoomIn, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
            marginStart = (6 * d).toInt()
        })
        root.addView(bar)

        val hs = HorizontalScrollView(this)
        list = RecyclerView(this).apply {
            layoutManager = LinearLayoutManager(this@PdfActivity)
            adapter = PageAdapter()
        }
        hs.addView(list, ViewGroup.LayoutParams(screenW, ViewGroup.LayoutParams.MATCH_PARENT))
        root.addView(hs, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))
        setContentView(root)
    }

    private fun zoom(delta: Float) {
        scale = (scale + delta).coerceIn(1f, 2f)
        list.layoutParams = list.layoutParams.apply { width = (screenW * scale).toInt() }
        list.adapter?.notifyDataSetChanged()
    }

    private inner class PageAdapter : RecyclerView.Adapter<RecyclerView.ViewHolder>() {
        override fun getItemCount() = renderer?.pageCount ?: 0

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RecyclerView.ViewHolder {
            val iv = ImageView(parent.context).apply {
                adjustViewBounds = true
                scaleType = ImageView.ScaleType.FIT_XY
                setBackgroundColor(Color.WHITE)
            }
            iv.layoutParams = RecyclerView.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
                bottomMargin = (8 * resources.displayMetrics.density).toInt()
            }
            return object : RecyclerView.ViewHolder(iv) {}
        }

        override fun onBindViewHolder(holder: RecyclerView.ViewHolder, position: Int) {
            val r = renderer ?: return
            val w = (screenW * scale).toInt()
            val bmp = synchronized(r) {
                val page = r.openPage(position)
                val h = (w.toFloat() * page.height / page.width).toInt().coerceAtLeast(1)
                val b = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
                b.eraseColor(Color.WHITE)
                page.render(b, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
                page.close()
                b
            }
            (holder.itemView as ImageView).setImageBitmap(bmp)
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        runCatching { renderer?.close() }
        runCatching { pfd?.close() }
    }
}
