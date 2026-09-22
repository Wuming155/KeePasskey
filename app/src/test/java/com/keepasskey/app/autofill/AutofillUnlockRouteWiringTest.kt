package com.keepasskey.app.autofill

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * PD-05 候选 C（ISSUE-P3-256 AC②）的**接线守卫**（源码文本断言），与
 * [AutofillUnlockRouterTest]（判据纯函数穷举）配对。
 *
 * 本项要防的失效形态是「判据写对了但没接上」或「接上了却把安全面拆掉」：
 *
 * 1. **解锁页必须经纯函数路由**：[AutofillUnlockActivity] 内出现
 *    [AutofillUnlockRouter.route] 调用，且不得绕过判据自判；
 * 2. **解锁页不得自建 Dataset**：PD-05 对候选 B 的禁令延续——归属展示 / 确认 / Dataset
 *    回传全部由落地页承担；
 * 3. **PICKER 分支逐字保持旧构造**：ISSUE-P2-86 真机验证可用的选择器入参不得漂移；
 * 4. **CONFIRM 分支入参与转发契约**：确认页六个既有 extras 齐备，结果经既有
 *    `pickerResultLauncher` 原样转发（`RESULT_OK + data` 转发、取消 `RESULT_CANCELED +
 *    Bundle.EMPTY`）；
 * 5. **判据源不得退化**：绑定门（ISSUE-P2-46）、exact 强匹配语义、唯一性判定（singleOrNull）
 *    必须留在 [AutofillUnlockRouter] 源内——整函数改成恒 PICKER 空壳时本组断言必红。
 */
class AutofillUnlockRouteWiringTest {

    private val unlockSource: String get() = readSource(UNLOCK)
    private val routerSource: String get() = readSource(ROUTER)

    @Test
    fun `解锁页必须经纯函数路由而不得自判`() {
        assertTrue(
            "解锁页未调用 AutofillUnlockRouter.route——路由判据必须单点化（PD-05 AC②）",
            unlockSource.contains("AutofillUnlockRouter.route(")
        )
        assertFalse(
            "解锁页内不得复读匹配语义（MatchReason）——是否强匹配只准由判据回答",
            unlockSource.contains("MatchReason.")
        )
        // 判据输入必须经既有绑定门与打分器（不得自造匹配语义）
        assertTrue(
            "路由输入必须经 AndroidPackageBindingPolicy.isPackageDimensionAuthorized（ISSUE-P2-46 门）",
            unlockSource.contains("AndroidPackageBindingPolicy.isPackageDimensionAuthorized(")
        )
        assertTrue(
            "路由输入必须经既有 AutofillCandidateRanker.rank 打分（不得自造匹配语义）",
            unlockSource.contains("AutofillCandidateRanker.rank(")
        )
    }

    @Test
    fun `解锁页不得自建数据集回传（PD-05 候选 B 禁令延续）`() {
        assertFalse(
            "解锁页出现 Dataset 构造——数据集构造必须只在落地页（选择器 / 确认页）内",
            unlockSource.contains("Dataset.Builder")
        )
        assertFalse(
            "解锁页出现共享载荷构造器——回传载荷只由 AutofillAuthResultDelivery 产出",
            unlockSource.contains("buildAuthenticationResultDataset(")
        )
        assertFalse(
            "解锁页出现 authenticationResultIntent——解锁页只转发落地页结果，不自行组装成功载荷",
            unlockSource.contains("authenticationResultIntent(")
        )
    }

    @Test
    fun `PICKER 分支必须保持旧版逐字的选择器构造`() {
        // ISSUE-P2-86 真机验证可用的四条入参（逐字锁定，防「顺手重构」漂移）
        listOf(
            "putExtra(AutofillPickerActivity.EXTRA_USERNAME_ID, usernameId)",
            "putExtra(AutofillPickerActivity.EXTRA_PASSWORD_ID, passwordId)"
        ).forEach { line ->
            assertTrue("PICKER 分支缺少逐字构造行：$line", unlockSource.contains(line))
        }
        assertTrue(
            "PICKER 分支必须原样搬运服务端下发的调用方包名",
            unlockSource.contains("intent.getStringExtra(AutofillPickerActivity.EXTRA_CALLING_PACKAGE)")
        )
        assertTrue(
            "PICKER 分支必须原样搬运服务端下发的表单自报域",
            unlockSource.contains("intent.getStringExtra(AutofillPickerActivity.EXTRA_WEB_DOMAIN)")
        )
    }

    @Test
    fun `CONFIRM 分支必须携带确认页全部入参并经既有 launcher 转发`() {
        listOf(
            "AutofillConfirmActivity.EXTRA_TARGET_USERNAME_ID",
            "AutofillConfirmActivity.EXTRA_TARGET_PASSWORD_ID",
            "AutofillConfirmActivity.EXTRA_ENTRY_ID",
            "AutofillConfirmActivity.EXTRA_CREDENTIAL_TITLE",
            "AutofillConfirmActivity.EXTRA_GRANT_PACKAGE",
            "AutofillConfirmActivity.EXTRA_GRANT_DOMAIN"
        ).forEach { extra ->
            assertTrue("CONFIRM 分支缺少确认页入参 $extra", unlockSource.contains(extra))
        }
        assertTrue(
            "确认页结果必须经既有 pickerResultLauncher 原样转发（与选择器同一通道）",
            unlockSource.contains("pickerResultLauncher.launch(confirmIntent)")
        )
        assertTrue(
            "PICKER 分支同样必须经 pickerResultLauncher（转发契约不随路由分叉）",
            unlockSource.contains("pickerResultLauncher.launch(pickerIntent)")
        )
        // 转发契约逐字保持（与 AutofillAuthResultWiringTest 同源口径）
        assertTrue(
            "成功结果必须原样转发 resultCode + data",
            unlockSource.contains("setResult(resultCode, data)")
        )
        assertTrue(
            "取消必须走 RESULT_CANCELED + Bundle.EMPTY 双参（Android 12 extras 非 null 契约）",
            unlockSource.contains("setResult(RESULT_CANCELED, Intent().putExtras(Bundle.EMPTY))")
        )
        // 不确定分支回落 PICKER（PD-05 fail-closed）
        assertTrue(
            "判据计算失败必须回落 PICKER，不得静默直达确认页",
            unlockSource.contains("Routing(AutofillUnlockRouter.Route.Picker, null)")
        )
    }

    @Test
    fun `判据源必须保留绑定门 exact强匹配与唯一性判定`() {
        assertTrue(
            "绑定门被移除——未绑定调用方将直达确认页（ISSUE-P2-46 fail-closed 回归）",
            routerSource.contains("if (!packageDimensionAuthorized) return Route.Picker")
        )
        assertTrue(
            "强匹配必须消费既有 exact 语义 EXACT_DOMAIN",
            routerSource.contains("MatchReason.EXACT_DOMAIN")
        )
        assertTrue(
            "强匹配必须消费既有 exact 语义 EXACT_PACKAGE",
            routerSource.contains("MatchReason.EXACT_PACKAGE")
        )
        assertTrue(
            "唯一性判定必须留在判据内（singleOrNull）",
            routerSource.contains("singleOrNull")
        )
        assertFalse(
            "判据退化为恒 PICKER 空壳（整函数体被替换）——CONFIRM 分支将永不可达",
            routerSource.contains("): Route {\n        return Route.Picker")
        )
    }

    private fun readSource(path: String): String {
        val file = File(repositoryRoot, path)
        assertTrue("源文件不存在（是否被重命名/移动）：$path", file.isFile)
        return file.readText()
    }

    private companion object {
        const val UNLOCK = "app/src/main/java/com/keepasskey/app/autofill/AutofillUnlockActivity.kt"
        const val ROUTER = "app/src/main/java/com/keepasskey/app/autofill/AutofillUnlockRouter.kt"

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
