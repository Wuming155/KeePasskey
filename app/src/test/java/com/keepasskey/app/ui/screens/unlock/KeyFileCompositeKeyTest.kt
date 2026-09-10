package com.keepasskey.app.ui.screens.unlock

import com.keepasskey.core.model.KdbxGroup
import com.keepasskey.database.exception.KdbxInvalidCredentialsException
import com.keepasskey.database.file.KdbxDatabase
import com.keepasskey.database.file.KdbxFile
import com.keepasskey.database.file.KdbxHeader
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertThrows
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream

/**
 * ISSUE-P3-04 复合密钥因子端到端断言（真 KDBX 读写，公开 database API）：
 * 「携带密钥文件」与「不携带密钥文件」在同一假主密码下必须得出**不同复合密钥**，
 * 因而不携带密钥文件无法解密携带密钥文件保存的库。
 *
 * 本用例只调用既有 database 公开通道（`KdbxFile.save/load` → 内部
 * `KdbxKeyFile.extractKey` + `deriveKeys`），**不在 app 层重写任何 KeyFile 格式解析**，
 * 密钥文件字节为虚构的 32 字节裸格式（官方解析梯子第 2 档），密码为虚构假值。
 */
class KeyFileCompositeKeyTest {

    private fun buildVault(password: CharArray, keyFileData: ByteArray): ByteArray {
        val database = KdbxDatabase(
            header = KdbxHeader.createDefault(useArgon2 = false),
            databaseName = "KeyFileFactorVault",
            rootGroup = KdbxGroup(name = "Root")
        )
        val bos = ByteArrayOutputStream()
        KdbxFile.save(bos, database, password, keyFileData)
        return bos.toByteArray()
    }

    @Test
    fun `携带密钥文件与不携带派生出不同复合密钥`() {
        val keyFileData = FAKE_KEY_FILE_BYTES.copyOf()
        val bytes = buildVault(FAKE_PASSWORD.copyOf(), keyFileData)

        // 有 KeyFile：复合密钥 = SHA-256(SHA-256(pwd) ‖ keyFileKey) → 解锁成功
        val loaded = KdbxFile.load(ByteArrayInputStream(bytes), FAKE_PASSWORD.copyOf(), keyFileData)
        assertEquals("KeyFileFactorVault", loaded.databaseName)

        // 无 KeyFile：同一主密码必须失败 —— 反证复合密钥不同（无密钥文件因子）
        assertThrows(KdbxInvalidCredentialsException::class.java) {
            KdbxFile.load(ByteArrayInputStream(bytes), FAKE_PASSWORD.copyOf(), null)
        }

        // 不同内容的 KeyFile：同样必须失败（密钥文件因子逐字节参与复合密钥）
        val otherKeyFile = ByteArray(FAKE_KEY_FILE_BYTES.size) { (it * 5 + 2).toByte() }
        assertNotEquals(
            "测试自检：两个假密钥文件字节必须不同",
            FAKE_KEY_FILE_BYTES.toList(),
            otherKeyFile.toList()
        )
        assertThrows(KdbxInvalidCredentialsException::class.java) {
            KdbxFile.load(ByteArrayInputStream(bytes), FAKE_PASSWORD.copyOf(), otherKeyFile)
        }
    }

    private companion object {
        /** 虚构假主密码（绝不使用真实密码） */
        val FAKE_PASSWORD = "FakeCompositePass#2026".toCharArray()

        /** 虚构假密钥文件：32 字节裸格式密钥文件 */
        val FAKE_KEY_FILE_BYTES = ByteArray(32) { (it * 5 + 1).toByte() }
    }
}
