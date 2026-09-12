package com.keepasskey.sync.engine

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.security.MessageDigest
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

/**
 * 同步防回滚守卫单元测试（ISSUE-P2-18 + F-23 整改回归）。
 *
 * 覆盖：首次内容可接受、同一内容视为未变、曾接受过的历史版本被判重放、
 * 全新内容（其他客户端写入）不误报、状态被篡改 / 缺失时按无历史处理不误报；
 * 以及 **F-23 核心不变式**——状态落在跨锁定保留的 `filesDir` 目录，
 * 缓存清理（`SyncCache.clear` / `clearAll`，即锁库销毁路径）不得摧毁重放防护。
 */
class SyncRollbackGuardTest {

    @get:Rule
    val tempFolder = TemporaryFolder()

    private val remotePath = "vault.kdbx"

    /** 生产落点语义：`filesDir/<STATE_DIR_NAME>`（跨会话锁定保留） */
    private fun stateDir(): File =
        File(tempFolder.root, SyncRollbackGuard.STATE_DIR_NAME).apply { mkdirs() }

    private fun stateFileIn(dir: File): File =
        File(dir, SyncCache.sha256Hex(remotePath.toByteArray(Charsets.UTF_8)) + SyncRollbackGuard.SUFFIX_STATE)

    @Test
    fun `首次内容可接受且记录后同一内容视为未变`() {
        val guard = SyncRollbackGuard(stateDir(), FixedMac("key-A"))
        val v1 = "content-v1".toByteArray()

        assertEquals(RollbackVerdict.Accept, guard.inspect(remotePath, v1))
        guard.recordAccepted(remotePath, v1)
        assertEquals(RollbackVerdict.Unchanged, guard.inspect(remotePath, v1))
    }

    @Test
    fun `曾接受过的历史版本被判为重放`() {
        val guard = SyncRollbackGuard(stateDir(), FixedMac("key-A"))
        val v1 = "content-v1".toByteArray()
        val v2 = "content-v2".toByteArray()

        guard.recordAccepted(remotePath, v1)
        guard.recordAccepted(remotePath, v2)
        // v1 曾是设备侧接受过的版本，被入侵端点重放旧库即命中
        assertEquals(RollbackVerdict.ReplayDetected, guard.inspect(remotePath, v1))
        assertEquals(RollbackVerdict.Unchanged, guard.inspect(remotePath, v2))
    }

    @Test
    fun `全新内容不误报以兼容其他客户端写入`() {
        val guard = SyncRollbackGuard(stateDir(), FixedMac("key-A"))
        guard.recordAccepted(remotePath, "content-v1".toByteArray())

        // 其它官方客户端写入的是全新内容（新摘要）→ 必须接受
        assertEquals(
            RollbackVerdict.Accept,
            guard.inspect(remotePath, "content-from-keepassdx".toByteArray())
        )
    }

    // ===== F-23：锁库 / 缓存清理后重放防护必须仍然有效 =====

    @Test
    fun `F23 接受A后接受B_经历缓存清理_重放A仍被判为重放`() {
        // 复刻生产布局：密文快照在 cacheDir（锁库即清），防回滚状态在 filesDir（跨锁定保留）
        val cacheDir = File(tempFolder.root, "cacheDir/sync").apply { mkdirs() }
        val filesDir = File(tempFolder.root, "filesDir").apply { mkdirs() }
        val cache = SyncCache(cacheDir)
        val guard = SyncRollbackGuard(
            File(filesDir, SyncRollbackGuard.STATE_DIR_NAME),
            FixedMac("key-A")
        )

        val v1 = "content-v1".toByteArray()
        val v2 = "content-v2".toByteArray()

        // 接受版本 A，再接受版本 B
        assertEquals(RollbackVerdict.Accept, guard.inspect(remotePath, v1))
        guard.recordAccepted(remotePath, v1)
        assertEquals(RollbackVerdict.Accept, guard.inspect(remotePath, v2))
        guard.recordAccepted(remotePath, v2)

        // 模拟会话锁定：SyncCacheEvictor.onSessionLocked() → SyncCache.clearAll()（并叠加单路径 clear）
        cache.writeCache(remotePath, v2)
        cache.clear(remotePath)
        assertTrue(cache.clearAll())

        // 云侧重放设备侧曾接受过的 A：状态跨锁定保留 → 必须仍判为重放（整改前此处返回 Accept）
        assertEquals(RollbackVerdict.ReplayDetected, guard.inspect(remotePath, v1))
        assertEquals(RollbackVerdict.Unchanged, guard.inspect(remotePath, v2))
    }

    @Test
    fun `F23 SyncCache 清理不得删除防回滚状态文件`() {
        // 即便状态目录被误配到缓存目录（或存在升级前的历史状态文件），
        // 缓存清理也不得摧毁重放防护——这是整改前缺陷的成因，必须由缓存侧兜底
        val cacheDir = tempFolder.newFolder("colocated-cache")
        val cache = SyncCache(cacheDir)
        cache.writeCache(remotePath, "cached-v1".toByteArray())

        val guard = SyncRollbackGuard(cacheDir, FixedMac("key-A"))
        val v1 = "content-v1".toByteArray()
        guard.recordAccepted(remotePath, v1)

        val stateFile = stateFileIn(cacheDir)
        assertTrue("状态文件必须已落盘", stateFile.isFile)

        cache.clear(remotePath)

        assertTrue("SyncCache.clear 不得删除防回滚状态", stateFile.isFile)
        assertNull("缓存密文仍须被销毁", cache.readCache(remotePath))
        assertEquals(RollbackVerdict.Unchanged, guard.inspect(remotePath, v1))

        cache.writeCache(remotePath, "cached-v2".toByteArray())
        assertTrue(cache.clearAll())

        assertTrue("SyncCache.clearAll 不得删除防回滚状态", stateFile.isFile)
        assertNull("缓存密文仍须被销毁", cache.readCache(remotePath))
        assertEquals(RollbackVerdict.Unchanged, guard.inspect(remotePath, v1))
    }

    @Test
    fun `F23 状态文件缺失时按无历史处理`() {
        val guard = SyncRollbackGuard(stateDir(), FixedMac("key-A"))
        val v1 = "content-v1".toByteArray()
        guard.recordAccepted(remotePath, v1)
        assertEquals(RollbackVerdict.Unchanged, guard.inspect(remotePath, v1))

        // 状态目录被清空（卸载残留、用户清除数据、异常中断）→ fail-open 接受
        stateFileIn(stateDir()).delete()

        assertEquals(RollbackVerdict.Accept, guard.inspect(remotePath, v1))
    }

    @Test
    fun `F23 升级迁移后旧目录状态不被读取_首轮按无历史处理`() {
        // 升级前状态落在 cacheDir/sync（<hash>.rollback）；整改后守卫只读 filesDir/<STATE_DIR_NAME>，
        // 本批不做搬运：旧状态对守卫不可见 → 首轮同步按「无历史」接受（已留痕的迁移取舍）
        val legacyDir = tempFolder.newFolder("legacy-cache-sync")
        SyncRollbackGuard(legacyDir, FixedMac("key-A")).recordAccepted(remotePath, "content-v1".toByteArray())
        assertTrue("旧布局状态应存在于 legacy 目录", stateFileIn(legacyDir).isFile)

        val migrated = SyncRollbackGuard(stateDir(), FixedMac("key-A"))

        // 旧目录中的历史版本对守卫不可见：首轮按「无历史」放行（已留痕的迁移取舍）
        assertEquals(RollbackVerdict.Accept, migrated.inspect(remotePath, "content-v1".toByteArray()))

        // 一旦经新布局重新接受，重放拦截能力立即恢复（v1 进入 recent 链，重放即被拒）
        migrated.recordAccepted(remotePath, "content-v1".toByteArray())
        migrated.recordAccepted(remotePath, "content-v2".toByteArray())
        assertEquals(RollbackVerdict.ReplayDetected, migrated.inspect(remotePath, "content-v1".toByteArray()))
        assertTrue("不得触碰旧布局状态文件", stateFileIn(legacyDir).isFile)
    }

    @Test
    fun `状态MAC失效时按无历史处理不产生误报回退`() {
        val v1 = "content-v1".toByteArray()
        val v2 = "content-v2".toByteArray()
        val original = SyncRollbackGuard(stateDir(), FixedMac("key-A"))
        original.recordAccepted(remotePath, v1)
        original.recordAccepted(remotePath, v2)
        assertEquals(RollbackVerdict.ReplayDetected, original.inspect(remotePath, v1))

        // 模拟状态文件被篡改 / 密钥不匹配：MAC 校验失败 → 状态不可信 → 无历史，不误报回退
        // （fail-open 取舍：宁可漏判一次重放，也不制造用户无法自愈的误报回退）
        val tamperReader = SyncRollbackGuard(stateDir(), FixedMac("key-B"))
        assertEquals(RollbackVerdict.Accept, tamperReader.inspect(remotePath, v1))
    }

    /** 固定密钥的等价 HMAC 实现（JVM 可测，语义与 AndroidKeyStore 实现一致） */
    private class FixedMac(keyMaterial: String) : SyncIntegrityMac {
        private val key = SecretKeySpec(keyMaterial.toByteArray(), "HmacSHA256")

        override fun compute(data: ByteArray): ByteArray =
            Mac.getInstance("HmacSHA256").apply { init(key) }.doFinal(data)

        override fun verify(data: ByteArray, mac: ByteArray?): Boolean {
            if (mac == null) return false
            return MessageDigest.isEqual(compute(data), mac)
        }
    }
}
