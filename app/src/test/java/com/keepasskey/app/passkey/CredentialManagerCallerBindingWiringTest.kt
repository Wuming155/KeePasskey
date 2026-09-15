package com.keepasskey.app.passkey

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * CM 通道调用方绑定与调用方归属交叉核对的**接线守卫**（ISSUE-P2-83 / ISSUE-P3-111）。
 *
 * 断言对象是源码文本而非运行时行为，理由与仓库既有接线守卫一致：这些是
 * 「某个调用点是否真的接上了某个门控」的**结构性**约束，运行时用例无法覆盖
 * （没有它就只是「代码里少了一行」，不会有任何测试变红）。
 */
class CredentialManagerCallerBindingWiringTest {

    // ── ISSUE-P2-83 AC①：两条通道的信任存储必须互不背书 ──────────────

    @Test
    fun `CM 与自动填充使用不同的信任存储文件`() {
        val cmPrefs = declaredPrefsName(readSource(CM_TRUST_STORE))
        val autofillPrefs = declaredPrefsName(readSource(AUTOFILL_TRUST_STORE))

        assertEquals("CM 通道存储文件常量缺失", CM_PREFS_NAME, cmPrefs)
        assertEquals("自动填充通道存储文件常量缺失", AUTOFILL_PREFS_NAME, autofillPrefs)
        assertNotEquals(
            "两条通道不得共用信任文件——否则一次 CM 授权会让自动填充侧首现闸门静默不再询问（削弱 ISSUE-P1-24）",
            autofillPrefs,
            cmPrefs
        )
    }

    /** 取源码中 `const val PREFS_NAME = "..."` 的**声明值**（而非文本中出现过的任意同名字符串） */
    private fun declaredPrefsName(source: String): String? =
        Regex("""const val PREFS_NAME = "([^"]+)"""")
            .find(source)
            ?.groupValues
            ?.get(1)

    @Test
    fun `CM 信任存储不得回退到仅包名的降级键`() {
        val cm = readSource(CM_TRUST_STORE)

        // 键必须为「包名|摘要」两段式；摘要不可读时返回 false 而非写入 pkg|
        assertTrue("信任键必须为包名 + 摘要两段式", cm.contains("\"\$normalizedPackage|\$certSha256Hex\""))
        assertTrue("摘要不可读时必须 fail-closed 返回 false", cm.contains("?: return false"))
    }

    // ── ISSUE-P2-83：写入点必须真的接上 ───────────────────────────────

    @Test
    fun `保存与注册两条流程均写入 CM 通道绑定`() {
        assertTrue(
            "PasswordSaveActivity 未写入 CM 通道调用方绑定",
            readSource(PASSWORD_SAVE).contains("callerTrustStore.trust(")
        )
        assertTrue(
            "PasskeyCreateActivity 未写入 CM 通道调用方绑定",
            readSource(PASSKEY_CREATE).contains("callerTrustStore.trust(")
        )
    }

    @Test
    fun `写入点取的是系统背书的签名摘要而非自报值`() {
        listOf(PASSWORD_SAVE, PASSKEY_CREATE).forEach { path ->
            val source = readSource(path)
            assertTrue(
                "$path 必须经 CallingOriginResolver.certDigests 取系统背书摘要",
                source.contains("CallingOriginResolver.certDigests(")
            )
            assertTrue(
                "$path 必须在摘要不可读时保持未绑定（fail-closed）",
                source.contains("callerTrustStore.trust(")
            )
        }
    }

    // ── ISSUE-P3-111：密码填充链路的调用方归属交叉核对 ────────────────

    @Test
    fun `密码填充检索系统请求并交叉核对调用方包名`() {
        val source = readSource(PASSWORD_FILL)

        assertTrue(
            "PasswordFillActivity 必须检索系统注入的 ProviderGetCredentialRequest",
            source.contains("PendingIntentHandler.retrieveProviderGetCredentialRequest(")
        )
        assertTrue(
            "必须取平台背书的 CallingAppInfo 包名（不得用 Activity.getCallingPackage）",
            source.contains("CallingOriginResolver.systemAttestedPackageName(")
        )
        assertTrue(
            "系统认证包名与预期包名不一致时必须 fail-closed",
            source.contains("拒绝回填密码")
        )
        assertFalse(
            "不得回退到 Activity.getCallingPackage（PendingIntent 拉起场景为 android/null）",
            source.contains("getCallingPackage()")
        )
    }

    @Test
    fun `交叉核对不得在检索失败时拒绝填充`() {
        val source = readSource(PASSWORD_FILL)

        // 第四轮复核对 P3-111 的定版提醒：先保证 retrieve* 可用，否则交叉核对恒失败、
        // 把「能填充」变成「不能填充」。故拒绝条件必须同时要求 attestedPackage 非 null。
        assertTrue(
            "拒绝分支必须以 attestedPackage != null 为前提（检索失败不得阻断填充）",
            source.contains("attestedPackage != null")
        )
    }

    // ── ISSUE-P2-83：候选层与回传层的门控必须真的接上 ────────────────

    @Test
    fun `候选组装与两处回传前校验均接入签名绑定门控`() {
        listOf(ASSEMBLER, PASSWORD_FILL, PASSKEY_ASSERT).forEach { path ->
            assertTrue(
                "$path 未接入 CredentialManagerPackageBindingGate",
                readSource(path).contains("CredentialManagerPackageBindingGate.")
            )
        }
    }

    @Test
    fun `候选层包名维度必须以门控判定为前置`() {
        val matcher = readSource(CANDIDATE_MATCHER)

        assertTrue(
            "通行密钥候选：普通应用分支必须以 packageDimensionAllowed 为前置",
            matcher.contains("packageDimensionAllowed && callingPackage.isNotBlank() &&")
        )
        assertTrue(
            "密码候选：包名维度必须以 packageDimensionAllowed 为前置",
            matcher.contains("val packageMatch = packageDimensionAllowed && callingPackage.isNotBlank()")
        )
        assertTrue(
            "组装器必须经抽取出的候选判定器收口（不得再内联一份各自漂移的判据）",
            readSource(ASSEMBLER).contains("CredentialCandidateMatcher.matchesPassword(") &&
                readSource(ASSEMBLER).contains("CredentialCandidateMatcher.matchesPasskey(")
        )
    }

    @Test
    fun `findMatchingEntries 的门控入参不得有默认值`() {
        val source = readSource(PROVIDER_SERVICE)

        assertTrue(
            "包名维度必须显式接收门控判定结果",
            source.contains("packageDimensionAuthorized: Boolean")
        )
        assertFalse(
            "包名维度是越权面：给默认值等于留一个「忘记传参 = 放行」的 fail-open 口子",
            source.contains("packageDimensionAuthorized: Boolean = true") ||
                source.contains("packageDimensionAuthorized: Boolean = false")
        )
    }

    // ── ISSUE-P2-84：保存失败不得谎报成功 ─────────────────────────────

    @Test
    fun `保存失败必须 fail-closed 而非回传成功`() {
        val source = readSource(PASSWORD_SAVE)

        val failureCheck = source.indexOf("saveResult is KdbxResult.Failure")
        // 匹配**调用点**而非注释里出现过的同名字符串（带实参，避免被注释误命中）
        val success = source.indexOf("setResult(RESULT_OK, resultIntent)")

        assertTrue("必须检查 saveAutofillCredential 的返回结果", failureCheck >= 0)
        assertTrue("必须保留成功回传路径", success >= 0)
        assertTrue(
            "失败判定必须前置于成功回传（否则失败路径仍会回传 RESULT_OK）",
            failureCheck < success
        )
    }

    private fun readSource(path: String): String {
        val file = File(repositoryRoot, path)
        assertTrue("源文件不存在（是否被重命名/移动）：$path", file.isFile)
        return file.readText()
    }

    private companion object {
        const val CM_TRUST_STORE = "app/src/main/java/com/keepasskey/app/passkey/CredentialManagerCallerTrustStore.kt"
        const val AUTOFILL_TRUST_STORE = "app/src/main/java/com/keepasskey/app/autofill/AutofillCallerTrustStore.kt"
        const val PASSWORD_FILL = "app/src/main/java/com/keepasskey/app/passkey/PasswordFillActivity.kt"
        const val PASSWORD_SAVE = "app/src/main/java/com/keepasskey/app/passkey/PasswordSaveActivity.kt"
        const val PASSKEY_CREATE = "app/src/main/java/com/keepasskey/app/passkey/PasskeyCreateActivity.kt"
        const val PASSKEY_ASSERT = "app/src/main/java/com/keepasskey/app/passkey/PasskeyAssertionActivity.kt"
        const val ASSEMBLER = "app/src/main/java/com/keepasskey/app/passkey/CredentialResponseAssembler.kt"
        const val CANDIDATE_MATCHER = "app/src/main/java/com/keepasskey/app/passkey/CredentialCandidateMatcher.kt"
        const val PROVIDER_SERVICE = "app/src/main/java/com/keepasskey/app/passkey/KeePasskeyCredentialProviderService.kt"

        const val CM_PREFS_NAME = "keepasskey_cm_caller_trust"
        const val AUTOFILL_PREFS_NAME = "keepasskey_autofill_caller_trust"

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
            error("无法定位仓库根目录（起始：${System.getProperty("user.dir")}）")
        }

        const val ROOT_SEARCH_DEPTH = 4
    }
}
