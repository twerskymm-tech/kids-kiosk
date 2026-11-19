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
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/**
 * The kids' shop: the parent puts up things to buy (name, price, picture, and who can buy it),
 * and a kid spends her own chore money on them. Each purchase shows up on the parent's phone page,
 * where it can be marked as given or refunded.
 */
object Shop {
    /** kid: "" = any kid can buy it, otherwise only that kid. */
    data class Item(val id: Long, val title: String, val cents: Int, val image: String, val kid: String)
    /** status: "bought" (money taken), "waiting" (money taken, waiting for the parent's OK), "given", "refunded" */
    data class Purchase(val id: Long, val item: Long, val title: String, val cents: Int, val kid: String, val at: Long, val status: String)

    private fun p(c: Context) = c.getSharedPreferences("shop", Context.MODE_PRIVATE)

    fun items(c: Context): List<Item> = runCatching { JSONArray(p(c).getString("items", "[]")) }.getOrDefault(JSONArray()).let { a ->
        (0 until a.length()).map { a.getJSONObject(it) }.map {
            Item(it.getLong("id"), it.optString("title"), it.optInt("cents", 0), it.optString("image", ""), it.optString("kid", ""))
        }.filter { it.title.isNotBlank() && it.cents > 0 }
    }
    fun itemsJson(c: Context): JSONArray = JSONArray().apply {
        items(c).forEach { put(JSONObject().put("id", it.id).put("title", it.title).put("cents", it.cents).put("image", it.image).put("kid", it.kid)) }
    }
    fun imageFile(c: Context, id: Long) = File(c.filesDir, "shop_$id.jpg")

    /** New list from the parent. Returns pictures to download. */
    fun setItems(c: Context, a: JSONArray): List<Pair<Long, String>> {
        val old = items(c).associateBy { it.id }
        p(c).edit().putString("items", a.toString()).apply()
        val now = items(c)
        old.keys.filter { id -> now.none { it.id == id } }.forEach { imageFile(c, it).delete() }
        return now.filter { it.image.isNotEmpty() && (old[it.id]?.image != it.image || !imageFile(c, it.id).exists()) }.map { it.id to it.image }
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

    /** She buys it: the money comes off her total. Returns false if she doesn't have enough. */
    fun buy(c: Context, item: Item, kid: String): Boolean {
        if (Chores.earned(c, kid) < item.cents) return false
        Chores.spend(c, kid, item.cents)
        var id = System.currentTimeMillis()
        val list = purchases(c)
        while (list.any { it.id == id }) id++
        savePurchases(c, list + Purchase(id, item.id, item.title, item.cents, kid, System.currentTimeMillis(), if (approve(c)) "waiting" else "bought"))
        return true
    }

    /** The parent marked purchases: given, approved ("bought"), or refunded (the money goes back). */
    fun setStatus(c: Context, j: JSONObject) {
        val list = purchases(c).map { x ->
            val st = j.optString(x.id.toString(), "")
            if (st.isEmpty() || st == x.status || x.status == "refunded") x
            else {
                if (st == "refunded") Chores.spend(c, x.kid, -x.cents)
                x.copy(status = st)
            }
        }
        savePurchases(c, list)
    }
}

/** The shop screen: her money at the top, then the things she can buy. */
class ShopActivity : Activity() {
    private lateinit var root: FrameLayout
    private lateinit var wallet: LinearLayout
    private lateinit var list: LinearLayout
    private val ink = 0xFF1F2333.toInt()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        window.statusBarColor = 0xFF3A1D6E.toInt(); window.navigationBarColor = 0xFF7A2A7A.toInt()
        root = FrameLayout(this).apply {
            background = GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM, intArrayOf(0xFF3A1D6E.toInt(), 0xFF7A2A7A.toInt(), 0xFFC0475E.toInt()))
        }
        val col = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(16), dp(18), dp(16), dp(10)) }
        val top = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        top.addView(text("✕", 22f, Color.WHITE, bold = true, gravity = Gravity.CENTER).apply {
            background = rounded(0x33FFFFFF, dpf(24f)); setOnClickListener { finish() }
        }, lp(dp(48), dp(48)))
        top.addView(text("🛍️  Shop", 28f, Color.WHITE, bold = true), lp(0, WRAP, 1f).apply { marginStart = dp(14) })
        col.addView(top, lp(MATCH, WRAP))
        wallet = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        col.addView(wallet, lp(MATCH, WRAP).apply { topMargin = dp(14) })
        list = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        col.addView(ScrollView(this).apply { isVerticalScrollBarEnabled = false; addView(list) }, lp(MATCH, 0, 1f).apply { topMargin = dp(14) })
        root.addView(col, FrameLayout.LayoutParams(MATCH, MATCH))
        setContentView(root)
    }

    override fun onResume() { super.onResume(); fill() }

    override fun onPause() {
        super.onPause()
        if (Locks.current(this).reason != Locks.Reason.NONE) finish()
    }

    private fun fill() {
        val kids = Chores.kids(this)
        wallet.removeAllViews()
        kids.forEachIndexed { i, kid ->
            val pill = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL; gravity = Gravity.CENTER
                setPadding(dp(8), dp(8), dp(8), dp(8))
                background = rounded(Chores.color(this@ShopActivity, kid), dpf(16f))
            }
            pill.addView(text(kid, 16f, Color.WHITE, bold = true, gravity = Gravity.CENTER).apply { maxLines = 1 })
            pill.addView(text("💰 " + Chores.money(Chores.earned(this, kid)), 18f, Color.WHITE, bold = true, gravity = Gravity.CENTER))
            wallet.addView(pill, lp(0, WRAP, 1f).apply { if (i > 0) marginStart = dp(10) })
        }
        list.removeAllViews()
        val items = Shop.items(this)
        if (items.isEmpty()) {
            list.addView(text("Nothing in the shop yet.", 18f, Color.WHITE, gravity = Gravity.CENTER), lp(MATCH, WRAP).apply { topMargin = dp(40) })
            return
        }
        items.chunked(2).forEach { pair ->
            val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
            pair.forEachIndexed { j, it -> row.addView(card(it), lp(0, WRAP, 1f).apply { if (j > 0) marginStart = dp(12) }) }
            if (pair.size == 1) row.addView(View(this), lp(0, 1, 1f).apply { marginStart = dp(12) })
            list.addView(row, lp(MATCH, WRAP).apply { bottomMargin = dp(12) })
        }
        // what she bought, waiting to be handed over
        val open = Shop.purchases(this).filter { it.status == "bought" || it.status == "waiting" }.takeLast(6).reversed()
        if (open.isNotEmpty()) {
            list.addView(text("Coming soon", 15f, 0xDDFFFFFF.toInt(), bold = true), lp(MATCH, WRAP).apply { topMargin = dp(6); bottomMargin = dp(6) })
            open.forEach { x ->
                val note = if (x.status == "waiting") "waiting for OK" else "on its way"
                list.addView(text("🎁  ${x.kid}: ${x.title}  ($note)", 15f, Color.WHITE).apply {
                    background = rounded(0x33FFFFFF, dpf(12f)); setPadding(dp(12), dp(8), dp(12), dp(8))
                }, lp(MATCH, WRAP).apply { bottomMargin = dp(6) })
            }
        }
    }

    private fun card(item: Shop.Item): View {
        val box = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL; gravity = Gravity.CENTER_HORIZONTAL
            setPadding(dp(10), dp(10), dp(10), dp(12))
            background = rounded(Color.WHITE, dpf(20f))
            setOnClickListener { who(item, this) }
        }
        val pic = Shop.imageFile(this, item.id).takeIf { it.exists() }?.let { Slides.decode(it, 500) }
        val img = FrameLayout(this).apply { background = rounded(0xFFF1EEFB.toInt(), dpf(14f)); clipToOutline = true }
        if (pic != null) img.addView(ImageView(this).apply { setImageBitmap(pic); scaleType = ImageView.ScaleType.CENTER_CROP }, FrameLayout.LayoutParams(MATCH, MATCH))
        else img.addView(text("🎁", 52f, ink, gravity = Gravity.CENTER), FrameLayout.LayoutParams(MATCH, MATCH))
        box.addView(img, lp(MATCH, dp(130)))
        box.addView(text(item.title.replaceFirstChar { it.uppercase() }, 16f, ink, bold = true, gravity = Gravity.CENTER).apply { maxLines = 2 },
            lp(MATCH, WRAP).apply { topMargin = dp(8) })
        box.addView(text(Chores.money(item.cents), 17f, Color.WHITE, bold = true, gravity = Gravity.CENTER).apply {
            background = rounded(0xFF15803D.toInt(), dpf(14f)); setPadding(dp(14), dp(3), dp(14), dp(3))
        }, lp(WRAP, WRAP).apply { topMargin = dp(6) })
        if (item.kid.isNotEmpty()) box.addView(text("for ${item.kid}", 13f, Chores.color(this, item.kid), bold = true, gravity = Gravity.CENTER),
            lp(WRAP, WRAP).apply { topMargin = dp(4) })
        return box
    }

    /** Who is buying? Kids without enough money see how much more they need. */
    private fun who(item: Shop.Item, from: View) {
        val kids = Chores.kids(this).filter { item.kid.isEmpty() || it.equals(item.kid, ignoreCase = true) }
        if (kids.isEmpty()) return
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
        AlertDialog.Builder(this)
            .setTitle("Buy ${item.title}?")
            .setMessage("${Chores.money(item.cents)} comes out of $kid's money. She'll have ${Chores.money(have - item.cents)} left.")
            .setPositiveButton("Buy it!") { _, _ -> if (Shop.buy(this, item, kid)) { Remote.syncNow(this, null); celebrate(item, kid) } }
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

