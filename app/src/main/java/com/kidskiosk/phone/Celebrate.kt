package com.kidskiosk.phone

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import android.view.View
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.sin
import kotlin.random.Random

/** Confetti bursting up from the bottom and falling down, about 3 seconds. Removes itself when done. */
class ConfettiView(c: Context, private val colors: IntArray) : View(c) {
    private class Bit(var x: Float, var y: Float, var vx: Float, var vy: Float, var rot: Float, val spin: Float,
                      val w: Float, val h: Float, val color: Int, val round: Boolean)

    private val bits = mutableListOf<Bit>()
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private var fade = 1f
    private var last = 0L

    init { isClickable = false }

    override fun onSizeChanged(w: Int, h: Int, ow: Int, oh: Int) {
        if (bits.isNotEmpty() || w == 0) return
        val d = resources.displayMetrics.density
        repeat(140) { i ->
            val left = i % 2 == 0   // two cannons, bottom left and bottom right
            val ang = (if (left) -60.0 else -120.0) + Random.nextDouble(-22.0, 22.0)
            val speed = (Random.nextFloat() * 900f + 900f) * d / 2.6f
            bits += Bit(
                if (left) w * 0.08f else w * 0.92f, h * 0.95f,
                (cos(ang * PI / 180) * speed).toFloat(), (sin(ang * PI / 180) * speed).toFloat(),
                Random.nextFloat() * 360f, Random.nextFloat() * 720f - 360f,
                (Random.nextFloat() * 6f + 6f) * d, (Random.nextFloat() * 6f + 9f) * d,
                colors[Random.nextInt(colors.size)], Random.nextInt(4) == 0)
        }
        ValueAnimator.ofFloat(0f, 1f).apply {
            duration = 3200
            addUpdateListener {
                val now = System.nanoTime()
                val dt = if (last == 0L) 0.016f else ((now - last) / 1e9f).coerceAtMost(0.05f)
                last = now
                val g = 900f * d / 2.6f
                bits.forEach { b ->
                    b.vy += g * dt; b.vx *= 0.99f; b.vy = b.vy.coerceAtMost(260f * d / 2.6f + 200f)
                    b.x += b.vx * dt; b.y += b.vy * dt; b.rot += b.spin * dt
                }
                val t = it.animatedValue as Float
                fade = if (t > 0.75f) (1f - t) / 0.25f else 1f
                invalidate()
            }
            addListener(object : android.animation.AnimatorListenerAdapter() {
                override fun onAnimationEnd(a: android.animation.Animator) { (parent as? android.view.ViewGroup)?.removeView(this@ConfettiView) }
            })
            start()
        }
    }

    override fun onDraw(canvas: Canvas) {
        bits.forEach { b ->
            paint.color = b.color
            paint.alpha = (255 * fade).toInt()
            canvas.save()
            canvas.translate(b.x, b.y)
            canvas.rotate(b.rot)
            if (b.round) canvas.drawCircle(0f, 0f, b.w / 2, paint)
            else canvas.drawRect(-b.w / 2, -b.h / 4, b.w / 2, b.h / 4, paint)
            canvas.restore()
        }
    }
}

/** A short happy tune made on the phone (no sound files needed). */
object Cheer {
    /** A sleepy snore: a soft buzzy breath in and out (lower and longer for the moon). */
    fun snore(big: Boolean): Unit {
        // a cartoon snore: a soft rumbly breath in ("hrrrr"), a short pause, then a gentle whistle out ("fyuuu")
        val inEnd = if (big) 1.0 else 0.45
        val outStart = inEnd + if (big) 0.2 else 0.1
        val total = outStart + if (big) 1.0 else 0.6
        var low = 0.0; var air = 0.0; var phase = 0.0
        pcm(total, smooth = 1.0) { t, _ ->
            val n = Random.nextDouble() * 2 - 1
            low += (n - low) * (if (big) 0.025 else 0.06)          // deep rumble from filtered noise
            air += (n - air) * 0.3                                  // soft breathy air
            when {
                t < inEnd -> {
                    val env = sin(PI * t / inEnd).let { it * it }
                    low * env * (if (big) 1.1 else 0.5) + air * env * 0.01
                }
                t < outStart -> 0.0
                else -> {
                    val p = (t - outStart) / (total - outStart)
                    val env = sin(PI * p).let { it * it }
                    val f = (if (big) 950.0 - 400.0 * p else 1500.0 - 500.0 * p) * (1 + 0.008 * sin(2 * PI * 5 * t))
                    phase += 2 * PI * f / 44100
                    sin(phase) * env * (if (big) 0.03 else 0.022) + air * env * 0.012
                }
            }
        }
    }

    /** A quick rising whoosh with a little ding, for a star flying away. */
    fun whoosh() = pcm(0.55) { t, _ ->
        val f = 700 + 1400 * (t / 0.55)
        val env = exp(-t * 6)
        val ding = if (t > 0.3) sin(2 * PI * 1975.5 * (t - 0.3)) * exp(-(t - 0.3) * 12) * 0.07 else 0.0
        sin(2 * PI * f * t) * env * 0.05 + ding
    }

    /** Lights out: a soft switch click, then a few music-box notes saying goodnight. */
    fun lullaby() {
        // (start time in seconds, frequency) – a gentle falling tune
        val notes = listOf(0.25 to 1318.5, 0.6 to 1046.5, 0.95 to 784.0, 1.3 to 1046.5, 1.75 to 1174.7, 2.1 to 987.8, 2.45 to 784.0)
        pcm(3.6, smooth = 1.0) { t, _ ->
            var v = if (t < 0.012) (Random.nextDouble() * 2 - 1) * 0.06 * (1 - t / 0.012) else 0.0   // click
            notes.forEach { (st, f) ->
                if (t >= st) {
                    val u = t - st
                    val env = exp(-u * 3.2) * (1 - exp(-u * 400))
                    v += (sin(2 * PI * f * u) + 0.35 * sin(2 * PI * 2 * f * u) * exp(-u * 6) + 0.12 * sin(2 * PI * 3 * f * u) * exp(-u * 10)) * env * 0.035
                }
            }
            v
        }
    }

    /** A tiny pop, for the moon changing its hat. */
    fun pop() = pcm(0.18) { t, _ -> sin(2 * PI * (520 + 900 * t / 0.18) * t) * exp(-t * 25) * 0.08 }

    private fun pcm(seconds: Double, smooth: Double = 0.35, wave: (Double, Double) -> Double) {
        Thread {
            runCatching {
                val rate = 44100
                val n = (seconds * rate).toInt()
                // a gentle low-pass keeps the buzz soft
                var lp = 0.0
                val data = ShortArray(n) { i ->
                    val t = i.toDouble() / rate
                    lp += (wave(t, seconds) - lp) * smooth
                    (lp.coerceIn(-1.0, 1.0) * 32000).toInt().toShort()
                }
                play(data, rate)
            }
        }.start()
    }

    private fun play(pcm: ShortArray, rate: Int) {
        val track = AudioTrack.Builder()
            .setAudioAttributes(AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_GAME)
                .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION).build())
            .setAudioFormat(AudioFormat.Builder().setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                .setSampleRate(rate).setChannelMask(AudioFormat.CHANNEL_OUT_MONO).build())
            .setBufferSizeInBytes(pcm.size * 2)
            .setTransferMode(AudioTrack.MODE_STATIC)
            .build()
        track.write(pcm, 0, pcm.size)
        track.play()
        Thread.sleep(pcm.size * 1000L / rate + 200)
        track.release()
    }

    fun play(lucky: Boolean = false) {
        Thread {
            runCatching {
                val rate = 44100
                // C5 E5 G5 C6 (and a sparkle on top for a lucky day)
                val notes = if (lucky) doubleArrayOf(523.25, 659.25, 783.99, 1046.5, 1318.5, 1568.0) else doubleArrayOf(523.25, 659.25, 783.99, 1046.5)
                val step = 0.11; val tail = 0.55
                val total = ((step * notes.size + tail) * rate).toInt()
                val buf = FloatArray(total)
                notes.forEachIndexed { i, f ->
                    val start = (i * step * rate).toInt()
                    val len = ((if (i == notes.size - 1) tail + 0.1 else step + 0.25) * rate).toInt()
                    for (n in 0 until len) {
                        val idx = start + n
                        if (idx >= total) break
                        val t = n.toDouble() / rate
                        val env = (1 - exp(-t * 200)) * exp(-t * (if (i == notes.size - 1) 4.5 else 9.0))
                        buf[idx] += (env * (sin(2 * PI * f * t) + 0.3 * sin(4 * PI * f * t))).toFloat() * 0.3f
                    }
                }
                val pcm = ShortArray(total) { (buf[it].coerceIn(-1f, 1f) * 32000).toInt().toShort() }
                val track = AudioTrack.Builder()
                    .setAudioAttributes(AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_GAME)
                        .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION).build())
                    .setAudioFormat(AudioFormat.Builder().setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                        .setSampleRate(rate).setChannelMask(AudioFormat.CHANNEL_OUT_MONO).build())
                    .setBufferSizeInBytes(pcm.size * 2)
                    .setTransferMode(AudioTrack.MODE_STATIC)
                    .build()
                track.write(pcm, 0, pcm.size)
                track.play()
                Thread.sleep((total * 1000L / rate) + 200)
                track.release()
            }
        }.start()
    }
}
