package com.tapinvaders

import android.content.Context
import android.os.Build

/**
 * Tiny persistent store — no settings menu. Remembers a high score and best
 * wave PER MODE (classic / remix / fps), and whether to render side-by-side.
 */
class SettingsStore(context: Context) {
    private val p = context.getSharedPreferences("tapinvaders", Context.MODE_PRIVATE)

    private val deviceText = listOf(
        Build.MODEL, Build.MANUFACTURER, Build.BRAND, Build.DEVICE, Build.PRODUCT
    ).joinToString(" ").lowercase()

    // FABLE_X3_STARTER_GUIDE gotcha #24: the X3 Pro reports Build.MODEL=ARGF20;
    // detect RayNeo hardware by manufacturer/brand/product instead.
    val isRayNeoX3 =
        "rayneo" in deviceText || "leiniao" in deviceText || "ffalcon" in deviceText ||
            ("x3" in deviceText && ("tcl" in deviceText || "falcon" in deviceText))

    val sbs get() = isRayNeoX3 // side-by-side for the glasses, single view elsewhere

    fun highScore(mode: Int): Int = p.getInt("hi$mode", 0)

    fun setHighScore(mode: Int, v: Int) {
        if (v > highScore(mode)) p.edit().putInt("hi$mode", v).apply()
    }

    fun bestWave(mode: Int): Int = p.getInt("bw$mode", 1)

    fun setBestWave(mode: Int, v: Int) {
        if (v > bestWave(mode)) p.edit().putInt("bw$mode", v).apply()
    }

    var games: Int
        get() = p.getInt("games", 0)
        set(v) { p.edit().putInt("games", v).apply() }
}
