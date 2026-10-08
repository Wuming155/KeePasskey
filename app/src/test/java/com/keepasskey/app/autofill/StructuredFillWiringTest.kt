package com.keepasskey.app.autofill

import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * 结构化数据填充的接线守卫（ISSUE-P3-375 AC③④ + 安全面，静态源码比对）。
 *
 * 识别 / 候选 / 取值三段的**行为**由 `StructuredFieldPolicyTest` 穷举；本守卫锁定跨文件接线：
 * 1. 解析编排合入识别结果、存在性与屏蔽两道闸门的结构化分支；
 * 2. 服务主链路的锁库分支与选择器门（纯结构化不挂 U/P 兜底）；
 * 3. 确认页结构化 extras 全链（下发 → 读取 → 取值 → 回传数据集）且恒挂认证；
 * 4. 选择器标注与文案资源成对。
 */
class StructuredFillWiringTest {

    private fun readSource(path: String): String {
        var dir: File? = File(System.getProperty("user.dir").orEmpty()).absoluteFile
        repeat(6) {
            val candidate = dir ?: return@repeat
            val file = File(candidate, path)
            if (file.isFile) return file.readText()
            dir = candidate.parentFile
        }
        error("源码缺失：$path")
    }

    @Test
    fun `解析编排合入识别且两道闸门带结构化分支`() {
        val resolver = readSource("app/src/main/java/com/keepasskey/app/autofill/AutofillTargetFieldResolver.kt")
        assertTrue(
            "必须调用 detectFillableTargets 合入 ScanResult",
            resolver.contains("StructuredFieldPolicy.detectFillableTargets(")
        )
        assertTrue(
            "存在性闸门必须含结构化目标（纯结构化表单不得被早退）",
            resolver.contains("structuredParsed.isEmpty()) return null")
        )
        assertTrue(
            "字段级屏蔽的整表拒绝必须只在无结构化目标时生效",
            resolver.contains("if (fieldDecision.blocksEntireForm && structuredParsed.isEmpty())")
        )
        assertTrue(
            "TargetFields 必须携带结构化目标 id 映射",
            resolver.contains("structuredTargetIds = structuredParsed")
        )
    }

    @Test
    fun `服务主链路锁库分支与选择器门正确`() {
        val service = readSource("app/src/main/java/com/keepasskey/app/autofill/KeePasskeyAutofillService.kt")
        assertTrue(
            "纯结构化表单锁库态必须返回空响应（解锁链交付面为 U/P）",
            service.contains("纯结构化表单在库锁定态暂不下发")
        )
        assertTrue(
            "deliverUnlockedResponse 必须下传结构化目标",
            service.contains("structuredTargetIds = structuredTargetIds")
        )
        assertTrue(
            "选择器兜底必须在有 U/P 目标时才挂（纯结构化不挂）",
            service.contains("if (usernameId != null || passwordId != null)")
        )
    }

    @Test
    fun `确认 Intent 全链携带结构化目标且恒挂认证`() {
        val builders = readSource("app/src/main/java/com/keepasskey/app/autofill/AutofillDatasetBuilders.kt")
        assertTrue(
            "confirmIntent 必须携带角色名列表",
            builders.contains("EXTRA_TARGET_STRUCTURED_ROLES")
        )
        assertTrue(
            "confirmIntent 必须携带框 id 列表",
            builders.contains("EXTRA_TARGET_STRUCTURED_IDS")
        )
        assertTrue(
            "必须装配结构化数据集",
            builders.contains("appendStructuredDatasets(responseBuilder, context, structuredTargetIds)")
        )
        // 恒挂认证的断言指向结构化装配文件（本批自 DatasetBuilders 拆出）
        val structuredSource =
            readSource("app/src/main/java/com/keepasskey/app/autofill/AutofillStructuredDatasets.kt")
        val structuredBlock = structuredSource
            .substringAfter("internal suspend fun KeePasskeyAutofillService.appendStructuredDatasets")
        assertTrue(
            "结构化数据集必须挂确认认证（恒认证，不走免确认路径）",
            structuredBlock.contains("attachConfirmationAuth(")
        )

        val confirm = readSource("app/src/main/java/com/keepasskey/app/autofill/AutofillConfirmActivity.kt")
        assertTrue(
            "确认页必须读取结构化目标",
            confirm.contains("readStructuredTargetsFrom(intent)")
        )
        assertTrue(
            "确认页存在性闸门必须含结构化分支",
            confirm.contains("structured.isEmpty()) return null")
        )
        assertTrue(
            "确认页必须按条目取回结构化值并下传",
            confirm.contains("structuredFields = structuredValues")
        )

        val delivery = readSource("app/src/main/java/com/keepasskey/app/autofill/AutofillAuthResultDelivery.kt")
        assertTrue(
            "回传数据集构造必须逐框写入结构化字段",
            delivery.contains("structuredFields: Map<AutofillId, String> = emptyMap()")
        )
    }

    @Test
    fun `结构树投影 html autocomplete 且选择器带标注`() {
        val structureScan = readSource("app/src/main/java/com/keepasskey/app/autofill/AutofillStructureScan.kt")
        assertTrue(
            "结构树必须提取 HTML autocomplete（识别源之二）",
            structureScan.contains("htmlAutocomplete(node)")
        )
        assertTrue(
            "autocomplete 必须投影进 ScanNode",
            structureScan.contains("autocomplete = node.autocomplete")
        )

        val screen = readSource("app/src/main/java/com/keepasskey/app/autofill/AutofillPickerScreen.kt")
        assertTrue(
            "选择器必须对结构化条目明确标注（AC④）",
            screen.contains("StructuredFieldPolicy.hasAnyStructuredData(entry)")
        )
        assertTrue(
            "标注必须引用文案资源",
            screen.contains("R.string.structured_entry_badge")
        )
    }

    @Test
    fun `结构化文案中英成对且零插值`() {
        listOf("values/strings.xml", "values-en/strings.xml").forEach { path ->
            val source = readSource("app/src/main/res/$path")
            listOf("structured_dataset_subtitle", "structured_entry_badge").forEach { key ->
                val value = Regex("<string name=\"$key\">(.*?)</string>").find(source)
                    ?.groupValues?.get(1)
                assertTrue("缺少 $key（$path）", value != null)
                assertTrue(
                    "$key 不得携带插值（零敏感插值口径）",
                    !value.orEmpty().contains("%")
                )
            }
        }
    }
}
