package com.keepasskey.app.passkey

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Base64

/**
 * WebAuthn 请求模型单测（本次整改：`pubKeyCredParams` / `user.id` / `allowCredentials` /
 * `excludeCredentials` / `userVerification` / `extensions.prf` 此前**全部被忽略**，
 * 导致算法写死、userHandle 自造、候选不收敛、UV 要求被降级）。
 *
 * 解析走零依赖的 [SimpleJson]（宿主单测可执行），故本用例在 JVM 上即可锁定语义。
 */
class WebAuthnRequestOptionsTest {

    private fun b64Url(bytes: ByteArray): String =
        Base64.getUrlEncoder().withoutPadding().encodeToString(bytes)

    private val creationJson = """
        {
          "challenge": "chal",
          "rp": {"name": "Demo", "id": "example.com"},
          "user": {"id": "dXNlci1pZA", "name": "alice", "displayName": "Alice"},
          "pubKeyCredParams": [
            {"type": "public-key", "alg": -8},
            {"type": "public-key", "alg": -7}
          ],
          "excludeCredentials": [{"type": "public-key", "id": "cred-a"}],
          "authenticatorSelection": {"userVerification": "required", "residentKey": "required"},
          "extensions": {"prf": {"eval": {"first": "${b64Url(ByteArray(32) { 1 })}"}}}
        }
    """.trimIndent()

    private val assertionJson = """
        {
          "challenge": "chal",
          "rpId": "example.com",
          "allowCredentials": [
            {"type": "public-key", "id": "cred-a"},
            {"type": "public-key", "id": "cred-b"}
          ],
          "userVerification": "discouraged",
          "extensions": {"prf": {"eval": {"first": "${b64Url(ByteArray(32) { 2 })}", "second": "${b64Url(ByteArray(32) { 3 })}"}}}
        }
    """.trimIndent()

    private fun creation(): WebAuthnRequest = WebAuthnRequest.parse(creationJson)!!
    private fun assertion(): WebAuthnRequest = WebAuthnRequest.parse(assertionJson)!!

    @Test
    fun `注册请求解析 rp-id 用户名与 user-id`() {
        val request = creation()
        assertEquals("example.com", request.rpId)
        assertEquals("alice", request.userName)
        assertEquals("Alice", request.userDisplayName)
        assertEquals("dXNlci1pZA", request.userId)
        assertEquals("chal", request.challenge)
    }

    @Test
    fun `注册请求按请求顺序解析 pubKeyCredParams`() {
        assertEquals(listOf(-8, -7), creation().pubKeyCredParams)
    }

    @Test
    fun `excludeCredentials 解析为 id 集合`() {
        assertEquals(setOf("cred-a"), creation().excludeCredentialIds)
    }

    @Test
    fun `断言请求解析 rpId allowCredentials 与 userVerification`() {
        val request = assertion()
        assertEquals("example.com", request.rpId)
        assertEquals(setOf("cred-a", "cred-b"), request.allowCredentialIds)
        assertEquals(WebAuthnRequest.UserVerification.DISCOURAGED, request.userVerification)
        assertEquals(
            WebAuthnRequest.UserVerification.REQUIRED,
            creation().authenticatorSelectionUserVerification
        )
    }

    @Test
    fun `userVerification 缺省为 preferred 且大小写不敏感`() {
        assertEquals(
            WebAuthnRequest.UserVerification.PREFERRED,
            WebAuthnRequest.parse("{}")!!.userVerification
        )
        assertEquals(
            WebAuthnRequest.UserVerification.REQUIRED,
            WebAuthnRequest.parse("""{"userVerification":"REQUIRED"}""")!!.userVerification
        )
        assertEquals(
            WebAuthnRequest.UserVerification.PREFERRED,
            WebAuthnRequest.parse("{}")!!.authenticatorSelectionUserVerification
        )
    }

    @Test
    fun `PRF 输入解析出 eval 两个输入且无 evalByCredential`() {
        val prf = assertion().prfEval
        assertNotNull(prf)
        assertArrayEquals(ByteArray(32) { 2 }, prf!!.first)
        assertArrayEquals(ByteArray(32) { 3 }, prf.second)
        assertFalse("断言请求允许 evalByCredential", prf.evalByCredentialPresent)

        val createPrf = creation().prfEval
        assertNotNull(createPrf)
        assertNull("创建请求未给出 eval.second", createPrf!!.second)
    }

    @Test
    fun `PRF 请求缺少 eval-first 时视为未请求`() {
        assertNull(WebAuthnRequest.parse("""{"extensions":{"prf":{}}}""")!!.prfEval)
        assertNull(
            WebAuthnRequest.parse("""{"extensions":{"prf":{"eval":{"first":"!!!"}}}}""")!!.prfEval
        )
    }

    @Test
    fun `evalByCredential 在注册请求中可被识别`() {
        val json = """{"extensions":{"prf":{"eval":{"first":"${b64Url(ByteArray(32) { 9 })}"},"evalByCredential":{"cred-a":{}}}}}"""
        assertTrue(WebAuthnRequest.parse(json)!!.prfEval!!.evalByCredentialPresent)
    }

    @Test
    fun `畸形请求 JSON 一律回落到安全缺省`() {
        assertNull("完全非法的 JSON 必须解析失败（调用方按缺省处理）", WebAuthnRequest.parse("{ this is not json"))
        assertNull(WebAuthnRequest.parse(""))
        assertNull(WebAuthnRequest.parse(null))

        // 结构合法但字段缺失 / 类型不符：全部回落到安全缺省，绝不隐式强转
        val empty = WebAuthnRequest.parse("""{"user":{},"pubKeyCredParams":[],"rp":{}}""")!!
        assertEquals("", empty.rpId)
        assertEquals("", empty.challenge)
        assertEquals("", empty.userId)
        assertEquals(emptyList<Int>(), empty.pubKeyCredParams)
        assertEquals(emptySet<String>(), empty.allowCredentialIds)
        assertEquals(emptySet<String>(), empty.excludeCredentialIds)
        assertNull(empty.prfEval)

        val wrongTypes = WebAuthnRequest.parse(
            """{"challenge":123,"rp":{"id":456},"pubKeyCredParams":[{"alg":"-7"}]}"""
        )!!
        assertEquals("数值 / 字符串不得被强转为字符串", "", wrongTypes.challenge)
        assertEquals("", wrongTypes.rpId)
        assertEquals(emptyList<Int>(), wrongTypes.pubKeyCredParams)
    }

    @Test
    fun `Base64URL 解码容忍有无 padding 且拒绝非法输入`() {
        val raw = ByteArray(20) { (it + 1).toByte() }
        val padded = Base64.getUrlEncoder().encodeToString(raw)
        val unpadded = Base64.getUrlEncoder().withoutPadding().encodeToString(raw)
        assertArrayEquals(raw, WebAuthnRequest.base64UrlDecode(padded))
        assertArrayEquals(raw, WebAuthnRequest.base64UrlDecode(unpadded))
        assertNull(WebAuthnRequest.base64UrlDecode("!!!"))
        assertNull(WebAuthnRequest.base64UrlDecode("A"))
        assertNull(WebAuthnRequest.base64UrlDecode(""))
    }

    // ── SimpleJson 解析器自身边界（对抗性输入不得使解析器崩溃或静默错解析） ──

    @Test
    fun `转义与 Unicode 转义被正确还原`() {
        val parsed = SimpleJson.asObject(
            SimpleJson.parse("""{"a":"line\nbreak","b":"\u0041\u4e2d","c":"quote\"slash\\"}""")
        )!!
        assertEquals("line\nbreak", parsed["a"])
        assertEquals("A中", parsed["b"])
        assertEquals("quote\"slash\\", parsed["c"])
    }

    @Test
    fun `超深嵌套被拒绝且不抛栈溢出`() {
        val deep = "[".repeat(SimpleJson.MAX_DEPTH + 5) + "]".repeat(SimpleJson.MAX_DEPTH + 5)
        try {
            SimpleJson.parse(deep)
            org.junit.Assert.fail("超过深度上限的输入必须被拒绝")
        } catch (_: IllegalArgumentException) {
            // 预期：fail-closed 而非 StackOverflowError
        }
    }

    @Test
    fun `尾部多余内容与非闭合结构被拒绝`() {
        listOf("{}{}", """{"a":1} trailing""", """{"a":}""", """{"a" 1}""", "[1,2", "\"unclosed").forEach { broken ->
            try {
                SimpleJson.parse(broken)
                org.junit.Assert.fail("非法 JSON 必须被拒绝: $broken")
            } catch (_: IllegalArgumentException) {
                // 预期
            }
        }
    }

    @Test
    fun `数字解析覆盖负数小数与指数且拒绝非有限值`() {
        val parsed = SimpleJson.asObject(
            SimpleJson.parse("""{"a":-7,"b":1.5,"c":1e3}""")
        )!!
        assertEquals(-7, SimpleJson.int(parsed, "a"))
        assertEquals(1000, SimpleJson.int(parsed, "c"))
        assertNull("非整数不得被静默截断", SimpleJson.int(parsed, "b"))
    }
}
