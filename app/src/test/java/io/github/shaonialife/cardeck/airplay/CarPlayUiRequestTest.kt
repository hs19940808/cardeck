package io.github.shaonialife.cardeck.airplay

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** 使用实车导出的 requestUI 形态验证导航键不再落入未知命令分支。 */
class CarPlayUiRequestTest {
    @Test
    fun mazdaNavigationButtonSelectsTheDefaultMapPage() {
        assertEquals(CarPlayUiRequest.MAPS, CarPlayUiRequest.from(command("maps:")))
    }

    @Test
    fun plainCarPlayRequestSelectsDesktop() {
        assertEquals(CarPlayUiRequest.HOME, CarPlayUiRequest.from(mapOf("type" to "requestUI")))
        assertEquals(CarPlayUiRequest.HOME, CarPlayUiRequest.from(command("")))
        assertEquals(CarPlayUiRequest.HOME, CarPlayUiRequest.from(mapOf("params" to emptyMap<String, Any>())))
    }

    @Test
    fun musicRequestUsesExistingMediaDashboard() {
        assertEquals(CarPlayUiRequest.MUSIC, CarPlayUiRequest.from(command("music:")))
    }

    @Test
    fun unknownUrlsAndMalformedParametersDoNotSwitchPages() {
        assertNull(CarPlayUiRequest.from(command("https://example.com")))
        assertNull(CarPlayUiRequest.from(command("intent:maps:")))
        assertNull(CarPlayUiRequest.from(mapOf("params" to "bad")))
        assertNull(CarPlayUiRequest.from(mapOf("params" to mapOf("url" to 1L))))
    }

    /** 保留车机命令的 params/url 层级。 */
    private fun command(url: String): Map<String, Any> = mapOf("type" to "requestUI", "params" to mapOf("url" to url))
}
