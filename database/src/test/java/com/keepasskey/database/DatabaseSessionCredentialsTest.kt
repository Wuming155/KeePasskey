package com.keepasskey.database

import com.keepasskey.database.session.DatabaseSession
import kotlinx.coroutines.runBlocking
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
}
