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
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.IOException
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
        return try {
            ByteArrayOutputStream()
                .also { KdbxFile.save(it, db, password, keyFileData) }
                .toByteArray()
        } finally {
            db.clearSensitiveData()
        }
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

    @Test
    fun `解绑后连续编辑保存并锁定重开不得恢复旧密钥文件因子`() = runBlocking {
        val oldPwd = "Old#Pass#2026".toCharArray()
        val newPwd = "New#Pass#2026".toCharArray()
        var disk = initialBytes(oldPwd, keyFile)
        val session = openSession(disk, oldPwd, keyFile) { disk = it.copyOf() }
        try {
            assertTrue(session.changeCredentials(newPwd, null).isSuccess)
            assertNull("解绑后导出因子应为空", session.exportKeyFileBytes())
            repeat(2) { index ->
                val entry = session.databaseFlow.value!!.rootGroup.entries.single()
                val title = "解绑后编辑${index + 1}"
                session.saveEntry(entry.withField(KdbxConstants.Fields.TITLE, ProtectedString(title)))
                assertTrue("解绑后的普通保存必须成功", session.save().isSuccess)
                loadAndUse(disk, newPwd, null) { reopened ->
                    assertEquals(title, reopened.rootGroup.entries.single().title)
                    assertEquals("secret#2026", reopened.rootGroup.entries.single().password!!.readString())
                }
                assertThrows(KdbxInvalidCredentialsException::class.java) {
                    loadAndUse(disk, newPwd, keyFile) {}
                }
            }
            session.lock()
            assertTrue(session.openStream(
                pathIdentifier = "rebind.kdbx",
                inputStreamProvider = { ByteArrayInputStream(disk) },
                saveWriter = { disk = it.copyOf() },
                passwordChars = newPwd,
                keyFileData = null
            ).isSuccess)
            assertEquals("解绑后编辑2", session.databaseFlow.value!!.rootGroup.entries.single().title)
        } finally {
            session.close()
            oldPwd.fill('0')
            newPwd.fill('0')
            disk.fill(0)
        }
    }

    @Test
    fun `更换文件后再次保存只接受目标组合且导出新文件`(): Unit = runBlocking {
        val pwd = "Pass#2026".toCharArray()
        val nextKey = ByteArray(64) { (it + 9).toByte() }
        var disk = initialBytes(pwd, keyFile)
        val session = openSession(disk, pwd, keyFile) { disk = it.copyOf() }
        try {
            assertTrue(session.changeCredentials(pwd, nextKey).isSuccess)
            assertTrue(session.save().isSuccess)
            val exported = session.exportKeyFileBytes()!!
            try {
                assertArrayEquals(nextKey, exported)
            } finally {
                exported.fill(0)
            }
            loadAndUse(disk, pwd, nextKey) { assertTrue(it.rootGroup.entries.isNotEmpty()) }
            assertThrows(KdbxInvalidCredentialsException::class.java) {
                loadAndUse(disk, pwd, keyFile) {}
            }
            assertThrows(KdbxInvalidCredentialsException::class.java) {
                loadAndUse(disk, pwd, null) {}
            }
        } finally {
            session.close()
            pwd.fill('0')
            nextKey.fill(0)
            disk.fill(0)
        }
    }

    @Test
    fun `解绑写盘失败必须保留旧磁盘与旧缓存并能继续保存`(): Unit = runBlocking {
        val oldPwd = "Old#Pass#2026".toCharArray()
        val newPwd = "New#Pass#2026".toCharArray()
        var disk = initialBytes(oldPwd, keyFile)
        val before = disk.copyOf()
        var failWrite = true
        val session = DatabaseSession()
        assertTrue(session.openStream(
            pathIdentifier = "rollback.kdbx",
            inputStreamProvider = { ByteArrayInputStream(disk) },
            saveWriter = { disk = it.copyOf() },
            trackedSaveWriter = { bytes, receipt ->
                if (failWrite) {
                    receipt.notCommitted() // 注入点证明目标从未改变，不能用任意 writer 异常假定回滚安全。
                    throw IOException("测试写盘失败")
                }
                disk = bytes.copyOf()
                receipt.committed()
            },
            passwordChars = oldPwd, keyFileData = keyFile
        ).isSuccess)
        try {
            assertTrue(session.changeCredentials(newPwd, null).isFailure)
            assertArrayEquals("失败不得改变磁盘", before, disk)
            val exported = session.exportKeyFileBytes()!!
            try {
                assertArrayEquals("失败须恢复旧文件缓存", keyFile, exported)
            } finally {
                exported.fill(0)
            }
            failWrite = false
            assertTrue("回滚后必须能够继续保存", session.save().isSuccess)
            loadAndUse(disk, oldPwd, keyFile) { assertTrue(it.rootGroup.entries.isNotEmpty()) }
            assertThrows(KdbxInvalidCredentialsException::class.java) {
                loadAndUse(disk, newPwd, null) {}
            }
        } finally {
            session.close()
            oldPwd.fill('0')
            newPwd.fill('0')
            disk.fill(0)
            before.fill(0)
        }
    }

    @Test
    fun `仅文件会话不能通过双参接口移除全部因子`() = runBlocking {
        val emptyPwd = charArrayOf()
        var disk = initialBytes(emptyPwd, keyFile)
        val before = disk.copyOf()
        val session = openSession(disk, emptyPwd, keyFile) { disk = it.copyOf() }
        try {
            listOf<CharArray?>(null, charArrayOf()).forEach { password ->
                assertTrue("无因子组合必须拒绝", session.changeCredentials(password, null).isFailure)
                assertArrayEquals(before, disk)
            }
            assertTrue("仅文件专用入口同样须拒绝解绑", session.changeKeyFileOnly(null).isFailure)
            assertTrue(session.save().isSuccess)
            loadAndUse(disk, emptyPwd, keyFile) { assertTrue(it.rootGroup.entries.isNotEmpty()) }
        } finally {
            session.close()
            emptyPwd.fill('0')
            disk.fill(0)
            before.fill(0)
        }
    }

    private fun loadAndUse(
        bytes: ByteArray,
        pwd: CharArray,
        key: ByteArray?,
        block: (KdbxDatabase) -> Unit
    ) {
        val db = KdbxFile.load(ByteArrayInputStream(bytes), pwd, key)
        try {
            block(db)
        } finally {
            db.clearSensitiveData()
        }
    }

    private suspend fun openSession(
        initial: ByteArray,
        pwd: CharArray,
        key: ByteArray?,
        writer: suspend (ByteArray) -> Unit
    ): DatabaseSession = DatabaseSession().also { session ->
        assertTrue("前置：会话打开失败", session.openStream(
            pathIdentifier = "rebind.kdbx",
            inputStreamProvider = { ByteArrayInputStream(initial) },
            saveWriter = writer,
            passwordChars = pwd,
            keyFileData = key
        ).isSuccess)
    }
}
