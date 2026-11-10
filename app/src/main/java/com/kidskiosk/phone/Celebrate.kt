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
