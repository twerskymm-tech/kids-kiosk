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
    /**
     * A cartoon snore: a soft rumbly breath in ("hrrrr"), then a gentle "mimimimi" whistle out.
     * The bunny's is shorter and higher.
     */
    fun snore(big: Boolean) {
        val inEnd = if (big) 0.9 else 0.4
        val outStart = inEnd + if (big) 0.15 else 0.08
        val total = outStart + if (big) 1.1 else 0.6
        var low = 0.0; var hum = 0.0; var humF = if (big) 105.0 else 170.0; var phase = 0.0
        pcm(total) { t, _ ->
            val n = Random.nextDouble() * 2 - 1
            low += (n - low) * (if (big) 0.03 else 0.07)
            if (t < inEnd) {
                val env = sin(PI * t / inEnd).let { it * it }
                humF += (Random.nextDouble() - 0.5) * 0.6                 // a wandering, not buzzing, hum
                hum += 2 * PI * humF / RATE
                low * env * (if (big) 0.9 else 0.45) + sin(hum) * env * (if (big) 0.012 else 0.008)
            } else if (t < outStart) 0.0
            else {
                val p = (t - outStart) / (total - outStart)
                val env = sin(PI * p).let { it * it }
                val f = if (big) 1150.0 - 350.0 * p else 1650.0 - 400.0 * p
                phase += 2 * PI * f / RATE
                val mi = 0.5 - 0.5 * cos(2 * PI * (if (big) 6.5 else 8.5) * (t - outStart))   // "mi-mi-mi"
                sin(phase) * env * mi * (if (big) 0.028 else 0.02)
            }
        }
    }

    /** An airy whoosh that rises, then a little bell, for a star flying away. */
    fun whoosh() {
        var lowp = 0.0; var band = 0.0
        pcm(0.75) { t, _ ->
            val n = Random.nextDouble() * 2 - 1
            val f = 500 + 2500 * (t / 0.45).coerceAtMost(1.0)
            val k = 2 * sin(PI * f / RATE)
            lowp += k * band; val high = n - lowp - 0.5 * band; band += k * high   // a sweeping band-pass
            val env = if (t < 0.45) sin(PI * t / 0.45) else 0.0
            val u = t - 0.38
            val bell = if (u > 0) (sin(2 * PI * 2093.0 * u) + 0.3 * sin(2 * PI * 5776.7 * u) * exp(-u * 18)) * exp(-u * 7) * 0.035 else 0.0
            band * env * 0.05 + bell
        }
    }

    /** A soft "bloop", for the moon changing its hat. */
    fun pop() {
        var phase = 0.0
        pcm(0.2) { t, _ ->
            val f = 320 + 700 * (t / 0.08).coerceAtMost(1.0)
            phase += 2 * PI * f / RATE
            sin(phase) * exp(-t * 22) * (1 - exp(-t * 600)) * 0.06
        }
    }

    /** "Tee-hee-hee", for the peekaboo cloud. */
    fun giggle() {
        val starts = doubleArrayOf(0.0, 0.15, 0.30)
        val freqs = doubleArrayOf(980.0, 1080.0, 1220.0)
        var phase = 0.0
        pcm(0.5) { t, _ ->
            var v = 0.0
            for (i in starts.indices) {
                val u = t - starts[i]
                if (u in 0.0..0.11) {
                    val env = sin(PI * u / 0.11)
                    val f = freqs[i] * (1 + 0.04 * sin(2 * PI * 30 * u)) * (1 - 0.15 * u / 0.11)
                    phase += 2 * PI * f / RATE
                    v = (sin(phase) + 0.35 * sin(2 * phase) + 0.15 * sin(3 * phase)) * env * 0.03
                }
            }
            v
        }
    }

    /** A soft puff of air, for nudging the drifting cloud. */
    fun puff() {
        var low = 0.0
        pcm(0.45) { t, _ ->
            low += (Random.nextDouble() * 2 - 1 - low) * 0.12
            low * sin(PI * t / 0.45).let { it * it } * 0.12
        }
    }

    fun lullaby() {
        // (start time in seconds, frequency) – a gentle falling tune
        val notes = listOf(0.25 to 1318.5, 0.6 to 1046.5, 0.95 to 784.0, 1.3 to 1046.5, 1.75 to 1174.7, 2.1 to 987.8, 2.45 to 784.0)
        pcm(3.6) { t, _ ->
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

    private const val RATE = 44100

    /** Make the sound sample by sample, add a little room echo so it sounds soft and round, and play it. */
    private fun pcm(seconds: Double, wave: (Double, Double) -> Double) {
        Thread {
            runCatching {
                val n = (seconds * RATE).toInt()
                val tail = (0.5 * RATE).toInt()
                val dry = DoubleArray(n + tail)
                for (i in 0 until n) dry[i] = wave(i.toDouble() / RATE, seconds)
                // small reverb: four feedback echoes, mixed in quietly
                val delays = intArrayOf(1327, 1559, 1801, 2063)
                val bufs = delays.map { DoubleArray(it) }
                val idx = IntArray(delays.size)
                val out = ShortArray(n + tail)
                for (i in out.indices) {
                    var wet = 0.0
                    for (c in delays.indices) {
                        val b = bufs[c]; val y = b[idx[c]]
                        b[idx[c]] = dry[i] + y * 0.5
                        idx[c] = (idx[c] + 1) % b.size
                        wet += y
                    }
                    val v = dry[i] + wet * 0.12
                    out[i] = (v.coerceIn(-1.0, 1.0) * 32000).toInt().toShort()
                }
                play(out, RATE)
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
