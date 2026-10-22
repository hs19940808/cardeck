package io.github.shaonialife.cardeck.media

import org.junit.Assert.assertEquals
import org.junit.Test

/** 播放位置始终以播放器上报的锚点外推，不能因采样时刻变化向前后跳动。 */
class NowPlayingPositionTest {
    /** 协议层和桌面必须使用同一缓存与同一播放锚点。 */
    @Test
    fun iap2BridgeKeepsMediaFieldsAndExtrapolatedPosition() {
        val playing =
            MediaSessionsState.NowPlaying(
                "歌曲",
                "歌手",
                true,
                positionMs = 10_000,
                durationMs = 100_000,
                anchoredAtMs = 1_000,
                album = "专辑",
            )
        val snapshot = playing.toIap2Snapshot(6_000)
        assertEquals("歌曲", snapshot.title)
        assertEquals("歌手", snapshot.artist)
        assertEquals("专辑", snapshot.album)
        assertEquals(15_000L, snapshot.positionMs)
        assertEquals(100_000L, snapshot.durationMs)
        assertEquals(true, snapshot.playing)
    }

    @Test
    fun positionAdvancesFromItsPlaybackStateTimestamp() {
        val playing = MediaSessionsState.NowPlaying("歌曲", "歌手", true, positionMs = 10_000, durationMs = 100_000, anchoredAtMs = 1_000)
        assertEquals(15_000L, playing.displayPositionAt(6_000))
        assertEquals(16_000L, playing.copy().displayPositionAt(7_000))
    }

    @Test
    fun futureTimestampDoesNotMovePositionBackwards() {
        val playing = MediaSessionsState.NowPlaying("歌曲", "歌手", true, positionMs = 10_000, durationMs = 100_000, anchoredAtMs = 6_000)
        assertEquals(10_000L, playing.displayPositionAt(5_000))
    }

    @Test
    fun pauseSpeedAndDurationAreRespected() {
        val playing =
            MediaSessionsState.NowPlaying(
                "歌曲",
                "歌手",
                true,
                positionMs = 10_000,
                durationMs = 20_000,
                playbackSpeed = 2f,
                anchoredAtMs = 1_000,
            )
        assertEquals(14_000L, playing.displayPositionAt(3_000))
        assertEquals(20_000L, playing.displayPositionAt(100_000))
        assertEquals(10_000L, playing.copy(playing = false).displayPositionAt(100_000))
    }
}
