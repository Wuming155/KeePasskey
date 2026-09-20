package com.keepasskey.app.passkey

import com.keepasskey.app.security.CallerCertDigests
import com.keepasskey.app.testutil.stripCommentsOnly
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * ISSUE-P2-220 回归：**创建链路 fail-closed 拒绝必须有用户可见的原因**。
 *
 * 缺陷形态：各拒绝分支（缺系统注入请求 / 缺注册参数 / DAL 未通过 / `excludeCredentials`
 * 命中 / 锁定态复核失败）统一静默 `failAndFinish()`——只回传 `RESULT_CANCELED` 就结束，
 * 任何界面都不呈现。用户视角是「点了继续就断」，无法区分「功能坏了」与「被安全门控拒绝」。
 *
 * 本用例分三层：
 * 1. **注册门禁判定**（[PasskeyRegistrationGate]，纯逻辑 + 注入式 DAL）——各分支必须给出
 *    明确原因，且「浏览器豁免」「跳过 DAL」不得误触发 DAL 请求；
 * 2. **文案选择**——原因 ↔ 字符串资源一一对应，且文案一律为**无插值**固定文本
 *    （ISSUE-P1-10：不得携带 rpId / 包名 / 域名等敏感标识）；
 * 3. **接线与回传契约**（静态源码断言，对齐既有 `*WiringTest` 先例）——每个 fail-closed
 *    分支都路由到 `rejectAndFinish(...)`，唯一保留的静默收尾只允许出现在「用户主动取消
 *    用户验证」这一非 fail-closed 路径上；收尾仍是 `RESULT_CANCELED`。
 */
class CredentialRejectionFeedbackTest {

    // ── ① 注册门禁：各分支的原因投影 ─────────────────────────────────

    @Test
    fun `浏览器委派调用豁免 DAL 且不发起校验请求`() = runTest {
        val reason = evaluate(origin = "https://example.com", callerPackage = null)

        assertNull("浏览器路径已由 DomainMatcher 强制 rp.id ↔ origin 归属，无需 DAL", reason)
        assertEquals("浏览器路径不得再发起 DAL 网络校验", 0, dalCalls)
    }

    @Test
    fun `取不到系统背书的调用包名时给出调用方未知原因`() = runTest {
        val reason = evaluate(callerPackage = null)

        assertEquals(CredentialRejectionReason.CALLER_UNKNOWN, reason)
        assertEquals("包名不可得即 fail-closed，不得再请求 DAL", 0, dalCalls)
    }

    @Test
    fun `用户显式跳过 DAL 校验时放行且不发起校验请求`() = runTest {
        val reason = evaluate(skipDalVerification = true, dalResult = NOT_VERIFIED)

        assertNull("跳过 DAL 是用户显式取舍（削弱防线），本门禁必须放行", reason)
        assertEquals("已跳过即不得再发起 DAL 网络校验", 0, dalCalls)
    }

    @Test
    fun `签名摘要不可读时给出证书不可读原因`() = runTest {
        assertEquals(
            CredentialRejectionReason.CALLER_CERT_UNREADABLE,
            evaluate(callingAppInfoPresent = false)
        )
        assertEquals(
            CredentialRejectionReason.CALLER_CERT_UNREADABLE,
            evaluate(certDigests = CallerCertDigests.EMPTY)
        )
        assertEquals("摘要不可读时 DAL 无从执行，不得发起请求", 0, dalCalls)
    }

    @Test
    fun `DAL 结论按 fail-closed 语义投影为原因`() = runTest {
        assertEquals(
            CredentialRejectionReason.DAL_UNVERIFIED,
            evaluate(dalResult = DigitalAssetLinksVerifier.DalResult.NOT_VERIFIED)
        )
        assertEquals(
            "网络不可用必须与「声明未通过」区分（用户可对症重试）",
            CredentialRejectionReason.DAL_NETWORK_UNAVAILABLE,
            evaluate(dalResult = DigitalAssetLinksVerifier.DalResult.NETWORK_UNAVAILABLE)
        )
        assertNull(
            "DAL 通过即放行",
            evaluate(dalResult = DigitalAssetLinksVerifier.DalResult.VERIFIED)
        )
    }

    @Test
    fun `DAL 校验只接受非空调用包名`() = runTest {
        evaluate(dalResult = DigitalAssetLinksVerifier.DalResult.VERIFIED)

        assertEquals(1, dalCalls)
        assertEquals("门禁必须把系统背书的包名原样交给 DAL", PKG, dalPackageSeen)
    }

    @Test
    fun `DAL 三种结论的原因映射为穷举单射`() {
        assertNull(CredentialRejectionReason.fromDalResult(DigitalAssetLinksVerifier.DalResult.VERIFIED))
        assertEquals(
            CredentialRejectionReason.DAL_UNVERIFIED,
            CredentialRejectionReason.fromDalResult(DigitalAssetLinksVerifier.DalResult.NOT_VERIFIED)
        )
        assertEquals(
            CredentialRejectionReason.DAL_NETWORK_UNAVAILABLE,
            CredentialRejectionReason.fromDalResult(
                DigitalAssetLinksVerifier.DalResult.NETWORK_UNAVAILABLE
            )
        )
    }

    // ── ② 文案选择 ────────────────────────────────────────────────

    @Test
    fun `每个拒绝原因都映射到互不重复的字符串资源`() {
        val reasons = CredentialRejectionReason.entries
        val resIds = reasons.map { it.messageRes }

        assertTrue("拒绝原因不得映射到占位资源", resIds.none { it == 0 })
        assertEquals(
            "每个拒绝原因必须有各自的文案（不得复用同一句糊弄用户）",
            reasons.size,
            resIds.toSet().size
        )
        assertEquals(
            "拒绝原因 ↔ 资源名必须一一对应（无未登记条目）",
            reasons.size,
            enumResourceNames().size
        )
    }

    @Test
    fun `拒绝文案一律为无插值固定文本（不得携带 rpId 包名域名等标识）`() {
        val zh = readSource(ZH_PASSKEY_STRINGS) + readSource(ZH_STRINGS)
        val en = readSource(EN_STRINGS)

        for ((reason, resName) in enumResourceNames()) {
            val zhBody = stringBody(zh, resName)
                ?: error("中文资源缺失：$reason → $resName（ISSUE-P1-10 要求预定义文案）")
            assertFalse(
                "拒绝文案 $resName 含格式占位符：那正是承载 rpId / 包名等敏感标识的唯一通道",
                zhBody.contains("%")
            )
            val enBody = stringBody(en, resName)
                ?: error("英文资源缺失：$reason → $resName（values-en 须同步，避免 MissingTranslation）")
            assertFalse("英文拒绝文案 $resName 含格式占位符", enBody.contains("%"))
        }
    }

    // ── ③ 接线与回传契约 ──────────────────────────────────────────

    @Test
    fun `创建链路各 fail-closed 分支都路由到明确原因`() {
        val source = readCode(CREATE_ACTIVITY_PATH)

        for (routing in listOf(
            "rejectAndFinish(CredentialRejectionReason.MISSING_REQUEST)",
            "rejectAndFinish(CredentialRejectionReason.MISSING_PARAMETERS)",
            "rejectAndFinish(CredentialRejectionReason.VAULT_LOCKED)",
            "rejectAndFinish(CredentialRejectionReason.CREDENTIAL_ALREADY_EXISTS)",
            "rejectAndFinish(CredentialRejectionReason.INTERNAL_ERROR)"
        )) {
            assertTrue("创建链路缺少原因呈现：$routing（AC① 要求逐分支呈现原因）", source.contains(routing))
        }
        assertTrue(
            "门禁（DAL / 归属 / 调用方）结论必须原样作为原因呈现",
            source.contains("rejectAndFinish(gateRejection, remedy)")
        )
        assertFalse(
            "旧的布尔门禁（无原因）必须已被 PasskeyRegistrationGate 取代",
            source.contains("fun passesRegistrationGates(")
        )
        assertTrue(
            "门禁判定必须经 PasskeyRegistrationGate（原因与判定同源）",
            source.contains("PasskeyRegistrationGate.evaluate(")
        )
    }

    @Test
    fun `除用户主动取消用户验证外不得再有静默收尾`() {
        val source = readCode(CREATE_ACTIVITY_PATH)
        val silent = Regex("""failAndFinish\(\)""").findAll(source).count()

        assertEquals(
            "仅「用户验证未通过」这一非 fail-closed 路径允许静默收尾，其余分支必须呈现原因",
            1,
            silent
        )
        assertTrue(
            "唯一允许的静默收尾必须落在 onRejected（用户主动取消 / 验证失败）内",
            source.substringAfter("onRejected = {").substringBefore("}").contains("failAndFinish()")
        )
    }

    @Test
    fun `拒绝呈现后再以 RESULT_CANCELED 收尾（对系统契约不变）`() {
        val base = readCode(BASE_ACTIVITY_PATH)

        assertTrue(
            "基类必须提供带原因的拒绝收尾入口",
            base.contains("fun rejectAndFinish(") && base.contains("reason: CredentialRejectionReason")
        )
        assertTrue(
            "拒绝页必须由预定义资源取文案（不得拼接 rpId / 包名）",
            base.contains("getString(reason.messageRes)")
        )
        assertTrue(
            "用户确认与页面销毁走同一收尾（仍 RESULT_CANCELED）",
            base.contains("onConfirm = { settleRejection() }")
        )
        val failAndFinishBody = base.substringAfter("protected fun failAndFinish()").substringBefore("}")
        assertTrue(
            "失败收尾必须回传 RESULT_CANCELED（呈现原因不得改变对系统的契约）",
            failAndFinishBody.contains("setResult(RESULT_CANCELED)")
        )
    }

    // ── ④ ISSUE-P3-221：就地补救（窗口内授权、无跳转） ─────────────

    @Test
    fun `仅确有用户可执行解法的原因才给出补救动作`() {
        for (reason in CredentialRejectionReason.entries) {
            val expected = reason == CredentialRejectionReason.DAL_UNVERIFIED ||
                reason == CredentialRejectionReason.DAL_NETWORK_UNAVAILABLE
            assertEquals(
                "「$reason」的补救动作判定与预期不符——不得为无从下手的原因硬凑一个动作",
                expected,
                CredentialRejectionAction.forReason(reason) != null
            )
        }
        assertEquals(
            "网络不可用与声明未通过同源（非白名单浏览器走普通应用 DAL 分支），授权对两者都是正解",
            CredentialRejectionAction.ADD_PRIVILEGED_BROWSER,
            CredentialRejectionAction.forReason(CredentialRejectionReason.DAL_NETWORK_UNAVAILABLE)
        )
    }

    @Test
    fun `补救动作唯一且不提供削弱防线的入口`() {
        assertEquals(
            "动作枚举应恰好只有「加入特权名单」一项",
            listOf(CredentialRejectionAction.ADD_PRIVILEGED_BROWSER),
            CredentialRejectionAction.entries
        )
        assertEquals(
            "动作按钮应恰好引用一条资源",
            1,
            actionResourceNames().distinct().size
        )
        assertTrue(
            "动作资源不得出现「跳过」类条目——把关闭 DAL 校验做成一步直达就是给削弱核心防线的捷径",
            actionResourceNames().none { it.contains("skip") }
        )
    }

    @Test
    fun `就地授权必须接上浏览器资格判定与 fail-closed 写入`() {
        val activity = readCode(CREATE_ACTIVITY_PATH)
        val builder = readCode(BROWSER_BUILDER_PATH)

        assertTrue(
            "门禁拒绝后必须经 BrowserRemedyBuilder 判定补救资格",
            activity.contains("BrowserRemedyBuilder.build(")
        )
        assertTrue(
            "授权必须写特权浏览器白名单",
            builder.contains("store.setEnabled(")
        )
        assertTrue(
            "写入属于包管理/偏好面，须下沉 Default（不得在主线程裸跑）",
            builder.contains("Dispatchers.Default")
        )
        assertTrue(
            "Builder 必须枚举浏览器候选作资格判定（排除原生 App 这一假入口）",
            builder.contains("store.installedBrowsers()")
        )
        assertTrue(
            "Builder 必须排除已启用项（已启用再给「添加」无意义）",
            builder.contains("if (browser.enabled) return null")
        )
    }

    @Test
    fun `补救资源中英齐备且插值只承载应用名`() {
        val zh = readSource(ZH_PASSKEY_STRINGS) + readSource(ZH_STRINGS)
        val en = readSource(EN_STRINGS)

        // 无插值资源：退出 / 动作按钮 / 失败反馈——按钮不得点名任何调用方
        for (resName in FIXED_REMEDY_RESOURCES) {
            val zhBody = stringBody(zh, resName)
                ?: error("中文资源缺失：$resName")
            assertFalse("$resName 不得含格式占位符", zhBody.contains("%"))
            val enBody = stringBody(en, resName)
                ?: error("英文资源缺失：$resName")
            assertFalse("英文 $resName 不得含格式占位符", enBody.contains("%"))
        }
        // 说明句允许且必须含 %1$s（应用展示名）；不得出现第二个占位符
        val placeholderRegex = Regex("%[0-9]+\\\$s|%s")
        for (resName in NAMED_REMEDY_RESOURCES) {
            val zhBody = stringBody(zh, resName)
                ?: error("中文资源缺失：$resName")
            assertEquals("$resName 应恰好含一个 %1\$s（应用展示名）", 1,
                placeholderRegex.findAll(zhBody).count())
            val enBody = stringBody(en, resName)
                ?: error("英文资源缺失：$resName")
            assertEquals("英文 $resName 应恰好含一个 %1\$s", 1,
                placeholderRegex.findAll(enBody).count())
        }
    }

    @Test
    fun `补救全程窗口内闭环不跳转且收尾仍回传取消`() {
        val base = readCode(BASE_ACTIVITY_PATH)

        assertFalse(
            "就地补救不得拉起其它界面（无跳转是该方案的核心）",
            base.contains("startActivity(")
        )
        assertTrue(
            "用户确认与退出必须走幂等收尾",
            base.contains("onConfirm = { settleRejection() }")
        )
        val failAndFinishBody = base.substringAfter("protected fun failAndFinish()").substringBefore("}")
        assertTrue(
            "收尾必须仍是 RESULT_CANCELED（就地授权不得改变对系统契约）",
            failAndFinishBody.contains("setResult(RESULT_CANCELED)")
        )
    }

    @Test
    fun `拒绝页仅在补救存在时渲染两选择并覆盖成功失败态`() {
        val screen = readCode(REJECTION_SCREEN_PATH)

        assertTrue("必须存在授权成功态分支", screen.contains("status == STATUS_ADDED"))
        assertTrue("必须存在授权失败反馈分支", screen.contains("status == STATUS_FAILED"))
        assertTrue("无补救时不得渲染按钮或多余留白", screen.contains("remedy != null"))
    }

    // ── 辅助 ──────────────────────────────────────────────────────

    private var dalCalls = 0
    private var dalPackageSeen: String? = null

    private suspend fun evaluate(
        origin: String = APK_KEY_HASH_ORIGIN,
        callerPackage: String? = PKG,
        skipDalVerification: Boolean = false,
        callingAppInfoPresent: Boolean = true,
        certDigests: CallerCertDigests = CallerCertDigests.ofSingle(DIGEST),
        dalResult: DigitalAssetLinksVerifier.DalResult = VERIFIED
    ): CredentialRejectionReason? {
        dalCalls = 0
        dalPackageSeen = null
        return PasskeyRegistrationGate.evaluate(
            origin = origin,
            callerPackage = callerPackage,
            skipDalVerification = skipDalVerification,
            callingAppInfoPresent = callingAppInfoPresent,
            certDigests = certDigests
        ) { verifiedPackage ->
            dalCalls++
            dalPackageSeen = verifiedPackage
            dalResult
        }
    }

    /** 自枚举源文件解析「原因 → 资源名」映射（单一真相源，避免测试里手抄一遍）。 */
    private fun enumResourceNames(): Map<String, String> {
        val source = readSource(REASON_ENUM_PATH)
        return Regex("""^\s*([A-Z][A-Z0-9_]*)\(R\.string\.([a-z0-9_]+)\)""", RegexOption.MULTILINE)
            .findAll(source)
            .associate { it.groupValues[1] to it.groupValues[2] }
    }

    /** 自**动作**枚举源解析其引用的字符串资源（自动覆盖后续新增的动作，无需手抄） */
    private fun actionResourceNames(): List<String> =
        Regex("""R\.string\.([a-z0-9_]+)""")
            .findAll(readCode(ACTION_ENUM_PATH))
            .map { it.groupValues[1] }
            .toList()

    private fun stringBody(xml: String, name: String): String? =
        Regex("""<string name="$name">(.*?)</string>""", RegexOption.DOT_MATCHES_ALL)
            .find(xml)?.groupValues?.get(1)

    private fun readSource(path: String): String {
        val file = File(repositoryRoot, path)
        assertTrue("源码/资源文件不存在（是否被重命名或移动）：$path", file.isFile)
        return file.readText()
    }

    /** 剔除注释后的源码——整改说明里会写出被断言的调用形态，不剔除即会「注释里的假接线」也算通过 */
    private fun readCode(path: String): String = stripCommentsOnly(readSource(path))

    private companion object {
        const val PKG = "com.example.app"
        val DIGEST = "AA".repeat(32)
        const val APK_KEY_HASH_ORIGIN = "android:apk-key-hash:AAAA"
        val VERIFIED = DigitalAssetLinksVerifier.DalResult.VERIFIED
        val NOT_VERIFIED = DigitalAssetLinksVerifier.DalResult.NOT_VERIFIED

        const val REASON_ENUM_PATH =
            "app/src/main/java/com/keepasskey/app/passkey/CredentialRejectionReason.kt"
        const val ACTION_ENUM_PATH =
            "app/src/main/java/com/keepasskey/app/passkey/CredentialRejectionAction.kt"
        const val REJECTION_SCREEN_PATH =
            "app/src/main/java/com/keepasskey/app/passkey/CredentialRejectionScreen.kt"
        const val BROWSER_BUILDER_PATH =
            "app/src/main/java/com/keepasskey/app/passkey/BrowserRemedyBuilder.kt"

        /** 无插值补救资源：退出 / 动作按钮 / 失败反馈 */
        val FIXED_REMEDY_RESOURCES = listOf(
            "cred_reject_exit",
            "cred_reject_action_add_browser",
            "cred_reject_browser_add_failed"
        )

        /** 含 %1$s（应用展示名）的说明句资源 */
        val NAMED_REMEDY_RESOURCES = listOf(
            "cred_reject_browser_not_allowed",
            "cred_reject_browser_added"
        )
        const val CREATE_ACTIVITY_PATH =
            "app/src/main/java/com/keepasskey/app/passkey/PasskeyCreateActivity.kt"
        const val BASE_ACTIVITY_PATH =
            "app/src/main/java/com/keepasskey/app/passkey/BaseCredentialActivity.kt"
        const val ZH_PASSKEY_STRINGS = "app/src/main/res/values/strings_sync_passkey.xml"
        const val ZH_STRINGS = "app/src/main/res/values/strings.xml"
        const val EN_STRINGS = "app/src/main/res/values-en/strings.xml"
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