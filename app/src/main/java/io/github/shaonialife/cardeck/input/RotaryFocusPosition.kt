package io.github.shaonialife.cardeck.input

/** 旋钮按实际页面容量前后移动，首尾返回 null 交给 Dock 接续。 */
internal fun nextRotaryCell(
    counts: List<Int>,
    page: Int,
    cell: Int?,
    forward: Boolean,
): Pair<Int, Int>? {
    if (page !in counts.indices) return null
    val direction = if (forward) 1 else -1
    val current = cell ?: if (forward) -1 else counts[page]
    val next = current + direction
    if (next in 0 until counts[page]) return page to next
    val adjacent = page + direction
    if (adjacent !in counts.indices || counts[adjacent] <= 0) return null
    return adjacent to if (forward) 0 else counts[adjacent] - 1
}
