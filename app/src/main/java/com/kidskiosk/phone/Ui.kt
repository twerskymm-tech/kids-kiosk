package com.kidskiosk.phone

import android.app.Activity
import android.app.AlertDialog
import android.content.Context
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.text.InputType
import android.view.Gravity
import android.view.View
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import java.text.DateFormat
import java.util.Date

fun Context.dp(v: Int): Int = (v * resources.displayMetrics.density).toInt()
fun Context.dpf(v: Float): Float = v * resources.displayMetrics.density

fun rounded(color: Int, radius: Float, stroke: Int = 0, strokeColor: Int = Color.TRANSPARENT) = GradientDrawable().apply {
    setColor(color); cornerRadius = radius
    if (stroke > 0) setStroke(stroke, strokeColor)
}

fun Context.text(s: String, sp: Float, color: Int, bold: Boolean = false, gravity: Int = Gravity.START) = TextView(this).apply {
    text = s; textSize = sp; setTextColor(color); this.gravity = gravity
    if (bold) setTypeface(typeface, android.graphics.Typeface.BOLD)
}

fun lp(w: Int, h: Int, weight: Float = 0f) = LinearLayout.LayoutParams(w, h, weight)
const val MATCH = LinearLayout.LayoutParams.MATCH_PARENT
const val WRAP = LinearLayout.LayoutParams.WRAP_CONTENT

fun View.margins(c: Context, l: Int = 0, t: Int = 0, r: Int = 0, b: Int = 0): View {
    (layoutParams as? android.view.ViewGroup.MarginLayoutParams)?.setMargins(c.dp(l), c.dp(t), c.dp(r), c.dp(b))
    return this
}

fun Context.toast(s: String) = Toast.makeText(this, s, Toast.LENGTH_LONG).show()

fun timeText(c: Context, ms: Long): String = DateFormat.getTimeInstance(DateFormat.SHORT).format(Date(ms))
fun dayTimeText(c: Context, ms: Long): String {
    val day = java.text.SimpleDateFormat("EEE", java.util.Locale.getDefault()).format(Date(ms))
    return "$day ${timeText(c, ms)}"
}
fun minutesText(c: Context, m: Int): String {
    val cal = java.util.Calendar.getInstance().apply {
        set(java.util.Calendar.HOUR_OF_DAY, m / 60); set(java.util.Calendar.MINUTE, m % 60)
    }
    return timeText(c, cal.timeInMillis)
}

/**
 * The parent PIN pad. With no PIN yet it asks for a new one (twice).
 * Five wrong tries rest the pad for a minute.
 */
object PinDialog {
    fun ask(a: Activity, onOk: () -> Unit) {
        if (!Prefs.hasPin(a)) { create(a, onOk); return }
        val now = System.currentTimeMillis()
        if (now < Prefs.pinBlockedUntil) {
            a.toast("Too many tries. Wait ${((Prefs.pinBlockedUntil - now) / 1000) + 1} seconds."); return
        }
        val e = pinField(a)
        val d = AlertDialog.Builder(a)
            .setTitle("Parent PIN")
            .setView(wrap(a, e))
            .setPositiveButton("Open") { _, _ ->
                if (Prefs.checkPin(a, e.text.toString())) { Prefs.wrongTries = 0; onOk() }
                else {
                    Prefs.wrongTries++
                    if (Prefs.wrongTries >= 5) { Prefs.wrongTries = 0; Prefs.pinBlockedUntil = System.currentTimeMillis() + 60_000L }
                    a.toast("Wrong PIN")
                }
            }
            .setNegativeButton("Cancel", null)
            .create()
        d.window?.setSoftInputMode(android.view.WindowManager.LayoutParams.SOFT_INPUT_STATE_ALWAYS_VISIBLE)
        d.show()
    }

    fun create(a: Activity, onOk: () -> Unit) {
        val e1 = pinField(a, "New PIN (4 to 8 digits)")
        val e2 = pinField(a, "Same PIN again")
        val box = LinearLayout(a).apply { orientation = LinearLayout.VERTICAL; addView(e1); addView(e2) }
        val d = AlertDialog.Builder(a)
            .setTitle("Set the parent PIN")
            .setMessage("You'll need this PIN to leave kids mode. Open the PIN pad by tapping the top-right corner of the kids' screen 5 times.")
            .setView(wrap(a, box))
            .setCancelable(false)
            .setPositiveButton("Save", null)
            .create()
        d.setOnShowListener {
            d.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                val p1 = e1.text.toString(); val p2 = e2.text.toString()
                when {
                    p1.length !in 4..8 -> a.toast("Use 4 to 8 digits")
                    p1 != p2 -> a.toast("The two PINs don't match")
                    else -> { Prefs.setPin(a, p1); d.dismiss(); a.toast("PIN saved"); onOk() }
                }
            }
        }
        d.show()
    }

    private fun pinField(a: Activity, hint: String = "PIN") = EditText(a).apply {
        this.hint = hint
        inputType = InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_VARIATION_PASSWORD
        textSize = 22f
    }

    private fun wrap(a: Activity, v: View) = LinearLayout(a).apply {
        orientation = LinearLayout.VERTICAL
        setPadding(a.dp(24), a.dp(8), a.dp(24), 0)
        addView(v)
    }
}
