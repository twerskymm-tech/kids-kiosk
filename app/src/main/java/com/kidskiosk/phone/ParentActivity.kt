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
        refreshPreview()
    }

    override fun onPause() {
        super.onPause()
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

    // ── Saving ──

    private fun save() {
        if (!::body.isInitialized || !::namesField.isInitialized) return
        Prefs.setAllowed(this, checks.filter { it.value.isChecked }.keys)
        callFields.forEachIndexed { i, (n, p) -> Prefs.setCall(this, i, n.text.toString().trim(), p.text.toString().trim()) }
        Prefs.setKidNames(this, namesField.text.toString().trim())
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
