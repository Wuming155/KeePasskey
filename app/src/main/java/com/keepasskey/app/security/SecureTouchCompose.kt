package com.keepasskey.app.security

import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.ui.platform.LocalView

/**
 * Compose 侧遮挡触摸过滤（ISSUE-P2-09 / ZT-14）。
 *
 * 在敏感界面的根节点调用后，将宿主 ComposeView 的 `filterTouchesWhenObscured` 置为 true，
 * 使系统在窗口被其它窗口部分/完全遮挡时丢弃整棵 Compose 子树的触摸事件（点击劫持防护）。
 * 离开组合时恢复原值，避免污染非敏感界面。
 *
 * 说明：Compose 无直接等价的 Modifier，官方做法即在宿主 View 上开启该标志，
 * 故以「组合期副作用 + 退出还原」实现。
 */
@Composable
fun ApplyObscuredTouchFilter() {
    val view = LocalView.current
    DisposableEffect(view) {
        val previous = view.filterTouchesWhenObscured
        view.filterTouchesWhenObscured = true
        onDispose { view.filterTouchesWhenObscured = previous }
    }
}
