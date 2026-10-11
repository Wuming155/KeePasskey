package com.keepasskey.app.autofill

import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * ISSUE-P3-577：会话授权宽限的**敏感值判据必须计入当前 TOTP 直填值**。
 *
 * 与仓库既有静态接线守卫同范式（`AutofillAuthResultRecheckWiringTest` /
 * `AutofillAuthResultWiringTest`）：数据集路径的豁免判据由 [KeePasskeyAutofillService] 的
 * 扩展函数装配（依赖 Android `AutofillId` 与 Hilt 注入的仓库 / 设置存储），JVM 侧无法构造
 * 等价运行现场，故以源码级不变式锁定「候选 TOTP 快照被统计并并入敏感值参数」——
 * 纯策略面用例（`AutofillSessionGrantStoreTest`）只证「敏感值 ⇒ 不豁免」，
 * **不证**接线（判据算而不判时它照样绿）。
 */
class AutofillSensitiveValueWiringTest {

    @Test
    fun `数据集路径的敏感值判据必须统计候选 TOTP 与交付面同口径`() {
        val policySource = readSource(UNLOCKED_CANDIDATES_PATH)

        assertTrue(
            "豁免判据必须对候选计算当前 TOTP（ISSUE-P3-577）",
            policySource.contains("calculateEntryTotp")
        )
        assertTrue(
            "TOTP 统计必须与交付面同口径——共用 totpIsDeliverable（非 HOTP 且码非空）",
            policySource.contains("AutofillAuthenticationPolicy.totpIsDeliverable")
        )
        assertTrue(
            "TOTP 统计结果必须并入敏感值参数（否则算而不判）",
            policySource.contains("hasDeliverableTotp") &&
                policySource.contains("datasetCarriesSensitiveValue") &&
                policySource.contains("carriesSensitiveValue")
        )
        assertTrue(
            "TOTP 统计必须以 OTP 框存在（otpId 非空）为前提，无 OTP 框的表单不得被误收紧",
            policySource.contains("otpId != null")
        )
    }

    @Test
    fun `数据集装配必须把 OTP 框 id 传入豁免判据且直填共用同一谓词`() {
        val buildersSource = readSource(DATASET_BUILDERS_PATH)
        assertTrue(
            "调用 unlockedConfirmationPolicy 时必须传入 otpId（命名参数防与 passwordId 对调）",
            buildersSource.contains("otpId = otpId")
        )
        assertTrue(
            "直填与豁免判据必须共用 totpIsDeliverable（单一事实源，杜绝口径漂移）",
            buildersSource.contains("AutofillAuthenticationPolicy.totpIsDeliverable")
        )
    }

    private fun readSource(path: String): String {
        val file = File(repositoryRoot, path)
        assertTrue("源文件不存在（是否被重命名/移动）：$path", file.isFile)
        return file.readText()
    }

    private companion object {
        const val UNLOCKED_CANDIDATES_PATH =
            "app/src/main/java/com/keepasskey/app/autofill/AutofillUnlockedCandidates.kt"
        const val DATASET_BUILDERS_PATH =
            "app/src/main/java/com/keepasskey/app/autofill/AutofillDatasetBuilders.kt"
        const val ROOT_SEARCH_DEPTH = 4

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
