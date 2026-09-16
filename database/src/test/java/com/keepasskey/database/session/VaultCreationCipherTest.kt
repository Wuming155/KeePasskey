package com.keepasskey.database.session

import com.keepasskey.core.model.KdbxConstants
import com.keepasskey.core.model.KdbxUuid
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.util.Arrays

/**
 * ISSUE-P2-85：建库时**外层加密算法**必须真实落到 `.kdbx` 文件头，且能原样读回。
 *
 * 本用例覆盖缺陷的**行为面**：修复前 `SessionOpener.create` 把外层算法硬编码为
 * `AES_256_CBC`，无论调用方传入什么（`CreateVaultPreset` 的 ChaCha20 / Twofish 档）
 * 都写出 AES-256-CBC 库——UI 展示与产物长期不一致。此处对三种算法各做一次
 * 「建库 → 关闭 → 用同一口令重新打开 → 读文件头 `cipherUuid`」的真实往返，
 * 顺带证明 ChaCha20 的 12 字节 nonce / Twofish 的 16 字节 IV 长度分支都走得通。
 *
 * 与 app 模块 `CreateVaultPresetTest`（预设表 + 接线静态守卫）互补：
 * 那条守「选择有没有被传递」，本条守「传递后有没有真的写进文件」。
 */
class VaultCreationCipherTest {

    @get:Rule
    val tempFolder = TemporaryFolder()

    @Test
    fun `ChaCha20 预设建库后文件头算法为 ChaCha20`() = runBlocking {
        assertRoundTripCipher(
            fileName = "chacha20.kdbx",
            cipherUuid = KdbxConstants.Cipher.CHACHA20,
            useArgon2 = true
        )
    }

    @Test
    fun `Twofish 预设建库后文件头算法为 Twofish`() = runBlocking {
        assertRoundTripCipher(
            fileName = "twofish.kdbx",
            cipherUuid = KdbxConstants.Cipher.TWOFISH,
            useArgon2 = false
        )
    }

    @Test
    fun `AES-256 预设建库后文件头算法为 AES-256-CBC`() = runBlocking {
        assertRoundTripCipher(
            fileName = "aes256.kdbx",
            cipherUuid = KdbxConstants.Cipher.AES_256_CBC,
            useArgon2 = true
        )
    }

    @Test
    fun `省略算法时保持 KDBX4 官方默认 AES-256-CBC`() = runBlocking {
        // 反向锁：默认值不得被顺手改成 ChaCha20——「文件缺失即初始化」等无预设入口依赖它，
        // 且既有库互操作用例也基于该默认。若要变更默认，必须同步更新本用例并留痕。
        val file = File(tempFolder.root, "default.kdbx")
        val password = "Fake#DefaultCipher".toCharArray()
        try {
            val session = DatabaseSession()
            assertTrue(session.create(file, "default", password, useArgon2 = false).isSuccess)
            assertEquals(
                "省略 cipherUuid 时必须仍是 KDBX4 官方默认 AES-256-CBC",
                KdbxConstants.Cipher.AES_256_CBC,
                session.databaseFlow.value?.header?.cipherUuid
            )
            session.close()
        } finally {
            Arrays.fill(password, '0')
        }

        // 关闭后的重新打开必须能解出同一算法
        val reopened = "Fake#DefaultCipher".toCharArray()
        try {
            val session = DatabaseSession()
            assertTrue(session.open(file, reopened).isSuccess)
            assertEquals(
                KdbxConstants.Cipher.AES_256_CBC,
                session.databaseFlow.value?.header?.cipherUuid
            )
            session.close()
        } finally {
            Arrays.fill(reopened, '0')
        }
    }

    /** 建库 → 核对会话内文件头 → 关闭 → 重新打开 → 再核对文件头（真实往返，非仅内存断言） */
    private suspend fun assertRoundTripCipher(
        fileName: String,
        cipherUuid: KdbxUuid,
        useArgon2: Boolean
    ) {
        val file = File(tempFolder.root, fileName)
        val password = "Fake#CipherRoundTrip".toCharArray()
        try {
            val creator = DatabaseSession()
            val created = creator.create(
                file = file,
                name = fileName,
                passwordChars = password,
                useArgon2 = useArgon2,
                cipherUuid = cipherUuid
            )
            assertTrue("以算法 $cipherUuid 建库应成功：$created", created.isSuccess)
            assertEquals(
                "建库会话内的文件头算法必须等于调用方传入值",
                cipherUuid,
                creator.databaseFlow.value?.header?.cipherUuid
            )
            creator.close()
        } finally {
            Arrays.fill(password, '0')
        }

        val reopenPassword = "Fake#CipherRoundTrip".toCharArray()
        try {
            val reader = DatabaseSession()
            val opened = reader.open(file, reopenPassword)
            assertTrue("重新打开应成功（算法 $cipherUuid）：$opened", opened.isSuccess)
            assertEquals(
                "落盘文件头的算法必须等于建库时选择的算法（$fileName）",
                cipherUuid,
                reader.databaseFlow.value?.header?.cipherUuid
            )
            reader.close()
        } finally {
            Arrays.fill(reopenPassword, '0')
        }
    }
}
