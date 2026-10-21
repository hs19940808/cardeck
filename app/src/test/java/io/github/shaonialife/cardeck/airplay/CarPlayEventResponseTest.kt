package io.github.shaonialife.cardeck.airplay

import io.github.shaonialife.cardeck.iap2.Iap2SenderSession
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** changeModes 的资源结果必须与请求 CSeq 一起返回，单独的 modesChanged 不能替代它。 */
class CarPlayEventResponseTest {
    @Test
    fun changeModesResponseContainsTheAcceptedResourceSnapshotAndRequestSequence() {
        val modes = CarPlayModes()
        val reply = modes.change(mapOf("resources" to listOf(mapOf("resourceID" to 1L, "transferType" to 1L))))
        val body = BinaryPlist.encode(reply)

        val wire = client().buildEventResponse("37", body)

        val headerEnd = String(wire, Charsets.ISO_8859_1).indexOf("\r\n\r\n")
        val header = String(wire, 0, headerEnd, Charsets.US_ASCII)
        assertTrue(header.startsWith("RTSP/1.0 200 OK\r\n"))
        assertTrue(header.contains("CSeq: 37"))
        assertTrue(header.contains("Content-Type: application/x-apple-binary-plist"))
        assertTrue(header.contains("Content-Length: ${body.size}"))
        val decoded = BinaryPlist.parse(wire.copyOfRange(headerEnd + 4, wire.size)) as Map<*, *>
        assertEquals(0L, decoded["status"])
        assertEquals(reply["params"], decoded["params"])
    }

    @Test
    fun normalEventAcknowledgementStaysEmpty() {
        val wire = client().buildEventResponse("38", ByteArray(0))
        val response = String(wire, Charsets.US_ASCII)
        assertTrue(response.contains("Content-Length: 0\r\n"))
        assertTrue(response.contains("CSeq: 38\r\n"))
        assertFalse(response.contains("Content-Type:"))
        assertTrue(response.endsWith("\r\n\r\n"))
    }

    /** 仅构造协议对象；测试不依赖 Android 设备或真实车机。 */
    private fun client(): AirPlayClient =
        AirPlayClient(
            Iap2SenderSession.CarPlayEndpoint(
                ssid = "test-car",
                passphrase = "",
                channel = 0,
                securityType = 0,
                airPlayPort = 7000,
                deviceIdentifier = null,
                publicKey = null,
                sourceVersion = null,
                ipAddresses = listOf("127.0.0.1"),
            ),
            log = {},
        )
}
