package com.keepasskey.app.security

import android.content.res.Configuration
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
 * ## 遮挡触摸过滤（`ISSUE-P2-245`，2026-09-21 补接线）
 *
 * `FLAG_SECURE` 只防**截屏 / 录屏 / Recents 预览**，不防**点击劫持（tapjacking）**——后者需要
 * `View.setFilterTouchesWhenObscured`，而它与 `FLAG_SECURE` 是**同形的窗口级缺口**：过滤同样是
 * **视图 / 窗口级**属性，不会从 Activity 窗口传播到对话框窗口。故本包装在**同一个** [DisposableEffect]
 * 内对同一落点叠加两者（两者正交：一个挡截屏、一个丢遮挡态触摸）。
 *
 * - **目标取对话框窗口的 `decorView`**：与 [FlagSecureGuard.applyObscuredTouchFilter] 对 Activity 窗口的
 *   既有口径一致（同一威胁面用同一施加点，便于审计与复查）；`decorView` 即窗口根视图
 *   （`DecorView`，`ViewGroup` 子类）。
 * - **过滤覆盖整棵子树**：javadoc 表述为「the framework will discard touches …… whenever the view's
 *   window is obscured by another visible window at the touched location」（本地 SDK 源 `android-36.1` 的
 *   `View.java` 类级 Security 段与 `getFilterTouchesWhenObscured()` 逐字直读）。
 *   「连同后代一并丢弃」的**实现**依据（非 javadoc 措辞）：`View.onFilterTouchEventForSecurity`
 *   在视图自身带 `FILTER_TOUCHES_WHEN_OBSCURED` 且事件带 `MotionEvent.FLAG_WINDOW_IS_OBSCURED` 时返回
 *   false（`View.java:16854-16860`），而 `ViewGroup.dispatchTouchEvent` 以该返回值**包住**整段子视图派发
 *   （`ViewGroup.java:2658`）⇒ 在 `decorView` 这类 `ViewGroup` 根上开启即等于丢弃整窗的遮挡态触摸。
 *   **不**声称「部分遮挡也计入」——遮挡粒度由平台判定，本包装只保证接线已施加。
 * - **无权限、无 API 版本门槛**：`FILTER_TOUCHES_WHEN_OBSCURED` 自 API 1 即有；本地 SDK 源的
 *   `setFilterTouchesWhenObscured` / `getFilterTouchesWhenObscured` **无** `@RequiresApi` /
 *   `@RequiresPermission` 标注（直读核实）。
 * - **为何不用 `Window.setHideOverlayWindows(true)`**：① 该 API 的语义是「阻止非系统悬浮窗**绘制**在本窗口
 *   之上」（javadoc 原文 "Prevent non-system overlay windows from being drawn on top of this window"，
 *   `Window.java:1151-1153`；须持 `HIDE_OVERLAY_WINDOWS` 权限，本仓已在 Manifest 声明）——它抑制的是
 *   **绘制 / 可见性**，**不**等价于「触摸一定被丢弃」；② 依 `docs/architecture/已知工程限界.md` §3.3 的既有
 *   结论，它只阻断**新**覆盖、**不**解除**已存在**的遮挡窗口。故沿用 `BaseCredentialActivity` 体系的同口径：
 *   触摸过滤为**独立施加**，不与 `setHideOverlayWindows` 互相替代。
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
 * [`PopupProperties(securePolicy = SecureFlagPolicy.SecureOn)`](https://developer.android.com/reference/kotlin/androidx/compose/ui/window/securePolicy)
 * （`DropdownMenu` / `ExposedDropdownMenuBox` 的 `properties` 参数）。
 *
 * **ISSUE-P3-79 盘点结论（2026-09-15 逐点直读源码核实，**无需接线**）**：
 * 全仓 Popup 调用点**恰好 4 处**——`VaultListTopBars`（顶栏溢出，1 项）、`VaultGroupRow`（分组操作，3 项）、
 * `EntryDetailTopBarSections`（条目操作，3 项；§194 前该菜单在 `EntryDetailTopBar` 内）、`CloudSyncComponents`（`ExposedDropdownMenuBox` + `DropdownMenu`，
 * 1 项 × provider 数）。上述 **8 个 `DropdownMenuItem` 全部为静态动作文案 / provider 名称**
 * （彻底退出应用 / 重命名 / 更改图标 / 删除分组 / 移动到分组 / 删除条目 / 删除共享图标 /
 * WebDAV·S3 provider 名与描述），**无任何口令、TOTP、密钥或用户数据插值**；
 * 全仓亦无其它 `Popup(` / `TooltipBox(` / `ModalBottomSheet(` 调用点。
 *
 * ⇒ **不接线**（避免「全量加 flag」的过度改动）。**接线条件（须遵守）**：一旦任一 Popup
 * 的菜单项开始渲染**凭据类内容**（口令 / TOTP / 密钥 / 用户名等用户数据插值），
 * **必须**为该调用点补 `PopupProperties(securePolicy = SecureFlagPolicy.SecureOn)`；
 * 该条件由 `PopupSecureFlagInventoryTest` 以「调用点清单锁 + 菜单块内敏感记号扫描」自动守护——
 * 新增 Popup 调用点或菜单内出现敏感记号都会**当场报红**。
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
 * 真正的 `addFlags` 需要真实窗口（window token / WindowManager 服务），已由设备侧用例
 * `app/src/androidTest/java/com/keepasskey/app/security/DialogWindowHardeningDeviceTest.kt` 在真机上实证
 * （三条断言：`decorView.filterTouchesWhenObscured == true`、窗口 `FLAG_SECURE` 位已置、
 * 关闭对话框后 `FLAG_SECURE` 已清）。
 * **该用例证明的是「遮罩与过滤已真实施加到对话框窗口」，不是「遮挡窗口的触摸确实被丢弃」**——
 * 后者需要真机上有真实的遮挡窗口配合，仍为手工冒烟项（`ISSUE-P2-245` AC③ 的如实边界）。
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
        // 遮挡触摸过滤（ISSUE-P2-245）：与 FLAG_SECURE 同形的窗口级缺口，故在同一落点施加；
        // save/restore 语义同 ApplyObscuredTouchFilter。原值读得到即等价于「窗口已解析」
        //（decorView 一经 window.decorView 取用即存在，getFilterTouchesWhenObscured() 返回非空 Boolean），
        // 故该非空守卫同时也是 fail-safe：取不到窗口时不写入、也不回写任何视图。
        val dialogDecorView = dialogWindow?.decorView
        val previousFilterTouchesWhenObscured = dialogDecorView?.filterTouchesWhenObscured
        if (previousFilterTouchesWhenObscured != null) {
            dialogDecorView?.filterTouchesWhenObscured = true
        }
        onDispose {
            if (previousFilterTouchesWhenObscured != null) {
                dialogDecorView?.filterTouchesWhenObscured = previousFilterTouchesWhenObscured
            }
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
 *
 * 可见性为 `internal`（`ISSUE-P2-245`，2026-09-21）：设备侧用例
 * `DialogWindowHardeningDeviceTest` 需以**同一解析口径**取到对话框窗口（否则用例断言的是
 * 「另一种取窗口方式的结果」，无法证成生产路径的接线），故不再收为 `private`。
 */
internal fun View.dialogWindowOrNull(): Window? {
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

// IDE 预览标注：仅开发期在 Android Studio Preview 面板可见，不参与运行时 UI。
// 预览环境的 LocalView 父链不含 DialogWindowProvider，包装按 fail-safe 静默不动作（不崩溃、不改窗口）
@androidx.compose.ui.tooling.preview.Preview(name = "受保护对话框内容 - 浅色", showBackground = true)
@androidx.compose.ui.tooling.preview.Preview(name = "受保护对话框内容 - 深色", showBackground = true, uiMode = Configuration.UI_MODE_NIGHT_YES)
@Composable
internal fun SecureDialogPreview() {
    com.keepasskey.app.ui.theme.KeePasskeyTheme {
        SecureDialog {
            androidx.compose.material3.Text(text = "预览受保护对话框内容")
        }
    }
}
