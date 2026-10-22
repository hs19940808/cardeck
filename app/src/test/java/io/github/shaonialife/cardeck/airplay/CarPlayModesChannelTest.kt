package io.github.shaonialife.cardeck.airplay

import io.github.shaonialife.cardeck.iap2.Iap2SenderSession
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import java.net.ServerSocket
import java.net.Socket
import java.net.SocketTimeoutException
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/** 重放昂克赛拉的切屏命令，核对通知实际到达事件连接，而不是只收到控制连接的 200。 */
class CarPlayModesChannelTest {
    @Test
    fun resourceNotificationsUseTheEventChannelInPlainAndEncryptedSessions() {
        for (secret in listOf(null, ByteArray(32) { it.toByte() })) {
            Harness(secret).use { h ->
                assertTrue(h.client.assertModes())
                val initial = h.receive()
                assertResource(initial, 1L)

                h.sendCommand("21", "changeModes", mapOf("resources" to listOf(mapOf("resourceID" to 1L, "transferType" to 1L))))
                val accepted = h.receive()
                assertTrue(accepted.head.startsWith("RTSP/1.0 200 OK"))
                assertTrue(accepted.head.contains("CSeq: 21\r\n"))
                assertEquals(0L, accepted.dictionary()["status"])
                assertResource(h.receive(), 2L)

                // 实车在原车界面按导航键时，先释放屏幕，再发 maps: 页面请求。
                h.sendCommand("22", "changeModes", mapOf("resources" to listOf(mapOf("resourceID" to 1L, "transferType" to 2L))))
                assertEquals(0L, h.receive().dictionary()["status"])
                h.receive()
                h.sendCommand("23", "requestUI", mapOf("url" to "maps:"))
                val uiAck = h.receive()
                assertTrue(uiAck.head.contains("CSeq: 23\r\n"))
                assertEquals(0, uiAck.body.size)
                val active = h.receive()
                assertResource(active, 1L)
                assertTrue(h.uiDelivered.await(2, TimeUnit.SECONDS))

                // 对方的资源确认只记日志，不能再应答而形成回声。
                val sequence = Regex("CSeq: (\\d+)").find(active.head)!!.groupValues[1]
                h.send("RTSP/1.0 200 OK\r\nCSeq: $sequence\r\nContent-Length: 0\r\n\r\n".toByteArray())
                assertTrue(h.modeAcknowledged.await(2, TimeUnit.SECONDS))
                h.peer.soTimeout = 200
                assertThrows(SocketTimeoutException::class.java) { h.peer.getInputStream().read() }
            }
        }
    }

    @Test
    fun missingOrClosedEventChannelCannotReportSuccessfulResourceDelivery() {
        Harness(null).use { h ->
            h.client.close()
            assertFalse(h.client.assertModes())
            assertEquals(-1, h.peer.getInputStream().read())
        }
    }

    /** 从真实线格式中检查屏幕归属；不能只验证调用了某个内部方法。 */
    private fun assertResource(
        message: Message,
        owner: Long,
    ) {
        assertTrue(message.head.startsWith("POST /command RTSP/1.0"))
        val command = message.dictionary()
        assertEquals("modesChanged", command["type"])
        val params = command["params"] as Map<*, *>
        val screen = (params["resources"] as List<*>).filterIsInstance<Map<*, *>>().first { it["resourceID"] == 1L }
        assertEquals(owner, screen["entity"])
    }

    private data class Message(
        val head: String,
        val body: ByteArray,
    ) {
        /** 解码完整报文体，保持请求头和 plist 的检验相互独立。 */
        fun dictionary(): Map<*, *> = BinaryPlist.parse(body) as Map<*, *>
    }

    /** 只建立本地事件连接；故意不建立 RTSP 控制连接，以复现原错误路径。 */
    private class Harness(
        secret: ByteArray?,
    ) : AutoCloseable {
        private val listener = ServerSocket(0)
        val uiDelivered = CountDownLatch(1)
        val modeAcknowledged = CountDownLatch(1)
        val client =
            AirPlayClient(
                Iap2SenderSession.CarPlayEndpoint("mock", "", 0, 0, 7000, null, null, null, listOf("127.0.0.1")),
                log = { if (it.startsWith("EVENT: modesChanged 应答")) modeAcknowledged.countDown() },
            )
        val peer: Socket
        private val cipher =
            secret?.let {
                ControlCipher(
                    AirPlayCrypto.hkdfSha512(it, "Events-Salt".toByteArray(), "Events-Read-Encryption-Key".toByteArray()),
                    AirPlayCrypto.hkdfSha512(it, "Events-Salt".toByteArray(), "Events-Write-Encryption-Key".toByteArray()),
                )
            }
        private val plaintext = ArrayDeque<Byte>()

        init {
            client.connectEventChannel(secret, AirPlayClient.SetupInfo(listener.localPort, 0, null, emptyList(), 1280, 480, "screen", 30)) {
                if (it["type"] == "requestUI" && (it["params"] as? Map<*, *>)?.get("url") == "maps:") uiDelivered.countDown()
            }
            peer = listener.accept().apply { soTimeout = 3_000 }
        }

        /** 将完整模拟命令写入事件通道，使用接收端方向的密钥。 */
        fun sendCommand(
            sequence: String,
            type: String,
            params: Map<String, Any>,
        ) {
            val body = BinaryPlist.encode(mapOf("type" to type, "params" to params))
            send("POST /command RTSP/1.0\r\nCSeq: $sequence\r\nContent-Length: ${body.size}\r\n\r\n".toByteArray() + body)
        }

        /** 保留与生产连接相同的加密计数，覆盖通知与应答交替写入。 */
        fun send(bytes: ByteArray) {
            peer.getOutputStream().write(cipher?.encrypt(bytes) ?: bytes)
            peer.getOutputStream().flush()
        }

        /** 按 Content-Length 读满一条报文，不依赖 TCP 的分片边界。 */
        fun receive(): Message {
            val header = StringBuilder()
            while (!header.endsWith("\r\n\r\n")) header.append(readByte().toChar())
            val head = header.toString()
            val length = Regex("Content-Length: (\\d+)", RegexOption.IGNORE_CASE).find(head)!!.groupValues[1].toInt()
            return Message(head, ByteArray(length) { readByte().toByte() })
        }

        /** 加密帧可能包含部分或多条 RTSP 消息，因此单独缓存解密后的字节。 */
        private fun readByte(): Int {
            if (cipher == null) return peer.getInputStream().read().also { check(it >= 0) }
            if (plaintext.isEmpty()) {
                val input = peer.getInputStream()
                val header = input.readNBytes(2)
                check(header.size == 2)
                val length = (header[0].toInt() and 255) or ((header[1].toInt() and 255) shl 8)
                plaintext.addAll(cipher.decrypt(header + input.readNBytes(length + 16)).data.toList())
            }
            return plaintext.removeFirst().toInt() and 255
        }

        /** 结束客户端会同时释放事件连接，避免测试留下常驻 reader。 */
        override fun close() {
            client.close()
            peer.close()
            listener.close()
        }
    }
}
