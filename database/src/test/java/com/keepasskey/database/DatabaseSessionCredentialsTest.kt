package com.keepasskey.database

import com.keepasskey.database.session.DatabaseSession
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.util.Arrays

/**
 * 验证 DatabaseSession.useCredentials 安全凭据克隆与生命周期。
 */
class DatabaseSessionCredentialsTest {

    @get:Rule
    val tempFolder = TemporaryFolder()

    @Test
    fun testUseCredentialsReturnsClonesAndCleansUp() = runBlocking {
        val testFile = File(tempFolder.root, "creds_test.kdbx")
        val session = DatabaseSession()

        val originalPassword = "MyStrongSessionPassword!".toCharArray()
        val createResult = session.create(testFile, "Vault", originalPassword, useArgon2 = false)
        assertTrue(createResult.isSuccess)

        // 验证 useCredentials 提供独立克隆
        val blockExecuted = session.useCredentials { pwd, key ->
            assertNotNull(pwd)
            assertEquals(String(originalPassword), String(pwd!!))

            // 清零克隆数组（验证调用方清理逻辑）
            Arrays.fill(pwd, '0')
            true
        }
        assertTrue(blockExecuted)

        // 再次调用 useCredentials 验证原会话内部缓存未被外部清零破坏
        session.useCredentials { pwd, _ ->
            assertNotNull(pwd)
            assertEquals(String(originalPassword), String(pwd!!))
            Arrays.fill(pwd, '0')
        }

        session.close()
    }

    /** ISSUE-P2-312：清零责任由借出方承担——不依赖调用方自觉。 */
    @Test
    fun `useCredentials 返回前清零借出的主密码与密钥文件克隆`() = runBlocking {
        val testFile = File(tempFolder.root, "creds_wipe_test.kdbx")
        val session = DatabaseSession()
        val password = "SessionMasterPwd#1".toCharArray()
        val keyFile = byteArrayOf(9, 8, 7, 6, 5)
        assertTrue(
            session.create(testFile, "Vault", password, useArgon2 = false, keyFileData = keyFile).isSuccess
        )

        var handedPassword: CharArray? = null
        var handedKeyFile: ByteArray? = null
        val observed = session.useCredentials { pwd, key ->
            handedPassword = pwd
            handedKeyFile = key
            pwd?.concatToString()
        }

        // 块内确实拿到了可用凭据（否则「已清零」的断言可以是空转出来的）
        assertEquals(String(password), observed)
        assertEquals(password.size, handedPassword?.size)
        assertEquals(keyFile.size, handedKeyFile?.size)
        // 返回后借出副本已被 useCredentials 自身清零
        assertArrayEquals(CharArray(password.size) { '0' }, handedPassword)
        assertArrayEquals(ByteArray(keyFile.size), handedKeyFile)
        // 会话内部缓存不因借出副本的清零而被破坏
        session.useCredentials { pwd, key ->
            assertEquals(String(password), pwd?.concatToString())
            assertNotNull(key)
        }

        session.close()
    }

    /** ISSUE-P2-312：派生 / 解密失败的异常路径同样不得留下未擦除的凭据克隆。 */
    @Test
    fun `useCredentials 在块抛异常时同样清零借出的克隆`() = runBlocking {
        val testFile = File(tempFolder.root, "creds_wipe_throw_test.kdbx")
        val session = DatabaseSession()
        val password = "SessionMasterPwd#2".toCharArray()
        assertTrue(session.create(testFile, "Vault", password, useArgon2 = false).isSuccess)

        var handedPassword: CharArray? = null
        val thrown = runCatching {
            session.useCredentials { pwd, _ ->
                handedPassword = pwd
                throw IllegalStateException("模拟解密失败")
            }
        }.exceptionOrNull()

        assertTrue(thrown is IllegalStateException)
        assertArrayEquals(CharArray(password.size) { '0' }, handedPassword)

        session.close()
    }
}
