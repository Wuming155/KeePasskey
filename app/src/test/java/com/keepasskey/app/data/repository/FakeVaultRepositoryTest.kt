package com.keepasskey.app.data.repository

import com.keepasskey.app.ui.model.EntryCategory
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * FakeVaultRepository 内存实现的单元测试：
 * 覆盖多密码库管理、条目 CRUD、回收站语义、批量操作与历史修订保留。
 */
class FakeVaultRepositoryTest {

    private fun newRepository() = FakeVaultRepository()

    @Test
    fun `初始数据包含多个数据库且仅一个激活`() = runTest {
        val repository = newRepository()

        val databases = repository.getDatabases().first()

        assertTrue(databases.size >= 3)
        assertEquals(1, databases.count { it.isActive })
        assertNotNull(databases.find { it.isActive })
    }

    @Test
    fun `selectDatabase 切换激活数据库`() = runTest {
        val repository = newRepository()

        repository.selectDatabase("db_work")
        val databases = repository.getDatabases().first()

        val active = databases.filter { it.isActive }
        assertEquals(1, active.size)
        assertEquals("db_work", active.first().id)
    }

    @Test
    fun `createDatabase 新建库并设为激活`() = runTest {
        val repository = newRepository()

        repository.createDatabase(name = "新密码库", masterPassword = "test-only-password", keyFile = false, preset = "AES-256 + Argon2id")

        val databases = repository.getDatabases().first()
        val created = databases.first { it.name == "新密码库.kdbx" }
        assertTrue(created.isActive)
        assertEquals("AES-256 + Argon2id", created.encryptionPreset)
        // 其余数据库全部取消激活
        assertEquals(0, databases.count { it.isActive && it.id != created.id })
    }

    @Test
    fun `removeDatabase 从列表中移除`() = runTest {
        val repository = newRepository()

        repository.removeDatabase("db_offline")

        val databases = repository.getDatabases().first()
        assertNull(databases.find { it.id == "db_offline" })
    }

    @Test
    fun `saveEntry 新增与更新条目`() = runTest {
        val repository = newRepository()
        val newEntry = repository.getEntry("1").first()!!.copy(id = "new_1", title = "新条目", revisions = emptyList())

        repository.saveEntry(newEntry)
        var stored = repository.getEntries().first()
        assertNotNull(stored.find { it.id == "new_1" })

        // 更新时自动保留一份历史修订
        val updated = stored.find { it.id == "new_1" }!!.copy(title = "改名条目")
        repository.saveEntry(updated)
        stored = repository.getEntries().first()
        val result = stored.find { it.id == "new_1" }!!
        assertEquals("改名条目", result.title)
        assertEquals(1, result.revisions.size)
        assertEquals(newEntry.username, result.revisions.first().username)
    }

    @Test
    fun `deleteEntry 首次进入回收站 再次删除彻底移除`() = runTest {
        val repository = newRepository()

        repository.deleteEntry("1")
        var entry = repository.getEntry("1").first()
        assertNotNull(entry)
        assertEquals("group_recycle_bin", entry!!.groupId)

        repository.deleteEntry("1")
        entry = repository.getEntry("1").first()
        assertNull(entry)
    }

    @Test
    fun `restoreEntry 从回收站还原`() = runTest {
        val repository = newRepository()

        repository.deleteEntry("1")
        repository.restoreEntry("1")

        val entry = repository.getEntry("1").first()!!
        assertNull(entry.groupId)
    }

    @Test
    fun `emptyRecycleBin 只清空回收站条目`() = runTest {
        val repository = newRepository()
        repository.deleteEntry("1")
        repository.deleteEntry("2")

        repository.emptyRecycleBin()

        val entries = repository.getEntries().first()
        assertTrue(entries.find { it.id == "1" } == null)
        assertTrue(entries.find { it.id == "2" } == null)
        assertNotNull(entries.find { it.id == "3" })
    }

    @Test
    fun `batchMoveEntries 移动到目标分组`() = runTest {
        val repository = newRepository()

        repository.batchMoveEntries(setOf("1", "3"), "group_work")

        val entries = repository.getEntries().first()
        assertEquals("group_work", entries.find { it.id == "1" }!!.groupId)
        assertEquals("group_work", entries.find { it.id == "3" }!!.groupId)
    }

    @Test
    fun `batchDeleteEntries 批量移入回收站`() = runTest {
        val repository = newRepository()

        repository.batchDeleteEntries(setOf("1", "3"))

        val entries = repository.getEntries().first()
        assertEquals("group_recycle_bin", entries.find { it.id == "1" }!!.groupId)
        assertEquals("group_recycle_bin", entries.find { it.id == "3" }!!.groupId)
    }

    @Test
    fun `deleteGroup 删除文件夹并将条目移入回收站`() = runTest {
        val repository = newRepository()

        repository.deleteGroup("group_dev")

        assertFalse(repository.getGroups().first().any { it.id == "group_dev" })
        val devEntries = repository.getEntries().first().filter { it.id == "2" || it.id == "4" || it.id == "6" }
        assertTrue(devEntries.all { it.groupId == "group_recycle_bin" })
    }

    @Test
    fun `saveGroup 新增文件夹`() = runTest {
        val repository = newRepository()
        val group = com.keepasskey.app.ui.model.VaultGroup(
            id = "group_new",
            name = "测试分组",
            parentId = null,
            iconName = "folder"
        )

        repository.saveGroup(group)

        assertTrue(repository.getGroups().first().any { it.id == "group_new" })
    }

    @Test
    fun `回收站分组标记正确`() = runTest {
        val repository = newRepository()

        val recycleBin = repository.getGroups().first().find { it.id == "group_recycle_bin" }

        assertNotNull(recycleBin)
        assertTrue(recycleBin!!.isRecycleBin)
    }

    @Test
    fun `mock 数据覆盖全部条目类别`() = runTest {
        val repository = newRepository()

        val categories = repository.getEntries().first().map { it.category }.toSet()

        assertTrue(categories.contains(EntryCategory.LOGIN))
        assertTrue(categories.contains(EntryCategory.PASSKEY))
        assertTrue(categories.contains(EntryCategory.CARD))
        assertTrue(categories.contains(EntryCategory.NOTE))
    }
}
