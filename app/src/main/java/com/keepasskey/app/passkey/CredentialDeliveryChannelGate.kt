package com.keepasskey.app.passkey

/**
 * 凭据通道开关在**最终交付边界**的复核（ENG-20261028-05）。
 *
 * ## 为什么交付点还要再判一次
 *
 * 服务入口（[KeePasskeyCredentialProviderService] 的 `get` / `create`）与候选组装处的求值
 * 只覆盖「请求进入时」。凭据流程存在多处挂起点——系统候选已呈现、链式解锁页、
 * 用户验证弹窗——用户完全可能在挂起期间到设置页关闭通道。故交付落点必须按**本次**
 * 开关值再求值一次，关闭即按既有失败语义拒绝（`RESULT_CANCELED` 或空响应），
 * 绝不交付已关闭通道的凭据。
 *
 * ## 开关各自的作用域
 *
 * - **CM 通道总开关**（`ExtendedSettingsStore.isCredentialProviderEnabled`）：本应用在系统
 *   凭据管理器中的口令与公钥两路**一律**关闭。口令交付只认它，见 [allowsPasswordDelivery]。
 * - **通行密钥开关**（`isPasskeySupportEnabled`）：只收敛公钥候选 / 断言 / 注册，
 *   见 [allowsPasskeyDelivery]。**不得**拿它拦口令请求——两条通道在设置页各自独立成行，
 *   误接会把「关掉通行密钥」变成「关掉全部密码填充」。
 * - **Autofill 通道开关**（`isAutofillServiceEnabled`）：只约束传统自动填充通道，
 *   由该通道自己的交付点（`KeePasskeyAutofillService`、`AutofillConfirmActivity`、
 *   `AutofillPickerActivity`）直接求值；它不参与 CM 判定，故不进入本对象。
 *
 * 纯函数、无 Android 依赖，判定表可由宿主单测穷举（`CredentialDeliveryChannelGateTest`）。
 */
internal object CredentialDeliveryChannelGate {

    /**
     * CM 口令通道（填充 [PasswordFillActivity] / 保存 [PasswordSaveActivity]）：只受 CM 总开关约束。
     *
     * 签名刻意不含通行密钥开关：口令交付在构造上就不可能被该开关屏蔽（ENG-20261028-05 关闭条件）。
     */
    fun allowsPasswordDelivery(credentialProviderEnabled: Boolean): Boolean = credentialProviderEnabled

    /**
     * CM 公钥通道（候选 / 断言 [PasskeyAssertionActivity] / 注册）：CM 总开关 ∧ 通行密钥开关。
     *
     * 总开关关闭时即使通行密钥开关仍开启也不得交付：总开关的语义是「本通道不交付任何凭据」。
     */
    fun allowsPasskeyDelivery(
        credentialProviderEnabled: Boolean,
        passkeySupportEnabled: Boolean
    ): Boolean = credentialProviderEnabled && passkeySupportEnabled
}
