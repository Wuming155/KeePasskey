package com.keepasskey.app.data.repository

import com.keepasskey.core.model.KdbxEntry
import com.keepasskey.core.model.PasskeyData

/**
 * Passkey（WebAuthn）凭据面（ISSUE-P3-305：自 [VaultRepository] 按职责拆出的能力面，纯结构性改动）。
 *
 * 职责单一：RP ID / Credential ID 维度的**凭据查询**、新建与原地替换、`excludeCredentials`
 * 查重，以及签名计数器（SignCount）的写回与原子递增——即通行密钥注册与断言两条链所需的
 * 全部库侧契约。
 *
 * 拆出后 [VaultRepository] 继承本接口，既有调用点（形如 `vaultRepository.findPasskeyByCredentialId(...)`）
 * **零改动**；`FakeVaultRepository` 等测试替身与 `by delegate` 委托实现同样无需改动。
 */
interface VaultPasskeyRepository {

    /**
     * 根据依赖方标识 (RP ID) 或域名查询匹配的凭据条目
     */
    suspend fun findEntriesForRpId(rpId: String): List<KdbxEntry>

    /**
     * 根据 Base64URL 编码的 Credential ID 查找对应的 Passkey 凭据条目
     */
    suspend fun findPasskeyByCredentialId(credentialId: String): KdbxEntry?

    /**
     * 保存全新的 Passkey 凭据条目至根群组。
     * [boundPackage] 非空时（普通应用创建路径）条目 url 记录为 android://<包名>，
     * 供凭据查询按严格包名边界匹配；为空时记录为 https://<rpId>。
     */
    suspend fun saveNewPasskeyEntry(
        data: PasskeyData,
        boundPackage: String? = null
    ): KdbxEntry

    /**
     * 新建或**原地替换** Passkey 凭据条目。
     *
     * 复用条件：同 rpId（域匹配）+ 同用户名的既有 Passkey 条目 —— 命中时保留条目其它内容
     * （标题 / 备注 / 密码 / 标签 / 历史 / 附件），仅整体换新 Passkey schema 字段；
     * 未命中则等价于 [saveNewPasskeyEntry]。整改动机：同站点重复注册曾产生多条重复条目。
     */
    suspend fun saveOrReplacePasskeyEntry(
        data: PasskeyData,
        boundPackage: String? = null
    ): KdbxEntry

    /**
     * `excludeCredentials` 查重（WebAuthn 规范）：返回 [credentialIds] 中**已存在于库内**的
     * credentialId 子集（空集表示全部未被占用）。命中即由调用方 fail-closed 拒绝注册。
     */
    suspend fun findExistingPasskeyCredentialIds(credentialIds: Set<String>): Set<String>

    /**
     * 递增并写回 Passkey 条目的签名计数器 (SignCount)。
     *
     * ISSUE-P3-27 子项 2：本入口不向调用方回传落库值，**断言路径不得使用它**——
     * 需要把计数器写进 AuthenticatorData 的调用方必须改用 [incrementPasskeySignCount]。
     */
    suspend fun patchPasskeySignCount(entryId: String, newCount: Int)

    /**
     * 原子递增并返回**本次实际落库**的签名计数器（ISSUE-P3-27 子项 2）。
     *
     * 断言路径必须使用本返回值，禁止用锁外快照自行计算（`快照 + 1`）：
     * 快照在进入断言时读取、与落库不在同一临界区，两个并发断言会算出同一个值并各自
     * 向 RP 交出**重复**的 signCount（违反 WebAuthn 单调性）；「已签名回传、落盘前进程中断」
     * 的重试同样会再交一次同值。本方法的递增与落库属同一受控原子变换，故返回值即「已提交」
     * 的计数器，且返回值已写进响应时库内计数器必然已推进。
     *
     * @return 实际落库的计数器值；条目不存在 / entryId 非法时返回 null
     *   （调用方此时必须 fail-closed 拒绝签发，不得回退为自算值）。
     */
    suspend fun incrementPasskeySignCount(entryId: String): Int?
}
