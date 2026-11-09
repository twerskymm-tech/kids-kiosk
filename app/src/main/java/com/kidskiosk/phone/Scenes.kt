package com.kidskiosk.phone

import android.content.Context
import java.io.File

/** Shabbos and Yom Tov pictures: built-in art (res/drawable-nodpi, drawn from art/scenes.py) or the parent's own photo. */
object Scenes {
    /** key to name shown in parent mode */
    val ALL = linkedMapOf(
        "shabbos" to "Shabbos",
        "rosh_hashana" to "Rosh Hashana",
        "yom_kippur" to "Yom Kippur",
        "sukkos" to "Sukkos",
        "simchas_torah" to "Shemini Atzeres / Simchas Torah",
        "pesach" to "Pesach",
        "shavuos" to "Shavuos",
    )

    fun photoFile(c: Context, key: String) = File(c.filesDir, "scene_$key.jpg")

    fun builtIn(key: String): Int = when (key) {
        "rosh_hashana" -> R.drawable.scene_rosh_hashana
        "yom_kippur" -> R.drawable.scene_yom_kippur
        "sukkos" -> R.drawable.scene_sukkos
        "simchas_torah" -> R.drawable.scene_simchas_torah
        "pesach" -> R.drawable.scene_pesach
        "shavuos" -> R.drawable.scene_shavuos
        else -> R.drawable.scene_shabbos
    }
}
