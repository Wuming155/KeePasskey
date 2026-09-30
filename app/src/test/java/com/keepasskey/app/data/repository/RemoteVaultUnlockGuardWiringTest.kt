package com.keepasskey.app.data.repository

import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * 远端登记条目解锁 fail-closed 守卫的接线守卫（**ISSUE-P2-399**，静态源码比对，
 * 体例沿用 `CreateVaultLocationWiringTest`）。
 *
 * 整改前的失效形态是**数据丢失级**且静默的：远端登记条目（path 为 URL）在解锁时走
 * `File(path).exists() == false` 分支，被「文件缺失即新建空库」兜底吞掉——空库顶替云端库，
 * 且同步凭据在位时会被自动同步整库上传覆盖云端真库。行为级证据只有真机可给，
 * 故以源码顺序锁定两条不可回退的约束：
 *
 * 1. `unlockActiveDatabase` 必须存在 `isRemote` 早退守卫并落显式失败文案
 *    （`repo_cloud_vault_not_downloaded`）；
 * 2. 该守卫必须位于 `databaseSession.create(`（新建空库兜底）**之前**。
 *
 * 断言前不剔除注释——本守卫匹配的是**调用形态**而非散文。
 */
class RemoteVaultUnlockGuardWiringTest {

    private val coordinatorSource: String
        get() = readSource(
            "app/src/main/java/com/keepasskey/app/data/repository/VaultLifecycleCoordinator.kt"
        )

    @Test
    fun `远端条目文件缺失时显式失败而非新建空库`() {
        assertTrue(
            "[$COORDINATOR] 缺少远端条目守卫文案（锚点是否已变更？）",
            coordinatorSource.contains("repo_cloud_vault_not_downloaded")
        )
    }

    @Test
    fun `远端守卫必须先于新建空库兜底裁决`() {
        val guard = coordinatorSource.indexOf("activeDb.isRemote")
        val createFallback = coordinatorSource.indexOf("databaseSession.create(")
        assertTrue("[$COORDINATOR] 未找到 isRemote 守卫", guard >= 0)
        assertTrue("[$COORDINATOR] 未找到新建空库兜底分支", createFallback >= 0)
        assertTrue(
            "[$COORDINATOR] isRemote 守卫必须在新建空库兜底之前（否则 URL 条目仍会落入空库兜底）",
            guard < createFallback
        )
    }

    private fun readSource(path: String): String {
        val file = File(repositoryRoot, path)
        assertTrue("源码文件不存在（是否被重命名或移动）：$path", file.isFile)
        return file.readText()
    }

    private companion object {
        const val ROOT_SEARCH_DEPTH = 6

        const val COORDINATOR = "VaultLifecycleCoordinator"

        /** 仓库根：同时具备 app 与 core 模块源码目录的最近祖先 */
        val repositoryRoot: File by lazy {
            var dir: File? = File(System.getProperty("user.dir").orEmpty()).absoluteFile
            repeat(ROOT_SEARCH_DEPTH) {
                val candidate = dir ?: return@repeat
                if (File(candidate, "app/src/main/java").isDirectory &&
                    File(candidate, "core/src/main/java").isDirectory
                ) {
                    return@lazy candidate
                }
                dir = candidate.parentFile
            }
            error("未能定位仓库根（自 ${System.getProperty("user.dir")} 向上 ${ROOT_SEARCH_DEPTH} 层）")
        }
    }
}
