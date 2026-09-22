package com.keepasskey.app.security

/**
 * 解锁断言私钥（TASK-18）密钥规格的**唯一语义声明点**。
 *
 * ## 定位：断言私钥不是认证闸门
 *
 * 快速解锁的认证闸门是**封印密钥**：`AES-256-GCM`、`setUserAuthenticationRequired(true)` +
 * `setUserAuthenticationParameters(0, AUTH_BIOMETRIC_STRONG)` 的 per-operation 密钥，必须以
 * `BiometricPrompt.CryptoObject` 逐次授权才能 `doFinal`——认证与解密因此是**密码学绑定**的
 * （伪造认证回调解不开封印，见 [AutofillAuthBindingPolicy]）。断言私钥只承担凭据持有性证明、
 * `signCount` 反克隆与登记记录防篡改，**不**要求用户认证（[REQUIRES_USER_AUTHENTICATION] = false）。
 *
 * ## 为什么不能绑定用户认证（平台约束，非本仓选择）
 *
 * 1. **一次认证只能授权一个密钥操作**：`BiometricPrompt.authenticate(...)` 只接受**一个**
 *    `CryptoObject`，而封印密钥的 per-operation 门控**必须**绑 `CryptoObject` ⇒ 同一次认证无法
 *    再授权断言私钥；改为断言私钥 per-operation 则需第二次弹窗（快速解锁体验不可接受）。
 * 2. **带时间窗的密钥用不了 `CryptoObject`**：官方指南（`identity/sign-in/biometric-auth`
 *    §「Authenticate using either biometric or lock screen credentials」）要求该类密钥「允许回退到
 *    非生物识别凭据」且**不得**把 `CryptoObject` 传给 `authenticate(...)`；平台侧同源——该形态的
 *    SID 取**门锁 root SID**，框架源码注释即写「解锁安全锁屏者即授权该密钥」
 *    （SDK 源 `android/security/keystore2/KeyStore2ParameterUtils.java` 的 `addSids`）。
 *    ⇒ 断言私钥一旦按时间窗绑定认证，本流程**没有**任何路径能授权它。
 *
 * ## API 陷阱：`setUserAuthenticationParameters` 单独调用**不生效**
 *
 * `setUserAuthenticationParameters(timeout, type)` 只**配置**认证参数，认证要求本身仅由
 * `setUserAuthenticationRequired(true)` 开启（SDK 源 javadoc 原文：*This has effect if the key
 * requires user authentication for its use (see `setUserAuthenticationRequired(boolean)`)*；
 * 该 Builder 只写 `mUserAuthenticationValidityDurationSeconds` / `mUserAuthenticationType`，
 * 而 `KeyStore2ParameterUtils.addUserAuthArgs` 在 `!isUserAuthenticationRequired()` 时直接下发
 * `KM_TAG_NO_AUTH_REQUIRED`，**认证参数被静默忽略**）。
 *
 * `ISSUE-P1-09` 正踩在此陷阱上：其规格只调用了 `setUserAuthenticationParameters(30, AUTH_BIOMETRIC_STRONG)`
 * 而未调用 `setUserAuthenticationRequired(true)`，于是私钥**从未**绑定认证；而同一批把断言读路径写成
 * 「按 `KeyInfo` 探测『是否绑定认证』，不匹配即删钥重建」，该前提恒不成立 ⇒ 每次断言都删钥重建 ⇒
 * 新公钥与**已登记记录**脱钩 ⇒ 每一次快速解锁都被断言门控 fail-closed 拒绝（`ISSUE-P1-242`）。
 * 设备侧实测（Pixel_10 / API 36 与 Redmi 4X / API 37 读数一致）：该形态生成出的私钥
 * `authRequired=false validity=0 authType=0`，且**无任何用户认证授权即可签名**。
 *
 * ## 反向约束（**不得**恢复的两种形态）
 *
 * - **不得**把读路径改回「探测失败即删钥重建」：轮换只在 [UnlockPasskeyManager.enroll] 的
 *   「删别名 → 重建 → 重写登记记录」序列内，那里公钥与记录同时刷新、不存在中间态。
 * - **不得**在 [KeystoreKeyMaterial.buildUnlockPasskeySpec] 里补 `setUserAuthenticationRequired(true)`：
 *   那会让私钥真的绑定认证，随即撞上上面的平台约束（签名恒抛 `UserNotAuthenticatedException`），
 *   等于把「断言不可用」从「公钥脱钩」换成「授权不可达」，故障依旧。要恢复认证门控必须先解决平台
 *   约束（解除条件见 `docs/architecture/已知工程限界.md` §3.6）。
 *
 * ## 安全后果（诚实边界）
 *
 * 断言私钥不承担认证闸门，其不绑定认证的实际后果仅限「同 UID 代码执行者可在无生物识别授权时
 * 签出合法断言」；该对手**仍然无法解封封印载荷**（封印密钥的 per-operation 门控不受影响），
 * 故不构成新的解锁通道，属纵深防御层的收窄而非闸门失效。
 *
 * ## 回归防线
 *
 * - 宿主：`UnlockPasskeyKeySpecTest` 以「策略取值 + 生产源码接线」双断言钉死本决策（含「断言私钥
 *   规格不得出现任何认证器集合或时间窗声明」「读路径不得轮换密钥」两条不变式）；
 * - 设备：`UnlockPasskeySigningDeviceTest` 以真实 `AndroidKeyStore` 实证「生成 → `initSign` → 签名 →
 *   公钥验签」与 `enroll → assertUnlock → verifyAndCommit` 全链可用（宿主 JVM 无 AndroidKeyStore，
 *   该面只能设备侧覆盖；该用例在 `ISSUE-P1-242` 的原始实现上**真机判红**）。
 */
internal object UnlockPasskeyKeyPolicy {

    /**
     * 断言私钥是否要求用户认证——**必须为 false**。
     *
     * 取值理由、平台约束与「为何不得改回 true」见类 KDoc；本常量是唯一声明点，
     * [KeystoreKeyMaterial.buildUnlockPasskeySpec] 与宿主守卫测试都以此为准，
     * 不得在别处另写位或表达式。
     */
    const val REQUIRES_USER_AUTHENTICATION = false

    /**
     * 断言私钥是否仅可在设备解锁态使用。
     *
     * 官方语义：`setUnlockedDeviceRequired(true)` 只约束私钥操作（公钥操作不受限），
     * 与「是否需要用户认证」是两回事，可独立使用（见 `KeyGenParameterSpec.Builder#setUnlockedDeviceRequired`）。
     * 快速解锁只发生在前台解锁态，保留该约束不产生可用性代价。
     */
    const val UNLOCKED_DEVICE_REQUIRED = true
}
