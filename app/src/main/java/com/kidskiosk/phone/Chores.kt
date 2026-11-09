package com.kidskiosk.phone

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.time.LocalDate

/**
 * The kids' chores board: the parent sets each kid's chores (and which days), the home screen shows
 * what is still pending today, and the kids tap to check them off. Ticks start fresh every day.
 */
object Chores {
    data class Chore(val id: Long, val kid: String, val title: String, val days: Int) {
        /** days: bit 0 = Sunday ... bit 6 = Shabbos (Saturday) */
        fun isOn(d: LocalDate): Boolean = days and (1 shl (d.dayOfWeek.value % 7)) != 0
    }

    const val EVERY_DAY = 0b1111111
    const val WEEKDAYS = 0b0111111   // Sunday to Friday

    private fun p(c: Context) = c.getSharedPreferences("chores", Context.MODE_PRIVATE)

    fun enabled(c: Context) = p(c).getBoolean("on", true)
    fun setEnabled(c: Context, on: Boolean) { p(c).edit().putBoolean("on", on).apply() }

    /** The kids' names, in order (shared with the Greenlight setting). */
    fun kids(c: Context): List<String> = Prefs.greenlightKids(c)

    fun all(c: Context): List<Chore> {
        val a = runCatching { JSONArray(p(c).getString("list", "[]")) }.getOrDefault(JSONArray())
        return (0 until a.length()).map { a.getJSONObject(it) }.map {
            Chore(it.getLong("id"), it.getString("kid"), it.getString("title"), it.optInt("days", EVERY_DAY))
        }
    }

    fun setAll(c: Context, list: List<Chore>) = save(c, list)

    private fun save(c: Context, list: List<Chore>) {
        val a = JSONArray()
        list.forEach { a.put(JSONObject().put("id", it.id).put("kid", it.kid).put("title", it.title).put("days", it.days)) }
        p(c).edit().putString("list", a.toString()).apply()
    }

    fun add(c: Context, kid: String, title: String, days: Int) {
        save(c, all(c) + Chore(System.currentTimeMillis(), kid, title.trim(), days))
    }

    fun remove(c: Context, id: Long) { save(c, all(c).filter { it.id != id }) }

    fun today(c: Context, kid: String): List<Chore> {
        val d = LocalDate.now()
        return all(c).filter { it.kid.equals(kid, ignoreCase = true) && it.isOn(d) }
    }

    // ── Ticks for today ──
    private fun doneKey() = "done_" + LocalDate.now()
    fun done(c: Context): Set<Long> =
        (p(c).getString(doneKey(), "") ?: "").split(",").mapNotNull { it.toLongOrNull() }.toSet()
    fun toggle(c: Context, id: Long) {
        val d = done(c).toMutableSet()
        if (!d.remove(id)) d.add(id)
        val e = p(c).edit()
        // drop older days' ticks
        p(c).all.keys.filter { it.startsWith("done_") && it != doneKey() }.forEach { e.remove(it) }
        e.putString(doneKey(), d.joinToString(",")).apply()
    }

    fun daysText(days: Int): String = when (days) {
        EVERY_DAY -> "every day"
        WEEKDAYS -> "Sun–Fri"
        else -> listOf("Sun", "Mon", "Tue", "Wed", "Thu", "Fri", "Shabbos").filterIndexed { i, _ -> days and (1 shl i) != 0 }.joinToString(", ")
    }
}
