package io.github.shaonialife.cardeck.input

/** 兼容标准 params 包裹与旧接收端平铺的 hidSendReport，保留原始报告字节。 */
internal data class HidReportCommand(
    val uuid: String?,
    val report: ByteArray,
) {
    companion object {
        /** 缺报告时返回 null，不能将无法解析的命令当作全部松开。 */
        fun from(command: Map<*, *>): HidReportCommand? {
            val params = command["params"] as? Map<*, *>
            val uuid = (params?.get("uuid") ?: command["uuid"])?.toString()
            val report = (params?.get("hidReport") ?: command["hidReport"]) as? ByteArray ?: return null
            return HidReportCommand(uuid, report)
        }
    }
}
