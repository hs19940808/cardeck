package io.github.shaonialife.cardeck.input

import android.view.KeyEvent
import io.github.shaonialife.cardeck.iap2.Iap2Csm
import io.github.shaonialife.cardeck.iap2.Iap2MessageIds
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** 组件注销、重注册和跨来源同键的完整生命周期。 */
class Iap2HidSessionTest {
    /** 停止、重注册和断链均属于取消确认，不能通过普通松开误点当前按钮。 */
    @Test
    fun componentShutdownCancelsConfirmationInsteadOfCompletingAClick() {
        val button =
            "05 01 09 08 a1 01 05 09 09 01 15 00 25 01 75 01 95 01 81 02 75 07 81 01 c0"
                .split(' ')
                .map { it.toInt(16).toByte() }
                .toByteArray()
        for (action in listOf("stop", "restart", "close")) {
            val session = Iap2HidSession({})
            val reports = mutableListOf<ControllerReports.Report>()
            session.onControllerInput = { reports.add(it) }
            val start = Iap2Csm.Frame(Iap2MessageIds.START_HID, Iap2Csm.u16(0, 3) + Iap2Csm.param(4, button))
            session.handle(start)
            report(session, 3, 1)
            when (action) {
                "stop" -> stop(session, 3)
                "restart" -> session.handle(start)
                "close" -> session.close()
            }
            assertEquals(setOf(KeyEvent.KEYCODE_DPAD_CENTER), reports.first().keys)
            assertEquals(emptySet<Int>(), reports.last().keys)
            assertTrue("$action 应发送取消标记", reports.last().canceled)
        }
    }

    private val descriptor =
        (
            "05 0c 09 01 a1 01 15 00 25 01 05 0c 09 b0 09 b5 09 b6 " +
                "0a 23 02 75 01 95 04 81 02 75 01 95 04 81 01 c0"
        ).split(' ').map { it.toInt(16).toByte() }.toByteArray()

    /** 真实组件号可以超过一字节，与 Report ID 拼接后仍保持唯一。 */
    @Test
    fun stoppingOneOfTwoComponentsDoesNotReleaseTheirSharedKey() {
        val session = Iap2HidSession({})
        val state = HidKeyState()
        val edges = mutableListOf<Pair<Int, Boolean>>()
        session.onControllerInput = { edges.addAll(state.update("iap:${it.id}", it.keys)) }
        start(session, 3)
        start(session, 65535)
        report(session, 3, 8)
        report(session, 65535, 8)
        stop(session, 3)
        assertEquals(listOf(KeyEvent.KEYCODE_HOME to true), edges)
        stop(session, 65535)
        assertEquals(listOf(KeyEvent.KEYCODE_HOME to true, KeyEvent.KEYCODE_HOME to false), edges)
    }

    /** 描述符重注册必须释放旧状态，否则再次按确认会被去重吞掉。 */
    @Test
    fun reRegisteringAComponentReleasesItsOldState() {
        val session = Iap2HidSession({})
        val states = mutableListOf<Set<Int>>()
        session.onControllerInput = { states.add(it.keys) }
        start(session, 7)
        report(session, 7, 8)
        start(session, 7)
        report(session, 7, 8)
        assertEquals(listOf(setOf(KeyEvent.KEYCODE_HOME), emptySet<Int>(), setOf(KeyEvent.KEYCODE_HOME)), states)
    }

    /** 多组件时缺少或截断组件编号不能靠最后一次 StartHID 猜。 */
    @Test
    fun ambiguousAndMalformedComponentIdentifiersAreIgnored() {
        val session = Iap2HidSession({})
        val media = mutableListOf<Int>()
        session.onMediaKey = { media.add(it) }
        start(session, 7)
        start(session, 8)
        session.handle(Iap2Csm.Frame(Iap2MessageIds.ACCESSORY_HID_REPORT, Iap2Csm.param(1, byteArrayOf(2))))
        session.handle(Iap2Csm.Frame(Iap2MessageIds.ACCESSORY_HID_REPORT, Iap2Csm.u8(0, 7) + Iap2Csm.param(1, byteArrayOf(2))))
        assertEquals(emptyList<Int>(), media)
        report(session, 7, 2)
        assertEquals(listOf(KeyEvent.KEYCODE_MEDIA_NEXT), media)
    }

    /** 通道退出要补松开；迟到包不应在没有 StartHID 时重新打开输入。 */
    @Test
    fun closingTheTransportReleasesKeysAndRejectsLateReports() {
        val session = Iap2HidSession({})
        val states = mutableListOf<Set<Int>>()
        session.onControllerInput = { states.add(it.keys) }
        start(session, 3)
        report(session, 3, 8)
        session.close()
        report(session, 3, 8)
        assertEquals(listOf(setOf(KeyEvent.KEYCODE_HOME), emptySet<Int>()), states)
        start(session, 3)
        report(session, 3, 8)
        assertEquals(2, states.size)
    }

    /** 多组件中的同一媒体 Usage 合并边沿，直到最后一个来源释放才允许重新短按。 */
    @Test
    fun mediaHoldAndReleaseAreIndependentAcrossComponents() {
        var clock = 0L
        val session = Iap2HidSession({}, { clock })
        val media = mutableListOf<Int>()
        session.onMediaKey = { media.add(it) }
        start(session, 3)
        start(session, 4)
        report(session, 3, 2)
        report(session, 4, 2)
        stop(session, 3)
        report(session, 4, 2)
        assertEquals(List(1) { KeyEvent.KEYCODE_MEDIA_NEXT }, media)
        clock = 1_500
        report(session, 4, 2)
        assertEquals(List(2) { KeyEvent.KEYCODE_MEDIA_NEXT }, media)
        report(session, 4, 0)
        report(session, 4, 2)
        assertEquals(List(3) { KeyEvent.KEYCODE_MEDIA_NEXT }, media)
    }

    /** 仅含组件号而没有 report data 的包是元数据，不能伪造成松开。 */
    @Test
    fun componentIdentifierAloneCannotBecomeAReport() {
        assertNull(Iap2HidSession.reportFromParams(Iap2Csm.parseParams(Iap2Csm.u16(0, 3))))
    }

    /** StartHID 按标准参数编号携带描述符，覆盖不同组件号的边界值。 */
    private fun start(
        session: Iap2HidSession,
        component: Int,
    ) {
        session.handle(Iap2Csm.Frame(Iap2MessageIds.START_HID, Iap2Csm.u16(0, component) + Iap2Csm.param(4, descriptor)))
    }

    /** 报告元数据与位图分开，不把组件编号作为报告前缀。 */
    private fun report(
        session: Iap2HidSession,
        component: Int,
        bits: Int,
    ) {
        session.handle(
            Iap2Csm.Frame(
                Iap2MessageIds.ACCESSORY_HID_REPORT,
                Iap2Csm.u16(0, component) + Iap2Csm.param(1, byteArrayOf(bits.toByte())),
            ),
        )
    }

    /** 组件注销只针对指定编号。 */
    private fun stop(
        session: Iap2HidSession,
        component: Int,
    ) {
        session.handle(Iap2Csm.Frame(Iap2MessageIds.STOP_HID, Iap2Csm.u16(0, component)))
    }
}
