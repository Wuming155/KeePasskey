package com.keepasskey.app.passkey

/**
 * 系统侧「本应用是否已登记为凭据提供者」的判定（`ISSUE-P2-239`）。
 *
 * ## 为什么需要这一项
 * 凭据提供者通道的失效**完全静默**：系统未启用本应用时，创建请求在框架层即被丢弃
 * （`CreateCredentialException.TYPE_NO_CREATE_OPTIONS`），**根本不会发到本应用** ⇒
 * 用户只看到「点保存没反应」，既无报错也无从归因。自动填充通道早有健康自检卡，
 * 本通道此前既无自检也无引导。
 *
 * ## 信号来源的取舍（含一次被现场推翻的初版设计，如实留痕）
 * 初版判定读的是 `Settings.Secure.credential_service`（§240 §2.3 的 E1~E3 归因实验已证明该键是
 * 创建请求路由的**必要且充分**条件）。**该设计在真机上被推翻**：本应用读取该键抛
 * `java.lang.SecurityException`（Redmi 4X / Android 17 · API 37 实测，2026-09-21 17:37），
 * 即该键对普通应用**不可读**，判定会永远停在「未知」。
 * 现改用**公开 API**：`android.credentials.CredentialManager.isEnabledCredentialProviderService(
 * ComponentName)`（API 34+，语义即「该系统组件是否已是已启用的凭据提供者」）——它同样只反映
 * 系统真实状态，且无需任何权限、不依赖内部键。两信号的等价性已在本机按「登记 / 清除登记」
 * 两态实测（读数见 `ISSUE-P2-239` 批次正文 §4）。
 *
 * ## 判定纪律（AC②）
 * 三态必须严格区分：**读取失败一律判 [UNKNOWN] 并按异常项呈现**，不得与 [REGISTERED] 合并——
 * 「谎报正常」会让用户继续面对「点保存没反应」却看到一张全绿的卡，比不检测更坏。
 */
enum class CredentialProviderRegistration {

    /** 系统已把本应用登记为已启用的凭据提供者 */
    REGISTERED,

    /**
     * 系统未启用本应用作为凭据提供者（该状态下的用户可见后果＝保存 / 通行密钥请求根本不发到本应用）
     */
    NOT_REGISTERED,

    /** 无法判定（系统服务不可得或调用抛异常）——呈现为「未知」，**不得**呈现为正常 */
    UNKNOWN;

    companion object {

        /**
         * 纯函数判定（AC③：判据与平台查询分离，宿主 JVM 可对全部输入穷举）。
         *
         * @param systemState 平台探针的读取结果（见 [CredentialProviderEnabledState]）
         */
        fun of(systemState: CredentialProviderEnabledState): CredentialProviderRegistration =
            when (systemState) {
                CredentialProviderEnabledState.ENABLED -> REGISTERED
                CredentialProviderEnabledState.DISABLED -> NOT_REGISTERED
                CredentialProviderEnabledState.UNREADABLE -> UNKNOWN
            }
    }
}

/**
 * `CredentialManager.isEnabledCredentialProviderService(...)` 的读取结果（`ISSUE-P2-239`）。
 *
 * 三态**必须**在类型上分开：`Boolean?` 里 `null` 既可能是「系统服务不可得」，也可能是
 * 「组件名推导失败」，而按 AC② 二者都只能呈现为「未知」——但用可空布尔表达时，
 * 调用方极易把 `null` 顺手写成 `?: true`（＝谎报正常）。故以枚举显式表达。
 *
 * 本类型不依赖任何 Android 类，宿主 JVM 可直接构造全部形态。
 */
enum class CredentialProviderEnabledState {

    /** 系统答复：本组件已是已启用的凭据提供者 */
    ENABLED,

    /** 系统答复：本组件不是已启用的凭据提供者 */
    DISABLED,

    /** 未能取得系统答复（系统服务不可得 / 调用抛异常 / 组件名无法推导）——按「未知」处理 */
    UNREADABLE
}
