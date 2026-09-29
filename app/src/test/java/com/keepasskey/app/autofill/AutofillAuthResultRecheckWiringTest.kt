package com.keepasskey.app.autofill

import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * ISSUE-P2-384：认证回传链路必须在构造 Dataset **之前**复检字段级屏蔽。
 *
 * 与仓库既有静态接线守卫同范式（`AutofillAuthResultWiringTest` /
 * `UnlockedNotificationWiringTest`）：Activity 依赖系统 Autofill API 与 Hilt，
 * JVM 侧无法构造等价运行现场，故以源码级不变式锁定「复检闸门存在且位于构造之前」。
 */
class AutofillAuthResultRecheckWiringTest {

    private val pickerSource: String by lazy { readSource(PICKER_PATH) }
    private val confirmSource: String by lazy { readSource(CONFIRM_PATH) }
    private val policySource: String by lazy { readSource(POLICY_PATH) }

    @Test
    fun `选择器交付前必须复检字段屏蔽`() {
        assertTrue(
            "选择器 deliver 必须调用 AutofillAuthDeliveryBlockPolicy.filter（ISSUE-P2-384 AC①）",
            pickerSource.contains("AutofillAuthDeliveryBlockPolicy.filter")
        )
        assertTrue(
            "选择器交付前必须读取字段屏蔽仓库（与 onFillRequest 同源判定）",
            pickerSource.contains("autofillFieldBlocklistStore")
        )
        val filterIdx = pickerSource.indexOf("AutofillAuthDeliveryBlockPolicy.filter")
        // 只认真实赋值调用点，避免误命中注释中的函数名
        val buildIdx = pickerSource.indexOf("= buildAuthenticationResultDataset")
        assertTrue(
            "选择器必须在 buildAuthenticationResultDataset 之前完成复检（fail-closed）",
            filterIdx >= 0 && buildIdx >= 0 && filterIdx < buildIdx
        )
    }

    @Test
    fun `确认页交付前必须复检字段屏蔽`() {
        assertTrue(
            "确认页 resolveAuthResultIntent 必须调用 AutofillAuthDeliveryBlockPolicy.filter",
            confirmSource.contains("AutofillAuthDeliveryBlockPolicy.filter")
        )
        assertTrue(
            "确认页必须注入字段屏蔽仓库",
            confirmSource.contains("autofillFieldBlocklistStore")
        )
        assertTrue(
            "确认页必须把复检结果传给 AutofillConfirmAuthDelivery.build（Dataset 构造唯一入口）",
            confirmSource.contains("AutofillConfirmAuthDelivery.build") &&
                confirmSource.contains("deliverable = deliverable")
        )
        val filterIdx = confirmSource.indexOf("AutofillAuthDeliveryBlockPolicy.filter")
        val buildIdx = confirmSource.indexOf("AutofillConfirmAuthDelivery.build")
        assertTrue(
            "确认页必须在 Dataset 构造之前完成复检",
            filterIdx >= 0 && buildIdx >= 0 && filterIdx < buildIdx
        )
        val deliverySource = readSource(CONFIRM_DELIVERY_PATH)
        assertTrue(
            "AutofillConfirmAuthDelivery 必须委托 buildAuthenticationResultDataset 构造 Dataset",
            deliverySource.contains("buildAuthenticationResultDataset")
        )
        assertTrue(
            "AutofillConfirmAuthDelivery 必须使用 deliverable.usernameId / passwordId（复检后 id）",
            deliverySource.contains("deliverable.usernameId") &&
                deliverySource.contains("deliverable.passwordId")
        )
    }

    @Test
    fun `setIgnoredIds 已接入 FillResponse 以失效系统侧缓存`() {
        val serviceSource = readSource(SERVICE_PATH)
        assertTrue(
            "onFillRequest 必须在 FillResponse 上写 setIgnoredIds（ISSUE-P2-384 AC②）",
            serviceSource.contains("setIgnoredIds")
        )
        assertTrue(
            "屏蔽框 id 必须来自 TargetFields.blockedIds 投影",
            serviceSource.contains("blockedIds")
        )
        val resolverSource = readSource(RESOLVER_PATH)
        assertTrue(
            "TargetFieldResolver 必须收集 blockedIds 并投影到 TargetFields",
            resolverSource.contains("blockedIds") && resolverSource.contains("allowUsername")
        )
    }

    @Test
    fun `策略对象与 Activity 同包同源且只会收紧`() {
        assertTrue(policySource.contains("internal object AutofillAuthDeliveryBlockPolicy"))
        assertTrue(
            "策略注释必须声明 fail-closed 语义",
            policySource.contains("fail-closed")
        )
        assertTrue(policySource.contains("AutofillFieldBlockPolicy.decide"))
    }

    private fun readSource(path: String): String {
        val file = File(repositoryRoot, path)
        assertTrue("源文件不存在（是否被重命名/移动）：$path", file.isFile)
        return file.readText()
    }

    private companion object {
        const val PICKER_PATH =
            "app/src/main/java/com/keepasskey/app/autofill/AutofillPickerActivity.kt"
        const val CONFIRM_PATH =
            "app/src/main/java/com/keepasskey/app/autofill/AutofillConfirmActivity.kt"
        const val CONFIRM_DELIVERY_PATH =
            "app/src/main/java/com/keepasskey/app/autofill/AutofillConfirmAuthDelivery.kt"
        const val POLICY_PATH =
            "app/src/main/java/com/keepasskey/app/autofill/AutofillAuthDeliveryBlockPolicy.kt"
        const val SERVICE_PATH =
            "app/src/main/java/com/keepasskey/app/autofill/KeePasskeyAutofillService.kt"
        const val RESOLVER_PATH =
            "app/src/main/java/com/keepasskey/app/autofill/AutofillTargetFieldResolver.kt"
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
