package io.github.shaonialife.cardeck.input

/** 按设备/Report ID 保存持续按下状态，避免轮询报告反复触发确认或切歌。 */
internal class HidKeyState {
    private val held = mutableMapOf<String, Set<Int>>()

    /** 会话退役时丢弃所有来源，下一会话的首次按下必须重新生成边沿。 */
    fun clear() = held.clear()

    /** 按所有来源的并集合并边沿；另一来源仍按住时不能提前松开同一个键。 */
    fun update(
        device: String,
        keys: Set<Int>,
    ): List<Pair<Int, Boolean>> {
        val previous = held.values.flatten().toSet()
        if (keys.isEmpty()) held.remove(device) else held[device] = keys
        val current = held.values.flatten().toSet()
        return (previous - current).map { it to false } + (current - previous).map { it to true }
    }
}
