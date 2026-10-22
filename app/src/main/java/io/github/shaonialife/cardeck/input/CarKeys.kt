package io.github.shaonialife.cardeck.input

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.view.KeyEvent
import android.view.ViewConfiguration
import io.github.shaonialife.cardeck.launcher.VirtualLauncher
import io.github.shaonialife.cardeck.shizuku.CarInputClient
import io.github.shaonialife.cardeck.util.Bg
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.atomic.AtomicBoolean

/** 将车机控制器映射为虚拟屏键盘；所有 Binder 调用串行留在后台。 */
internal object CarKeys {
    private data class Held(
        val display: Int,
        val code: Int,
        val downTime: Long,
        var repeats: Int = 0,
    )

    private val jobs = ConcurrentLinkedQueue<() -> Unit>()
    private val draining = AtomicBoolean()
    private val timer = Handler(Looper.getMainLooper())
    private val held = mutableMapOf<Int, Held>()
    private val keyState = HidKeyState()
    private var context: Context? = null
    private var log: (String) -> Unit = {}

    @Volatile private var generation = 0

    @Volatile private var dockFocus = false

    @Volatile var sessionActive = false
        private set

    /** 会话只持有 applicationContext，避免 Activity 重建后引用旧窗口。 */
    fun attach(
        context: Context,
        log: (String) -> Unit,
    ) {
        this.context = context.applicationContext
        this.log = log
    }

    /** 焦点跨过桌面边界时切换输入屏，不影响正在按住的键的松开目标。 */
    fun focusDock(enabled: Boolean) {
        dockFocus = enabled
        CarFocusNavigation.refreshInputTarget()
    }

    /** 只有当前会话虚拟屏就绪后才允许输入，连接准备中的报告不写入按住状态。 */
    @Synchronized
    fun startSession() {
        sessionActive = true
        CarFocusNavigation.refreshInputTarget()
    }

    /** 旋转保留增量；每格发 Tab/Shift+Tab，原生应用也可使用标准键盘焦点。 */
    fun rotate(steps: Int) =
        enqueue { current ->
            val meta = if (steps < 0) KeyEvent.META_SHIFT_ON else 0
            // 异常报告不得长时间占满输入队列；正常 8-bit 旋钮增量远小于该上限。
            repeat(
                kotlin.math
                    .abs(steps.toLong())
                    .coerceAtMost(64)
                    .toInt(),
            ) {
                if (current != generation || !sessionActive) return@enqueue
                val display = targetDisplay()
                val now = SystemClock.uptimeMillis()
                send(Held(display, KeyEvent.KEYCODE_TAB, now), KeyEvent.ACTION_DOWN, meta)
                send(Held(display, KeyEvent.KEYCODE_TAB, now), KeyEvent.ACTION_UP, meta)
            }
        }

    /** 持续按下只发一次 DOWN，物理松开时向原屏发 UP，支持确认键长按。 */
    fun keys(
        source: String,
        keys: Set<Int>,
        canceled: Boolean = false,
    ) = enqueue { current ->
        val changes = keyState.update(source, keys)
        for ((code, down) in changes) {
            if (current != generation || !sessionActive) return@enqueue
            if (code == KeyEvent.KEYCODE_HOME) {
                if (down) VirtualLauncher.onHomePressed()
                continue
            }
            if (down) {
                if (held.containsKey(code)) continue
                val press = Held(targetDisplay(), code, SystemClock.uptimeMillis())
                if (send(press, KeyEvent.ACTION_DOWN)) {
                    held[code] = press
                    if (code == KeyEvent.KEYCODE_DPAD_CENTER || code in KeyEvent.KEYCODE_DPAD_UP..KeyEvent.KEYCODE_DPAD_RIGHT) {
                        repeatLater(press, ViewConfiguration.getLongPressTimeout().toLong())
                    }
                }
            } else {
                held.remove(code)?.let { send(it, KeyEvent.ACTION_UP, canceled = canceled) }
            }
        }
    }

    /** 断开立即使旧任务失效，补发松开，防止下一会话沿用“按住确认”的状态。 */
    @Synchronized
    fun reset() {
        sessionActive = false
        generation++
        dockFocus = false
        CarFocusNavigation.refreshInputTarget()
        submit {
            held.values.forEach { send(it, KeyEvent.ACTION_UP, canceled = true) }
            held.clear()
            keyState.clear()
        }
    }

    /** 地图全屏走地图屏，超采样应用走实际活动屏；绝不回退到手机主屏。 */
    internal fun targetDisplay(): Int =
        when {
            !sessionActive -> -1
            dockFocus -> VirtualLauncher.dockDisplayId
            VirtualLauncher.mapFullscreen -> VirtualLauncher.mapDisplayId
            else -> VirtualLauncher.activeAppDisplayId()
        }

    /** 定时器只排队，不在主线程调用 Shizuku；释放或换会话后停止重复。 */
    private fun repeatLater(
        press: Held,
        delay: Long,
    ) {
        val current = generation
        timer.postDelayed({
            if (current == generation) {
                enqueue {
                    if (held[press.code] === press) {
                        press.repeats++
                        send(press, KeyEvent.ACTION_DOWN)
                        repeatLater(press, ViewConfiguration.getKeyRepeatDelay().toLong())
                    }
                }
            }
        }, delay)
    }

    /** 不支持的 ROM 或陈旧 UserService 明确留痕，不把失败当作已执行。 */
    private fun send(
        press: Held,
        action: Int,
        meta: Int = 0,
        canceled: Boolean = false,
    ): Boolean {
        if (press.display <= 0) return false
        return try {
            val service = CarInputClient.service
            if (service == null) {
                context?.let { CarInputClient.ensure(it) }
                log("KEY: 注入服务未就绪，正在重新绑定")
                false
            } else {
                val ok = service.keyEvent(press.display, action, press.code, meta, press.downTime, press.repeats, canceled)
                if (!ok) log("KEY: 注入被拒 display=${press.display} key=${press.code} action=$action")
                ok
            }
        } catch (error: Exception) {
            log("KEY: 注入失败 - ${error.message}")
            false
        }
    }

    /** 旧会话排队中的按键不得在新虚拟屏上生效。 */
    @Synchronized
    private fun enqueue(job: (Int) -> Unit) {
        if (!sessionActive) return
        val current = generation
        submit { if (sessionActive && generation == current) job(current) }
    }

    /** 使用已有 Bg 池排空队列，避免为每个旋转刻度创建一条独立线程。 */
    private fun submit(job: () -> Unit) {
        jobs.add(job)
        if (!draining.compareAndSet(false, true)) return
        Bg.run("car-keys") {
            try {
                while (true) (jobs.poll() ?: break).invoke()
            } finally {
                draining.set(false)
                if (jobs.isNotEmpty()) submit {}
            }
        }
    }
}
