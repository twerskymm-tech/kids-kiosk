package com.kidskiosk.phone

import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageInstaller
import android.os.Build
import android.os.Handler
import android.os.Looper
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URL

/**
 * Updates itself from this repo's latest GitHub release (the repo is public).
 * As device owner it installs with no taps, and the kids' screen reopens by itself.
 * Checks on its own at most every 6 hours, or right away from parent mode.
 */
object Updater {
    private const val REPO = "twerskymm-tech/kids-kiosk"
    private const val APK = "kids-home.apk"
    @Volatile private var busy = false
    private var lastAuto = 0L
    private val main = Handler(Looper.getMainLooper())

    fun autoCheck(c: Context) {
        val now = System.currentTimeMillis()
        if (now - lastAuto < 6 * 3_600_000L) return
        lastAuto = now
        check(c.applicationContext, null)
    }

    /** report: called on the main thread with a message for the parent (null = stay quiet). */
    fun check(c: Context, report: ((String) -> Unit)?) {
        if (!Kiosk.isOwner(c)) { report?.invoke("Updates need full lock to be on."); return }
        if (busy) { report?.invoke("Already updating..."); return }
        busy = true
        Thread {
            val msg = try {
                val latest = latest()
                when {
                    latest == null -> "Can't check right now (no internet?)"
                    !newer(latest.first, BuildConfig.VERSION_NAME) -> "You have the newest version (${BuildConfig.VERSION_NAME})."
                    else -> { install(c, latest.second); "Updating... the app reopens in a moment." }
                }
            } catch (e: Exception) { "Update failed: ${e.message}" }
            busy = false
            if (report != null) main.post { report(msg) }
        }.start()
    }

    private fun latest(): Pair<String, String>? {
        val conn = URL("https://api.github.com/repos/$REPO/releases/latest").openConnection() as HttpURLConnection
        conn.connectTimeout = 15000; conn.readTimeout = 20000
        conn.setRequestProperty("Accept", "application/vnd.github+json")
        if (conn.responseCode != 200) return null
        val j = JSONObject(conn.inputStream.bufferedReader().readText())
        val assets = j.optJSONArray("assets") ?: return null
        for (i in 0 until assets.length()) {
            val a = assets.getJSONObject(i)
            if (a.optString("name") == APK) return j.optString("name") to a.optString("browser_download_url")
        }
        return null
    }

    private fun install(c: Context, url: String) {
        val file = File(c.filesDir, "update.apk")
        var u = URL(url); var hops = 0
        var conn: HttpURLConnection
        while (true) {
            conn = u.openConnection() as HttpURLConnection
            conn.instanceFollowRedirects = false; conn.connectTimeout = 20000; conn.readTimeout = 60000
            val code = conn.responseCode
            if (code in 300..399 && hops++ < 5) { u = URL(u, conn.getHeaderField("Location")); conn.disconnect(); continue }
            if (code != 200) throw java.io.IOException("download error $code")
            break
        }
        conn.inputStream.use { inp -> file.outputStream().use { inp.copyTo(it) } }
        val pi = c.packageManager.packageInstaller
        val params = PackageInstaller.SessionParams(PackageInstaller.SessionParams.MODE_FULL_INSTALL)
        params.setAppPackageName(c.packageName)
        val id = pi.createSession(params)
        pi.openSession(id).use { s ->
            s.openWrite("kiosk", 0, file.length()).use { out -> file.inputStream().use { it.copyTo(out) }; s.fsync(out) }
            val flags = PendingIntent.FLAG_UPDATE_CURRENT or (if (Build.VERSION.SDK_INT >= 31) PendingIntent.FLAG_MUTABLE else 0)
            val pending = PendingIntent.getBroadcast(c, id, Intent(c, InstallResultReceiver::class.java), flags)
            s.commit(pending.intentSender)
        }
    }

    private fun parts(v: String): List<Int> =
        Regex("(\\d+)\\.(\\d+)\\.(\\d+)").find(v)?.groupValues?.drop(1)?.map { it.toInt() } ?: emptyList()

    private fun newer(a: String, b: String): Boolean {
        val x = parts(a); val y = parts(b)
        if (x.size != 3 || y.size != 3) return false
        for (i in 0..2) if (x[i] != y[i]) return x[i] > y[i]
        return false
    }
}

/** Result of the background install (nothing to do; the app restarts on success). */
class InstallResultReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {}
}
