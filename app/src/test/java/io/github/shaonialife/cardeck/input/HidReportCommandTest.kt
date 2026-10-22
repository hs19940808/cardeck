package io.github.shaonialife.cardeck.input

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** 标准事件命令的 params 与旧平铺字段都必须能进入 HID 解析通路。 */
class HidReportCommandTest {
    @Test
    fun nestedAndFlatReportsKeepTheSameBytesAndUuid() {
        val report = byteArrayOf(1, 0, 0, -1)
        val fields = mapOf("uuid" to "0", "hidReport" to report)
        val nested = requireNotNull(HidReportCommand.from(mapOf("type" to "hidSendReport", "params" to fields)))
        val flat = requireNotNull(HidReportCommand.from(fields + ("type" to "hidSendReport")))
        assertEquals("0", nested.uuid)
        assertEquals(flat.uuid, nested.uuid)
        assertArrayEquals(report, nested.report)
        assertArrayEquals(flat.report, nested.report)
    }

    @Test
    fun missingAndInvalidReportsAreNotConvertedIntoReleases() {
        assertNull(HidReportCommand.from(mapOf("params" to mapOf("uuid" to "0"))))
        assertNull(HidReportCommand.from(mapOf("hidReport" to "00000000")))
    }
}
