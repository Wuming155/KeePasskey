package com.keepasskey.app.autofill

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * 自动填充认证流的**接线守卫**（ISSUE-P2-73 AC① / ISSUE-P3-122 IPC-01 / ISSUE-P2-88）。
 *
 * 断言对象是源码文本：这几条都是「某个调用点是否按平台契约写」的结构性约束，
 * 运行时用例无法覆盖（少写一个参数不会有任何测试变红，只会在真机上崩溃或静默不填充）。
 *
 * 官方 `Dataset.Builder#setAuthentication` 契约（**两条都是强制项**）：
 * ① 「Do **not** make the provided pending intent immutable by using `PendingIntent.FLAG_IMMUTABLE`
 *    as the platform needs to fill in the authentication arguments」；
 * ② 认证结束后必须把「fully populated dataset」经 `AutofillManager.EXTRA_AUTHENTICATION_RESULT`
 *    回传——「it will replace the authenticated dataset and will be immediately filled in」。
 */
class AutofillAuthResultWiringTest {

    // ── ISSUE-P2-73 AC① / ISSUE-P2-88：认证结果必须双参、extras 非空，且携带数据集 ──

    @Test
    fun `认证 Activity 不得使用单参 setResult 回传`() {
        listOf(AUTOFILL_UNLOCK, AUTOFILL_CONFIRM, AUTOFILL_PICKER).forEach { path ->
            assertFalse(
                "$path 出现单参 setResult(RESULT_OK)——官方明文：Android 12 起 extras 为 null 会崩溃",
                readSource(path).contains("setResult(RESULT_OK)\n")
            )
        }
        // 解锁页（ISSUE-P2-86）：解锁成功后链入选择器并**原样转发**其结果，
        // 故成功回传恒为双参 setResult(resultCode, data)，data 即选择器回传的认证结果
        assertTrue(
            "$AUTOFILL_UNLOCK 必须把选择器的 resultCode + data 以双参 setResult 原样转发给框架",
            readSource(AUTOFILL_UNLOCK).contains("setResult(resultCode, data)")
        )
    }

    @Test
    fun `成功回传必须携带数据集而非空载荷`() {
        // 确认页（ISSUE-P2-88）：认证成功后必须回传真实 Dataset；只回传「成功 + 空 extras」
        // 会让框架无值可写（真机留痕 `onAuthenticationResult(): empty intent`，输入框恒为空）
        val confirm = readSource(AUTOFILL_CONFIRM)
        val okResultArgs = Regex("""setResult\(\s*RESULT_OK\s*,\s*([^)\n]*)""")
            .findAll(confirm)
            .map { it.groupValues[1] }
            .toList()
        assertTrue("确认页必须存在 RESULT_OK 回传路径", okResultArgs.isNotEmpty())
        okResultArgs.forEach { argument ->
            assertFalse(
                "确认页的 RESULT_OK 回传不得使用空载荷（实际实参：$argument）——" +
                    "必须携带经 $AUTOFILL_DELIVERY 构造的真实 Dataset",
                argument.contains("Bundle.EMPTY")
            )
        }
        assertTrue(
            "确认页必须经共享交付通道回传数据集（认证成功路径）",
            confirm.contains("authenticationResultIntent(") &&
                confirm.contains("buildAuthenticationResultDataset(")
        )
        // 选择器（既有可用路径）同样必须走共享交付通道——两处同语义只剩一份实现
        assertTrue(
            "选择器必须经共享交付通道回传数据集",
            readSource(AUTOFILL_PICKER).contains("authenticationResultIntent(")
        )
        // 载荷的构造点：EXTRA_AUTHENTICATION_RESULT 必须出现在交付通道内（唯一构造点）
        val delivery = readSource(AUTOFILL_DELIVERY)
        assertTrue(
            "$AUTOFILL_DELIVERY 必须把数据集放进 AutofillManager.EXTRA_AUTHENTICATION_RESULT",
            delivery.contains("AutofillManager.EXTRA_AUTHENTICATION_RESULT, dataset")
        )
        // 取消路径必须双参 + extras 非空（同上「extras 为 null 会崩溃」口径）
        assertTrue(
            "取消回传必须双参且 extras 非空",
            delivery.contains("internal fun authenticationCanceledIntent(): Intent = Intent().putExtras(Bundle.EMPTY)")
        )
    }

    @Test
    fun `真机实测可用的两条认证路径必须保持同一份数据集构造`() {
        val delivery = readSource(AUTOFILL_DELIVERY)
        // 字段写入是「数据集带值」的唯一实现点；缺失即回传空数据集（build 会抛异常）
        assertTrue(
            "交付通道必须按目标框 id 写入用户名 / 口令字段",
            delivery.contains("builder.setField(\n            usernameId,") &&
                delivery.contains("builder.setField(\n            passwordId,")
        )
        assertTrue(
            "两个目标框 id 皆空 / 凭据无字段时必须以 null 表示「不可交付」",
            delivery.contains("return if (fieldCount == 0) null else builder.build()")
        )
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

    // ── ISSUE-P2-86 / ISSUE-P2-88：三条认证 PendingIntent 一律 FLAG_MUTABLE ──

    @Test
    fun `三条认证 PendingIntent 必须一律 FLAG_MUTABLE`() {
        val source = readSource(BUILDERS)

        assertEquals(
            "解锁 / 选择器 / 二次确认三条认证路径都必须 FLAG_MUTABLE——官方明文：" +
                "「Do not make the provided pending intent immutable ... as the platform needs to " +
                "fill in the authentication arguments」",
            3,
            Regex("PendingIntent\\.FLAG_MUTABLE or PendingIntent\\.FLAG_UPDATE_CURRENT")
                .findAll(source).count()
        )
        assertFalse(
            "不得残留任何 FLAG_IMMUTABLE 的认证 PendingIntent（真机对照：IMMUTABLE 恒不填充）",
            source.contains("PendingIntent.FLAG_IMMUTABLE")
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
        val confirmEntry = source
            .substringAfter("val confirmIntent = Intent(this, AutofillConfirmActivity::class.java)")
            .substringBefore("\n\n")
        assertFalse(
            "确认入口基 Intent 不得携带任何 activity flag（ISSUE-P2-88：三条认证入口同一构造口径）",
            confirmEntry.contains("FLAG_ACTIVITY")
        )
        assertTrue(
            "确认入口必须随认证 Intent 下发目标框 id，否则确认页无法构造字段 id 正确的回传数据集",
            confirmEntry.contains("AutofillConfirmActivity.EXTRA_TARGET_USERNAME_ID") &&
                confirmEntry.contains("AutofillConfirmActivity.EXTRA_TARGET_PASSWORD_ID")
        )
        assertTrue(
            "确认页必须真的读取这两个目标框 id",
            readSource(AUTOFILL_CONFIRM).contains("readAutofillId(EXTRA_TARGET_USERNAME_ID)") &&
                readSource(AUTOFILL_CONFIRM).contains("readAutofillId(EXTRA_TARGET_PASSWORD_ID)")
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

    // ── ISSUE-P3-42：会话授权宽限依赖「候选数据集自带真实值」，不得摘除 ──

    @Test
    fun `已解锁候选数据集必须仍带真实值`() {
        val source = readSource(BUILDERS)
        assertTrue(
            "候选数据集必须仍以 setField 写入真实用户名 / 口令——ISSUE-P3-42 的会话授权宽限分支" +
                "（skipRepeatConfirmation）**不挂**认证 PendingIntent，其填充完全依赖数据集自带的值；" +
                "摘掉会让「30 秒内免二次确认」直接填不出值",
            source.contains("dsBuilder.setField(\n                usernameId,") &&
                source.contains("dsBuilder.setField(\n                passwordId,")
        )
        assertTrue(
            "候选数据集必须仍以 Field.Builder().setValue 承载真实值（而非 null 占位）",
            source.contains("Field.Builder().setValue(AutofillValue.forText(username))") &&
                source.contains("Field.Builder().setValue(AutofillValue.forText(password))")
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
        const val AUTOFILL_PICKER = "app/src/main/java/com/keepasskey/app/autofill/AutofillPickerActivity.kt"
        const val AUTOFILL_DELIVERY = "app/src/main/java/com/keepasskey/app/autofill/AutofillAuthResultDelivery.kt"
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
