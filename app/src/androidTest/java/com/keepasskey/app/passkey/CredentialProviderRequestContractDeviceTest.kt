package com.keepasskey.app.passkey

import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.Signature
import android.content.pm.SigningInfo
import android.os.Bundle
import android.service.credentials.CredentialProviderService
import androidx.credentials.GetPublicKeyCredentialOption
import androidx.credentials.provider.BeginCreatePublicKeyCredentialRequest
import androidx.credentials.provider.CallingAppInfo
import androidx.credentials.provider.PendingIntentHandler
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import org.junit.runner.RunWith
import java.security.MessageDigest
import java.util.Base64

/**
 * Credential Manager **Provider 侧请求契约** · 设备侧真机验证。
 *
 * ## 为什么必须真机
 *
 * `androidx.credentials.provider.*` 是 Android-only 类型，宿主单测**一例都没有 import 过**
 * （2026-09-18 核实：`grep -rl "androidx.credentials" app/src/test` 零命中；
 * `CredentialManagerCallerBindingWiringTest` 只对**源码文本**做 `contains` 断言）。
 * 加之 `org.json` 在宿主单测里是未实现桩、`PendingIntent` 需真实 Context，
 * 「调用方是谁 / 它声称的 origin 可不可信」这条 CWE-346 主链在宿主上**从未被执行**。
 *
 * 本用例用**真实签名信息**（测试 APK 自身经 `PackageManager` 取到的 `SigningInfo`）构造规范的
 * provider 对象并驱动生产解析路径，钉死四件事：
 * 1. [CallingOriginResolver.apkKeyHashOrigins] 与 [CallingOriginResolver.certDigests] 在真机上
 *    等于**独立复算**的 `SHA-256(Signature.toByteArray())`（ISSUE-P3-93 的「全部签名者」口径）；
 * 2. 浏览器委派 origin 只有在**包名 + 证书指纹**同时命中特权白名单时才被采信——白名单 JSON 由
 *    [CallingOriginResolver.buildAllowlistJson] 手写拼接，与 androidx `getOrigin` 的校验器是否
 *    兼容**只有真机能证**；不匹配的调用方即使自带 origin 也必须降级为 `android:apk-key-hash:`；
 * 3. ISSUE-P2-72：系统认证包名与候选组装阶段写入的预期包名不一致时**拒绝签发**；
 * 4. [CredentialCreateEntries.passkeyEntry] 的 `rp.id` fail-closed 与 CreateEntry 装配
 *    （既有的 `PasskeyCreationDeviceTest` 名字声称「解析标准 BeginCreatePublicKeyCredentialRequest」
 *    却从未真正构造过该对象，本用例补上这条实证）。
 */
@RunWith(AndroidJUnit4::class)
class CredentialProviderRequestContractDeviceTest {

    private val context = InstrumentationRegistry.getInstrumentation().targetContext

    /** 测试 APK 自身的包名与**真实**签名信息（非合成 hex）。 */
    private val callingPackage: String = context.packageName

    private val realSigningInfo: SigningInfo by lazy {
        @Suppress("DEPRECATION")
        val info = context.packageManager.getPackageInfo(
            callingPackage,
            PackageManager.GET_SIGNING_CERTIFICATES
        )
        assertNotNull("真机上必须能取到本应用 APK 的 SigningInfo", info.signingInfo)
        requireNotNull(info.signingInfo)
    }

    private fun callingAppInfo(origin: String? = null): CallingAppInfo =
        CallingAppInfo(callingPackage, realSigningInfo, origin)

    @Test
    fun 真机签名信息下apk密钥哈希与证书摘要等于独立复算值() {
        val info = callingAppInfo()
        assertEquals(callingPackage, CallingOriginResolver.systemAttestedPackageName(info))

        val signers = realSigningInfo.allSigners()
        assertTrue("真机上必须取到至少一个签名者", signers.isNotEmpty())
        val expectedHashOrigins = signers.map { signer ->
            CallingOriginResolver.APK_KEY_HASH_PREFIX +
                Base64.getUrlEncoder().withoutPadding()
                    .encodeToString(sha256(signer.toByteArray()))
        }
        assertEquals(
            "apk-key-hash origin 必须逐个签名者等于独立复算值（顺序：当前有效在前、历史在后）",
            expectedHashOrigins.distinct(),
            CallingOriginResolver.apkKeyHashOrigins(info)
        )

        val digests = CallingOriginResolver.certDigests(info)
        assertEquals(
            "摘要集合必须覆盖全部签名者并归一为大写十六进制",
            signers.map { sha256(it.toByteArray()).toUpperHex() }.distinct(),
            digests.values
        )
        assertEquals("主摘要必须存在（展示与信任记录写入用）", digests.values.first(), CallingOriginResolver.certSha256Hex(info))

        assertNull(
            "系统包名 android 不得被当作调用方归属（ISSUE-P2-72 的 `android://android` 绑定面）",
            CallingOriginResolver.systemAttestedPackageName(CallingAppInfo("android", realSigningInfo))
        )
        assertNull(CallingOriginResolver.systemAttestedPackageName(null))
        assertEquals(
            "候选值按顺序取首个合格者",
            callingPackage,
            CallingOriginResolver.clientDataAndroidPackageName("android", null, callingPackage)
        )
        assertNull(
            "全部候选不合格时必须返回 null（不得回退为本应用包名而对 RP 谎报归属）",
            CallingOriginResolver.clientDataAndroidPackageName("android", "", null)
        )
    }

    @Test
    fun 浏览器委派origin必须同时命中包名与证书指纹() {
        val info = callingAppInfo()
        // 白名单指纹必须是**大写冒号分隔**形态（与 `builtInAllowlistEntries()` 同一派生路径）；
        // 直接把 certDigests 的无冒号形态喂进去是另一处漂移，故此处显式走同一转换函数。
        val allowlist = CallingOriginResolver.buildAllowlistJson(
            mapOf(
                callingPackage to CallingOriginResolver.certDigests(info).values
                    .map { CallingOriginResolver.toColonSeparatedUpper(it) }
            )
        )

        // ① 白名单内：包名 + 真实指纹均命中 → 采信委派 origin（这条同时证伪「手写 JSON 与平台校验器不兼容」）
        assertEquals(
            "包名与指纹均命中白名单时必须返回声称的 web origin",
            WEB_ORIGIN,
            CallingOriginResolver.resolveTrustedOrigin(callingAppInfo(WEB_ORIGIN), allowlist)
        )

        // ② 同包名但指纹不符（另一台机器 / 未知轮换密钥）：降级为 apk-key-hash
        val wrongFingerprint = CallingOriginResolver.buildAllowlistJson(
            mapOf(callingPackage to listOf(ZERO_FINGERPRINT))
        )
        val downgraded = CallingOriginResolver.resolveTrustedOrigin(callingAppInfo(WEB_ORIGIN), wrongFingerprint)
        assertEquals(
            "指纹不匹配的委派调用方必须降级为普通应用路径（绝不采信其自称的 origin）",
            CallingOriginResolver.apkKeyHashOrigins(info).first(),
            downgraded
        )
        assertFalse("降级后的 origin 不得仍是 web origin", CallingOriginResolver.isBrowserOrigin(downgraded))

        // ③ 白名单为空：同样 fail-closed
        assertEquals(
            CallingOriginResolver.apkKeyHashOrigins(info).first(),
            CallingOriginResolver.resolveTrustedOrigin(callingAppInfo(WEB_ORIGIN), EMPTY_ALLOWLIST)
        )

        // ④ 无 origin 的普通应用调用：一律 apk-key-hash，与白名单无关
        assertEquals(
            CallingOriginResolver.apkKeyHashOrigins(info).first(),
            CallingOriginResolver.resolveTrustedOrigin(info, allowlist)
        )
    }

    @Test
    fun 真机断言请求解析_系统背书包名交叉核对与PRF入参() {
        val clientDataHash = ByteArray(32) { (it + 1).toByte() }
        val intent = platformGetCredentialIntent(ASSERTION_REQUEST_JSON, clientDataHash)
        assertNotNull(
            "前置：注入的平台 GetCredentialRequest 必须能被 PendingIntentHandler 换成 " +
                "ProviderGetCredentialRequest（换不出来即平台契约形态与本用例假设不符，整条用例是空跑）",
            PendingIntentHandler.retrieveProviderGetCredentialRequest(intent)
        )

        val caller = PasskeyAssertionRequestParser.resolveAttestedCaller(intent, TAG)
        assertFalse("未写入预期包名时不得判为不一致", caller.rejected)
        assertEquals("未写入预期包名时期望值应为空串（不得伪造）", "", caller.expectedPackage)
        assertEquals(
            "clientDataJSON 的归属必须取**系统认证**包名（ISSUE-P2-72：不得回退为本应用包名）",
            callingPackage, caller.clientDataPackage
        )

        val mismatched = platformGetCredentialIntent(ASSERTION_REQUEST_JSON, clientDataHash).apply {
            putExtra(PasskeyAssertionActivity.EXTRA_EXPECTED_PACKAGE, LOOKALIKE_PACKAGE)
        }
        assertTrue(
            "ISSUE-P2-72：组装阶段与断言窗口看到的调用方不一致必须 fail-closed",
            PasskeyAssertionRequestParser.resolveAttestedCaller(mismatched, TAG).rejected
        )

        val withEntry = platformGetCredentialIntent(ASSERTION_REQUEST_JSON, clientDataHash).apply {
            putExtra(PasskeyAssertionActivity.EXTRA_EXPECTED_PACKAGE, callingPackage)
            putExtra(PasskeyAssertionActivity.EXTRA_ENTRY_ID, ENTRY_ID)
            putExtra(PasskeyAssertionActivity.EXTRA_ORIGIN, WEB_ORIGIN)
        }
        val parsedCaller = PasskeyAssertionRequestParser.resolveAttestedCaller(withEntry, TAG)
        val assembled = PasskeyAssertionRequestParser.buildAssertionContext(withEntry, TAG, parsedCaller)
        assertNotNull("带 entryId 的断言请求必须可装配", assembled)
        requireNotNull(assembled)
        assertEquals(ENTRY_ID, assembled.entryId)
        assertArrayEquals(
            "特权调用方自带的 clientDataJSON 摘要必须原样透传（自建哈希会让 RP 验签失败）",
            clientDataHash, assembled.providedClientDataHash
        )
        assertTrue("userVerification=required 必须映射为强制生物识别", assembled.requireBiometric)
        val prf = assembled.prfEval
        assertNotNull("extensions.prf.eval 必须被解析", prf)
        requireNotNull(prf)
        assertArrayEquals(byteArrayOf(0x00, 0x01, 0x02), prf.first)
        assertArrayEquals(byteArrayOf(0x03, 0x04, 0x05), prf.second)
        assertFalse("断言请求未带 evalByCredential", prf.evalByCredentialPresent)
        assertEquals(
            "allowCredentials 必须用于收敛候选",
            setOf("AQIDBA"),
            WebAuthnRequest.parse(ASSERTION_REQUEST_JSON)?.allowCredentialIds
        )

        val missingEntry = Intent(withEntry).apply {
            removeExtra(PasskeyAssertionActivity.EXTRA_ENTRY_ID)
        }
        assertNull(
            "缺少 entryId 必须返回 null 由调用方收尾，绝不以空 id 继续签发",
            PasskeyAssertionRequestParser.buildAssertionContext(missingEntry, TAG, parsedCaller)
        )
    }

    @Test
    fun 真机注册入口装配_rp_id不可信必须拒绝呈现() {
        val request = BeginCreatePublicKeyCredentialRequest(
            CREATE_REQUEST_JSON,
            callingAppInfo(),
            Bundle()  // 必须用可变 Bundle：BeginCreatePublicKeyCredentialRequest 会向 callerToken 写入（Bundle.EMPTY 抛 UnsupportedOperationException）
        )
        val trusted = CredentialCreateEntries.passkeyEntry(context, request, WEB_ORIGIN)
        assertNotNull("rp.id 是调用方 origin 的可注册后缀时必须呈现创建入口", trusted)
        requireNotNull(trusted)
        assertEquals("账号名取请求的 user.name", "alice", trusted.accountName.toString())
        assertNotNull("入口必须携带可回注的 PendingIntent（ISSUE-P1-01）", trusted.pendingIntent)

        val spoofed = BeginCreatePublicKeyCredentialRequest(
            CREATE_REQUEST_JSON.replace("\"example.com\"", "\"evil-registrable.com\""),
            callingAppInfo(),
            Bundle()  // 必须用可变 Bundle：BeginCreatePublicKeyCredentialRequest 会向 callerToken 写入（Bundle.EMPTY 抛 UnsupportedOperationException）
        )
        assertNull(
            "rp.id 非调用方 origin 的可注册后缀时必须 fail-closed（不呈现任何条目）",
            CredentialCreateEntries.passkeyEntry(context, spoofed, WEB_ORIGIN)
        )

        // rp.id 缺失时回落为 origin 的可注册域；公共后缀仍须被拒
        val noRpId = BeginCreatePublicKeyCredentialRequest(
            CREATE_REQUEST_JSON.replace("\"id\":\"example.com\",", ""),
            callingAppInfo(),
            Bundle()  // 必须用可变 Bundle：BeginCreatePublicKeyCredentialRequest 会向 callerToken 写入（Bundle.EMPTY 抛 UnsupportedOperationException）
        )
        assertNotNull(
            "rp.id 缺失时应回落调用方 origin 的可注册域",
            CredentialCreateEntries.passkeyEntry(context, noRpId, WEB_ORIGIN)
        )
        assertNull(
            "origin 无可注册域时仍须拒绝（公共后缀不得作 RP ID）",
            CredentialCreateEntries.passkeyEntry(context, noRpId, "https://com")
        )
    }

    /** 全部签名者：当前有效（在前）+ 历史轮换（在后），与生产取值口径一致。 */
    private fun SigningInfo.allSigners(): List<Signature> =
        (apkContentsSigners.orEmpty().toList() + signingCertificateHistory.orEmpty().toList())
            .distinctBy { it.toByteArray().toList() }

    /**
     * 按**平台契约**装配一条 get 结果 Intent。
     *
     * 系统在候选下发后，把 [android.service.credentials.GetCredentialRequest] 以
     * [CredentialProviderService.EXTRA_GET_CREDENTIAL_REQUEST] 注入 provider 的结果 Activity，
     * androidx 再经 [PendingIntentHandler] 换成 `ProviderGetCredentialRequest`（API 34+ 路径）。
     * 刻意走平台对象而非直接 new androidx 对象：让「系统背书」这一环真实经过一次
     * parcel 往返与 `CredentialOption` 换型——这正是宿主单测无法覆盖的部分。
     */
    private fun platformGetCredentialIntent(requestJson: String, clientDataHash: ByteArray): Intent {
        // 由 androidx 的公开构造器产出「规范形态」的请求数据 Bundle（含 subtype 与两个字段），
        // 再原样交给平台 Builder —— 全程不复制库内 private 常量，形态与线上一致。
        val androidxOption = GetPublicKeyCredentialOption(requestJson, clientDataHash)
        val platformOption = android.credentials.CredentialOption.Builder(
            androidxOption.type,
            androidxOption.requestData,
            androidxOption.candidateQueryData ?: Bundle()
        ).build()
        // 形参顺序自证：requestData 必须落到 credentialRetrievalData 槽位，否则下面的 androidx
        // 换型会静默降级为 GetCustomCredentialOption，断言面失真而用例仍可能「看起来通过」。
        if (platformOption.credentialRetrievalData != androidxOption.requestData) {
            fail(
                "平台 CredentialOption.credentialRetrievalData 未按预期装载" +
                    "（Builder 形参顺序需复核）actual=${platformOption.credentialRetrievalData}"
            )
        }
        val frameworkRequest = android.service.credentials.GetCredentialRequest(
            android.service.credentials.CallingAppInfo(callingPackage, realSigningInfo, null),
            listOf(platformOption)
        )
        return Intent().putExtra(
            CredentialProviderService.EXTRA_GET_CREDENTIAL_REQUEST,
            frameworkRequest
        )
    }

    private fun sha256(bytes: ByteArray): ByteArray =
        MessageDigest.getInstance("SHA-256").digest(bytes)

    private fun ByteArray.toUpperHex(): String = joinToString("") { "%02X".format(it) }

    private companion object {
        const val WEB_ORIGIN = "https://app.example.com"
        const val LOOKALIKE_PACKAGE = "com.evil.lookalike"
        const val ENTRY_ID = "entry-42"
        const val TAG = "device-cm-contract"

        /** 一定不属于本机的指纹（前 16 字节零，形态与平台要求一致）。 */
        const val ZERO_FINGERPRINT =
            "00:00:00:00:00:00:00:00:00:00:00:00:00:00:00:00:00:00:00:00:00:00:00:00:00:00:00:00:00:00:00:00"

        const val EMPTY_ALLOWLIST = """{"apps":[]}"""

        /** 断言请求：`userVerification: required` + PRF eval + 单个 allowCredential。 */
        const val ASSERTION_REQUEST_JSON =
            """{"challenge":"q0k7Tew","rpId":"example.com","userVerification":"required",""" +
                """"allowCredentials":[{"type":"public-key","id":"AQIDBA"}],""" +
                """"extensions":{"prf":{"eval":{"first":"AAEC","second":"AwQF"}}}}"""

        /** 注册请求：rp.id 与 user 齐备，公钥参数含 ES256。 */
        const val CREATE_REQUEST_JSON =
            """{"rp":{"id":"example.com"},"user":{"name":"alice","displayName":"Alice","id":"AAECAw"},""" +
                """"challenge":"Y2hhbGxlbmdl","pubKeyCredParams":[{"type":"public-key","alg":-7}],""" +
                """"authenticatorSelection":{"userVerification":"required"}}"""
    }
}
