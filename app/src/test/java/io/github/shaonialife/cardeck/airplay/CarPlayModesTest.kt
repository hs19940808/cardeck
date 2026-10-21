package io.github.shaonialife.cardeck.airplay

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** 覆盖原车接管、导航键重新唤起与借用归还，防止固定抢屏破坏资源交接。 */
class CarPlayModesTest {
    @Test
    fun initialStateKeepsTheExistingSetupDeclaration() {
        val snapshot = CarPlayModes().snapshot()
        assertResource(snapshot, 1L, 1L, 1L)
        assertResource(snapshot, 2L, 1L, 1L)
    }

    @Test
    fun carTakingScreenGetsAnAcceptedSnapshotInsteadOfPhoneReclaimingIt() {
        val modes = CarPlayModes()
        val reply = modes.change(request(1L, 1L))
        assertEquals(0L, reply["status"])
        val accepted = reply["params"] as Map<*, *>
        assertResource(accepted, 1L, 2L, 2L)
        assertResource(accepted, 2L, 1L, 1L)
        assertEquals(accepted, modes.snapshot())
    }

    @Test
    fun navigationRequestReclaimsScreenWhileKeepingCarAudioAndCallState() {
        val modes = CarPlayModes()
        modes.change(request(1L, 1L))
        modes.change(request(2L, 1L))
        modes.change(mapOf("appStates" to listOf(mapOf("appStateID" to 2L, "state" to true))))

        modes.takeScreen()

        val snapshot = modes.snapshot()
        assertResource(snapshot, 1L, 1L, 1L)
        assertResource(snapshot, 2L, 2L, 2L)
        val call = (snapshot["appStates"] as List<*>).filterIsInstance<Map<*, *>>().first { it["appStateID"] == 2L }
        assertEquals(2L, call["entity"])
    }

    @Test
    fun releasingCarOwnershipDoesNotPretendThePhoneAlreadyTookScreen() {
        val modes = CarPlayModes()
        modes.change(request(1L, 1L))
        modes.change(request(1L, 2L))
        assertResource(modes.snapshot(), 1L, 0L, 0L)
        modes.takeScreen()
        assertResource(modes.snapshot(), 1L, 1L, 1L)
    }

    @Test
    fun borrowingScreenKeepsItsPermanentOwnerAndRestoresItOnReturn() {
        val modes = CarPlayModes()
        modes.change(request(1L, 3L))
        assertResource(modes.snapshot(), 1L, 2L, 1L)
        modes.change(request(1L, 4L))
        assertResource(modes.snapshot(), 1L, 1L, 1L)
    }

    @Test
    fun nestedBorrowsNeedTheMatchingNumberOfReturns() {
        val modes = CarPlayModes()
        modes.change(request(1L, 3L))
        modes.change(request(1L, 3L))
        modes.change(request(1L, 4L))
        assertResource(modes.snapshot(), 1L, 2L, 1L)
        modes.change(request(1L, 4L))
        assertResource(modes.snapshot(), 1L, 1L, 1L)
    }

    @Test
    fun invalidResourceRollsBackTheEntireBatch() {
        val modes = CarPlayModes()
        val before = modes.snapshot()
        val reply =
            modes.change(
                mapOf(
                    "resources" to
                        listOf(
                            mapOf("resourceID" to 1L, "transferType" to 1L),
                            mapOf("resourceID" to 99L, "transferType" to 1L),
                        ),
                ),
            )
        assertNotEquals(0L, reply["status"])
        assertNull(reply["params"])
        assertEquals(before, modes.snapshot())
    }

    @Test
    fun malformedStateDoesNotCommitAnOtherwiseValidResourceChange() {
        val modes = CarPlayModes()
        val before = modes.snapshot()
        val reply = modes.change(request(1L, 1L) + ("appStates" to listOf(mapOf("appStateID" to 2L))))
        assertNotEquals(0L, reply["status"])
        assertEquals(before, modes.snapshot())
    }

    @Test
    fun unknownTransferAndMalformedListsAreRejected() {
        val modes = CarPlayModes()
        assertNotEquals(0L, modes.change(request(1L, 999L))["status"])
        assertNotEquals(0L, modes.change(mapOf("resources" to "bad"))["status"])
        assertNotEquals(0L, modes.change(mapOf("resources" to listOf(1L)))["status"])
        assertNotEquals(0L, modes.change(null)["status"])
    }

    @Test
    fun olderSnapshotsStayUnchangedWhenResourcesChange() {
        val modes = CarPlayModes()
        val before = modes.snapshot()
        modes.change(request(1L, 1L))
        assertResource(before, 1L, 1L, 1L)
        assertResource(modes.snapshot(), 1L, 2L, 2L)
    }

    /** 带用户发起优先级和约束字段的资源请求，验证交接不再被固定资源声明覆盖。 */
    private fun request(
        id: Long,
        transfer: Long,
    ): Map<String, Any> =
        mapOf(
            "resources" to
                listOf(
                    mapOf(
                        "resourceID" to id,
                        "transferType" to transfer,
                        "transferPriority" to 500L,
                        "takeConstraint" to 1000L,
                        "borrowConstraint" to 1000L,
                    ),
                ),
        )

    /** 核对线上快照里的当前与永久归属，而不是内部实现字段。 */
    private fun assertResource(
        snapshot: Map<*, *>,
        id: Long,
        entity: Long,
        permanentEntity: Long,
    ) {
        val resource = (snapshot["resources"] as List<*>).filterIsInstance<Map<*, *>>().first { it["resourceID"] == id }
        assertEquals(entity, resource["entity"])
        assertEquals(permanentEntity, resource["permanentEntity"])
    }
}
