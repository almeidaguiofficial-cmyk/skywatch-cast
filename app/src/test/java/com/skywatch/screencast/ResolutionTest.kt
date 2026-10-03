package com.skywatch.screencast

import com.skywatch.screencast.Resolution.Quality
import org.junit.Assert.assertEquals
import org.junit.Test

class ResolutionTest {

    @Test
    fun `tela 2712x1220 em 720p vira 1280x576 mantendo o formato`() {
        assertEquals(1280 to 576, Resolution.fit(2712, 1220, 1280))
    }

    @Test
    fun `tela 2712x1220 em 1080p vira 1920x864`() {
        assertEquals(1920 to 864, Resolution.fit(2712, 1220, 1920))
    }

    @Test
    fun `lado menor arredonda pro multiplo de 16 mais proximo`() {
        // 1080 * 1280 / 2340 = 590,8 -> 592
        assertEquals(1280 to 592, Resolution.fit(2340, 1080, 1280))
    }

    @Test
    fun `nunca amplia uma tela que ja cabe`() {
        assertEquals(1280 to 720, Resolution.fit(1280, 720, 1280))
        assertEquals(1600 to 720, Resolution.fit(1600, 720, 1920))
    }

    @Test
    fun `padrao 720p tenta a tela reduzida e depois 1280x720`() {
        assertEquals(listOf(1280 to 576, 1280 to 720), Resolution.candidates(2712, 1220, Quality.HD))
    }

    @Test
    fun `nativa mantem a cadeia antiga nativa depois fit 1920 depois 1280x720`() {
        assertEquals(
            listOf(2712 to 1220, 1920 to 864, 1280 to 720),
            Resolution.candidates(2712, 1220, Quality.NATIVE),
        )
    }

    @Test
    fun `candidatos repetidos aparecem uma vez so`() {
        assertEquals(listOf(1280 to 720), Resolution.candidates(1280, 720, Quality.HD))
    }

    @Test
    fun `preferencia ausente ou invalida cai no 720p`() {
        assertEquals(Quality.HD, Quality.fromKey(null))
        assertEquals(Quality.HD, Quality.fromKey("lixo"))
        assertEquals(Quality.NATIVE, Quality.fromKey("native"))
        assertEquals(Quality.FULL_HD, Quality.fromKey("1080"))
    }
}
