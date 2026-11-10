package com.kidskiosk.phone

import android.content.Context
import android.os.Handler
import android.os.Looper
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.security.SecureRandom
import java.time.LocalDate

/**
 * Remote control from the parent's own phone (the "Kids Home Control" web app).
 * About once a minute the kiosk sends its current settings, chores, ticks and app list, and picks up
 * any changes the parent made there. The phone identifies itself with a random key it made itself;
 * a parent links it once by typing the short code parent mode shows.
 */
object Remote {
    const val SITE = "kids-home-control-4e3b85f5.base44.app"
    private const val URL_SYNC = "https://$SITE/functions/kiosk-sync"
    private const val EVERY_MS = 60_000L
    private const val PAIRING_EVERY_MS = 15_000L

    private val main = Handler(Looper.getMainLooper())
    @Volatile private var busy = false
    @Volatile private var lastTry = 0L

    /** HomeActivity listens so the screen updates as soon as a change arrives. */
    @Volatile var onChange: (() -> Unit)? = null

    private fun p(c: Context) = c.getSharedPreferences("remote", Context.MODE_PRIVATE)

    fun isOn(c: Context) = p(c).getBoolean("on", false)
    fun isLinked(c: Context) = p(c).getBoolean("linked", false)
    fun pairCode(c: Context) = p(c).getString("pair_code", "") ?: ""
    fun phoneName(c: Context) = p(c).getString("name", "") ?: ""
    fun lastOk(c: Context) = p(c).getLong("last_ok", 0L)
    fun lastError(c: Context) = p(c).getString("last_error", "") ?: ""

    /** Start (or restart) linking: makes a key if there is none and a fresh code to type on the web page. */
    fun connect(c: Context, done: (() -> Unit)? = null) {
        val e = p(c).edit()
        if ((p(c).getString("key", "") ?: "").isEmpty()) e.putString("key", randomKey())
        e.putBoolean("on", true).putBoolean("linked", false).putString("pair_code", randomCode()).apply()
        syncNow(c, done)
    }

    fun disconnect(c: Context) {
        p(c).edit().clear().apply()
    }

    /** Called from the app's 20-second tick. */
    fun autoSync(c: Context) {
        if (!isOn(c)) return
        Slides.ensure(c)
        val gap = if (isLinked(c)) EVERY_MS else PAIRING_EVERY_MS
        if (System.currentTimeMillis() - lastTry < gap) return
        syncNow(c, null)
    }

    fun syncNow(c: Context, done: (() -> Unit)?) {
        val app = c.applicationContext
        if (!isOn(app) || busy) { done?.let { main.post(it) }; return }
        busy = true
        lastTry = System.currentTimeMillis()
        // Read everything on the main thread (prefs + package list are cheap), send on a worker thread.
        val body = runCatching { request(app) }.getOrNull()
        Thread {
            try {
                if (body == null) throw IllegalStateException("couldn't read settings")
                val res = post(body.toString())
                main.post { handle(app, res); busy = false; done?.invoke() }
            } catch (e: Exception) {
                p(app).edit().putString("last_error", e.message ?: "no connection").apply()
                main.post { busy = false; done?.invoke() }
            }
        }.start()
    }

    private fun request(c: Context): JSONObject {
        val j = JSONObject()
            .put("key", p(c).getString("key", ""))
            .put("version", BuildConfig.VERSION_NAME)
            .put("applied_rev", p(c).getLong("applied_rev", 0L))
            .put("state", state(c))
        if (!isLinked(c)) j.put("pair_code", pairCode(c))
        return j
    }

    private fun post(body: String): JSONObject {
        val conn = URL(URL_SYNC).openConnection() as HttpURLConnection
        conn.requestMethod = "POST"
        conn.connectTimeout = 15000; conn.readTimeout = 20000
        conn.doOutput = true
        conn.setRequestProperty("Content-Type", "application/json")
        conn.outputStream.use { it.write(body.toByteArray()) }
        val code = conn.responseCode
        val text = (if (code < 400) conn.inputStream else conn.errorStream)?.bufferedReader()?.readText() ?: ""
        if (code == 404) return JSONObject().put("unknown", true)
        if (code != 200) throw java.io.IOException("server said $code")
        return JSONObject(text)
    }

    private fun handle(c: Context, res: JSONObject) {
        val e = p(c).edit().putLong("last_ok", System.currentTimeMillis()).putString("last_error", "")
        if (res.optBoolean("unknown")) {
            // The parent unlinked this phone (or the record is gone): start over with a new code.
            e.putBoolean("linked", false).putString("pair_code", randomCode()).putLong("applied_rev", 0L).apply()
            return
        }
        val linked = res.optBoolean("linked")
        e.putBoolean("linked", linked).putString("name", res.optString("name"))
        if (linked) e.putString("pair_code", "")
        e.apply()
        val pending = res.optJSONObject("pending")
        val rev = res.optLong("pending_rev", 0L)
        // While parent mode is open on the phone, wait: its own Save would undo the change.
        val parentOpen = System.currentTimeMillis() < Prefs.parentUntil(c)
        if (linked && !parentOpen && pending != null && rev > p(c).getLong("applied_rev", 0L)) {
            runCatching { apply(c, pending) }
            p(c).edit().putLong("applied_rev", rev).apply()
            onChange?.invoke()
            // Report back right away so the web page shows the change as done.
            lastTry = 0L
            main.postDelayed({ syncNow(c, null) }, 1500L)
        }
    }

    // ── What the phone reports ──

    private fun state(c: Context): JSONObject {
        val settings = JSONObject()
            .put("allowed", JSONArray(Kiosk.allowedApps(c).toList()))
            .put("kids", Prefs.greenlightKids(c).joinToString(", "))
            .put("greeting", Prefs.kidNames(c))
            .put("call", JSONArray().apply {
                for (i in 0..1) put(JSONObject().put("name", Prefs.callName(c, i)).put("number", Prefs.callNumber(c, i)))
            })
            .put("bed_on", Prefs.bedtimeOn(c))
            .put("bed_start", Prefs.bedStart(c))
            .put("bed_end", Prefs.bedEnd(c))
            .put("shab_on", Prefs.shabbosOn(c))
            .put("paused_until", Prefs.pausedUntil(c))
            .put("chores_on", Chores.enabled(c))
            .put("photos", Slides.json(c))
            .put("kid_colors", Chores.colorsJson(c))
            .put("gl_split", Prefs.greenlightSplit(c))
            .put("call_app", Prefs.callApp(c))
            .put("has_place", Prefs.hasPlace(c))
            .put("lat", Prefs.lat(c))
            .put("lon", Prefs.lon(c))
            .put("israel", Prefs.israel(c))
            .put("candle_min", Prefs.candleMin(c))
            .put("havdalah_min", Prefs.havdalahMin(c))
            .put("background", p(c).getString("bg_url", "") ?: "")
            .put("scenes", JSONObject().apply { Scenes.ALL.keys.forEach { k -> put(k, p(c).getString("scene_$k", "") ?: "") } })
            .put("chores", JSONArray().apply {
                Chores.all(c).forEach { put(JSONObject().put("id", it.id).put("kid", it.kid).put("title", it.title).put("days", it.days).put("cents", it.cents)) }
            })
        val lock = Locks.current(c)
        return JSONObject()
            .put("today", LocalDate.now().toString())
            .put("settings", settings)
            .put("installed", JSONArray().apply {
                Kiosk.launchableApps(c).forEach { (pkg, label) -> put(JSONObject().put("pkg", pkg).put("label", label)) }
            })
            .put("done", JSONArray(Chores.done(c).toList()))
            .put("earned", Chores.earnedAll(c))
            .put("claims", Chores.claims(c))
            .put("lock", JSONObject().put("reason", lock.reason.name).put("until", lock.until))
            .put("call_app", Prefs.callApp(c))
            .put("scene_names", JSONObject().apply { Scenes.ALL.forEach { (k, v) -> put(k, v) } })
            .put("has_own_background", HomeActivity.photoFile(c).exists())
    }

    // ── Changes from the parent ──

    private fun apply(c: Context, s: JSONObject) {
        s.optJSONArray("allowed")?.let { a -> Prefs.setAllowed(c, (0 until a.length()).map { a.getString(it) }.toSet()) }
        if (s.has("kids")) Prefs.setGreenlightKids(c, s.optString("kids"))
        if (s.has("greeting")) Prefs.setKidNames(c, s.optString("greeting").trim())
        s.optJSONArray("call")?.let { a ->
            for (i in 0 until minOf(2, a.length())) {
                val o = a.getJSONObject(i)
                Prefs.setCall(c, i, o.optString("name").trim(), o.optString("number").trim())
            }
        }
        if (s.has("bed_on") || s.has("bed_start") || s.has("bed_end")) {
            Prefs.setBedtime(c,
                s.optBoolean("bed_on", Prefs.bedtimeOn(c)),
                s.optInt("bed_start", Prefs.bedStart(c)).coerceIn(0, 1439),
                s.optInt("bed_end", Prefs.bedEnd(c)).coerceIn(0, 1439))
        }
        if (s.has("shab_on")) Prefs.setShabbosOn(c, s.optBoolean("shab_on"))
        if (s.has("paused_until")) Prefs.setPausedUntil(c, s.optLong("paused_until"))
        // Payouts: amounts to take off what each kid is owed (each change arrives once)
        s.optJSONObject("paid")?.let { j -> j.keys().forEach { k -> Chores.paid(c, k, j.optInt(k, 0)) } }
        s.optJSONObject("kid_colors")?.let { Chores.setColors(c, it) }
        if (s.has("gl_split")) Prefs.setGreenlightSplit(c, s.optBoolean("gl_split"))
        s.optString("call_app", "").takeIf { it.isNotBlank() && Kiosk.isInstalled(c, it) }?.let { Prefs.setCallApp(c, it) }
        if (s.has("lat") && s.has("lon")) {
            val la = s.optDouble("lat", Double.NaN); val lo = s.optDouble("lon", Double.NaN)
            if (la in -90.0..90.0 && lo in -180.0..180.0) Prefs.setPlace(c, la, lo)
        }
        if (s.has("israel")) Prefs.setIsrael(c, s.optBoolean("israel"))
        if (s.has("candle_min") || s.has("havdalah_min")) Prefs.setMinutes(c,
            s.optInt("candle_min", Prefs.candleMin(c)).coerceIn(0, 90), s.optInt("havdalah_min", Prefs.havdalahMin(c)).coerceIn(0, 120))
        if (s.has("background")) {
            val url = s.optString("background")
            p(c).edit().putString("bg_url", url).apply()
            if (url.isEmpty()) { HomeActivity.photoFile(c).delete(); Prefs.bumpPhoto(c) }
            else Slides.download(c, url, HomeActivity.photoFile(c)) { Prefs.bumpPhoto(c) }
        }
        s.optJSONObject("scenes")?.let { j ->
            j.keys().forEach { k ->
                if (k !in Scenes.ALL) return@forEach
                val url = j.optString(k)
                p(c).edit().putString("scene_$k", url).apply()
                if (url.isEmpty()) { Scenes.photoFile(c, k).delete(); Prefs.bumpPhoto(c) }
                else Slides.download(c, url, Scenes.photoFile(c, k)) { Prefs.bumpPhoto(c) }
            }
        }
        if (s.optBoolean("update_now")) Updater.check(c, null)
        if (s.has("chores_on")) Chores.setEnabled(c, s.optBoolean("chores_on"))
        s.optJSONArray("photos")?.let { Slides.set(c, it) }
        s.optJSONArray("chores")?.let { a ->
            Chores.setAll(c, (0 until a.length()).map { a.getJSONObject(it) }.map {
                Chores.Chore(it.getLong("id"), it.optString("kid"), it.optString("title"), it.optInt("days", Chores.EVERY_DAY), it.optInt("cents", 0))
            }.filter { it.title.isNotBlank() && it.kid.isNotBlank() })
        }
        Kiosk.apply(c)
    }

    private fun randomKey(): String {
        val chars = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789"
        val r = SecureRandom()
        return (1..40).map { chars[r.nextInt(chars.length)] }.joinToString("")
    }

    private fun randomCode(): String {
        val chars = "ABCDEFGHJKMNPQRSTUVWXYZ23456789"
        val r = SecureRandom()
        return (1..8).map { chars[r.nextInt(chars.length)] }.joinToString("")
    }
}
