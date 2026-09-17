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
        // 确认页：数据集自带真实值 ⇒ 以「双参 setResult(RESULT_OK, Intent 非空 extras)」结束
        val confirm = readSource(AUTOFILL_CONFIRM)
        assertTrue(
            "$AUTOFILL_CONFIRM 必须使用双参 setResult(RESULT_OK, Intent) 回传成功",
            confirm.contains("setResult(RESULT_OK, Intent().putExtras(Bundle.EMPTY))")
        )
        // 解锁页（ISSUE-P2-86）：解锁成功后链入选择器并**原样转发**其结果，
        // 故成功回传恒为双参 setResult(resultCode, data)，data 即选择器回传的认证结果
        val unlock = readSource(AUTOFILL_UNLOCK)
        assertTrue(
            "$AUTOFILL_UNLOCK 必须把选择器的 resultCode + data 以双参 setResult 原样转发给框架",
            unlock.contains("setResult(resultCode, data)")
        )
        listOf(AUTOFILL_UNLOCK, AUTOFILL_CONFIRM).forEach { path ->
            assertFalse(
                "$path 出现单参 setResult(RESULT_OK)——官方明文：Android 12 起 extras 为 null 会崩溃",
                readSource(path).contains("setResult(RESULT_OK)\n")
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

    // ── ISSUE-P2-86：认证 PendingIntent 的可变性必须与「是否回传认证结果」一致 ──

    @Test
    fun `解锁与选择器入口必须 FLAG_MUTABLE 而确认入口保持 FLAG_IMMUTABLE`() {
        val source = readSource(BUILDERS)

        assertEquals(
            "解锁与选择器两条路径都必须 FLAG_MUTABLE——框架要向认证 PendingIntent 注入 fillIn " +
                "extras 并消费其回传的认证结果；真机对照（ISSUE-P2-86 阶段一）显示「回传数据集但" +
                "保留 FLAG_IMMUTABLE + 基 Intent 带 NEW_TASK|CLEAR_TOP」恒不填充",
            2,
            Regex("PendingIntent\\.FLAG_MUTABLE or PendingIntent\\.FLAG_UPDATE_CURRENT")
                .findAll(source).count()
        )
        assertEquals(
            "确认路径保持 FLAG_IMMUTABLE——阶段二实测：改为 FLAG_MUTABLE 后该路径**仍未写入**" +
                "（真机 2026-09-17），故不做未经证实的改动、也不扩大攻击面",
            1,
            Regex("PendingIntent\\.FLAG_IMMUTABLE or PendingIntent\\.FLAG_UPDATE_CURRENT")
                .findAll(source).count()
        )
        assertTrue(
            "FLAG_MUTABLE 的理由注释必须保留（否则后人会「顺手统一」掉）",
            source.contains("必须 FLAG_MUTABLE")
        )
    }

    @Test
    fun `认证入口的基 Intent 必须与已实测可用的选择器入口同构`() {
        val source = readSource(BUILDERS)
        val pickerEntry = source.substringAfter("val pickerIntent = Intent(this, AutofillPickerActivity::class.java)")
            .substringBefore("PendingIntent.getActivity(")
        assertFalse(
            "选择器入口（对照组）本身不得携带 activity flag",
            pickerEntry.contains("FLAG_ACTIVITY")
        )

        val unlockEntry = source
            .substringAfter("val unlockIntent = Intent(this, AutofillUnlockActivity::class.java)")
            .substringBefore("PendingIntent.getActivity(")
        assertFalse(
            "解锁入口基 Intent 不得携带任何 activity flag——旧构造的 NEW_TASK|CLEAR_TOP 与" +
                "已实测可用的选择器构造相异（ISSUE-P2-86 阶段一）",
            unlockEntry.contains("FLAG_ACTIVITY")
        )
        listOf(
            "AutofillPickerActivity.EXTRA_USERNAME_ID",
            "AutofillPickerActivity.EXTRA_PASSWORD_ID",
            "AutofillPickerActivity.EXTRA_CALLING_PACKAGE",
            "AutofillPickerActivity.EXTRA_WEB_DOMAIN"
        ).forEach { extra ->
            assertTrue(
                "解锁入口必须随认证 Intent 下传选择器上下文 $extra（供解锁页链入选择器）",
                unlockEntry.contains(extra)
            )
        }
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
