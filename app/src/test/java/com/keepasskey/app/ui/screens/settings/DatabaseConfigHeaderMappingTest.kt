package com.keepasskey.app.ui.screens.settings

import com.keepasskey.core.model.KdbxConstants
import com.keepasskey.core.model.KdbxGroup
import com.keepasskey.core.model.KdbxUuid
import com.keepasskey.crypto.kdf.KdfParameters
import com.keepasskey.database.file.KdbxDatabase
import com.keepasskey.database.file.KdbxHeader
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * 「密码库与加密」页文件头映射测试（ISSUE-P2-19 / P3-59 整改验收）。
 *
 * 此前该页加密算法 / KDF / Argon2 参数 / 压缩 / 默认用户名全部来自硬编码占位默认值，
 * 与真实文件头不符（ChaCha20 显示 vs AES 实际、Argon2d·8轮 vs Argon2id·2轮、路径/用户名留空）。
 * 本测试锁定 [databaseConfigFromHeader] 以活动库文件头为单一真相源的映射行为。
 */
class DatabaseConfigHeaderMappingTest {

    private fun database(
        cipherUuid: KdbxUuid,
        kdfParameters: KdfParameters,
        compression: Int = KdbxConstants.Compression.GZIP,
        defaultUserName: String = ""
    ): KdbxDatabase = KdbxDatabase(
        header = KdbxHeader(
            version = KdbxConstants.Version.VERSION_4_0,
            cipherUuid = cipherUuid,
            compression = compression,
            kdfParameters = kdfParameters
        ),
        databaseName = "test-vault.kdbx",
        rootGroup = KdbxGroup(name = "Root"),
        defaultUserName = defaultUserName
    )

    @Test
    fun `AES 文件头显示 AES-256-CBC 而非 ChaCha20`() {
        val kdf = KdfParameters.Argon2(
            type = KdfParameters.Argon2.Argon2Type.ARGON2ID,
            salt = ByteArray(32),
            parallelism = 2,
            memoryInBytes = 64L * 1024 * 1024,
            iterations = 2L
        )
        val config = databaseConfigFromHeader(
            database(KdbxConstants.Cipher.AES_256_CBC, kdf)
        )

        assertEquals("AES-256-CBC (256-bit)", config.encryptionAlgorithm)
        assertEquals("Argon2id", config.kdfAlgorithm)
        assertEquals(2L, config.argon2Iterations)
        assertEquals(64L, config.argon2MemoryMb)
        assertEquals(2, config.argon2Parallelism)
    }

    @Test
    fun `ChaCha20 与 Twofish 文件头映射各自标签`() {
        val kdf = KdfParameters.Argon2(
            type = KdfParameters.Argon2.Argon2Type.ARGON2D,
            salt = ByteArray(32)
        )
        assertEquals(
            "ChaCha20-Poly1305 (256-bit)",
            databaseConfigFromHeader(database(KdbxConstants.Cipher.CHACHA20, kdf)).encryptionAlgorithm
        )
        assertEquals(
            "Twofish-CBC (256-bit)",
            databaseConfigFromHeader(database(KdbxConstants.Cipher.TWOFISH, kdf)).encryptionAlgorithm
        )
        assertEquals("Argon2d", databaseConfigFromHeader(database(KdbxConstants.Cipher.CHACHA20, kdf)).kdfAlgorithm)
    }

    @Test
    fun `AES-KDF 文件头映射 AES-KDF 标签且 Argon2 参数归零`() {
        val config = databaseConfigFromHeader(
            database(
                KdbxConstants.Cipher.AES_256_CBC,
                KdfParameters.Aes(seed = ByteArray(32), rounds = 600_000L)
            )
        )
        assertEquals("AES-KDF", config.kdfAlgorithm)
        assertEquals(0L, config.argon2Iterations)
        assertEquals(0L, config.argon2MemoryMb)
        assertEquals(0, config.argon2Parallelism)
    }

    @Test
    fun `未压缩文件头映射无压缩`() {
        val kdf = KdfParameters.Argon2(
            type = KdfParameters.Argon2.Argon2Type.ARGON2ID,
            salt = ByteArray(32)
        )
        val config = databaseConfigFromHeader(
            database(KdbxConstants.Cipher.AES_256_CBC, kdf, compression = KdbxConstants.Compression.NONE)
        )
        assertEquals("无压缩", config.compressionAlgorithm)
    }

    @Test
    fun `默认用户名取自 Meta 可为空（UI 端负责未设置占位）`() {
        val kdf = KdfParameters.Argon2(
            type = KdfParameters.Argon2.Argon2Type.ARGON2ID,
            salt = ByteArray(32)
        )
        val empty = databaseConfigFromHeader(database(KdbxConstants.Cipher.AES_256_CBC, kdf))
        assertEquals("", empty.defaultUsername)

        val named = databaseConfigFromHeader(
            database(KdbxConstants.Cipher.AES_256_CBC, kdf, defaultUserName = "tester")
        )
        assertEquals("tester", named.defaultUsername)
    }
}
