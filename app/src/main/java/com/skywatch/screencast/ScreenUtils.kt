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
     * Candidatos de resolução pra transmitir no preset escolhido (padrão 720p), do preferido
     * pro mais seguro. O encoder pega o primeiro que aceitar. Regras em [Resolution].
     */
    fun captureCandidates(context: Context, quality: Resolution.Quality): List<Pair<Int, Int>> {
        val (w, h) = landscapeSize(context)
        return Resolution.candidates(w, h, quality)
    }

    private fun even(v: Int) = if (v % 2 == 0) v else v - 1
}
