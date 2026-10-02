package com.keepasskey.database.session

import com.keepasskey.core.model.KdbxConstants
import com.keepasskey.core.model.KdbxEntry
import com.keepasskey.core.model.KdbxGroup
import com.keepasskey.core.security.ProtectedString
import com.keepasskey.database.exception.KdbxInvalidCredentialsException
import com.keepasskey.database.file.KdbxDatabase
import com.keepasskey.database.file.KdbxFile
import com.keepasskey.database.file.KdbxHeader
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import org.junit.Assert.assertThrows

/**
 * ISSUE-P3-430：`changeKeyFileOnly(newKeyFileData)` 语义回归——
 * **主密码分量原样保留，仅改绑密钥文件因子**（app 层「不改密码、单独设置密钥文件」的底层支撑）。
 *
 * 1. 密码 + 密钥文件库换绑新密钥文件 → 产物「旧密码 + 新密钥文件」可开、旧密钥文件打不开；
 * 2. 仅密码库绑定密钥文件 → 产物「旧密码 + 密钥文件」可开、旧密码单独打不开；
 * 3. 仅密钥文件会话（无主密码分量）解绑唯一因子 → fail-closed 拒绝（不得产出无因子库）。
 * 任何一条红都意味着密码分量未被真实保留或 fail-closed 闸门失效。
 */
class ChangeKeyFileOnlyTest {

    private val keyFileA = ByteArray(64) { (it + 1).toByte() }
    private val keyFileB = ByteArray(64) { (it + 65).toByte() }

    private fun initialBytes(password: CharArray?, keyFileData: ByteArray?): ByteArray {
        val entry = KdbxEntry(
            fields = linkedMapOf(
                KdbxConstants.Fields.PASSWORD to ProtectedString("secret#2026", isProtected = true)
            )
        )
        val db = KdbxDatabase(
            header = KdbxHeader.createDefault(useArgon2 = false),
            rootGroup = KdbxGroup(name = "Root", entries = listOf(entry))
        )
        return ByteArrayOutputStream()
            .also { KdbxFile.save(it, db, password, keyFileData) }
            .toByteArray()
    }

    /** 打开会话（按初始凭据）并执行 changeKeyFileOnly，返回写盘产物 */
    private suspend fun change(
        initial: ByteArray,
        oldPwd: CharArray?,
        oldKeyFile: ByteArray?,
        newKeyFile: ByteArray?
    ): ByteArray {
        var saved: ByteArray? = null
        val session = DatabaseSession()
        assertTrue(
            "前置：会话打开失败",
            session.openStream(
                pathIdentifier = "kfonly.kdbx",
                inputStreamProvider = { ByteArrayInputStream(initial) },
                saveWriter = { bytes -> saved = bytes.copyOf() },
                passwordChars = oldPwd,
                keyFileData = oldKeyFile
            ).isSuccess
        )
        assertTrue("仅改密钥文件失败", session.changeKeyFileOnly(newKeyFile).isSuccess)
        // 快照契约：改绑后可从会话读出保留的主密码分量（克隆副本，读取即清零）
        session.passwordSnapshot()?.fill('0')
        val written = saved
        assertNotNull("改绑后必须触发一次写盘", written)
        session.close()
        return written!!
    }

    @Test
    fun `密码加密钥文件库换绑后旧密码保留且旧密钥文件失效`() = runBlocking {
        val pwd = "Keep#Pass#2026".toCharArray()
        val written = change(initialBytes(pwd, keyFileA), pwd, keyFileA, keyFileB)

        // 旧密码 + 旧密钥文件：必须打不开（改绑真实生效）
        assertThrows(KdbxInvalidCredentialsException::class.java) {
            KdbxFile.load(ByteArrayInputStream(written), pwd, keyFileA)
        }
        // 旧密码 + 新密钥文件：必须可开（密码分量真实保留）
        val reopened = KdbxFile.load(ByteArrayInputStream(written), pwd, keyFileB)
        assertTrue("旧密码 + 新密钥文件必须可解锁", reopened.rootGroup.entries.isNotEmpty())
    }

    @Test
    fun `仅密码库绑定密钥文件后旧密码单独失效且新组合可开`() = runBlocking {
        val pwd = "Keep#Pass#2026".toCharArray()
        val written = change(initialBytes(pwd, null), pwd, null, keyFileA)

        assertThrows(KdbxInvalidCredentialsException::class.java) {
            KdbxFile.load(ByteArrayInputStream(written), pwd, null)
        }
        val reopened = KdbxFile.load(ByteArrayInputStream(written), pwd, keyFileA)
        assertTrue("旧密码 + 新密钥文件必须可解锁", reopened.rootGroup.entries.isNotEmpty())
    }

    @Test
    fun `仅密钥文件会话解绑唯一因子必须被拒绝`() = runBlocking {
        var saved: ByteArray? = null
        val session = DatabaseSession()
        assertTrue(
            "前置：会话打开失败",
            session.openStream(
                pathIdentifier = "kfonly-null.kdbx",
                inputStreamProvider = { ByteArrayInputStream(initialBytes(null, keyFileA)) },
                saveWriter = { bytes -> saved = bytes.copyOf() },
                passwordChars = null,
                keyFileData = keyFileA
            ).isSuccess
        )
        val result = session.changeKeyFileOnly(null)
        assertFalse("解绑唯一因子必须 fail-closed 拒绝", result.isSuccess)
        // 会话凭据不得被破坏：密钥文件因子仍在，仍可走 changeCredentials
        assertTrue("被拒后快照通道应仍可用", session.passwordSnapshot() == null)
        session.close()
    }
}
