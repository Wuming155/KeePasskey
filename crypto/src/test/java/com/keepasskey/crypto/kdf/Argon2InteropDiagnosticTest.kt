package com.keepasskey.crypto.kdf

import com.keepasskey.core.model.KdbxConstants
import com.keepasskey.core.model.KdbxUuid
import com.keepasskey.crypto.kdf.KdfParameters.Argon2.Argon2Type
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Test
import java.security.MessageDigest

/**
 * 临时诊断测试：真机 test.kdbx（KeePass 2.x 生成，Argon2d）的 transformedKey 与
 * pykeepass/libargon2 参考值（sha256=37d0cbcf731e5be0c110f81df8da610a53152d1a292fc55f81744f872533efdf）比对。
 * 参考基准（pykeepass compute_key_composite + argon2-cffi hash_secret_raw）：
 * composite sha256 = da1c26c725496f55905ea808729e8d81dfbd4fc8be2d3d60bf6f5c34df74f647
 */
class Argon2InteropDiagnosticTest {

    @Test
    fun `transformed key matches libargon2 reference for real world file`() {
        val password = "xdqaCEGFEAHBETAH72732/*632."

        // 复合密钥 = SHA256(SHA256(pwd) ‖ keyfileKey32)，keyfileKey 来自 XML v2.0 十六进制 Data
        val passwordHash = MessageDigest.getInstance("SHA-256").digest(password.toByteArray(Charsets.UTF_8))
        val keyFileKey = "7DDC70C9FED76DEE09DBFCE3FA317E7F200C71F23651617F5225F44CB212ABDE"
            .chunked(2).map { it.toInt(16).toByte() }.toByteArray()
        val composite = MessageDigest.getInstance("SHA-256")
            .digest(passwordHash + keyFileKey)
        assertArrayEquals(
            "composite 与 pykeepass 基准不一致",
            "e1ae9645d9b7f9f459a25be856005dbc6f7effcdc298a64b458beb5572e8689e".chunked(2)
                .map { it.toInt(16).toByte() }.toByteArray(),
            composite
        )

        // 真实文件的 Argon2 参数
        val salt = "327c276fe8c3ece964e8595c6a251915c4ade462235a822b57ff24746729b278"
            .chunked(2).map { it.toInt(16).toByte() }.toByteArray()
        val params = KdfParameters.Argon2(
            type = Argon2Type.ARGON2D,
            salt = salt,
            parallelism = 4,
            memoryInBytes = 64L * 1024 * 1024,
            iterations = 89L,
            version = 19
        )

        val engine = Argon2KdfEngine(Argon2Type.ARGON2D)
        val transformed = engine.transform(composite, params)
        val digest = MessageDigest.getInstance("SHA-256").digest(transformed)
        val hex = digest.joinToString("") { "%02x".format(it) }
        println("BC transformedKey sha256 = $hex")

        assertEquals(
            "transformedKey 与 libargon2/pykeepass 参考不一致",
            "37d0cbcf731e5be0c110f81df8da610a53152d1a292fc55f81744f872533efdf",
            hex
        )
    }
}
