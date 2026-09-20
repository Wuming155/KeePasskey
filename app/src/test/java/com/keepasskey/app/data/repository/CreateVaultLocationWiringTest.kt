package com.keepasskey.app.data.repository

import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * 新建库落地位置的接线守卫（**ISSUE-P2-229** 验收 AC②③，静态源码比对，
 * 体例沿用 [com.keepasskey.app.security.AccessibilityNoticeWiringTest]）。
 *
 * 本项的失效形态全是**静默**的，而行为级证据只有真机才能给出（SAF 面板与各 provider 的差异），
 * 故以源码顺序与调用存在性锁定五条不可回退的编排约束：
 *
 * 1. **授权先行**：`takePersistableUriPermission` 必须早于任何建库与写盘动作——否则授权失败时会
 *    留下「已创建但重启后打不开」的库（整改前 `importExternalDatabase` 对同一调用是静默 `catch`，
 *    新建路径改为此处的前置硬失败；打开已有库侧的残留面转登 `ISSUE-P3-230`）；
 * 2. **失败即回滚**：三条失败出口（建库失败 / 写入失败 / 读回复核失败）都删除本次
 *    `CreateDocument` 产出的文档，不在用户选定位置留下空库；
 * 3. **临时件必删**：`cacheDir` 中转库必须在 `finally` 删除；
 * 4. **写后读回复核**，且登记仍走仓库既有的 `importExternalDatabase`（单一登记出口）；
 * 5. **不静默降级**：向导侧未挑定文档时确认按钮必须禁用（不得悄悄改投内部存储）。
 *
 * 断言前不剔除注释——本守卫匹配的是**调用形态**而非散文。
 */
class CreateVaultLocationWiringTest {

    private val creatorSource: String
        get() = readSource("app/src/main/java/com/keepasskey/app/data/repository/SafVaultCreation.kt")

    private val coordinatorSource: String
        get() = readSource(
            "app/src/main/java/com/keepasskey/app/data/repository/VaultLifecycleCoordinator.kt"
        )

    private val wizardSource: String
        get() = readSource(
            "app/src/main/java/com/keepasskey/app/ui/screens/database/CreateVaultWizardDialog.kt"
        )

    @Test
    fun `持久化授权先于任何建库与写盘动作`() {
        val consent = creatorSource.indexOf("takePersistableUriPermission")
        val create = creatorSource.indexOf("databaseSession.create(")
        val write = creatorSource.indexOf("openOutputStream(targetUri")

        assertTrue("SAF 建库路径必须申请持久化授权", consent >= 0)
        assertTrue("授权必须在临时库创建之前裁决", consent < create)
        assertTrue("授权必须在写入目标文档之前裁决", consent < write)
    }

    @Test
    fun `授权失败显式报错而非静默继续`() {
        assertTrue(
            "授权失败必须返回显式失败文案（不得复用 importExternalDatabase 的静默 catch）",
            creatorSource.contains("repo_saf_persist_consent_failed")
        )
        assertTrue(
            "写入失败同样须显式报错",
            creatorSource.contains("repo_saf_write_failed")
        )
    }

    @Test
    fun `三条失败出口均回滚刚创建的文档`() {
        listOf(
            "created !is KdbxResult.Success",
            "written.isFailure",
            "opened !is KdbxResult.Success"
        ).forEach {
            assertTrue("缺失败判定：$it", creatorSource.contains(it))
        }
        assertTrue(
            "每个失败分支都须删除本次创建的文档（至少三处调用）",
            Regex("SafDocumentCleanup\\.deleteCreatedDocument").findAll(creatorSource).count() >= 3
        )
    }

    @Test
    fun `中转临时文件在 finally 中删除`() {
        val finallyAt = creatorSource.indexOf("} finally {")
        assertTrue("缺 finally 收口", finallyAt >= 0)
        assertTrue(
            "cacheDir 中转库必须在 finally 删除",
            creatorSource.substring(finallyAt).contains("tempFile.delete()")
        )
    }

    @Test
    fun `写后读回复核且登记复用仓库单一出口`() {
        assertTrue(
            "写完后须以 openStream 重新绑定（既验证读回、又让用户停留在已解锁态）",
            creatorSource.contains("databaseSession.openStream(")
        )
        assertTrue("登记经 register 回调上行", creatorSource.contains("register(name, targetUri.toString())"))
        assertTrue(
            "协调器侧必须按「系统文件选择器」口径登记（该值决定 isRemote=false，即不参与同步）",
            coordinatorSource.contains("importExternalDatabase(dbName, path, SYNC_TYPE_FILE_PICKER)")
        )
    }

    @Test
    fun `解锁与新建共用同一条 SAF 写回通道`() {
        assertTrue(
            "协调器解锁 content:// 库时须复用 SafVaultCreation.saveWriter（不得各写一份 rwt）",
            coordinatorSource.contains("SafVaultCreation.saveWriter(context, uri, activeDb.name)")
        )
        assertTrue(
            "rwt 截断式写只允许存在一处定义",
            Regex("openOutputStream\\(uri, \"rwt\"\\)").findAll(creatorSource).count() == 1
        )
    }

    @Test
    fun `未挑定文档时不得静默回落到内部存储`() {
        assertTrue(
            "位置校验必须参与表单有效性（否则未挑定也会创建）",
            wizardSource.contains("isKeyFileValid && isLocationValid")
        )
        assertTrue(
            "自选位置下未挑定即视为无效",
            wizardSource.contains("storageLocation == VaultStorageLocation.INTERNAL || selectedVaultUri.isNotBlank()")
        )
    }

    private fun readSource(path: String): String {
        val file = File(repositoryRoot, path)
        assertTrue("源码文件不存在（是否被重命名或移动）：$path", file.isFile)
        return file.readText()
    }

    private companion object {
        const val ROOT_SEARCH_DEPTH = 6

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
