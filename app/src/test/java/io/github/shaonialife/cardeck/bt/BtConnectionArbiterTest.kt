package io.github.shaonialife.cardeck.bt

import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class BtConnectionArbiterTest {
    @Test
    fun inboundCancelsPendingConnectBeforeAuthenticationStarts() {
        val gate = BtConnectionArbiter()
        var cancelled = false
        val attempt =
            checkNotNull(
                gate.beginOutbound("E2:FF:B3:00:77:17") {
                    // 回调执行时门闸必须已经关闭，断 HFP 的调用方也能立即停止后续操作。
                    assertTrue(gate.inboundActive)
                    cancelled = true
                },
            )
        val inbound = checkNotNull(gate.beginInbound("E2:FF:B3:00:77:17"))
        assertTrue(cancelled)
        assertFalse(gate.isCurrent(attempt))
        assertFalse(gate.establish(attempt))
        gate.endOutbound(attempt)
        assertTrue(gate.inboundActive)
        gate.endInbound(inbound)
        assertFalse(gate.inboundActive)
    }

    @Test
    fun establishedOutboundSessionIsNotInterruptedByDuplicateInbound() {
        val gate = BtConnectionArbiter()
        var cancelled = false
        val attempt = checkNotNull(gate.beginOutbound("car") { cancelled = true })
        assertTrue(gate.establish(attempt))
        assertNull(gate.beginInbound("car"))
        assertFalse(cancelled)
        assertTrue(gate.isCurrent(attempt))
        gate.endOutbound(attempt)
        assertNotNull(gate.beginInbound("car"))
    }

    @Test
    fun inboundBoxKeepsItsDirectionAcrossFailedAuthenticationRounds() {
        val gate = BtConnectionArbiter()
        val first = checkNotNull(gate.beginInbound("e2:ff:b3:00:77:17"))
        assertNull(gate.beginInbound("other"))
        assertNull(gate.beginOutbound("other") {})
        gate.endInbound(first)
        assertTrue(gate.prefersInbound("E2:FF:B3:00:77:17"))
        assertNull(gate.beginOutbound("E2:FF:B3:00:77:17") {})
        val retry = checkNotNull(gate.beginInbound("E2:FF:B3:00:77:17"))
        // 上一轮 finally 晚到也不得清除新会话。
        gate.endInbound(first)
        assertTrue(gate.inboundActive)
        gate.endInbound(retry)
        assertNotNull(gate.beginOutbound("other") {})
    }

    @Test
    fun finishingOldAttemptDoesNotReleaseNewAttempt() {
        val gate = BtConnectionArbiter()
        val old = checkNotNull(gate.beginOutbound("car") {})
        gate.endOutbound(old)
        val current = checkNotNull(gate.beginOutbound("car") {})
        gate.endOutbound(old)
        assertTrue(gate.isCurrent(current))
        assertNull(gate.beginOutbound("other") {})
    }
}
