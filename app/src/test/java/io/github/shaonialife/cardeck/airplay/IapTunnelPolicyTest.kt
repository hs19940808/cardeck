package io.github.shaonialife.cardeck.airplay

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * MIB3 双链路（iAP2 over AirPlay 控制通道）的启用判定。
 *
 * 背景（2026-10-21 smartBox-933C + 马自达悦联实车）：我们按 MIB3 时序在控制通道
 * POST /command 发 DETECT，安卓盒子（Samuncle/Angle）约 110ms 后直接 reset 整条
 * RTSP 连接，四次尝试时间戳完全重合，表现为「显示已连接后立刻断开」。
 */
class IapTunnelPolicyTest {
    @Test
    fun androidBoxMustNotGetMib3Tunnel() {
        // /info 原值：manufacturer=Samuncle model=Angle
        assertFalse(IapTunnelPolicy.needsMib3Tunnel("Samuncle Angle"))
        assertFalse(IapTunnelPolicy.needsMib3Tunnel("samuncle angle"))
    }

    @Test
    fun audiFamilyStillGetsTunnel() {
        assertTrue(IapTunnelPolicy.needsMib3Tunnel("AUDI 3260 DELPHI"))
        assertTrue(IapTunnelPolicy.needsMib3Tunnel("Harman MIB2"))
        assertTrue(IapTunnelPolicy.needsMib3Tunnel("Porsche PCM"))
    }

    @Test
    fun unknownIdentityKeepsLegacyBehaviour() {
        // /info 拿不到时不能因为这条规则把奥迪的隧道关掉（宁可多发一条命令）
        assertTrue(IapTunnelPolicy.needsMib3Tunnel("unknown"))
        assertTrue(IapTunnelPolicy.needsMib3Tunnel(""))
    }
}
