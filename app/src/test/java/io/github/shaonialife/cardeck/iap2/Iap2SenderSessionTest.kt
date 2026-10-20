package io.github.shaonialife.cardeck.iap2

import io.github.shaonialife.cardeck.transport.Iap2LinkChannel
import io.github.shaonialife.cardeck.transport.InMemoryStreamPair
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.IOException
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

/**
 * 发送端会话对 mock 配件端的端到端回环测试：
 * 内存双工流两端各跑一个链路通道，mock 按 DiPlay/xcertplay 的配件侧时序应答，
 * 验证 Phase 1 全序列（识别→MFi 跳过→HID→WiFi 配置→CarPlay 启动→通知）。
 */
class Iap2SenderSessionTest {
    @Test(timeout = 15_000)
    fun unknownRsaBoxUses20BytesWithFragmentedCertificate() {
        withAccessory(
            certificateBytes = MfiCertificateHintTest.certificate("rsa").encoded,
            expectedChallengeSize = 20,
        ) { session, _ ->
            assertEquals("MockAP", session.run(10_000).ssid)
        }
    }

    @Test(timeout = 15_000)
    fun ecReceiverUses32BytesEvenWithLegacyName() {
        withAccessory(
            receiverName = "Audi-test-${UUID.randomUUID()}",
            certificateBytes = MfiCertificateHintTest.certificate("ec").encoded,
            expectedChallengeSize = 32,
        ) { session, _ ->
            assertEquals("MockAP", session.run(10_000).ssid)
        }
    }

    @Test(timeout = 15_000)
    fun known32ByteSuccessOverridesRsaCertificateHint() {
        val receiverName = "smartBox-test-${UUID.randomUUID()}"
        MfiLengthMemory.recordSuccess(null, "$receiverName MU-1 Mock Manufacturer", 32)
        withAccessory(
            receiverName = receiverName,
            certificateBytes = MfiCertificateHintTest.certificate("rsa").encoded,
            expectedChallengeSize = 32,
        ) { session, _ ->
            assertEquals("MockAP", session.run(10_000).ssid)
        }
    }

    @Test(timeout = 15_000)
    fun unknownCertificateStillSupports32BytesAnd118ByteFragments() {
        withAccessory(expectedChallengeSize = 32) { session, _ ->
            assertEquals("MockAP", session.run(10_000).ssid)
        }
    }

    @Test(timeout = 15_000)
    fun disconnectDuringCertificateIsNotReportedAsTimeout() {
        withAccessory(truncateCertificate = true) { session, logs ->
            val error = runCatching { session.run(10_000) }.exceptionOrNull()
            assertTrue("实际结果=$error", error is IOException)
            assertTrue(error!!.message!!.contains("0xaa01"))
            assertTrue(error.message!!.contains("链路断开"))
            assertFalse(error.message!!.contains("超时"))
            assertFalse(logs.any { it.contains("tx=0xaa02") })
        }
    }

    @Test(timeout = 20_000)
    fun disconnectAfter32BytesRetries20BytesOnNewLink() {
        val receiverName = "smartBox-test-${UUID.randomUUID()}"
        withAccessory(receiverName = receiverName, expectedChallengeSize = 32, closeOnChallenge = true) { session, logs ->
            val error = runCatching { session.run(10_000) }.exceptionOrNull()
            assertTrue("实际结果=$error", error is IOException)
            assertTrue(error!!.message!!.contains("32B"))
            assertFalse("断链后不能再报发送 0xAA05 失败", error.message!!.contains("0xaa05"))
            assertTrue(logs.any { it.contains("下次重连优先 20B") })
            assertFalse(logs.any { it.contains("tx=0xaa05") })
        }
        withAccessory(receiverName = receiverName, expectedChallengeSize = 20) { session, _ ->
            assertEquals("MockAP", session.run(10_000).ssid)
        }
    }

    /** 复现盒子 118B 控制分片，并把配件线程异常传回 JUnit，避免后台断言失败被忽略。 */
    private fun withAccessory(
        receiverName: String = "smartBox-test-${UUID.randomUUID()}",
        certificateBytes: ByteArray = ByteArray(908) { it.toByte() },
        expectedChallengeSize: Int? = null,
        closeOnChallenge: Boolean = false,
        truncateCertificate: Boolean = false,
        test: (Iap2SenderSession, List<String>) -> Unit,
    ) {
        val pair = InMemoryStreamPair()
        val phoneChannel = Iap2LinkChannel(pair.sideA, name = "phone-regression")
        val accessoryChannel = Iap2LinkChannel(pair.sideB, name = "box-regression")
        val failure = AtomicReference<Throwable?>()
        val logs = ArrayList<String>()
        val accessory =
            MockAccessory(
                accessoryChannel,
                receiverName = receiverName,
                certificateBytes = certificateBytes,
                expectedChallengeSize = expectedChallengeSize,
                closeOnChallenge = closeOnChallenge,
                truncateCertificate = truncateCertificate,
                chunkSize = 118,
            )
        val worker =
            Thread(
                {
                    try {
                        accessory.run()
                    } catch (error: Throwable) {
                        failure.set(error)
                        accessoryChannel.close()
                    }
                },
                "box-regression",
            ).apply {
                isDaemon = true
                start()
            }
        try {
            test(Iap2SenderSession(phoneChannel, log = { logs += it }), logs)
            worker.join(2_000)
            assertFalse("配件脚本未完成", worker.isAlive)
            failure.get()?.let { throw AssertionError("配件脚本失败", it) }
        } finally {
            phoneChannel.close()
            accessoryChannel.close()
            worker.join(2_000)
        }
    }

    @Test
    fun fullBringUpAgainstMockAccessory() {
        val pair = InMemoryStreamPair()
        val phoneChannel = Iap2LinkChannel(pair.sideA, name = "phone")
        val accessoryChannel = Iap2LinkChannel(pair.sideB, name = "accessory")
        val accessory = MockAccessory(accessoryChannel)

        val accessoryThread = Thread({ accessory.run() }, "mock-accessory")
        accessoryThread.isDaemon = true
        accessoryThread.start()

        val endpoint =
            Iap2SenderSession(
                phoneChannel,
                log = { line -> println("PHONE $line") },
                bluetoothMac = "34:86:DA:86:BB:96",
                deviceName = "test-device",
            ).run(timeoutMillis = 10_000L)

        assertEquals("MockAP", endpoint.ssid)
        assertEquals("secret-passphrase", endpoint.passphrase)
        assertEquals(36, endpoint.channel)
        assertEquals(2, endpoint.securityType)
        assertEquals(49152, endpoint.airPlayPort)
        assertEquals("mock-device-id", endpoint.deviceIdentifier)
        assertNotNull(endpoint.publicKey)
        assertEquals(listOf("192.168.49.1", "fd00::1"), endpoint.ipAddresses)

        phoneChannel.close()
        accessoryChannel.close()
    }

    /**
     * 奥迪 MMI 9310 形态：识别帧只声明发 0x5703/0xea03（不发 0x4301），Phase 1
     * 因此不等待；车机在保活期间（Phase 1 之后）才补发 0x4301。
     * 旧实现把这条消息当 keep-alive 只记长度，配对永远不会重启——本用例锁住
     * 「晚到 0x4301 必须解析成端点并回调」这一行为。
     */
    @Test
    fun lateStartSessionAfterPhaseOneTriggersEndpointUpdate() {
        val pair = InMemoryStreamPair()
        val phoneChannel = Iap2LinkChannel(pair.sideA, name = "phone")
        val accessoryChannel = Iap2LinkChannel(pair.sideB, name = "accessory")
        val accessory = MockAccessory(accessoryChannel, audiDeclarations = true, lateStartSession = true)

        val accessoryThread = Thread({ accessory.run() }, "mock-accessory")
        accessoryThread.isDaemon = true
        accessoryThread.start()

        val updates = AtomicReference<Iap2SenderSession.CarPlayEndpoint?>()
        val updateLatch = CountDownLatch(1)
        val session =
            Iap2SenderSession(
                phoneChannel,
                log = { line -> println("PHONE $line") },
                bluetoothMac = "34:86:DA:86:BB:96",
                deviceName = "test-device",
                onEndpointUpdate = {
                    updates.set(it)
                    updateLatch.countDown()
                },
            )

        val phaseOne = session.run(timeoutMillis = 10_000L)
        // 车机声明不发 0x4301：Phase 1 必须立即返回空端点，而不是白等 3s 窗口
        assertTrue(phaseOne.ipAddresses.isEmpty())

        val pump = Thread({ session.pumpIncomingForever() }, "phone-pump")
        pump.isDaemon = true
        pump.start()

        assertTrue("晚到 0x4301 未触发端点回调", updateLatch.await(5, TimeUnit.SECONDS))
        val late = updates.get()
        assertNotNull(late)
        assertEquals("MockAP", late!!.ssid)
        assertEquals(49152, late.airPlayPort)
        assertEquals(listOf("192.168.49.1", "fd00::1"), late.ipAddresses)
        assertEquals("mock-device-id", late.deviceIdentifier)

        phoneChannel.close()
        accessoryChannel.close()
        pump.join(2_000)
    }

    /**
     * Audi MMI 3315 形态：0x4301 的地址字段按定长 NUL 填充（IPv6 后 22 个、IPv4 后 5 个）。
     * 旧实现只去掉**一个**尾部 NUL，得到 `"10.174.189.1\0\0\0\0\0"`——它 contains('.')=true、
     * contains(':')=false，能骗过所有「是不是 IPv4」的判断，但 Android 的 getaddrinfo 解不了，
     * 表现为 `Unable to resolve host "10.174.189.1"`（2026-10-18 实测：配对成功后视频端口
     * 与 keepAlive 全挂、会话 10 秒被拆）。
     */
    @Test
    fun nulPaddedAddressesAreStrippedCompletely() {
        val pair = InMemoryStreamPair()
        val phoneChannel = Iap2LinkChannel(pair.sideA, name = "phone")
        val accessoryChannel = Iap2LinkChannel(pair.sideB, name = "accessory")
        val accessory = MockAccessory(accessoryChannel, padAddresses = true)

        val accessoryThread = Thread({ accessory.run() }, "mock-accessory")
        accessoryThread.isDaemon = true
        accessoryThread.start()

        val endpoint =
            Iap2SenderSession(
                phoneChannel,
                log = { line -> println("PHONE $line") },
                bluetoothMac = "34:86:DA:86:BB:96",
                deviceName = "test-device",
            ).run(timeoutMillis = 10_000L)

        // 每个地址都必须干净：没有 NUL、没有空白
        endpoint.ipAddresses.forEach { address ->
            check(address.none { it == '\u0000' }) { "地址含 NUL：${address.toByteArray().toList()}" }
            check(address.trim() == address) { "地址含首尾空白：'$address'" }
        }
        assertEquals(listOf("192.168.49.1", "fd00::1"), endpoint.ipAddresses)
        // 端口解析不受填充影响（同一帧里的 u32 参数）
        assertEquals(49152, endpoint.airPlayPort)

        phoneChannel.close()
        accessoryChannel.close()
    }

    /** 按 DiPlay Iap2WirelessControlClient 的时序脚本化应答。 */
    private class MockAccessory(
        private val channel: Iap2LinkChannel,
        /** 奥迪形态：识别帧声明只发 0x5703/0xea03（不发 StartHID/0x4301）。 */
        private val audiDeclarations: Boolean = false,
        /** Phase 1 结束后再补发 0x4301（车机窗口晚开的真实时序）。 */
        private val lateStartSession: Boolean = false,
        /**
         * 地址字段按真实车机的定长填充（Audi MMI 3315 实测：IPv6 后跟 22 个 NUL、
         * IPv4 后跟 5 个）。用于锁定「必须去掉全部尾部 NUL」这一行为。
         */
        private val padAddresses: Boolean = false,
        private val receiverName: String = "MockCarPlay",
        private val certificateBytes: ByteArray = ByteArray(64) { it.toByte() },
        private val expectedChallengeSize: Int? = null,
        private val closeOnChallenge: Boolean = false,
        private val truncateCertificate: Boolean = false,
        private val chunkSize: Int = Int.MAX_VALUE,
    ) {
        private val parser = Iap2Csm.Parser()
        private val pending = ArrayDeque<Iap2Csm.Frame>()

        fun run() {
            check(channel.awaitReady(10_000)) { "mock link not ready" }

            expect(Iap2MessageIds.START_IDENTIFICATION)
            send(
                Iap2MessageIds.IDENTIFICATION_INFORMATION,
                Iap2Csm.string(0, receiverName),
                Iap2Csm.string(1, "MU-1"),
                Iap2Csm.string(2, "Mock Manufacturer"),
                if (audiDeclarations) {
                    Iap2Csm.u16List(6, intArrayOf(0x5703, 0xea03))
                } else {
                    ByteArray(0)
                },
            )
            expect(Iap2MessageIds.IDENTIFICATION_ACCEPTED)

            expect(Iap2MessageIds.MFI_REQUEST_CERTIFICATE)
            if (truncateCertificate) {
                val frame = Iap2Csm.encode(Iap2MessageIds.MFI_CERTIFICATE, Iap2Csm.param(0, certificateBytes))
                check(channel.sendControl(frame.copyOf(118)))
                Thread.sleep(30)
                channel.close()
                return
            }
            send(Iap2MessageIds.MFI_CERTIFICATE, Iap2Csm.param(0, certificateBytes))
            val challenge = expect(Iap2MessageIds.MFI_REQUEST_CHALLENGE)
            expectedChallengeSize?.let {
                val actual = Iap2Csm.parseParams(challenge.body).single { param -> param.id == 0 }.value.size
                assertEquals("挑战长度必须匹配接收端", it, actual)
            }
            if (closeOnChallenge) {
                channel.close()
                return
            }
            send(Iap2MessageIds.MFI_RESPONSE, Iap2Csm.param(0, ByteArray(70) { (it * 7).toByte() }))
            expect(Iap2MessageIds.MFI_AUTHENTICATION_SUCCEEDED)

            // 订阅 + StartHID（奥迪形态不发，发送端应直接跳过等待）
            if (!audiDeclarations) {
                send(Iap2MessageIds.START_NOW_PLAYING_UPDATES)
                send(Iap2MessageIds.START_POWER_UPDATES)
                send(
                    Iap2MessageIds.START_HID,
                    Iap2Csm.u16(0, 0),
                    Iap2Csm.u16(1, 2),
                    Iap2Csm.u16(2, 1),
                    Iap2Csm.param(4, byteArrayOf(0x05, 0x0c)),
                )
                expect(Iap2MessageIds.DEVICE_HID_REPORT)
            }

            // WiFi 配置
            expect(Iap2MessageIds.REQUEST_ACCESSORY_WIFI_CONFIGURATION)
            // 迟到一条订阅请求（真实车机 bring-up 常见：0x5000 与 0x5703 相邻到达）。
            // 它必须被暂存而不是在 expectOrNull 里被反复重读——旧实现把非预期帧塞回
            // 「自己下一轮优先读取」的队列，同一帧无限循环（2026-10-18 日志刷屏根因）。
            send(Iap2MessageIds.START_NOW_PLAYING_UPDATES)
            send(
                Iap2MessageIds.ACCESSORY_WIFI_CONFIGURATION,
                Iap2Csm.string(1, "MockAP"),
                Iap2Csm.string(2, "secret-passphrase"),
                Iap2Csm.u8(3, 2),
                Iap2Csm.u8(4, 36),
            )

            // 设备状态问候（真机顺序：0x4E0D/0x4E0A/0x4E09/0x4E0B/0x4E0C → 0x4300）
            expect(Iap2MessageIds.WIRELESS_CARPLAY_UPDATE)
            expect(Iap2MessageIds.DEVICE_LANGUAGE_UPDATE)
            expect(Iap2MessageIds.DEVICE_INFORMATION_UPDATE)
            expect(Iap2MessageIds.DEVICE_TIME_UPDATE)
            expect(Iap2MessageIds.DEVICE_UUID_UPDATE)

            // CarPlay 启动
            expect(Iap2MessageIds.CARPLAY_AVAILABILITY)
            if (!lateStartSession) {
                sendStartSession()
            }

            expect(Iap2MessageIds.DEVICE_TRANSPORT_IDENTIFIER)
            if (lateStartSession) {
                // 车机打开 CarPlay 窗口后补发（Phase 1 早已结束）
                Thread.sleep(150)
                sendStartSession()
            }
        }

        private fun sendStartSession() {
            send(
                Iap2MessageIds.CARPLAY_START_SESSION,
                Iap2Csm.group(
                    1,
                    Iap2Csm.string(0, "MockAP"),
                    Iap2Csm.string(1, "secret-passphrase"),
                    Iap2Csm.u8(2, 36),
                    addressParam(3, "192.168.49.1", padTo = 17),
                    addressParam(3, "fd00::1", padTo = 47),
                    Iap2Csm.u8(4, 2),
                ),
                Iap2Csm.u32(2, 49152L),
                Iap2Csm.string(3, "mock-device-id"),
                Iap2Csm.string(4, "a".repeat(64) + "publickeyhex"),
                Iap2Csm.string(5, "560.20"),
            )
        }

        /** 地址参数：真实车机按定长 NUL 填充；默认（不填充）与之前行为一致。 */
        private fun addressParam(
            id: Int,
            value: String,
            padTo: Int,
        ): ByteArray {
            val raw = value.encodeToByteArray()
            if (!padAddresses) return Iap2Csm.param(id, raw + byteArrayOf(0))
            // 至少保留一个 NUL 结尾（CSM 字符串语义），其余补到固定宽度
            val width = maxOf(padTo, raw.size + 1)
            val padded = raw + ByteArray(width - raw.size)
            return Iap2Csm.param(id, padded)
        }

        private fun send(
            messageId: Int,
            vararg body: ByteArray,
        ) {
            val joined =
                when (body.size) {
                    0 -> ByteArray(0)
                    1 -> body[0]
                    else -> body.reduce { acc, bytes -> acc + bytes }
                }
            val frame = Iap2Csm.encode(messageId, joined)
            var offset = 0
            val limit = minOf(channel.peerMaxControlPayload() ?: 2048, chunkSize)
            while (offset < frame.size) {
                val end = minOf(offset + limit, frame.size)
                check(channel.sendControl(frame.copyOfRange(offset, end))) { "mock send failed" }
                offset = end
                if (offset < frame.size && chunkSize != Int.MAX_VALUE) Thread.sleep(5)
            }
        }

        private fun expect(messageId: Int): Iap2Csm.Frame {
            println("MOCK expect 0x${messageId.toString(16)}")
            val frame =
                recvFrame(10_000)
                    ?: error("mock timed out waiting 0x${messageId.toString(16)}")
            check(frame.messageId == messageId) {
                "mock expected 0x${messageId.toString(16)} got 0x${frame.messageId.toString(16)}"
            }
            println("MOCK got 0x${frame.messageId.toString(16)}")
            return frame
        }

        private fun recvFrame(timeoutMillis: Long): Iap2Csm.Frame? {
            pending.firstOrNull()?.let {
                pending.removeFirst()
                return it
            }
            val bytes = channel.recvControl(timeoutMillis) ?: return null
            val frames = parser.offer(bytes)
            if (frames.isEmpty()) return null
            frames.forEachIndexed { index, frame -> if (index > 0) pending += frame }
            return frames.first()
        }
    }
}
