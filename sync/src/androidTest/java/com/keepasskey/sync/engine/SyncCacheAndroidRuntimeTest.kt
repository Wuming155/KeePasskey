package com.keepasskey.sync.engine

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.ByteArrayInputStream
import java.io.File
import java.nio.file.Files
import java.nio.file.attribute.PosixFilePermission

/**
 * SyncCache 落盘基线的**设备侧（instrumented）**回归（ISSUE-P2-27 / ISSUE-P2-24 AC③）。
 *
 * ## 为什么必须有这一层
 *
 * `SyncCache.restrictToOwnerOnly` 优先走 POSIX 精确置位（0600 / 0700），失败才降级为
 * `java.io` 尽力而为——**宿主 JVM（Windows）永远走降级分支**，无法证明设备上真的收敛到 0600。
 * 而缓存内是完整 KDBX 密文快照与（ISSUE-P2-24 起）大附件明文，
 * 「文件仅属主可读写」正是设备侧必须验证的安全不变量。
 */
@RunWith(AndroidJUnit4::class)
class SyncCacheAndroidRuntimeTest {

    private val context = InstrumentationRegistry.getInstrumentation().targetContext

    private fun newCacheDir(): File =
        File(context.cacheDir, "sync-cache-runtime-test").apply { deleteRecursively() }

    private val ownerOnlyFile = setOf(PosixFilePermission.OWNER_READ, PosixFilePermission.OWNER_WRITE)
    private val ownerOnlyDir = ownerOnlyFile + PosixFilePermission.OWNER_EXECUTE

    @Test
    fun `缓存目录与落盘文件权限收敛为仅属主可访问`() {
        val dir = newCacheDir()
        val cache = SyncCache(dir)
        val data = ByteArray(2048) { (it % 251).toByte() }

        cache.writeCache("vault/remote.kdbx", data)

        assertEquals("缓存目录必须为 0700", ownerOnlyDir, Files.getPosixFilePermissions(dir.toPath()))
        val cacheFile = dir.listFiles { file -> file.name.endsWith(".cache") }!!.single()
        assertEquals("缓存文件必须为 0600", ownerOnlyFile, Files.getPosixFilePermissions(cacheFile.toPath()))
        assertTrue(data.contentEquals(cache.readCache("vault/remote.kdbx")!!))
        assertNull(cache.readCache("vault/missing.kdbx"))
    }

    @Test
    fun `流式落盘同样收敛权限且读回逐字节一致`() {
        val dir = newCacheDir()
        val cache = SyncCache(dir)
        val data = ByteArray(300_000) { (it % 251).toByte() }

        cache.writeCacheStreaming("attachments/k1", ByteArrayInputStream(data), data.size.toLong())

        val cacheFile = dir.listFiles { file -> file.name.endsWith(".cache") }!!.single()
        assertEquals("流式落盘文件必须为 0600", ownerOnlyFile, Files.getPosixFilePermissions(cacheFile.toPath()))
        assertEquals(data.size.toLong(), cache.cacheSize("attachments/k1"))
        assertArrayEquals(data, cache.openCacheStream("attachments/k1")!!.readBytes())
    }

    /**
     * `clearAll` 清空全部落盘内容——**判据必须是「磁盘上真实存在的文件」**（ISSUE-P3-246）。
     *
     * 为什么不用 `File.listFiles()` 的**原始返回值**判「目录已清空」：限界
     * [`docs/architecture/已知工程限界.md`](../../../../../../../../docs/architecture/已知工程限界.md) **§7 边界 1**
     * 明令不得如此——本机（Windows/NTFS）实测偶发返回**大写拼写、磁盘上并不存在**的**鬼影条目**
     * （宿主侧 `SyncCacheTest` / `SyncCacheEvictorTest` 已因同形偶发红按实体口径更正，
     * 依据与踩坑史见 `docs/records/SyncCache大写CACHE临时文件定位记录.md` 与 §107 / `ISSUE-P2-192` 的既有口径）。
     *
     * **判别力未放宽**：`isFile == true` 的真残留（真的没删掉）依旧留在返回值里 ⇒ 断言照旧变红；
     * 被排除的只是「目录索引说有、磁盘上没有」的条目。残余：`isFile` 并非百分百可靠
     * （§7 边界 3 自陈约 3% 假阳性未消除）。
     */
    @Test
    fun `clearAll 清空全部落盘内容`() {
        val dir = newCacheDir()
        val cache = SyncCache(dir)
        val data = ByteArray(1024) { it.toByte() }
        cache.writeCacheStreaming("attachments/a", ByteArrayInputStream(data), data.size.toLong())
        cache.writeCache("attachments/b", data)

        assertTrue(cache.clearAll())

        val leftovers = dir.walkTopDown().filter { it.isFile }.toList()
        val listed = (dir.listFiles() ?: emptyArray()).map { it.name }
        assertEquals(
            "清理后不得残留任何**实体**文件（判据为 isFile，见限界 §7 边界 1；" +
                "磁盘上没有的目录索引条目不计入）。索引条目（含鬼影）=$listed；实体残留=${leftovers.map { it.name }}",
            emptyList<File>(),
            leftovers
        )
    }
}
