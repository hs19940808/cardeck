package io.github.shaonialife.cardeck.input

import android.view.KeyEvent

/**
 * 车机 Consumer Control HID 报告 → Android 媒体键。
 *
 * 车机/方向盘媒体键在 CarPlay 里走 HID 通路（不是 AirPlay 事件命令），设备通常是
 * `eso_playback`（奥迪 `/info` hidDevices[0]，uuid=0）。
 *
 * **报文字节是 Usage 数组的下标，不是 Usage ID**（2026-10-17 实测定谳）：
 * 奥迪描述符 `LogicalMin 0 / LogicalMax 8 + ReportSize 8 + ReportCount 1 +
 * Input(Data,Array,Abs)`，Usage 列表按序声明 9 项（0x0000、0xB0、0xB1、0xB3、0xB4、
 * 0xB5、0xB6、0xB9、0xBC）——Array 语义下 1 字节报告给出的是「第几项」。
 * 实测：按下「下一首」收到 0x05、按下「上一首」收到 0x06，分别对应 usages[5]=0xB5、
 * usages[6]=0xB6；若把字节直接当 Usage ID 会得到 0x05/0x06 → 无对应键（原先的 bug）。
 *
 * 因此这里**解析设备自带的 HID 描述符**得到 Usage 数组顺序（不写死某一台车机），
 * 再按下标取值；描述符不可用时回退「字节即 Usage ID」的直读语义。
 */
object ConsumerKeys {
    /** Consumer 页面（Generic Desktop 之外最常用的多媒体页）。 */
    private const val USAGE_PAGE_CONSUMER = 0x0C

    /**
     * 一台 HID 设备的 Consumer 键映射。
     *
     * @param usages 按下标排列的 Usage ID（解析自描述符，保持声明顺序）
     * @param isArray true=报文值是 usages 下标（标准 Array 语义）；false=值即 Usage ID
     */
    class KeyMap(
        val usages: List<Int>,
        val isArray: Boolean,
    ) {
        internal var layout: HidInputLayout? = null

        /** 短包不等于全部松开，调用方应保留上一个完整报告的状态。 */
        fun accepts(report: ByteArray): Boolean = layout?.fieldsFor(report) != null || layout == null && report.isNotEmpty()

        /** 每个 Report ID 各自上报状态，不能用另一组报告的空位释放本组按键。 */
        internal fun reportId(report: ByteArray): Int = layout?.fieldsFor(report)?.firstOrNull()?.reportId ?: 0

        /** 报告值 → Usage ID；0 或越界返回 null（0 = 无按键/释放）。 */
        fun usageFor(reportValue: Int): Int? {
            if (reportValue == 0) return null
            return if (isArray) {
                usages.getOrNull(reportValue)?.takeIf { it != 0 }
            } else {
                reportValue
            }
        }

        /**
         * HID 报文 → 本次**按下**的 Usage 集合。
         *
         * 两种报告语义必须分开，混用会一个键都点不出来：
         *  - Array（`81 00`）：字节值是 usages 下标，一次只可能有一个值；
         *  - Variable / 位图（`81 02`）：**每个 usage 占 1 bit**，report 里按位展开。
         *
         * 本田 HFT（Alpine 8A250-30A）的 StartHID 描述符正是位图：
         * `… 75 01 95 05 09 B0 09 B1 09 B5 09 B6 09 B9 81 02 …` → 1 字节报告，
         * bit0=播放(0xB0)、bit1=暂停(0xB1)、bit2=下一首(0xB5)、bit3=上一首(0xB6)、
         * bit4=停止(0xB9)；报告 0x04 = 下一首、0x00 = 全部松开。
         */
        fun usagesFor(report: ByteArray): List<Int> {
            if (report.isEmpty()) return emptyList()
            layout?.let { parsed ->
                return parsed
                    .fieldsFor(report)
                    .orEmpty()
                    .filter { it.applicationPage == USAGE_PAGE_CONSUMER }
                    .mapNotNull { field ->
                        val value = field.value(report)
                        val usage = field.usage(value)
                        usage?.code?.takeIf { usage.page == USAGE_PAGE_CONSUMER && it != 0 && (!field.variable || value != 0) }
                    }.distinct()
            }
            if (isArray) return listOfNotNull(usageFor(report[0].toInt() and 0xFF))
            val pressed = ArrayList<Int>(usages.size)
            usages.forEachIndexed { index, usage ->
                val byteIndex = index / 8
                if (byteIndex >= report.size || usage == 0) return@forEachIndexed
                val bit = (report[byteIndex].toInt() shr (index % 8)) and 0x01
                if (bit == 1) pressed += usage
            }
            return pressed
        }
    }

    /**
     * 解析 HID 描述符里的 Consumer Usage 数组。
     * 只取第一个「UsagePage=Consumer」的 Application Collection 内的 Usage 项；
     * 描述符不含 Consumer 页时返回 null（该设备不是媒体键）。
     *
     * 支持短条目编码（bSize: 0→0字节 / 1→1 / 2→2 / 3→4）。
     */
    fun parseDescriptor(descriptor: ByteArray): KeyMap? {
        val layout = HidInputLayout.parse(descriptor) ?: return null
        val fields = layout.fields.filter { it.applicationPage == USAGE_PAGE_CONSUMER }
        val usages = fields.flatMap { it.usages }.filter { it.page == USAGE_PAGE_CONSUMER }.map { it.code }
        if (usages.isEmpty()) return null
        return KeyMap(usages, fields.none { it.variable }).also { it.layout = layout }
    }

    /** Usage ID → Android 媒体键；无对应键返回 0（如 Repeat）。 */
    fun keyCodeFor(usage: Int): Int =
        when (usage) {
            0xB0 -> KeyEvent.KEYCODE_MEDIA_PLAY
            0xB1 -> KeyEvent.KEYCODE_MEDIA_PAUSE
            0xB3 -> KeyEvent.KEYCODE_MEDIA_FAST_FORWARD
            0xB4 -> KeyEvent.KEYCODE_MEDIA_REWIND
            0xB5 -> KeyEvent.KEYCODE_MEDIA_NEXT
            0xB6 -> KeyEvent.KEYCODE_MEDIA_PREVIOUS
            0xB9 -> KeyEvent.KEYCODE_MEDIA_STOP
            0xCD -> KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE
            else -> 0
        }

    /** 可读名称（日志用）。 */
    fun nameOf(usage: Int): String =
        when (usage) {
            0xB0 -> "播放"
            0xB1 -> "暂停"
            0xB3 -> "快进"
            0xB4 -> "快退"
            0xB5 -> "下一首"
            0xB6 -> "上一首"
            0xB9 -> "停止"
            0xBC -> "循环"
            0xCD -> "播放/暂停"
            else -> "未知(0x%02x)".format(usage)
        }
}
