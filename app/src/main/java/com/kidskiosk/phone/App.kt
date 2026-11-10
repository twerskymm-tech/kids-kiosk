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
            Remote.autoSync(this@App)
            handler.postDelayed(this, 20_000L)
        }
    }

    override fun onCreate() {
        super.onCreate()
        Kiosk.apply(this)
        val screen = object : BroadcastReceiver() {
            override fun onReceive(c: Context, i: Intent) {
                if (i.action == Intent.ACTION_SCREEN_OFF) {
                    // Screen turned off: 40 seconds later the phone is back on the home screen (unless on a call)
                    handler.removeCallbacks(homeAfterOff); handler.postDelayed(homeAfterOff, 40_000L)
                } else {
                    handler.removeCallbacks(homeAfterOff)
                    check()
                }
            }
        }
        registerReceiver(screen, IntentFilter().apply {
            addAction(Intent.ACTION_SCREEN_ON); addAction(Intent.ACTION_USER_PRESENT); addAction(Intent.ACTION_SCREEN_OFF)
        })
        Kiosk.keepScreenOn(this)
        handler.post(tick)
    }

    private val homeAfterOff = Runnable {
        val inCall = System.currentTimeMillis() - HomeActivity.lastCallAt < 90 * 60_000L
        if (!inCall && System.currentTimeMillis() >= Prefs.parentUntil(this)) { endBluetooth(); bringHome() }
    }

    /**
     * Speakers: opens the phone's "where does the sound play" picker, where Samsung lets you tick
     * more than one Bluetooth speaker at once. Falls back to the Bluetooth page.
     */
    fun openSpeakers() {
        bluetoothUntil = System.currentTimeMillis() + 3 * 60_000L
        Kiosk.apply(this)
        val playing = runCatching {
            val am = getSystemService(AUDIO_SERVICE) as android.media.AudioManager
            if (am.isMusicActive) Kiosk.allowedApps(this).firstOrNull { it.contains("24") || it.contains("music") } else null
        }.getOrNull() ?: packageName
        val opened = runCatching {
            // Android 13+: the system output picker
            sendBroadcast(Intent("com.android.systemui.action.LAUNCH_MEDIA_OUTPUT_DIALOG")
                .setPackage("com.android.systemui").putExtra("package_name", playing))
            true
        }.getOrDefault(false)
        if (!opened || android.os.Build.VERSION.SDK_INT < 33) runCatching {
            startActivity(Intent("com.android.settings.panel.action.MEDIA_OUTPUT")
                .putExtra("com.android.settings.panel.extra.PACKAGE_NAME", playing)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        }.onFailure { openBluetooth(); return }
        handler.removeCallbacks(btWatch); handler.postDelayed(btWatch, 2000L)
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
            if (started == 0L) { sawCall = false; return }
            val audio = getSystemService(AUDIO_SERVICE) as android.media.AudioManager
            val inCall = audio.mode == android.media.AudioManager.MODE_IN_COMMUNICATION || audio.mode == android.media.AudioManager.MODE_IN_CALL
            if (now - started > 90 * 60_000L || (HomeActivity.visible && !inCall && now - started > 10_000L)) {
                // Back on the home screen with no call going: switch the calling app off again.
                sawCall = false; HomeActivity.lastCallAt = 0L
                speaker(audio, false); Kiosk.apply(this@App)
                return
            }
            when {
                inCall -> {
                    if (!sawCall) speakerSince = now
                    sawCall = true
                    // Calls go on speaker. The calling app may switch it back while connecting, so keep asking for a bit.
                    if (now - speakerSince < 20_000L) speaker(audio, true)
                }
                sawCall -> { endCall(); return }                          // call just ended
                now - started > 90_000L -> { endCall(); return }          // never got through
            }
            handler.postDelayed(this, 1500L)
        }
    }

    fun watchCall() { sawCall = false; handler.removeCallbacks(callWatch); handler.postDelayed(callWatch, 3000L) }

    private var speakerSince = 0L

    private fun endCall() {
        sawCall = false; HomeActivity.lastCallAt = 0L
        Kiosk.apply(this)   // calling app off again: no incoming calls
        speaker(getSystemService(AUDIO_SERVICE) as android.media.AudioManager, false)
        bringHome()
    }

    @Suppress("DEPRECATION")
    private fun speaker(audio: android.media.AudioManager, on: Boolean) {
        runCatching {
            if (android.os.Build.VERSION.SDK_INT >= 31) {
                if (on) {
                    val cur = audio.communicationDevice
                    if (cur?.type != android.media.AudioDeviceInfo.TYPE_BUILTIN_SPEAKER) {
                        audio.availableCommunicationDevices
                            .firstOrNull { it.type == android.media.AudioDeviceInfo.TYPE_BUILTIN_SPEAKER }
                            ?.let { audio.setCommunicationDevice(it) }
                    }
                } else audio.clearCommunicationDevice()
            }
            if (audio.isSpeakerphoneOn != on) audio.isSpeakerphoneOn = on
        }
    }

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
