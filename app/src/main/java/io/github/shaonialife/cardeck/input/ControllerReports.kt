package io.github.shaonialife.cardeck.input

import android.view.KeyEvent

/** 根据 Multi-axis/Joystick 描述符解析旋钮、四向拨动、确认、返回和主页。 */
internal class ControllerReports private constructor(
    private val layout: HidInputLayout,
) {
    private val wheelPositions = mutableMapOf<Pair<Int, Int>, Int>()

    /** 键是持续状态，旋转是相对增量；不能把两者都按“报告值变化”去重。 */
    data class Report(
        val id: Int,
        val keys: Set<Int>,
        val wheel: Int,
        val canceled: Boolean = false,
    )

    /** 读取本报告的字段，短包和未知 Report ID 不改变已有按下状态。 */
    fun parse(report: ByteArray): Report? {
        val fields = layout.fieldsFor(report)?.filter { it.applicationPage == 1 && it.applicationUsage in listOf(4, 5, 8, 14) }
        if (fields.isNullOrEmpty()) return null
        val keys = mutableSetOf<Int>()
        var wheel = 0
        for (field in fields) {
            val value = field.value(report)
            val usage = field.usage(value) ?: continue
            when {
                usage.page == 1 && usage.code == 0x38 -> {
                    val previous = wheelPositions.put(field.reportId to field.offset, value)
                    wheel += if (field.relative) value else previous?.let { value - it } ?: 0
                }
                usage.page == 1 && usage.code in 0x30..0x31 -> axis(field, value, usage.code)?.let(keys::add)
                usage.page == 1 && usage.code == 0x39 -> hat(value - field.minimum).forEach(keys::add)
                (!field.variable || value != 0) -> button(usage.page, usage.code)?.let(keys::add)
            }
        }
        return Report(fields.firstOrNull()?.reportId ?: 0, keys, wheel)
    }

    companion object {
        /** 只接管通用控制器 Application，Digitizer、Telephony 和媒体设备各走自己的通路。 */
        fun fromDescriptor(descriptor: ByteArray): ControllerReports? {
            val layout = HidInputLayout.parse(descriptor) ?: return null
            val controller = layout.fields.any { it.applicationPage == 1 && it.applicationUsage in listOf(4, 5, 8, 14) }
            return if (controller) ControllerReports(layout) else null
        }

        /** 有符号控制器的 ±1 也表示一次拨动，不能按模拟摇杆死区过滤掉。 */
        private fun axis(
            field: HidInputLayout.Field,
            value: Int,
            usage: Int,
        ): Int? {
            val center = if (field.minimum < 0 || field.relative) 0 else (field.minimum.toLong() + field.maximum + 1).div(2).toInt()
            val deadZone = if (field.minimum < 0 || field.relative) 0 else 1
            return when {
                value < center - deadZone -> if (usage == 0x30) KeyEvent.KEYCODE_DPAD_LEFT else KeyEvent.KEYCODE_DPAD_UP
                value > center + deadZone -> if (usage == 0x30) KeyEvent.KEYCODE_DPAD_RIGHT else KeyEvent.KEYCODE_DPAD_DOWN
                else -> null
            }
        }

        /** Consumer 设备也可能混有主页/返回键，不能交给 AudioManager 当媒体键。 */
        fun navigationKeyCode(usage: Int): Int = button(0x0C, usage) ?: 0

        /** HID Hat 的八向值从上开始顺时针排列，范围之外为松开。 */
        private fun hat(value: Int): List<Int> =
            when (value) {
                0 -> listOf(KeyEvent.KEYCODE_DPAD_UP)
                1 -> listOf(KeyEvent.KEYCODE_DPAD_UP, KeyEvent.KEYCODE_DPAD_RIGHT)
                2 -> listOf(KeyEvent.KEYCODE_DPAD_RIGHT)
                3 -> listOf(KeyEvent.KEYCODE_DPAD_DOWN, KeyEvent.KEYCODE_DPAD_RIGHT)
                4 -> listOf(KeyEvent.KEYCODE_DPAD_DOWN)
                5 -> listOf(KeyEvent.KEYCODE_DPAD_DOWN, KeyEvent.KEYCODE_DPAD_LEFT)
                6 -> listOf(KeyEvent.KEYCODE_DPAD_LEFT)
                7 -> listOf(KeyEvent.KEYCODE_DPAD_UP, KeyEvent.KEYCODE_DPAD_LEFT)
                else -> emptyList()
            }

        /** 只映射描述符明确声明的按钮，不按设备 uuid 或包长猜测。 */
        private fun button(
            page: Int,
            usage: Int,
        ): Int? =
            when {
                page == 9 && usage == 1 -> KeyEvent.KEYCODE_DPAD_CENTER
                page == 0x0C && usage == 0x41 -> KeyEvent.KEYCODE_DPAD_CENTER
                page == 0x0C && usage in listOf(0x46, 0x224) -> KeyEvent.KEYCODE_BACK
                page == 0x0C && usage == 0x223 -> KeyEvent.KEYCODE_HOME
                page == 0x0C && usage == 0x40 -> KeyEvent.KEYCODE_MENU
                page == 0x0C && usage == 0x42 -> KeyEvent.KEYCODE_DPAD_UP
                page == 0x0C && usage == 0x43 -> KeyEvent.KEYCODE_DPAD_DOWN
                page == 0x0C && usage == 0x44 -> KeyEvent.KEYCODE_DPAD_LEFT
                page == 0x0C && usage == 0x45 -> KeyEvent.KEYCODE_DPAD_RIGHT
                else -> null
            }
    }
}
