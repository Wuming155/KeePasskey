package com.keepasskey.app.autofill

import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * `ISSUE-P2-617` 接线守卫（静态源码断言）。
 *
 * 缺陷形态：浏览器网站凭据记忆按包名 + 签名召回（未绑定已验证域名），候选排序绕过当前域匹配，
 * 已信任调用方的生物识别提示不显示当前网站，最终交付路径也未复检条目与当前域的匹配。
 *
 * 本守卫锁定四条接线（行为判据由 [AutofillCallerEntryMemoryTest] /
 * [AutofillCandidateRankerTest] 的纯函数用例覆盖）：
 * 1. 召回绑定已校验域（见 `AutofillPickerEntryInvariantTest`，此处不重复）；
 * 2. 确认页最终交付前复检条目与当前来源（`entryMatchesDeliveryOrigin`，与候选装配同源判据）；
 * 3. 已信任调用方的生物识别副标题显示当前网站（无域如实标注纯应用表单）；
 * 4. 两处记忆写入点均绑定域（确认页用归属校验后的域，选择器用表单域）。
 *
 * 宿主侧无浏览器autofill驱动面（JVM 造不出跨站同包请求），来源绑定只能以接线守卫锁定——
 * 任一复检被删、提示域被拿掉、写入丢域即红。
 */
class AutofillCallerMemoryOriginWiringTest {

    private val confirmSource: String by lazy { readSource(CONFIRM_ACTIVITY_PATH) }
    private val pickerSource: String by lazy { readSource(PICKER_ACTIVITY_PATH) }
    private val rankerSource: String by lazy { readSource(RANKER_PATH) }
    private val resolverSource: String by lazy { readSource(RESOLVER_PATH) }

    @Test
    fun `确认页交付前必须复检条目与当前来源`() {
        // 复检实现自 Activity 拆至 AutofillConfirmAuthResolver（行数闸门，零行为变更）；
        // 调用仍由 deliverAuthResult 发起，判据实现位置由本守卫锁定
        assertTrue(
            "Activity 必须经 resolveConfirmAuthResultIntent 构造回传（复检内聚其中）",
            confirmSource.contains("resolveConfirmAuthResultIntent(")
        )
        assertTrue(
            "resolveConfirmAuthResultIntent 必须调用 entryMatchesDeliveryOrigin 复检（ISSUE-P2-617：" +
                "缓存重放 / 未来准入来源不得绕过候选装配的两道门）",
            resolverSource.contains("entryMatchesDeliveryOrigin(")
        )
        assertTrue(
            "复检须按条目取回新鲜实例（不得用 Intent 里的陈旧标识代替判定）",
            resolverSource.contains("vaultRepository.getKdbxEntry(entryId)")
        )
    }

    @Test
    fun `生物识别副标题必须显示当前网站`() {
        // 已信任调用方走系统认证弹窗（无法渲染归属块），副标题是用户分辨「向哪个网站交凭据」的唯一锚点
        assertTrue(
            "副标题必须并入归属校验后的目标域（ISSUE-P2-617：此前只显示包名）",
            confirmSource.contains("R.string.autofill_confirm_caller_domain")
        )
        assertTrue(
            "无域时必须如实标注纯应用表单（不得留空让用户误以为是网站填充）",
            confirmSource.contains("R.string.autofill_confirm_domain_none")
        )
    }

    @Test
    fun `两处记忆写入点必须绑定域`() {
        assertTrue(
            "确认页记忆写入必须绑定归属校验后的域（EXTRA_GRANT_DOMAIN，非表单自报）",
            confirmSource.contains("autofillCallerEntryMemory.remember(it.packageName, it.certSha256Hex, entryId, it.webDomain)")
        )
        assertTrue(
            "选择器记忆写入必须绑定表单域",
            pickerSource.contains("autofillCallerEntryMemory.remember(callingPackage, callerDigests, entryId, formDomain)")
        )
    }

    @Test
    fun `候选排序的记忆档在浏览器上下文必须过域匹配`() {
        assertTrue(
            "记忆档不得再无条件入选（ISSUE-P2-617 第二道门）",
            rankerSource.contains("entryMatchesWebDomain(entry, rememberedUrl, normalizedDomain)")
        )
    }

    private fun readSource(path: String): String {
        val file = File(repositoryRoot, path)
        assertTrue("源码文件不存在（是否被重命名或移动）：$path", file.isFile)
        return file.readText()
    }

    private companion object {
        const val CONFIRM_ACTIVITY_PATH =
            "app/src/main/java/com/keepasskey/app/autofill/AutofillConfirmActivity.kt"
        const val PICKER_ACTIVITY_PATH =
            "app/src/main/java/com/keepasskey/app/autofill/AutofillPickerActivity.kt"
        const val RANKER_PATH =
            "app/src/main/java/com/keepasskey/app/autofill/AutofillCandidateRanker.kt"
        const val RESOLVER_PATH =
            "app/src/main/java/com/keepasskey/app/autofill/AutofillConfirmAuthResolver.kt"

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
