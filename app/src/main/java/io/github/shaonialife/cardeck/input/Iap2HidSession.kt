package io.github.shaonialife.cardeck.input

import io.github.shaonialife.cardeck.iap2.Iap2Csm
import io.github.shaonialife.cardeck.iap2.Iap2MessageIds

/** 蓝牙与 Wi-Fi 隧道共用的 iAP2 HID 生命周期，每个组件独立保存描述符和报告状态。 */
internal class Iap2HidSession(
    private val log: (String) -> Unit,
    private val nowMillis: () -> Long = { System.nanoTime() / 1_000_000L },
) {
    private class Device(
        val descriptor: ByteArray?,
    ) {
        val controller = descriptor?.let(ControllerReports::fromDescriptor)
        val media = descriptor?.let(ConsumerKeys::parseDescriptor)
    }

    private val devices = mutableMapOf<Int, Device>()
    private val navigation = mutableMapOf<Int, Set<Int>>()
    private val mediaReports = mutableMapOf<Int, Set<Int>>()
    private val mediaFiredAt = mutableMapOf<Int, Long>()
    private var hasStarted = false
    private var closed = false
    private var loggedReports = 0

    var onControllerInput: ((ControllerReports.Report) -> Unit)? = null
    var onMediaKey: ((Int) -> Unit)? = null

    /** HID 必须先于消息日志的限流处理，连续同值 Wheel 也是独立刻度。 */
    @Synchronized
    fun handle(frame: Iap2Csm.Frame): Boolean {
        if (frame.messageId !in listOf(Iap2MessageIds.START_HID, Iap2MessageIds.ACCESSORY_HID_REPORT, Iap2MessageIds.STOP_HID)) return false
        if (closed) return true
        val params = Iap2Csm.parseParams(frame.body)
        when (frame.messageId) {
            Iap2MessageIds.START_HID -> start(params)
            Iap2MessageIds.ACCESSORY_HID_REPORT -> report(params)
            Iap2MessageIds.STOP_HID -> stop(params)
            else -> return false
        }
        return true
    }

    /** 通道退出时补发空状态，仅清除本通道的来源，不释放其他通道仍按住的键。 */
    @Synchronized
    fun close() {
        if (closed) return
        closed = true
        navigation.keys.toList().forEach { onControllerInput?.invoke(ControllerReports.Report(it, emptySet(), 0, canceled = true)) }
        navigation.clear()
        mediaReports.clear()
        mediaFiredAt.clear()
        devices.clear()
        hasStarted = true
    }

    /** 标准描述符在参数 4；兼容旧接收端参数 2，不按字段长度猜组件名。 */
    private fun start(params: List<Iap2Csm.Param>) {
        val id = componentId(params) ?: if (params.none { it.id == 0 }) 0 else return
        release(id)
        val descriptor =
            params.firstOrNull { it.id == 4 }?.value
                ?: params.firstOrNull { it.id == 2 && it.value.size > 8 }?.value
        devices[id] = Device(descriptor)
        hasStarted = true
        log("IAP-HID: StartHID component=$id descriptor=${descriptor?.size ?: 0}B")
    }

    /** 显式未知组件不得借用其他设备；缺编号只在组件唯一时兼容。 */
    private fun report(params: List<Iap2Csm.Param>) {
        val bytes = reportFromParams(params) ?: return
        val hasData = params.any { it.id == 1 }
        val id =
            if (hasData && params.any { it.id == 0 }) {
                componentId(params) ?: return
            } else {
                if (devices.size > 1) return
                devices.keys.firstOrNull() ?: 0
            }
        val device =
            devices[id] ?: run {
                if (hasStarted) {
                    if (loggedReports++ < 8) log("IAP-HID: 未注册组件 component=$id，报告忽略")
                    return
                }
                Device(null)
            }
        if (loggedReports++ < 8) {
            log("IAP-HID: report component=$id bytes=${bytes.joinToString(" ") { "%02x".format(it) }}")
        }
        device.controller?.parse(bytes)?.let {
            emit(it.copy(id = sourceId(id, it.id)))
        }
        val media = device.media
        val usages =
            when {
                media != null && media.accepts(bytes) -> media.usagesFor(bytes)
                device.descriptor == null && bytes.size == 1 -> listOf(bytes[0].toInt() and 0xFF).filter { it != 0 }
                else -> return
            }
        val source = sourceId(id, media?.reportId(bytes) ?: 0)
        emit(
            ControllerReports.Report(
                -source - 1,
                usages
                    .mapNotNull {
                        ControllerReports.navigationKeyCode(it).takeIf { key ->
                            key != 0
                        }
                    }.toSet(),
                0,
            ),
        )
        updateMedia(source, usages.filter { ControllerReports.navigationKeyCode(it) == 0 }.toSet())
    }

    /** StopHID 只结束指定组件；之后的迟到报告须等新的 StartHID 才能恢复。 */
    private fun stop(params: List<Iap2Csm.Param>) {
        val id = componentId(params) ?: if (params.isEmpty() && devices.size == 1) devices.keys.first() else return
        release(id)
        devices.remove(id)
        hasStarted = true
        log("IAP-HID: StopHID component=$id")
    }

    /** 记录非空快照，停止/重注册时无需猜测哪组报告还按着确认或返回。 */
    private fun emit(report: ControllerReports.Report) {
        val previous = navigation[report.id].orEmpty()
        if (report.keys.isEmpty()) navigation.remove(report.id) else navigation[report.id] = report.keys
        if (previous != report.keys || report.wheel != 0) {
            log("IAP-HID: 控制器 source=${report.id} keys=${report.keys} wheel=${report.wheel}")
        }
        onControllerInput?.invoke(report)
    }

    /** 组件号左移 8 位并附加 Report ID；负数另用于 Consumer 导航字段。 */
    private fun sourceId(
        component: Int,
        reportId: Int,
    ): Int = (component shl 8) or reportId

    /** 重注册和停止组件都要释放其旧按键，其他组件的持续状态仍保留。 */
    private fun release(component: Int) {
        navigation.keys.filter { (if (it < 0) -it - 1 else it) ushr 8 == component }.forEach { source ->
            navigation.remove(source)
            onControllerInput?.invoke(ControllerReports.Report(source, emptySet(), 0, canceled = true))
        }
        mediaReports.keys.filter { it ushr 8 == component }.forEach(mediaReports::remove)
        mediaFiredAt.keys.retainAll(mediaReports.values.flatten().toSet())
    }

    /** 按媒体 Usage 合并来源；仅首次按下或持续按住 1.5 秒后触发。 */
    private fun updateMedia(
        source: Int,
        usages: Set<Int>,
    ) {
        if (usages.isEmpty()) mediaReports.remove(source) else mediaReports[source] = usages
        val pressed = mediaReports.values.flatten().toSet()
        mediaFiredAt.keys.retainAll(pressed)
        val now = nowMillis()
        for (usage in pressed) {
            val previous = mediaFiredAt[usage]
            if (previous != null && now - previous < 1_500L) continue
            mediaFiredAt[usage] = now
            val key = ConsumerKeys.keyCodeFor(usage)
            if (key != 0) {
                log("IAP-HID: 媒体键 ${ConsumerKeys.nameOf(usage)} → keyCode=$key")
                onMediaKey?.invoke(key)
            }
        }
    }

    companion object {
        /** 参数 0 是两字节大端组件编号，不是固定报告类型。 */
        fun componentId(params: List<Iap2Csm.Param>): Int? {
            val value = params.firstOrNull { it.id == 0 }?.value ?: return null
            if (value.size != 2) return null
            return ((value[0].toInt() and 0xFF) shl 8) or (value[1].toInt() and 0xFF)
        }

        /** 显式空 data 不能回退元数据；仅保留旧接收端单字段短报文的兼容。 */
        fun reportFromParams(params: List<Iap2Csm.Param>): ByteArray? {
            val data = params.firstOrNull { it.id == 1 }
            if (data != null) return data.value.takeIf { it.isNotEmpty() }
            val single = params.singleOrNull() ?: return null
            if (single.id == 0 && single.value.size == 2) return null
            return single.value.takeIf { it.size in 1..8 }
        }
    }
}
