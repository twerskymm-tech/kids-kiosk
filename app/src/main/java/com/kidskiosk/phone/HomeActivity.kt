package com.kidskiosk.phone

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.graphics.BitmapFactory
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.media.AudioManager
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import android.widget.Button
import android.widget.FrameLayout
import android.widget.GridLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.SeekBar
import android.widget.TextClock
import android.widget.TextView
import androidx.activity.OnBackPressedCallback
import androidx.appcompat.app.AppCompatActivity
import java.io.File
import java.util.Calendar

/** The kids' home screen: greeting and clock over the family photo, big app tiles, call buttons, volume and brightness. */
class HomeActivity : AppCompatActivity() {
    companion object {
        @Volatile var visible = false
        @Volatile var lastCallAt = 0L
        private val TILE_COLORS = intArrayOf(
            0xFFFF6B6B.toInt(), 0xFF4D96FF.toInt(), 0xFF6BCB77.toInt(), 0xFFFFB84C.toInt(),
            0xFFB980F0.toInt(), 0xFFFF8FB1.toInt(), 0xFF00C2CB.toInt(), 0xFFF97B22.toInt(),
        )
        fun photoFile(c: Context) = File(c.filesDir, "background.jpg")
    }

    private val handler = Handler(Looper.getMainLooper())
    private lateinit var bg: ImageView
    private lateinit var greeting: TextView
    private lateinit var grid: GridLayout
    private lateinit var main: View
    private lateinit var lockLayer: LinearLayout
    private lateinit var lockTitle: TextView
    private lateinit var lockSub: TextView
    private lateinit var setupNote: TextView
    private lateinit var callRow: LinearLayout
    private lateinit var lockCallRow: LinearLayout
    private var shownPhoto = -1L
    private var cornerTaps = 0
    private var firstTapAt = 0L

    private val refresh = object : Runnable {
        override fun run() { updateLock(); handler.postDelayed(this, 15_000L) }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_DRAWS_SYSTEM_BARS_BACKGROUNDS)
        window.statusBarColor = Color.BLACK
        window.navigationBarColor = Color.BLACK
        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() { /* this is home: nowhere to go back to */ }
        })
        setContentView(build())
    }

    override fun onResume() {
        super.onResume()
        visible = true
        Prefs.setParentUntil(this, 0L)
        Kiosk.apply(this)
        Kiosk.lock(this)
        loadPhoto()
        greeting.text = greetingText()
        fillTiles()
        fillCalls(callRow); fillCalls(lockCallRow)
        setupNote.visibility = if (Kiosk.isOwner(this)) View.GONE else View.VISIBLE
        handler.removeCallbacks(refresh); handler.post(refresh)
        if (!Prefs.hasPin(this)) PinDialog.create(this) { openParent() }
    }

    override fun onPause() {
        super.onPause()
        visible = false
        handler.removeCallbacks(refresh)
    }

    // ── Layout ──

    private fun build(): View {
        val root = FrameLayout(this)
        bg = ImageView(this).apply { scaleType = ImageView.ScaleType.CENTER_CROP }
        root.addView(bg, FrameLayout.LayoutParams(MATCH, MATCH))
        // soft dark shading so white text reads on any photo
        root.addView(View(this).apply {
            background = GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM,
                intArrayOf(0x99000000.toInt(), 0x33000000, 0x99000000.toInt()))
        }, FrameLayout.LayoutParams(MATCH, MATCH))

        val col = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(28), dp(20), dp(16))
        }
        greeting = text("", 26f, Color.WHITE, bold = true)
        col.addView(greeting)
        col.addView(TextClock(this).apply {
            textSize = 64f; setTextColor(Color.WHITE)
            format12Hour = "h:mm"; format24Hour = "H:mm"
            setTypeface(typeface, android.graphics.Typeface.BOLD)
        })
        col.addView(TextClock(this).apply {
            textSize = 18f; setTextColor(0xDDFFFFFF.toInt())
            format12Hour = "EEEE, MMMM d"; format24Hour = "EEEE, MMMM d"
        })

        grid = GridLayout(this).apply { columnCount = 2; useDefaultMargins = false }
        val scroll = ScrollView(this).apply { isFillViewport = true; addView(grid, FrameLayout.LayoutParams(MATCH, WRAP)) }
        col.addView(scroll, lp(MATCH, 0, 1f).apply { topMargin = dp(20) })

        callRow = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        col.addView(callRow, lp(MATCH, WRAP).apply { topMargin = dp(12) })
        col.addView(controls(), lp(MATCH, WRAP).apply { topMargin = dp(12) })

        setupNote = text("Setup not finished: the phone isn't locked yet. See the setup guide.", 13f, 0xFFFFE08A.toInt(), gravity = Gravity.CENTER)
        col.addView(setupNote, lp(MATCH, WRAP).apply { topMargin = dp(8) })
        main = col
        root.addView(col, FrameLayout.LayoutParams(MATCH, MATCH))

        // Bedtime / Shabbos screen
        lockLayer = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            setPadding(dp(28), dp(28), dp(28), dp(28))
            background = GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM, intArrayOf(0xFF141E46.toInt(), 0xFF2B2A6B.toInt()))
            isClickable = true
            visibility = View.GONE
        }
        lockTitle = text("", 40f, Color.WHITE, bold = true, gravity = Gravity.CENTER)
        lockSub = text("", 18f, 0xDDFFFFFF.toInt(), gravity = Gravity.CENTER)
        lockLayer.addView(lockTitle)
        lockLayer.addView(lockSub, lp(MATCH, WRAP).apply { topMargin = dp(12) })
        lockCallRow = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        lockLayer.addView(lockCallRow, lp(MATCH, WRAP).apply { topMargin = dp(48) })
        root.addView(lockLayer, FrameLayout.LayoutParams(MATCH, MATCH))

        // Hidden parent corner (top right): 5 quick taps opens the PIN pad
        root.addView(View(this).apply {
            setOnClickListener { cornerTap() }
        }, FrameLayout.LayoutParams(dp(72), dp(72), Gravity.TOP or Gravity.END))
        return root
    }

    private fun controls(): View {
        val box = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(10), dp(16), dp(10))
            background = rounded(0x55000000, dpf(20f))
        }
        val audio = getSystemService(Context.AUDIO_SERVICE) as AudioManager
        val maxVol = audio.getStreamMaxVolume(AudioManager.STREAM_MUSIC)
        box.addView(slider("Volume", maxVol, audio.getStreamVolume(AudioManager.STREAM_MUSIC)) { v ->
            runCatching { audio.setStreamVolume(AudioManager.STREAM_MUSIC, v, 0) }
        })
        val cur = runCatching { Settings.System.getInt(contentResolver, Settings.System.SCREEN_BRIGHTNESS) }.getOrDefault(128)
        box.addView(slider("Brightness", 255, cur) { v ->
            if (!Kiosk.setBrightness(this, v)) {
                window.attributes = window.attributes.apply { screenBrightness = v.coerceAtLeast(5) / 255f }
            }
        })
        return box
    }

    private fun slider(label: String, max: Int, value: Int, onChange: (Int) -> Unit): View {
        val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        row.addView(text(label, 15f, Color.WHITE, bold = true), lp(dp(100), WRAP))
        row.addView(SeekBar(this).apply {
            this.max = max; progress = value
            setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(s: SeekBar, p: Int, fromUser: Boolean) { if (fromUser) onChange(p) }
                override fun onStartTrackingTouch(s: SeekBar) {}
                override fun onStopTrackingTouch(s: SeekBar) {}
            })
        }, lp(0, dp(44), 1f))
        return row
    }

    // ── Content ──

    private fun greetingText(): String {
        val h = Calendar.getInstance().get(Calendar.HOUR_OF_DAY)
        val part = when (h) { in 4..11 -> "Good morning"; in 12..16 -> "Good afternoon"; else -> "Good evening" }
        val names = Prefs.kidNames(this).trim()
        return if (names.isEmpty()) "$part!" else "$part, $names!"
    }

    private fun loadPhoto() {
        val v = Prefs.photoVersion(this)
        if (v == shownPhoto) return
        shownPhoto = v
        val f = photoFile(this)
        val bmp = if (f.exists()) runCatching {
            val o = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeFile(f.path, o)
            var s = 1
            while (o.outWidth / (s * 2) >= 1080 && o.outHeight / (s * 2) >= 1080) s *= 2
            BitmapFactory.decodeFile(f.path, BitmapFactory.Options().apply { inSampleSize = s })
        }.getOrNull() else null
        if (bmp != null) bg.setImageBitmap(bmp)
        else bg.setImageDrawable(GradientDrawable(GradientDrawable.Orientation.TL_BR,
            intArrayOf(0xFF7F5AF0.toInt(), 0xFFFF6B9A.toInt(), 0xFFFFB86B.toInt())))
    }

    private fun fillTiles() {
        grid.removeAllViews()
        val pm = packageManager
        val apps = Kiosk.allowedApps(this).mapNotNull { pkg ->
            val launch = pm.getLaunchIntentForPackage(pkg) ?: return@mapNotNull null
            val label = runCatching { pm.getApplicationLabel(pm.getApplicationInfo(pkg, 0)).toString() }.getOrDefault(pkg)
            Triple(pkg, label, launch)
        }.sortedBy { it.second.lowercase() }
        if (apps.isEmpty()) {
            grid.addView(text("No apps yet. A parent can add them in parent mode.", 18f, Color.WHITE, gravity = Gravity.CENTER),
                GridLayout.LayoutParams(GridLayout.spec(0), GridLayout.spec(0, 2, 1f)).apply { width = 0 })
            return
        }
        apps.forEachIndexed { i, (pkg, label, launch) ->
            val tile = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                gravity = Gravity.CENTER
                setPadding(dp(8), dp(16), dp(8), dp(16))
                background = rounded(TILE_COLORS[i % TILE_COLORS.size], dpf(28f))
                elevation = dpf(6f)
                setOnClickListener { open(launch) }
            }
            tile.addView(ImageView(this).apply {
                setImageDrawable(runCatching { pm.getApplicationIcon(pkg) }.getOrNull())
                background = rounded(Color.WHITE, dpf(22f))
                setPadding(dp(8), dp(8), dp(8), dp(8))
            }, lp(dp(84), dp(84)))
            tile.addView(text(label, 20f, Color.WHITE, bold = true, gravity = Gravity.CENTER).apply { maxLines = 2 },
                lp(MATCH, WRAP).apply { topMargin = dp(10) })
            val glp = GridLayout.LayoutParams(GridLayout.spec(i / 2), GridLayout.spec(i % 2, 1f)).apply {
                width = 0; height = dp(178)
                setMargins(dp(8), dp(8), dp(8), dp(8))
            }
            grid.addView(tile, glp)
        }
    }

    private fun open(launch: Intent) {
        try { startActivity(Intent(launch).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
        catch (e: Exception) { toast("That app can't open right now") }
    }

    private fun fillCalls(row: LinearLayout) {
        row.removeAllViews()
        for (i in 0..1) {
            val number = Prefs.callNumber(this, i).trim()
            if (number.isEmpty()) continue
            val b = Button(this).apply {
                text = "Call ${Prefs.callName(this@HomeActivity, i)}"
                isAllCaps = false
                textSize = 20f
                setTextColor(Color.WHITE)
                setTypeface(typeface, android.graphics.Typeface.BOLD)
                background = rounded(0xFF22B573.toInt(), dpf(32f))
                minHeight = dp(64)
                setOnClickListener { call(number) }
            }
            row.addView(b, lp(0, dp(64), 1f).apply { setMargins(dp(6), 0, dp(6), 0) })
        }
        row.visibility = if (row.childCount == 0) View.GONE else View.VISIBLE
    }

    /** Calls over Wi-Fi through the Google Voice app. */
    private fun call(number: String) {
        if (!Kiosk.isInstalled(this, Kiosk.VOICE)) { toast("Google Voice isn't installed yet. Ask a parent."); return }
        val uri = Uri.parse("tel:" + Uri.encode(number))
        val tries = listOf(Intent.ACTION_CALL, Intent.ACTION_DIAL, Intent.ACTION_VIEW)
        for (action in tries) {
            try {
                startActivity(Intent(action, uri).setPackage(Kiosk.VOICE).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                lastCallAt = System.currentTimeMillis()
                return
            } catch (e: ActivityNotFoundException) { /* try the next way */ }
            catch (e: SecurityException) { /* try the next way */ }
        }
        // Last resort: just open Voice
        packageManager.getLaunchIntentForPackage(Kiosk.VOICE)?.let {
            lastCallAt = System.currentTimeMillis(); open(it)
        } ?: toast("Couldn't start the call")
    }

    // ── Locks ──

    private fun updateLock() {
        val s = Locks.current(this)
        if (s.reason == Locks.Reason.NONE) {
            lockLayer.visibility = View.GONE; main.visibility = View.VISIBLE
            greeting.text = greetingText()
            return
        }
        lockTitle.text = when (s.reason) {
            Locks.Reason.BEDTIME -> "Good night"
            Locks.Reason.SHABBOS -> "Good Shabbos"
            else -> "Good Yom Tov"
        }
        lockSub.text = when (s.reason) {
            Locks.Reason.BEDTIME -> "The phone opens again at ${timeText(this, s.until)}."
            else -> "The phone opens again after Havdalah, ${dayTimeText(this, s.until)}."
        }
        lockLayer.visibility = View.VISIBLE; main.visibility = View.INVISIBLE
    }

    // ── Parent ──

    private fun cornerTap() {
        val now = System.currentTimeMillis()
        if (now - firstTapAt > 3000L) { firstTapAt = now; cornerTaps = 0 }
        cornerTaps++
        if (cornerTaps >= 5) { cornerTaps = 0; PinDialog.ask(this) { openParent() } }
    }

    private fun openParent() {
        Prefs.setParentUntil(this, System.currentTimeMillis() + ParentActivity.OPEN_MS)
        startActivity(Intent(this, ParentActivity::class.java))
    }
}
