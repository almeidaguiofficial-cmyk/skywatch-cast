package com.skywatch.screencast

import kotlin.math.roundToInt

/**
 * Regras de resolução da transmissão, sem dependência de Android (testável na JVM).
 *
 * Por que 720p é o padrão: o feed do drone dentro do app de voo já chega ao celular em no
 * máximo 1080p, então capturar a tela inteira (ex.: 2712×1220) não ganha detalhe real — só
 * espalha o mesmo bitrate por 3,3 milhões de pixels. Em 4G/Wi-Fi de campo (~1-2,5 Mbps) isso
 * vira mosaico cinza quando o drone se move. Em 1280 no lado maior sobra ~4,5x mais bit por pixel.
 */
object Resolution {

    /** Presets da tela do app. [maxLong] = lado maior máximo; null = resolução nativa da tela. */
    enum class Quality(val key: String, val maxLong: Int?) {
        HD("720", 1280),
        FULL_HD("1080", 1920),
        NATIVE("native", null);

        companion object {
            val DEFAULT = HD
            fun fromKey(key: String?): Quality = entries.firstOrNull { it.key == key } ?: DEFAULT
        }
    }

    /**
     * Reduz (w, h) pra caber em [maxLong] no lado maior, mantendo o formato da tela.
     * Quando reduz, alinha em múltiplos de 16 (tamanho mais seguro pros encoders H.264 de
     * celular). Nunca amplia: se já cabe, devolve o tamanho original (par).
     */
    fun fit(w: Int, h: Int, maxLong: Int): Pair<Int, Int> {
        val lng = maxOf(w, h)
        if (lng <= maxLong) return even(w) to even(h)
        val s = maxLong.toDouble() / lng
        return align16(w * s) to align16(h * s)
    }

    /**
     * Candidatos pro encoder, do preferido pro mais seguro. O serviço usa o primeiro que o
     * encoder do aparelho aceitar; 1280×720 fica sempre como último recurso.
     */
    fun candidates(nativeW: Int, nativeH: Int, quality: Quality): List<Pair<Int, Int>> {
        val list = mutableListOf<Pair<Int, Int>>()
        val max = quality.maxLong
        if (max == null) {
            list.add(even(nativeW) to even(nativeH))
            if (maxOf(nativeW, nativeH) > 1920) list.add(fit(nativeW, nativeH, 1920))
        } else {
            list.add(fit(nativeW, nativeH, max))
        }
        list.add(1280 to 720)
        return list.distinct()
    }

    private fun align16(v: Double): Int = maxOf(16, (v / 16.0).roundToInt() * 16)

    private fun even(v: Int) = if (v % 2 == 0) v else v - 1
}
