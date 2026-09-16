package com.keepasskey.app.data.repository

import com.keepasskey.core.model.KdbxConstants
import com.keepasskey.core.model.KdbxUuid

/**
 * 建库向导的「加密预设」——**加密算法与 KDF 选择的唯一真相源**。
 *
 * ## 立规缘由（本枚举要消除的缺陷）
 *
 * 预设此前是 UI 侧的**裸字符串列表**，落盘路径只以
 * `preset.contains("AES-KDF")` 反推 KDF，**加密算法被整段丢弃**：
 * `SessionOpener.create` 恒以 `AES_256_CBC` 建库。于是
 * ① 默认选中的「ChaCha20 + Argon2id」实际产出 **AES-256-CBC** 库；
 * ② 选「Twofish + AES-KDF」同样产出 **AES-256-CBC** 库（KDF 正确、算法错误）。
 * 向导展示与产物**长期不一致**，且全程无任何提示——用户以为在用 ChaCha20 / Twofish。
 *
 * 收敛为类型化枚举后：UI 只渲染 [label] / [chipLabel]，落盘只用 [cipherUuid] 与 [useArgon2]，
 * **不存在**「字符串没匹配上就静默回落默认算法」的路径；新增预设必须同时给出算法与 KDF。
 *
 * 三者均为 `CipherFactory` 已支持的**外层**算法（AES-256-CBC / ChaCha20 / Twofish-CBC）；
 * IV / nonce 长度由 `KdbxHeader.createDefault` 按 [cipherUuid] 自动取
 * `BLOCK_CIPHER_IV_LENGTH`（16B）或 `CHACHA20_NONCE_LENGTH`（12B）。
 */
enum class CreateVaultPreset(
    /** 完整标签：向导展示用，同时是持久化 / 展示层的既有取值（`VaultDatabaseInfo.encryptionPreset`） */
    val label: String,
    /** 向导芯片上的短标签（受宽度限制，只取算法名） */
    val chipLabel: String,
    /** 外层加密算法（KDBX `CipherID`） */
    val cipherUuid: KdbxUuid,
    /** true = Argon2id（本仓默认档），false = AES-KDF */
    val useArgon2: Boolean
) {
    /** ChaCha20（RFC 7539 流密码）+ Argon2id —— 向导默认档 */
    CHACHA20_ARGON2ID(
        label = "ChaCha20 + Argon2id",
        chipLabel = "ChaCha20",
        cipherUuid = KdbxConstants.Cipher.CHACHA20,
        useArgon2 = true
    ),

    /** AES-256-CBC + Argon2id —— KDBX4 官方默认算法 */
    AES256_ARGON2ID(
        label = "AES-256 + Argon2id",
        chipLabel = "AES-256",
        cipherUuid = KdbxConstants.Cipher.AES_256_CBC,
        useArgon2 = true
    ),

    /** Twofish-CBC + AES-KDF —— 与官方客户端旧配置互通的最保守档 */
    TWOFISH_AES_KDF(
        label = "Twofish + AES-KDF",
        chipLabel = "Twofish",
        cipherUuid = KdbxConstants.Cipher.TWOFISH,
        useArgon2 = false
    );

    companion object {
        /** 向导默认选中项（与本仓既有 UI 默认一致：ChaCha20 + Argon2id） */
        val DEFAULT: CreateVaultPreset = CHACHA20_ARGON2ID

        /**
         * 标签 → 预设。**未登记标签返回 null**：调用方必须 fail-closed
         * （显式失败 / 回落 [DEFAULT] 并留痕），**不得**悄悄用默认算法建库。
         *
         * 供「持久化或跨进程边界的字符串入口」使用；应用内新代码请直接传枚举。
         */
        fun fromLabel(label: String): CreateVaultPreset? =
            entries.firstOrNull { it.label == label }
    }
}
