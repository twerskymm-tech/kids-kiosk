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
 * Touch: the moon and the bunny snore with floating Zs; a big star flies to a new spot.
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
    private var nextShoot = SystemClock.uptimeMillis() + 4000

    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val path = Path()
    private var running = false
    private val tick = object : Runnable {
        override fun run() {
            if (!running) return
            invalidate()
            val busy = zees.isNotEmpty() || dust.isNotEmpty() || bigs.any { it.flyStart != 0L } || shoot != null
            postDelayed(this, if (busy) 33 else 50)
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
                hypot(x - 228f, y - 330f) < 80f -> { snore(196f, 300f, now, big = true); Cheer.snore(big = true) }
                hypot(x - 66f, y - 606f) < 44f -> { snore(40f, 586f, now, big = false); Cheer.snore(big = false) }
                else -> return false
            }
            if (!running) { running = true; post(tick) }
            return true
        }
        return false
    }

    private fun snore(x: Float, y: Float, now: Long, big: Boolean) {
        val sizes = if (big) listOf(13f, 17f, 22f, 27f) else listOf(9f, 12f, 15f)
        sizes.forEachIndexed { i, sz -> zees += Zee(x - i * 4f, y, sz, now + i * 280L, if (big) -26f else -18f) }
    }

    /** Pick a new open spot in the sky (away from the clock, the moon and the other stars) and fly there. */
    private fun fly(b: Big, now: Long) {
        var nx = b.x; var ny = b.y
        for (tries in 0 until 60) {
            val x = 24f + rnd.nextFloat() * 312f; val y = 228f + rnd.nextFloat() * 250f
            val clear = hypot(x - 232f, y - 322f) > 105f && hypot(x - b.x, y - b.y) > 70f &&
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
