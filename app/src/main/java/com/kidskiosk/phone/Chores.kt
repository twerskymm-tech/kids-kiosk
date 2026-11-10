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
    /** cents: what the kid earns for doing it (0 = nothing). */
    data class Chore(val id: Long, val kid: String, val title: String, val days: Int, val cents: Int = 0) {
        /** days: bit 0 = Sunday ... bit 6 = Shabbos (Saturday) */
        fun isOn(d: LocalDate): Boolean = days and (1 shl (d.dayOfWeek.value % 7)) != 0
    }

    /** kid value for a chore anyone can do ("up for grabs"). */
    const val ANYONE = "*"

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
            Chore(it.getLong("id"), it.getString("kid"), it.getString("title"), it.optInt("days", EVERY_DAY), it.optInt("cents", 0))
        }
    }

    fun setAll(c: Context, list: List<Chore>) = save(c, list)

    private fun save(c: Context, list: List<Chore>) {
        val a = JSONArray()
        list.forEach { a.put(JSONObject().put("id", it.id).put("kid", it.kid).put("title", it.title).put("days", it.days).put("cents", it.cents)) }
        p(c).edit().putString("list", a.toString()).apply()
    }

    fun add(c: Context, kid: String, title: String, days: Int, cents: Int = 0) {
        val list = all(c)
        var id = System.currentTimeMillis()
        while (list.any { it.id == id }) id++
        save(c, list + Chore(id, kid, title.trim(), days, cents.coerceAtLeast(0)))
    }

    // ── Money earned and not paid yet, per kid (in cents) ──
    fun earned(c: Context, kid: String): Int = earnedAll(c).optInt(kid.lowercase(), 0)
    fun earnedAll(c: Context): JSONObject = runCatching { JSONObject(p(c).getString("earned", "{}")) }.getOrDefault(JSONObject())
    private fun addEarned(c: Context, kid: String, cents: Int) {
        val j = earnedAll(c)
        j.put(kid.lowercase(), (j.optInt(kid.lowercase(), 0) + cents).coerceAtLeast(0))
        p(c).edit().putString("earned", j.toString()).apply()
    }
    /** The parent paid the kid (from the control panel): take it off what's owed. */
    fun paid(c: Context, kid: String, cents: Int) = addEarned(c, kid, -cents)

    fun money(cents: Int): String = if (cents % 100 == 0) "\$${cents / 100}" else "\$%d.%02d".format(cents / 100, cents % 100)

    fun remove(c: Context, id: Long) { save(c, all(c).filter { it.id != id }) }

    fun today(c: Context, kid: String): List<Chore> {
        val d = LocalDate.now()
        return all(c).filter { it.kid.equals(kid, ignoreCase = true) && it.isOn(d) }
    }

    /** Today's up-for-grabs chores. */
    fun grabs(c: Context): List<Chore> = today(c, ANYONE)

    // ── Who did each up-for-grabs chore today ──
    private fun claimKey() = "claims_" + LocalDate.now()
    fun claims(c: Context): JSONObject = runCatching { JSONObject(p(c).getString(claimKey(), "{}")) }.getOrDefault(JSONObject())
    fun claimedBy(c: Context, id: Long): String = claims(c).optString(id.toString(), "")

    // ── Each kid's color (#RRGGBB), set by the parent ──
    private val PALETTE = intArrayOf(0xFFFF6B9A.toInt(), 0xFF4D96FF.toInt(), 0xFFFFB84C.toInt(), 0xFF6BCB77.toInt(), 0xFFB980F0.toInt(), 0xFF00C2CB.toInt())
    fun colorsJson(c: Context): JSONObject = runCatching { JSONObject(p(c).getString("kid_colors", "{}")) }.getOrDefault(JSONObject())
    fun setColors(c: Context, j: JSONObject) { p(c).edit().putString("kid_colors", j.toString()).apply() }
    fun color(c: Context, kid: String): Int {
        val hex = colorsJson(c).optString(kid.lowercase(), "")
        runCatching { if (hex.isNotEmpty()) return android.graphics.Color.parseColor(hex) }
        val i = kids(c).indexOfFirst { it.equals(kid, ignoreCase = true) }.coerceAtLeast(0)
        return PALETTE[i % PALETTE.size]
    }

    // ── Ticks for today ──
    private fun doneKey() = "done_" + LocalDate.now()
    fun done(c: Context): Set<Long> =
        (p(c).getString(doneKey(), "") ?: "").split(",").mapNotNull { it.toLongOrNull() }.toSet()
    /** Checks a chore off (or back on). Returns true when it is now done. Pays (or takes back) its price. */
    fun toggle(c: Context, id: Long, by: String? = null): Boolean {
        val d = done(c).toMutableSet()
        val nowDone = !d.remove(id)
        if (nowDone) d.add(id)
        val claims = claims(c)
        val ch = all(c).firstOrNull { it.id == id }
        if (ch != null) {
            // Up for grabs: the money goes to (or comes back from) whoever did it
            val who = if (ch.kid == ANYONE) (if (nowDone) by.orEmpty() else claims.optString(id.toString(), "")) else ch.kid
            if (ch.kid == ANYONE) { if (nowDone) claims.put(id.toString(), who) else claims.remove(id.toString()) }
            if (ch.cents > 0 && who.isNotEmpty()) addEarned(c, who, if (nowDone) ch.cents else -ch.cents)
        }
        val e = p(c).edit()
        // drop older days' ticks
        p(c).all.keys.filter { (it.startsWith("done_") && it != doneKey()) || (it.startsWith("claims_") && it != claimKey()) }.forEach { e.remove(it) }
        e.putString(doneKey(), d.joinToString(",")).putString(claimKey(), claims.toString()).apply()
        return nowDone
    }

    fun daysText(days: Int): String = when (days) {
        EVERY_DAY -> "every day"
        WEEKDAYS -> "Sun–Fri"
        else -> listOf("Sun", "Mon", "Tue", "Wed", "Thu", "Fri", "Shabbos").filterIndexed { i, _ -> days and (1 shl i) != 0 }.joinToString(", ")
    }
}
