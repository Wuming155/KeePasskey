package com.keepasskey.app.passkey

import com.keepasskey.app.testutil.stripCommentsOnly
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * 「无匹配 → 新建密码条目」Action 的判据与接线回归锁（`ISSUE-P3-345` / `PD-51` 裁决 4）。
 *
 * ## 分层（体例同 `ClipboardSecurityManagerScheduledClearTest`）
 *
 * 1. **行为层**（[shouldOfferPasswordCreateAction] 纯函数）：四条门控
 *    「有无口令请求 / 有无候选 / 只读会话」的排列穷举。场景以**表格驱动**
 *    （实参取自容器迭代，非就地字面量），防「永远为真的断言」机检（`ISSUE-P3-305` 体例）误伤。
 * 2. **文案层**：Action 标题字符串资源**零插值**（`PD-51` 裁决 3，对齐 `ISSUE-P1-10` /
 *    `PD-12`：调用方标识不得进按钮）——宿主 JVM 无法解析资源，故直接读资源文件断言。
 * 3. **接线层**（源码守卫）：装配器唯一候选出口必须**经**该纯函数裁决并消费
 *    「逐 option 计数的候选数」，不得自行内联；Action 的 PendingIntent 必须走
 *    [CredentialPendingIntents] 契约（`FLAG_MUTABLE` + 非 ONE_SHOT + 进程级唯一 requestCode）。
 *
 * ⚠ 端到端呈现（系统选择器是否渲染 Actions 类目、`RESULT_CANCELED` 是否重弹选择器）
 * 属系统 UI 行为，宿主无法证伪 ⇒ 由 `ISSUE-P3-345` AC②③ 的真机读数承担，本用例不覆盖。
 */
class CredentialCreateActionPolicyTest {

    // ---------------------------------------------------------------------------------------
    // 行为层：shouldOfferPasswordCreateAction
    // ---------------------------------------------------------------------------------------

    private data class Scenario(
        val label: String,
        val sawPasswordOption: Boolean,
        val passwordCandidateCount: Int,
        val sessionReadOnly: Boolean,
        val offerCreateEntryEnabled: Boolean,
        val expected: Boolean
    )

    private val scenarios = listOf(
        // 命中：口令请求 + 零候选 + 会话可写 + 新建入口开 ⇒ 挂 Action
        Scenario("口令请求·零候选·可写·入口开", true, 0, false, true, true),
        // 有候选 ⇒ 不挂（用户已有可用凭据，Action 是噪声）
        Scenario("口令请求·有候选", true, 1, false, true, false),
        Scenario("口令请求·多候选", true, 3, false, true, false),
        // 只读会话 ⇒ 不挂（PD-51 裁决 4：新建不了就不呈现，控件不许骗人）
        Scenario("口令请求·零候选·只读", true, 0, true, true, false),
        // 无口令请求（仅公钥 / 自定义选项）⇒ 永不挂（PD-51 裁决 1：本应用造不出有效通行密钥）
        Scenario("仅公钥请求·零候选", false, 0, false, true, false),
        Scenario("仅公钥请求·只读", false, 0, true, true, false),
        // ISSUE-P3-376 第五门控：设置页关闭「无匹配时就地新建」⇒ 即便其余全满足也不挂
        Scenario("口令请求·零候选·可写·入口关", true, 0, false, false, false)
    )

    @Test
    fun `挂出判定穷举——口令请求零候选且会话可写且入口开关开启才挂`() {
        val failures = scenarios.filter { scenario ->
            shouldOfferPasswordCreateAction(
                sawPasswordOption = scenario.sawPasswordOption,
                passwordCandidateCount = scenario.passwordCandidateCount,
                sessionReadOnly = scenario.sessionReadOnly,
                offerCreateEntryEnabled = scenario.offerCreateEntryEnabled
            ) != scenario.expected
        }
        assertEquals(
            "判据与场景表不一致：" + failures.joinToString { it.label },
            emptyList<Scenario>(),
            failures
        )
    }

    // ---------------------------------------------------------------------------------------
    // 文案层：Action 标题零插值
    // ---------------------------------------------------------------------------------------

    @Test
    fun `Action 标题与草稿页文案不得携带插值占位`() {
        val zh = File("src/main/res/values/strings.xml").readText()
        val en = File("src/main/res/values-en/strings.xml").readText()
        listOf(
            "cred_action_create_password_title" to zh,
            "cred_action_create_password_title" to en,
            "autofill_picker_create_new" to zh,
            "autofill_picker_create_new" to en
        ).forEach { (name, source) ->
            val value = Regex("<string name=\"$name\">(.*?)</string>").find(source)
                ?.groupValues?.get(1)
            assertTrue("缺少字符串资源 $name", value != null)
            // 位置占位（%1$s 等）即插值形态——按钮文案零插值（PD-51 裁决 3）
            assertFalse(
                "按钮文案 $name 不得携带插值占位，当前值：$value",
                value.orEmpty().contains("%")
            )
        }
        // 归属展示允许插值（表单内只读绑定行，PD-51 裁决 3 允许面），锁定其存在以防漂移
        listOf("values/strings.xml" to "zh", "values-en/strings.xml" to "en").forEach { (path, tag) ->
            val source = File("src/main/res/$path").readText()
            assertTrue(
                "缺少草稿页归属展示资源（$tag）",
                source.contains("name=\"cred_draft_binding_label\"")
            )
        }
    }

    // ---------------------------------------------------------------------------------------
    // 接线层：装配器必须经纯函数裁决，Action 的 PendingIntent 必须走集中契约
    // ---------------------------------------------------------------------------------------

    @Test
    fun `装配器唯一候选出口必须经纯函数裁决并消费逐 option 计数`() {
        val source = stripCommentsOnly(
            File("src/main/java/com/keepasskey/app/passkey/CredentialResponseAssembler.kt").readText()
        )
        // 逐 option 计数（防止实现改成「只看最后一个 option」或「只看公钥计数」）
        assertTrue(
            "口令候选必须逐 option 累加计数",
            source.contains("passwordCandidateCount += buildPasswordEntries(")
        )
        assertTrue(
            "口令请求出现标记缺失",
            source.contains("sawPasswordOption = true")
        )
        // 挂出必须经纯函数（不得内联回判定条件，否则行为层穷举被绕过）
        assertTrue(
            "Action 挂出必须经 shouldOfferPasswordCreateAction 裁决",
            source.contains("shouldOfferPasswordCreateAction(")
        )
        assertTrue(
            "挂出处必须传入只读会话门控",
            source.contains("sessionReadOnly = vaultRepository.isSessionReadOnly()")
        )
        assertTrue(
            "挂出处必须传入新建入口开关门控（ISSUE-P3-376 第五门控）",
            source.contains("offerCreateEntryEnabled = settingsStore.isAutofillOfferCreateEntryEnabled()")
        )
        assertTrue(
            "必须以 addAction 挂出（不得改走 addCredentialEntry 伪装候选）",
            source.contains("responseBuilder.addAction(")
        )
    }

    @Test
    fun `Action 与候选条目共用 PendingIntent 集中契约`() {
        val assembler = stripCommentsOnly(
            File("src/main/java/com/keepasskey/app/passkey/CredentialResponseAssembler.kt").readText()
        )
        // 挂出必须经同族创建入口装配对象（与 passkeyEntry / passwordEntry 同落点）
        assertTrue(
            "Action 构造必须下沉到 CredentialCreateEntries.createPasswordAction",
            assembler.contains("CredentialCreateEntries.createPasswordAction(")
        )
        val entries = stripCommentsOnly(
            File("src/main/java/com/keepasskey/app/passkey/CredentialCreateEntries.kt").readText()
        )
        val actionBlock = entries.substringAfter("fun createPasswordAction")
        assertTrue("缺少 createPasswordAction 实现", actionBlock != entries)
        assertTrue(
            "Action PendingIntent 必须使用 CredentialPendingIntents.ENTRY_FLAGS",
            actionBlock.contains("CredentialPendingIntents.ENTRY_FLAGS")
        )
        assertTrue(
            "Action requestCode 必须取自进程级单调分配器",
            actionBlock.contains("CredentialPendingIntents.nextRequestCode()")
        )
        assertTrue(
            "Action 落地页必须是 PasswordDraftActivity",
            actionBlock.contains("PasswordDraftActivity::class.java")
        )
    }
}
