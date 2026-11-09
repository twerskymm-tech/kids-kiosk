package com.kidskiosk.phone

import android.content.Context
import java.util.Calendar
import java.util.Date
import java.util.TimeZone

/** Bedtime and Shabbos / Yom Tov: is the phone locked right now, and until when. */
object Locks {
    enum class Reason { NONE, BEDTIME, SHABBOS, YOMTOV }
    data class State(val reason: Reason, val until: Long = 0L)

    fun place(c: Context): Shabbos.Place? {
        if (!Prefs.hasPlace(c)) return null
        return Shabbos.Place(Prefs.lat(c), Prefs.lon(c), TimeZone.getDefault(), Prefs.israel(c), Prefs.candleMin(c), Prefs.havdalahMin(c))
    }

    fun current(c: Context, now: Long = System.currentTimeMillis()): State {
        if (now < Prefs.pausedUntil(c)) return State(Reason.NONE)
        if (Prefs.shabbosOn(c)) {
            val pl = place(c)
            val w = pl?.let { runCatching { Shabbos.now(Date(now), it) }.getOrNull() }
            if (w != null) return State(if (w.yomTov) Reason.YOMTOV else Reason.SHABBOS, w.end.time)
        }
        if (Prefs.bedtimeOn(c)) {
            val cal = Calendar.getInstance().apply { timeInMillis = now }
            val m = cal.get(Calendar.HOUR_OF_DAY) * 60 + cal.get(Calendar.MINUTE)
            val s = Prefs.bedStart(c); val e = Prefs.bedEnd(c)
            val inside = if (s == e) false else if (s < e) m in s until e else (m >= s || m < e)
            if (inside) {
                val end = Calendar.getInstance().apply {
                    timeInMillis = now
                    set(Calendar.HOUR_OF_DAY, e / 60); set(Calendar.MINUTE, e % 60); set(Calendar.SECOND, 0); set(Calendar.MILLISECOND, 0)
                    if (timeInMillis <= now) add(Calendar.DAY_OF_YEAR, 1)
                }
                return State(Reason.BEDTIME, end.timeInMillis)
            }
        }
        return State(Reason.NONE)
    }
}
