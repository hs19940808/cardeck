package io.github.shaonialife.cardeck.media

import android.os.SystemClock
import io.github.shaonialife.cardeck.iap2.Iap2NowPlayingSession

/** 协议线程只读媒体服务缓存，不在链路锁内查询系统会话或解码封面。 */
internal fun readIap2NowPlaying(): Iap2NowPlayingSession.Snapshot? =
    MediaSessionsState.nowPlaying?.toIap2Snapshot(SystemClock.elapsedRealtime())

/** 使用与车机桌面相同的播放位置推算，避免两套界面显示不同进度。 */
internal fun MediaSessionsState.NowPlaying.toIap2Snapshot(elapsedRealtimeMs: Long): Iap2NowPlayingSession.Snapshot =
    Iap2NowPlayingSession.Snapshot(title, artist, album, playing, durationMs, displayPositionAt(elapsedRealtimeMs))
