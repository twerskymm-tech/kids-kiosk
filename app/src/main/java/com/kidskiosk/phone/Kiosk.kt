package com.kidskiosk.phone

import android.Manifest
import android.app.Activity
import android.app.ActivityManager
import android.app.admin.DeviceAdminReceiver
import android.app.admin.DevicePolicyManager
import android.app.admin.SystemUpdatePolicy
import android.content.BroadcastReceiver
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Build
import android.os.UserManager
import android.provider.Settings

/**
 * Full lock. The app is the phone's "device owner" (set once from a computer, see README),
 * so Android itself keeps the phone pinned to the kids' home screen and the allowed apps.
 */
object Kiosk {
    const val VOICE = "com.google.android.apps.googlevoice"
    const val SETTINGS = "com.android.settings"

    private val RESTRICTIONS = listOf(
        UserManager.DISALLOW_FACTORY_RESET,
        UserManager.DISALLOW_SAFE_BOOT,
        UserManager.DISALLOW_ADD_USER,
        UserManager.DISALLOW_INSTALL_UNKNOWN_SOURCES,
    )

    private fun dpm(c: Context) = c.getSystemService(Context.DEVICE_POLICY_SERVICE) as DevicePolicyManager
    fun admin(c: Context) = ComponentName(c, AdminReceiver::class.java)
    fun isOwner(c: Context): Boolean = runCatching { dpm(c).isDeviceOwnerApp(c.packageName) }.getOrDefault(false)

    /** Apps the kids may use: what the parent ticked, or at first Greenlight and 24/6 if they are installed. */
    fun allowedApps(c: Context): Set<String> {
        Prefs.allowed(c)?.let { return it }
        return launchableApps(c).filter { (_, label) ->
            val l = label.lowercase()
            l.contains("greenlight") || l.contains("24/6") || l.contains("24 6")
        }.map { it.first }.toSet()
    }

    /** Every app with an icon (package, label), sorted by name. */
    fun launchableApps(c: Context): List<Pair<String, String>> {
        val pm = c.packageManager
        val i = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
        @Suppress("DEPRECATION")
        return pm.queryIntentActivities(i, 0)
            .map { it.activityInfo.packageName to it.loadLabel(pm).toString() }
            .filter { it.first != c.packageName }
            .distinctBy { it.first }
            .sortedBy { it.second.lowercase() }
    }

    /** Everything full lock turns on. Safe to run again any time. */
    fun apply(c: Context) {
        if (!isOwner(c)) return
        val d = dpm(c); val a = admin(c)
        val bt = (c.applicationContext as? App)?.let { System.currentTimeMillis() < it.bluetoothUntil } == true
        val calling = callingAllowed(c)
        val pkgs = (allowedApps(c) + c.packageName + (if (calling) setOf(Prefs.callApp(c)) else emptySet()) +
            (if (bt) setOf(SETTINGS, "com.android.bluetooth") else emptySet())).toTypedArray()
        runCatching { d.setLockTaskPackages(a, pkgs) }
        holdCallApp(c, !calling)
        // Home button works (comes back here). No notifications, no recent apps, no status bar info.
        // The power menu stays so the phone can be turned off.
        if (Build.VERSION.SDK_INT >= 28) runCatching {
            d.setLockTaskFeatures(a, DevicePolicyManager.LOCK_TASK_FEATURE_HOME or DevicePolicyManager.LOCK_TASK_FEATURE_GLOBAL_ACTIONS)
        }
        // Always the home screen
        runCatching {
            val f = IntentFilter(Intent.ACTION_MAIN).apply { addCategory(Intent.CATEGORY_HOME); addCategory(Intent.CATEGORY_DEFAULT) }
            d.addPersistentPreferredActivity(a, f, ComponentName(c, HomeActivity::class.java))
        }
        RESTRICTIONS.forEach { r -> runCatching { d.addUserRestriction(a, r) } }
        // Location for Shabbos times, and calls through Google Voice, with no questions
        listOf(Manifest.permission.ACCESS_COARSE_LOCATION, Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.CALL_PHONE).forEach { p ->
            runCatching { d.setPermissionGrantState(a, c.packageName, p, DevicePolicyManager.PERMISSION_GRANT_STATE_GRANTED) }
        }
        // No lock screen swipe for the kids; Android updates only at night (1–5 AM)
        runCatching { d.setKeyguardDisabled(a, true) }
        runCatching { d.setSystemUpdatePolicy(a, SystemUpdatePolicy.createWindowedInstallPolicy(60, 300)) }
    }

    /**
     * No incoming calls: the calling app is switched off (suspended) and kept out of the kiosk except
     * while a kid is placing a call from the home screen, or while parent mode is open.
     */
    fun callingAllowed(c: Context): Boolean {
        val now = System.currentTimeMillis()
        val started = HomeActivity.lastCallAt
        return now < Prefs.parentUntil(c) || (started != 0L && now - started < 90 * 60_000L)
    }

    private fun holdCallApp(c: Context, hold: Boolean) {
        if (!isOwner(c) || Build.VERSION.SDK_INT < 24) return
        val d = dpm(c); val a = admin(c)
        val sp = c.getSharedPreferences("kiosk", Context.MODE_PRIVATE)
        val pkg = Prefs.callApp(c)
        // If the parent picked a different calling app, switch the old one back on.
        val old = sp.getString("held_pkg", "") ?: ""
        if (old.isNotEmpty() && old != pkg) runCatching { d.setPackagesSuspended(a, arrayOf(old), false) }
        if (!isInstalled(c, pkg)) return
        runCatching { d.setPackagesSuspended(a, arrayOf(pkg), hold) }
        sp.edit().putString("held_pkg", if (hold) pkg else "").apply()
    }

    /** The screen stays on until someone presses the power button. */
    fun keepScreenOn(c: Context) {
        if (!isOwner(c) || Build.VERSION.SDK_INT < 28) return
        runCatching { dpm(c).setSystemSetting(admin(c), Settings.System.SCREEN_OFF_TIMEOUT, Int.MAX_VALUE.toString()) }
    }

    /** Brightness 0–255 (owner can change it without asking). */
    fun setBrightness(c: Context, v: Int): Boolean {
        if (!isOwner(c) || Build.VERSION.SDK_INT < 28) return false
        return runCatching {
            dpm(c).setSystemSetting(admin(c), Settings.System.SCREEN_BRIGHTNESS_MODE, Settings.System.SCREEN_BRIGHTNESS_MODE_MANUAL.toString())
            dpm(c).setSystemSetting(admin(c), Settings.System.SCREEN_BRIGHTNESS, v.coerceIn(1, 255).toString())
        }.isSuccess
    }

    fun isLocked(c: Context): Boolean {
        val am = c.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
        return am.lockTaskModeState != ActivityManager.LOCK_TASK_MODE_NONE
    }

    fun lock(a: Activity) { if (isOwner(a) && !isLocked(a)) runCatching { a.startLockTask() } }
    fun unlock(a: Activity) { if (isLocked(a)) runCatching { a.stopLockTask() } }

    /** Takes the lock off completely (before uninstalling the app). */
    fun removeOwner(a: Activity) {
        if (!isOwner(a)) return
        val d = dpm(a); val ad = admin(a)
        unlock(a)
        RESTRICTIONS.forEach { r -> runCatching { d.clearUserRestriction(ad, r) } }
        runCatching { d.clearPackagePersistentPreferredActivities(ad, a.packageName) }
        runCatching { d.setKeyguardDisabled(ad, false) }
        runCatching { d.setLockTaskPackages(ad, arrayOf()) }
        if (Build.VERSION.SDK_INT >= 24) runCatching { d.setPackagesSuspended(ad, arrayOf(Prefs.callApp(a)), false) }
        @Suppress("DEPRECATION")
        runCatching { d.clearDeviceOwnerApp(a.packageName) }
    }

    /** Signs a kid out of an app by wiping its data (owner only), then calls done(true/false) on the main thread. */
    fun clearAppData(c: Context, pkg: String, done: (Boolean) -> Unit) {
        if (!isOwner(c) || Build.VERSION.SDK_INT < 28) { done(false); return }
        try {
            dpm(c).clearApplicationUserData(admin(c), pkg, c.mainExecutor) { _, ok -> done(ok) }
        } catch (e: Exception) { done(false) }
    }

    fun appLabel(c: Context, pkg: String): String = runCatching {
        c.packageManager.getApplicationLabel(c.packageManager.getApplicationInfo(pkg, 0)).toString()
    }.getOrDefault(if (pkg == VOICE) "Google Voice" else pkg)

    fun isInstalled(c: Context, pkg: String): Boolean =
        runCatching { c.packageManager.getPackageInfo(pkg, 0); true }.getOrDefault(false)
}

/** The app as device owner. */
class AdminReceiver : DeviceAdminReceiver() {
    override fun onEnabled(context: Context, intent: Intent) {
        Kiosk.apply(context)
    }

    override fun onProfileProvisioningComplete(context: Context, intent: Intent) {
        Kiosk.apply(context)
        runCatching {
            context.startActivity(Intent(context, HomeActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        }
    }
}

/** Setup-QR steps Android 12+ asks the owner app: "fully managed phone", then "done". */
class ProvisionActivity : Activity() {
    override fun onCreate(savedInstanceState: android.os.Bundle?) {
        super.onCreate(savedInstanceState)
        when (intent?.action) {
            DevicePolicyManager.ACTION_GET_PROVISIONING_MODE -> setResult(RESULT_OK,
                Intent().putExtra(DevicePolicyManager.EXTRA_PROVISIONING_MODE, DevicePolicyManager.PROVISIONING_MODE_FULLY_MANAGED_DEVICE))
            DevicePolicyManager.ACTION_ADMIN_POLICY_COMPLIANCE -> { Kiosk.apply(this); setResult(RESULT_OK) }
            else -> setResult(RESULT_CANCELED)
        }
        finish()
    }
}

/** After the app is updated, open the kids' screen again. */
class UpdatedReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Intent.ACTION_MY_PACKAGE_REPLACED) return
        Kiosk.apply(context)
        runCatching {
            context.startActivity(Intent(context, HomeActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        }
    }
}
