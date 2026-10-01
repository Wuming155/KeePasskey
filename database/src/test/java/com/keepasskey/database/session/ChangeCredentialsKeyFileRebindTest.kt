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
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import org.junit.Assert.assertThrows

/**
 * ISSUE-P3-428：双参 `changeCredentials(newPasswordChars, newKeyFileData)` 的**改绑语义**回归。
 *
 * 上游（app 层 ISSUE-P3-428）新增「已有库绑定 / 更换 / 解绑密钥文件第二因子」入口，
 * 三态分别落到本层双参重载：传字节 = 绑定或更换、显式 `null` = 解绑（单参重载沿用
 * 快照语义不变，由 [ChangeCredentialsKeyFileWipeTest] 锁定）。本类只验证双参路径
 * **写出的库到底用哪组凭据能开**：
 * 1. 对仅密码库传入密钥文件 → 产物必须「新密码 + 密钥文件」可开、**新密码单独不可开**；
 * 2. 对密码 + 密钥文件库传 `null` → 产物必须「新密码」单独可开、**新密码 + 旧密钥文件不可开**。
 * 任何一条红了都意味着「改绑」实际没有改绑（如静默沿用快照），上游三态即成虚假开关。
 */
class ChangeCredentialsKeyFileRebindTest {

    private val keyFile = ByteArray(64) { (it + 1).toByte() }

    private fun initialBytes(password: CharArray, keyFileData: ByteArray?): ByteArray {
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

    private suspend fun openAndChange(
        initial: ByteArray,
        oldPwd: CharArray,
        initialKeyFile: ByteArray?,
        newPwd: CharArray,
        newKeyFile: ByteArray?
    ): ByteArray {
        var saved: ByteArray? = null
        val session = DatabaseSession()
        assertTrue(
            "前置：会话打开失败",
            session.openStream(
                pathIdentifier = "rebind.kdbx",
                inputStreamProvider = { ByteArrayInputStream(initial) },
                saveWriter = { bytes -> saved = bytes.copyOf() },
                passwordChars = oldPwd,
                keyFileData = initialKeyFile
            ).isSuccess
        )
        assertTrue("换凭据失败", session.changeCredentials(newPwd, newKeyFile).isSuccess)
        val written = saved
        assertNotNull("换凭据后必须触发一次写盘", written)
        session.close()
        return written!!
    }

    @Test
    fun `对仅密码库绑定密钥文件后产物必须以新密码加密钥文件开启`() = runBlocking {
        val oldPwd = "Old#Pass#2026".toCharArray()
        val newPwd = "New#Pass#2026".toCharArray()
        val written = openAndChange(initialBytes(oldPwd, null), oldPwd, null, newPwd, keyFile)

        // 行为级核心断言：绑定必须真实生效——旧凭据形态（新密码单独）必须打不开
        assertThrows(KdbxInvalidCredentialsException::class.java) {
            KdbxFile.load(ByteArrayInputStream(written), newPwd, null)
        }
        val reopened = KdbxFile.load(ByteArrayInputStream(written), newPwd, keyFile)
        assertTrue("新密码 + 密钥文件必须可解锁", reopened.rootGroup.entries.isNotEmpty())
    }

    @Test
    fun `对密码加密钥文件库解绑后产物必须以新密码单独开启`() = runBlocking {
        val oldPwd = "Old#Pass#2026".toCharArray()
        val newPwd = "New#Pass#2026".toCharArray()
        val written = openAndChange(initialBytes(oldPwd, keyFile), oldPwd, keyFile, newPwd, null)

        // 行为级核心断言：解绑必须真实生效——旧凭据形态（新密码 + 旧密钥文件）必须打不开
        assertThrows(KdbxInvalidCredentialsException::class.java) {
            KdbxFile.load(ByteArrayInputStream(written), newPwd, keyFile)
        }
        val reopened = KdbxFile.load(ByteArrayInputStream(written), newPwd, null)
        assertTrue("新密码单独必须可解锁", reopened.rootGroup.entries.isNotEmpty())
    }
}
