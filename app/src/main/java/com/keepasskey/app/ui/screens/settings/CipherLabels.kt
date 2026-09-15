package com.keepasskey.app.ui.screens.settings

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
}
