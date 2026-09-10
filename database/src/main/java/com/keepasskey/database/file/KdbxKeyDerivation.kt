package com.keepasskey.database.file

import com.keepasskey.crypto.hash.HashUtil
import com.keepasskey.crypto.kdf.KdfFactory
import java.nio.charset.StandardCharsets
import java.util.Arrays

/**
 * KDBX v4 复合密钥派生（ISSUE-P3-29：自 `KdbxFile.kt` 拆出，纯结构性拆分）。
 *
 * 原实现逐字迁移，行为零变更；`KdbxFile.deriveKeys` 保留为同签名门面委托，
 * 既有调用方（含单测）零改动。
 */
internal object KdbxKeyDerivation {

    /** 主密钥派生时的种子缓冲长度（SHA-512 摘要长度） */
    private const val SEED_HASH_BUFFER_SIZE = 64

    /**
     * 派生用于数据加密的 cipherKey (32B) 与用于认证的 hmacKey (64B)。
     *
     * 复合密钥（P1-10 整改，对齐官方 KeePass 2.61.1 CompositeKey.CreateRawCompositeKey32：
     * 只拼接实际存在的凭据分量后整体 SHA-256；官方解锁框对空密码不添加密码分量 KeyUtil.CreateKey，
     * 因此 [passwordChars] 为 null 或空数组时必须跳过密码分量，绝不把 SHA-256("") 拼入复合密钥）：
     * - 仅主密码：SHA-256(SHA-256(password))（密码分量本身即 SHA-256(UTF-8 密码)，KcpPassword）
     * - 仅密钥文件：SHA-256(keyFileKey)（keyFileKey 经 [KdbxKeyFile.extractKey] 官方解析梯子提取）
     * - 密码 + 密钥文件：SHA-256(SHA-256(password) ‖ keyFileKey)
     *
     * 官方标准（KeePass 2.61.1 KdbxFile.ComputeKeys + CryptoUtil.ResizeKey，
     * 与 pykeepass compute_master / KeePassDX DatabaseInputKDBX 交叉验证一致）：
     * - cipherKey = SHA-256(masterSeed ‖ transformedKey)
     * - hmacKey64 = SHA-512(masterSeed ‖ transformedKey ‖ 0x01)
     *
     * 历史兼容（isLegacy = true，本应用早期版本写出的文件）：
     * - cipherKey = SHA-512(masterSeed ‖ transformedKey)[0..32)
     * - hmacKey64 同官方
     */
    fun deriveKeys(
        header: KdbxHeader,
        passwordChars: CharArray?,
        keyFileData: ByteArray?,
        isLegacy: Boolean = false
    ): Pair<ByteArray, ByteArray> {
        val hasPassword = passwordChars != null && passwordChars.isNotEmpty()
        val hasKeyFile = keyFileData != null && keyFileData.isNotEmpty()

        val compositeKey = when {
            hasPassword && hasKeyFile -> {
                // 密码 + 密钥文件：SHA-256(SHA-256(password) ‖ keyFileKey)
                val passwordHash = HashUtil.sha256(charsToUtf8(passwordChars))
                try {
                    // 按官方语义解析密钥文件（XML .keyx 取 <Data> / 裸 32 字节 /
                    // 64 位 hex 文本 / 任意二进制整文件 SHA-256），
                    // 原实现对 XML 密钥文件整文件哈希导致复合密钥错误（虚假开关整改）
                    val keyFileKey = KdbxKeyFile.extractKey(keyFileData)
                    try {
                        HashUtil.sha256(passwordHash, keyFileKey)
                    } finally {
                        Arrays.fill(keyFileKey, 0.toByte())
                    }
                } finally {
                    Arrays.fill(passwordHash, 0.toByte())
                }
            }
            hasKeyFile -> {
                // P1-10：仅密钥文件 —— 直接 SHA-256(keyFileKey)，不拼入空密码分量 SHA-256("")。
                // 原实现恒拼入 SHA-256("")，官方仅密钥文件库 100% 派生错误密钥、报「主密码错误」
                val keyFileKey = KdbxKeyFile.extractKey(keyFileData)
                try {
                    HashUtil.sha256(keyFileKey)
                } finally {
                    Arrays.fill(keyFileKey, 0.toByte())
                }
            }
            else -> {
                // 仅密码：SHA-256(SHA-256(password))。
                // 密码与密钥文件均缺失时退化为 SHA-256(SHA-256(""))——本应用历史「空密码库」
                // 语义（官方客户端无法创建此类库），保持既有空密码库读写兼容，
                // 凭据校验由头部 HMAC 给出明确失败。
                val passwordBytes = if (hasPassword) charsToUtf8(passwordChars) else ByteArray(0)
                val passwordHash = HashUtil.sha256(passwordBytes)
                Arrays.fill(passwordBytes, 0.toByte())
                try {
                    HashUtil.sha256(passwordHash)
                } finally {
                    Arrays.fill(passwordHash, 0.toByte())
                }
            }
        }

        val kdfEngine = KdfFactory.getEngine(header.kdfParameters.kdfUuid)
        val transformedKey = kdfEngine.transform(compositeKey, header.kdfParameters)
        Arrays.fill(compositeKey, 0.toByte())

        // 组装 65 字节复合种子：MasterSeed (32B) + TransformedKey (32B) + 1 (1B)
        val cmpKey = ByteArray(65)
        System.arraycopy(header.masterSeed, 0, cmpKey, 0, 32)
        System.arraycopy(transformedKey, 0, cmpKey, 32, 32)
        Arrays.fill(transformedKey, 0.toByte())

        // 官方 KDBX4 派生（对齐 KeePass 2.x / pykeepass / KeePassXC）：
        // cipherKey  = SHA-256(masterSeed ‖ transformedKey)
        // hmacKey64  = SHA-512(masterSeed ‖ transformedKey ‖ 0x01)
        // 历史 bug 回放：本应用曾把 cipherKey 误实现为 SHA-512(seed‖tk)[0..32)（无尾部常量），
        // 该错误公式保留为旧文件探针回退路径（isLegacy = true）
        val cipherKeyBytes = ByteArray(SEED_HASH_BUFFER_SIZE)
        System.arraycopy(cmpKey, 0, cipherKeyBytes, 0, 64)
        val cipherKey = if (isLegacy) {
            HashUtil.sha512(cipherKeyBytes).copyOfRange(0, 32)
        } else {
            HashUtil.sha256(cipherKeyBytes)
        }
        Arrays.fill(cipherKeyBytes, 0.toByte())

        cmpKey[64] = 1.toByte()
        val hmacKey64 = HashUtil.sha512(cmpKey)
        Arrays.fill(cmpKey, 0.toByte())

        return Pair(cipherKey, hmacKey64)
    }

    private fun charsToUtf8(chars: CharArray): ByteArray {
        val charBuffer = java.nio.CharBuffer.wrap(chars)
        val byteBuffer = StandardCharsets.UTF_8.encode(charBuffer)
        val bytes = ByteArray(byteBuffer.remaining())
        byteBuffer.get(bytes)
        return bytes
    }
}
