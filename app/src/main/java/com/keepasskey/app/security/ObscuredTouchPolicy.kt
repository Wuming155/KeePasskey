package com.keepasskey.app.security

import android.view.View

/**
 * 遮挡触摸判定工具（ISSUE-P2-09 / ZT-14）。
 *
 * 对应官方 `View.setFilterTouchesWhenObscured(true)` / `Window.setFilterTouchesWhenObscured(true)`：
 * 当窗口被其它窗口部分或完全遮挡时，系统会在触摸事件上携带
 * `MotionEvent.FLAG_WINDOW_IS_OBSCURED` / `FLAG_WINDOW_IS_PARTIALLY_OBSCURED`，
 * 过滤开启后该类事件被丢弃，抵御 overlay 点击劫持（tapjacking）。
 *
 * 常量按官方位值显式声明（与 `android.view.MotionEvent` 对齐），使判定内核零 Android 依赖、
 * 可在 JVM 上直接单测（与 `AutofillFieldScanner` 解耦 `android.text.InputType` 的做法一致）。
 */
object ObscuredTouchPolicy {

    /** 与 android.view.MotionEvent.FLAG_WINDOW_IS_OBSCURED 对齐（完全遮挡） */
    const val FLAG_WINDOW_IS_OBSCURED = 0x00000001

    /** 与 android.view.MotionEvent.FLAG_WINDOW_IS_PARTIALLY_OBSCURED 对齐（部分遮挡） */
    const val FLAG_WINDOW_IS_PARTIALLY_OBSCURED = 0x00000002

    private const val OBSCURED_MASK = FLAG_WINDOW_IS_OBSCURED or FLAG_WINDOW_IS_PARTIALLY_OBSCURED

    /** 触摸事件是否来自被遮挡窗口（完全或部分遮挡均视为可疑） */
    fun isObscured(eventFlags: Int): Boolean = (eventFlags and OBSCURED_MASK) != 0

    /** 是否应丢弃该触摸（敏感视图的安全语义：遮挡即丢弃） */
    fun shouldDropTouch(eventFlags: Int): Boolean = isObscured(eventFlags)
}

/**
 * 为 View 开启遮挡触摸过滤（View 级兜底；窗口级设置在 FlagSecureGuard 中统一施加）。
 */
fun View.hardenAgainstObscuredTouches() {
    filterTouchesWhenObscured = true
}
