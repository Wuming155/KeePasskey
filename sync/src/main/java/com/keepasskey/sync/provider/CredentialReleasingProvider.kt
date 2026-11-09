package com.keepasskey.sync.provider

/**
 * Provider 的**可选**「凭据可确定性失效」能力（ENG-20261028-42）。
 *
 * 为什么是独立接口而不是 [SyncProvider] 的方法：协议适配对凭据的驻留形态不同，能力也不同。
 * WebDAV 的 Basic 头可由口令副本按请求现算，失效后拒绝继续构造即可 fail-closed；
 * 而有的实现（或尚在整改中的实现）没有等价载体。把方法加进 [SyncProvider] 会强迫所有实现
 * 声明一个语义无法保证的接口，故以「可选能力 + 调用方判定」表达，未实现者保持既有行为。
 *
 * 契约：
 * - **幂等**：重复调用无副作用，不放大失败面；
 * - **确定性**：调用返回后，实现持有的可擦凭据副本必须已清零，不依赖 GC；
 * - **fail-closed**：失效后实现**不得**以空口令 / 空密钥降级继续发请求，而须以
 *   [com.keepasskey.sync.model.SyncException.AuthenticationError] 失败；
 * - **不可复活**：失效不可撤销，调用方必须确认该 Provider 不会再被任何存活消费者使用
 *   （仍可能被待决冲突裁决复用的实例**不得**释放，见 `SyncEngine.releaseProviderCredentials` 的持有周期）。
 */
interface CredentialReleasingProvider {

    /** 确定性擦除 Provider 持有的凭据副本；此后该实例的认证请求一律失败。 */
    fun releaseCredentials()
}
