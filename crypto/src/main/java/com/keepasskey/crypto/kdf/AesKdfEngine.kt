package com.keepasskey.crypto.kdf

import com.keepasskey.core.model.KdbxConstants
import com.keepasskey.core.model.KdbxUuid
import com.keepasskey.crypto.exception.CryptoException

/**
 * AES-KDF 密钥派生引擎（KeePass 经典 KDF）。
 *
 * ISSUE-P3-34：优先走原生 Rust 内核（`crypto/src/main/rust/src/aes_kdf.rs`，
 * RustCrypto `aes` + `sha2`）——该 KDF 轮数达千万级（`KdfBenchmark.DEFAULT_AES_ROUNDS = 6_000_000`）
 * 且**严格串行链式依赖**，既不可并行也不能靠加宽缓冲摊薄，只能靠实现质量；
 * 原 JVM 实现为「每轮一次 JCE `update`、每次 32 字节」，跨界开销被轮数放大。
 *
 * 兜底（[AesKdfJce]）适用于：桌面单测未注入宿主库、个别机型缺 ABI、探活失败等场景。
 * 原 JVM 实现已原样迁出至 [AesKdfJce]，**行为零变更**（含异常类型与消息）。
 */
class AesKdfEngine : KdfEngine {

    override val kdfUuid: KdbxUuid = KdbxConstants.Kdf.AES_KDF
    override val name: String = "AES-KDF"

    override fun transform(compositeKey: ByteArray, parameters: KdfParameters): ByteArray {
        val aesParams = parameters as? KdfParameters.Aes
            ?: throw CryptoException.KdfException("参数类型错误，期望 KdfParameters.Aes")

        require(compositeKey.size == 32) { "AES-KDF compositeKey 长度必须为 32 字节" }
        require(aesParams.seed.size == 32) { "AES-KDF seed 长度必须为 32 字节" }

        if (NativeAesKdf.available) {
            // 探活已证明原生通路与 JCE 逐字节等价；此处失败即真实异常，如实上抛（不静默回退重算）
            return NativeAesKdf.derive(compositeKey, aesParams.seed, aesParams.rounds)
        }
        return AesKdfJce.transform(compositeKey, aesParams.seed, aesParams.rounds)
    }
}
