package com.kidskiosk.phone

import android.Manifest
import android.annotation.SuppressLint
import android.app.AlertDialog
import android.app.TimePickerDialog
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
import android.location.Location
import android.location.LocationManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.text.InputType
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.widget.Button
import android.widget.CheckBox
import android.widget.EditText
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.Switch
import android.widget.TextView
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import java.util.Date
import java.util.Locale

/**
 * Parent mode (after the hidden corner + PIN). The phone is unlocked while this is open.
 * Kids mode comes back by itself 15 minutes after the last tap here, even if you went into Settings.
 */
class ParentActivity : AppCompatActivity() {
    companion object { const val OPEN_MS = 15 * 60_000L }

    private val ink = 0xFF1F2333.toInt()
    private val muted = 0xFF6B7080.toInt()
    private val brand = 0xFF6C4CF1.toInt()
    private lateinit var body: LinearLayout
    private val checks = mutableMapOf<String, CheckBox>()
    private lateinit var callFields: List<Pair<EditText, EditText>>
    private lateinit var namesField: EditText
    private lateinit var glKidsField: EditText
    private lateinit var choreList: LinearLayout
    private lateinit var latField: EditText
    private lateinit var lonField: EditText
    private lateinit var candleField: EditText
    private lateinit var havdalahField: EditText
    private lateinit var shabbosPreview: TextView
    private lateinit var callAppText: TextView
    private lateinit var pauseStatus: TextView
    private lateinit var bedStartBtn: Button
    private lateinit var bedEndBtn: Button
    private var bedStart = 0
    private lateinit var remoteBox: LinearLayout
    private val uiHandler = android.os.Handler(android.os.Looper.getMainLooper())
    private val remoteTick = object : Runnable {
        override fun run() { fillRemote(); uiHandler.postDelayed(this, 3000L) }
    }
    private var bedEnd = 0

    private val pickPhoto = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri: Uri? ->
        if (uri == null) return@registerForActivityResult
        runCatching {
            contentResolver.openInputStream(uri)!!.use { inp ->
                HomeActivity.photoFile(this).outputStream().use { inp.copyTo(it) }
            }
            Prefs.bumpPhoto(this); toast("Background photo saved")
        }.onFailure { toast("Couldn't use that photo") }
    }

    private var sceneKey = ""
    private val pickScene = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri: Uri? ->
        if (uri == null || sceneKey.isEmpty()) return@registerForActivityResult
        runCatching {
            contentResolver.openInputStream(uri)!!.use { inp -> Scenes.photoFile(this, sceneKey).outputStream().use { inp.copyTo(it) } }
            Prefs.bumpPhoto(this); toast("${Scenes.ALL[sceneKey]} picture saved")
        }.onFailure { toast("Couldn't use that photo") }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.statusBarColor = brand
        val scroll = ScrollView(this).apply { setBackgroundColor(0xFFF5F5FA.toInt()) }
        body = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(18), dp(20), dp(18), dp(40)) }
        scroll.addView(body)
        setContentView(scroll)
        build()
        onBackPressedDispatcher.addCallback(this, object : androidx.activity.OnBackPressedCallback(true) {
            override fun handleOnBackPressed() { backToKids() }
        })
    }

    override fun onResume() {
        super.onResume()
        keepOpen()
        Kiosk.unlock(this)
        Kiosk.apply(this)   // calling app usable while parent mode is open (to set it up)
        refreshPreview()
        uiHandler.removeCallbacks(remoteTick); uiHandler.post(remoteTick)
    }

    override fun onPause() {
        super.onPause()
        uiHandler.removeCallbacks(remoteTick)
        save()
    }

    override fun dispatchTouchEvent(ev: MotionEvent): Boolean {
        if (ev.action == MotionEvent.ACTION_DOWN) keepOpen()
        return super.dispatchTouchEvent(ev)
    }

    private fun keepOpen() = Prefs.setParentUntil(this, System.currentTimeMillis() + OPEN_MS)

    private fun backToKids() {
        save()
        Prefs.setParentUntil(this, 0L)
        startActivity(Intent(this, HomeActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_REORDER_TO_FRONT))
        finish()
    }


    // ── Layout ──

    private fun build() {
        body.addView(text("Parent mode", 28f, ink, bold = true))
        body.addView(text("The phone is unlocked. Kids mode comes back by itself 15 minutes after your last tap here.", 14f, muted))
        body.addView(button("Back to kids mode", primary = true) { backToKids() }, lp(MATCH, dp(56)).apply { topMargin = dp(14) })
        val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        row.addView(button("Phone settings") { open(Intent(Settings.ACTION_SETTINGS)) }, lp(0, dp(52), 1f).apply { marginEnd = dp(6) })
        row.addView(button("Play Store") { openPkg("com.android.vending") }, lp(0, dp(52), 1f).apply { marginStart = dp(6) })
        body.addView(row, lp(MATCH, WRAP).apply { topMargin = dp(8) })

        // Remote control from the parent's phone
        val remote = card("Remote control", "Change apps, chores, call buttons and locks from your own phone, from anywhere.")
        remoteBox = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        remote.addView(remoteBox, lp(MATCH, WRAP))

        // Kids' apps
        val apps = card("Kids' apps", "Tick the apps that show on the kids' screen. Install new ones from the Play Store first.")
        val allowed = Kiosk.allowedApps(this)
        for ((pkg, label) in Kiosk.launchableApps(this)) {
            val r = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
            r.addView(ImageView(this).apply { setImageDrawable(runCatching { packageManager.getApplicationIcon(pkg) }.getOrNull()) }, lp(dp(36), dp(36)))
            val cb = CheckBox(this).apply { text = label; textSize = 16f; setTextColor(ink); isChecked = pkg in allowed }
            checks[pkg] = cb
            r.addView(cb, lp(0, WRAP, 1f).apply { marginStart = dp(10) })
            apps.addView(r, lp(MATCH, WRAP).apply { topMargin = dp(4) })
        }

        apps.addView(text("Kids' names", 15f, ink, bold = true), lp(MATCH, WRAP).apply { topMargin = dp(14) })
        apps.addView(text("Separated by commas (e.g. Rivky, Chaya). Used for the chores board and for a Greenlight tile per kid.", 13f, muted))
        glKidsField = field(Prefs.greenlightKids(this).joinToString(", "), "Names, e.g. Rivky, Chaya")
        apps.addView(glKidsField)
        apps.addView(switch("Separate Greenlight tile per kid (signs the other kid out)", Prefs.greenlightSplit(this)) { on -> Prefs.setGreenlightSplit(this, on) })

        // Chores board
        val chores = card("Chores board", "Shows each kid's chores for today on the home screen; they tap to check them off. Ticks reset every morning.")
        chores.addView(switch("Show chores board", Chores.enabled(this)) { on -> Chores.setEnabled(this, on) })
        choreList = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        chores.addView(choreList)
        chores.addView(button("Add a chore") { save(); addChore() }, lp(MATCH, dp(52)).apply { topMargin = dp(8) })
        showChores()

        // Calls
        val calls = card("Call buttons", "The kids' screen shows a button for each number. Calls go over Wi-Fi through the calling app you choose below (Google Voice, Voiply, ...).")
        callFields = (0..1).map { i ->
            val name = field(Prefs.callName(this, i), "Button name (e.g. Mommy)")
            val num = field(Prefs.callNumber(this, i), "Phone number", InputType.TYPE_CLASS_PHONE)
            calls.addView(text(if (i == 0) "First button" else "Second button", 14f, muted, bold = true), lp(MATCH, WRAP).apply { topMargin = dp(10) })
            calls.addView(name); calls.addView(num)
            name to num
        }
        callAppText = text("", 14f, muted)
        calls.addView(callAppText, lp(MATCH, WRAP).apply { topMargin = dp(10) })
        val car = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        car.addView(button("Choose calling app") { pickCallApp() }, lp(0, dp(52), 1f).apply { marginEnd = dp(6) })
        car.addView(button("Open calling app") { openPkg(Prefs.callApp(this)) }, lp(0, dp(52), 1f).apply { marginStart = dp(6) })
        calls.addView(car, lp(MATCH, WRAP).apply { topMargin = dp(6) })
        showCallApp()

        // Bedtime
        val bed = card("Bedtime lock", "The phone locks every night between these times. Call buttons still work.")
        bedStart = Prefs.bedStart(this); bedEnd = Prefs.bedEnd(this)
        val bedSwitch = switch("Bedtime lock on", Prefs.bedtimeOn(this)) { on -> Prefs.setBedtime(this, on, bedStart, bedEnd) }
        bed.addView(bedSwitch)
        val times = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        bedStartBtn = button("Starts ${minutesText(this, bedStart)}") { pickTime(bedStart) { m -> bedStart = m; bedStartBtn.text = "Starts ${minutesText(this, m)}"; Prefs.setBedtime(this, bedSwitch.isChecked, bedStart, bedEnd) } }
        bedEndBtn = button("Ends ${minutesText(this, bedEnd)}") { pickTime(bedEnd) { m -> bedEnd = m; bedEndBtn.text = "Ends ${minutesText(this, m)}"; Prefs.setBedtime(this, bedSwitch.isChecked, bedStart, bedEnd) } }
        times.addView(bedStartBtn, lp(0, dp(52), 1f).apply { marginEnd = dp(6) })
        times.addView(bedEndBtn, lp(0, dp(52), 1f).apply { marginStart = dp(6) })
        bed.addView(times, lp(MATCH, WRAP).apply { topMargin = dp(6) })

        // Shabbos / Yom Tov
        val shab = card("Shabbos and Yom Tov lock", "Locks from candle lighting until Havdalah, worked out on the phone for your location. Call buttons still work.")
        shab.addView(switch("Shabbos and Yom Tov lock on", Prefs.shabbosOn(this)) { on -> Prefs.setShabbosOn(this, on); refreshPreview() })
        shab.addView(switch("We live in Israel (one day Yom Tov)", Prefs.israel(this)) { on -> Prefs.setIsrael(this, on); refreshPreview() })
        shab.addView(button("Use this phone's location") { useLocation() }, lp(MATCH, dp(52)).apply { topMargin = dp(8) })
        val ll = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        val dec = InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_FLAG_DECIMAL or InputType.TYPE_NUMBER_FLAG_SIGNED
        latField = field(if (Prefs.hasPlace(this)) "%.4f".format(Locale.US, Prefs.lat(this)) else "", "Latitude", dec)
        lonField = field(if (Prefs.hasPlace(this)) "%.4f".format(Locale.US, Prefs.lon(this)) else "", "Longitude", dec)
        ll.addView(latField, lp(0, WRAP, 1f).apply { marginEnd = dp(6) }); ll.addView(lonField, lp(0, WRAP, 1f).apply { marginStart = dp(6) })
        shab.addView(ll)
        val mm = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        candleField = field(Prefs.candleMin(this).toString(), "Candle lighting: min before sunset", InputType.TYPE_CLASS_NUMBER)
        havdalahField = field(Prefs.havdalahMin(this).toString(), "Havdalah: min after sunset", InputType.TYPE_CLASS_NUMBER)
        mm.addView(labeled("Candle lighting\n(min before sunset)", candleField), lp(0, WRAP, 1f).apply { marginEnd = dp(6) })
        mm.addView(labeled("Havdalah\n(min after sunset)", havdalahField), lp(0, WRAP, 1f).apply { marginStart = dp(6) })
        shab.addView(mm, lp(MATCH, WRAP).apply { topMargin = dp(6) })
        shab.addView(button("Save and show times") { save(); refreshPreview() }, lp(MATCH, dp(52)).apply { topMargin = dp(6) })
        shabbosPreview = text("", 15f, ink)
        shab.addView(shabbosPreview, lp(MATCH, WRAP).apply { topMargin = dp(8) })

        // Pause
        val pause = card("Pause locks", "Lets the kids use the phone during bedtime or Shabbos for a while.")
        pauseStatus = text("", 15f, ink)
        pause.addView(pauseStatus)
        val pr = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        pr.addView(button("Pause 1 hour") { Prefs.setPausedUntil(this, System.currentTimeMillis() + 3_600_000L); refreshPreview() }, lp(0, dp(52), 1f).apply { marginEnd = dp(6) })
        pr.addView(button("Resume locks") { Prefs.setPausedUntil(this, 0L); refreshPreview() }, lp(0, dp(52), 1f).apply { marginStart = dp(6) })
        pause.addView(pr, lp(MATCH, WRAP).apply { topMargin = dp(6) })

        // Look
        val look = card("Look", "Names for the greeting (\"Good morning, ...!\") and the background photo.")
        namesField = field(Prefs.kidNames(this), "Names, e.g. Rivky & Chaya")
        look.addView(namesField)
        val lr = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        lr.addView(button("Choose photo") { runCatching { pickPhoto.launch(arrayOf("image/*")) }.onFailure { toast("No photo picker on this phone") } }, lp(0, dp(52), 1f).apply { marginEnd = dp(6) })
        lr.addView(button("Remove photo") { HomeActivity.photoFile(this).delete(); Prefs.bumpPhoto(this); toast("Photo removed") }, lp(0, dp(52), 1f).apply { marginStart = dp(6) })
        look.addView(lr, lp(MATCH, WRAP).apply { topMargin = dp(6) })

        // Shabbos / Yom Tov pictures
        val pics = card("Shabbos and Yom Tov pictures", "Each one has a built-in picture. You can use your own photo instead.")
        for ((key, name) in Scenes.ALL) {
            val r = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
            r.addView(text(name, 15f, ink), lp(0, WRAP, 1f))
            r.addView(button("Own photo") { sceneKey = key; runCatching { pickScene.launch(arrayOf("image/*")) }.onFailure { toast("No photo picker on this phone") } }, lp(WRAP, dp(44)).apply { marginStart = dp(6) })
            r.addView(button("Built-in") { Scenes.photoFile(this, key).delete(); Prefs.bumpPhoto(this); toast("$name: built-in picture") }, lp(WRAP, dp(44)).apply { marginStart = dp(6) })
            pics.addView(r, lp(MATCH, WRAP).apply { topMargin = dp(6) })
        }

        // PIN and setup
        val sec = card("PIN and setup", if (Kiosk.isOwner(this)) "Full lock is on." else "Full lock is NOT on yet. Follow the setup guide (factory reset + one command from your PC).")
        sec.addView(button("Change PIN") { PinDialog.create(this) {} }, lp(MATCH, dp(52)).apply { topMargin = dp(6) })
        sec.addView(button("Check for app update") { toast("Checking..."); Updater.check(this) { m -> toast(m) } }, lp(MATCH, dp(52)).apply { topMargin = dp(6) })
        if (Kiosk.isOwner(this)) sec.addView(button("Remove full lock (before uninstalling)") { confirmRemove() }, lp(MATCH, dp(52)).apply { topMargin = dp(6) })
        sec.addView(text("Version ${runCatching { packageManager.getPackageInfo(packageName, 0).versionName }.getOrDefault("")}", 12f, muted), lp(MATCH, WRAP).apply { topMargin = dp(8) })
    }

    // ── Remote control ──

    private var remoteShown = ""
    private fun fillRemote() {
        if (!::remoteBox.isInitialized) return
        val on = Remote.isOn(this); val linked = Remote.isLinked(this); val code = Remote.pairCode(this)
        val last = Remote.lastOk(this)
        val status = when {
            !on -> ""
            linked -> "Connected" + (if (Remote.phoneName(this).isNotEmpty()) " as \"${Remote.phoneName(this)}\"" else "") +
                ". Last check-in: " + (if (last == 0L) "not yet" else android.text.format.DateUtils.getRelativeTimeSpanString(last).toString()) +
                (if (Remote.lastError(this).isNotEmpty()) "\nLast try failed: ${Remote.lastError(this)}" else "")
            else -> if (last == 0L && Remote.lastError(this).isNotEmpty()) "Can't reach the control panel: ${Remote.lastError(this)}. Check Wi-Fi." else ""
        }
        val key = "$on|$linked|$code|$status"
        if (key == remoteShown) return
        remoteShown = key
        remoteBox.removeAllViews()
        when {
            !on -> remoteBox.addView(button("Connect remote control", primary = true) { Remote.connect(this) { fillRemote() } }, lp(MATCH, dp(52)).apply { topMargin = dp(8) })
            !linked -> {
                remoteBox.addView(text("On your own phone, open", 15f, ink), lp(MATCH, WRAP).apply { topMargin = dp(8) })
                remoteBox.addView(text(Remote.SITE, 16f, brand, bold = true))
                remoteBox.addView(text("sign in, and type this code:", 15f, ink))
                remoteBox.addView(text(code.chunked(4).joinToString(" "), 40f, ink, bold = true).apply {
                    gravity = Gravity.CENTER; letterSpacing = 0.12f; typeface = android.graphics.Typeface.MONOSPACE
                }, lp(MATCH, WRAP).apply { topMargin = dp(8); bottomMargin = dp(4) })
                if (status.isNotEmpty()) remoteBox.addView(text(status, 13f, 0xFFC0392B.toInt()))
                remoteBox.addView(text("The code works for 15 minutes while this screen is open.", 13f, muted))
                remoteBox.addView(button("Cancel") { Remote.disconnect(this); fillRemote() }, lp(MATCH, dp(48)).apply { topMargin = dp(8) })
            }
            else -> {
                remoteBox.addView(text(status, 15f, ink), lp(MATCH, WRAP).apply { topMargin = dp(8) })
                remoteBox.addView(text("Changes you make there reach this phone within a minute. Changes here show up there too.", 13f, muted))
                val r = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
                r.addView(button("Check in now") { save(); Remote.syncNow(this) { remoteShown = ""; fillRemote(); toast("Checked in") } }, lp(0, dp(48), 1f).apply { marginEnd = dp(6) })
                r.addView(button("Disconnect") {
                    AlertDialog.Builder(this).setTitle("Disconnect remote control?")
                        .setMessage("This phone stops listening to the control panel. You can connect again any time.")
                        .setPositiveButton("Disconnect") { _, _ -> Remote.disconnect(this); fillRemote() }
                        .setNegativeButton("Cancel", null).show()
                }, lp(0, dp(48), 1f).apply { marginStart = dp(6) })
                remoteBox.addView(r, lp(MATCH, WRAP).apply { topMargin = dp(8) })
            }
        }
    }

    // ── Saving ──

    private fun save() {
        if (!::body.isInitialized || !::namesField.isInitialized) return
        Prefs.setAllowed(this, checks.filter { it.value.isChecked }.keys)
        callFields.forEachIndexed { i, (n, p) -> Prefs.setCall(this, i, n.text.toString().trim(), p.text.toString().trim()) }
        Prefs.setKidNames(this, namesField.text.toString().trim())
        Prefs.setGreenlightKids(this, glKidsField.text.toString())
        val lat = latField.text.toString().trim().toDoubleOrNull()
        val lon = lonField.text.toString().trim().toDoubleOrNull()
        if (lat != null && lon != null && lat in -90.0..90.0 && lon in -180.0..180.0) Prefs.setPlace(this, lat, lon)
        val c = candleField.text.toString().toIntOrNull()?.coerceIn(0, 90) ?: Prefs.candleMin(this)
        val h = havdalahField.text.toString().toIntOrNull()?.coerceIn(0, 120) ?: Prefs.havdalahMin(this)
        Prefs.setMinutes(this, c, h)
        Kiosk.apply(this)
    }

    private fun refreshPreview() {
        if (!::shabbosPreview.isInitialized) return
        val pl = Locks.place(this)
        shabbosPreview.text = when {
            pl == null -> "Set your location above so the lock knows Shabbos times."
            else -> runCatching {
                val w = Shabbos.currentOrNext(Date(), pl)
                if (w == null) "Couldn't work out the times for this location."
                else "${if (w.yomTov) "Next Yom Tov / Shabbos" else "Next Shabbos"}: locks ${dayTimeText(this, w.start.time)}, opens ${dayTimeText(this, w.end.time)}"
            }.getOrDefault("Couldn't work out the times for this location.")
        }
        val until = Prefs.pausedUntil(this)
        val s = Locks.current(this)
        pauseStatus.text = when {
            System.currentTimeMillis() < until -> "Locks paused until ${timeText(this, until)}."
            s.reason == Locks.Reason.NONE -> "No lock right now."
            else -> "Locked now (${s.reason.name.lowercase().replace("yomtov", "Yom Tov")}) until ${dayTimeText(this, s.until)}."
        }
    }

    @SuppressLint("MissingPermission")
    private fun useLocation() {
        val fine = checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED
        val coarse = checkSelfPermission(Manifest.permission.ACCESS_COARSE_LOCATION) == PackageManager.PERMISSION_GRANTED
        if (!fine && !coarse) {
            requestPermissions(arrayOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION), 7)
            return
        }
        val lm = getSystemService(LOCATION_SERVICE) as LocationManager
        val got: (Location?) -> Unit = { loc ->
            if (loc == null) toast("Couldn't find the location. Turn on Location in Phone settings, or type it in.")
            else {
                latField.setText("%.4f".format(Locale.US, loc.latitude)); lonField.setText("%.4f".format(Locale.US, loc.longitude))
                save(); refreshPreview(); toast("Location saved")
            }
        }
        val providers = lm.getProviders(true)
        if (providers.isEmpty()) { got(null); return }
        val last = providers.mapNotNull { runCatching { lm.getLastKnownLocation(it) }.getOrNull() }.maxByOrNull { it.time }
        if (Build.VERSION.SDK_INT >= 30) {
            val prov = if (LocationManager.NETWORK_PROVIDER in providers) LocationManager.NETWORK_PROVIDER else providers.first()
            toast("Finding location...")
            runCatching { lm.getCurrentLocation(prov, null, mainExecutor) { loc -> got(loc ?: last) } }.onFailure { got(last) }
        } else got(last)
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == 7 && grantResults.any { it == PackageManager.PERMISSION_GRANTED }) useLocation()
    }

    private fun showChores() {
        choreList.removeAllViews()
        val all = Chores.all(this)
        if (all.isEmpty()) { choreList.addView(text("No chores yet.", 14f, muted), lp(MATCH, WRAP).apply { topMargin = dp(6) }); return }
        for (kid in all.map { it.kid }.distinct()) {
            choreList.addView(text(kid, 15f, ink, bold = true), lp(MATCH, WRAP).apply { topMargin = dp(10) })
            all.filter { it.kid == kid }.forEach { ch ->
                val r = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
                val price = if (ch.cents > 0) "  " + Chores.money(ch.cents) else ""
                r.addView(text("${ch.title}$price  (${Chores.daysText(ch.days)})", 15f, ink), lp(0, WRAP, 1f))
                r.addView(button("Remove") { Chores.remove(this, ch.id); showChores() }, lp(WRAP, dp(40)))
                choreList.addView(r, lp(MATCH, WRAP).apply { topMargin = dp(4) })
            }
        }
    }

    private fun addChore() {
        val kids = Chores.kids(this)
        if (kids.isEmpty()) { toast("Type the kids' names first (Kids' apps section)"); return }
        val box = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(24), dp(8), dp(24), 0) }
        val title = field("", "Chore, e.g. Make your bed")
        box.addView(title)
        val price = field("", "Pays (optional), e.g. 0.50", InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_FLAG_DECIMAL)
        box.addView(price)
        box.addView(text("For", 14f, muted, bold = true), lp(MATCH, WRAP).apply { topMargin = dp(10) })
        val kidChecks = kids.map { k -> CheckBox(this).apply { text = k; isChecked = true }.also { box.addView(it) } }
        box.addView(text("Days", 14f, muted, bold = true), lp(MATCH, WRAP).apply { topMargin = dp(10) })
        val dayNames = listOf("Sun", "Mon", "Tue", "Wed", "Thu", "Fri", "Shabbos")
        val dayRow1 = LinearLayout(this); val dayRow2 = LinearLayout(this)
        val dayChecks = dayNames.mapIndexed { i, d -> CheckBox(this).apply { text = d; isChecked = i < 6 }.also { (if (i < 4) dayRow1 else dayRow2).addView(it) } }
        box.addView(dayRow1); box.addView(dayRow2)
        AlertDialog.Builder(this)
            .setTitle("Add a chore")
            .setView(ScrollView(this).apply { addView(box) })
            .setPositiveButton("Add") { _, _ ->
                val t = title.text.toString().trim()
                var days = 0
                dayChecks.forEachIndexed { i, cb -> if (cb.isChecked) days = days or (1 shl i) }
                val who = kids.filterIndexed { i, _ -> kidChecks[i].isChecked }
                if (t.isEmpty() || days == 0 || who.isEmpty()) { toast("Type a chore, and pick at least one kid and one day"); return@setPositiveButton }
                val cents = ((price.text.toString().replace("$", "").trim().toDoubleOrNull() ?: 0.0) * 100).toInt().coerceIn(0, 100_000)
                who.forEach { Chores.add(this, it, t, days, cents) }
                showChores()
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun showCallApp() {
        val pkg = Prefs.callApp(this)
        val ok = Kiosk.isInstalled(this, pkg)
        callAppText.text = if (ok) "Calling app: ${Kiosk.appLabel(this, pkg)}. Open it once and sign in so calls work."
            else "Calling app: ${Kiosk.appLabel(this, pkg)} is not installed. Install it from the Play Store, or choose another."
        callAppText.setTextColor(if (ok) muted else 0xFFC0392B.toInt())
    }

    private fun pickCallApp() {
        val apps = Kiosk.launchableApps(this)
        AlertDialog.Builder(this)
            .setTitle("Which app makes the calls?")
            .setItems(apps.map { it.second }.toTypedArray()) { _, i ->
                Prefs.setCallApp(this, apps[i].first); Kiosk.apply(this); showCallApp()
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun confirmRemove() {
        AlertDialog.Builder(this)
            .setTitle("Remove full lock?")
            .setMessage("The phone becomes a normal phone again and you can uninstall this app. To lock it again later you'd need another factory reset.")
            .setPositiveButton("Remove") { _, _ -> Kiosk.removeOwner(this); toast("Full lock removed"); recreate() }
            .setNegativeButton("Cancel", null)
            .show()
    }

    // ── Small parts ──

    private fun open(i: Intent) { runCatching { startActivity(i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }.onFailure { toast("Couldn't open that") } }
    private fun openPkg(pkg: String) { packageManager.getLaunchIntentForPackage(pkg)?.let { open(it) } ?: toast("Not installed") }

    private fun pickTime(m: Int, done: (Int) -> Unit) {
        TimePickerDialog(this, { _, h, min -> done(h * 60 + min) }, m / 60, m % 60, android.text.format.DateFormat.is24HourFormat(this)).show()
    }

    private fun card(title: String, sub: String): LinearLayout {
        val c = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(14), dp(16), dp(16))
            background = rounded(Color.WHITE, dpf(16f))
            elevation = dpf(2f)
        }
        c.addView(text(title, 19f, ink, bold = true))
        c.addView(text(sub, 14f, muted), lp(MATCH, WRAP).apply { topMargin = dp(2) })
        body.addView(c, lp(MATCH, WRAP).apply { topMargin = dp(16) })
        return c
    }

    private fun button(label: String, primary: Boolean = false, onClick: () -> Unit) = Button(this).apply {
        text = label; isAllCaps = false; textSize = 15f
        setTextColor(if (primary) Color.WHITE else brand)
        background = if (primary) rounded(brand, dpf(14f)) else rounded(Color.WHITE, dpf(14f), dp(1), 0xFFD5D2E8.toInt())
        setOnClickListener { onClick() }
    }

    @Suppress("UseSwitchCompatOrMaterialCode")
    private fun switch(label: String, on: Boolean, onChange: (Boolean) -> Unit) = Switch(this).apply {
        text = label; textSize = 16f; setTextColor(ink); isChecked = on
        setPadding(0, dp(8), 0, dp(8))
        setOnCheckedChangeListener { _, v -> onChange(v) }
    }

    private fun field(value: String, hint: String, type: Int = InputType.TYPE_CLASS_TEXT) = EditText(this).apply {
        setText(value); this.hint = hint; inputType = type; textSize = 16f; setTextColor(ink)
    }

    private fun labeled(label: String, v: View) = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        addView(text(label, 13f, muted)); addView(v)
    }
}
