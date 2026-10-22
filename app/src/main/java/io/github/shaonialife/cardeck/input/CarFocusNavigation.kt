package io.github.shaonialife.cardeck.input

import android.app.Activity
import android.app.Application
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.KeyEvent
import android.view.View
import android.view.ViewGroup
import android.view.ViewTreeObserver
import android.view.Window
import android.widget.EditText
import android.widget.SeekBar
import io.github.shaonialife.cardeck.launcher.VirtualLauncher

/** 分页桌面提供逻辑顺序，避免快速旋转时焦点落到 ViewPager 的离屏页面。 */
internal interface RotaryFocusHost {
    /** 移动一格；首尾没有下一项时返回 false，由 Dock 接续。 */
    fun moveRotaryFocus(forward: Boolean): Boolean

    /** 从其他虚拟屏进入时定位整套分页的首项或末项。 */
    fun focusRotaryEdge(forward: Boolean)
}

/** 自有虚拟屏页面的键盘焦点与高亮，手机主屏的操作方式保持原样。 */
internal object CarFocusNavigation {
    private val drivers = mutableMapOf<Activity, Driver>()
    private val main = Handler(Looper.getMainLooper())

    /** 输入目标改变时仅保留该屏的描边，离开的页面保留原焦点供返回恢复。 */
    fun refreshInputTarget() {
        if (Looper.myLooper() != Looper.getMainLooper()) {
            main.post { refreshInputTarget() }
            return
        }
        val target = CarKeys.targetDisplay()
        drivers.values.forEach { it.setInputActive(it.display == target) }
    }

    /** 跟随 Activity 生命周期安装，包含车机设置及其临时二级页面。 */
    fun install(application: Application) {
        application.registerActivityLifecycleCallbacks(
            object : Application.ActivityLifecycleCallbacks {
                override fun onActivityResumed(activity: Activity) {
                    val id = activity.display?.displayId ?: return
                    if (id <= 0 ||
                        id !in
                        listOf(
                            VirtualLauncher.dockDisplayId,
                            VirtualLauncher.appDisplayId,
                            VirtualLauncher.appHiDisplayId,
                            VirtualLauncher.mapDisplayId,
                        )
                    ) {
                        return
                    }
                    drivers.remove(activity)?.close()
                    drivers[activity] = Driver(activity)
                    if (id != VirtualLauncher.dockDisplayId) CarKeys.focusDock(false)
                    refreshInputTarget()
                }

                override fun onActivityPaused(activity: Activity) {
                    drivers.remove(activity)?.close()
                }

                override fun onActivityDestroyed(activity: Activity) {
                    drivers.remove(activity)?.close()
                }

                override fun onActivityCreated(
                    activity: Activity,
                    state: Bundle?,
                ) = Unit

                override fun onActivityStarted(activity: Activity) = Unit

                override fun onActivityStopped(activity: Activity) = Unit

                override fun onActivitySaveInstanceState(
                    activity: Activity,
                    state: Bundle,
                ) = Unit
            },
        )
    }

    /** 在 Dock 与当前应用屏之间接续焦点；系统不会跨两个虚拟屏自动做 focusSearch。 */
    private fun cross(
        from: Driver,
        forward: Boolean,
    ): Boolean {
        val toDock = from.display != VirtualLauncher.dockDisplayId
        val target =
            if (toDock) {
                VirtualLauncher.dockDisplayId
            } else {
                if (VirtualLauncher.mapFullscreen) {
                    VirtualLauncher.mapDisplayId
                } else {
                    VirtualLauncher.activeAppDisplayId()
                }
            }
        if (target <= 0) return false
        CarKeys.focusDock(toDock)
        drivers.values.firstOrNull { it.display == target }?.edge(forward)
        return true
    }

    /** 窗口回调只做轻量 UI 操作；按键注入和应用启动仍由后台通路执行。 */
    private class Driver(
        val activity: Activity,
    ) {
        val display = activity.display?.displayId ?: -1
        private val root = activity.window.decorView as ViewGroup
        private val original = activity.window.callback
        private var inputActive = false
        private var highlighted: View? = null
        private var outline: GradientDrawable? = null
        private val focusListener = ViewTreeObserver.OnGlobalFocusChangeListener { _, focused -> highlight(focused) }
        private val layoutListener =
            ViewTreeObserver.OnGlobalLayoutListener {
                prepare(root)
                highlighted?.let { outline?.setBounds(0, 0, it.width, it.height) }
            }
        private val callback =
            object : Window.Callback by original {
                override fun dispatchKeyEvent(event: KeyEvent): Boolean {
                    refreshInputTarget()
                    if (event.keyCode == KeyEvent.KEYCODE_BACK && display == VirtualLauncher.dockDisplayId) {
                        // Dock 常驻，但返回必须能退出其焦点，继续操作此前的应用。
                        if (event.action == KeyEvent.ACTION_UP && !event.isCanceled) CarKeys.focusDock(false)
                        return true
                    }
                    val direction =
                        when (event.keyCode) {
                            KeyEvent.KEYCODE_TAB -> if (event.isShiftPressed) View.FOCUS_BACKWARD else View.FOCUS_FORWARD
                            KeyEvent.KEYCODE_DPAD_LEFT -> View.FOCUS_LEFT
                            KeyEvent.KEYCODE_DPAD_RIGHT -> View.FOCUS_RIGHT
                            KeyEvent.KEYCODE_DPAD_UP -> View.FOCUS_UP
                            KeyEvent.KEYCODE_DPAD_DOWN -> View.FOCUS_DOWN
                            else -> 0
                        }
                    val focused = activity.currentFocus
                    if (direction != 0 && focused !is EditText && focused !is SeekBar) {
                        if (event.action == KeyEvent.ACTION_DOWN) move(direction)
                        return true
                    }
                    if (event.action == KeyEvent.ACTION_DOWN && event.keyCode == KeyEvent.KEYCODE_DPAD_CENTER && focused == null) {
                        edge(true)
                    }
                    return original.dispatchKeyEvent(event)
                }

                override fun onWindowFocusChanged(hasFocus: Boolean) {
                    original.onWindowFocusChanged(hasFocus)
                    highlight(if (hasFocus) activity.currentFocus else null)
                }
            }

        init {
            prepare(root)
            activity.window.callback = callback
            root.viewTreeObserver.addOnGlobalFocusChangeListener(focusListener)
            root.viewTreeObserver.addOnGlobalLayoutListener(layoutListener)
        }

        /** 恢复窗口原回调，移除 overlay，避免退出设置后引用旧页面。 */
        fun close() {
            if (activity.window.callback === callback) activity.window.callback = original
            root.viewTreeObserver.removeOnGlobalFocusChangeListener(focusListener)
            root.viewTreeObserver.removeOnGlobalLayoutListener(layoutListener)
            highlight(null)
        }

        /** 只切换输入高亮，不清除页面原焦点，避免跨屏时自动聚焦到另一项。 */
        fun setInputActive(active: Boolean) {
            if (inputActive == active) return
            inputActive = active
            highlight(if (active) activity.currentFocus else null)
        }

        /** 切换到另一块屏时，从其首个或末个可操作控件接续。 */
        fun edge(forward: Boolean) {
            val host = activity as? RotaryFocusHost
            if (host != null) {
                host.focusRotaryEdge(forward)
            } else {
                controls().let { if (forward) it.firstOrNull() else it.lastOrNull() }?.requestFocus()
            }
        }

        /** Tab 走逻辑顺序，四向键走空间顺序；边界继续到 Dock/应用。 */
        private fun move(direction: Int) {
            val forward = direction != View.FOCUS_BACKWARD && direction != View.FOCUS_LEFT && direction != View.FOCUS_UP
            val host = activity as? RotaryFocusHost
            if (host != null && direction in listOf(View.FOCUS_FORWARD, View.FOCUS_BACKWARD)) {
                if (!host.moveRotaryFocus(forward)) cross(this, forward)
                return
            }
            val current = activity.currentFocus
            if (current == null) {
                edge(forward)
                return
            }
            val next = current.focusSearch(direction)
            val all = controls()
            val before = all.indexOf(current)
            val after = all.indexOf(next)
            val wrapped =
                direction == View.FOCUS_FORWARD &&
                    after in 0..before ||
                    direction == View.FOCUS_BACKWARD &&
                    after >= before &&
                    before >= 0
            if (next != null && next !== current && !wrapped && next.requestFocus(direction)) return
            cross(this, forward)
        }

        /** Native focusSearch 可滚动 ScrollView/RecyclerView；不要按当前可视矩形过滤控件。 */
        private fun controls(): List<View> = root.getFocusables(View.FOCUS_FORWARD).filter { it !== root && it.isEnabled && it.isShown }

        /** 自绘的应用格子、菜单行和图片按钮需要显式声明可聚焦。 */
        private fun prepare(view: View) {
            if (view.isClickable && view.isEnabled) view.isFocusable = true
            if (view is ViewGroup) for (index in 0 until view.childCount) prepare(view.getChildAt(index))
        }

        /** Overlay 描边不替换原有背景/选中态，焦点与当前设置菜单的选中项可同时辨认。 */
        private fun highlight(view: View?) {
            outline?.let { highlighted?.overlay?.remove(it) }
            highlighted = view?.takeIf { inputActive && root.hasWindowFocus() }
            outline =
                highlighted?.let {
                    GradientDrawable().apply {
                        setColor(Color.TRANSPARENT)
                        cornerRadius = 12 * it.resources.displayMetrics.density
                        setStroke((3 * it.resources.displayMetrics.density).toInt().coerceAtLeast(2), 0xFF64D2FF.toInt())
                        setBounds(0, 0, it.width, it.height)
                        it.overlay.add(this)
                    }
                }
        }
    }
}
