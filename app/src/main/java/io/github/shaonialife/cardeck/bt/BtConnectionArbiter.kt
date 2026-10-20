package io.github.shaonialife.cardeck.bt

/**
 * 在 socket.connect() 与盒子回连之间仲裁，不能只在客户端循环开头检查一次布尔值。
 * 回连优先取消尚未完成的出站尝试；已经建立的会话不被另一方向抢占。
 * 本进程见过主动回连的设备，断链后继续等它回连，避免每轮再断 HFP / 探测 …caff。
 */
internal class BtConnectionArbiter {
    class Outbound internal constructor(
        internal val cancel: () -> Unit,
    ) {
        internal var established = false
    }

    class Inbound internal constructor()

    private var outbound: Outbound? = null
    private var inbound: Inbound? = null
    private val inboundPeers = HashSet<String>()

    val inboundActive: Boolean get() = synchronized(this) { inbound != null }

    @Synchronized
    fun prefersInbound(address: String): Boolean = address.uppercase() in inboundPeers

    @Synchronized
    fun beginOutbound(
        address: String,
        cancel: () -> Unit,
    ): Outbound? {
        if (inbound != null || outbound != null || prefersInbound(address)) return null
        return Outbound(cancel).also { outbound = it }
    }

    @Synchronized
    fun isCurrent(attempt: Outbound): Boolean = outbound === attempt && inbound == null

    @Synchronized
    fun establish(attempt: Outbound): Boolean {
        if (!isCurrent(attempt)) return false
        attempt.established = true
        return true
    }

    @Synchronized
    fun endOutbound(attempt: Outbound) {
        if (outbound === attempt) outbound = null
    }

    fun beginInbound(address: String): Inbound? {
        val interrupted: Outbound?
        val accepted: Inbound
        synchronized(this) {
            if (inbound != null || outbound?.established == true) return null
            accepted = Inbound()
            inbound = accepted
            inboundPeers += address.uppercase()
            interrupted = outbound
            outbound = null
        }
        // 先占住入站名额，再在锁外关正在 connect 的 socket，避免关闭回调导致死锁。
        interrupted?.cancel?.invoke()
        return accepted
    }

    @Synchronized
    fun endInbound(session: Inbound) {
        if (inbound === session) inbound = null
    }
}
