package io.github.shaonialife.cardeck.input

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** 仪表盘占真实第 0 页，应用页与焦点标记必须使用同一套页码。 */
class RotaryFocusPositionTest {
    private val pages = listOf(3, 12, 5)

    @Test
    fun firstTurnSelectsFirstOrLastControlOnCurrentPage() {
        assertEquals(1 to 0, nextRotaryCell(pages, 1, null, true))
        assertEquals(1 to 11, nextRotaryCell(pages, 1, null, false))
    }

    @Test
    fun forwardAndBackwardCrossRealPageBoundaries() {
        assertEquals(2 to 0, nextRotaryCell(pages, 1, 11, true))
        assertEquals(0 to 2, nextRotaryCell(pages, 1, 0, false))
        assertEquals(1 to 11, nextRotaryCell(pages, 2, 0, false))
    }

    @Test
    fun endsYieldToDockInsteadOfLosingFocus() {
        assertNull(nextRotaryCell(pages, 0, 0, false))
        assertNull(nextRotaryCell(pages, 2, 4, true))
    }

    @Test
    fun rapidSeventeenDetentsTraverseEveryAppIncludingThePartialLastPage() {
        var selected: Pair<Int, Int>? = null
        repeat(17) {
            selected = nextRotaryCell(pages, selected?.first ?: 1, selected?.second, true)
        }
        assertEquals(2 to 4, selected)
    }
}
