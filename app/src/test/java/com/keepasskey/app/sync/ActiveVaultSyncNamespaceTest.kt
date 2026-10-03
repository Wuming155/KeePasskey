package com.keepasskey.app.sync

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.io.File

/**
 * 同步配置命名空间标识的解析口径（`ISSUE-P2-465` AC①）。
 *
 * 本类只测**判据本体**（纯 lambda 注入，无 Android 依赖）。判据错了的后果是「同一库在
 * 会话内外落到两个命名空间」——表现为「解锁前配好的同步，解锁后读不到」或反向的
 * 「另一个库的配置冒出来」，故逐条锁定回退顺序与归一化规则。
 */
class ActiveVaultSyncNamespaceTest {

    /** 沙盒根：用 tmpdir 构造绝对路径，避免依赖宿主 OS 的路径形态 */
    private val sandbox: File = File(System.getProperty("java.io.tmpdir") ?: ".", "kp-sandbox-files")

    private fun namespace(
        pathIdentifier: String? = null,
        filePath: String? = null,
        persisted: String? = null,
        filesDir: File? = sandbox
    ) = ActiveVaultSyncNamespace(
        filesDir = { filesDir },
        sessionPathIdentifier = { pathIdentifier },
        sessionFilePath = { filePath },
        persistedActiveId = { persisted }
    )

    @Test
    fun `会话路径标识优先_SAF 的 content URI 原样保留`() {
        val id = namespace(
            pathIdentifier = "content://com.example.docs/vault/a.kdbx",
            filePath = File(sandbox, "a.kdbx").absolutePath,
            persisted = "b.kdbx"
        ).currentId()

        assertEquals("content://com.example.docs/vault/a.kdbx", id)
    }

    @Test
    fun `会话文件绝对路径优先于库列表活动项`() {
        val filePath = File(sandbox, "a.kdbx").absolutePath
        val id = namespace(filePath = filePath, persisted = "b.kdbx").currentId()

        assertEquals(filePath, id)
    }

    @Test
    fun `沙盒裸文件名归一为沙盒内绝对路径`() {
        // 库列表对沙盒内扫描条目登记的 ID 是裸文件名（VaultDatabaseCatalog），
        // 而会话内路径标识是绝对路径——二者必须归一到同一个键，否则解锁前后互不可见
        val id = namespace(persisted = "a.kdbx").currentId()

        assertEquals(File(sandbox, "a.kdbx").absolutePath, id)
    }

    @Test
    fun `外部登记的绝对路径原样保留`() {
        val external = File(System.getProperty("java.io.tmpdir") ?: ".", "external").let {
            File(it, "other.kdbx").absolutePath
        }
        assertEquals(external, namespace(persisted = external).currentId())
    }

    @Test
    fun `三者皆空即无活动库`() {
        assertNull(namespace().currentId())
        assertNull(namespace(persisted = "  ").currentId())
    }

    @Test
    fun `filesDir 不可用时裸文件名原样返回而不抛异常`() {
        assertEquals("a.kdbx", namespace(persisted = "a.kdbx", filesDir = null).currentId())
    }

    @Test
    fun `idFor 与 currentId 同归一化口径`() {
        val namespace = namespace()
        // 显式给定库 ID（云端打开导入的落盘路径 / 删库清理）走同一条归一化规则
        assertEquals(File(sandbox, "a.kdbx").absolutePath, namespace.idFor("a.kdbx"))
        assertEquals("content://x/y.kdbx", namespace.idFor("content://x/y.kdbx"))
        assertNull(namespace.idFor(null))
    }
}
