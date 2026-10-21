package io.github.shaonialife.cardeck.bt

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.UUID

/** 回归验证主动回连盒子与提供配件端服务的车机采用不同的通话连接策略。 */
class BtProfilesTest {
    private val handsFree = UUID.fromString("0000111e-0000-1000-8000-00805f9b34fb")
    private val phoneIap2 = UUID.fromString("00000000-deca-fade-deca-deafdecacafe")
    private val accessoryIap2 = UUID.fromString("00000000-deca-fade-deca-deafdecacaff")

    /** 没有配件端服务的盒子保留既有通话连接，不套用车机主动接入策略。 */
    @Test
    fun preservesHandsFreeForBoxWithoutAccessoryService() {
        assertFalse(BtProfiles.shouldDisconnectProfiles(listOf(handsFree), sessionActive = false))
    }

    /** 手机角色的 UUID 不能被误认成车机允许主动接入的服务。 */
    @Test
    fun phoneServiceDoesNotEnableProfileDisconnection() {
        assertFalse(BtProfiles.shouldDisconnectProfiles(listOf(handsFree, phoneIap2), sessionActive = false))
    }

    /** 奥迪等明确提供配件端 iAP2 服务的设备继续沿用现有断开策略。 */
    @Test
    fun disconnectsProfilesForConfirmedAccessoryService() {
        assertTrue(BtProfiles.shouldDisconnectProfiles(listOf(handsFree, accessoryIap2), sessionActive = false))
    }

    /** SDP 缓存尚未生成时保留原有行为，避免阻断需要先释放通话通道的车机。 */
    @Test
    fun preservesFallbackForUnknownServices() {
        assertTrue(BtProfiles.shouldDisconnectProfiles(null, sessionActive = false))
        assertTrue(BtProfiles.shouldDisconnectProfiles(emptyList(), sessionActive = false))
    }

    /** 回连会话开始后，即便服务表可主动接入也不能再拆其通话连接。 */
    @Test
    fun activeSessionPreventsProfileDisconnection() {
        for (services in listOf(null, emptyList(), listOf(handsFree, accessoryIap2))) {
            assertFalse(BtProfiles.shouldDisconnectProfiles(services, sessionActive = true))
        }
    }
}
