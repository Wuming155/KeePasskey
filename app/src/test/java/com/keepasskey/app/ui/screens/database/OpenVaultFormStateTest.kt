package com.keepasskey.app.ui.screens.database

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * 云端打开对话框表单状态单元测试（ISSUE-P3-401）：
 * 展示名默认取远端路径末段文件名——路径变化（手输或浏览点选）时自动推导；
 * 用户手动编辑过展示名后路径再变不覆盖（清空视为放弃手动值，恢复推导）。
 */
class OpenVaultFormStateTest {

    @Test
    fun `初始即按默认远端路径推导展示名`() {
        val webdav = WebdavVaultFormState()
        assertEquals("keepasskey.kdbx", webdav.name)
        val s3 = S3VaultFormState()
        assertEquals("keepasskey.kdbx", s3.name)
    }

    @Test
    fun `路径变化时未编辑过的展示名跟随推导`() {
        val webdav = WebdavVaultFormState()
        webdav.applyRemotePath("mailbox/vaults/my_passwords.kdbx")
        // 路径字段存完整输入值；只有展示名取末段文件名
        assertEquals("mailbox/vaults/my_passwords.kdbx", webdav.remotePath)
        assertEquals("my_passwords.kdbx", webdav.name)

        val s3 = S3VaultFormState()
        s3.applyObjectKey("backups/deep/real_vault.kdbx")
        assertEquals("real_vault.kdbx", s3.name)
    }

    @Test
    fun `用户手动编辑后路径变化不再覆盖`() {
        val webdav = WebdavVaultFormState()
        webdav.editName("我的云端库")
        webdav.applyRemotePath("mailbox/other.kdbx")
        assertEquals("我的云端库", webdav.name)

        // 清空视为放弃手动值：此后路径变化恢复自动推导
        webdav.editName("")
        webdav.applyRemotePath("mailbox/again.kdbx")
        assertEquals("again.kdbx", webdav.name)
    }

    @Test
    fun `浏览点选回填与手输走同一推导`() {
        val webdav = WebdavVaultFormState()
        webdav.editName("custom")
        webdav.applyRemotePath("a/b.kdbx")
        assertEquals("custom", webdav.name)
        webdav.editName("") // 模拟用户清空后再点选
        webdav.applyRemotePath("a/c.kdbx")
        assertEquals("c.kdbx", webdav.name)
    }
}
