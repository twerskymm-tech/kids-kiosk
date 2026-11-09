package com.kidskiosk.phone

import android.content.Context
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import kotlin.math.ceil
import kotlin.math.min

/**
 * Lays the app tiles out so they always fit on the screen, no scrolling:
 * picks the number of columns that gives the biggest tiles, and shrinks the tiles (icon and name too) as apps are added.
 */
class TileGrid(c: Context) : ViewGroup(c) {
    private val gap = c.dp(10)
    private val maxTile = c.dp(190)
    private var cols = 2
    private var cell = 0
    private var cellH = 0

    override fun onMeasure(widthSpec: Int, heightSpec: Int) {
        val w = MeasureSpec.getSize(widthSpec)
        val h = MeasureSpec.getSize(heightSpec)
        val n = childCount
        if (n == 0) { setMeasuredDimension(w, h); return }
        var best = 0; var bestCols = 1
        for (c in (if (n >= 2) 2 else 1)..min(5, maxOf(2, n))) {
            val rows = ceil(n / c.toDouble()).toInt()
            val cw = (w - gap * (c - 1)) / c
            val ch = (h - gap * (rows - 1)) / rows
            val size = min(cw, (ch / 0.95).toInt())
            if (size > best) { best = size; bestCols = c }
        }
        // A lone tile on the last row looks untidy: use full rows when the tiles stay big enough.
        if (n % bestCols != 0) {
            for (c in min(5, n) downTo 2) {
                if (n % c != 0) continue
                val rows = n / c
                val size = min((w - gap * (c - 1)) / c, (((h - gap * (rows - 1)) / rows) / 0.95).toInt())
                if (size >= best * 0.6 && size >= context.dp(96)) { best = size; bestCols = c; break }
            }
        }
        cols = bestCols
        cell = min(best, maxTile)
        val rows = ceil(n / cols.toDouble()).toInt()
        cellH = min((cell * 0.95).toInt(), (h - gap * (rows - 1)) / rows)
        for (i in 0 until n) {
            val v = getChildAt(i)
            (v as? Tile)?.fit(cell, cellH)
            v.measure(MeasureSpec.makeMeasureSpec(cell, MeasureSpec.EXACTLY), MeasureSpec.makeMeasureSpec(cellH, MeasureSpec.EXACTLY))
        }
        setMeasuredDimension(w, h)
    }

    override fun onLayout(changed: Boolean, l: Int, t: Int, r: Int, b: Int) {
        val n = childCount
        if (n == 0) return
        val rows = ceil(n / cols.toDouble()).toInt()
        val totalH = rows * cellH + (rows - 1) * gap
        val top = ((b - t) - totalH) / 2
        for (i in 0 until n) {
            val row = i / cols
            val inRow = if (row == rows - 1) n - row * cols else cols
            val rowW = inRow * cell + (inRow - 1) * gap
            val left = ((r - l) - rowW) / 2 + (i % cols) * (cell + gap)
            val y = top + row * (cellH + gap)
            getChildAt(i).layout(left, y, left + cell, y + cellH)
        }
    }
}

/** One app tile: icon on a white rounded square, name under it; both scale with the tile. */
class Tile(c: Context) : LinearLayout(c) {
    val icon = ImageView(c)
    val label = TextView(c)
    private var fitted = -1

    init {
        orientation = VERTICAL
        gravity = android.view.Gravity.CENTER
        addView(icon)
        addView(label)
        label.gravity = android.view.Gravity.CENTER
        label.setTextColor(0xFF1F2333.toInt())
        label.setTypeface(label.typeface, android.graphics.Typeface.BOLD)
        label.maxLines = 2
        label.setLineSpacing(0f, 0.9f)
        label.ellipsize = android.text.TextUtils.TruncateAt.END
    }

    fun fit(w: Int, h: Int) {
        if (fitted == w * 10000 + h) return
        fitted = w * 10000 + h
        val iconSize = (min(w, h) * 0.5).toInt()
        icon.layoutParams = LayoutParams(iconSize, iconSize)
        icon.scaleType = ImageView.ScaleType.FIT_CENTER
        val px = (min(w, h) * 0.1f).coerceIn(context.dpf(11f), context.dpf(19f))
        label.setTextSize(android.util.TypedValue.COMPLEX_UNIT_PX, px)
        label.layoutParams = LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT).apply { topMargin = (h * 0.05).toInt() }
        setPadding(w / 20, 0, w / 20, 0)
        background = rounded(0xF2FFFFFF.toInt(), min(w, h) * 0.18f)
    }

    var tileColor = 0
}

