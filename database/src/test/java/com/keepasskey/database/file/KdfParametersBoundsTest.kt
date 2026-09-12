package com.keepasskey.database.file

import com.keepasskey.database.exception.KdbxCorruptFileException
import org.junit.Assert.assertThrows
import org.junit.Test

/**
 * H5 整改回归：KDF 参数上界校验。
 * 恶意构造的 KDBX 可声明超大 Argon2 内存（分配期 OOM）、超大 AES 轮数/迭代数（无限期占用 CPU），
 * 参数必须在解析期被拒绝（对照 KeePassDX Limits / KeePassXC 参数封顶语义）。
 *
 * D7 整改补充：Argon2 内存**下界**对齐官方 `Argon2Kdf.MinMemory = 1024 * 8 = 8192` 字节。
 * 原下界 1 MiB 严于规范 128 倍，会误拒官方客户端写出的合法小内存库（如 P=1 的低配配置）。
 * 上界与迭代/并行度上界仍为本仓**更严的 fail-closed 防 DoS 封顶**（对照表见
 * `KdbxKdfParameterCodec` 类 KDoc），宽于一切合法用户配置，正常文件不受影响。
 */
class KdfParametersBoundsTest {

    // ================= Argon2 边界 =================

    @Test
    fun `Argon2 内存低于下界被拒绝`() {
        // 官方下界 8192：8191 与更小的 1024 均须拒绝
        for (belowMin in listOf(1024L, 8191L)) {
            assertThrows(
                "memoryInBytes=$belowMin 低于官方下界 8192，必须拒绝",
                KdbxCorruptFileException::class.java
            ) {
                KdbxHeader.validateArgon2Bounds(
                    memoryInBytes = belowMin, iterations = 2L, parallelism = 2, version = 0x13
                )
            }
        }
    }

    /**
     * D7 核心：官方下界 8192 字节（`Argon2Kdf.MinMemory`）必须**合法通过**。
     * 这是「不误拒合法库」的判据——原 1 MiB 下界会在此失败。
     */
    @Test
    fun `Argon2 内存等于官方下界 8192 合法通过`() {
        KdbxHeader.validateArgon2Bounds(
            memoryInBytes = 8192L, iterations = 1L, parallelism = 1, version = 0x10
        )
    }

    /** 下界之上的常见小内存配置（如 16 KiB）亦须合法。 */
    @Test
    fun `Argon2 内存略高于官方下界合法通过`() {
        KdbxHeader.validateArgon2Bounds(
            memoryInBytes = 16L * 1024, iterations = 2L, parallelism = 1, version = 0x13
        )
    }

    @Test
    fun `Argon2 内存超过绝对上界被拒绝`() {
        assertThrows(KdbxCorruptFileException::class.java) {
            KdbxHeader.validateArgon2Bounds(
                memoryInBytes = 8L * 1024 * 1024 * 1024, iterations = 2L, parallelism = 2, version = 0x13
            )
        }
    }

    @Test
    fun `Argon2 迭代越界被拒绝`() {
        assertThrows(KdbxCorruptFileException::class.java) {
            KdbxHeader.validateArgon2Bounds(
                memoryInBytes = 64L * 1024 * 1024, iterations = 0L, parallelism = 2, version = 0x13
            )
        }
        assertThrows(KdbxCorruptFileException::class.java) {
            KdbxHeader.validateArgon2Bounds(
                memoryInBytes = 64L * 1024 * 1024, iterations = (1L shl 24) + 1, parallelism = 2, version = 0x13
            )
        }
    }

    @Test
    fun `Argon2 并行度越界被拒绝`() {
        assertThrows(KdbxCorruptFileException::class.java) {
            KdbxHeader.validateArgon2Bounds(
                memoryInBytes = 64L * 1024 * 1024, iterations = 2L, parallelism = 0, version = 0x13
            )
        }
        assertThrows(KdbxCorruptFileException::class.java) {
            KdbxHeader.validateArgon2Bounds(
                memoryInBytes = 64L * 1024 * 1024, iterations = 2L, parallelism = 65, version = 0x13
            )
        }
    }

    @Test
    fun `Argon2 非法版本被拒绝`() {
        assertThrows(KdbxCorruptFileException::class.java) {
            KdbxHeader.validateArgon2Bounds(
                memoryInBytes = 64L * 1024 * 1024, iterations = 2L, parallelism = 2, version = 0x99
            )
        }
    }

    @Test
    fun `Argon2 合法参数通过校验`() {
        // 常规配置（64MB / 3 轮 / 4 并行）不受影响
        KdbxHeader.validateArgon2Bounds(
            memoryInBytes = 64L * 1024 * 1024, iterations = 3L, parallelism = 4, version = 0x13
        )
        // Argon2 v1.0 旧版本文件合法
        KdbxHeader.validateArgon2Bounds(
            memoryInBytes = 16L * 1024 * 1024, iterations = 2L, parallelism = 2, version = 0x10
        )
    }

    // ================= AES-KDF 边界 =================

    @Test
    fun `AES-KDF 轮数越界被拒绝`() {
        assertThrows(KdbxCorruptFileException::class.java) {
            KdbxHeader.validateAesKdfBounds(rounds = 0L)
        }
        assertThrows(KdbxCorruptFileException::class.java) {
            KdbxHeader.validateAesKdfBounds(rounds = (1L shl 28) + 1)
        }
    }

    @Test
    fun `AES-KDF 合法轮数通过校验`() {
        // 官方默认 6,000,000 轮量级（KeePass 2.x 事实标准）
        KdbxHeader.validateAesKdfBounds(rounds = 6_000_000L)
        KdbxHeader.validateAesKdfBounds(rounds = 1L)
    }
}
