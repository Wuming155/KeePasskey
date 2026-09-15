package com.keepasskey.app.autofill

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * 自动填充认证流的**接线守卫**（ISSUE-P2-73 AC① / ISSUE-P3-122 IPC-01）。
 *
 * 断言对象是源码文本：这两条都是「某个调用点是否按平台契约写」的结构性约束，
 * 运行时用例无法覆盖（少写一个参数不会有任何测试变红，只会在真机上崩溃或串扰）。
 */
class AutofillAuthResultWiringTest {

    // ── ISSUE-P2-73 AC①：认证结果必须双参且 extras 非空 ───────────────

    @Test
    fun `认证 Activity 不得使用单参 setResult 回传成功`() {
        listOf(AUTOFILL_UNLOCK, AUTOFILL_CONFIRM).forEach { path ->
            val source = readSource(path)

            assertTrue(
                "$path 必须使用双参 setResult(RESULT_OK, Intent) 回传成功",
                source.contains("setResult(RESULT_OK, Intent().putExtras(Bundle.EMPTY))")
            )
            assertFalse(
                "$path 出现单参 setResult(RESULT_OK)——官方明文：Android 12 起 extras 为 null 会崩溃",
                source.contains("setResult(RESULT_OK)\n")
            )
        }
    }

    // ── ISSUE-P3-122 IPC-01：认证 PendingIntent 的 requestCode 单调化 ──

    @Test
    fun `认证入口的 requestCode 必须经单调分配器而非常量`() {
        val source = readSource(BUILDERS)

        assertTrue(
            "必须存在进程级单调分配器（与 CM 通道同构）",
            source.contains("AtomicInteger(AUTH_REQUEST_CODE_BASE)") &&
                source.contains("private fun nextAuthRequestCode(): Int = authRequestCodeAllocator.getAndIncrement()")
        )
        assertTrue(
            "三条认证入口都必须改用分配器",
            Regex("nextAuthRequestCode\\(\\)").findAll(source).count() >= 3
        )
        assertFalse(
            "不得残留把常量 requestCode 直接交给 PendingIntent.getActivity 的写法",
            source.contains("REQUEST_CODE_UNLOCK,") ||
                source.contains("REQUEST_CODE_PICKER,") ||
                source.contains("REQUEST_CODE_CONFIRM_BASE + index,")
        )
    }

    // ── 反向锁定：AC② 的 FLAG 口径不得被「为对齐文档」改掉 ────────────

    @Test
    fun `解锁与确认入口保持 FLAG_IMMUTABLE 而选择器保持 FLAG_MUTABLE`() {
        val source = readSource(BUILDERS)

        assertEquals(
            "只有选择器这一条路径可以用 FLAG_MUTABLE（框架需向其注入 fillIn extras）；" +
                "解锁与确认两条路径不消费任何 fillIn extras，改为 MUTABLE 纯属扩大攻击面",
            1,
            Regex("PendingIntent\\.FLAG_MUTABLE or PendingIntent\\.FLAG_UPDATE_CURRENT")
                .findAll(source).count()
        )
        assertEquals(
            "解锁与确认两条路径必须保持 FLAG_IMMUTABLE",
            2,
            Regex("PendingIntent\\.FLAG_IMMUTABLE or PendingIntent\\.FLAG_UPDATE_CURRENT")
                .findAll(source).count()
        )
        assertTrue(
            "选择器路径必须保留其 FLAG_MUTABLE 的理由注释（否则后人会「顺手统一」掉）",
            source.contains("框架需注入 fillIn extras，必须 FLAG_MUTABLE")
        )
    }

    private fun readSource(path: String): String {
        val file = File(repositoryRoot, path)
        assertTrue("源文件不存在（是否被重命名/移动）：$path", file.isFile)
        return file.readText()
    }

    private companion object {
        const val AUTOFILL_UNLOCK = "app/src/main/java/com/keepasskey/app/autofill/AutofillUnlockActivity.kt"
        const val AUTOFILL_CONFIRM = "app/src/main/java/com/keepasskey/app/autofill/AutofillConfirmActivity.kt"
        const val BUILDERS = "app/src/main/java/com/keepasskey/app/autofill/AutofillDatasetBuilders.kt"

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
            error("无法定位仓库根目录（起始：${System.getProperty("user.dir")}）")
        }

        const val ROOT_SEARCH_DEPTH = 4
    }
}
