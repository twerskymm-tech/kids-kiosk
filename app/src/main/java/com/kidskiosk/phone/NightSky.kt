package com.kidskiosk.phone

import android.content.Context
import android.graphics.Canvas
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Path
import android.graphics.Shader
import android.os.SystemClock
import android.view.View
import kotlin.math.PI
import kotlin.math.sin
import kotlin.random.Random

/**
 * Live layer over the bedtime picture: stars that twinkle, a few sparkles that pulse, fireflies that
 * float over the hills, and now and then a shooting star. Positions are fractions of the picture
 * (art/bedtime.py, 360x780). Runs at about 20 frames a second and only while it is on screen.
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
    private var shoot: Shoot? = null
    private var nextShoot = SystemClock.uptimeMillis() + 4000

    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val path = Path()
    private var running = false
    private val tick = object : Runnable {
        override fun run() {
            if (!running) return
            invalidate()
            postDelayed(this, 50)
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
