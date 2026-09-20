package com.keepasskey.app.data.repository

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * `ISSUE-P3-230` 持久化读授权判据的单元测试（判据本体为纯函数，JVM 可跑）。
 *
 * 这条判据的失效形态全是**静默**的：判错只会让提示不出现（或凭空出现），
 * 编译、既有用例与界面都不会报错。故此处把两个方向都钉住：
 *
 * 1. **不得漏报**：`content://` 且不在已授权集合中 ⇒ true（否则缺陷原样复发）；
 * 2. **不得误报**：本地路径 / 大小写或空白变形以外的形态、以及**查询失败**（调用方传 `null`）
 *    都不得判为「缺授权」——把「查不到」说成「没授权」等于向用户谎报风险。
 */
class VaultUriPermissionTest {

    @Test
    fun `content 路径未在已授权集合中判为缺授权`() {
        assertTrue(
            lacksPersistedReadPermission("content://com.example.docs/document/vault.kdbx", emptySet())
        )
        assertTrue(
            lacksPersistedReadPermission(
                "content://com.example.docs/document/vault.kdbx",
                setOf("content://com.example.docs/document/other.kdbx")
            )
        )
    }

    @Test
    fun `content 路径已在已授权集合中判为不缺失`() {
        val uri = "content://com.example.docs/document/vault.kdbx"
        assertFalse(lacksPersistedReadPermission(uri, setOf(uri)))
        // 与之并存的其它授权不影响本路径
        assertFalse(
            lacksPersistedReadPermission(
                uri,
                setOf(uri, "content://com.example.docs/document/another.kdbx")
            )
        )
    }

    @Test
    fun `非 content 路径恒不判为缺授权`() {
        // 应用私有目录与本地文件不涉及 SAF 授权，不得触发任何提示
        assertFalse(lacksPersistedReadPermission("/data/user/0/com.keepasskey/files/vault.kdbx", emptySet()))
        assertFalse(lacksPersistedReadPermission("/storage/emulated/0/Documents/vault.kdbx", emptySet()))
        assertFalse(lacksPersistedReadPermission("", emptySet()))
    }

    @Test
    fun `空串与近似路径不得互相命中`() {
        // 精确相等语义：前缀相同但路径不同不得被当作已授权
        assertTrue(
            lacksPersistedReadPermission(
                "content://com.example.docs/document/vault.kdbx",
                setOf("content://com.example.docs/document/vault")
            )
        )
    }
}
