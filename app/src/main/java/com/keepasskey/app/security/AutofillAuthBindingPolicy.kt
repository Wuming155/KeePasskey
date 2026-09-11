package com.keepasskey.app.security

/**
 * 自动填充放行认证绑定判定（ISSUE-P3-52，纯 Kotlin，JVM 可测）。
 *
 * 背景：自动填充确认 / 选择器此前调用 [BiometricAuthManager.authenticate] 时**未传 Cipher**，
 * 生物识别仅证明「用户在场」，未与本次凭据放行操作密码学绑定。整改后两处均以 Keystore 认证绑定
 * 密钥的 `Cipher` 发起（`CryptoObject`），并要求认证成功结果**确实携带 Cipher** 方予放行。
 *
 * 本策略把该放行判据从 Android 组件中抽出为纯函数，使「非绑定结果不得放行」可在 JVM 单测直接断言。
 */
object AutofillAuthBindingPolicy {

    /**
     * 认证结果是否与本次放行操作密码学绑定（可放行）。
     *
     * 仅当结果为 [BiometricResult.Success] 且携带非空 `Cipher` 时返回 true；
     * 失败 / 取消 / 成功但无 Cipher 一律 false（fail-closed，不得放行明文凭据）。
     */
    fun isBound(result: BiometricResult): Boolean =
        result is BiometricResult.Success && result.cipher != null
}
