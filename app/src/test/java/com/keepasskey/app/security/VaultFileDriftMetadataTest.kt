package com.keepasskey.app.security

import com.keepasskey.database.session.DatabaseSession
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

/**
 * `ISSUE-P3-447` AC② 的**宿主可测部分**回归锁：本地 `File` 通道的真实元数据探针，
 * 以及 SAF（`content://`）通道「元数据不可读不误报 / 元数据可用即同强度判漂移」的裁决口径。
 *
 * **如实声明**：`ContentResolver.query` 取 SAF 文档 `lastModified` / `size` 的实际行为
 * 无 JVM 实现（无 Robolectric），只能由真机 SAF 库走查锁定；本用例覆盖的是
 * 「调用侧如何处理不可读 / 可用两种结果」这一纯 JVM 可判部分。
 */
class VaultFileDriftMetadataTest {

    @get:Rule
    val tempFolder = TemporaryFolder()

    private fun coordinator(): VaultFileDriftCoordinator =
        VaultFileDriftCoordinator(VaultFileBaselineHolder(DatabaseSession()))

    @Test
    fun `本地文件探针读取真实大小与路径标识`() {
        val file = File(tempFolder.root, "vault.kdbx").apply { writeBytes(ByteArray(1234) { 1 }) }

        val baseline = VaultFileMetadataProbe.baselineFor(null, file.absolutePath)

        assertNotNull("本地文件存在时必须取到基线", baseline)
        assertEquals("size 必须为真实字节数", 1234L, baseline!!.sizeBytes)
        assertEquals("路径标识必须为文件绝对路径", file.absolutePath, baseline.pathIdentifier)
    }

    @Test
    fun `本地文件不存在时探针返回null`() {
        assertNull(VaultFileMetadataProbe.baselineFor(null, File(tempFolder.root, "nope.kdbx").absolutePath))
    }

    @Test
    fun `SAF通道分流判据与无上下文降级`() {
        assertTrue(VaultFileMetadataProbe.isSafPath("content://test.docs/vault.kdbx"))
        assertTrue(VaultFileMetadataProbe.isSafPath("content://test.docs/vault.kdbx?x=1"))
        assertFalse(VaultFileMetadataProbe.isSafPath("/data/user/0/x/vault.kdbx"))
        assertFalse(VaultFileMetadataProbe.isSafPath(null))
        assertNull(
            "无上下文（纯 JVM）时 SAF 元数据不可读，必须返回 null 而非占位假基线",
            VaultFileMetadataProbe.baselineFor(null, "content://test.docs/vault.kdbx")
        )
    }

    @Test
    fun `SAF元数据不可读不判漂移而可用时与本地同强度`() {
        val coord = coordinator()
        val safPath = "content://test.docs/vault.kdbx"
        coord.refreshBaselineAfterPersist(
            VaultFileBaseline.fromMetadata(safPath, lastModifiedMillis = 1_000_000L, sizeBytes = 2048)
        )

        assertFalse(
            "SAF 提供方不暴露元数据（current == null）不得误报漂移",
            coord.checkSafDrift(safPath, null)
        )
        assertFalse(
            "同一元数据不得判漂移",
            coord.checkSafDrift(safPath, VaultFileBaseline.fromMetadata(safPath, 1_000_000L, 2048))
        )
        assertTrue(
            "SAF 尺寸变化必须判漂移（与本地 File 通道同强度）",
            coord.checkSafDrift(safPath, VaultFileBaseline.fromMetadata(safPath, 1_000_000L, 4096))
        )
        assertTrue(
            "SAF mtime 超 1 秒粒度必须判漂移",
            coord.checkSafDrift(safPath, VaultFileBaseline.fromMetadata(safPath, 1_005_000L, 2048))
        )
    }

    @Test
    fun `无基线时SAF通道不判漂移`() {
        val coord = coordinator()

        assertFalse(
            "尚未留存基线（如刚打开且元数据不可读）时不得中止保存",
            coord.checkSafDrift(
                "content://test.docs/vault.kdbx",
                VaultFileBaseline.fromMetadata("content://test.docs/vault.kdbx", 5L, 5L)
            )
        )
    }
}
