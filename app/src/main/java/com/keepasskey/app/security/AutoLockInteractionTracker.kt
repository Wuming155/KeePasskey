package com.keepasskey.app.security

import android.app.Activity
import android.app.Application
import android.os.Bundle
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.Window
import android.view.inputmethod.CompletionInfo
import android.view.inputmethod.CorrectionInfo
import android.view.inputmethod.InputConnection
import android.view.inputmethod.InputConnectionWrapper
import android.view.inputmethod.InputContentInfo

/**
 * ENG-20261028-04：把窗口实际受理的触摸 / 按键事件与软键盘编辑操作接入 [AutoLockManager.onUserInteraction]。
 *
 * 接入点：
 * 1. [Window.Callback]：普通 Activity 窗口与独立对话框窗口的 `DecorView` 会把触摸与按键派发给
 *    `window.callback`。Activity 窗口经 [Application.ActivityLifecycleCallbacks] 自动包装，
 *    独立对话框（Compose `Dialog` / `AlertDialog`）经 [attachWindow] 包装，未来新增的
 *    Activity / 对话框均自动生效。
 * 2. 软键盘 [InputConnection]：通过 [wrapInputConnection] 包装输入连接，在软键盘真实文本输入、
 *    拼音候选组合、退格删除等编辑操作时派发交互刷新；纯只读查询（如 [InputConnection.getTextBeforeCursor]）
 *    不刷新，严格维持「阅读不刷新、编辑才保活」的语义。
 *
 * 只把「一次交互的开始」计入刷新，避免手势与高频输入过程中的事件反复重排定时器：
 * - 触摸：`ACTION_DOWN`（`ACTION_MOVE` / `ACTION_UP` 不重复刷新）；
 * - 按键：`ACTION_DOWN` 且 `repeatCount == 0`（长按重复不重复刷新）；
 * - 轨迹球：`ACTION_DOWN`；
 * - 通用运动：`ACTION_DOWN` / `ACTION_SCROLL`（鼠标点击、滚轮滚动）；
 * - 软键盘：仅针对编辑修改类操作刷新，只读查询不刷新。
 *
 * 另设最小转发间隔（250ms），采样带来的刷新滞后远小于设置页最小正档（30 秒），
 * 不改变闲置判定的时间口径。
 *
 * 锁定态是否刷新由 [ForegroundIdleLockPolicy.shouldRefreshOnInteraction] 在
 * [AutoLockManager.onUserInteraction] 内裁决，本类不再重复判断。
 */
internal class AutoLockInteractionTracker(
    private val onInteraction: () -> Unit,
    private val nowMillis: () -> Long = System::currentTimeMillis
) : Application.ActivityLifecycleCallbacks {

    private var lastForwardedAtMillis: Long? = null

    /** 进程级注册（与 [AutoLockManager.initialize] 同一冷启动点；重复注册由调用方幂等守卫承担）。 */
    fun install(application: Application) {
        application.registerActivityLifecycleCallbacks(this)
    }

    override fun onActivityCreated(activity: Activity, savedInstanceState: Bundle?) {
        // Activity 已在 attach 阶段装好 PhoneWindow 并以自身为 callback；此处只替换为转发包装，
        // 其余全部方法原样委派，不改变 Activity / FragmentActivity 的输入处理语义。
        activity.window?.let(::attachWindow)
    }

    override fun onActivityStarted(activity: Activity) = Unit

    override fun onActivityResumed(activity: Activity) = Unit

    override fun onActivityPaused(activity: Activity) = Unit

    override fun onActivityStopped(activity: Activity) = Unit

    override fun onActivitySaveInstanceState(activity: Activity, outState: Bundle) = Unit

    override fun onActivityDestroyed(activity: Activity) = Unit

    /**
     * 测试 / 诊断观察点：当有窗口成功接入本转发器时派发通知（传承载窗口）。
     * 仅供 instrumented 测试或调试链路捕获真实被测窗口，不承担业务分派。
     */
    internal var onWindowAttached: ((Window) -> Unit)? = null

    /**
     * 测试 / 诊断观察点：当有窗口解除接入时派发通知（传承载窗口）。
     */
    internal var onWindowDetached: ((Window) -> Unit)? = null

    /**
     * 将任意窗口（Activity 或独立 Compose 对话框等）的 [Window.Callback] 接入交互转发。
     *
     * 接入点仍为 [Window.Callback]：各窗口的 `DecorView` 会把触摸与按键派发给
     * `window.callback`。独立对话框（Compose `Dialog` / `AlertDialog`）拥有独立的
     * `PhoneWindow`，其输入不经宿主 Activity 窗口，需单独挂接本方法。
     * 幂等包装：若已是 [InteractionWindowCallback] 则直接跳过。
     */
    fun attachWindow(window: Window) {
        val current = window.callback ?: return
        if (current is InteractionWindowCallback) {
            current.refCount++
            return
        }
        window.callback = InteractionWindowCallback(current)
        onWindowAttached?.invoke(window)
    }

    /**
     * 撤销特定窗口上的交互转发包装（引用计数递减归零时还原原有委托）。
     */
    fun detachWindow(window: Window) {
        val current = window.callback as? InteractionWindowCallback ?: return
        current.refCount--
        if (current.refCount <= 0) {
            window.callback = current.delegate
            onWindowDetached?.invoke(window)
        }
    }

    /**
     * 供软键盘 [InputConnection] 或 Compose 文本输入子系统上报交互。
     */
    fun notifyInteraction() {
        forwardInteraction()
    }

    /**
     * 将目标 [InputConnection] 包装为具备前台闲置刷新能力的代理。
     * 只对文本编辑操作触发刷新，纯只读查询不刷新。
     */
    fun wrapInputConnection(target: InputConnection): InputConnection {
        if (target is AutoLockInputConnectionWrapper) return target
        return AutoLockInputConnectionWrapper(target, ::notifyInteraction)
    }

    private inner class InteractionWindowCallback(
        val delegate: Window.Callback
    ) : Window.Callback by delegate {
        var refCount: Int = 1

        override fun dispatchTouchEvent(event: MotionEvent): Boolean {
            if (event.actionMasked == MotionEvent.ACTION_DOWN) forwardInteraction()
            return delegate.dispatchTouchEvent(event)
        }

        override fun dispatchKeyEvent(event: KeyEvent): Boolean {
            if (event.action == KeyEvent.ACTION_DOWN && event.repeatCount == 0) forwardInteraction()
            return delegate.dispatchKeyEvent(event)
        }

        override fun dispatchTrackballEvent(event: MotionEvent): Boolean {
            if (event.actionMasked == MotionEvent.ACTION_DOWN) forwardInteraction()
            return delegate.dispatchTrackballEvent(event)
        }

        override fun dispatchGenericMotionEvent(event: MotionEvent): Boolean {
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN, MotionEvent.ACTION_SCROLL -> forwardInteraction()
            }
            return delegate.dispatchGenericMotionEvent(event)
        }
    }

    private fun forwardInteraction() {
        val now = nowMillis()
        val last = lastForwardedAtMillis
        if (last != null && now >= last && now - last < MIN_FORWARD_INTERVAL_MILLIS) return
        lastForwardedAtMillis = now
        onInteraction()
    }

    private companion object {
        const val MIN_FORWARD_INTERVAL_MILLIS = 250L
    }
}

/**
 * 闲置自动锁定 InputConnection 代理包装。
 *
 * 拦截软键盘各类文本编辑与提交方法，上报 [onEditInteraction] 以重排前台闲置定时器。
 * 纯读取操作（如 [getTextBeforeCursor]、[getSelectedText] 等）原样转发且不上报，
 * 严格维持「阅读不刷新、编辑才保活」的语义。
 */
internal class AutoLockInputConnectionWrapper(
    target: InputConnection,
    private val onEditInteraction: () -> Unit
) : InputConnectionWrapper(target, false) {

    override fun commitText(text: CharSequence?, newCursorPosition: Int): Boolean {
        onEditInteraction()
        return super.commitText(text, newCursorPosition)
    }

    override fun setComposingText(text: CharSequence?, newCursorPosition: Int): Boolean {
        onEditInteraction()
        return super.setComposingText(text, newCursorPosition)
    }

    override fun setComposingRegion(start: Int, end: Int): Boolean {
        onEditInteraction()
        return super.setComposingRegion(start, end)
    }

    override fun finishComposingText(): Boolean {
        onEditInteraction()
        return super.finishComposingText()
    }

    override fun deleteSurroundingText(beforeLength: Int, afterLength: Int): Boolean {
        onEditInteraction()
        return super.deleteSurroundingText(beforeLength, afterLength)
    }

    override fun deleteSurroundingTextInCodePoints(beforeLength: Int, afterLength: Int): Boolean {
        onEditInteraction()
        return super.deleteSurroundingTextInCodePoints(beforeLength, afterLength)
    }

    override fun setSelection(start: Int, end: Int): Boolean {
        onEditInteraction()
        return super.setSelection(start, end)
    }

    override fun performEditorAction(editorAction: Int): Boolean {
        onEditInteraction()
        return super.performEditorAction(editorAction)
    }

    override fun performContextMenuAction(id: Int): Boolean {
        onEditInteraction()
        return super.performContextMenuAction(id)
    }

    override fun sendKeyEvent(event: KeyEvent?): Boolean {
        if (event?.action == KeyEvent.ACTION_DOWN && event.repeatCount == 0) {
            onEditInteraction()
        }
        return super.sendKeyEvent(event)
    }

    override fun commitCompletion(text: CompletionInfo?): Boolean {
        onEditInteraction()
        return super.commitCompletion(text)
    }

    override fun commitCorrection(correctionInfo: CorrectionInfo?): Boolean {
        onEditInteraction()
        return super.commitCorrection(correctionInfo)
    }

    override fun commitContent(
        inputContentInfo: InputContentInfo,
        flags: Int,
        opts: Bundle?
    ): Boolean {
        onEditInteraction()
        return super.commitContent(inputContentInfo, flags, opts)
    }

    override fun performPrivateCommand(action: String?, data: Bundle?): Boolean {
        onEditInteraction()
        return super.performPrivateCommand(action, data)
    }
}
