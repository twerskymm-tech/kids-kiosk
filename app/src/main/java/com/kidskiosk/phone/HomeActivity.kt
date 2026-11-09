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
import android.widget.Button
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
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
    private lateinit var grid: TileGrid
    private lateinit var board: LinearLayout
    private lateinit var greetingNames: TextView
    private lateinit var main: View
    private lateinit var lockLayer: LinearLayout
    private lateinit var lockFrame: FrameLayout
    private lateinit var lockBg: ImageView
    private var shownScene = ""
    private lateinit var lockTitle: TextView
    private lateinit var lockSub: TextView
    private lateinit var zmanim: LinearLayout
    private lateinit var setupNote: TextView
    private lateinit var callRow: LinearLayout
    private lateinit var lockCallRow: LinearLayout
    private var shownPhoto = -1L
    private var cornerTaps = 0
    private var firstTapAt = 0L

    private val refresh = object : Runnable {
        override fun run() { updateLock(); fillBoard(); handler.postDelayed(this, 30_000L) }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
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
        (application as App).endBluetooth()
        Kiosk.apply(this)
        Kiosk.lock(this)
        loadPhoto()
        greeting.text = greetingText()
        fillTiles()
        fillBoard()
        fillCalls(callRow); fillCalls(lockCallRow)
        setupNote.visibility = if (Kiosk.isOwner(this)) View.GONE else View.VISIBLE
        handler.removeCallbacks(refresh); handler.post(refresh)
        if (!Prefs.hasPin(this)) PinDialog.create(this) { openParent() }
        Updater.autoCheck(this)
        Remote.onChange = { if (visible) reloadAll() }
    }

    /** A change came in from the parent's control panel. */
    private fun reloadAll() {
        Kiosk.apply(this)
        greeting.text = greetingText()
        fillTiles()
        fillBoard()
        fillCalls(callRow); fillCalls(lockCallRow)
        updateLock()
    }

    override fun onPause() {
        super.onPause()
        visible = false
        Remote.onChange = null
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
            gravity = Gravity.CENTER_HORIZONTAL
            setPadding(dp(20), dp(24), dp(20), dp(16))
        }
        greeting = text("", 22f, 0xEEFFFFFF.toInt(), bold = true, gravity = Gravity.CENTER)
        greetingNames = text("", 26f, Color.WHITE, bold = true, gravity = Gravity.CENTER)
        col.addView(greeting, lp(MATCH, WRAP))
        col.addView(greetingNames, lp(MATCH, WRAP))
        col.addView(TextClock(this).apply {
            textSize = 72f; setTextColor(Color.WHITE); gravity = Gravity.CENTER
            format12Hour = "h:mm"; format24Hour = "H:mm"
            setTypeface(typeface, android.graphics.Typeface.BOLD)
        }, lp(MATCH, WRAP).apply { topMargin = dp(4) })
        col.addView(TextClock(this).apply {
            textSize = 18f; setTextColor(0xDDFFFFFF.toInt()); gravity = Gravity.CENTER
            format12Hour = "EEEE, MMMM d"; format24Hour = "EEEE, MMMM d"
        }, lp(MATCH, WRAP))

        board = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        col.addView(board, lp(MATCH, WRAP).apply { topMargin = dp(14) })

        grid = TileGrid(this)
        col.addView(grid, lp(MATCH, 0, 1f).apply { topMargin = dp(14) })

        callRow = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        col.addView(callRow, lp(MATCH, WRAP).apply { topMargin = dp(12) })
        col.addView(controls(), lp(MATCH, WRAP).apply { topMargin = dp(12) })

        setupNote = text("Setup not finished: the phone isn't locked yet. See the setup guide.", 13f, 0xFFFFE08A.toInt(), gravity = Gravity.CENTER)
        col.addView(setupNote, lp(MATCH, WRAP).apply { topMargin = dp(8) })
        main = col
        root.addView(col, FrameLayout.LayoutParams(MATCH, MATCH))

        // Bedtime / Shabbos screen
        lockFrame = FrameLayout(this).apply { isClickable = true; visibility = View.GONE }
        lockBg = ImageView(this).apply { scaleType = ImageView.ScaleType.CENTER_CROP }
        lockFrame.addView(lockBg, FrameLayout.LayoutParams(MATCH, MATCH))
        // darker at the top so the clock and times read on any picture
        lockFrame.addView(View(this).apply {
            background = GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM,
                intArrayOf(0xB3000000.toInt(), 0x66000000, 0x00000000, 0x00000000))
        }, FrameLayout.LayoutParams(MATCH, MATCH))
        lockLayer = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            setPadding(dp(24), dp(20), dp(24), dp(24))
        }
        lockLayer.addView(TextClock(this).apply {
            textSize = 84f; setTextColor(Color.WHITE); gravity = Gravity.CENTER
            format12Hour = "h:mm"; format24Hour = "H:mm"
            setTypeface(typeface, android.graphics.Typeface.BOLD)
        }, lp(MATCH, WRAP))
        lockTitle = text("", 26f, Color.WHITE, bold = true, gravity = Gravity.CENTER)
        lockSub = text("", 18f, 0xDDFFFFFF.toInt(), gravity = Gravity.CENTER)
        lockLayer.addView(lockTitle, lp(MATCH, WRAP).apply { topMargin = dp(8) })
        lockLayer.addView(lockSub, lp(MATCH, WRAP).apply { topMargin = dp(6) })
        zmanim = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(14), dp(20), dp(14))
            background = rounded(0x66000000, dpf(20f))
            layoutDirection = View.LAYOUT_DIRECTION_RTL
        }
        lockLayer.addView(zmanim, lp(MATCH, WRAP).apply { topMargin = dp(16) })
        lockLayer.addView(View(this), lp(MATCH, 0, 1f))   // the picture shows here
        lockCallRow = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        lockLayer.addView(lockCallRow, lp(MATCH, WRAP))
        lockFrame.addView(lockLayer, FrameLayout.LayoutParams(MATCH, MATCH))
        root.addView(lockFrame, FrameLayout.LayoutParams(MATCH, MATCH))

        // Hidden parent corner (top right): 5 quick taps opens the PIN pad
        root.addView(View(this).apply {
            setOnClickListener { cornerTap() }
        }, FrameLayout.LayoutParams(dp(72), dp(72), Gravity.TOP or Gravity.END))
        return root
    }

    /** One slim row: volume, brightness, Bluetooth. */
    private fun controls(): View {
        val box = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(12), dp(2), dp(6), dp(2))
            background = rounded(0x55000000, dpf(24f))
        }
        val audio = getSystemService(Context.AUDIO_SERVICE) as AudioManager
        val maxVol = audio.getStreamMaxVolume(AudioManager.STREAM_MUSIC)
        box.addView(text("\uD83D\uDD0A", 18f, Color.WHITE))
        box.addView(seek(maxVol, audio.getStreamVolume(AudioManager.STREAM_MUSIC)) { v ->
            runCatching { audio.setStreamVolume(AudioManager.STREAM_MUSIC, v, 0) }
        }, lp(0, dp(44), 1f))
        box.addView(text("\u2600\uFE0F", 18f, Color.WHITE))
        val cur = runCatching { Settings.System.getInt(contentResolver, Settings.System.SCREEN_BRIGHTNESS) }.getOrDefault(128)
        box.addView(seek(255, cur) { v ->
            if (!Kiosk.setBrightness(this, v)) {
                window.attributes = window.attributes.apply { screenBrightness = v.coerceAtLeast(5) / 255f }
            }
        }, lp(0, dp(44), 1f))
        box.addView(Button(this).apply {
            text = "Bluetooth"; isAllCaps = false; textSize = 13f
            setTextColor(Color.WHITE)
            setTypeface(typeface, android.graphics.Typeface.BOLD)
            background = rounded(0xFF2F6FED.toInt(), dpf(20f))
            minHeight = 0; minimumHeight = 0; minWidth = 0; minimumWidth = 0
            setPadding(dp(12), 0, dp(12), 0)
            setOnClickListener { (application as App).openBluetooth() }
        }, lp(WRAP, dp(38)).apply { marginStart = dp(4) })
        return box
    }

    private fun seek(max: Int, value: Int, onChange: (Int) -> Unit) = SeekBar(this).apply {
        this.max = max; progress = value
        setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(s: SeekBar, p: Int, fromUser: Boolean) { if (fromUser) onChange(p) }
            override fun onStartTrackingTouch(s: SeekBar) {}
            override fun onStopTrackingTouch(s: SeekBar) {}
        })
    }

    // ── Content ──

    private fun greetingText(): String {
        val h = Calendar.getInstance().get(Calendar.HOUR_OF_DAY)
        val part = when (h) { in 4..11 -> "Good morning"; in 12..16 -> "Good afternoon"; else -> "Good evening" }
        val names = Prefs.kidNames(this).trim()
        greetingNames.text = names
        greetingNames.visibility = if (names.isEmpty()) View.GONE else View.VISIBLE
        return if (names.isEmpty()) "$part!" else part
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
        }.sortedWith(compareBy({ !it.second.lowercase().contains("greenlight") }, { it.second.lowercase() }))
        // Greenlight shared by several kids: one tile per kid
        val glKids = Prefs.greenlightKids(this)
        data class T(val pkg: String, val label: String, val launch: Intent, val kid: String?)
        val tiles = apps.flatMap { (pkg, label, launch) ->
            if (Prefs.greenlightSplit(this) && glKids.size >= 2 && label.lowercase().contains("greenlight")) glKids.map { T(pkg, "$it's $label", launch, it) }
            else listOf(T(pkg, label, launch, null))
        }
        tiles.forEachIndexed { i, t ->
            val tile = Tile(this).apply {
                tileColor = TILE_COLORS[i % TILE_COLORS.size]
                elevation = dpf(6f)
                icon.setImageDrawable(runCatching { pm.getApplicationIcon(t.pkg) }.getOrNull())
                this.label.text = t.label
                setOnClickListener { if (t.kid == null) open(t.launch) else openAs(t.pkg, t.launch, t.kid) }
            }
            grid.addView(tile)
        }
        // Family photos from the parent: a tile that opens the slideshow, showing the first photo
        val photos = Slides.files(this)
        if (photos.isNotEmpty()) {
            val tile = Tile(this).apply {
                tileColor = TILE_COLORS[tiles.size % TILE_COLORS.size]
                elevation = dpf(6f)
                Slides.decode(photos.first(), 300)?.let { bmp ->
                    icon.setImageDrawable(androidx.core.graphics.drawable.RoundedBitmapDrawableFactory.create(resources, bmp).apply {
                        cornerRadius = minOf(bmp.width, bmp.height) / 5f
                    })
                    icon.scaleType = ImageView.ScaleType.CENTER_CROP
                }
                this.label.text = "Photos"
                setOnClickListener { open(Intent(this@HomeActivity, SlideshowActivity::class.java)) }
            }
            grid.addView(tile)
        }
    }

    /**
     * Opens Greenlight for one kid. If the other kid used it last, Greenlight is signed out first
     * (its data wiped) so it opens on the login screen, where the saved password fills in with one tap.
     */
    private fun openAs(pkg: String, launch: Intent, kid: String) {
        if (Prefs.greenlightLast(this) == kid) { open(launch); return }
        toast("Switching to $kid...")
        Kiosk.clearAppData(this, pkg) { ok ->
            if (ok) Prefs.setGreenlightLast(this, kid)
            toast(if (ok) "Log in as $kid" else "Log out of Greenlight, then log in as $kid")
            open(launch)
        }
    }

    /**
     * Chores board: one card per kid with all of today's chores. Tap a chore to check it off;
     * tap it again to uncheck it (if it was ticked by mistake).
     */
    private fun fillBoard() {
        board.removeAllViews()
        val kids = Chores.kids(this)
        if (!Chores.enabled(this) || kids.isEmpty() || Chores.all(this).isEmpty()) { board.visibility = View.GONE; return }
        board.visibility = View.VISIBLE
        val done = Chores.done(this)
        val kidColors = intArrayOf(0xFFFF6B9A.toInt(), 0xFF4D96FF.toInt(), 0xFFFFB84C.toInt(), 0xFF6BCB77.toInt())
        kids.forEachIndexed { i, kid ->
            val color = kidColors[i % kidColors.size]
            val card = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(dp(10), dp(10), dp(10), dp(8))
                background = rounded(0xE6FFFFFF.toInt(), dpf(18f))
                elevation = dpf(4f)
            }
            val list = Chores.today(this, kid)
            val left = list.count { it.id !in done }
            // Header: name on a colored strip, and how many are left
            val head = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL
                setPadding(dp(10), dp(6), dp(10), dp(6))
                background = rounded(color, dpf(12f))
            }
            head.addView(text(kid, 17f, Color.WHITE, bold = true).apply { maxLines = 1 }, lp(0, WRAP, 1f))
            head.addView(text(when {
                list.isEmpty() -> ""
                left == 0 -> "\u2B50 Done!"
                else -> "$left left"
            }, 14f, Color.WHITE, bold = true))
            card.addView(head, lp(MATCH, WRAP))
            if (list.isEmpty()) card.addView(text("No chores today", 15f, 0xFF6B7080.toInt()), lp(MATCH, WRAP).apply { topMargin = dp(8); bottomMargin = dp(4) })
            list.take(6).forEach { ch ->
                val isDone = ch.id in done
                val row = LinearLayout(this).apply {
                    orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL
                    setPadding(dp(4), dp(7), dp(4), dp(7))
                    setOnClickListener { Chores.toggle(this@HomeActivity, ch.id); fillBoard() }
                }
                val box = text(if (isDone) "\u2713" else "", 17f, Color.WHITE, bold = true, gravity = Gravity.CENTER).apply {
                    background = if (isDone) rounded(0xFF22B573.toInt(), dpf(14f)) else rounded(Color.WHITE, dpf(14f), dp(2), 0xFFB8BCCB.toInt())
                }
                row.addView(box, lp(dp(28), dp(28)))
                row.addView(text(ch.title, 16f, if (isDone) 0xFF9AA0AE.toInt() else 0xFF1F2333.toInt(), bold = !isDone).apply {
                    if (isDone) paintFlags = paintFlags or android.graphics.Paint.STRIKE_THRU_TEXT_FLAG
                    maxLines = 2; ellipsize = android.text.TextUtils.TruncateAt.END
                }, lp(0, WRAP, 1f).apply { marginStart = dp(10) })
                card.addView(row, lp(MATCH, WRAP))
            }
            if (list.size > 6) card.addView(text("+${list.size - 6} more", 13f, 0xFF6B7080.toInt()))
            board.addView(card, lp(0, WRAP, 1f).apply { if (i > 0) marginStart = dp(8) })
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
            val b = SlideToCall(this, Prefs.callName(this, i)) { call(number) }
            row.addView(b, lp(0, dp(64), 1f).apply { setMargins(dp(5), 0, dp(5), 0) })
        }
        row.visibility = if (row.childCount == 0) View.GONE else View.VISIBLE
    }

    /** Calls over Wi-Fi through the calling app the parent picked (Google Voice, Voiply, ...). */
    private fun call(number: String) {
        val app = Prefs.callApp(this)
        if (!Kiosk.isInstalled(this, app)) { toast("The calling app isn't installed yet. Ask a parent."); return }
        // Switch the calling app on just for this call (it stays off the rest of the time, so no calls come in).
        lastCallAt = System.currentTimeMillis()
        Kiosk.apply(this)
        val uri = Uri.parse("tel:" + Uri.encode(number))
        val tries = listOf(Intent.ACTION_CALL, Intent.ACTION_DIAL, Intent.ACTION_VIEW)
        for (action in tries) {
            try {
                startActivity(Intent(action, uri).setPackage(app).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                (application as App).watchCall()
                return
            } catch (e: ActivityNotFoundException) { /* try the next way */ }
            catch (e: SecurityException) { /* try the next way */ }
        }
        // Last resort: just open the calling app
        packageManager.getLaunchIntentForPackage(app)?.let {
            (application as App).watchCall(); open(it)
        } ?: run { lastCallAt = 0L; Kiosk.apply(this); toast("Couldn't start the call") }
    }

    // ── Locks ──

    private fun updateLock() {
        val s = Locks.current(this)
        if (s.reason == Locks.Reason.NONE) {
            lockFrame.visibility = View.GONE; main.visibility = View.VISIBLE
            greeting.text = greetingText()
            return
        }
        zmanim.removeAllViews()
        if (s.reason == Locks.Reason.BEDTIME) {
            showScene("bedtime")
            lockTitle.text = "Good night"
            lockSub.text = "The phone opens again at ${timeText(this, s.until)}."
            zmanim.visibility = View.GONE
        } else {
            val pl = Locks.place(this)
            val now = java.util.Date()
            val w = pl?.let { runCatching { Shabbos.now(now, it) }.getOrNull() }
            val info = if (pl != null && w != null) runCatching { Shabbos.info(w, now, pl) }.getOrNull() else null
            showScene(info?.occasion ?: "shabbos")
            if (info == null) {
                lockTitle.text = if (s.reason == Locks.Reason.YOMTOV) "גוט יום טוב" else "גוט שבת"
                lockSub.text = ""
                zmanim.visibility = View.GONE
            } else {
                lockTitle.text = info.title
                lockSub.text = info.hebrewDate
                val end = if (s.reason == Locks.Reason.YOMTOV) "צאת החג" else "צאת השבת"
                listOf("הדלקת נרות" to info.candle, "סוף זמן קריאת שמע" to info.shma, "שקיעה" to info.sunset, end to info.havdalah)
                    .forEach { (label, d) -> if (d != null) zmanim.addView(zmanRow(label, d.time)) }
                zmanim.visibility = View.VISIBLE
            }
        }
        lockFrame.visibility = View.VISIBLE; main.visibility = View.INVISIBLE
    }

    /** The picture behind the lock screen: the parent's own photo for that occasion, or the built-in one. */
    private fun showScene(key: String) {
        val stamp = key + Prefs.photoVersion(this)
        if (stamp == shownScene) return
        shownScene = stamp
        if (key == "bedtime") {
            lockBg.setImageDrawable(GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM, intArrayOf(0xFF141E46.toInt(), 0xFF2B2A6B.toInt())))
            return
        }
        val own = Scenes.photoFile(this, key)
        val bmp = if (own.exists()) runCatching { decodeSampled(own) }.getOrNull() else null
        if (bmp != null) lockBg.setImageBitmap(bmp) else lockBg.setImageResource(Scenes.builtIn(key))
    }

    private fun decodeSampled(f: File): android.graphics.Bitmap? {
        val o = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(f.path, o)
        var s = 1
        while (o.outWidth / (s * 2) >= 1080 && o.outHeight / (s * 2) >= 1080) s *= 2
        return BitmapFactory.decodeFile(f.path, BitmapFactory.Options().apply { inSampleSize = s })
    }

    private fun zmanRow(label: String, ms: Long): View {
        val r = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            layoutDirection = View.LAYOUT_DIRECTION_RTL
            setPadding(0, dp(6), 0, dp(6))
        }
        r.addView(text(label, 20f, Color.WHITE, bold = true), lp(0, WRAP, 1f))
        r.addView(text(timeText(this, ms), 20f, 0xFFFFE08A.toInt(), bold = true))
        return r
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
