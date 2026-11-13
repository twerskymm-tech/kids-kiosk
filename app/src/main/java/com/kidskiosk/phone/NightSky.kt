package com.kidskiosk.phone

import android.content.Context
import android.graphics.Canvas
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Path
import android.graphics.Shader
import android.os.SystemClock
import android.view.MotionEvent
import android.view.View
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.PI
import kotlin.math.sin
import kotlin.random.Random

/**
 * Live layer over the bedtime picture: stars that twinkle, a few sparkles that pulse, fireflies that
 * float over the hills, and now and then a shooting star. Positions are fractions of the picture
 * (art/bedtime.py, 360x780). Runs at about 20 frames a second and only while it is on screen.
 * Touch: the moon and the bunny snore with floating Zs; a big star flies to a new spot; the moon's hat
 * changes; the house turns its light off with a "shhh".
 */
class NightSky(c: Context) : View(c) {
    private class Star(val fx: Float, val fy: Float, val r: Float, val base: Float, val speed: Float, val phase: Float)
    private class Fly(val fx: Float, val fy: Float, val ax: Float, val ay: Float, val speed: Float, val phase: Float)
    private class Shoot(val x0: Float, val y0: Float, val dx: Float, val dy: Float, val start: Long)

    private val rnd = Random(7)
    private val stars = List(120) {
        val fy = rnd.nextFloat() * 0.82f
        Star(rnd.nextFloat(), fy, listOf(0.6f, 0.8f, 1.0f, 1.3f)[rnd.nextInt(4)],
            if (fy < 0.27f) 0.35f else 0.65f, 0.6f + rnd.nextFloat() * 1.8f, rnd.nextFloat() * 6.28f)
    }
    // the sparkles from the drawing: x, y (in 360x780), size
    private val sparkles = listOf(Triple(40f, 250f, 5f), Triple(318f, 470f, 4f), Triple(150f, 232f, 3.5f),
        Triple(210f, 452f, 4.5f), Triple(24f, 430f, 3.5f), Triple(336f, 236f, 3f))
    private val flies = List(14) {
        Fly(0.03f + rnd.nextFloat() * 0.94f, (560f + rnd.nextFloat() * 110f) / 780f,
            4f + rnd.nextFloat() * 10f, 3f + rnd.nextFloat() * 6f, 0.15f + rnd.nextFloat() * 0.3f, rnd.nextFloat() * 6.28f)
    }
    // the big smiling stars: they can be tapped and fly somewhere else
    private class Big(var x: Float, var y: Float, val r: Float, val rot: Float, val color: Int,
                      var fromX: Float = x, var fromY: Float = y, var flyStart: Long = 0L)
    private val bigs = listOf(Big(70f, 330f, 15f, -12f, 0xFFFFE27A.toInt()), Big(122f, 412f, 11f, 10f, 0xFFFFD1E8.toInt()),
        Big(46f, 470f, 9f, 5f, 0xFFC9F2FF.toInt()))
    private class Zee(val x: Float, val y: Float, val size: Float, val start: Long, val drift: Float)
    private val zees = mutableListOf<Zee>()
    private class Dust(val x: Float, val y: Float, val vx: Float, val vy: Float, val start: Long)
    private val dust = mutableListOf<Dust>()
    private val starPath = Path()
    private val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0xFFE9E4FF.toInt(); typeface = android.graphics.Typeface.create(android.graphics.Typeface.DEFAULT, android.graphics.Typeface.BOLD)
    }
    private var shoot: Shoot? = null
    private var hatPop = 0L          // when the hat last changed (for a little bounce)
    private var lightsOff = 0L       // when the house window went dark
    private val shh = mutableListOf<Long>()
    private var peekAt = 0L          // tapped the peekaboo cloud: show its face now
    private val born = SystemClock.uptimeMillis()
    private var driftPush = 0L       // tapped the drifting cloud: a little puff of speed
    private var driftOffset = 0f
    private var nextShoot = SystemClock.uptimeMillis() + 4000

    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val path = Path()
    private var running = false
    private val tick = object : Runnable {
        override fun run() {
            if (!running) return
            invalidate()
            postDelayed(this, 40)
        }
    }

    private fun d(v: Float) = v * resources.displayMetrics.density

    override fun onVisibilityAggregated(isVisible: Boolean) {
        super.onVisibilityAggregated(isVisible)
        if (isVisible && !running) { running = true; post(tick) }
        else if (!isVisible) { running = false; removeCallbacks(tick) }
    }

    override fun onDetachedFromWindow() { running = false; removeCallbacks(tick); super.onDetachedFromWindow() }

    /** The picture is center-cropped like the ImageView under it: map a 360x780 point onto the screen. */
    private fun mapX(x: Float): Float { val s = scale(); return (width - 360f * s) / 2f + x * s }
    private fun mapY(y: Float): Float { val s = scale(); return (height - 780f * s) / 2f + y * s }
    private fun scale() = maxOf(width / 360f, height / 780f)
    private fun picX(x: Float) = (x - (width - 360f * scale()) / 2f) / scale()
    private fun picY(y: Float) = (y - (height - 780f * scale()) / 2f) / scale()

    override fun onTouchEvent(e: MotionEvent): Boolean {
        if (e.actionMasked == MotionEvent.ACTION_DOWN) {
            val x = picX(e.x); val y = picY(e.y)
            val now = SystemClock.uptimeMillis()
            val star = bigs.firstOrNull { it.flyStart == 0L && hypot(x - it.x, y - it.y) < maxOf(26f, it.r * 2.2f) }
            when {
                star != null -> { fly(star, now); Cheer.whoosh() }
                hypot(x - 238f, y - 274f) < 38f -> {
                    hat = (hat + 1) % HATS; hatPop = now; Cheer.pop()
                    repeat(10) { dust += Dust(240f, 274f, rnd.nextFloat() * 60f - 30f, rnd.nextFloat() * 50f - 30f, now) }
                }
                hypot(x - 268f, y - 610f) < 38f -> {
                    lightsOff = now; shh += now; Cheer.lullaby()
                }
                hypot(x - 100f, y - 524f) < 46f -> { peekAt = now; Cheer.giggle() }
                hypot(x - driftX(now), y - 506f) < 36f -> { driftPush = now; Cheer.puff() }
                hypot(x - 228f, y - 366f) < 80f -> { snore(196f, 336f, now, big = true); Cheer.snore(big = true) }
                hypot(x - 66f, y - 606f) < 44f -> { snore(40f, 586f, now, big = false); Cheer.snore(big = false) }
                else -> return false
            }
            if (!running) { running = true; post(tick) }
            return true
        }
        return false
    }

    private fun snore(x: Float, y: Float, now: Long, big: Boolean) {
        val sizes = if (big) listOf(9f, 11f, 14f) else listOf(7f, 8.5f, 10f)
        sizes.forEachIndexed { i, sz -> zees += Zee(x - i * 4f, y, sz, now + i * 280L, if (big) -26f else -18f) }
    }

    /** Pick a new open spot in the sky (away from the clock, the moon and the other stars) and fly there. */
    private fun fly(b: Big, now: Long) {
        var nx = b.x; var ny = b.y
        for (tries in 0 until 60) {
            val x = 24f + rnd.nextFloat() * 312f; val y = 228f + rnd.nextFloat() * 250f
            val clear = hypot(x - 232f, y - 358f) > 105f && hypot(x - b.x, y - b.y) > 70f &&
                bigs.none { it !== b && hypot(x - it.x, y - it.y) < 50f } && !(y > 470f && (x < 160f || x > 250f))
            if (clear) { nx = x; ny = y; break }
        }
        b.fromX = b.x; b.fromY = b.y; b.x = nx; b.y = ny; b.flyStart = now
    }

    override fun onDraw(canvas: Canvas) {
        val now = SystemClock.uptimeMillis()
        val t = now / 1000f
        val s = scale()
        paint.shader = null
        // twinkling stars
        stars.forEach { st ->
            val tw = 0.5f + 0.5f * sin(t * st.speed + st.phase)
            paint.color = 0xFFFFFFFF.toInt()
            paint.alpha = (255 * (st.base * (0.35f + 0.65f * tw))).toInt().coerceIn(0, 255)
            canvas.drawCircle(mapX(st.fx * 360f), mapY(st.fy * 780f), st.r * s * (0.8f + 0.3f * tw), paint)
        }
        // pulsing sparkles
        sparkles.forEachIndexed { i, (x, y, r) ->
            val tw = 0.5f + 0.5f * sin(t * 1.3f + i * 1.7f)
            sparkle(canvas, mapX(x), mapY(y), r * s * (0.6f + 0.6f * tw), (140 + 115 * tw).toInt())
        }
        // clouds: one plays peekaboo, the other drifts off one side and comes back from the other
        canvas.save()
        canvas.translate(mapX(0f), mapY(0f)); canvas.scale(s, s)
        drawCloud(canvas, driftX(now), 498f, 0.75f, 0xD98D83D9.toInt())
        sleepyFace(canvas, driftX(now) + 1f, 505f, 0.75f)
        drawCloud(canvas, 96f, 520f, 1f, 0xF29F95E6.toInt())
        peekaboo(canvas, now)
        canvas.restore()
        // the moon's hat (tap it to change)
        canvas.save()
        canvas.translate(mapX(0f), mapY(0f)); canvas.scale(s, s)
        if (hatPop != 0L) {
            val p = (now - hatPop) / 450f
            if (p >= 1f) hatPop = 0L
            else { val k = 1f + 0.25f * sin(p * PI.toFloat()) * (1 - p); canvas.scale(k, k, 236f, 298f) }
        }
        canvas.translate(-18f, 44f); canvas.rotate(-14f, 240f, 260f)
        drawHat(canvas, hat)
        canvas.restore()
        // the house: lights out for a few seconds, and a floating "Shhh..."
        if (lightsOff != 0L) {
            val p = (now - lightsOff) / 5000f
            if (p >= 1f) lightsOff = 0L
            else {
                val dark = when { p < 0.06f -> p / 0.06f; p > 0.9f -> (1 - p) / 0.1f; else -> 1f }
                paint.color = 0xFF2A2060.toInt(); paint.alpha = (255 * dark).toInt()
                canvas.drawRect(mapX(254.5f), mapY(608.5f), mapX(268.5f), mapY(621.5f), paint)
                paint.color = 0xFFFFF6B8.toInt(); paint.alpha = (200 * dark).toInt()
                canvas.drawCircle(mapX(258.5f), mapY(612f), 0.9f * s, paint)
            }
        }
        shh.removeAll { now - it > 3000 }
        shh.forEach { st ->
            val p = (now - st) / 3000f
            textPaint.textSize = 14f * s * (0.8f + 0.4f * p)
            textPaint.alpha = (255 * (if (p < 0.15f) p / 0.15f else (1 - p) / 0.85f)).toInt().coerceIn(0, 255)
            canvas.drawText("\u266A", mapX(236f + 12f * sin(p * 6f)), mapY(590f - 55f * p), textPaint)
            canvas.drawText("\u266B", mapX(258f + 10f * sin(p * 5f + 2f)), mapY(578f - 45f * p), textPaint)
        }
        // fireflies floating over the hills
        flies.forEach { f ->
            val x = mapX(f.fx * 360f + f.ax * sin(t * f.speed * 2 + f.phase))
            val y = mapY(f.fy * 780f + f.ay * sin(t * f.speed * 3.1f + f.phase * 2))
            val glow = 0.5f + 0.5f * sin(t * (f.speed * 6) + f.phase)
            paint.color = 0xFFFFF3A0.toInt(); paint.alpha = (60 * glow).toInt()
            canvas.drawCircle(x, y, 3.6f * s, paint)
            paint.color = 0xFFFFF6B8.toInt(); paint.alpha = (90 + 165 * glow).toInt()
            canvas.drawCircle(x, y, 1.3f * s, paint)
        }
        // big smiling stars (flying ones leave a trail of sparkle dust)
        bigs.forEach { b ->
            var x = b.x; var y = b.y; var spin = 0f
            if (b.flyStart != 0L) {
                val p = ((now - b.flyStart) / 800f).coerceAtMost(1f)
                val e = if (p < 0.5f) 2 * p * p else 1 - (-2 * p + 2) * (-2 * p + 2) / 2
                val lift = sin(p * PI).toFloat() * 50f
                x = b.fromX + (b.x - b.fromX) * e; y = b.fromY + (b.y - b.fromY) * e - lift
                spin = 360f * e
                if (rnd.nextFloat() < 0.8f) dust += Dust(x, y, rnd.nextFloat() * 16f - 8f, rnd.nextFloat() * 16f - 4f, now)
                if (p >= 1f) b.flyStart = 0L
            }
            val pulse = 1f + 0.06f * sin(t * 2f + b.r)
            faceStar(canvas, mapX(x), mapY(y), b.r * s * pulse, b.rot + spin, b.color)
        }
        dust.removeAll { now - it.start > 700 }
        dust.forEach { d ->
            val p = (now - d.start) / 700f
            sparkle(canvas, mapX(d.x + d.vx * p), mapY(d.y + d.vy * p), 3.2f * s * (1 - p), (255 * (1 - p)).toInt())
        }
        // floating Zs from whoever is snoring
        zees.removeAll { now - it.start > 1800 }
        zees.forEach { z ->
            val p = (now - z.start) / 1800f
            if (p < 0f) return@forEach
            textPaint.textSize = z.size * s * (0.7f + 0.5f * p)
            textPaint.alpha = (255 * (if (p < 0.2f) p / 0.2f else (1 - p) / 0.8f)).toInt().coerceIn(0, 255)
            val zx = z.x + z.drift * p + 6f * sin(p * 9f); val zy = z.y - 60f * p
            canvas.save(); canvas.rotate(-12f, mapX(zx), mapY(zy))
            canvas.drawText("Z", mapX(zx), mapY(zy), textPaint)
            canvas.restore()
        }
        // a shooting star every 8 to 20 seconds
        if (shoot == null && now >= nextShoot) {
            val fromLeft = rnd.nextBoolean()
            shoot = Shoot(if (fromLeft) rnd.nextFloat() * 150f else 210f + rnd.nextFloat() * 150f, 215f + rnd.nextFloat() * 120f,
                if (fromLeft) 170f else -170f, 70f + rnd.nextFloat() * 40f, now)
        }
        shoot?.let { sh ->
            val p = (now - sh.start) / 900f
            if (p >= 1f) { shoot = null; nextShoot = now + 8000 + rnd.nextLong(12000); return@let }
            val head = p.coerceAtMost(1f)
            val tail = (p - 0.35f).coerceAtLeast(0f)
            val hx = mapX(sh.x0 + sh.dx * head); val hy = mapY(sh.y0 + sh.dy * head)
            val tx = mapX(sh.x0 + sh.dx * tail); val ty = mapY(sh.y0 + sh.dy * tail)
            val fade = sin(p * PI).toFloat()
            paint.strokeWidth = 2.2f * s / 2f + d(0.5f)
            paint.strokeCap = Paint.Cap.ROUND
            paint.style = Paint.Style.STROKE
            paint.shader = LinearGradient(tx, ty, hx, hy, 0x00FFFFFF, 0xFFFFFFFF.toInt(), Shader.TileMode.CLAMP)
            paint.alpha = (255 * fade).toInt()
            canvas.drawLine(tx, ty, hx, hy, paint)
            paint.shader = null; paint.style = Paint.Style.FILL
            paint.color = 0xFFFFFFFF.toInt(); paint.alpha = (255 * fade).toInt()
            canvas.drawCircle(hx, hy, 1.8f * s, paint)
        }
    }

    /** Where the drifting cloud is: slowly right, off the edge, and back in from the left (about 70 s a lap). */
    private fun driftX(now: Long): Float {
        if (driftPush != 0L) {
            val p = (now - driftPush) / 1500f
            if (p >= 1f) { driftOffset += 60f; driftPush = 0L }
        }
        val push = if (driftPush != 0L) 60f * ((now - driftPush) / 1500f).let { it * (2 - it) } else 0f
        val lap = 360f + 160f
        val x = ((now - born) / 1000f) * 7.5f + 300f + 80f + driftOffset + push
        return (x % lap) - 80f
    }

    private fun drawCloud(canvas: Canvas, cx: Float, cy: Float, k: Float, color: Int) {
        paint.style = Paint.Style.FILL; paint.shader = null; paint.color = color
        canvas.drawRoundRect(cx - 46 * k, cy, cx + 54 * k, cy + 22 * k, 11 * k, 11 * k, paint)
        listOf(Triple(-34f, 6f, 16f), Triple(-16f, -6f, 22f), Triple(8f, -12f, 26f), Triple(32f, -2f, 20f), Triple(48f, 8f, 14f))
            .forEach { (dx, dy, r) -> canvas.drawCircle(cx + dx * k, cy + dy * k + 6 * k, r * k, paint) }
        paint.color = 0x2EFFFFFF; canvas.drawCircle(cx + 2 * k, cy - 10 * k, 14 * k, paint)
    }

    private fun sleepyFace(canvas: Canvas, cx: Float, cy: Float, k: Float) {
        paint.style = Paint.Style.STROKE; paint.strokeCap = Paint.Cap.ROUND; paint.strokeWidth = 1.4f
        paint.color = 0xFF5B4E9E.toInt()
        canvas.drawArc(cx - 9 * k, cy - 3 * k, cx - 3 * k, cy + 2 * k, 20f, 140f, false, paint)
        canvas.drawArc(cx + 3 * k, cy - 3 * k, cx + 9 * k, cy + 2 * k, 20f, 140f, false, paint)
        canvas.drawArc(cx - 3 * k, cy + 2 * k, cx + 3 * k, cy + 7 * k, 20f, 140f, false, paint)
        paint.style = Paint.Style.FILL
        paint.color = 0x66FF9AB5; canvas.drawCircle(cx - 12 * k, cy + 4 * k, 2.6f * k, paint); canvas.drawCircle(cx + 12 * k, cy + 4 * k, 2.6f * k, paint)
    }

    /** The big cloud hides its eyes with two little puffy hands, then peeks out with a big smile. */
    private fun peekaboo(canvas: Canvas, now: Long) {
        val cyc = 8000L
        val t = if (peekAt != 0L && now - peekAt < 2600) 4300L + (now - peekAt)   // tapped: jump to the peek
                else { if (peekAt != 0L) peekAt = 0L; (now - born + 2000) % cyc }
        // open: 0 = hands over eyes, 1 = hands out to the sides
        val open = when {
            t < 4300 -> 0f
            t < 4700 -> (t - 4300) / 400f
            t < 6500 -> 1f
            t < 6900 -> 1f - (t - 6500) / 400f
            else -> 0f
        }.let { it * it * (3 - 2 * it) }
        val cx = 100f; val cy = 528f
        val ink = 0xFF4A3F8C.toInt()
        paint.style = Paint.Style.FILL
        // cheeks
        paint.color = 0x80FF9AB5.toInt(); canvas.drawCircle(cx - 17f, cy + 6f, 3.4f, paint); canvas.drawCircle(cx + 17f, cy + 6f, 3.4f, paint)
        if (open > 0.4f) {
            // wide happy eyes and an open smile
            paint.color = ink; canvas.drawCircle(cx - 8f, cy, 3.2f, paint); canvas.drawCircle(cx + 8f, cy, 3.2f, paint)
            paint.color = 0xFFFFFFFF.toInt(); canvas.drawCircle(cx - 7f, cy - 1.2f, 1.1f, paint); canvas.drawCircle(cx + 9f, cy - 1.2f, 1.1f, paint)
            paint.color = ink; canvas.drawArc(cx - 5f, cy + 2f, cx + 5f, cy + 11f, 0f, 180f, true, paint)
            paint.color = 0xFFFF8FA3.toInt(); canvas.drawArc(cx - 3f, cy + 6f, cx + 3f, cy + 10.5f, 0f, 180f, true, paint)
            if (open > 0.9f) {
                textPaint.textSize = 9f; textPaint.alpha = 230
                canvas.drawText("boo!", cx + 26f, cy - 18f, textPaint)
            }
        } else {
            // little smile while hiding
            paint.style = Paint.Style.STROKE; paint.strokeWidth = 1.5f; paint.strokeCap = Paint.Cap.ROUND; paint.color = ink
            canvas.drawArc(cx - 4f, cy + 3f, cx + 4f, cy + 9f, 20f, 140f, false, paint)
            paint.style = Paint.Style.FILL
        }
        // the two puffy hands: over the eyes, then out to the sides
        for (side in listOf(-1f, 1f)) {
            val hx = cx + side * (8f + 22f * open); val hy = cy + 1f + 7f * open
            paint.color = 0xFFB7AEF2.toInt(); canvas.drawCircle(hx, hy, 6.5f, paint)
            paint.color = 0x33FFFFFF; canvas.drawCircle(hx - 1.5f, hy - 2f, 3f, paint)
        }
    }

    companion object {
        const val HATS = 6
        /** Which hat the moon wears (kept while the app runs). */
        var hat = 0
    }

    private val conePath = Path().apply {
        moveTo(214f, 276f); cubicTo(220f, 240f, 246f, 214f, 276f, 214f); cubicTo(300f, 214f, 314f, 230f, 318f, 252f)
        cubicTo(310f, 240f, 298f, 234f, 286f, 236f); cubicTo(276f, 240f, 268f, 250f, 264f, 262f); close()
    }
    private val brimPath = Path().apply {
        moveTo(208f, 282f); cubicTo(224f, 262f, 250f, 250f, 272f, 254f); lineTo(270f, 266f)
        cubicTo(250f, 262f, 228f, 272f, 216f, 290f); close()
    }

    /** Draw hat number i, in picture coordinates (already moved onto the moon's tip). */
    private fun drawHat(canvas: Canvas, i: Int) {
        paint.style = Paint.Style.FILL; paint.shader = null
        if (i == 5) {   // a little golden crown
            path.reset()
            path.moveTo(214f, 280f); path.lineTo(210f, 246f); path.lineTo(226f, 262f); path.lineTo(238f, 236f)
            path.lineTo(250f, 260f); path.lineTo(266f, 242f); path.lineTo(266f, 274f); path.close()
            paint.color = 0xFFFFC83D.toInt(); canvas.drawPath(path, paint)
            paint.color = 0xFFFFE58A.toInt(); canvas.drawRect(214f, 268f, 266f, 276f, paint)
            paint.color = 0xFFE8590C.toInt(); canvas.drawCircle(238f, 236f, 3.5f, paint)
            paint.color = 0xFF4DABF7.toInt(); canvas.drawCircle(210f, 246f, 3f, paint); canvas.drawCircle(266f, 242f, 3f, paint)
            paint.color = 0xFFE64980.toInt(); canvas.drawCircle(240f, 270f, 3f, paint)
            paint.color = 0xFF51CF66.toInt(); canvas.drawCircle(226f, 271f, 2.4f, paint); canvas.drawCircle(254f, 270f, 2.4f, paint)
            return
        }
        // nightcaps: color, pattern color, pom color
        val (body, deco, pom) = when (i) {
            1 -> Triple(0xFFFF8FB8.toInt(), 0xFFFFE066.toInt(), 0xFFFFE066.toInt())   // pink with yellow stars
            2 -> Triple(0xFFB197FC.toInt(), 0xFFFFFFFF.toInt(), 0xFFFF8FB8.toInt())   // purple with dots
            3 -> Triple(0xFF51CF66.toInt(), 0xFFFF6B6B.toInt(), 0xFFFFFFFF.toInt())   // green with red stripes
            4 -> Triple(0xFFFFA94D.toInt(), 0xFFFFF3BF.toInt(), 0xFFFFFFFF.toInt())   // orange with zigzag
            else -> Triple(0xFF7FB6FF.toInt(), 0xFFFFFFFF.toInt(), 0xFFFFFFFF.toInt()) // blue with white stripes
        }
        paint.color = body; canvas.drawPath(conePath, paint)
        canvas.save(); canvas.clipPath(conePath)
        paint.color = deco
        when (i) {
            0, 3 -> { paint.alpha = if (i == 0) 180 else 255; for (k in 0..4) { canvas.save(); canvas.rotate(35f, 260f, 240f); canvas.drawRect(200f + k * 24f, 180f, 210f + k * 24f, 300f, paint); canvas.restore() } }
            1 -> listOf(242f to 244f, 262f to 228f, 286f to 224f, 302f to 238f, 254f to 252f).forEach { (x, y) -> tinyStar(canvas, x, y, 5f) }
            2 -> listOf(236f to 250f, 252f to 232f, 270f to 224f, 292f to 222f, 306f to 240f, 260f to 246f, 282f to 236f).forEach { (x, y) -> canvas.drawCircle(x, y, 3.2f, paint) }
            4 -> { paint.style = Paint.Style.STROKE; paint.strokeWidth = 3f; paint.strokeJoin = Paint.Join.ROUND
                for (row in 0..1) { path.reset(); path.moveTo(200f, 250f - row * 18f); var x = 200f; var up = true
                    while (x < 330f) { x += 9f; path.lineTo(x, (if (up) 242f else 250f) - row * 18f); up = !up }
                    canvas.drawPath(path, paint) }
                paint.style = Paint.Style.FILL }
        }
        canvas.restore()
        paint.alpha = 255
        paint.color = 0xFFFFFFFF.toInt(); canvas.drawPath(brimPath, paint)
        paint.color = pom; canvas.drawCircle(320f, 258f, 10f, paint)
        paint.color = 0x33000000; canvas.drawCircle(323f, 261f, 5f, paint)
    }

    private fun tinyStar(canvas: Canvas, cx: Float, cy: Float, r: Float) {
        path.reset()
        for (k in 0 until 10) {
            val rad = if (k % 2 == 0) r else r * 0.45f
            val a = Math.toRadians((-90 + k * 36).toDouble())
            val px = cx + rad * cos(a).toFloat(); val py = cy + rad * sin(a).toFloat()
            if (k == 0) path.moveTo(px, py) else path.lineTo(px, py)
        }
        path.close(); canvas.drawPath(path, paint)
    }

    /** A chubby star with sleepy eyes, a smile and pink cheeks. */
    private fun faceStar(canvas: Canvas, cx: Float, cy: Float, r: Float, rot: Float, color: Int) {
        paint.shader = android.graphics.RadialGradient(cx, cy, r * 1.9f, 0x73FFE9A0, 0x00FFE9A0, Shader.TileMode.CLAMP)
        paint.alpha = 255
        canvas.drawCircle(cx, cy, r * 1.9f, paint)
        paint.shader = null
        starPath.reset()
        for (i in 0 until 10) {
            val rad = if (i % 2 == 0) r else r * 0.5f
            val a = Math.toRadians((rot - 90 + i * 36).toDouble())
            val px = cx + rad * cos(a).toFloat(); val py = cy + rad * sin(a).toFloat()
            if (i == 0) starPath.moveTo(px, py) else starPath.lineTo(px, py)
        }
        starPath.close()
        paint.style = Paint.Style.FILL; paint.color = color
        canvas.drawPath(starPath, paint)
        paint.style = Paint.Style.STROKE; paint.strokeJoin = Paint.Join.ROUND; paint.strokeWidth = r * 0.08f
        paint.color = 0xFFF5B941.toInt()
        canvas.drawPath(starPath, paint)
        val u = r / 15f
        paint.color = 0xFF5A3A12.toInt(); paint.strokeWidth = 1.2f * u * 1.4f; paint.strokeCap = Paint.Cap.ROUND
        val e = r * 0.22f
        for (dx in listOf(-e, e)) canvas.drawArc(cx + dx - 2.4f * u, cy - 3.4f * u, cx + dx + 2.4f * u, cy + 1.2f * u, 20f, 140f, false, paint)
        canvas.drawArc(cx - 2.2f * u, cy + 0.6f * u, cx + 2.2f * u, cy + 4.4f * u, 20f, 140f, false, paint)
        paint.style = Paint.Style.FILL
        paint.color = 0xCCFF9DA8.toInt()
        canvas.drawCircle(cx - e - 2.5f * u, cy + 2.5f * u, 1.7f * u, paint)
        canvas.drawCircle(cx + e + 2.5f * u, cy + 2.5f * u, 1.7f * u, paint)
    }

    private fun sparkle(canvas: Canvas, cx: Float, cy: Float, r: Float, alpha: Int) {
        val k = r * 0.18f
        path.reset()
        path.moveTo(cx, cy - r)
        path.quadTo(cx + k, cy - k, cx + r, cy)
        path.quadTo(cx + k, cy + k, cx, cy + r)
        path.quadTo(cx - k, cy + k, cx - r, cy)
        path.quadTo(cx - k, cy - k, cx, cy - r)
        path.close()
        paint.color = 0xFFFFF6D6.toInt(); paint.alpha = alpha.coerceIn(0, 255)
        canvas.drawPath(path, paint)
    }
}
