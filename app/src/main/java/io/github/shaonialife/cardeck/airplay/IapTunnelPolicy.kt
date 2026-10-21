package io.github.shaonialife.cardeck.airplay

/**
 * 「iAP2 over AirPlay 控制通道」（MIB3 双链路）的启用判定。
 *
 * 2026-10-21 18:13 实车实测（smartBox-933C 插马自达悦联 8.8"）：屏幕流 SETUP 成功后我们
 * 按 MIB3 时序在**控制通道** POST /command 发 DETECT，接收端（/info 自称
 * `Samuncle / Angle / OSInfo=Android8 / firmwareRevision=WifiCP0.1` 的安卓盒子）
 * **约 100~110ms 后 reset 整条 RTSP 连接**，四次尝试逐次重合：
 *
 * ```
 * 18:13:58.704 MIB3 detect 已发 → 18:13:58.811 EVENT 读取结束 - Connection reset
 * 18:14:04.725 MIB3 detect 已发 → 18:14:04.843 同上
 * 18:15:48.812 MIB3 detect 已发 → 18:15:48.922 同上（这一次数据端口已连通，37ms 后被拆）
 * 18:15:54.904 MIB3 detect 已发 → 18:15:55.015 同上
 * ```
 *
 * 用户看到的现象是「一瞬间显示已连接、随后又断开」。Audi MIB3 需要这条第二链路，
 * 但**非 MIB3 接收端会把它当成非法命令直接拆链**，因此必须按接收端身份启用。
 *
 * 身份未知（/info 缺失、拿不到 manufacturer/model）时保持旧行为（启用），
 * 避免把奥迪这类真要隧道的车机误伤成「没有触摸」。
 */
internal object IapTunnelPolicy {
    private val MIB3_FAMILY = listOf("audi", "delphi", "mib", "harman", "porsche")

    /**
     * @param receiverIdentity `/info` 的 manufacturer + model（见 AirPlayClient.receiverIdentity）
     * @return true = 需要 MIB3 双链路（可以发控制通道 DETECT）
     */
    fun needsMib3Tunnel(receiverIdentity: String): Boolean {
        val text = receiverIdentity.trim().lowercase()
        if (text.isEmpty() || text == "unknown") return true
        return MIB3_FAMILY.any { text.contains(it) }
    }
}
