package io.github.shaonialife.cardeck.iap2

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

/** 校验线上 TLV 字段、订阅筛选、增量和生命周期，而非只检查内部状态。 */
class Iap2NowPlayingSessionTest {
    private var clock = 0L
    private var current: Iap2NowPlayingSession.Snapshot? = song()
    private val session = Iap2NowPlayingSession({ current }, { clock })

    @Test
    fun firstSubscriptionContainsLiveMediaAndPlaybackFields() {
        val fields = fields(start())
        assertEquals("夜曲", fields.getValue(0 to 1).asString())
        assertEquals("周杰伦", fields.getValue(0 to 12).asString())
        assertEquals("十一月的萧邦", fields.getValue(0 to 6).asString())
        assertArrayEquals(byteArrayOf(0, 2, 0xBF.toByte(), 0x20), fields.getValue(0 to 4).value)
        assertArrayEquals(byteArrayOf(1), fields.getValue(1 to 0).value)
        assertArrayEquals(byteArrayOf(0, 0, 0xA4.toByte(), 0x10), fields.getValue(1 to 1).value)
    }

    @Test
    fun subscriptionOnlySendsRequestedSupportedAttributes() {
        val request =
            Iap2Csm.group(0, Iap2Csm.param(1, ByteArray(0)), Iap2Csm.param(20, ByteArray(0))) +
                Iap2Csm.group(1, Iap2Csm.param(0, ByteArray(0)))
        assertEquals(setOf(0 to 1, 1 to 0), fields(start(request)).keys)
    }

    @Test
    fun repeatedSnapshotsDoNotResendUnchangedFields() {
        start()
        current = current?.copy()
        clock = 1_000
        assertNull(session.poll())
        current = current?.copy(title = "新歌", positionMs = 43_000)
        clock = 2_000
        val delta = fields(session.poll())
        assertEquals(setOf(0 to 1, 1 to 1), delta.keys)
        assertEquals("新歌", delta.getValue(0 to 1).asString())
    }

    @Test
    fun playingToPausedOnlyUpdatesItsState() {
        start()
        current = current?.copy(playing = false)
        clock = 1_000
        val delta = fields(session.poll())
        assertEquals(setOf(1 to 0), delta.keys)
        assertArrayEquals(byteArrayOf(2), delta.getValue(1 to 0).value)
    }

    @Test
    fun playerDisappearingClearsItsOldSongAndProgress() {
        start()
        current = null
        clock = 1_000
        val delta = fields(session.poll())
        assertEquals("", delta.getValue(0 to 1).asString())
        assertEquals("", delta.getValue(0 to 12).asString())
        assertEquals("", delta.getValue(0 to 6).asString())
        assertArrayEquals(ByteArray(4), delta.getValue(0 to 4).value)
        assertArrayEquals(ByteArray(4), delta.getValue(1 to 1).value)
        assertArrayEquals(byteArrayOf(0), delta.getValue(1 to 0).value)
    }

    @Test
    fun stopAndClosePreventLateUpdatesAndResubscribeSendsFullState() {
        start()
        session.handle(Iap2Csm.Frame(Iap2MessageIds.STOP_NOW_PLAYING_UPDATES, ByteArray(0)))
        clock = 2_000
        current = current?.copy(title = "另一首")
        assertNull(session.poll())
        assertEquals(6, fields(start()).size)
        session.close()
        assertNull(start())
        clock = 4_000
        assertNull(session.poll())
    }

    @Test
    fun malformedSubscriptionKeepsThePreviousValidRequest() {
        start()
        assertNull(start(byteArrayOf(0, 10, 0, 0, 1)))
        assertNull(start(Iap2Csm.group(0, byteArrayOf(0, 10, 0, 1, 1))))
        current = current?.copy(title = "仍可更新")
        clock = 1_000
        assertEquals("仍可更新", fields(session.poll()).getValue(0 to 1).asString())
    }

    @Test
    fun pollingIsLimitedToOncePerSecondAndOnlyWhileSubscribed() {
        var reads = 0
        val sampled =
            Iap2NowPlayingSession({
                reads++
                current
            }, { clock })
        assertNull(sampled.poll())
        sampled.handle(Iap2Csm.Frame(Iap2MessageIds.START_NOW_PLAYING_UPDATES, ByteArray(0)))
        clock = 999
        assertNull(sampled.poll())
        assertEquals(1, reads)
        clock = 1_000
        sampled.poll()
        assertEquals(2, reads)
    }

    @Test
    fun malformedMetadataCannotBreakStringsOrUnsignedMillisecondFields() {
        current = song().copy(title = "夜\u0000曲" + "好".repeat(1_000), durationMs = Long.MAX_VALUE, positionMs = Long.MAX_VALUE)
        val fields = fields(start())
        val title = fields.getValue(0 to 1).asString()
        assertNotNull(title)
        assertEquals(512, title!!.length)
        assertEquals("夜 曲", title.take(3))
        assertArrayEquals(ByteArray(4) { 0xFF.toByte() }, fields.getValue(0 to 4).value)
        assertArrayEquals(ByteArray(4) { 0xFF.toByte() }, fields.getValue(1 to 1).value)
    }

    @Test
    fun emptyPlayerKeepsTheCapturedMib3IdleResponse() {
        current = null
        val frame = Iap2Csm.encode(Iap2MessageIds.NOW_PLAYING_UPDATE, requireNotNull(start()))
        assertEquals(0x55, frame.size)
        assertArrayEquals(Iap2NowPlayingSession.IDLE_FRAME, frame)
        clock = 1_000
        assertNull(session.poll())
    }

    /** 只用标准 StartNowPlayingUpdates 消息驱动入口。 */
    private fun start(body: ByteArray = ByteArray(0)): ByteArray? =
        session.handle(Iap2Csm.Frame(Iap2MessageIds.START_NOW_PLAYING_UPDATES, body))

    /** 按车机线上结构解析子参数，键为 group/attribute。 */
    private fun fields(body: ByteArray?): Map<Pair<Int, Int>, Iap2Csm.Param> =
        Iap2Csm
            .parseParams(requireNotNull(body))
            .flatMap { group ->
                Iap2Csm.parseParams(group.value).map { (group.id to it.id) to it }
            }.toMap()

    /** 中文文本、毫秒大端数值和播放状态各有独立断言。 */
    private fun song() = Iap2NowPlayingSession.Snapshot("夜曲", "周杰伦", "十一月的萧邦", true, 180_000, 42_000)
}
