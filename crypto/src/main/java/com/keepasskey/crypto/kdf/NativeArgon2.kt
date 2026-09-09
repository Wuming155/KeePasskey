package com.keepasskey.crypto.kdf

import com.keepasskey.crypto.exception.CryptoException

/**
 * Argon2 原生 JNI 绑定（自维护）
 *
 * 原生库 `libkeepasskey_argon2.so` 由本模块 `src/main/rust/` 源码构建（ISSUE-P2-14 Rust 秘密飞地）：
 * RustCrypto `argon2` + `zeroize`，经 cargo-ndk 从源码交叉编译 4 ABI（零二进制信任根）；
 * password/salt/secret/AD/派生输出全路径 RAII 确定性擦除，panic 经 `catch_unwind` 归一为返回 null。
 *
 * 仅在 Android 运行时可用；桌面 JVM（单元测试）加载失败自动降级 BouncyCastle。
 */
object NativeArgon2 {

    /** argon2_type：Argon2d */
    const val TYPE_ARGON2D = 0

    /** argon2_type：Argon2id */
    const val TYPE_ARGON2ID = 2

    /**
     * 可用性探活（懒加载一次）：加载 .so 并以极小参数（t=1, m=8 KiB, p=1）真实试算，
     * 确保功能可用而非仅加载成功；桌面 JVM / 个别机型失败时返回 false。
     */
    val available: Boolean by lazy {
        try {
            System.loadLibrary("keepasskey_argon2")
            // 探活试算：随机占位输入，结果仅用于验证调用通路，即刻丢弃
            val probe = deriveKey(
                password = ByteArray(32),
                salt = ByteArray(16),
                secret = null,
                associatedData = null,
                iterations = 1,
                memoryKib = 8,
                parallelism = 1,
                version = 0x13,
                type = TYPE_ARGON2ID
            )
            probe?.fill(0)
            probe != null
        } catch (t: Throwable) {
            false
        }
    }

    /**
     * Argon2 密钥派生（透传 KDBX 4 完整参数面，含 KDF secret / associatedData）。
     *
     * @param password 复合密钥摘要（KDBX 规约的 32B SHA-256），调用方持有清零责任
     * @param salt 32B 盐
     * @param secret KDF secret（罕见，可为 null）
     * @param associatedData KDF associatedData（罕见，可为 null）
     * @param iterations 迭代次数 t（≥1）
     * @param memoryKib 内存参数（KiB，≥ 8×parallelism）
     * @param parallelism 并行度 lanes（≥1）
     * @param version 0x10 / 0x13
     * @param type [TYPE_ARGON2D] / [TYPE_ARGON2ID]
     * @return 32B 派生密钥；参数非法 / 派生失败（含内存分配失败）返回 null
     */
    external fun deriveKey(
        password: ByteArray,
        salt: ByteArray,
        secret: ByteArray?,
        associatedData: ByteArray?,
        iterations: Int,
        memoryKib: Int,
        parallelism: Int,
        version: Int,
        type: Int
    ): ByteArray?

    /**
     * 原生派生统一入口：失败归一为 [CryptoException.KdfException]（KeePassDX Limits 模式，
     * 不裸吞错误、不静默回退重算）。
     */
    fun derive(
        password: ByteArray,
        salt: ByteArray,
        secret: ByteArray?,
        associatedData: ByteArray?,
        iterations: Int,
        memoryKib: Int,
        parallelism: Int,
        version: Int,
        type: Int
    ): ByteArray {
        val out = try {
            deriveKey(
                password, salt, secret, associatedData,
                iterations, memoryKib, parallelism, version, type
            )
        } catch (e: UnsatisfiedLinkError) {
            throw CryptoException.KdfException("Argon2 原生库不可用", e)
        }
        if (out == null) {
            throw CryptoException.KdfException(
                "Argon2 密钥派生失败（参数越界或 KDF 内存超出本机可用内存）"
            )
        }
        return out
    }
}
