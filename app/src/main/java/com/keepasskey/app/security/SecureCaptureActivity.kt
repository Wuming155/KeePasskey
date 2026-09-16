package com.keepasskey.app.security

import android.os.Bundle
import android.view.WindowManager
import com.journeyapps.barcodescanner.CaptureActivity

/**
 * TOTP 二维码扫码取景窗口（ISSUE-P3-71）。
 *
 * 继承 zxing 默认 [CaptureActivity]，在首帧即强制施加：
 * - [WindowManager.LayoutParams.FLAG_SECURE]：取景画面（可能含密钥种子二维码）禁止截屏 / 录屏 /
 *   多任务缩略图捕获——默认库窗口游离于 [FlagSecureGuard]（仅 attach 至 MainActivity 与
 *   [com.keepasskey.app.passkey.BaseCredentialActivity] 体系）之外，构成防截屏盲区；
 * - `setHideOverlayWindows(true)`：屏蔽其它应用 TYPE_APPLICATION_OVERLAY 悬浮窗覆盖（反 overlay
 *   攻击），与站内其余敏感窗口的加固策略保持一致；
 * - `decorView.filterTouchesWhenObscured = true`（ISSUE-P3-103）：窗口被其它窗口部分遮挡时
 *   丢弃整棵视图子树的触摸事件（反点击劫持）。与 `setHideOverlayWindows` 叠加而非替代——
 *   前者只阻断**新绘制**的悬浮窗，对**已存在**的遮挡窗口（如系统级无障碍覆盖、旧式 overlay）
 *   不生效；两者共同构成与自动填充 / 通行密钥窗口一致的加固面
 *   （见 `docs/architecture/已知工程限界.md` §3.3）。
 *
 * 通过 [com.journeyapps.barcodescanner.ScanOptions.setCaptureActivity] 在发起扫码时指定，
 * 并在 `AndroidManifest.xml` 显式声明。
 */
class SecureCaptureActivity : CaptureActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.setFlags(
            WindowManager.LayoutParams.FLAG_SECURE,
            WindowManager.LayoutParams.FLAG_SECURE
        )
        window.setHideOverlayWindows(true)
        // ISSUE-P3-103：与 FlagSecureGuard.applyObscuredTouchFilter 同口径（正确入口是 View 层，
        // compileSdk 37 的 android.jar 中 android.view.Window 并无该方法）
        window.decorView.filterTouchesWhenObscured = true
    }
}
