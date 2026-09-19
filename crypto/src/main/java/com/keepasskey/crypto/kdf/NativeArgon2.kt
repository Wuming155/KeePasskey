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

    /** 探活 KAT 的固定输入长度（password 32B / salt 16B，KDBX 规约口径） */
    private const val PROBE_PASSWORD_LEN = 32
    private const val PROBE_SALT_LEN = 16

    /**
     * 探活 KAT（ISSUE-P3-204）：固定输入 + 冻结期望摘要，与同批其余内核口径对齐。
     *
     * 输入：Argon2id v0x13，t=1 / m=8 KiB / p=1，password = 32×0x00，salt = 16×0x00，
     * 无 secret / associatedData，输出 32 字节。
     * 期望值由独立参考实现（phc-winner-argon2 系 `argon2-cffi`）计算并冻结，
     * 使探活不仅能发现「库没加载 / 符号缺失 / 返回 null」，还能发现「内核返回同长度
     * 垃圾值」这类静默错误——后者若漏过，会以「派生密钥错误 → HMAC 校验失败 →
     * 用户无法解锁」的形式在真机上暴露（可用性窗口，无机密性后果，见 ISSUE-P3-204）。
     * 回归对拍：`Argon2ProbeKatTest` 在宿主 JVM 以同输入断言原生输出逐字节等于本值。
     */
    private val PROBE_KAT_HEX: String =
        "c9cc39f9d3cc47bb2db7c1be933c763de2724869bf55c412382afbc904cb3407"

    /**
     * 可用性探活（懒加载一次）：加载 .so 并以极小参数（t=1, m=8 KiB, p=1）真实试算，
     * 且与 [PROBE_KAT_HEX] 冻结摘要逐字节比对（ISSUE-P3-204 起，原「非空即通过」升级为
     * KAT 对照，与 `NativeAesKdf` / `NativeTwofish` 等同批内核口径一致）；
     * 桌面 JVM / 个别机型失败时返回 false。
     */
    val available: Boolean by lazy {
        try {
            System.loadLibrary("keepasskey_argon2")
            // 探活试算：固定输入，结果仅用于 KAT 对照，对照后即刻清零
            val probe = deriveKey(
                password = ByteArray(PROBE_PASSWORD_LEN),
                salt = ByteArray(PROBE_SALT_LEN),
                secret = null,
                associatedData = null,
                iterations = 1,
                memoryKib = 8,
                parallelism = 1,
                version = 0x13,
                type = TYPE_ARGON2ID
            )
            val kat = hexToBytes(PROBE_KAT_HEX)
            val matches = probe != null && probe.size == kat.size && probe.contentEquals(kat)
            try {
                probe?.fill(0)
            } finally {
                kat.fill(0)
            }
            matches
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

    private fun hexToBytes(hex: String): ByteArray =
        ByteArray(hex.length / 2) { i -> hex.substring(i * 2, i * 2 + 2).toInt(16).toByte() }
}
