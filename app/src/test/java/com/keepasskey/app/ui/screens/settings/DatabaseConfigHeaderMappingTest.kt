package com.keepasskey.app.ui.screens.settings

import com.keepasskey.core.model.KdbxConstants
import com.keepasskey.core.model.KdbxGroup
import com.keepasskey.core.model.KdbxUuid
import com.keepasskey.crypto.kdf.KdfParameters
import com.keepasskey.database.file.KdbxDatabase
import com.keepasskey.database.file.KdbxHeader
import com.keepasskey.app.testutil.stripCommentsOnly
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * 「密码库与加密」页文件头映射测试（ISSUE-P2-19 / P3-59 整改验收；
 * ISSUE-P3-92 追加标签词汇表收敛守卫）。
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
            // ISSUE-P3-92：ChaCha20 无 Poly1305 AEAD 标签（此前误标为 ChaCha20-Poly1305）
            "ChaCha20 (256-bit)",
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

    // ===== ISSUE-P3-92：加密算法标签的词汇表守卫 =====

    /**
     * 本仓**没有** ChaCha20-Poly1305 这个 AEAD：KDBX4 外层只做 ChaCha20（RFC 8439）流加密，
     * 完整性由独立的 HMAC-SHA256 块流承担。仓库主源码（剔除注释后）不得再出现 `Poly1305` 字样
     * ——出现即意味着 UI 又向用户声明了一层并不存在的认证保护。
     */
    @Test
    fun `主源码剔除注释后不得再出现 Poly1305 字样`() {
        val offenders = mainSourceFiles()
            .filter { stripComments(it.readText()).contains(POLY1305) }
            .map { it.name }

        assertFalse(
            "以下文件仍向用户声明不存在的 AEAD（ChaCha20-Poly1305）：$offenders",
            offenders.isNotEmpty()
        )
    }

    /**
     * 标签必须来自单一词汇表 [CipherLabels]：三个字面量在仓库主源码中只允许各出现一次
     * （即其定义处）。此前三处独立硬编码且**一致地错**，正是本条的成因。
     */
    @Test
    fun `加密算法标签必须收敛到单一词汇表`() {
        val sources = mainSourceFiles().map { it.name to stripComments(it.readText()) }

        listOf(CipherLabels.CHACHA20, CipherLabels.AES_256_CBC, CipherLabels.TWOFISH_CBC).forEach { label ->
            val holders = sources.filter { (_, text) -> text.contains("\"$label\"") }.map { it.first }
            assertEquals("标签「$label」只应定义于 CipherLabels.kt：$holders", listOf(LABELS_SOURCE_NAME), holders)
        }
    }

    /** `app/src/main/java` 下全部 Kotlin 源文件 */
    private fun mainSourceFiles(): List<File> =
        File(repositoryRoot, "app/src/main/java")
            .walkTopDown()
            .filter { it.isFile && it.extension == "kt" }
            .toList()

    /** 剔除块注释与行注释——整改说明本身会写出被断言的字面量 */
    private fun stripComments(source: String): String = stripCommentsOnly(source)

    private companion object {
        const val POLY1305 = "Poly1305"
        const val LABELS_SOURCE_NAME = "CipherLabels.kt"

        /** 仓库根：同时具备 app 与 core 模块源码目录的最近祖先 */
        val repositoryRoot: File by lazy {
            var dir: File? = File(System.getProperty("user.dir").orEmpty()).absoluteFile
            repeat(ROOT_SEARCH_DEPTH) {
                val candidate = dir ?: return@repeat
                if (File(candidate, "app/src/main/java").isDirectory &&
                    File(candidate, "core/src/main/java").isDirectory
                ) {
                    return@lazy candidate
                }
                dir = candidate.parentFile
            }
            error("无法定位仓库根目录（起始：${System.getProperty("user.dir")}）")
        }

        const val ROOT_SEARCH_DEPTH = 4
    }
}
