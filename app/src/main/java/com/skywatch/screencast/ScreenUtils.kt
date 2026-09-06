package com.skywatch.screencast

import android.content.Context
import android.os.Build
import android.util.DisplayMetrics
import android.view.WindowManager

object ScreenUtils {

    /** Tamanho real da tela em LANDSCAPE (lado maior x lado menor), sempre par. */
    fun landscapeSize(context: Context): Pair<Int, Int> {
        val wm = context.getSystemService(Context.WINDOW_SERVICE) as WindowManager
        val w: Int
        val h: Int
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            val b = wm.maximumWindowMetrics.bounds
            w = b.width(); h = b.height()
        } else {
            val dm = DisplayMetrics()
            @Suppress("DEPRECATION") wm.defaultDisplay.getRealMetrics(dm)
            w = dm.widthPixels; h = dm.heightPixels
        }
        return even(maxOf(w, h)) to even(minOf(w, h))
    }

    /**
     * Candidatos de resolução pra transmitir, do melhor pro mais seguro:
     * 1) resolução nativa da tela; 2) nativa reduzida pra caber em 1920 no lado maior;
     * 3) 1280x720 (fallback garantido). O encoder pega o primeiro que aceitar.
     */
    fun captureCandidates(context: Context): List<Pair<Int, Int>> {
        val (w, h) = landscapeSize(context)
        val list = mutableListOf(w to h)
        if (maxOf(w, h) > 1920) list.add(fit(w, h, 1920))
        list.add(1280 to 720)
        return list.distinct()
    }

    private fun fit(w: Int, h: Int, maxLong: Int): Pair<Int, Int> {
        val lng = maxOf(w, h)
        if (lng <= maxLong) return even(w) to even(h)
        val s = maxLong.toFloat() / lng
        return even((w * s).toInt()) to even((h * s).toInt())
    }

    private fun even(v: Int) = if (v % 2 == 0) v else v - 1
}
