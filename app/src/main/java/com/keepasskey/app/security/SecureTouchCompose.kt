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
 *
 * ## 与另两处施加点的分工（同一威胁面、不同窗口）
 *
 * 遮挡触摸过滤是**视图 / 窗口级**属性，不会跨窗口传播，故按窗口类型各有落点：
 * - **Activity 窗口**：[FlagSecureGuard.applyObscuredTouchFilter] 在 `attach` 时对 `decorView` 恒常开启
 *   （不随防截屏开关变化），独立凭据 / 自动填充 Activity 另有同等施加；
 * - **对话框窗口**（Compose `AlertDialog` / `Dialog` 各自建窗）：[SecureDialogWindowEffect]
 *   在本窗 `decorView` 上开启，**必须写在对话框自身的内容槽位内**；
 * - **本函数**：把过滤绑定到页面根节点的组合生命周期。宿主 Activity 已自行施加时它是同一
 *   落点的加固性重复；页面被未自行加固的承载复用时，它是该页唯一的施加点。
 *
 * 平台的遮挡判定本身（`MotionEvent.FLAG_WINDOW_IS_OBSCURED` / `FLAG_WINDOW_IS_PARTIALLY_OBSCURED`）
 * 由系统完成，本仓不另建事件位判定内核：开旗后由 `View.onFilterTouchEventForSecurity` 丢弃事件。
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
