package io.github.shaonialife.cardeck.input

/** HID Input 字段布局；偏移按位计数，包含 Constant 填充和 Report ID。 */
internal class HidInputLayout(
    val fields: List<Field>,
    private val reportBits: Map<Int, Int>,
) {
    /** 一个 Variable 值或一个 Array 槽位，Usage 以 page/code 分开保存。 */
    data class Field(
        val reportId: Int,
        val offset: Int,
        val size: Int,
        val usages: List<Usage>,
        val variable: Boolean,
        val relative: Boolean,
        val minimum: Int,
        val maximum: Int,
        val applicationPage: Int,
        val applicationUsage: Int,
    ) {
        /** 从小端位流读取值；负 Logical Minimum 表示该字段需要符号扩展。 */
        fun value(report: ByteArray): Int {
            val start = offset + if (reportId == 0) 0 else 8
            var value = 0
            for (bit in 0 until size) {
                val position = start + bit
                value = value or (((report[position / 8].toInt() ushr (position % 8)) and 1) shl bit)
            }
            return if (minimum < 0 && size < 32) (value shl (32 - size)) shr (32 - size) else value
        }

        /** Variable 固定对应一个 Usage；Array 的值按 Logical Minimum 定位 Usage。 */
        fun usage(value: Int): Usage? = if (variable) usages.firstOrNull() else usages.getOrNull(value - minimum)
    }

    /** 完整 Usage，避免 Consumer 页切换后误把 Generic Desktop 轴当媒体键。 */
    data class Usage(
        val page: Int,
        val code: Int,
    )

    /** 只返回本 Report ID 的字段；不完整的报告直接拒绝，不制造松开事件。 */
    fun fieldsFor(report: ByteArray): List<Field>? {
        val id = if (reportBits.keys.any { it != 0 }) report.firstOrNull()?.toInt()?.and(0xFF) ?: return null else 0
        val bits = reportBits[id] ?: return null
        val prefix = if (id == 0) 0 else 1
        if (report.size < prefix + (bits + 7) / 8) return null
        return fields.filter { it.reportId == id }
    }

    companion object {
        /** 解析短条目、Usage 范围和 Push/Pop；只累计 Input 的位偏移。 */
        fun parse(descriptor: ByteArray): HidInputLayout? = Parser(descriptor).parse()
    }

    private data class Globals(
        var page: Int = 0,
        var minimum: Int = 0,
        var maximum: Int = 0,
        var size: Int = 0,
        var count: Int = 0,
        var reportId: Int = 0,
    )

    /** 解析状态局限于一个描述符，不缓存跨设备的 Usage Page 或位偏移。 */
    private class Parser(
        private val bytes: ByteArray,
    ) {
        private var globals = Globals()
        private val stack = ArrayDeque<Globals>()
        private val applications = ArrayDeque<Usage>()
        private val usages = mutableListOf<Usage>()
        private var rangeStart: Usage? = null
        private val offsets = mutableMapOf<Int, Int>()
        private val fields = mutableListOf<Field>()

        /** 遇到截断或不合理的位宽时拒绝整份描述符。 */
        fun parse(): HidInputLayout? {
            var index = 0
            while (index < bytes.size) {
                val prefix = bytes[index++].toInt() and 0xFF
                if (prefix == 0xFE) {
                    if (index + 2 > bytes.size) return null
                    val length = bytes[index].toInt() and 0xFF
                    index += 2 + length
                    if (index > bytes.size) return null
                    continue
                }
                val size = if (prefix and 3 == 3) 4 else prefix and 3
                if (index + size > bytes.size) return null
                var value = 0
                repeat(size) { value = value or ((bytes[index++].toInt() and 0xFF) shl (8 * it)) }
                val signed = if (size in 1..3) (value shl (32 - 8 * size)) shr (32 - 8 * size) else value
                val tag = prefix ushr 4
                when ((prefix ushr 2) and 3) {
                    0 -> if (!main(tag, value)) return null
                    1 -> if (!global(tag, value, signed)) return null
                    2 -> local(tag, value, size)
                }
            }
            return HidInputLayout(fields, offsets)
        }

        /** 全局属性可跨 Collection 延续，但 Push/Pop 必须恢复其各自取值。 */
        private fun global(
            tag: Int,
            value: Int,
            signed: Int,
        ): Boolean {
            when (tag) {
                0 -> globals.page = value
                1 -> globals.minimum = signed
                2 -> globals.maximum = if (globals.minimum < 0) signed else value
                7 -> globals.size = value
                8 -> if (value in 1..255) globals.reportId = value else return false
                9 -> globals.count = value
                10 -> stack.addLast(globals.copy())
                11 -> globals = stack.removeLastOrNull() ?: return false
            }
            return true
        }

        /** 4 字节 Usage 自带页号；Usage Minimum/Maximum 展开成声明顺序。 */
        private fun local(
            tag: Int,
            value: Int,
            size: Int,
        ) {
            val usage = Usage(if (size == 4) value ushr 16 else globals.page, value and 0xFFFF)
            when (tag) {
                0 -> usages.add(usage)
                1 -> rangeStart = usage
                2 ->
                    rangeStart?.let { start ->
                        if (start.page == usage.page && usage.code - start.code in 0..1023) {
                            (start.code..usage.code).forEach { usages.add(Usage(start.page, it)) }
                        }
                    }
            }
        }

        /** Constant 也占位；Collection 的 Usage 不得泄漏成下一组 Input 的按键。 */
        private fun main(
            tag: Int,
            flags: Int,
        ): Boolean {
            // 部分实车描述符在 Usage 之间夹有 0x00 保留项，不能清掉 Array 的零占位。
            if (tag !in 8..12) return true
            when (tag) {
                8 -> if (!input(flags)) return false
                10 ->
                    applications.addLast(
                        if (flags ==
                            1
                        ) {
                            usages.firstOrNull() ?: Usage(0, 0)
                        } else {
                            applications.lastOrNull() ?: Usage(0, 0)
                        },
                    )
                12 -> if (applications.isNotEmpty()) applications.removeLast()
            }
            usages.clear()
            rangeStart = null
            return true
        }

        /** 将一个 Input 拆成独立字段，保留 Array 的完整 Usage 表。 */
        private fun input(flags: Int): Boolean {
            val g = globals
            val app = applications.lastOrNull() ?: Usage(0, 0)
            val variable = flags and 2 != 0
            // DiPlay 的旧 Array 描述符含保留项，Report Size 被吞掉；保留原来的单字节兼容。
            val size = if (g.size == 0 && !variable && app.page == 0x0C && g.count == 1 && g.maximum in 0..255) 8 else g.size
            if (size !in 1..32 || g.count !in 1..1024) return false
            val offset = offsets[g.reportId] ?: 0
            if (offset + size * g.count > 32768) return false
            offsets[g.reportId] = offset + size * g.count
            if (flags and 1 != 0) return true
            repeat(g.count) { slot ->
                val choices = if (variable) listOfNotNull(usages.getOrNull(slot) ?: usages.lastOrNull()) else usages.toList()
                fields.add(
                    Field(
                        g.reportId,
                        offset + slot * size,
                        size,
                        choices,
                        variable,
                        flags and 4 != 0,
                        g.minimum,
                        g.maximum,
                        app.page,
                        app.code,
                    ),
                )
            }
            return true
        }
    }
}
