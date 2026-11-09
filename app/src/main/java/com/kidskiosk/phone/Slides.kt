package com.kidskiosk.phone

import android.app.Activity
import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.GestureDetector
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.TextView
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import kotlin.math.abs

/**
 * Family photos the parent uploads in the control panel. The phone keeps a copy of each one,
 * and the home screen shows a Photos tile that opens a slideshow the kids can swipe through.
 */
object Slides {
    data class Photo(val id: String, val url: String)

    private val main = Handler(Looper.getMainLooper())
    @Volatile private var downloading = false

    private fun p(c: Context) = c.getSharedPreferences("slides", Context.MODE_PRIVATE)
    private fun dir(c: Context) = File(c.filesDir, "slides").apply { mkdirs() }
    private fun file(c: Context, id: String) = File(dir(c), id.replace(Regex("[^A-Za-z0-9_-]"), "_") + ".jpg")

    fun list(c: Context): List<Photo> {
        val a = runCatching { JSONArray(p(c).getString("list", "[]")) }.getOrDefault(JSONArray())
        return (0 until a.length()).map { a.getJSONObject(it) }.map { Photo(it.optString("id"), it.optString("url")) }
            .filter { it.id.isNotEmpty() && it.url.startsWith("https://") }
    }

    fun json(c: Context): JSONArray = JSONArray().apply { list(c).forEach { put(JSONObject().put("id", it.id).put("url", it.url)) } }

    fun set(c: Context, a: JSONArray) {
        p(c).edit().putString("list", a.toString()).apply()
        ensure(c)
    }

    /** The photos already on the phone, in order. */
    fun files(c: Context): List<File> = list(c).map { file(c, it.id) }.filter { it.exists() }

    /** Download new photos and delete removed ones (in the background). */
    fun ensure(c: Context) {
        val app = c.applicationContext
        val photos = list(app)
        val keep = photos.map { file(app, it.id).name }.toSet()
        dir(app).listFiles()?.forEach { if (it.name !in keep) it.delete() }
        val missing = photos.filter { !file(app, it.id).exists() }
        if (missing.isEmpty() || downloading) return
        downloading = true
        Thread {
            var got = 0
            for (ph in missing) runCatching {
                val conn = URL(ph.url).openConnection() as HttpURLConnection
                conn.connectTimeout = 20000; conn.readTimeout = 60000
                if (conn.responseCode == 200) {
                    val tmp = File(dir(app), "tmp.part")
                    conn.inputStream.use { inp -> tmp.outputStream().use { inp.copyTo(it) } }
                    tmp.renameTo(file(app, ph.id)); got++
                }
            }
            downloading = false
            if (got > 0) main.post { Remote.onChange?.invoke() }
        }.start()
    }

    /** Download one picture to a file, then call done on the main thread (only if it worked). */
    fun download(c: Context, url: String, to: File, done: () -> Unit) {
        if (!url.startsWith("https://")) return
        Thread {
            val ok = runCatching {
                val conn = URL(url).openConnection() as HttpURLConnection
                conn.connectTimeout = 20000; conn.readTimeout = 60000
                if (conn.responseCode != 200) error("download ${conn.responseCode}")
                val tmp = File(to.parentFile, to.name + ".part")
                conn.inputStream.use { inp -> tmp.outputStream().use { inp.copyTo(it) } }
                tmp.renameTo(to)
            }.getOrDefault(false)
            if (ok) main.post { done(); Remote.onChange?.invoke() }
        }.start()
    }

    fun decode(f: File, maxSide: Int): Bitmap? = runCatching {
        val o = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(f.path, o)
        var s = 1
        while (maxOf(o.outWidth, o.outHeight) / (s * 2) >= maxSide) s *= 2
        BitmapFactory.decodeFile(f.path, BitmapFactory.Options().apply { inSampleSize = s })
    }.getOrNull()
}

/** Full-screen slideshow: swipe left or right, or let it move on by itself every few seconds. */
class SlideshowActivity : Activity() {
    private val handler = Handler(Looper.getMainLooper())
    private lateinit var image: ImageView
    private lateinit var counter: TextView
    private var files: List<File> = emptyList()
    private var index = 0

    private val next = object : Runnable {
        override fun run() { show(index + 1); handler.postDelayed(this, 6000L) }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        window.statusBarColor = Color.BLACK; window.navigationBarColor = Color.BLACK
        val root = FrameLayout(this).apply { setBackgroundColor(Color.BLACK) }
        image = ImageView(this).apply { scaleType = ImageView.ScaleType.FIT_CENTER }
        root.addView(image, FrameLayout.LayoutParams(-1, -1))
        val close = text("✕", 26f, Color.WHITE, bold = true, gravity = Gravity.CENTER).apply {
            background = rounded(0x66000000, dpf(28f))
            setOnClickListener { finish() }
        }
        root.addView(close, FrameLayout.LayoutParams(dp(56), dp(56), Gravity.TOP or Gravity.START).apply { setMargins(dp(14), dp(30), 0, 0) })
        counter = text("", 15f, 0xCCFFFFFF.toInt(), gravity = Gravity.CENTER).apply {
            background = rounded(0x66000000, dpf(14f)); setPadding(dp(12), dp(4), dp(12), dp(4))
        }
        root.addView(counter, FrameLayout.LayoutParams(-2, -2, Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL).apply { bottomMargin = dp(28) })
        setContentView(root)

        val swipe = GestureDetector(this, object : GestureDetector.SimpleOnGestureListener() {
            override fun onDown(e: MotionEvent) = true
            override fun onFling(e1: MotionEvent?, e2: MotionEvent, vx: Float, vy: Float): Boolean {
                if (abs(vx) < abs(vy)) return false
                show(if (vx < 0) index + 1 else index - 1); restartTimer(); return true
            }
            override fun onSingleTapUp(e: MotionEvent): Boolean {
                show(if (e.x > image.width / 2f) index + 1 else index - 1); restartTimer(); return true
            }
        })
        image.setOnTouchListener { v, e -> if (e.action == MotionEvent.ACTION_UP) v.performClick(); swipe.onTouchEvent(e) }
    }

    override fun onResume() {
        super.onResume()
        files = Slides.files(this)
        if (files.isEmpty()) { finish(); return }
        show(index)
        restartTimer()
    }

    override fun onPause() {
        super.onPause()
        handler.removeCallbacks(next)
        // Bedtime / Shabbos: the home screen takes over again.
        if (Locks.current(this).reason != Locks.Reason.NONE) finish()
    }

    private fun restartTimer() { handler.removeCallbacks(next); handler.postDelayed(next, 6000L) }

    private fun show(i: Int) {
        if (files.isEmpty()) return
        index = ((i % files.size) + files.size) % files.size
        image.setImageBitmap(Slides.decode(files[index], 1600))
        image.alpha = 0f; image.animate().alpha(1f).setDuration(300).start()
        counter.text = "${index + 1} / ${files.size}"
        counter.visibility = if (files.size > 1) View.VISIBLE else View.GONE
    }
}
