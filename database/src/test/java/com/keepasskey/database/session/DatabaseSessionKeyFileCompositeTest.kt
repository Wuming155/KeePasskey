package com.keepasskey.database.session

import com.keepasskey.core.result.KdbxResult
import com.keepasskey.database.exception.KdbxInvalidCredentialsException
import com.keepasskey.database.file.KdbxKeyFile
import com.keepasskey.database.file.KdbxKeyFileGenerator
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.security.MessageDigest

/**
 * ISSUE-P3-21 回归测试：建库侧「生成附属密钥文件」假开关整改——复合密钥第二因子必须真实落地。
 *
 * 覆盖官方 `CompositeKey` 三分支在 `DatabaseSession` 上的真实读写：
 * - 仅主密码（既有行为：`keyFileData = null`）；
 * - 主密码 + 密钥文件（建库后**同一密钥文件可解锁**、**仅主密码必须失败**）；
 * - 仅密钥文件（空主密码）；
 * 以及生成器与既有解析实现 `KdbxKeyFile` 的 round-trip、既有导出通道的交付一致性、
 * 保存后重开仍需求同一第二因子。
 *
 * 说明：本文件位于 `database/src/test/java/com/keepasskey/database/session/`（本次并行整改的
 * 文件所有权范围），被测对象是 `DatabaseSession.create` 与本任务新增的
 * `database/file/KdbxKeyFileGenerator`；不使用任何真实密钥材料（密钥全部现场随机生成）。
 * 全部用例仅依赖 JVM + JUnit4，不触碰 `android.*`。
 */
class DatabaseSessionKeyFileCompositeTest {

    @get:Rule
    val tempFolder = TemporaryFolder()

    /** 虚构测试主密码（禁用真实口令） */
    private val masterPassword = "Fake#P3-21-Master".toCharArray()

    private fun vaultFile(name: String): File = File(tempFolder.root, name)

    @Test
    fun `以主密码与生成型密钥文件建库后携带同一密钥文件可解锁`() = runBlocking {
        val target = vaultFile("composite.kdbx")
        val keyFile = KdbxKeyFileGenerator.generate()
        try {
            val creator = DatabaseSession()
            assertTrue(
                "复合密钥建库必须成功",
                creator.create(
                    target, "CompositeVault", masterPassword.clone(),
                    useArgon2 = false, keyFileData = keyFile
                ).isSuccess
            )
            creator.close()

            val opener = DatabaseSession()
            val unlocked = opener.open(target, masterPassword.clone(), keyFile.clone())
            assertTrue("携带同一密钥文件必须能解锁", unlocked.isSuccess)
            assertEquals(
                "解密出的必须是真实载荷（根分组名与建库一致）",
                "CompositeVault",
                opener.databaseFlow.value?.rootGroup?.name
            )
            opener.close()
        } finally {
            keyFile.fill(0)
        }
    }

    @Test
    fun `复合密钥库仅以主密码解锁必须失败`() = runBlocking {
        val target = vaultFile("password-only-rejected.kdbx")
        val keyFile = KdbxKeyFileGenerator.generate()
        try {
            val creator = DatabaseSession()
            assertTrue(
                creator.create(
                    target, "CompositeVault", masterPassword.clone(),
                    useArgon2 = false, keyFileData = keyFile
                ).isSuccess
            )
            creator.close()

            val opener = DatabaseSession()
            val rejected = opener.open(target, masterPassword.clone(), keyFileData = null)
            assertTrue("缺少密钥文件因子必须以凭据错误失败", rejected.isFailure)
            assertTrue(
                "失败分型必须是凭据错误（而非文件损坏等其它原因）",
                (rejected as KdbxResult.Failure).error is KdbxInvalidCredentialsException
            )
            opener.close()
        } finally {
            keyFile.fill(0)
        }
    }

    @Test
    fun `用户选定的既有密钥文件字节真实参与复合密钥`() = runBlocking {
        val target = vaultFile("existing-keyfile.kdbx")
        // 外部既有密钥文件：v1.0 Base64 形态（非本应用生成器的 v2.0 产物），
        // 证明任意合法 KeyFile 经同一解析通道参与复合密钥
        val rawKey = ByteArray(32) { (it * 7 + 3).toByte() }
        val base64 = java.util.Base64.getEncoder().encodeToString(rawKey)
        val externalKeyFile = """<?xml version="1.0" encoding="UTF-8"?>
<KeyFile>
    <Meta><Version>1.0</Version></Meta>
    <Key><Data>$base64</Data></Key>
</KeyFile>
""".toByteArray(Charsets.UTF_8)
        try {
            assertArrayEquals("前置条件：既有密钥文件必须能被既有解析实现还原", rawKey, KdbxKeyFile.extractKey(externalKeyFile))

            val creator = DatabaseSession()
            assertTrue(
                creator.create(
                    target, "ExistingKeyFileVault", masterPassword.clone(),
                    useArgon2 = false, keyFileData = externalKeyFile
                ).isSuccess
            )
            creator.close()

            val opener = DatabaseSession()
            assertTrue(
                "SELECT_EXISTING 选中的密钥文件必须真实参与复合密钥",
                opener.open(target, masterPassword.clone(), externalKeyFile.clone()).isSuccess
            )
            opener.close()

            val passwordOnly = DatabaseSession()
            assertTrue(
                "该库不得被仅密码解开（证明第二因子确实生效）",
                passwordOnly.open(target, masterPassword.clone(), keyFileData = null).isFailure
            )
            passwordOnly.close()
        } finally {
            rawKey.fill(0)
            externalKeyFile.fill(0)
        }
    }

    @Test
    fun `仅密钥文件（空主密码）库按官方三分支派生`() = runBlocking {
        val target = vaultFile("key-only.kdbx")
        val keyFile = KdbxKeyFileGenerator.generate()
        try {
            val creator = DatabaseSession()
            assertTrue(
                creator.create(
                    target, "KeyOnlyVault", CharArray(0),
                    useArgon2 = false, keyFileData = keyFile
                ).isSuccess
            )
            creator.close()

            val opener = DatabaseSession()
            assertTrue(
                "空主密码 + 同一密钥文件必须可解锁（官方仅密钥文件分支）",
                opener.open(target, CharArray(0), keyFile.clone()).isSuccess
            )
            opener.close()

            val noFactor = DatabaseSession()
            assertTrue(
                "既无密码也无密钥文件时不得解锁",
                noFactor.open(target, CharArray(0), keyFileData = null).isFailure
            )
            noFactor.close()
        } finally {
            keyFile.fill(0)
        }
    }

    @Test
    fun `建库后既有导出通道交付的正是所用密钥文件字节`() = runBlocking {
        val target = vaultFile("export-channel.kdbx")
        val keyFile = KdbxKeyFileGenerator.generate()
        val session = DatabaseSession()
        try {
            assertTrue(
                session.create(
                    target, "ExportChannelVault", masterPassword.clone(),
                    useArgon2 = false, keyFileData = keyFile
                ).isSuccess
            )
            val exported = session.exportKeyFileBytes()
            assertTrue("复合密钥库必须能经既有导出通道交付密钥文件", exported != null)
            assertArrayEquals("导出的必须正是建库所用密钥文件字节", keyFile, exported)
        } finally {
            session.close()
            keyFile.fill(0)
        }
    }

    @Test
    fun `复合密钥库保存后再打开仍需同一密钥文件`() = runBlocking {
        val target = vaultFile("save-reopen.kdbx")
        val keyFile = KdbxKeyFileGenerator.generate()
        try {
            val creator = DatabaseSession()
            assertTrue(
                creator.create(
                    target, "SaveReopenVault", masterPassword.clone(),
                    useArgon2 = false, keyFileData = keyFile
                ).isSuccess
            )
            assertTrue("会话保存必须以同一复合密钥重新派生", creator.save().isSuccess)
            creator.close()

            val opener = DatabaseSession()
            assertTrue(
                "保存后的库仍须由同一密钥文件解开",
                opener.open(target, masterPassword.clone(), keyFile.clone()).isSuccess
            )
            opener.close()

            val passwordOnly = DatabaseSession()
            assertTrue(
                "保存不得把库降级为可被仅密码解开",
                passwordOnly.open(target, masterPassword.clone(), keyFileData = null).isFailure
            )
            passwordOnly.close()
        } finally {
            keyFile.fill(0)
        }
    }

    @Test
    fun `生成器产出的 XML 密钥文件可被既有解析实现还原为同一 32 字节密钥`() {
        val keyFileBytes = KdbxKeyFileGenerator.generate()
        val first = KdbxKeyFile.extractKey(keyFileBytes)
        val second = KdbxKeyFile.extractKey(keyFileBytes)
        try {
            assertEquals(
                "官方 KeyFile 密钥长度固定 32 字节",
                KdbxKeyFileGenerator.KEY_LENGTH_BYTES,
                first.size
            )
            assertArrayEquals("同一密钥文件的解析必须确定", first, second)

            val xml = keyFileBytes.toString(Charsets.UTF_8)
            assertTrue("必须声明官方 v2.0 版本", xml.contains("<Version>2.0</Version>"))
            val expectedHash = MessageDigest.getInstance("SHA-256").digest(first)
                .copyOf(HASH_PREFIX_BYTES)
                .joinToString("") { "%02X".format(it) }
            assertTrue(
                "Hash 属性必须为密钥 SHA-256 前 $HASH_PREFIX_BYTES 字节（解析侧强制校验）",
                xml.contains("Hash=\"$expectedHash\"")
            )
        } finally {
            first.fill(0)
            second.fill(0)
            keyFileBytes.fill(0)
        }
    }

    @Test
    fun `生成器两次产出不同的密钥内容`() {
        val firstFile = KdbxKeyFileGenerator.generate()
        val secondFile = KdbxKeyFileGenerator.generate()
        val firstKey = KdbxKeyFile.extractKey(firstFile)
        val secondKey = KdbxKeyFile.extractKey(secondFile)
        try {
            assertFalse("CSPRNG 必须每次产出不同密钥材料", firstKey.contentEquals(secondKey))
            assertFalse("同源文件内容亦不得相同", firstFile.contentEquals(secondFile))
        } finally {
            firstKey.fill(0)
            secondKey.fill(0)
            firstFile.fill(0)
            secondFile.fill(0)
        }
    }

    private companion object {
        /** 与生成器一致的 Hash 属性长度（字节）：官方公式为 SHA-256 前 4 字节 */
        const val HASH_PREFIX_BYTES = 4
    }
}
