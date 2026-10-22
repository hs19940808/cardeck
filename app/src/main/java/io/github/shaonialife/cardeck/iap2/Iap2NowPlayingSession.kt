package io.github.shaonialife.cardeck.iap2

/** 按车机订阅推送媒体文本、时长、进度和播放状态；蓝牙与 Wi-Fi 共用此状态机。 */
internal class Iap2NowPlayingSession(
    private val snapshot: () -> Snapshot?,
    private val nowMillis: () -> Long = { System.nanoTime() / 1_000_000L },
) {
    /** 只携带当前已实现的媒体字段，封面文件传输和播放队列不在此数据通路。 */
    data class Snapshot(
        val title: String? = null,
        val artist: String? = null,
        val album: String? = null,
        val playing: Boolean = false,
        val durationMs: Long = 0,
        val positionMs: Long = 0,
    )

    private var requested = emptyMap<Int, Set<Int>>()
    private var lastFields = emptyMap<Pair<Int, Int>, ByteArray>()
    private var active = false
    private var closed = false
    private var lastPollAt = 0L

    /** 首次订阅立即返回完整已支持字段；停止后不再读取或发送媒体状态。 */
    fun handle(frame: Iap2Csm.Frame): ByteArray? {
        if (closed) return null
        return when (frame.messageId) {
            Iap2MessageIds.START_NOW_PLAYING_UPDATES -> subscribe(frame.body)
            Iap2MessageIds.STOP_NOW_PLAYING_UPDATES -> {
                active = false
                null
            }
            else -> null
        }
    }

    /** 复用宿主节拍，最多每秒检查一次；只返回发生变化的订阅字段。 */
    fun poll(): ByteArray? {
        if (!active || closed) return null
        val now = nowMillis()
        if (now - lastPollAt < 1_000L) return null
        lastPollAt = now
        val current = fields(snapshot())
        val changed = current.filter { (key, value) -> lastFields[key]?.contentEquals(value) != true }
        lastFields = current
        return changed.takeIf { it.isNotEmpty() }?.let(::body)
    }

    /** 连接结束永久关闭本实例，迟到的订阅不得复活旧会话。 */
    fun close() {
        active = false
        closed = true
        lastFields = emptyMap()
    }

    /** 校验嵌套参数完整性，再替换订阅，避免畸形请求清掉已有有效订阅。 */
    private fun subscribe(bytes: ByteArray): ByteArray? {
        val groups = completeParams(bytes) ?: return null
        val selected = mutableMapOf<Int, Set<Int>>()
        for ((group, supported) in SUPPORTED) {
            selected[group] =
                if (bytes.isEmpty()) {
                    supported
                } else {
                    val raw = groups.firstOrNull { it.id == group }?.value
                    val attributes = raw?.let(::completeParams) ?: if (raw == null) emptyList() else return null
                    attributes.map { it.id }.toSet().intersect(supported)
                }
        }
        requested = selected
        active = true
        lastPollAt = nowMillis()
        val current = snapshot()
        lastFields = fields(current)
        // 保留已有 MIB3 空态的逐字节应答，实际有媒体时才换为实时字段。
        return if (bytes.isEmpty() && current == null) IDLE_FRAME.copyOfRange(Iap2Csm.HEADER_BYTES, IDLE_FRAME.size) else body(lastFields)
    }

    /** 字段值包含各自 TLV 头，便于深比较以及组合增量应答。 */
    private fun fields(value: Snapshot?): Map<Pair<Int, Int>, ByteArray> {
        val duration = value?.durationMs?.coerceIn(0, 0xFFFFFFFFL) ?: 0
        val position = (value?.positionMs ?: 0).coerceIn(0, if (duration > 0) duration else 0xFFFFFFFFL)
        return linkedMapOf(
            (0 to 1) to Iap2Csm.string(1, text(value?.title)),
            (0 to 4) to Iap2Csm.u32(4, duration),
            (0 to 6) to Iap2Csm.string(6, text(value?.album)),
            (0 to 12) to Iap2Csm.string(12, text(value?.artist)),
            (1 to 0) to
                Iap2Csm.u8(
                    0,
                    if (value == null) {
                        0
                    } else if (value.playing) {
                        1
                    } else {
                        2
                    },
                ),
            (1 to 1) to Iap2Csm.u32(1, position),
        ).filterKeys { (group, attribute) -> attribute in requested[group].orEmpty() }
    }

    /** 每个更新只携带有变化的分组，未出现的字段在接收端保持上次值。 */
    private fun body(fields: Map<Pair<Int, Int>, ByteArray>): ByteArray =
        listOf(0, 1)
            .map { group ->
                val values = fields.filterKeys { it.first == group }.values
                if (values.isEmpty()) ByteArray(0) else Iap2Csm.group(group, *values.toTypedArray())
            }.fold(ByteArray(0)) { result, group -> result + group }

    /** 外部播放器的 NUL 或超长标题不能破坏 CSM 字符串和帧长。 */
    private fun text(value: String?): String = value.orEmpty().replace('\u0000', ' ').take(512)

    companion object {
        private val SUPPORTED = mapOf(0 to setOf(1, 4, 6, 12), 1 to setOf(0, 1))

        /** 已有真机抓包空态，订阅未指定字段且没有媒体时保持原应答。 */
        val IDLE_FRAME: ByteArray =
            (
                "404000555001002a00000005000100000800040000000000050006000005000c0000" +
                    "0500100000050012000005001a8000250001000500000000080001000000000005" +
                    "000500000500060000050007000005001000"
            ).chunked(2).map { it.toInt(16).toByte() }.toByteArray()

        /** 不完整 TLV 不能被 parseParams 的已解析前缀误当作完整订阅。 */
        private fun completeParams(bytes: ByteArray): List<Iap2Csm.Param>? {
            val params = Iap2Csm.parseParams(bytes)
            return params.takeIf { it.sumOf { param -> param.value.size + 4 } == bytes.size }
        }
    }
}
