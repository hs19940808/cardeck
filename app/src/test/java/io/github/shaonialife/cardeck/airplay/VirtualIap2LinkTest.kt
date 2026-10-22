package io.github.shaonialife.cardeck.airplay

import io.github.shaonialife.cardeck.iap2.Iap2Csm
import io.github.shaonialife.cardeck.iap2.Iap2LinkEngine
import io.github.shaonialife.cardeck.iap2.MfiLengthMemory
import io.github.shaonialife.cardeck.iap2.asString
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * MIB3 WiFi 隧道握手（VirtualIap2Link 服务端 × 裸引擎扮演车机客户端）。
 *
 * 时序即 AADisplay 以 Frida 抓到的真机行为：手机 arm（发一次 detect）→ 车机 SYN →
 * 手机 SYN|ACK →（链路 NORMAL）手机 0x1D00 → 车机 0x1D01 → 手机 0x1D02+0xAA00 →
 * （0xAA06 → 0xAA00）→ 0xAA01 → 手机 0xAA02 挑战 **20B** → 车机 0xAA03 → 手机
 * 0xAA05 + 突发 → 车机订阅（0x5000/0x4157/0x4154/0x4170）→ 对应空态应答。
 */

/**
 * 按链路帧头拆分（FF5A + u16 长度 + 9B 头）：引擎一次 takeOutput 可能拼多帧，
 * marker（6B，非 FF5A）跳过。
 */
private fun splitLinkFrames(chunk: ByteArray): List<ByteArray> {
    val out = ArrayList<ByteArray>()
    var off = 0
    while (off + 9 <= chunk.size) {
        if (chunk[off] != 0xFF.toByte() || chunk[off + 1] != 0x5A.toByte()) {
            off += 1
            continue
        }
        // 长度字段 = 整帧长度（含 9B 头），非载荷长
        val frameLen = ((chunk[off + 2].toInt() and 0xFF) shl 8) or (chunk[off + 3].toInt() and 0xFF)
        // 帧结构：9B 头（含头校验）+ 载荷 + 1B 尾部载荷校验（len=整帧长度）；
        // 裸 ACK/回显帧无载荷（整帧恰 9B）——跳过而非中断，同块其后的数据帧照常解析
        if (frameLen < 9 || off + frameLen > chunk.size) break
        if (frameLen > 9) out.add(chunk.copyOfRange(off + 9, off + frameLen - 1))
        off += frameLen
    }
    return out
}

class VirtualIap2LinkTest {
    /** 真实链路订阅后应发送缓存媒体和增量；StopNowPlaying 必须停止后续更新。 */
    @Test
    fun subscribedMediaUpdatesTravelThroughTheVirtualIap2Link() {
        val h = Harness()
        var song =
            io.github.shaonialife.cardeck.iap2.Iap2NowPlayingSession
                .Snapshot(title = "第一首", playing = true)
        h.link.nowPlayingProvider = { song }
        h.link.armMib3()
        h.car.start(0)
        h.exchangeUntilNormal()
        val requested = Iap2Csm.group(0, Iap2Csm.param(1, ByteArray(0))) + Iap2Csm.group(1, Iap2Csm.param(0, ByteArray(0)))
        h.carSends(0x5000, requested)

        fun fields(): List<Iap2Csm.Param> = Iap2Csm.parseParams(h.lastRawFrameOf(0x5001)!!.copyOfRange(6, h.lastRawFrameOf(0x5001)!!.size))
        assertEquals("第一首", Iap2Csm.parseParams(fields().first { it.id == 0 }.value).first().asString())
        val firstCount = h.controlFrames().count { it.second == 0x5001 }

        song = song.copy(title = "第二首", playing = false)
        h.clock += 1_500
        h.link.onCarBytes(ByteArray(0))
        assertEquals(firstCount + 1, h.controlFrames().count { it.second == 0x5001 })
        assertEquals("第二首", Iap2Csm.parseParams(fields().first { it.id == 0 }.value).first().asString())
        assertArrayEquals(byteArrayOf(2), Iap2Csm.parseParams(fields().first { it.id == 1 }.value).first().value)

        h.carSends(0x5002)
        val stoppedCount = h.controlFrames().count { it.second == 0x5001 }
        song = song.copy(title = "停止后不再发送")
        h.clock += 1_500
        h.link.onCarBytes(ByteArray(0))
        assertEquals(stoppedCount, h.controlFrames().count { it.second == 0x5001 })
    }

    private val mediaDescriptor =
        (
            "05 0c 09 01 a1 01 15 00 25 01 05 0c 09 b0 09 b5 09 b6 " +
                "0a 23 02 75 01 95 04 81 02 75 01 95 04 81 01 c0"
        ).split(' ').map { it.toInt(16).toByte() }.toByteArray()

    private val wheelDescriptor =
        (
            "05 01 09 08 a1 01 15 81 25 7f 75 08 95 01 09 38 81 06 c0"
        ).split(' ').map { it.toInt(16).toByte() }.toByteArray()

    /** 多组件必须使用各自的描述符，不能被最后一次 StartHID 覆盖。 */
    @Test
    fun differentHidComponentsKeepIndependentDescriptors() {
        val h = Harness()
        val wheels = mutableListOf<Int>()
        val media = mutableListOf<Int>()
        h.link.onControllerInput = { if (it.wheel != 0) wheels.add(it.wheel) }
        h.link.onMediaKey = { media.add(it) }
        h.link.armMib3()
        h.car.start(0)
        h.exchangeUntilNormal()
        h.carSends(0x6800, Iap2Csm.u16(0, 10) + Iap2Csm.param(4, wheelDescriptor))
        h.carSends(0x6800, Iap2Csm.u16(0, 11) + Iap2Csm.param(4, mediaDescriptor))
        h.carSends(0x6802, Iap2Csm.u16(0, 10) + Iap2Csm.param(1, byteArrayOf(1)))
        h.carSends(0x6802, Iap2Csm.u16(0, 11) + Iap2Csm.param(1, byteArrayOf(2)))
        assertEquals(listOf(1), wheels)
        assertEquals(listOf(android.view.KeyEvent.KEYCODE_MEDIA_NEXT), media)
    }

    /** 长组件名也会超过 8 字节，不能误当作 HID 描述符。 */
    @Test
    fun descriptorUsesItsParameterIdInsteadOfFirstLongField() {
        val h = Harness()
        val media = mutableListOf<Int>()
        h.link.onMediaKey = { media.add(it) }
        h.link.armMib3()
        h.car.start(0)
        h.exchangeUntilNormal()
        h.carSends(0x6800, Iap2Csm.u16(0, 11) + Iap2Csm.string(5, "Panel media controls") + Iap2Csm.param(4, mediaDescriptor))
        h.carSends(0x6802, Iap2Csm.u16(0, 11) + Iap2Csm.param(1, byteArrayOf(2)))
        assertEquals(listOf(android.view.KeyEvent.KEYCODE_MEDIA_NEXT), media)
    }

    /** 未注册组件不能借用另一组件的描述符，也不能制造松开。 */
    @Test
    fun unknownHidComponentCannotBorrowAnotherDevicesDescriptor() {
        val h = Harness()
        val media = mutableListOf<Int>()
        h.link.onMediaKey = { media.add(it) }
        h.link.armMib3()
        h.car.start(0)
        h.exchangeUntilNormal()
        h.carSends(0x6800, Iap2Csm.u16(0, 11) + Iap2Csm.param(4, mediaDescriptor))
        h.carSends(0x6802, Iap2Csm.u16(0, 99) + Iap2Csm.param(1, byteArrayOf(2)))
        assertEquals(emptyList<Int>(), media)
    }

    /** StopHID 终止组件时必须释放其导航键，并忽略 StartHID 前的迟到报告。 */
    @Test
    fun stopHidReleasesOnlyTheStoppedComponentAndRejectsLateReports() {
        val h = Harness()
        val reports = mutableListOf<io.github.shaonialife.cardeck.input.ControllerReports.Report>()
        h.link.onControllerInput = { reports.add(it) }
        h.link.armMib3()
        h.car.start(0)
        h.exchangeUntilNormal()
        h.carSends(0x6800, Iap2Csm.u16(0, 11) + Iap2Csm.param(4, mediaDescriptor))
        h.carSends(0x6802, Iap2Csm.u16(0, 11) + Iap2Csm.param(1, byteArrayOf(8)))
        h.carSends(0x6803, Iap2Csm.u16(0, 11))
        assertEquals(listOf(setOf(android.view.KeyEvent.KEYCODE_HOME), emptySet<Int>()), reports.map { it.keys })
        h.carSends(0x6802, Iap2Csm.u16(0, 11) + Iap2Csm.param(1, byteArrayOf(8)))
        assertEquals(2, reports.size)
    }

    private class Harness {
        val controlSent = ArrayList<ByteArray>()
        val allControl = ArrayList<ByteArray>()
        val eventSent = ArrayList<ByteArray>()
        val car = Iap2LinkEngine()
        val link =
            VirtualIap2Link(
                log = { },
                sendEventIap = {
                    eventSent.add(it)
                    true
                },
                sendControlIap = {
                    controlSent.add(it)
                    allControl.add(it)
                },
                tunnelWriter = { null },
                // 关闭节拍线程：测试内确定性驱动（无重传干扰）
                alive = { false },
                nowMillis = { clock },
            )
        var clock = 0L
        var now = 1L

        /** 车机→手机：喂字节并冲刷链路出站。 */
        fun carToLink() {
            val out = car.takeOutput()
            if (out.isNotEmpty()) link.onCarBytes(out)
        }

        /** 手机→车机：把控制通道出站字节喂回车机引擎。 */
        fun linkToCar() {
            while (controlSent.isNotEmpty()) {
                val batch = controlSent.toList()
                controlSent.clear()
                batch.forEach { car.feed(it, now) }
            }
        }

        fun exchange() {
            carToLink()
            linkToCar()
            now += 600
            clock += 600
            // 车机侧驱动计时器：采纳对端表后按超时机制补裸 ACK（真机行为同此——
            // BT 日志里我们的 SYN|ACK 之后车机立即回 ACK）
            car.advanceTime(now)
            linkToCar()
        }

        fun exchangeUntilNormal(rounds: Int = 8) {
            repeat(rounds) {
                exchange()
                if (car.state == Iap2LinkEngine.State.NORMAL) return
            }
        }

        /** 车机发一条 CSM，返回手机随后经控制通道发出的 CSM 消息号序列。 */
        fun carSends(
            messageId: Int,
            body: ByteArray = ByteArray(0),
        ): List<Int> {
            val before = controlFrames().size
            assertTrue(car.sendControl(Iap2Csm.encode(messageId, body), now))
            exchange()
            val ids = controlFrames().drop(before).map { it.second }
            return ids
        }

        /** 控制通道出站 →（链路帧拆分）→ 完整 CSM 帧字节与其消息号。
         *  每个链路帧载荷恰好一条 CSM：必须逐帧新建 Parser——SYN|ACK 的 LSP 载荷
         *  不是 CSM，共用有状态解析器会污染后续帧的边界。 */
        fun controlFrames(): List<Pair<ByteArray, Int>> {
            val out = ArrayList<Pair<ByteArray, Int>>()
            for (chunk in allControl) {
                splitLinkFrames(chunk).forEach { payload ->
                    Iap2Csm.Parser().offer(payload).forEach { frame ->
                        out.add(payload to frame.messageId)
                    }
                }
            }
            return out
        }

        /** 最近一条指定消息号的完整 CSM 帧字节（Frida 常量逐字节比对用）。 */
        fun lastRawFrameOf(messageId: Int): ByteArray? = controlFrames().lastOrNull { it.second == messageId }?.first

        /** 已发 0xAA02 挑战的长度序列（param 0 长度；多条时按出现顺序）。 */
        fun challengeLengths(): List<Int> =
            controlFrames()
                .filter { it.second == 0xAA02 }
                .map { (raw, _) ->
                    Iap2Csm
                        .parseParams(raw.copyOfRange(6, raw.size))
                        .first()
                        .value.size
                }
    }

    @Test
    fun mib3ArmSendsExactlyOneDetectOnControlChannel() {
        val h = Harness()
        h.link.armMib3()
        h.link.armMib3()
        assertEquals(listOf(Iap2LinkEngine.MARKER.toList()), h.controlSent.map { it.toList() })
        assertTrue(h.eventSent.isEmpty())
    }

    @Test
    fun preArmMarkerGetsNoReply() {
        val h = Harness()
        h.link.begin()
        h.link.onCarBytes(Iap2LinkEngine.MARKER)
        assertTrue(h.controlSent.isEmpty())
        assertTrue(h.eventSent.isEmpty())
    }

    /**
     * Honda HFT（2026-10-20 实测）：车机在事件通道上 1Hz 反复发 marker，说明它卡在
     * 探测态、在等**我们**的会话表。连着第 2 个 marker 起必须升级为「marker + 本表
     * 同步包」，且**只能走控制通道**——出站写在事件通道上车机不读（r6 踩过的坑）。
     */
    @Test
    fun repeatedMarkersUpgradeToControlChannelHandshake() {
        val h = Harness()
        h.link.armMib3()
        val detect = h.controlSent.size
        // 第 1 个：还不判定（MIB3 会再发一次探测重发）
        h.link.onCarBytes(Iap2LinkEngine.MARKER)
        assertEquals("首个 marker 不应额外发字节", detect, h.controlSent.size)
        // 第 2 个：升级为 marker + 同步包
        h.link.onCarBytes(Iap2LinkEngine.MARKER)
        val sent = h.controlSent.drop(detect)
        assertTrue("重复 marker 后必须补发协商字节", sent.isNotEmpty())
        val payload = sent.fold(ByteArray(0)) { acc, bytes -> acc + bytes }
        assertTrue(
            "补发的第一批必须含 marker（FF 55）",
            payload.size >= 6 && payload[0] == 0xFF.toByte() && payload[1] == 0x55.toByte(),
        )
        assertTrue(
            "marker 之后必须紧跟 FF5A 会话表（车机等的就是它）",
            payload.size >= 12 && payload[6] == 0xFF.toByte() && payload[7] == 0x5A.toByte(),
        )
        assertTrue(
            "会话表必须带 SYN 位（0x80）",
            (payload[10].toInt() and 0x80) != 0,
        )
        assertTrue("出站绝不能落到事件通道", h.eventSent.isEmpty())
    }

    @Test
    fun fullMib3HandshakeWithBurstAndSubscriptions() {
        val h = Harness()
        h.link.armMib3()
        h.car.start(0)
        h.exchangeUntilNormal()
        assertEquals(Iap2LinkEngine.State.NORMAL, h.car.state)
        // detect(1) + SYN|ACK + 0x1D00 已走控制通道；车机已收到 0x1D00
        assertTrue(h.controlFrames().map { it.second }.contains(0x1D00))

        // 0x1D01 → 0x1D02 + 0xAA00
        assertEquals(
            listOf(0x1D02, 0xAA00),
            h.carSends(0x1D01, Iap2Csm.string(0, "Audi_MMI_3315") + Iap2Csm.string(1, "AUDI 5163")),
        )

        // 0xAA06（auth serial）→ 再发 0xAA00
        assertEquals(listOf(0xAA00), h.carSends(0xAA06, Iap2Csm.string(0, "serial")))

        // 0xAA01（证书）→ 0xAA02 挑战 20B（MIB3 真机长度；DiPlay 形态为 32B）
        val challengeFrames = h.carSends(0xAA01, Iap2Csm.param(0, ByteArray(642)))
        assertEquals(listOf(0xAA02), challengeFrames)
        val challengeBody =
            h
                .lastRawFrameOf(0xAA02)!!
                .copyOfRange(6, h.lastRawFrameOf(0xAA02)!!.size)
                .let { Iap2Csm.parseParams(it).first() }
        assertEquals(20, challengeBody.value.size)

        // 0xAA03（签名应答）→ 0xAA05 + 突发（0x4E0C/0xA100/0x4E0A/0x4E0B/0x4E09/0xFFFA/0xFFF0）
        assertEquals(
            listOf(0xAA05, 0x4E0C, 0xA100, 0x4E0A, 0x4E0B, 0x4E09, 0xFFFA, 0xFFF0),
            h.carSends(0xAA03, Iap2Csm.param(0, ByteArray(96))),
        )

        // 订阅应答：0x5000 → 0x5001 空态；0x4157 → 0x4158；0x4154 → 0x4155；0x4170 → 0x4171
        assertEquals(listOf(0x5001), h.carSends(0x5000))
        assertEquals(listOf(0x4158), h.carSends(0x4157))
        assertEquals(listOf(0x4155), h.carSends(0x4154))
        assertEquals(listOf(0x4171), h.carSends(0x4170))
    }

    @Test
    fun nowPlayingIdleReplyIsTheFridaConstantVerbatim() {
        val h = Harness()
        h.link.armMib3()
        h.car.start(0)
        h.exchangeUntilNormal()
        h.carSends(0x1D01, Iap2Csm.string(0, "x"))
        h.carSends(0xAA01, Iap2Csm.param(0, ByteArray(642)))
        h.carSends(0xAA03, Iap2Csm.param(0, ByteArray(96)))
        h.carSends(0x5000)
        // 逐字节等于 Frida 抓包常量——字段布局只存在于真机行为里，重新编码极易走样
        assertEquals(
            VirtualIap2Link.MIB3_NOW_PLAYING_UPDATE_IDLE.toList(),
            h.lastRawFrameOf(0x5001)!!.toList(),
        )
        assertEquals(
            VirtualIap2Link.MIB3_COMMUNICATIONS_UPDATE_IDLE.toList(),
            run {
                h.carSends(0x4157)
                h.lastRawFrameOf(0x4158)!!.toList()
            },
        )
    }

    @Test
    fun tunnelMfiUsesRememberedLengthPerCar() {
        // 与其它测试不同身份，避免进程内记忆串扰
        val identity = "MemCar MemModel MemMfr tunnel"
        MfiLengthMemory.recordSuccess(null, identity, 32)
        val h = Harness()
        h.link.armMib3()
        h.car.start(0)
        h.exchangeUntilNormal()
        h.carSends(0x1D01, Iap2Csm.string(0, "MemCar") + Iap2Csm.string(1, "MemModel") + Iap2Csm.string(2, "MemMfr"))
        h.carSends(0xAA01, Iap2Csm.param(0, ByteArray(642)))
        // 记忆命中：32B 直接排第一，不再从 20B 试起
        assertEquals(listOf(32), h.challengeLengths())
    }

    @Test
    fun silentChallengeRetriesNextLengthWithinSession() {
        val h = Harness()
        h.link.armMib3()
        h.car.start(0)
        h.exchangeUntilNormal()
        h.carSends(0x1D01, Iap2Csm.string(0, "SilCar") + Iap2Csm.string(1, "SilMfr"))
        h.carSends(0xAA01, Iap2Csm.param(0, ByteArray(642)))
        assertEquals(listOf(20), h.challengeLengths())
        // 20B 静默：推进时钟越过 6s 窗口，喂一个 marker 触发节拍检查（drainLocked）
        h.clock += 7_000
        h.link.onCarBytes(Iap2LinkEngine.MARKER)
        assertEquals(listOf(20, 32), h.challengeLengths())
        // 32B 得到应答 → 0xAA05 + 突发照常
        val after = h.controlFrames().size
        h.carSends(0xAA03, Iap2Csm.param(0, ByteArray(96)))
        assertTrue(
            "frames=${h.controlFrames().drop(after).map { it.second }}",
            h
                .controlFrames()
                .drop(after)
                .map { it.second }
                .containsAll(listOf(0xAA05, 0x4E0C, 0xFFFA)),
        )
    }

    /**
     * 0x6802 取参数：本田 HFT 实测帧 `00 06 00 00 00 03 | 00 05 00 01 04`。
     *
     * param 0 恒为 `00 03`（报告类型），param 1 才是 HID 报文（0x04 = 下一首）。
     * 旧实现取「第一个短参数」→ 永远拿到 00 03 → 位图全 0 → 按键点不出动作。
     */
    @Test
    fun hidReportComesFromParamOneNotParamZero() {
        // 单行书写：ktlint 对该规则要求「多行则一参数一行」，单行更贴近原始帧的可读形式
        val body = byteArrayOf(0x00, 0x06, 0x00, 0x00, 0x00, 0x03, 0x00, 0x05, 0x00, 0x01, 0x04)
        val report = VirtualIap2Link.hidReportFromParams(Iap2Csm.parseParams(body))
        assertEquals(listOf<Byte>(0x04), report?.toList())
    }

    @Test
    fun hidReportFallsBackToLastShortParamWhenOnlyOneGiven() {
        val body = byteArrayOf(0x00, 0x05, 0x00, 0x00, 0x08)
        val report = VirtualIap2Link.hidReportFromParams(Iap2Csm.parseParams(body))
        assertEquals(listOf<Byte>(0x08), report?.toList())
    }

    /** 显式空 data 或多参数缺 data 都是残缺消息，不能把元数据当作按键。 */
    @Test
    fun incompleteHidCommandDoesNotFallBackToMetadata() {
        val emptyData = Iap2Csm.param(0, byteArrayOf(0, 3)) + Iap2Csm.param(1, ByteArray(0))
        val missingData = Iap2Csm.param(0, byteArrayOf(0, 3)) + Iap2Csm.param(2, byteArrayOf(8))
        assertNull(VirtualIap2Link.hidReportFromParams(Iap2Csm.parseParams(emptyData)))
        assertNull(VirtualIap2Link.hidReportFromParams(Iap2Csm.parseParams(missingData)))
    }

    /** 同一份马自达旋钮描述符经 iAP2 承载时仍应送入控制器通路。 */
    @Test
    fun mazdaKnobThroughIap2KeepsButtonsAndRelativeWheel() {
        val h = Harness()
        val reports = mutableListOf<io.github.shaonialife.cardeck.input.ControllerReports.Report>()
        h.link.onControllerInput = { reports.add(it) }
        h.link.armMib3()
        h.car.start(0)
        h.exchangeUntilNormal()
        val descriptor =
            (
                "05 01 09 08 a1 01 05 09 09 01 15 00 25 01 75 01 95 01 81 02 " +
                    "05 0c 0a 24 02 95 01 81 02 95 06 81 01 05 01 09 01 a1 00 " +
                    "09 30 09 31 15 81 25 7f 75 08 95 02 81 02 c0 09 38 15 81 " +
                    "25 7f 75 08 95 01 81 06 c0"
            ).split(' ').map { it.toInt(16).toByte() }.toByteArray()
        h.carSends(0x6800, Iap2Csm.param(2, descriptor))
        h.carSends(0x6802, Iap2Csm.param(1, byteArrayOf(1, 0, 0, 1)))
        h.carSends(0x6802, Iap2Csm.param(1, byteArrayOf(1, 0, 0, 1)))
        assertEquals(2, reports.size)
        assertEquals(setOf(android.view.KeyEvent.KEYCODE_DPAD_CENTER), reports[0].keys)
        assertEquals(listOf(1, 1), reports.map { it.wheel })
    }

    /** 主页属于导航，不能被 iAP2 媒体键通路当成未知音频键吞掉。 */
    @Test
    fun mediaBitmapHomeThroughIap2IsNotDispatchedAsAudio() {
        val h = Harness()
        val reports = mutableListOf<io.github.shaonialife.cardeck.input.ControllerReports.Report>()
        val media = mutableListOf<Int>()
        h.link.onControllerInput = { reports.add(it) }
        h.link.onMediaKey = { media.add(it) }
        h.link.armMib3()
        h.car.start(0)
        h.exchangeUntilNormal()
        val descriptor =
            (
                "05 0c 09 01 a1 01 15 00 25 01 05 0c 09 b0 09 b5 09 b6 " +
                    "0a 23 02 75 01 95 04 81 02 75 01 95 04 81 01 c0"
            ).split(' ')
                .map { it.toInt(16).toByte() }
                .toByteArray()
        h.carSends(0x6800, Iap2Csm.param(2, descriptor))
        h.carSends(0x6802, Iap2Csm.param(1, byteArrayOf(8)))
        h.carSends(0x6802, Iap2Csm.param(1, byteArrayOf(0)))
        assertEquals(setOf(android.view.KeyEvent.KEYCODE_HOME), reports[0].keys)
        assertEquals(emptySet<Int>(), reports[1].keys)
        assertEquals(emptyList<Int>(), media)
    }

    /** 旋钮和媒体 Collection 同属一个设备时，两种报告都要消费。 */
    @Test
    fun compositeHidDeliversBothWheelAndMediaReports() {
        val h = Harness()
        val wheels = mutableListOf<Int>()
        val media = mutableListOf<Int>()
        h.link.onControllerInput = { if (it.wheel != 0) wheels.add(it.wheel) }
        h.link.onMediaKey = { media.add(it) }
        h.link.armMib3()
        h.car.start(0)
        h.exchangeUntilNormal()
        val descriptor =
            (
                "05 01 09 08 a1 01 85 01 15 81 25 7f 75 08 95 01 09 38 81 06 c0 " +
                    "05 0c 09 01 a1 01 85 02 15 00 25 01 75 01 95 01 09 b5 81 02 75 07 81 01 c0"
            ).split(' ').map { it.toInt(16).toByte() }.toByteArray()
        h.carSends(0x6800, Iap2Csm.param(2, descriptor))
        h.carSends(0x6802, Iap2Csm.param(1, byteArrayOf(1, 1)))
        h.carSends(0x6802, Iap2Csm.param(1, byteArrayOf(2, 1)))
        assertEquals(listOf(1), wheels)
        assertEquals(listOf(android.view.KeyEvent.KEYCODE_MEDIA_NEXT), media)
    }

    /** 无描述符的多字节包不能只按首字节猜媒体键，旋钮和电话也有同样长度。 */
    @Test
    fun unknownMultiByteHidReportCannotTriggerAudio() {
        val h = Harness()
        val media = mutableListOf<Int>()
        h.link.onMediaKey = { media.add(it) }
        h.link.armMib3()
        h.car.start(0)
        h.exchangeUntilNormal()
        h.carSends(0x6802, Iap2Csm.param(1, byteArrayOf(0xB5.toByte(), 1, 0, 0)))
        assertEquals(emptyList<Int>(), media)
        h.carSends(0x6802, Iap2Csm.param(1, byteArrayOf(0xB5.toByte())))
        h.carSends(0x6802, Iap2Csm.param(1, byteArrayOf(0xB5.toByte(), 1, 0, 0)))
        h.carSends(0x6802, Iap2Csm.param(1, byteArrayOf(0xB5.toByte())))
        assertEquals(listOf(android.view.KeyEvent.KEYCODE_MEDIA_NEXT), media)
    }

    /** 第二组报告的空位不应令第一组持续按下被重新当成一次短按。 */
    @Test
    fun mediaReportIdsKeepTheirHeldStateAcrossInterleavedReports() {
        val h = Harness()
        val media = mutableListOf<Int>()
        h.link.onMediaKey = { media.add(it) }
        h.link.armMib3()
        h.car.start(0)
        h.exchangeUntilNormal()
        val descriptor =
            (
                "05 0c 09 01 a1 01 85 01 15 00 25 01 75 01 95 01 09 b5 81 02 75 07 81 01 " +
                    "85 02 75 01 09 b6 81 02 75 07 81 01 c0"
            ).split(' ').map { it.toInt(16).toByte() }.toByteArray()
        h.carSends(0x6800, Iap2Csm.param(2, descriptor))
        h.carSends(0x6802, Iap2Csm.param(1, byteArrayOf(1, 1)))
        h.carSends(0x6802, Iap2Csm.param(1, byteArrayOf(2, 0)))
        h.carSends(0x6802, Iap2Csm.param(1, byteArrayOf(1, 1)))
        assertEquals(listOf(android.view.KeyEvent.KEYCODE_MEDIA_NEXT), media)
        h.carSends(0x6802, Iap2Csm.param(1, byteArrayOf(1, 0)))
        h.carSends(0x6802, Iap2Csm.param(1, byteArrayOf(1, 1)))
        assertEquals(List(2) { android.view.KeyEvent.KEYCODE_MEDIA_NEXT }, media)
    }
}
