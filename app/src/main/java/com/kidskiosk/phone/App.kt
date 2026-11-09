package com.kidskiosk.phone

import android.app.Application
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Handler
import android.os.Looper

/**
 * Keeps an eye on things while the phone is on:
 * - when bedtime or Shabbos starts while a kid is inside Greenlight, brings the lock screen up
 * - when parent mode is left open (for example in Settings), brings kids mode back after the time is up
 */
class App : Application() {
    private val handler = Handler(Looper.getMainLooper())

    private val tick = object : Runnable {
        override fun run() {
            check()
            handler.postDelayed(this, 20_000L)
        }
    }

    override fun onCreate() {
        super.onCreate()
        Kiosk.apply(this)
        val screen = object : BroadcastReceiver() {
            override fun onReceive(c: Context, i: Intent) { check() }
        }
        registerReceiver(screen, IntentFilter().apply {
            addAction(Intent.ACTION_SCREEN_ON); addAction(Intent.ACTION_USER_PRESENT)
        })
        handler.post(tick)
    }

    fun check() {
        if (!Kiosk.isOwner(this)) return
        val now = System.currentTimeMillis()
        val parentOpen = now < Prefs.parentUntil(this)
        if (parentOpen) return
        if (Prefs.parentUntil(this) != 0L) {
            // parent mode just timed out
            Prefs.setParentUntil(this, 0L)
            bringHome()
            return
        }
        val reason = Locks.current(this, now).reason
        // During a lock only the home screen (with the call buttons) is allowed.
        // A call started from it gets 20 minutes before the lock screen comes back.
        val inCall = now - HomeActivity.lastCallAt < 20 * 60_000L
        if (reason != Locks.Reason.NONE && !HomeActivity.visible && !inCall) bringHome()
    }

    // ── After a call: back to the home screen ──
    private var sawCall = false
    private val callWatch = object : Runnable {
        override fun run() {
            val started = HomeActivity.lastCallAt
            val now = System.currentTimeMillis()
            if (started == 0L || HomeActivity.visible || now - started > 90 * 60_000L) { sawCall = false; return }
            val audio = getSystemService(AUDIO_SERVICE) as android.media.AudioManager
            val inCall = audio.mode == android.media.AudioManager.MODE_IN_COMMUNICATION || audio.mode == android.media.AudioManager.MODE_IN_CALL
            when {
                inCall -> sawCall = true
                sawCall -> { endCall(); return }                          // call just ended
                now - started > 90_000L -> { endCall(); return }          // never got through
            }
            handler.postDelayed(this, 1500L)
        }
    }

    fun watchCall() { sawCall = false; handler.removeCallbacks(callWatch); handler.postDelayed(callWatch, 3000L) }

    private fun endCall() { sawCall = false; HomeActivity.lastCallAt = 0L; bringHome() }

    // ── Bluetooth: the kids may use the Bluetooth page for 3 minutes ──
    @Volatile var bluetoothUntil = 0L
    private val btWatch = object : Runnable {
        override fun run() {
            if (bluetoothUntil == 0L) return
            if (System.currentTimeMillis() >= bluetoothUntil) { endBluetooth(); bringHome(); return }
            handler.postDelayed(this, 2000L)
        }
    }

    fun openBluetooth() {
        bluetoothUntil = System.currentTimeMillis() + 3 * 60_000L
        Kiosk.apply(this)
        try {
            startActivity(Intent(android.provider.Settings.ACTION_BLUETOOTH_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        } catch (e: Exception) { bluetoothUntil = 0L; Kiosk.apply(this); return }
        handler.removeCallbacks(btWatch); handler.postDelayed(btWatch, 2000L)
    }

    fun endBluetooth() {
        if (bluetoothUntil == 0L) return
        bluetoothUntil = 0L
        handler.removeCallbacks(btWatch)
        Kiosk.apply(this)
    }

    private fun bringHome() {
        runCatching {
            startActivity(Intent(this, HomeActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_REORDER_TO_FRONT))
        }
    }
}
