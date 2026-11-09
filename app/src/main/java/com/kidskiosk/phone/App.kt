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

    private fun bringHome() {
        runCatching {
            startActivity(Intent(this, HomeActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_REORDER_TO_FRONT))
        }
    }
}
