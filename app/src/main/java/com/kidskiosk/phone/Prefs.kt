package com.kidskiosk.phone

import android.content.Context
import android.content.SharedPreferences
import java.security.MessageDigest
import java.security.SecureRandom

/** Everything the parent sets, kept on the phone only. */
object Prefs {
    private fun p(c: Context): SharedPreferences = c.getSharedPreferences("kiosk", Context.MODE_PRIVATE)

    // ── Parent PIN (only a salted hash is kept) ──
    fun hasPin(c: Context) = p(c).contains("pin_hash")
    fun setPin(c: Context, pin: String) {
        val salt = ByteArray(16).also { SecureRandom().nextBytes(it) }.joinToString("") { "%02x".format(it) }
        p(c).edit().putString("pin_salt", salt).putString("pin_hash", hash(salt, pin)).apply()
    }
    fun checkPin(c: Context, pin: String): Boolean {
        val salt = p(c).getString("pin_salt", null) ?: return false
        return hash(salt, pin) == p(c).getString("pin_hash", null)
    }
    private fun hash(salt: String, pin: String): String =
        MessageDigest.getInstance("SHA-256").digest((salt + pin).toByteArray()).joinToString("") { "%02x".format(it) }

    /** After 5 wrong PINs the PIN pad rests for a minute. */
    var wrongTries = 0
    var pinBlockedUntil = 0L

    // ── Kids' apps ──
    fun allowed(c: Context): Set<String>? = p(c).getStringSet("allowed", null)?.toSet()
    fun setAllowed(c: Context, s: Set<String>) { p(c).edit().putStringSet("allowed", HashSet(s)).apply() }

    // ── Call buttons ──
    fun callName(c: Context, i: Int) = p(c).getString("call_name_$i", if (i == 0) "Mommy" else "Tatty") ?: ""
    fun callNumber(c: Context, i: Int) = p(c).getString("call_number_$i", "") ?: ""
    fun setCall(c: Context, i: Int, name: String, number: String) {
        p(c).edit().putString("call_name_$i", name).putString("call_number_$i", number).apply()
    }

    /** Which app places the calls (Google Voice by default; Voiply or any calling app the parent picks). */
    fun callApp(c: Context) = p(c).getString("call_app", Kiosk.VOICE) ?: Kiosk.VOICE
    fun setCallApp(c: Context, pkg: String) { p(c).edit().putString("call_app", pkg).apply() }

    /** Kids who each have their own Greenlight login on this phone (comma separated), and who used it last. */
    fun greenlightKids(c: Context): List<String> =
        (p(c).getString("gl_kids", "") ?: "").split(",").map { it.trim() }.filter { it.isNotEmpty() }
    fun setGreenlightKids(c: Context, s: String) { p(c).edit().putString("gl_kids", s).apply() }
    fun greenlightLast(c: Context) = p(c).getString("gl_last", "") ?: ""
    fun setGreenlightLast(c: Context, s: String) { p(c).edit().putString("gl_last", s).apply() }

    // ── Bedtime (minutes after midnight) ──
    fun bedtimeOn(c: Context) = p(c).getBoolean("bed_on", true)
    fun bedStart(c: Context) = p(c).getInt("bed_start", 20 * 60)
    fun bedEnd(c: Context) = p(c).getInt("bed_end", 7 * 60)
    fun setBedtime(c: Context, on: Boolean, start: Int, end: Int) {
        p(c).edit().putBoolean("bed_on", on).putInt("bed_start", start).putInt("bed_end", end).apply()
    }

    // ── Shabbos / Yom Tov ──
    fun shabbosOn(c: Context) = p(c).getBoolean("shab_on", true)
    fun setShabbosOn(c: Context, on: Boolean) { p(c).edit().putBoolean("shab_on", on).apply() }
    fun hasPlace(c: Context) = p(c).contains("lat")
    fun lat(c: Context) = p(c).getFloat("lat", 0f).toDouble()
    fun lon(c: Context) = p(c).getFloat("lon", 0f).toDouble()
    fun setPlace(c: Context, lat: Double, lon: Double) { p(c).edit().putFloat("lat", lat.toFloat()).putFloat("lon", lon.toFloat()).apply() }
    fun israel(c: Context) = p(c).getBoolean("israel", false)
    fun setIsrael(c: Context, v: Boolean) { p(c).edit().putBoolean("israel", v).apply() }
    fun candleMin(c: Context) = p(c).getInt("candle_min", 18)
    fun havdalahMin(c: Context) = p(c).getInt("havdalah_min", 50)
    fun setMinutes(c: Context, candle: Int, havdalah: Int) { p(c).edit().putInt("candle_min", candle).putInt("havdalah_min", havdalah).apply() }

    // ── Pause all locks until a time ──
    fun pausedUntil(c: Context) = p(c).getLong("paused_until", 0L)
    fun setPausedUntil(c: Context, ms: Long) { p(c).edit().putLong("paused_until", ms).apply() }

    // ── Parent mode open until (kids mode comes back by itself after that) ──
    fun parentUntil(c: Context) = p(c).getLong("parent_until", 0L)
    fun setParentUntil(c: Context, ms: Long) { p(c).edit().putLong("parent_until", ms).apply() }

    // ── Look ──
    fun kidNames(c: Context) = p(c).getString("kid_names", "") ?: ""
    fun setKidNames(c: Context, s: String) { p(c).edit().putString("kid_names", s).apply() }
    fun photoVersion(c: Context) = p(c).getLong("photo_version", 0L)
    fun bumpPhoto(c: Context) { p(c).edit().putLong("photo_version", System.currentTimeMillis()).apply() }
}
