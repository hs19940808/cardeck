package io.github.shaonialife.cardeck.net

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** 用 smartBox 的真实邀请格式验证无蓝牙交接的入口与取消边界。 */
class CarPlayCtrlInviteTest {
    private val invite = CarPlayCtrlInvite("192.168.1.101", "192.168.1.102", "73588229206")

    @Test
    fun parsesSmartBoxInviteWithoutBluetoothEndpoint() {
        val parsed =
            CarPlayCtrlInvite.parse(
                "GET /ctrl-int/1/connect HTTP/1.1\r\n" +
                    "Host: Android_N6J3BTYG.local.\r\n" +
                    "User-Agent: AirPlay/320.17.6\r\n" +
                    "airplay-receiver-device-id: 73588229206\r\n\r\n",
                "192.168.1.101",
                "192.168.1.102",
            )
        assertEquals(invite, parsed)
    }

    @Test
    fun probeOrSimilarPathDoesNotStartSession() {
        listOf("GET /ctrl-int/1 HTTP/1.1", "GET /ctrl-int/1/connect-other HTTP/1.1", "POST /ctrl-int/1/connect HTTP/1.1").forEach {
            assertNull(CarPlayCtrlInvite.parse(it, invite.peerAddress, invite.localAddress))
        }
    }

    @Test
    fun receiverIdIsOptionalAndQueryStringIsAccepted() {
        val parsed =
            CarPlayCtrlInvite.parse(
                "GET /ctrl-int/1/connect?reason=reconnect HTTP/1.1\r\n\r\n",
                invite.peerAddress,
                invite.localAddress,
            )
        assertNotNull(parsed)
        assertNull(parsed?.receiverId)
    }

    @Test
    fun selectedBoxHotspotAllowsInviteButWrongTargetDoesNot() {
        assertTrue(invite.matchesHotspot("smartBox-933C", "smartBox-933C"))
        assertFalse(invite.matchesHotspot("远方的MIX 2", "smartBox-933C"))
        assertFalse(invite.matchesHotspot("smartBox-933C", "Home WiFi"))
    }

    @Test
    fun unknownTargetOrSsidKeepsBluetoothPath() {
        assertFalse(invite.matchesHotspot(null, "smartBox-933C"))
        assertFalse(invite.matchesHotspot("smartBox-933C", null))
        assertFalse(invite.matchesHotspot("", ""))
    }

    @Test
    fun inviteMustBelongToTheBoundWifi() {
        assertTrue(invite.matchesNetwork("192.168.1.102", emptyList()))
        assertFalse(invite.matchesNetwork("192.168.50.24", emptyList()))
        assertFalse(invite.matchesNetwork(null, emptyList()))
    }

    @Test
    fun peerOutsideWifiSubnetIsRejectedEvenWithSameLocalAddress() {
        assertFalse(invite.copy(peerAddress = "192.168.50.2").matchesNetwork("192.168.1.102", emptyList()))
    }

    @Test
    fun ipv6ScopeDoesNotHideSameNetwork() {
        val scoped = invite.copy(peerAddress = "fe80::1%wlan0", localAddress = "fe80::2%wlan0")
        assertTrue(scoped.matchesNetwork(null, listOf("fe80::2")))
        assertFalse(scoped.matchesNetwork(null, listOf("fe80::3")))
    }

    @Test
    fun invitesBeforeUserStartsConnectionAreIgnored() {
        assertNull(CtrlInviteGate().acquire())
    }

    @Test
    fun repeatedInviteDoesNotCreateConcurrentConnection() {
        val gate = CtrlInviteGate()
        gate.start()
        val ticket = requireNotNull(gate.acquire())
        assertNull(gate.acquire())
        gate.release(ticket)
        assertNotNull(gate.acquire())
    }

    @Test
    fun normalBluetoothHandoffAndActiveAirPlayKeepTheirOwnership() {
        val gate = CtrlInviteGate()
        gate.start()
        assertNull(gate.acquire(bluetoothActive = true))
        assertNull(gate.acquire(airPlayActive = true))
        assertNotNull(gate.acquire())
    }

    @Test
    fun cancellingInvalidatesTaskWaitingForWifi() {
        val gate = CtrlInviteGate()
        gate.start()
        val ticket = requireNotNull(gate.acquire())
        gate.stop()
        assertFalse(gate.isCurrent(ticket))
        assertNull(gate.acquire())
    }

    @Test
    fun oldTaskCannotReviveOrUnlockNewAttemptAfterRestart() {
        val gate = CtrlInviteGate()
        gate.start()
        val oldTicket = requireNotNull(gate.acquire())
        gate.stop()
        gate.start()
        val newTicket = requireNotNull(gate.acquire())
        gate.release(oldTicket)
        assertFalse(gate.isCurrent(oldTicket))
        assertTrue(gate.isCurrent(newTicket))
        assertNull(gate.acquire())
    }
}
