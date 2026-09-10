package com.keepasskey.database

import com.keepasskey.database.session.AtomicFileWriter
import com.keepasskey.database.session.DatabaseSession
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

/**
 * ISSUE-P2-11 (ZT-16)：「保存前创建 .bak 备份」会话偏好与凭据轮换后旧备份清理回归测试。
 *
 * 旧缺陷：AtomicFileWriter 无条件生成 `<name>.kdbx.bak` 且从无删除逻辑，
 * `createBackupBeforeSave` 持久化后无任何消费方；改主密码后 .bak 仍可被旧口令解开。
 * 现验证：会话偏好关闭时不生成 .bak 并清理历史遗留；开启时按既有行为生成；
 * changeCredentials 成功后旧密文快照必须失效。
 */
class DatabaseSessionBackupPreferenceTest {

    @get:Rule
    val tempFolder = TemporaryFolder()

    private fun bakOf(target: File): File = AtomicFileWriter.backupFileFor(target)

    @Test
    fun testBackupDisabledSkipsBakAndRemovesLegacy() = runBlocking {
        val target = File(tempFolder.root, "vault.kdbx")
        // 预置历史遗留 .bak（模拟旧版本无条件生成、开关关闭后仍驻留磁盘）
        val bak = bakOf(target)
        bak.writeBytes("legacy-ciphertext".toByteArray())

        val session = DatabaseSession()
        session.createBackupBeforeSave = false
        val pwd = "Fake#BackupOff".toCharArray()
        assertTrue(session.create(target, "Vault", pwd, useArgon2 = false).isSuccess)
        assertFalse("关闭偏好时首次写入应清理历史遗留 .bak", bak.exists())

        assertTrue(session.save().isSuccess)
        assertFalse("关闭备份偏好时保存不得生成 .bak", bak.exists())
        assertTrue(target.exists())
        session.close()
    }

    @Test
    fun testBackupEnabledCreatesBakOnSave() = runBlocking {
        val target = File(tempFolder.root, "vault.kdbx")
        val session = DatabaseSession()
        assertEquals("会话默认保持既有行为：创建备份", true, session.createBackupBeforeSave)
        val pwd = "Fake#BackupOn".toCharArray()
        assertTrue(session.create(target, "Vault", pwd, useArgon2 = false).isSuccess)
        // 首次写入无原文件可备份，不应产生 .bak
        assertFalse(bakOf(target).exists())

        assertTrue(session.save().isSuccess)
        val bak = bakOf(target)
        assertTrue("开启备份偏好时保存必须生成 .bak", bak.exists())
        assertTrue("备份必须保留旧密文内容（非空文件）", bak.length() > 0)
        session.close()
    }

    @Test
    fun testChangeCredentialsRemovesStaleBak() = runBlocking {
        val target = File(tempFolder.root, "vault.kdbx")
        val session = DatabaseSession()
        val oldPwd = "Fake#OldPassword".toCharArray()
        val newPwd = "Fake#NewPassword".toCharArray()
        assertTrue(session.create(target, "Vault", oldPwd, useArgon2 = false).isSuccess)
        assertTrue(session.save().isSuccess)
        val bak = bakOf(target)
        assertTrue("前置条件：改密前存在可被旧口令解开的 .bak", bak.exists())

        assertTrue(session.changeCredentials(newPwd.clone()).isSuccess)

        assertFalse("凭据轮换后旧密文快照必须删除", bak.exists())

        // 新凭据可正常打开目标文件
        val newSession = DatabaseSession()
        assertTrue("改密后新凭据必须可解锁", newSession.open(target, newPwd.clone()).isSuccess)
        newSession.close()

        // 目标文件不再持有可被旧口令解开的内容
        val staleSession = DatabaseSession()
        assertTrue("旧口令不得再解开目标文件", staleSession.open(target, oldPwd.clone()).isFailure)
        staleSession.close()
        session.close()
    }
}
