package com.keepasskey.sync.engine

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.io.FileOutputStream
import java.nio.file.Files
import java.nio.file.attribute.PosixFilePermission
import java.util.UUID

/**
 * **ISSUE-P3-202 支点实测**：应用私有目录下由裸 `FileOutputStream` 新建的文件，其**默认权限**。
 *
 * ## 争议的问题
 *
 * `SyncCache.writeCache` / `writeBaseContent` / `SyncRollbackGuard.persist` 在 `rename` 之前
 * **未显式收敛权限**，与「密文自落盘第一刻起即为仅属主可见」的自声明口径存在落差。
 * 该落差是否构成**真实暴露窗口**，只取决于两项运行时事实（宿主 JVM 均不可代表）：
 * 1. 新建文件的默认权限（受应用进程 `umask` 与平台策略影响）；
 * 2. 父目录是否允许第三方**穿越/枚举**（决定"知道文件名"能否被利用）。
 *
 * 本用例把两项事实分别钉死，避免以「反正 umask 会兜住」或「必然暴露」任一侧的臆测收口。
 *
 * 实测留痕（Redmi 4X / Android 17 / API 37，2026-09-19）：`cacheDir` 为 `0771`
 * （`rwxrwx--x`：others 仅可穿越、**不可读不可写**）——故「文件名含随机 UUID」并非无关紧要，
 * 它是「目录可穿越但文件不可读」之外的**第二道**障碍。
 */
@RunWith(AndroidJUnit4::class)
class RawFileWritePermissionDeviceTest {

    private val context = InstrumentationRegistry.getInstrumentation().targetContext

    private val ownerOnlyFile =
        setOf(PosixFilePermission.OWNER_READ, PosixFilePermission.OWNER_WRITE)

    @Test
    fun `裸 FileOutputStream 新建文件即为仅属主可读写`() {
        val cacheProbeDir = File(context.cacheDir, "raw-write-permission-probe").apply {
            deleteRecursively()
            mkdirs()
        }
        val rollbackDir = File(context.filesDir, ROLLBACK_DIR_NAME).apply { mkdirs() }

        val cacheTmp = File(cacheProbeDir, "tmp-${UUID.randomUUID()}")
        val rollbackTmp = File(rollbackDir, "tmp-${UUID.randomUUID()}")

        FileOutputStream(cacheTmp).use { it.write(byteArrayOf(1)) }
        FileOutputStream(rollbackTmp).use { it.write(byteArrayOf(1)) }

        val cacheTmpPerms = Files.getPosixFilePermissions(cacheTmp.toPath())
        val rollbackTmpPerms = Files.getPosixFilePermissions(rollbackTmp.toPath())

        // 两条路径分别断言，使失败信息能精确定位到具体落点
        assertEquals(
            "ISSUE-P3-202 支点：应用私有目录下裸 FileOutputStream 新建的文件应即为 0600。" +
                "若此断言失败，则「写入窗口期以默认 umask 暴露」成立，须按 P3-202 AC① 收敛 tmp 权限；" +
                "cacheTmp=$cacheTmpPerms rollbackTmp=$rollbackTmpPerms",
            ownerOnlyFile,
            cacheTmpPerms
        )
        assertEquals(
            "ISSUE-P3-202：filesDir/$ROLLBACK_DIR_NAME 下裸写文件同样应为 0600；cacheTmp=$cacheTmpPerms rollbackTmp=$rollbackTmpPerms",
            ownerOnlyFile,
            rollbackTmpPerms
        )
    }

    @Test
    fun `私有目录不得向第三方开放读写`() {
        val rollbackDir = File(context.filesDir, ROLLBACK_DIR_NAME).apply { mkdirs() }

        val cacheDirPerms = Files.getPosixFilePermissions(context.cacheDir.toPath())
        val filesDirPerms = Files.getPosixFilePermissions(context.filesDir.toPath())
        val rollbackDirPerms = Files.getPosixFilePermissions(rollbackDir.toPath())
        val all = cacheDirPerms + filesDirPerms + rollbackDirPerms

        // 实测事实（Redmi 4X / API 37，2026-09-19）：平台预置的应用私有目录为 0771
        // （`rwxrwx--x`）——group 位即应用自身（沙箱内等价于属主），others 仅有**穿越**位。
        // 真正的不变量是「**第三方**（others）不得读取或写入」，穿越位 EXECUTE 是平台设计。
        assertFalse(
            "应用私有目录不得向 others 开放读或写（穿越位 EXECUTE 由平台设计保留）。" +
                "cacheDir=$cacheDirPerms filesDir=$filesDirPerms rollbackDir=$rollbackDirPerms",
            all.any {
                it == PosixFilePermission.OTHERS_READ || it == PosixFilePermission.OTHERS_WRITE
            }
        )
        // 自建的 rollback 目录由 mkdirs() 产出 ⇒ 其权限即进程 umask 的直接证据
        assertEquals(
            "mkdirs() 新建目录应为 0700（进程 umask 0077 的直接证据）；" +
                "cacheDir=$cacheDirPerms filesDir=$filesDirPerms rollbackDir=$rollbackDirPerms",
            ownerOnlyFile + PosixFilePermission.OWNER_EXECUTE,
            rollbackDirPerms
        )
    }

    /**
     * ISSUE-P3-202 AC②：**写入窗口期**权限断言——`writeTmpSynced` 的产物在交付
     * （rename）之前、即 tmp 仍以随机名存在的窗口内，就必须已是 0600。
     * 此前设备侧只断言终态权限（[SyncCacheAndroidRuntimeTest]），窗口内无断言；
     * 整改后 `SyncCache.writeCache` / `writeBaseContent` / `writeCacheStreaming` /
     * `SyncRollbackGuard.persist` 均经该原语落盘，本断言即「第一字节即仅属主」的窗口证据。
     */
    @Test
    fun `writeTmpSynced 产物在交付前的 tmp 窗口内即为仅属主`() {
        val probeDir = File(context.cacheDir, "tmp-window-permission-probe").apply {
            deleteRecursively()
            mkdirs()
        }
        val files = SyncCacheFiles(probeDir)
        val target = File(probeDir, "target.bin")
        val tmp = files.tmpFileFor(target)

        files.writeTmpSynced(tmp, byteArrayOf(1, 2, 3))

        assertTrue("writeTmpSynced 后 tmp 必须仍存在（未交付，窗口期成立）", tmp.exists())
        assertEquals(
            "ISSUE-P3-202：tmp 窗口内（交付前）必须已收敛为 0600；tmp=$tmp",
            ownerOnlyFile,
            Files.getPosixFilePermissions(tmp.toPath())
        )
        files.moveAtomically(tmp, target)
        assertEquals(
            "交付后的目标文件必须仍为 0600",
            ownerOnlyFile,
            Files.getPosixFilePermissions(target.toPath())
        )
        probeDir.deleteRecursively()
    }

    /**
     * ISSUE-P3-202 AC②：`SyncRollbackGuard` 的状态目录与状态文件落盘即仅属主——
     * 原实现 `persist` / `mkdirs()` 全程不调用任何收敛原语，本断言锁定整改后的口径。
     */
    @Test
    fun `防回滚状态目录与状态文件落盘即仅属主`() {
        val probeDir = File(context.filesDir, "rollback-p3-202-probe").apply {
            deleteRecursively()
            mkdirs()
        }
        // NoopSyncIntegrityMac 仅禁用完整性认证（verify 恒 false），persist 照常落盘——
        // 本用例只裁决权限面，与 MAC 语义无关
        val guard = SyncRollbackGuard(probeDir, NoopSyncIntegrityMac)
        guard.recordAccepted("p3-202-probe-remote", byteArrayOf(9))

        assertEquals(
            "ISSUE-P3-202：SyncRollbackGuard 构造期必须把状态目录收敛为 0700",
            ownerOnlyFile + PosixFilePermission.OWNER_EXECUTE,
            Files.getPosixFilePermissions(probeDir.toPath())
        )
        val stateFiles = probeDir.listFiles { f -> f.name.endsWith(".rollback") }.orEmpty()
        assertTrue("recordAccepted 后必须存在状态文件", stateFiles.isNotEmpty())
        for (stateFile in stateFiles) {
            assertEquals(
                "ISSUE-P3-202：防回滚状态文件落盘即 0600；file=${stateFile.name}",
                ownerOnlyFile,
                Files.getPosixFilePermissions(stateFile.toPath())
            )
        }
        probeDir.deleteRecursively()
    }

    private companion object {
        /** 与 `SyncRollbackGuard` 的状态目录同名（`filesDir/rollback`） */
        const val ROLLBACK_DIR_NAME = "rollback"
    }
}
