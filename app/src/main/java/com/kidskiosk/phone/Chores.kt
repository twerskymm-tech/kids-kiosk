package com.kidskiosk.phone

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.time.LocalDate
import kotlin.random.Random

/**
 * The kids' chores board: the parent sets each kid's chores (and which days), the home screen shows
 * what is still pending today, and the kids tap to check them off. Ticks start fresh every day.
 */
object Chores {
    /** cents: what the kid earns for doing it (0 = nothing). */
    /** date: "YYYY-MM-DD" for a chore that is only for that one day (then days is ignored). */
    data class Chore(val id: Long, val kid: String, val title: String, val days: Int, val cents: Int = 0, val date: String = "") {
        /** days: bit 0 = Sunday ... bit 6 = Shabbos (Saturday) */
        fun isOn(d: LocalDate): Boolean =
            if (date.isNotEmpty()) date == d.toString() else days and (1 shl (d.dayOfWeek.value % 7)) != 0
        /** A one-day chore whose day is over. */
        fun expired(): Boolean = date.isNotEmpty() && date < LocalDate.now().toString()
    }

    fun fromJson(o: JSONObject) = Chore(o.getLong("id"), o.optString("kid"), o.optString("title"), o.optInt("days", EVERY_DAY),
        o.optInt("cents", 0), o.optString("date", ""))
    fun toJson(ch: Chore): JSONObject = JSONObject().put("id", ch.id).put("kid", ch.kid).put("title", ch.title).put("days", ch.days)
        .put("cents", ch.cents).apply { if (ch.date.isNotEmpty()) put("date", ch.date) }

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
        // one-day chores disappear once their day is over
        return (0 until a.length()).map { fromJson(a.getJSONObject(it)) }.filter { !it.expired() }
    }

    fun setAll(c: Context, list: List<Chore>) = save(c, list)

    private fun save(c: Context, list: List<Chore>) {
        val a = JSONArray()
        list.forEach { a.put(toJson(it)) }
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
    /** life: also count it toward her savings goal (chores and bonuses do, payouts don't). */
    private fun addEarned(c: Context, kid: String, cents: Int, life: Boolean = true) {
        val k = kid.lowercase()
        if (life) {
            val l = lifetimeAll(c)
            l.put(k, (lifetime(c, kid) + cents).coerceAtLeast(0))
            p(c).edit().putString("lifetime", l.toString()).apply()
        }
        val j = earnedAll(c)
        j.put(k, (j.optInt(k, 0) + cents).coerceAtLeast(0))
        p(c).edit().putString("earned", j.toString()).apply()
    }
    /** The parent paid the kid (from the control panel): take it off what's owed. */
    fun paid(c: Context, kid: String, cents: Int) = addEarned(c, kid, -cents, life = false)

    // ── Everything she ever earned (payouts don't take it down): drives the savings goal ──
    private fun lifetimeAll(c: Context): JSONObject = runCatching { JSONObject(p(c).getString("lifetime", "{}")) }.getOrDefault(JSONObject())
    fun lifetime(c: Context, kid: String): Int = lifetimeAll(c).optInt(kid.lowercase(), earned(c, kid))

    // ── Savings goal per kid: what she is saving for, its price and a picture ──
    data class Goal(val title: String, val cents: Int, val image: String, val base: Int)
    private fun goalsAll(c: Context): JSONObject = runCatching { JSONObject(p(c).getString("goals", "{}")) }.getOrDefault(JSONObject())
    fun goal(c: Context, kid: String): Goal? {
        val o = goalsAll(c).optJSONObject(kid.lowercase()) ?: return null
        val g = Goal(o.optString("title").trim(), o.optInt("cents", 0), o.optString("image", ""), o.optInt("base", 0))
        return if (g.title.isEmpty() || g.cents <= 0) null else g
    }
    /** How much she saved toward her goal so far. */
    fun saved(c: Context, kid: String): Int = goal(c, kid)?.let { (lifetime(c, kid) - it.base).coerceAtLeast(0) } ?: 0
    fun goalFile(c: Context, kid: String) = File(c.filesDir, "goal_" + kid.lowercase().replace(Regex("[^a-z0-9]"), "_") + ".jpg")
    /** For the control panel: each goal with how far along it is. */
    fun goalsJson(c: Context): JSONObject = JSONObject().apply {
        val all = goalsAll(c)
        all.keys().forEach { k ->
            val o = all.optJSONObject(k) ?: return@forEach
            put(k, JSONObject().put("title", o.optString("title")).put("cents", o.optInt("cents")).put("image", o.optString("image"))
                .put("saved", (lifetime(c, k) - o.optInt("base", 0)).coerceAtLeast(0)))
        }
    }
    /** New goals from the parent (the whole set). A new goal name starts saving from zero. Returns the pictures to download. */
    fun setGoals(c: Context, j: JSONObject): List<Pair<String, String>> {
        val old = goalsAll(c)
        val out = JSONObject()
        val downloads = mutableListOf<Pair<String, String>>()
        j.keys().forEach { k0 ->
            val k = k0.lowercase()
            val o = j.optJSONObject(k0) ?: return@forEach
            val title = o.optString("title").trim()
            if (title.isEmpty()) return@forEach
            val prev = old.optJSONObject(k)
            val same = prev != null && prev.optString("title").trim().equals(title, ignoreCase = true) && !o.optBoolean("restart")
            val image = o.optString("image", "")
            out.put(k, JSONObject().put("title", title).put("cents", o.optInt("cents", 0).coerceAtLeast(0)).put("image", image)
                .put("base", if (same) prev!!.optInt("base", 0) else lifetime(c, k)))
            if (image.isEmpty()) goalFile(c, k).delete()
            else if (image != prev?.optString("image") || !goalFile(c, k).exists()) downloads += k to image
        }
        old.keys().forEach { k -> if (!out.has(k)) goalFile(c, k).delete() }
        p(c).edit().putString("goals", out.toString()).apply()
        return downloads
    }

    // ── Streaks: days in a row she finished all her chores. Shabbos, Yom Tov and days without chores don't break it ──
    const val LUCKY_CENTS = 25
    fun streakBonus(c: Context) = p(c).getInt("streak_bonus", 50)
    fun setStreakBonus(c: Context, cents: Int) { p(c).edit().putInt("streak_bonus", cents.coerceIn(0, 100000)).apply() }
    fun luckyOn(c: Context) = p(c).getBoolean("lucky_on", true)
    fun setLuckyOn(c: Context, on: Boolean) { p(c).edit().putBoolean("lucky_on", on).apply() }

    private fun completeSet(c: Context, kid: String): MutableSet<String> =
        (p(c).getString("complete_" + kid.lowercase(), "") ?: "").split(",").filter { it.isNotBlank() }.toMutableSet()
    private fun saveComplete(c: Context, kid: String, set: Set<String>) {
        p(c).edit().putString("complete_" + kid.lowercase(), set.sorted().takeLast(400).joinToString(",")).apply()
    }
    private fun skipDay(c: Context, kid: String, d: LocalDate, list: List<Chore>): Boolean =
        runCatching { Shabbos.restDay(d, Prefs.israel(c)) }.getOrDefault(d.dayOfWeek.value == 6) ||
            list.none { it.kid.equals(kid, ignoreCase = true) && it.isOn(d) }

    /** Her streak: counted back from today (or from yesterday while today is still open). */
    fun streak(c: Context, kid: String): Int {
        val set = completeSet(c, kid)
        if (set.isEmpty()) return 0
        val list = all(c)
        var d = LocalDate.now()
        if (d.toString() !in set) d = d.minusDays(1)
        var n = 0
        var skipped = 0
        while (skipped < 30) {
            when {
                d.toString() in set -> { n++; skipped = 0 }
                skipDay(c, kid, d, list) -> skipped++
                else -> return n
            }
            d = d.minusDays(1)
        }
        return n
    }
    fun streaksJson(c: Context): JSONObject = JSONObject().apply { kids(c).forEach { put(it.lowercase(), streak(c, it)) } }

    /** She just finished all of today's chores: what she got for it. */
    data class Finish(val kid: String, val streak: Int, val bonus: Int, val lucky: Int)
    private var lastFinish: Finish? = null
    fun takeFinish(): Finish? = lastFinish.also { lastFinish = null }

    private fun rewardKey() = "rewards_" + LocalDate.now()
    /** After a tick: did she just finish her day (or undo it)? Gives (or takes back) the streak bonus and lucky day. */
    private fun checkFinish(c: Context, kid: String) {
        val day = LocalDate.now().toString()
        val key = kid.lowercase()
        val mine = today(c, kid)
        val d = done(c)
        val allDone = mine.isNotEmpty() && mine.all { it.id in d }
        val set = completeSet(c, kid)
        val rewards = runCatching { JSONObject(p(c).getString(rewardKey(), "{}")) }.getOrDefault(JSONObject())
        val r = rewards.optJSONObject(key) ?: JSONObject()
        if (allDone && day !in set) {
            set.add(day); saveComplete(c, kid, set)
            val s = streak(c, kid)
            val sb = if (s > 0 && s % 7 == 0) streakBonus(c) else 0
            // one roll per day, so ticking off and on again doesn't give another chance
            if (!r.has("roll")) r.put("roll", if (Random.nextInt(8) == 0) 1 else 0)
            val lucky = if (luckyOn(c) && r.optInt("roll") == 1) LUCKY_CENTS else 0
            r.put("paid", sb + lucky)
            if (sb + lucky > 0) addEarned(c, kid, sb + lucky)
            lastFinish = Finish(kid, s, sb, lucky)
        } else if (!allDone && day in set) {
            set.remove(day); saveComplete(c, kid, set)
            val back = r.optInt("paid", 0)
            if (back > 0) addEarned(c, kid, -back)
            r.put("paid", 0)
        } else return
        rewards.put(key, r)
        val e = p(c).edit()
        p(c).all.keys.filter { it.startsWith("rewards_") && it != rewardKey() }.forEach { e.remove(it) }
        e.putString(rewardKey(), rewards.toString()).apply()
    }

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
        lastFinish = null
        if (ch != null && ch.kid != ANYONE) checkFinish(c, ch.kid)
        return nowDone
    }

    fun daysText(days: Int): String = when (days) {
        EVERY_DAY -> "every day"
        WEEKDAYS -> "Sun–Fri"
        else -> listOf("Sun", "Mon", "Tue", "Wed", "Thu", "Fri", "Shabbos").filterIndexed { i, _ -> days and (1 shl i) != 0 }.joinToString(", ")
    }
}
