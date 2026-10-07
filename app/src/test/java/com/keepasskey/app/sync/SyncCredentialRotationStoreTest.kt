package com.keepasskey.app.sync

import com.keepasskey.app.testutil.InMemorySharedPreferences
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * `ISSUE-P3-528`：主凭据轮换标记表的语义锁定（JVM 单测，经 `InMemorySharedPreferences` 替身，
 * 不引入 Robolectric）。
 *
 * 覆盖三条最要紧的性质：
 * 1. **读后即清且不重复消费**——若不销，「每轮同步都被当成有本地修改」会造成无意义重传；
 * 2. **按远端路径隔离**——两个库共用一个 WebDAV 账号时，A 库换密不得让 B 库重传；
 * 3. **键不含远端路径明文**——路径含邮箱等 PII（WebDAV 常见 `abc@163.com/x.kdbx`），
 *    不得以明文进 prefs 键（本仓日志 / 存储面同一纪律）。
 */
class SyncCredentialRotationStoreTest {

    private val prefs = InMemorySharedPreferences()
    private val store = SyncCredentialRotationStore(prefs.context())

    @Test
    fun `置位后可读，读后即清且不重复消费`() {
        store.markRecrypted(PATH_A)

        assertTrue("置位后必须可读", store.isRecrypted(PATH_A))
        assertTrue("首次消费必须返回已置位", store.consumeRecrypted(PATH_A))
        assertFalse("消费后标记必须已销", store.isRecrypted(PATH_A))
        assertFalse("不得重复消费（否则每轮同步都被当作有本地修改）", store.consumeRecrypted(PATH_A))
    }

    @Test
    fun `未置位时读取与消费都为空操作`() {
        assertFalse(store.isRecrypted(PATH_A))
        assertFalse(store.consumeRecrypted(PATH_A))
        assertTrue("空操作不得写入任何键", prefs.storage.isEmpty())
    }

    @Test
    fun `标记按远端路径隔离`() {
        store.markRecrypted(PATH_A)

        assertTrue(store.isRecrypted(PATH_A))
        assertFalse("另一路径不得继承待替换意图", store.isRecrypted(PATH_B))
        assertFalse("另一路径消费必须为空操作", store.consumeRecrypted(PATH_B))
        assertTrue("A 的标记不受影响", store.isRecrypted(PATH_A))
    }

    @Test
    fun `显式 clear 只清目标路径`() {
        store.markRecrypted(PATH_A)
        store.markRecrypted(PATH_B)

        store.clear(PATH_A)

        assertFalse(store.isRecrypted(PATH_A))
        assertTrue(store.isRecrypted(PATH_B))
    }

    @Test
    fun `同步关系终止清空 prefs 后标记随之消失`() {
        store.markRecrypted(PATH_A)

        // 语义：本表与 SyncCredentialsStore 共用同一 prefs 文件，后者 clear() 即整体销毁
        prefs.storage.clear()

        assertFalse("换服务器 / 退出同步后不得残留待替换意图", store.isRecrypted(PATH_A))
        assertFalse(store.consumeRecrypted(PATH_A))
    }

    @Test
    fun `键不含远端路径明文`() {
        store.markRecrypted(PATH_A)

        val key = prefs.storage.keys.single()
        assertTrue("键须带固定语义前缀", key.startsWith("recrypted_vault_"))
        assertFalse("键不得内嵌远端路径（含邮箱等 PII）", key.contains(PATH_A) || key.contains("163.com"))
    }

    private companion object {
        const val PATH_A = "/dav/abc@163.com/vault.kdbx"
        const val PATH_B = "/dav/abc@163.com/other.kdbx"
    }
}
