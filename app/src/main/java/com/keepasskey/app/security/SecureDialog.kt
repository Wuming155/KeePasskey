package com.keepasskey.app.security

import android.view.View
import android.view.ViewParent
import android.view.Window
import android.view.WindowManager
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.window.DialogWindowProvider

/**
 * 敏感对话框窗口的防截屏包装（F-13 同批缺陷：对话框窗口的 FLAG_SECURE 缺口）。
 *
 * ## 缺口
 *
 * `FLAG_SECURE` 是**窗口级（window-scoped）**属性，不会从 Activity 窗口传播到该 Activity
 * 创建出来的其它窗口。官方 Assist 指南明确要求：
 *
 * > "You must set FLAG_SECURE explicitly for every window created by the activity,
 * > including dialogs."
 * > — <https://developer.android.com/training/articles/assistant#excluding_views>（"Excluding views from assistants"）
 *
 * 参见 [WindowManager.LayoutParams.FLAG_SECURE](https://developer.android.com/reference/android/view/WindowManager.LayoutParams#FLAG_SECURE)。
 * 本仓既有 `FLAG_SECURE` 施加点（`MainActivity` 的 [FlagSecureGuard]、自动填充 / 通行密钥
 * 等独立 Activity）**全部落在 Activity window**；而 Compose 的 `AlertDialog` /
 * `ModalBottomSheet` 由 `Dialog` 创建**独立窗口**（`DialogLayout` 托管自己的 composition），
 * 内含主密码输入、子库凭据、附件预览与修订差异的对话框因此**完全没有遮蔽**——
 * 系统截图 / 录屏 / Recents 预览 / Assist 取树均可读到明文。
 *
 * ## 用法与语义
 *
 * 把本包装放在**对话框自身的内容槽位内**（如 `AlertDialog` 的 `text = { SecureDialog { ... } }`）；
 * 内容槽位已是一整块时可直接调用同实现的 [SecureDialogWindowEffect]（避免无意义的大段缩进改动）：
 * 两者都经 [LocalView] 的父链取到承载本对话框的窗口（[DialogWindowProvider]，由 Compose 的
 * `DialogLayout` 实现），进入时 `addFlags(FLAG_SECURE)`，`onDispose` 时 `clearFlags`——
 * 与 [FlagSecureGuard] 的「动态施加 / 动态撤销」语义一致，不会把 flag 泄漏给其它窗口
 * （每个对话框窗口独立，且仅撤销本包装自己施加的那一次）。
 *
 * 放在 `AlertDialog(...)` 之外包裹整个调用是**无效**的：那时 [LocalView] 属于 Activity 窗口，
 * 取不到对话框 provider，本包装会按 fail-safe 静默不动作（不崩溃、不误改 Activity 窗口）。
 *
 * ## 与 `flagSecureEnabled` 开关的关系（有意的 fail-closed 偏离，勿误当缺陷）
 *
 * 本包装**无条件**施加 dialog 窗口的 `FLAG_SECURE`，不读取 `SettingsRepository` 的用户开关
 * （[FlagSecureGuard] 对 Activity 窗口是「锁定态强制 ∨ 开关」的动态模型）。理由：这四类对话框
 * 的敏感度最高（主密钥修改 / 子库凭据 / 附件明文预览 / 密码明文差异），且用户关闭开关时已在
 * 设置页履行过风险确认。若产品要求与 Activity 窗口完全一致的解除语义，需把
 * `SettingsUiState.flagSecureEnabled` 下传至各对话框后再接入；本批不改（登记为后续项）。
 *
 * ## 未能覆盖：`DropdownMenu` / `Popup`
 *
 * Compose 的 Popup 窗口由 `PopupLayout` 承载，它**不实现** [DialogWindowProvider]，
 * 因此本包装对其恒为 fail-safe 空操作。Popup 系窗口的官方接线是
 * [`PopupProperties(securePolicy = SecureFlagPolicy.SecureOn)`](https://developer.android.com/reference/kotlin/androidx/compose/ui/window/SecureFlagPolicy)
 * （`DropdownMenu` / `ExposedDropdownMenuBox` 的 `properties` 参数）；本批不改动无关 UI
 * 的调用点，仅在此登记为后续接线项。
 *
 * 官方等价 API：Compose 自 1.0 起在 `DialogProperties` 上提供
 * [`securePolicy`](https://developer.android.com/reference/kotlin/androidx/compose/ui/window/DialogProperties#securePolicy())
 * （`SecureFlagPolicy.SecureOn` 即强制对话框窗口携带 `FLAG_SECURE`）。本包装与它语义等价，
 * 但只依赖稳定 API（`View` / `WindowManager` / [DialogWindowProvider]），
 * 且能覆盖不接收 `DialogProperties` 的对话框宿主，故作为本仓的统一入口。
 *
 * ## 可测性
 *
 * flag 施加/撤销的裁决抽为纯逻辑 [SecureDialogFlagPolicy]（JVM 单测全覆盖）；
 * 真正的 `addFlags` 需要真实窗口（window token / WindowManager 服务），**留待设备侧验证**。
 */
@Composable
internal fun SecureDialog(content: @Composable () -> Unit) {
    SecureDialogWindowEffect()
    content()
}

/**
 * [SecureDialog] 的等价「无内容形态」：内容槽位本身已是一整块（再包一层会产生无意义的大段
 * 缩进改动）时直接调用即可，与 [SecureDialog] 共用同一实现（后者即「本函数 + content」）。
 *
 * 必须在**对话框自身的内容槽位内**调用；在 `AlertDialog(...)` 之外调用取不到对话框窗口，
 * 按 fail-safe 静默不动作。
 */
@Composable
internal fun SecureDialogWindowEffect() {
    val view = LocalView.current
    DisposableEffect(view) {
        val dialogWindow = view.dialogWindowOrNull()
        val addedByThisWrapper =
            SecureDialogFlagPolicy.onEnter(dialogWindowResolved = dialogWindow != null) ==
                SecureDialogFlagAction.ADD_SECURE
        if (addedByThisWrapper) {
            dialogWindow?.addFlags(WindowManager.LayoutParams.FLAG_SECURE)
        }
        onDispose {
            // 仅撤销本包装自己施加的 flag；取不到 provider（fail-safe 空操作）时不触碰任何窗口
            if (SecureDialogFlagPolicy.onDispose(addedByThisWrapper) ==
                SecureDialogFlagAction.CLEAR_SECURE
            ) {
                dialogWindow?.clearFlags(WindowManager.LayoutParams.FLAG_SECURE)
            }
        }
    }
}

/**
 * 自 [View] 的父链向上查找承载当前 composition 的对话框窗口。
 *
 * Compose 的对话框 composition 由 `DialogLayout`（`AbstractComposeView` 子类，
 * 实现 [DialogWindowProvider]）托管，故 `LocalView.current.parent` 即命中的最近祖先；
 * 逐级上溯是为了兼容宿主层级变化（例如内容被再次包进容器）。
 *
 * 非 Dialog 的独立窗口（Popup / `DropdownMenu` 的 `PopupLayout`）**不存在** provider，
 * 返回 null 由调用方 fail-safe 处理：宁可少设一个 flag，也不得崩溃或误改 Activity 窗口。
 */
private fun View.dialogWindowOrNull(): Window? {
    var parent: ViewParent? = this.parent
    while (parent != null) {
        if (parent is DialogWindowProvider) return parent.window
        parent = (parent as? View)?.parent
    }
    return null
}

/** 对话框窗口 `FLAG_SECURE` 的施加动作（纯逻辑枚举，JVM 可测）。 */
internal enum class SecureDialogFlagAction {
    /** 本次需 `addFlags(FLAG_SECURE)` */
    ADD_SECURE,

    /** 本次需 `clearFlags(FLAG_SECURE)` */
    CLEAR_SECURE,

    /** 不动作（fail-safe：窗口未解析，或本包装未施加过） */
    NONE
}

/**
 * 对话框 `FLAG_SECURE` 施加 / 撤销的裁决内核（零 Android 依赖，JVM 单测全覆盖）。
 *
 * 两条不变式：
 * 1. **fail-safe**：取不到对话框窗口（`dialogWindowResolved == false`）时不动作，
 *    绝不为了让 flag「看起来生效」而改写 Activity 窗口——那会破坏用户的开关键语义；
 * 2. **只撤销自己的**：[onDispose] 仅在本次确实施加过时清理，保证不会误清他人
 *    （Activity 守卫 / 其它对话框）设置的 `FLAG_SECURE`。
 */
internal object SecureDialogFlagPolicy {

    /** 对话框内容进入组合：解析到窗口才施加。 */
    fun onEnter(dialogWindowResolved: Boolean): SecureDialogFlagAction =
        if (dialogWindowResolved) SecureDialogFlagAction.ADD_SECURE else SecureDialogFlagAction.NONE

    /** 对话框内容离开组合：仅撤销本包装施加过的那一次。 */
    fun onDispose(addedByThisWrapper: Boolean): SecureDialogFlagAction =
        if (addedByThisWrapper) SecureDialogFlagAction.CLEAR_SECURE else SecureDialogFlagAction.NONE
}
