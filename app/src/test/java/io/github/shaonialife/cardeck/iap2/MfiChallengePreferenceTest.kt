package io.github.shaonialife.cardeck.iap2

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * MFi 挑战长度的**首试档位**判定：真车系（含冒充车机的无线盒子）20B 优先。
 *
 * 背景（2026-10-21 smartBox-933C 实车日志）：该盒子 0x1D01 报
 * `smartBox-933C / Mazda DA / Yanfeng Visteon(Chongqing)…`，收到 32B 挑战后
 * **0.1 秒直接断链**（`MFi 挑战期间链路断开（32B，对端不接受该长度）`），
 * 而它的 32B 断链行为与奥迪 MMI 6318 完全一致——属于「真车系只要 20B」。
 * 若仍按测试台基线 32B 先试，每轮都会白丢一次链路，而盒子回连是有限窗口，
 * 窗口一过就再也连不上（12:53 日志正是这个形态：出站永远失败、盒子不回连）。
 */
class MfiChallengePreferenceTest {
    /** 日志原样取出的身份文本（identityText = name + model + manufacturer，小写）。 */
    private val smartBox =
        "smartbox-933c mazda da yanfeng visteon(chongqing) automotive electronics co., ltd"

    @Test
    fun mazdaBoxPrefersTwentyByte() {
        assertTrue(prefersTwentyByteChallenge(smartBox))
    }

    @Test
    fun realHeadUnitsStillPreferTwentyByte() {
        assertTrue(prefersTwentyByteChallenge("audi mmi 3315 audi 5163 delphi"))
        assertTrue(prefersTwentyByteChallenge("mib3 harman porsche"))
    }

    @Test
    fun androidTestBenchKeepsThirtyTwoByteFirst() {
        // DiPlay/xcertplay 测试台只接受精确 32B，不能因为这条规则被改到 20B 首试
        assertFalse(prefersTwentyByteChallenge("xcertplay diplay carlinkit authv3"))
        assertFalse(prefersTwentyByteChallenge("远方的mix 2 android diplay"))
    }
}
