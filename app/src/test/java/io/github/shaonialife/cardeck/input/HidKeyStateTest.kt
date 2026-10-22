package io.github.shaonialife.cardeck.input

import org.junit.Assert.assertEquals
import org.junit.Test

/** 车辆重复发送当前状态不应造成连点或重复切歌。 */
class HidKeyStateTest {
    @Test
    fun pressHoldAndReleaseHaveOneMatchingPair() {
        val state = HidKeyState()
        assertEquals(listOf(23 to true), state.update("knob:0", setOf(23)))
        assertEquals(emptyList<Pair<Int, Boolean>>(), state.update("knob:0", setOf(23)))
        assertEquals(listOf(23 to false), state.update("knob:0", emptySet()))
    }

    @Test
    fun changingDirectionReleasesOldKeyBeforePressingNewKey() {
        val state = HidKeyState()
        state.update("knob", setOf(21))
        assertEquals(listOf(21 to false, 22 to true), state.update("knob", setOf(22)))
    }

    @Test
    fun reportIdsDoNotReleaseEachOthersKeys() {
        val state = HidKeyState()
        state.update("knob:1", setOf(23))
        assertEquals(listOf(4 to true), state.update("knob:2", setOf(4)))
        assertEquals(listOf(4 to false), state.update("knob:2", emptySet()))
        assertEquals(emptyList<Pair<Int, Boolean>>(), state.update("knob:1", setOf(23)))
    }

    /** 不同报告声明同一个键时，要等所有来源松开后才注入 UP。 */
    @Test
    fun sharedKeyRemainsPressedUntilItsLastSourceReleases() {
        val state = HidKeyState()
        assertEquals(listOf(23 to true), state.update("knob:1", setOf(23)))
        assertEquals(emptyList<Pair<Int, Boolean>>(), state.update("knob:2", setOf(23)))
        assertEquals(emptyList<Pair<Int, Boolean>>(), state.update("knob:1", emptySet()))
        assertEquals(emptyList<Pair<Int, Boolean>>(), state.update("knob:2", setOf(23)))
        assertEquals(listOf(23 to false), state.update("knob:2", emptySet()))
    }

    /** 队列退役清空来源后，新会话同样的首包也必须生成一次按下。 */
    @Test
    fun clearingTheSessionMakesItsFirstReportFresh() {
        val state = HidKeyState()
        state.update("event:0", setOf(23))
        state.update("bt:3", setOf(23))
        state.clear()
        assertEquals(listOf(23 to true), state.update("event:0", setOf(23)))
    }
}
