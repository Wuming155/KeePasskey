package com.keepasskey.database.file

import com.keepasskey.database.exception.KdbxCorruptFileException
import org.junit.Assert.assertThrows
import org.junit.Test

/**
 * H5 整改回归：KDF 参数上界校验。
 * 恶意构造的 KDBX 可声明超大 Argon2 内存（分配期 OOM）、超大 AES 轮数/迭代数（无限期占用 CPU），
 * 参数必须在解析期被拒绝（对照 KeePassDX Limits / KeePassXC 参数封顶语义）。
 */
class KdfParametersBoundsTest {

    // ================= Argon2 边界 =================

    @Test
    fun `Argon2 内存低于下界被拒绝`() {
        assertThrows(KdbxCorruptFileException::class.java) {
            KdbxHeader.validateArgon2Bounds(
                memoryInBytes = 1024L, iterations = 2L, parallelism = 2, version = 0x13
            )
        }
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
