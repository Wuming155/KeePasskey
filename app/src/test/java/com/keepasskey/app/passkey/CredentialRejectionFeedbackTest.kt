package com.keepasskey.app.passkey

import com.keepasskey.app.security.CallerCertDigests
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * ISSUE-P2-220 / ENG-20261028-12：**凭据链路 fail-closed 拒绝原因与补救动作的可观测行为及枚举/资源契约**。
 *
 * 缺陷背景：各拒绝分支统一静默 `failAndFinish()` 回传 `RESULT_CANCELED`，用户视角是
 * 「点了继续就断」，无法区分「功能坏了」与「被安全门控拒绝」。
 *
 * 契约结构与分工（ENG-20261028-12 行为化整改）：
 * 1. **注册门禁判定**（[PasskeyRegistrationGate]）：输入输出行为全表覆盖（浏览器豁免、调用方未知、
 *    显式跳过 DAL、证书摘要不可读、DAL 校验结论投影）；
 * 2. **文案与枚举契约**：拒绝原因 ↔ 字符串资源一一对应且中英齐备；拒绝文案一律为**无插值固定文本**
 *    （ISSUE-P1-10 敏感标识防外泄静态门禁，严禁携带 rpId / 包名 / 域名等）；
 * 3. **就地补救动作与资格**：[CredentialRejectionAction] 枚举单射与 [CallerSignatureRemedyPolicy]
 *    资格判定严格互斥；补救文案插值受限；
 * 4. **Activity 链路与系统取消契约替代**：原先对 Activity 内部私有 `rejectAndFinish` 路由与
 *    `RESULT_CANCELED` 文本锁定断言已退役，由 [PasskeyCallerSignatureDeviceTest]（7 项真实 UI 路径）
 *    与 [CredentialChannelSwitchDeviceTest]（11 项系统时序与挂起取消）在真机/模拟器端到端证实。
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

    // ── ② 文案与资源选择 ──────────────────────────────────────────

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

    // ── ③ 就地补救（窗口内授权、无跳转） ───────────────────────────

    @Test
    fun `仅确有用户可执行解法的原因才给出补救动作`() {
        for (reason in CredentialRejectionReason.entries) {
            val expected = reason == CredentialRejectionReason.DAL_UNVERIFIED ||
                reason == CredentialRejectionReason.DAL_NETWORK_UNAVAILABLE ||
                reason == CredentialRejectionReason.CALLER_BROWSER_NOT_TRUSTED ||
                reason == CredentialRejectionReason.CALLER_SIGNATURE_UNBOUND
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
        assertEquals(
            "断言链路同源（ISSUE-P2-530）：非白名单浏览器上「列得出、签不出」必须给出同一条补救",
            CredentialRejectionAction.ADD_PRIVILEGED_BROWSER,
            CredentialRejectionAction.forReason(CredentialRejectionReason.CALLER_BROWSER_NOT_TRUSTED)
        )
        assertEquals(
            "未登记签名的原生调用方只能就地登记签名，不得给出「跳过」类捷径",
            CredentialRejectionAction.AUTHORIZE_CALLER_SIGNATURE,
            CredentialRejectionAction.forReason(CredentialRejectionReason.CALLER_SIGNATURE_UNBOUND)
        )
        assertEquals(
            "已登记而签名不符时不得提供任何补救动作（就地重绑等于让顶替者自证身份）",
            null,
            CredentialRejectionAction.forReason(CredentialRejectionReason.CALLER_SIGNATURE_MISMATCH)
        )
    }

    @Test
    fun `补救动作不得提供削弱防线的入口`() {
        assertEquals(
            "动作枚举应恰好是「加入浏览器名单」与「登记调用方签名」两项",
            setOf(
                CredentialRejectionAction.ADD_PRIVILEGED_BROWSER,
                CredentialRejectionAction.AUTHORIZE_CALLER_SIGNATURE
            ),
            CredentialRejectionAction.entries.toSet()
        )
        assertEquals(
            "两个动作各引用一条资源",
            2,
            actionResourceNames().distinct().size
        )
        assertTrue(
            "动作资源不得出现「跳过」类条目——把关闭 DAL / 签名校验做成一步直达就是给削弱核心防线的捷径",
            actionResourceNames().none { it.contains("skip") }
        )
    }

    @Test
    fun `两种就地补救各接自己的动作且资格严格互斥`() {
        // 1. 动作映射严格互斥，不得互相串用
        assertEquals(
            "浏览器补救原因必须映射到 ADD_PRIVILEGED_BROWSER",
            CredentialRejectionAction.ADD_PRIVILEGED_BROWSER,
            CredentialRejectionAction.forReason(CredentialRejectionReason.CALLER_BROWSER_NOT_TRUSTED)
        )
        assertEquals(
            "DAL 声明未通过必须映射到 ADD_PRIVILEGED_BROWSER",
            CredentialRejectionAction.ADD_PRIVILEGED_BROWSER,
            CredentialRejectionAction.forReason(CredentialRejectionReason.DAL_UNVERIFIED)
        )
        assertEquals(
            "DAL 网络不可用必须映射到 ADD_PRIVILEGED_BROWSER",
            CredentialRejectionAction.ADD_PRIVILEGED_BROWSER,
            CredentialRejectionAction.forReason(CredentialRejectionReason.DAL_NETWORK_UNAVAILABLE)
        )
        assertEquals(
            "调用方未登记签名必须映射到 AUTHORIZE_CALLER_SIGNATURE",
            CredentialRejectionAction.AUTHORIZE_CALLER_SIGNATURE,
            CredentialRejectionAction.forReason(CredentialRejectionReason.CALLER_SIGNATURE_UNBOUND)
        )
        assertNull(
            "签名不匹配原因绝不提供补救动作（防止顶替者就地重绑自证）",
            CredentialRejectionAction.forReason(CredentialRejectionReason.CALLER_SIGNATURE_MISMATCH)
        )

        // 2. 补救资格输入输出行为：复用 CallerSignatureRemedyPolicy 判据
        assertTrue(
            "未登记签名且摘要有效时具备签名补救资格",
            CallerSignatureRemedyPolicy.eligible(
                reason = CredentialRejectionReason.CALLER_SIGNATURE_UNBOUND,
                callerPackage = PKG,
                certDigests = CallerCertDigests.ofSingle(DIGEST),
                hasAnyBinding = false
            )
        )
        assertFalse(
            "该包名已存在任一绑定时绝不具备补救资格（禁止就地重绑）",
            CallerSignatureRemedyPolicy.eligible(
                reason = CredentialRejectionReason.CALLER_SIGNATURE_UNBOUND,
                callerPackage = PKG,
                certDigests = CallerCertDigests.ofSingle(DIGEST),
                hasAnyBinding = true
            )
        )
        assertFalse(
            "签名不匹配原因绝不具备补救资格",
            CallerSignatureRemedyPolicy.eligible(
                reason = CredentialRejectionReason.CALLER_SIGNATURE_MISMATCH,
                callerPackage = PKG,
                certDigests = CallerCertDigests.ofSingle(DIGEST),
                hasAnyBinding = false
            )
        )
        assertFalse(
            "浏览器补救原因绝不具备原生签名补救资格（防动作混用）",
            CallerSignatureRemedyPolicy.eligible(
                reason = CredentialRejectionReason.CALLER_BROWSER_NOT_TRUSTED,
                callerPackage = PKG,
                certDigests = CallerCertDigests.ofSingle(DIGEST),
                hasAnyBinding = false
            )
        )
        assertFalse(
            "DAL 校验原因绝不具备原生签名补救资格",
            CallerSignatureRemedyPolicy.eligible(
                reason = CredentialRejectionReason.DAL_UNVERIFIED,
                callerPackage = PKG,
                certDigests = CallerCertDigests.ofSingle(DIGEST),
                hasAnyBinding = false
            )
        )
        assertFalse(
            "包名为空绝不具备补救资格",
            CallerSignatureRemedyPolicy.eligible(
                reason = CredentialRejectionReason.CALLER_SIGNATURE_UNBOUND,
                callerPackage = null,
                certDigests = CallerCertDigests.ofSingle(DIGEST),
                hasAnyBinding = false
            )
        )
        assertFalse(
            "证书摘要为空绝不具备补救资格",
            CallerSignatureRemedyPolicy.eligible(
                reason = CredentialRejectionReason.CALLER_SIGNATURE_UNBOUND,
                callerPackage = PKG,
                certDigests = CallerCertDigests.EMPTY,
                hasAnyBinding = false
            )
        )
    }

    @Test
    fun `补救资源中英齐备且插值只承载应用名`() {
        val zh = readSource(ZH_PASSKEY_STRINGS) + readSource(ZH_STRINGS)
        val en = readSource(EN_STRINGS)

        // 无插值资源：退出 / 动作按钮 / 失败反馈 / 原生调用方登记（不点名调用方）
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
            .findAll(readSource(ACTION_ENUM_PATH))
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

        /** 无插值补救资源：退出 / 动作按钮 / 失败反馈 / 原生调用方登记（不点名调用方） */
        val FIXED_REMEDY_RESOURCES = listOf(
            "cred_reject_exit",
            "cred_reject_action_add_browser",
            "cred_reject_browser_add_failed",
            "cred_reject_action_authorize_caller",
            "cred_reject_caller_unbound",
            "cred_reject_caller_authorized",
            "cred_reject_caller_authorize_failed"
        )

        /** 含 %1$s（应用展示名）的说明句资源 */
        val NAMED_REMEDY_RESOURCES = listOf(
            "cred_reject_browser_not_allowed",
            "cred_reject_browser_added"
        )
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
