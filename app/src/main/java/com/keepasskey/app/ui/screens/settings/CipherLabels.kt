package com.keepasskey.app.ui.screens.settings

import com.keepasskey.core.model.KdbxConstants
import com.keepasskey.core.model.KdbxUuid

/**
 * KDBX 外层加密算法的**唯一显示词汇表**（ISSUE-P3-92）。
 *
 * 立此单一来源的原因：同一组标签此前在**三处独立硬编码**（算法选择对话框
 * `CipherAlgorithmDialog`、「密码库与加密」页的头部映射 `databaseConfigFromHeader`、
 * UI 状态默认值 `DatabaseUiState`），且三处**一致地错**——把 KDBX 的 ChaCha20（RFC 8439
 * 的 ChaCha20，**无 AEAD 标签**）写成「ChaCha20-Poly1305」：
 *
 * - 本仓 `ChaCha20CipherEngine` 用的是 `ChaCha7539` 流密码（`ChaCha20Engine`），
 *   KDBX4 外层加密只做流式加解密并**不**产生/校验 Poly1305 认证标签；
 * - 该表述与实际不符，属**功能声明失真**（用户据此误以为有 AEAD 完整性保护，
 *   而 KDBX 的完整性由 HMAC-SHA256 块流独立承担）。
 *
 * 故统一为「ChaCha20 (256-bit)」，并让三处引用同一常量，杜绝再次漂移。
 */
internal object CipherLabels {

    /** ChaCha20（RFC 8439 流密码；**无** Poly1305 AEAD 标签） */
    const val CHACHA20 = "ChaCha20 (256-bit)"

    const val AES_256_CBC = "AES-256-CBC (256-bit)"

    const val TWOFISH_CBC = "Twofish-CBC (256-bit)"

    /**
     * ISSUE-P2-271：显示标签 → KDBX 算法 ID 反查（选择器写侧的真实落点）。
     * 与 [databaseConfigFromHeader] 的正向映射共用同一常量，杜绝正反两向漂移；
     * 未知标签返回 null（调用方如实 no-op，不产生假变更）。
     */
    fun cipherUuidForLabel(label: String): KdbxUuid? = when (label) {
        CHACHA20 -> KdbxConstants.Cipher.CHACHA20
        AES_256_CBC -> KdbxConstants.Cipher.AES_256_CBC
        TWOFISH_CBC -> KdbxConstants.Cipher.TWOFISH
        else -> null
    }
}

/**
 * KDF 派生算法的**唯一显示词汇表**（ISSUE-P2-271：与 [CipherLabels] 同一纪律）。
 * 此前三个字面量在头部投影（`databaseConfigFromHeader`）与 KDF 选择对话框两处独立硬编码，
 * 选择器整改后新增第三处消费方（写侧反查），故收敛为常量杜绝漂移。
 */
internal object KdfLabels {

    const val ARGON2ID = "Argon2id"

    const val ARGON2D = "Argon2d"

    const val AES_KDF = "AES-KDF"
}
