package io.github.shaonialife.cardeck.input

import android.view.KeyEvent
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

/** 使用 smartBox + 马自达实车 /info 的原始 67 字节旋钮描述符。 */
class ControllerReportsTest {
    /** 组合设备的媒体 Collection 不得被旋钮解析器认成方向输入。 */
    @Test
    fun controllerAndMediaCollectionsKeepIndependentReportIds() {
        val descriptor =
            hex(
                "05 01 09 08 a1 01 85 01 15 81 25 7f 75 08 95 01 09 38 81 06 c0 " +
                    "05 0c 09 01 a1 01 85 02 15 00 25 01 75 01 95 01 09 b5 81 02 75 07 81 01 c0",
            )
        val controller = requireNotNull(ControllerReports.fromDescriptor(descriptor))
        val media = requireNotNull(ConsumerKeys.parseDescriptor(descriptor))
        assertEquals(1, controller.parse(bytes(1, 1))?.wheel)
        assertNull(controller.parse(bytes(2, 1)))
        assertEquals(listOf(0xB5), media.usagesFor(bytes(2, 1)))
    }

    /** 共享 Report ID 时，媒体菜单字段只能由媒体通路解释一次。 */
    @Test
    fun controllerIgnoresConsumerCollectionWithinTheSameReport() {
        val descriptor =
            hex(
                "05 01 09 08 a1 01 15 81 25 7f 75 08 95 01 09 38 81 06 c0 " +
                    "05 0c 09 01 a1 01 15 00 25 01 75 01 95 01 0a 23 02 81 02 75 07 81 01 c0",
            )
        val controller = requireNotNull(ControllerReports.fromDescriptor(descriptor))
        val media = requireNotNull(ConsumerKeys.parseDescriptor(descriptor))
        assertEquals(emptySet<Int>(), controller.parse(bytes(1, 1))?.keys)
        assertEquals(1, controller.parse(bytes(1, 1))?.wheel)
        assertEquals(listOf(0x223), media.usagesFor(bytes(1, 1)))
    }

    private val knob =
        hex(
            "05 01 09 08 a1 01 05 09 09 01 15 00 25 01 75 01 95 01 81 02 " +
                "05 0c 0a 24 02 95 01 81 02 95 06 81 01 05 01 09 01 a1 00 " +
                "09 30 09 31 15 81 25 7f 75 08 95 02 81 02 c0 09 38 15 81 " +
                "25 7f 75 08 95 01 81 06 c0",
        )

    @Test
    fun mazdaSelectAndBackAreIndependentBits() {
        val parser = parser()
        assertEquals(setOf(KeyEvent.KEYCODE_DPAD_CENTER), parser.parse(bytes(1, 0, 0, 0))?.keys)
        assertEquals(setOf(KeyEvent.KEYCODE_BACK), parser.parse(bytes(2, 0, 0, 0))?.keys)
        assertEquals(setOf(KeyEvent.KEYCODE_BACK, KeyEvent.KEYCODE_DPAD_CENTER), parser.parse(bytes(3, 0, 0, 0))?.keys)
        assertEquals(emptySet<Int>(), parser.parse(bytes(0, 0, 0, 0))?.keys)
    }

    @Test
    fun signedAxesAcceptBothSingleStepsAndFullDeflection() {
        val parser = parser()
        assertEquals(setOf(KeyEvent.KEYCODE_DPAD_RIGHT), parser.parse(bytes(0, 1, 0, 0))?.keys)
        assertEquals(setOf(KeyEvent.KEYCODE_DPAD_LEFT), parser.parse(bytes(0, 255, 0, 0))?.keys)
        assertEquals(setOf(KeyEvent.KEYCODE_DPAD_UP), parser.parse(bytes(0, 0, 129, 0))?.keys)
        assertEquals(setOf(KeyEvent.KEYCODE_DPAD_DOWN), parser.parse(bytes(0, 0, 127, 0))?.keys)
    }

    @Test
    fun repeatedRelativeWheelValuesAreNewDetents() {
        val parser = parser()
        assertEquals(1, parser.parse(bytes(0, 0, 0, 1))?.wheel)
        assertEquals(1, parser.parse(bytes(0, 0, 0, 1))?.wheel)
        assertEquals(-1, parser.parse(bytes(0, 0, 0, 255))?.wheel)
        assertEquals(4, parser.parse(bytes(0, 0, 0, 4))?.wheel)
    }

    @Test
    fun absoluteWheelPositionsDoNotRepeatWithoutMovement() {
        val absolute = knob.copyOf().apply { this[lastIndex - 1] = 2 }
        val parser = requireNotNull(ControllerReports.fromDescriptor(absolute))
        assertEquals(0, parser.parse(bytes(0, 0, 0, 0))?.wheel)
        assertEquals(3, parser.parse(bytes(0, 0, 0, 3))?.wheel)
        assertEquals(0, parser.parse(bytes(0, 0, 0, 3))?.wheel)
        assertEquals(-2, parser.parse(bytes(0, 0, 0, 1))?.wheel)
    }

    @Test
    fun truncatedReportsCannotReleaseAnAlreadyPressedButton() {
        assertNull(parser().parse(bytes(1, 0, 0)))
    }

    @Test
    fun reportIdIsNotMistakenForSelectOrWheel() {
        val parser =
            requireNotNull(
                ControllerReports.fromDescriptor(
                    hex(
                        "05 01 09 08 a1 01 85 07 05 09 09 01 15 00 25 01 75 01 95 01 " +
                            "81 02 95 07 81 01 05 01 09 38 15 81 25 7f 75 08 95 01 81 06 c0",
                    ),
                ),
            )
        assertEquals(setOf(KeyEvent.KEYCODE_DPAD_CENTER), parser.parse(bytes(7, 1, 1))?.keys)
        assertEquals(1, parser.parse(bytes(7, 1, 1))?.wheel)
        assertNull(parser.parse(bytes(6, 1, 1)))
        assertNull(parser.parse(bytes(7, 1)))
    }

    @Test
    fun hatSwitchIncludesDiagonalsAndNeutral() {
        val parser =
            requireNotNull(
                ControllerReports.fromDescriptor(
                    hex(
                        "05 01 09 08 a1 01 09 39 15 00 25 07 75 04 95 01 81 42 75 04 81 01 c0",
                    ),
                ),
            )
        assertEquals(setOf(KeyEvent.KEYCODE_DPAD_UP, KeyEvent.KEYCODE_DPAD_RIGHT), parser.parse(bytes(1))?.keys)
        assertEquals(emptySet<Int>(), parser.parse(bytes(8))?.keys)
    }

    @Test
    fun mediaAndTelephonyDevicesAreNotControllers() {
        assertNull(ControllerReports.fromDescriptor(hex("05 0b 09 01 a1 01 15 00 25 01 75 01 95 02 09 20 09 26 81 02 c0")))
        assertNull(ControllerReports.fromDescriptor(hex("05 0c 09 01 a1 01 15 00 25 01 09 b5 75 01 95 01 81 02 c0")))
        assertNull(ConsumerKeys.parseDescriptor(knob))
    }

    @Test
    fun malformedDescriptorIsRejected() {
        assertNull(ControllerReports.fromDescriptor(knob.copyOfRange(0, 17)))
        assertNotNull(ControllerReports.fromDescriptor(knob))
    }

    /** 每个测试持有独立的绝对旋钮位置状态。 */
    private fun parser(): ControllerReports = requireNotNull(ControllerReports.fromDescriptor(knob))

    /** 日志报告按字节给出，避免将负数文字转换误当作解码测试。 */
    private fun bytes(vararg values: Int): ByteArray = values.map { it.toByte() }.toByteArray()

    /** 描述符按原始十六进制录入，便于逐字节对照实车导出文件。 */
    private fun hex(text: String): ByteArray =
        text
            .split(' ')
            .filter { it.isNotBlank() }
            .map { it.toInt(16).toByte() }
            .toByteArray()
}
