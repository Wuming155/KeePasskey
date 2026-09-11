package com.keepasskey.sync.engine

import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.security.MessageDigest
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

/**
 * 同步防回滚守卫单元测试（ISSUE-P2-18）。
 *
 * 覆盖：首次内容可接受、同一内容视为未变、曾接受过的历史版本被判重放、
 * 全新内容（其他客户端写入）不误报、状态被篡改（MAC 失效）时按无历史处理不误报。
 */
class SyncRollbackGuardTest {

    @get:Rule
    val tempFolder = TemporaryFolder()

    private val remotePath = "vault.kdbx"

    private fun stateDir(): File = File(tempFolder.root, "rollback").apply { mkdirs() }

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

    @Test
    fun `状态MAC失效时按无历史处理不产生误报回退`() {
        val v1 = "content-v1".toByteArray()
        val v2 = "content-v2".toByteArray()
        val original = SyncRollbackGuard(stateDir(), FixedMac("key-A"))
        original.recordAccepted(remotePath, v1)
        original.recordAccepted(remotePath, v2)
        assertEquals(RollbackVerdict.ReplayDetected, original.inspect(remotePath, v1))

        // 模拟状态文件被篡改 / 密钥不匹配：MAC 校验失败 → 状态不可信 → 无历史，不误报回退
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
