package com.keepasskey.app.ui.theme

import androidx.compose.runtime.staticCompositionLocalOf

/**
 * 界面偏好组（`ISSUE-P3-444`）的两条全局开关——以 [staticCompositionLocalOf] 承载，
 * 由 `KeePasskeyApp` 在外壳层按设置页偏好注入实际值。
 *
 * 为什么用 CompositionLocal 而不是逐层参数：
 * - 消费面是**全站散点**（等宽字段 14 处、列表动画 2 处、导航转场 1 处），逐层下传会污染
 *   十余个中间组件的签名；
 * - 与既有 [com.keepasskey.app.ui.components.HapticsEnabled] 同一范式（定义在同包、
 *   由 `KeePasskeyApp` 的 `CompositionLocalProvider` 注入）。
 *
 * 缺省值取「与既有可观察行为一致」的那一档（等宽开、动效不降级）——任何未注入的宿主
 * （预览 / 截图测试 / 单测组合）渲染出的都是改动前的画面。
 */

/**
 * 密码 / TOTP 字段是否使用等宽字体（默认 `true`＝既有硬编码行为）。
 *
 * 消费点：`passwordFieldStyle()` / `totpFieldStyle()`（见 `Type.kt`）——全站等宽样式
 * 一律经这两个访问器取值，**不得**再直接引用 `MonospacePasswordStyle` /
 * `MonospaceTotpStyle` 常量（否则该字段绕过偏好）。
 */
val LocalMonospaceFields = staticCompositionLocalOf { true }

/**
 * 动效降级（默认 `false`＝正常播放）。
 *
 * 语义边界（`ISSUE-P3-444` AC②）：**只**决定「运行期是否播放转场 / 列表动画」，
 * **不改** [com.keepasskey.app.ui.navigation.AppNavigationMotion] 的定标常量本身——
 * 该类的时长 / 缩放 / 缓动取值仍受 `AppNavigationMotionTest` 守卫，与本开关正交。
 */
val LocalReduceAnimations = staticCompositionLocalOf { false }
