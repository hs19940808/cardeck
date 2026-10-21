package io.github.shaonialife.cardeck.net

/** 收到的 WiFi 连接邀请；本地地址用于确认后续绑定的仍是收到邀请的网络。 */
internal data class CarPlayCtrlInvite(
    val peerAddress: String,
    val localAddress: String,
    val receiverId: String?,
) {
    /** 只接受选定设备的同名热点，避免把家庭网络上的其它接收器当成车机。 */
    fun matchesHotspot(
        targetName: String?,
        ssid: String?,
    ): Boolean = !targetName.isNullOrBlank() && targetName == ssid

    /** 网络切换期间到达的旧邀请不得借用新 WiFi 发起连接。 */
    fun matchesNetwork(
        localIpv4: String?,
        localIpv6: List<String>,
    ): Boolean {
        val addresses = listOfNotNull(localIpv4) + localIpv6
        val localMatches = addresses.any { sameHost(it, localAddress) }
        return localMatches && EndpointHosts.isSameNetwork(peerAddress, localIpv4, localIpv6)
    }

    companion object {
        /** 仅解析连接邀请；普通探测和相似路径不启动 CarPlay 会话。 */
        fun parse(
            head: String,
            peerAddress: String,
            localAddress: String,
        ): CarPlayCtrlInvite? {
            val request = head.lineSequence().firstOrNull()?.split(' ') ?: return null
            if (request.size != 3 ||
                request[0] != "GET" ||
                request[1].substringBefore('?') != "/ctrl-int/1/connect"
            ) {
                return null
            }
            val receiverId =
                head
                    .lineSequence()
                    .firstOrNull {
                        it.substringBefore(':').trim().equals("AirPlay-Receiver-Device-ID", ignoreCase = true)
                    }?.substringAfter(':')
                    ?.trim()
                    ?.takeIf { it.isNotEmpty() }
            return CarPlayCtrlInvite(peerAddress, localAddress, receiverId)
        }

        /** 同一链路地址的 scope 写法可能不同，比较时仅剥离 scope。 */
        fun sameHost(
            first: String?,
            second: String,
        ): Boolean = first != null && EndpointHosts.stripScope(first).equals(EndpointHosts.stripScope(second), ignoreCase = true)
    }
}

/** 合并重复邀请，并让取消/重新连接之前排队的后台任务失效。 */
internal class CtrlInviteGate {
    private var generation = 0L
    private var active = false
    private var pending = false

    /** 新一轮用户连接开始，清除上一轮的邀请任务。 */
    @Synchronized
    fun start() {
        generation++
        active = true
        pending = false
    }

    /** 用户断开后，已排队任务也不能继续交接端点。 */
    @Synchronized
    fun stop() {
        generation++
        active = false
        pending = false
    }

    /** 返回本轮任务凭据；同一时刻只允许一条邀请进入绑定流程。 */
    @Synchronized
    fun acquire(
        bluetoothActive: Boolean = false,
        airPlayActive: Boolean = false,
    ): Long? {
        if (!active || pending || bluetoothActive || airPlayActive) return null
        pending = true
        return generation
    }

    /** 后台等待结束后必须复查，防止旧任务在下一轮连接中复活。 */
    @Synchronized
    fun isCurrent(ticket: Long): Boolean = active && ticket == generation

    /** 旧任务收尾不能释放新一轮正在处理的邀请。 */
    @Synchronized
    fun release(ticket: Long) {
        if (ticket == generation) pending = false
    }
}
