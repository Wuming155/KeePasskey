package com.keepasskey.app.passkey

import com.keepasskey.app.R
import com.keepasskey.core.model.PasskeyData
import java.security.KeyPairGenerator
import java.security.spec.ECGenParameterSpec
import java.util.Base64
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * `ISSUE-P3-647`：导入草案宿主的工作数组清零与草案生命周期（AC③ 的宿主半边）。
 *
 * 锁四件事，每件都对应「只看返回值就会漏掉」的形态：
 * ① `CharArray → UTF-8` 的工作载荷、扫描器 `ByteSink` 各容量代次、AST 字符串副本与私钥 DER
 *    在 [PasskeyImportDraftHost.begin] 返回时**逐字节为零**（用 [PasskeyCxfWorkProbe] 直接观测）；
 * ② 草案交付的私钥是**独立有效**副本——工作数组清零不得把用户待确认的凭据一起擦掉；
 * ③ 取消 / 确认 / 连续扫两次三条路径都擦除上一份草案，且 `takeForImport` 之后本类不再持有；
 * ④ 拒收路径（只读、畸形 JSON、字段非法）同样清零，且只报静态文案、不回显载荷。
 *
 * 夹具用运行时生成的 PKCS#8（`secp256r1` ⇒ DER 带 prime256v1 OID ⇒ 判 ES256），
 * 避免手抄 Base64 与解析器判据脱钩后用例静默空转。
 */
class PasskeyImportDraftHostTest {

    private val notices = ArrayList<Int>()

    private fun host(readOnly: Boolean = false): PasskeyImportDraftHost =
        PasskeyImportDraftHost(isReadOnly = { readOnly }, onNotice = { notices += it })

    private fun cxfPayload(rpId: String = "webauthn.io"): String {
        val der = KeyPairGenerator.getInstance("EC")
            .apply { initialize(ECGenParameterSpec("secp256r1")) }
            .generateKeyPair().private.encoded
        val key = Base64.getUrlEncoder().withoutPadding().encodeToString(der)
        return """{"type":"passkey","credentialId":"Y3JlZGVudGlhbElkRXhhbXBsZQ","rpId":"$rpId",""" +
            """"userHandle":"aGFuZGxl","key":"$key"}"""
    }

    /** ①+②：成功路径的工作数组全清零，交付草案仍可落库。 */
    @Test
    fun `一 成功解析后工作数组全清零 草案私钥保持有效`() {
        val drafts = host()
        val probe = PasskeyCxfWorkProbe()
        val decoded = cxfPayload().toCharArray()
        assertTrue("解析成功须进入确认环节", drafts.begin(decoded, probe))

        assertTrue("入参 CharArray 归本类擦除", decoded.all { it == ERASED_CHAR })
        val draft = requireNotNull(drafts.pending.value) { "成功解析须挂出草案" }
        assertWorkBuffersErased(probe, "成功路径", draft.credential.privateKeyPemChars)
        assertEquals("webauthn.io", draft.credential.relyingPartyId)
        assertTrue(
            "交付的私钥不得被工作数组清零连带擦掉",
            String(draft.credential.privateKeyPemChars).startsWith(PEM_HEADER)
        )
        assertEquals("草案须能转成落库模型", PasskeyData.ALGORITHM_ES256, draft.credential.algorithmId)
        drafts.dismiss()
    }

    /** ③：取消擦除草案；确认交出后本类不再持有（再次取消无副作用）。 */
    @Test
    fun `二 取消与确认交出都擦除草案且不重复持有`() {
        val drafts = host()
        drafts.begin(cxfPayload().toCharArray())
        val cancelled = requireNotNull(drafts.pending.value)
        drafts.dismiss()
        assertNull("取消后不得残留草案", drafts.pending.value)
        assertTrue("取消路径须清零私钥字符", cancelled.credential.privateKeyPemChars.all { it == ERASED_CHAR })

        drafts.begin(cxfPayload().toCharArray())
        val taken = requireNotNull(drafts.takeForImport()) { "确认路径须交出草案" }
        assertNull("交出后本类不得再持有（否则两处都能读同一份私钥）", drafts.pending.value)
        assertNull("重复确认返回 null（对话框关闭与确认竞态的既有口径）", drafts.takeForImport())
        assertTrue(
            "交出后草案的秘密仍须有效，擦除责任转移给调用方（落库 / 取消各自负责）",
            String(taken.credential.privateKeyPemChars).startsWith(PEM_HEADER)
        )
        taken.wipe()
        assertTrue("调用方擦除后清零", taken.credential.privateKeyPemChars.all { it == ERASED_CHAR })
    }

    /** ③：连续扫两次时上一份草案必须被擦除，不得因换新而泄漏；第二次的 PEM 数组各自独立。 */
    @Test
    fun `三 连续两次导入擦除上一份草案`() {
        val drafts = host()
        val probe = PasskeyCxfWorkProbe()
        drafts.begin(cxfPayload("first.example").toCharArray(), probe)
        val first = requireNotNull(drafts.pending.value)
        drafts.begin(cxfPayload("second.example").toCharArray(), probe)
        val second = requireNotNull(drafts.pending.value)
        assertTrue("上一份草案的私钥须被擦除", first.credential.privateKeyPemChars.all { it == ERASED_CHAR })
        assertEquals("新草案须是第二把", "second.example", second.credential.relyingPartyId)
        assertFalse("两份草案不得是同一实例", first === second)
        assertEquals("两把凭据各一份 PEM 工作数组", 2, probe.pemChars.size)
        assertWorkBuffersErased(probe, "连续两次导入", second.credential.privateKeyPemChars)
        drafts.wipeAll()
        assertTrue("wipeAll（销毁 / 锁定兜底）同样擦除", second.credential.privateKeyPemChars.all { it == ERASED_CHAR })
    }

    /** ④：只读会话在解析前硬拒绝，仍擦入参，且不产生任何工作缓冲。 */
    @Test
    fun `四 只读会话拒绝且不分配解析工作缓冲`() {
        val drafts = host(readOnly = true)
        val probe = PasskeyCxfWorkProbe()
        val decoded = cxfPayload().toCharArray()
        assertFalse("只读会话不得进入确认环节", drafts.begin(decoded, probe))
        assertTrue("只读路径仍须擦除入参", decoded.all { it == ERASED_CHAR })
        assertNull("只读不得挂草案", drafts.pending.value)
        assertEquals(listOf(R.string.readonly_save_rejected), notices)
        assertTrue(
            "只读拒绝发生在解析之前，不应分配 UTF-8 载荷",
            probe.utf8Payloads.isEmpty() && probe.sinkBuffers.isEmpty()
        )
    }

    /** ④：畸形 JSON 与字段非法两条拒收路径的工作数组同样清零。 */
    @Test
    fun `五 拒收路径同样清零工作数组且不回显载荷`() {
        val drafts = host()
        val malformed = PasskeyCxfWorkProbe()
        val truncated = cxfPayload().dropLast(3).toCharArray()
        assertFalse(drafts.begin(truncated, malformed))
        assertTrue("畸形 JSON 须分配并清零 UTF-8 载荷", malformed.utf8Payloads.isNotEmpty())
        assertWorkBuffersErased(malformed, "畸形 JSON 路径")
        assertEquals(listOf(R.string.passkey_import_failed_invalid), notices)

        notices.clear()
        val badField = PasskeyCxfWorkProbe()
        val invalid = cxfPayload().replace("Y3JlZGVudGlhbElkRXhhbXBsZQ", "!!!!").toCharArray()
        assertFalse(drafts.begin(invalid, badField))
        assertWorkBuffersErased(badField, "字段拒绝路径")
        assertEquals(listOf(R.string.passkey_import_failed_invalid), notices)
        assertNull("拒收不得挂草案", drafts.pending.value)
    }

    /** ④：超上限载荷（>4 KiB）在进入扫描器前即拒，仍须清零 UTF-8 工作载荷。 */
    @Test
    fun `六 超上限载荷拒绝时仍清零UTF8工作载荷`() {
        val drafts = host()
        val probe = PasskeyCxfWorkProbe()
        val oversized = ("{" + "\"pad\":\"" + "a".repeat(PasskeyCxfReader.MAX_IMPORT_PAYLOAD_BYTES) + "\"}")
        assertFalse(drafts.begin(oversized.toCharArray(), probe))
        assertEquals(listOf(R.string.passkey_import_failed_too_large), notices)
        assertEquals("UTF-8 工作载荷须被观察", 1, probe.utf8Payloads.size)
        assertTrue("UTF-8 工作载荷须清零", probe.utf8Payloads.single().all { it == 0.toByte() })
        assertTrue("超上限不得进入扫描器", probe.sinkBuffers.isEmpty())
    }

    /**
     * 统一断言：观察到的所有工作数组逐字节为零。
     *
     * [transferredPem] 是已交给草案的那一份 PEM 字符数组（R2 转移）：它属接收方，不得被清零；
     * 其余 PEM 代次（拒收 / 被替换的旧草案）仍须为零。
     */
    private fun assertWorkBuffersErased(
        probe: PasskeyCxfWorkProbe,
        label: String,
        transferredPem: CharArray? = null
    ) {
        assertTrue("$label：UTF-8 工作载荷须被观察", probe.utf8Payloads.isNotEmpty())
        probe.utf8Payloads.forEachIndexed { index, payload ->
            assertTrue("$label：第 $index 份 UTF-8 工作载荷仍有非零字节", payload.all { it == 0.toByte() })
        }
        assertTrue("$label：扫描工作缓冲须被观察", probe.sinkBuffers.isNotEmpty())
        probe.sinkBuffers.forEachIndexed { index, buffer ->
            assertTrue("$label：第 $index 代 ByteSink 缓冲仍有非零字节", buffer.all { it == 0.toByte() })
        }
        probe.stringCopies.forEachIndexed { index, copy ->
            assertTrue("$label：第 $index 份 AST 字符串副本仍有非零字节", copy.all { it == 0.toByte() })
        }
        probe.derBytes.forEachIndexed { index, der ->
            assertTrue("$label：第 $index 份私钥 DER 工作数组仍有非零字节", der.all { it == 0.toByte() })
        }
        probe.pemChars.forEachIndexed { index, pem ->
            if (pem === transferredPem) {
                assertTrue("$label：已转移给草案的 PEM 字符数组不得被清零", String(pem).startsWith(PEM_HEADER))
            } else {
                assertTrue("$label：第 $index 份 PEM 字符工作数组仍有非零字节", pem.all { it == ERASED_CHAR })
            }
        }
    }

    private companion object {
        const val ERASED_CHAR = '0'
        const val PEM_HEADER = "-----BEGIN PRIVATE KEY-----"
    }
}
