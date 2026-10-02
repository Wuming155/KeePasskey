package com.keepasskey.app.ui.screens.settings.subscreens

import com.keepasskey.app.data.repository.ChangeKeyFileIntent
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * ISSUE-P3-436：导入密钥文件的待确认持有态（`PendingKeyFileImport`）语义。
 *
 * 敏感数据铁律的收口判据——该类是「设置页导入密钥文件」链路中用户字节的唯一驻留点：
 * 1. `toIntent()` 产出 `Use` 意图且**按原引用借用**字节（不复制）——提交路径清零责任
 *    随意图移交至控制器 `finally`，持有方弃持引用后本类不再有副本可泄；
 * 2. `erase()` 就地清零——取消 / 换选 / 离场（`DisposableEffect.onDispose`）路径的字节
 *    绝不遗留；借用未解除时清零同样生效（同一数组）。
 */
class PendingKeyFileImportTest {

    @Test
    fun `toIntent 产出 Use 意图并携带来源 Uri 与显示名`() {
        val bytes = byteArrayOf(1, 2, 3, 4)
        val picked = PendingKeyFileImport(bytes, "content://test/keyfile", "backup.keyx")

        val intent = picked.toIntent()

        assertTrue(intent is ChangeKeyFileIntent.Use)
        intent as ChangeKeyFileIntent.Use
        assertArrayEquals(byteArrayOf(1, 2, 3, 4), intent.bytes)
        assertEquals("content://test/keyfile", intent.sourceUri)
        assertEquals("backup.keyx", intent.displayName)
    }

    @Test
    fun `toIntent 按原引用借用字节——不产生副本`() {
        val bytes = byteArrayOf(9, 8, 7)
        val picked = PendingKeyFileImport(bytes, "content://test/keyfile", "k.keyx")

        val intent = picked.toIntent() as ChangeKeyFileIntent.Use

        // 借用语义（与改密对话框 PickedKeyFile → Use 同口径）：同一数组，控制器 finally 清零即真清零
        assertSame(bytes, intent.bytes)
    }

    @Test
    fun `erase 就地清零——借用中的意图字节同样归零`() {
        val bytes = byteArrayOf(1, 1, 1)
        val picked = PendingKeyFileImport(bytes, "content://test/keyfile", "k.keyx")
        val intent = picked.toIntent() as ChangeKeyFileIntent.Use

        picked.erase()

        assertArrayEquals(ByteArray(3), bytes)
        assertArrayEquals(ByteArray(3), intent.bytes)
    }
}
