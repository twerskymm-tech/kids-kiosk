package com.kidskiosk.phone

import android.app.Activity
import android.app.AlertDialog
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import android.widget.FrameLayout
import android.widget.HorizontalScrollView
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/**
 * The kids' shop: the parent puts up things to buy (name, price, picture, category, stock, who can buy it,
 * a weekly special with a sale price), and a kid spends her own chore money on them. Each purchase shows up
 * on the parent's phone page, where it can be marked as given or refunded.
 */
object Shop {
    /**
     * kid: "" = any kid. stock: -1 = unlimited. was: the old price when it's on sale (0 = not on sale).
     * added: when the parent put it up (for the NEW badge).
     */
    data class Item(val id: Long, val title: String, val cents: Int, val image: String, val kid: String,
                    val note: String = "", val cat: String = "", val stock: Int = -1, val special: Boolean = false,
                    val was: Int = 0, val hidden: Boolean = false, val added: Long = 0L) {
        val soldOut get() = stock == 0
        val isNew get() = added > 0 && System.currentTimeMillis() - added < 5L * 24 * 3600 * 1000
    }
    /** status: "bought" (money taken), "waiting" (money taken, waiting for the parent's OK), "given", "refunded" */
    data class Purchase(val id: Long, val item: Long, val title: String, val cents: Int, val kid: String, val at: Long, val status: String)

    private fun p(c: Context) = c.getSharedPreferences("shop", Context.MODE_PRIVATE)

    private fun itemFrom(o: JSONObject) = Item(o.getLong("id"), o.optString("title"), o.optInt("cents", 0), o.optString("image", ""),
        o.optString("kid", ""), o.optString("note", ""), o.optString("cat", ""), o.optInt("stock", -1), o.optBoolean("special"),
        o.optInt("was", 0), o.optBoolean("hidden"), o.optLong("added", 0L))
    private fun itemJson(x: Item) = JSONObject().put("id", x.id).put("title", x.title).put("cents", x.cents).put("image", x.image)
        .put("kid", x.kid).put("note", x.note).put("cat", x.cat).put("stock", x.stock).put("special", x.special)
        .put("was", x.was).put("hidden", x.hidden).put("added", x.added)

    /** Everything, including hidden items (for the parent). */
    fun allItems(c: Context): List<Item> = runCatching { JSONArray(p(c).getString("items", "[]")) }.getOrDefault(JSONArray()).let { a ->
        (0 until a.length()).map { itemFrom(a.getJSONObject(it)) }.filter { it.title.isNotBlank() && it.cents > 0 }
    }
    /** What the kids see. */
    fun items(c: Context): List<Item> = allItems(c).filter { !it.hidden }
    fun itemsJson(c: Context): JSONArray = JSONArray().apply { allItems(c).forEach { put(itemJson(it)) } }
    private fun saveItems(c: Context, list: List<Item>) {
        p(c).edit().putString("items", JSONArray().apply { list.forEach { put(itemJson(it)) } }.toString()).apply()
    }
    fun imageFile(c: Context, id: Long) = File(c.filesDir, "shop_$id.jpg")

    fun name(c: Context) = p(c).getString("name", "")?.takeIf { it.isNotBlank() } ?: "Shop"
    fun setName(c: Context, s: String) { p(c).edit().putString("name", s.trim()).apply() }

    /** New list from the parent. Returns pictures to download. */
    fun setItems(c: Context, a: JSONArray): List<Pair<Long, String>> {
        val old = allItems(c).associateBy { it.id }
        val now = (0 until a.length()).map { itemFrom(a.getJSONObject(it)) }
            .map { if (it.added == 0L) it.copy(added = old[it.id]?.added?.takeIf { t -> t > 0 } ?: System.currentTimeMillis()) else it }
        saveItems(c, now)
        old.keys.filter { id -> now.none { it.id == id } }.forEach { imageFile(c, it).delete() }
        return now.filter { it.image.isNotEmpty() && (old[it.id]?.image != it.image || !imageFile(c, it.id).exists()) }.map { it.id to it.image }
    }

    private fun changeStock(c: Context, itemId: Long, by: Int) {
        saveItems(c, allItems(c).map { if (it.id == itemId && it.stock >= 0) it.copy(stock = (it.stock + by).coerceAtLeast(0)) else it })
    }

    /** Purchases need the parent's OK first (the money is held until then). */
    fun approve(c: Context) = p(c).getBoolean("approve", false)
    fun setApprove(c: Context, on: Boolean) { p(c).edit().putBoolean("approve", on).apply() }

    fun purchases(c: Context): List<Purchase> = runCatching { JSONArray(p(c).getString("purchases", "[]")) }.getOrDefault(JSONArray()).let { a ->
        (0 until a.length()).map { a.getJSONObject(it) }.map {
            Purchase(it.getLong("id"), it.optLong("item"), it.optString("title"), it.optInt("cents"), it.optString("kid"), it.optLong("at"), it.optString("status", "bought"))
        }
    }
    fun purchasesJson(c: Context): JSONArray = JSONArray().apply { purchases(c).forEach { put(toJson(it)) } }
    private fun toJson(x: Purchase) = JSONObject().put("id", x.id).put("item", x.item).put("title", x.title).put("cents", x.cents)
        .put("kid", x.kid).put("at", x.at).put("status", x.status)
    private fun savePurchases(c: Context, list: List<Purchase>) {
        p(c).edit().putString("purchases", JSONArray().apply { list.takeLast(60).forEach { put(toJson(it)) } }.toString()).apply()
    }

    /** She buys it: the money comes off her total. Returns false if she doesn't have enough or it's sold out. */
    fun buy(c: Context, item: Item, kid: String): Boolean {
        val fresh = allItems(c).firstOrNull { it.id == item.id } ?: return false
        if (fresh.soldOut || Chores.earned(c, kid) < fresh.cents) return false
        Chores.spend(c, kid, fresh.cents)
        changeStock(c, fresh.id, -1)
        var id = System.currentTimeMillis()
        val list = purchases(c)
        while (list.any { it.id == id }) id++
        savePurchases(c, list + Purchase(id, fresh.id, fresh.title, fresh.cents, kid, System.currentTimeMillis(), if (approve(c)) "waiting" else "bought"))
        return true
    }

    /** The parent marked purchases: given, approved ("bought"), or refunded (the money goes back, and it's back in stock). */
    fun setStatus(c: Context, j: JSONObject) {
        val list = purchases(c).map { x ->
            val st = j.optString(x.id.toString(), "")
            if (st.isEmpty() || st == x.status || x.status == "refunded") x
            else {
                if (st == "refunded") { Chores.spend(c, x.kid, -x.cents); changeStock(c, x.item, +1) }
                x.copy(status = st)
            }
        }
        savePurchases(c, list)
    }
}

/** A striped shop awning with a scalloped edge, across the top of the store. */
class Awning(c: Context) : View(c) {
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    override fun onDraw(canvas: Canvas) {
        val stripe = width / 12f
        val body = height * 0.7f
        val r = stripe / 2f
        for (i in 0 until 12) {
            paint.color = if (i % 2 == 0) 0xFFFF6B9A.toInt() else Color.WHITE
            canvas.drawRect(i * stripe, 0f, (i + 1) * stripe, body, paint)
            canvas.drawCircle(i * stripe + r, body, r, paint)
        }
        paint.color = 0x22000000
        canvas.drawRect(0f, body - 3f, width.toFloat(), body, paint)
    }
}

/** The store: a shop sign, her money, this week's special, category tabs and the shelves. */
class ShopActivity : Activity() {
    private lateinit var root: FrameLayout
    private lateinit var wallet: LinearLayout
    private lateinit var list: LinearLayout
    private var category = ""          // "" = All
    private val ink = 0xFF1F2333.toInt()
    private val pics = mutableMapOf<Long, Pair<Long, Bitmap?>>()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        window.statusBarColor = 0xFFFF6B9A.toInt(); window.navigationBarColor = 0xFFC0475E.toInt()
        root = FrameLayout(this).apply {
            background = GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM, intArrayOf(0xFF3A1D6E.toInt(), 0xFF7A2A7A.toInt(), 0xFFC0475E.toInt()))
        }
        val col = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        col.addView(Awning(this), lp(MATCH, dp(44)))
        val top = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL; setPadding(dp(16), dp(10), dp(16), 0) }
        top.addView(text("✕", 20f, Color.WHITE, bold = true, gravity = Gravity.CENTER).apply {
            background = rounded(0x33FFFFFF, dpf(22f)); setOnClickListener { finish() }
        }, lp(dp(44), dp(44)))
        top.addView(text("🛍️ " + Shop.name(this), 26f, Color.WHITE, bold = true).apply {
            maxLines = 1; ellipsize = android.text.TextUtils.TruncateAt.END; setShadowLayer(0f, 0f, dpf(2f), 0x33000000)
        }, lp(0, WRAP, 1f).apply { marginStart = dp(12) })
        col.addView(top, lp(MATCH, WRAP))
        wallet = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; setPadding(dp(16), 0, dp(16), 0) }
        col.addView(wallet, lp(MATCH, WRAP).apply { topMargin = dp(12) })
        list = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(16), 0, dp(16), dp(16)) }
        col.addView(ScrollView(this).apply { isVerticalScrollBarEnabled = false; addView(list) }, lp(MATCH, 0, 1f).apply { topMargin = dp(12) })
        root.addView(col, FrameLayout.LayoutParams(MATCH, MATCH))
        setContentView(root)
    }

    override fun onResume() { super.onResume(); fill() }

    override fun onPause() {
        super.onPause()
        if (Locks.current(this).reason != Locks.Reason.NONE) finish()
    }

    private fun buyers(item: Shop.Item) = Chores.kids(this).filter { item.kid.isEmpty() || it.equals(item.kid, ignoreCase = true) }
    private fun affordable(item: Shop.Item) = buyers(item).any { Chores.earned(this, it) >= item.cents }

    private fun fill() {
        val kids = Chores.kids(this)
        wallet.removeAllViews()
        kids.forEachIndexed { i, kid ->
            val pill = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(dp(12), dp(6), dp(12), dp(8))
                background = rounded(Chores.color(this@ShopActivity, kid), dpf(16f))
            }
            pill.addView(text(kid, 14f, Color.WHITE, bold = true).apply { maxLines = 1 })
            pill.addView(text("💰 " + Chores.money(Chores.earned(this, kid)), 20f, Color.WHITE, bold = true))
            wallet.addView(pill, lp(0, WRAP, 1f).apply { if (i > 0) marginStart = dp(10) })
        }
        list.removeAllViews()
        val items = Shop.items(this)
        if (items.isEmpty()) {
            list.addView(text("The shop is empty right now.\nCome back soon!", 18f, Color.WHITE, gravity = Gravity.CENTER), lp(MATCH, WRAP).apply { topMargin = dp(40) })
            return
        }
        // this week's special
        items.firstOrNull { it.special }?.let { list.addView(specialCard(it), lp(MATCH, WRAP).apply { topMargin = dp(12); bottomMargin = dp(4) }) }
        // category tabs
        val cats = items.map { it.cat.trim() }.filter { it.isNotEmpty() }.distinct()
        if (category.isNotEmpty() && category !in cats) category = ""
        if (cats.isNotEmpty()) {
            val tabs = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
            (listOf("") + cats).forEach { cat ->
                val on = cat == category
                tabs.addView(text(if (cat.isEmpty()) "All" else cat, 15f, if (on) 0xFF7A2A7A.toInt() else Color.WHITE, bold = true, gravity = Gravity.CENTER).apply {
                    background = rounded(if (on) Color.WHITE else 0x2EFFFFFF, dpf(18f))
                    setPadding(dp(14), dp(7), dp(14), dp(7))
                    setOnClickListener { category = cat; fill() }
                }, lp(WRAP, WRAP).apply { marginEnd = dp(8) })
            }
            list.addView(HorizontalScrollView(this).apply { isHorizontalScrollBarEnabled = false; addView(tabs) }, lp(MATCH, WRAP).apply { topMargin = dp(10) })
        }
        // shelves
        val shown = items.filter { category.isEmpty() || it.cat.trim() == category }
            .sortedWith(compareBy({ it.soldOut }, { !affordable(it) }, { it.cents }))
        shown.chunked(2).forEach { pair ->
            val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
            pair.forEachIndexed { j, it -> row.addView(card(it), lp(0, WRAP, 1f).apply { if (j > 0) marginStart = dp(12) }) }
            if (pair.size == 1) row.addView(View(this), lp(0, 1, 1f).apply { marginStart = dp(12) })
            list.addView(row, lp(MATCH, WRAP).apply { topMargin = dp(12) })
        }
        // what she bought, waiting to be handed over
        val open = Shop.purchases(this).filter { it.status == "bought" || it.status == "waiting" }.takeLast(6).reversed()
        if (open.isNotEmpty()) {
            list.addView(text("🎁 Coming soon", 16f, Color.WHITE, bold = true), lp(MATCH, WRAP).apply { topMargin = dp(18); bottomMargin = dp(6) })
            open.forEach { x ->
                val note = if (x.status == "waiting") "waiting for OK" else "on its way"
                list.addView(text("${x.kid}: ${x.title}  ·  $note", 15f, Color.WHITE).apply {
                    background = rounded(0x2EFFFFFF, dpf(12f)); setPadding(dp(12), dp(8), dp(12), dp(8))
                }, lp(MATCH, WRAP).apply { bottomMargin = dp(6) })
            }
        }
    }

    private fun picture(item: Shop.Item, emojiSize: Float): View {
        val f = Shop.imageFile(this, item.id)
        val bmp = if (f.exists()) {
            val c = pics[item.id]
            if (c != null && c.first == f.lastModified()) c.second else Slides.decode(f, 500).also { pics[item.id] = f.lastModified() to it }
        } else null
        val tint = intArrayOf(0xFFFFF1D6.toInt(), 0xFFE3F6FF.toInt(), 0xFFF3E8FF.toInt(), 0xFFFFE3EC.toInt(), 0xFFE6FCF5.toInt())[(item.id % 5).toInt()]
        val box = FrameLayout(this).apply { background = rounded(tint, dpf(14f)); clipToOutline = true }
        if (bmp != null) box.addView(ImageView(this).apply { setImageBitmap(bmp); scaleType = ImageView.ScaleType.CENTER_CROP }, FrameLayout.LayoutParams(MATCH, MATCH))
        else box.addView(text("🎁", emojiSize, ink, gravity = Gravity.CENTER), FrameLayout.LayoutParams(MATCH, MATCH))
        return box
    }

    private fun priceRow(item: Shop.Item, size: Float): LinearLayout {
        val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        row.addView(text(Chores.money(item.cents), size, Color.WHITE, bold = true).apply {
            background = rounded(0xFF15803D.toInt(), dpf(12f)); setPadding(dp(10), dp(2), dp(10), dp(2))
        })
        if (item.was > item.cents) row.addView(text(Chores.money(item.was), size - 3f, 0xFF9AA0AE.toInt()).apply {
            paintFlags = paintFlags or Paint.STRIKE_THRU_TEXT_FLAG
        }, lp(WRAP, WRAP).apply { marginStart = dp(6) })
        when {
            item.soldOut -> row.addView(text("Sold out", 12f, 0xFFC0475E.toInt(), bold = true), lp(WRAP, WRAP).apply { marginStart = dp(6) })
            item.stock in 1..3 -> row.addView(text("${item.stock} left!", 12f, 0xFFC0475E.toInt(), bold = true), lp(WRAP, WRAP).apply { marginStart = dp(6) })
        }
        return row
    }

    private fun specialCard(item: Shop.Item): View {
        val frame = FrameLayout(this)
        val box = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(10), dp(14), dp(12), dp(10))
            background = rounded(Color.WHITE, dpf(20f))
            setOnClickListener { who(item) }
            if (item.soldOut) alpha = 0.6f
        }
        box.addView(picture(item, 44f), lp(dp(92), dp(92)))
        val col = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        col.addView(text(item.title.replaceFirstChar { it.uppercase() }, 18f, ink, bold = true).apply { maxLines = 2 })
        col.addView(priceRow(item, 16f), lp(WRAP, WRAP).apply { topMargin = dp(4) })
        // how close each girl is
        val kids = buyers(item)
        val best = kids.maxByOrNull { Chores.earned(this, it) }
        if (best != null && !item.soldOut) {
            val have = Chores.earned(this, best)
            val msg = if (have >= item.cents) "$best can buy it now!" else "$best is ${Chores.money(item.cents - have)} away!"
            col.addView(text(msg, 13f, 0xFF4B5060.toInt(), bold = true), lp(WRAP, WRAP).apply { topMargin = dp(5) })
            val frac = (have.toFloat() / item.cents).coerceIn(0f, 1f)
            val bar = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; background = rounded(0x1F000000, dpf(4f)); clipToOutline = true }
            if (frac > 0f) bar.addView(View(this).apply { background = rounded(Chores.color(this@ShopActivity, best), dpf(4f)) }, lp(0, MATCH, frac))
            if (frac < 1f) bar.addView(View(this), lp(0, MATCH, 1f - frac))
            col.addView(bar, lp(MATCH, dp(8)).apply { topMargin = dp(4) })
        }
        box.addView(col, lp(0, WRAP, 1f).apply { marginStart = dp(12) })
        frame.addView(box, FrameLayout.LayoutParams(MATCH, WRAP).apply { topMargin = dp(10) })
        frame.addView(text("⭐ THIS WEEK'S SPECIAL", 12f, 0xFF7A3B00.toInt(), bold = true).apply {
            background = rounded(0xFFFFD43B.toInt(), dpf(10f)); setPadding(dp(10), dp(3), dp(10), dp(3)); rotation = -4f; elevation = dpf(2f)
        }, FrameLayout.LayoutParams(WRAP, WRAP).apply { leftMargin = dp(4) })
        return frame
    }

    private fun card(item: Shop.Item): View {
        val frame = FrameLayout(this)
        val box = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(8), dp(8), dp(8), dp(10))
            background = rounded(Color.WHITE, dpf(18f))
            setOnClickListener { who(item) }
            alpha = when { item.soldOut -> 0.45f; !affordable(item) -> 0.6f; else -> 1f }
        }
        box.addView(picture(item, 44f), lp(MATCH, dp(110)))
        box.addView(text(item.title.replaceFirstChar { it.uppercase() }, 15f, ink, bold = true).apply { maxLines = 2 }, lp(MATCH, WRAP).apply { topMargin = dp(6) })
        if (item.note.isNotBlank()) box.addView(text(item.note, 12f, 0xFF6B7080.toInt()).apply { maxLines = 2 })
        box.addView(priceRow(item, 15f), lp(WRAP, WRAP).apply { topMargin = dp(5) })
        if (item.kid.isNotEmpty()) box.addView(text("for ${item.kid}", 12f, Chores.color(this, item.kid), bold = true), lp(WRAP, WRAP).apply { topMargin = dp(3) })
        frame.addView(box, FrameLayout.LayoutParams(MATCH, WRAP))
        if (item.isNew && !item.soldOut) frame.addView(text("NEW", 11f, Color.WHITE, bold = true).apply {
            background = rounded(0xFFFF6B9A.toInt(), dpf(8f)); setPadding(dp(7), dp(2), dp(7), dp(2)); elevation = dpf(2f)
        }, FrameLayout.LayoutParams(WRAP, WRAP, Gravity.TOP or Gravity.END).apply { topMargin = dp(12); rightMargin = dp(12) })
        return frame
    }

    /** Who is buying? Kids without enough money see how much more they need. */
    private fun who(item: Shop.Item) {
        if (item.soldOut) {
            AlertDialog.Builder(this).setTitle("Sold out").setMessage("${item.title} is sold out for now. Maybe it'll be back soon!")
                .setPositiveButton("OK", null).show()
            return
        }
        val kids = buyers(item)
        if (kids.isEmpty()) return
        if (kids.size == 1) { confirm(item, kids[0]); return }
        val labels = kids.map { k ->
            val have = Chores.earned(this, k)
            if (have >= item.cents) "$k  (${Chores.money(have)})" else "$k  (needs ${Chores.money(item.cents - have)} more)"
        }
        AlertDialog.Builder(this)
            .setTitle("Who is buying ${item.title}?")
            .setItems(labels.toTypedArray()) { _, i -> confirm(item, kids[i]) }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun confirm(item: Shop.Item, kid: String) {
        val have = Chores.earned(this, kid)
        if (have < item.cents) {
            AlertDialog.Builder(this)
                .setTitle("Almost!")
                .setMessage("$kid has ${Chores.money(have)}. ${item.title} costs ${Chores.money(item.cents)}, so ${Chores.money(item.cents - have)} more to go. Keep doing your chores!")
                .setPositiveButton("OK", null).show()
            return
        }
        val note = if (item.note.isNotBlank()) "${item.note}\n\n" else ""
        AlertDialog.Builder(this)
            .setTitle("Buy ${item.title}?")
            .setMessage("$note${Chores.money(item.cents)} comes out of $kid's money. She'll have ${Chores.money(have - item.cents)} left.")
            .setPositiveButton("Buy it!") { _, _ -> if (Shop.buy(this, item, kid)) { Remote.syncNow(this, null); celebrate(item, kid) } else fill() }
            .setNegativeButton("Not now", null)
            .show()
    }

    private fun celebrate(item: Shop.Item, kid: String) {
        fill()
        val color = Chores.color(this, kid)
        root.addView(ConfettiView(this, intArrayOf(color, 0xFFFFD43B.toInt(), 0xFF4DABF7.toInt(), 0xFF69DB7C.toInt(), 0xFFFF6B6B.toInt(), Color.WHITE)),
            FrameLayout.LayoutParams(MATCH, MATCH))
        Cheer.play(false)
        val pop = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL; gravity = Gravity.CENTER_HORIZONTAL
            setPadding(dp(26), dp(20), dp(26), dp(20))
            background = rounded(Color.WHITE, dpf(28f), dp(4), color); elevation = dpf(16f)
        }
        pop.addView(text("🎉", 54f, ink, gravity = Gravity.CENTER))
        pop.addView(text("$kid bought ${item.title}!", 24f, color, bold = true, gravity = Gravity.CENTER))
        val next = if (Shop.approve(this)) "Tatty or Mommy will say OK, then give it to you." else "Tatty or Mommy will give it to you."
        pop.addView(text(next, 16f, 0xFF4B5060.toInt(), gravity = Gravity.CENTER), lp(WRAP, WRAP).apply { topMargin = dp(6) })
        pop.setOnClickListener { root.removeView(pop) }
        root.addView(pop, FrameLayout.LayoutParams(WRAP, WRAP, Gravity.CENTER).apply { leftMargin = dp(24); rightMargin = dp(24) })
        pop.scaleX = 0.3f; pop.scaleY = 0.3f; pop.alpha = 0f
        pop.animate().scaleX(1f).scaleY(1f).alpha(1f).setDuration(420).setInterpolator(android.view.animation.OvershootInterpolator(2.2f)).start()
        pop.postDelayed({ pop.animate().alpha(0f).setDuration(250).withEndAction { root.removeView(pop) }.start() }, 4000)
    }
}

/** An emoji drawn into a bitmap, for a tile icon. */
fun Context.emojiBitmap(e: String, size: Int = 256): Bitmap {
    val b = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
    val p = Paint(Paint.ANTI_ALIAS_FLAG).apply { textSize = size * 0.8f; textAlign = Paint.Align.CENTER }
    val c = Canvas(b)
    c.drawText(e, size / 2f, size / 2f - (p.descent() + p.ascent()) / 2f, p)
    return b
}
