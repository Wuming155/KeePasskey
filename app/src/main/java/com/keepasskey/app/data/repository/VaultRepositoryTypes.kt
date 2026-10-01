package com.keepasskey.app.data.repository

import com.keepasskey.app.ui.model.UiVaultEntry

/**
 * [VaultRepository] 的伴随值对象集合。
 *
 * ISSUE-P3-29：自 `VaultRepository.kt` 拆出，使接口文件回归阈值内。
 * 三者均为 **同包顶层声明**，全限定名与拆分前完全一致，故所有既有 import / 引用零改动；
 * 本拆分是**纯结构性**的，不含任何行为变更。
 */

/**
 * 历史修订的完整回滚快照（断点8 整改）。
 * [entry] 的受保护自定义字段已按需解密回填，可直接作为 saveEntry 入参提交；
 * [totpSecretChars] 为该修订的 TOTP 配置原文（otpauth:// URI 或 Base32 种子），
 * 空数组表示该修订无 TOTP 配置。
 *
 * ISSUE-P2-15：TOTP 原文由不可擦除的 String 改为 **CharArray 独占副本**，归调用方所有；
 * 回滚路径应直接将其转交 [VaultRepository.saveEntry] 的 `totpSecretChars` 参数
 * （由仓库按擦除契约用毕清零），或在用毕自行 `fill('0')`，不得再遗留 String 中转。
 */
data class EntryRevisionSnapshot(
    val entry: UiVaultEntry,
    val totpSecretChars: CharArray
)

/**
 * 单条凭据的 TOTP 即时计算快照（F2 整改）。
 * 仅含展示所需的非敏感结果（验证码与参数），不含种子。
 */
data class EntryTotpSnapshot(
    val code: String,
    val periodSeconds: Int,
    val digits: Int,
    val algorithm: String,
    // ISSUE-P3-49：HOTP 标识与当前（出码所用）计数器；isHotp=false 时 counter 无意义
    val isHotp: Boolean = false,
    val counter: Long = 0
)

/**
 * 新建密码库时的密钥文件因子（ISSUE-P3-21：对齐官方 `CompositeKey` 三分支）。
 *
 * 用 sealed 类型而非 `Boolean` 开关表达「不绑定 / 生成新密钥文件 / 使用既有密钥文件」三种意图：
 * 原 `createDatabase(..., keyFile: Boolean, ...)` 的布尔形参在
 * `RealVaultRepository` 内**从未被使用**（勾选后产出的库实际不含密钥文件因子），
 * 属安全语义上的欺骗；sealed 类型让「哪种因子」在类型层面必需，无法再被静默忽略。
 */
sealed interface CreateKeyFileFactor {

    /** 仅主密码（复合密钥只含密码分量） */
    data object None : CreateKeyFileFactor

    /**
     * 生成全新合规密钥文件（KeePass 2.x XML v2.0，生成器为 database 模块的
     * `KdbxKeyFileGenerator`）并作为第二因子绑定进会话，供既有导出通道交付用户。
     */
    data object Generate : CreateKeyFileFactor

    /**
     * 使用用户选定的既有密钥文件原始字节作为第二因子。
     *
     * [bytes] 为**借用语义**：实现方按借用契约克隆持有（派生复合密钥并缓存供保存使用），
     * 不擦除入参数组；调用方用毕必须在 `finally` 中显式 `fill(0)`。
     * 刻意不做 `data class`——避免对密钥字节生成基于内容的 `equals`/`hashCode` 与
     * `copy` 语义歧义（密钥字节不是值对象）。
     */
    class Existing(val bytes: ByteArray) : CreateKeyFileFactor
}

/**
 * 更换主凭据时对密钥文件第二因子的意图（ISSUE-P3-428）。
 *
 * 与建库侧 [CreateKeyFileFactor] 语义不同：这里作用于**已存在**的库，
 * 三态分别映射 database 层 `DatabaseSession.changeCredentials` 的两个重载——
 * `Keep` 走单参路径（沿用会话当前绑定的密钥文件快照），`Remove` / `Use`
 * 走双参路径（`null` = 不使用密钥文件 / 传字节 = 绑定或更换）。
 */
sealed interface ChangeKeyFileIntent {

    /** 维持会话当前绑定的密钥文件（未绑定则保持仅主密码），不改写第二因子 */
    data object Keep : ChangeKeyFileIntent

    /** 解绑第二因子：改后库仅以主密码保护 */
    data object Remove : ChangeKeyFileIntent

    /**
     * 绑定或更换为用户选定的密钥文件。
     *
     * [bytes] 为**借用语义**：与 [CreateKeyFileFactor.Existing] 同一口径——
     * 实现方只读取（派生复合密钥并克隆写入会话缓存），不持有引用、不擦除；
     * 调用方用毕必须在 `finally` 中显式 `fill(0)`。刻意不做 `data class`（理由同上）。
     */
    class Use(val bytes: ByteArray) : ChangeKeyFileIntent
}
