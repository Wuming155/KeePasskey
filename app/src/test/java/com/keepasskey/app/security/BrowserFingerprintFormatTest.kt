package com.keepasskey.app.security

import com.keepasskey.app.passkey.CallingOriginResolver
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 浏览器签名证书指纹的**格式与双副本一致性**守卫（ISSUE-P3-88）。
 *
 * 缺陷背景：`com.android.chrome` 的首条指纹在**无冒号副本**里多写了一个 hex 字符（65 位），
 * 而 SHA-256 恒为 64 hex ⇒ 该条目**永不匹配**（浏览器委派对它形同不存在）；
 * 更糟的是同一份白名单里**同时**存在正确的冒号分隔副本与测试里复用的同错误副本，
 * 仓库「自证」了笔误却因缺少格式断言而零失败。
 *
 * 本用例把两件事变成机器可查：
 * 1. **格式**：任何指纹规范化（去冒号、转大写）后必须是 64 位 hex；
 * 2. **双副本一致**（比长度更强）：`CallingOriginResolver` 的
 *    `PRIVILEGED_BROWSER_ALLOWLIST` 同时收录「带冒号 / 不带冒号」两种写法，
 *    两者的**规范化集合必须完全相等**——只改一处即失败，杜绝「同一值两种写法互相打架」。
 */
class BrowserFingerprintFormatTest {

    @Test
    fun `受信浏览器指纹必须全部为 64 位大写 hex`() {
        val offenders = BrowserSigningFingerprints.TRUSTED
            .flatMap { (pkg, fingerprints) -> fingerprints.map { pkg to it } }
            .filter { (_, value) -> !SHA256_HEX_UPPER.matches(value) }

        assertTrue(
            "以下指纹不是 64 位大写 hex（SHA-256 恒 64 位；多/少一位即永不匹配）：$offenders",
            offenders.isEmpty()
        )
    }

    @Test
    fun `受信浏览器指纹不得出现同一包内的重复值`() {
        BrowserSigningFingerprints.TRUSTED.forEach { (pkg, fingerprints) ->
            assertEquals(
                "包 $pkg 的指纹集合含重复项（去重后条目数变少说明存在冗余副本）",
                fingerprints.size,
                fingerprints.map { it.uppercase() }.toSet().size
            )
        }
    }

    @Test
    fun `passkey 白名单必须由已取证指纹表派生且指纹规范化为大写冒号分隔`() {
        val allowlist = CallingOriginResolver.builtInAllowlistJson
        // 用零依赖解析器（org.json 在宿主单测里是未实现的桩）
        val root = com.keepasskey.app.passkey.SimpleJson.asObject(
            com.keepasskey.app.passkey.SimpleJson.parse(allowlist)
        )!!
        val apps = com.keepasskey.app.passkey.SimpleJson.arrayAt(root, "apps")!!

        val parsed = LinkedHashMap<String, MutableList<String>>()
        for (app in apps) {
            val appObject = com.keepasskey.app.passkey.SimpleJson.asObject(app)!!
            val info = com.keepasskey.app.passkey.SimpleJson.objectAt(appObject, "info")!!
            val packageName = com.keepasskey.app.passkey.SimpleJson.string(info, "package_name")!!
            val signatures = com.keepasskey.app.passkey.SimpleJson.arrayAt(info, "signatures").orEmpty()
            val fingerprints = parsed.getOrPut(packageName) { mutableListOf() }
            for (signature in signatures) {
                val signatureObject = com.keepasskey.app.passkey.SimpleJson.asObject(signature)!!
                fingerprints += com.keepasskey.app.passkey.SimpleJson
                    .string(signatureObject, "cert_fingerprint_sha256")!!
            }
        }

        // 1. 包名与指纹数量必须与已取证表逐一对应（派生而非手抄）
        assertEquals(BrowserSigningFingerprints.TRUSTED.keys, parsed.keys)
        BrowserSigningFingerprints.TRUSTED.forEach { (pkg, fingerprints) ->
            assertEquals(
                "包 $pkg 的指纹条数必须与已取证表一致",
                fingerprints.size,
                parsed[pkg]?.size
            )
        }

        // 2. 全部指纹必须是「大写、冒号分隔」的 64 位 hex（getOrigin 白名单的规范写法）
        val offenders = parsed.flatMap { (pkg, fingerprints) -> fingerprints.map { pkg to it } }
            .filter { (_, value) ->
                value.split(":").size != COLON_HEX_SEGMENTS ||
                    value.replace(":", "").let { !SHA256_HEX_UPPER.matches(it) }
            }
        assertTrue("以下指纹不是规范的大写冒号分隔形态：$offenders", offenders.isEmpty())

        // 3. 规范化后必须与已取证表逐字相等（任何抄写偏差都会在此失败）
        BrowserSigningFingerprints.TRUSTED.forEach { (pkg, fingerprints) ->
            val expected = fingerprints.map { it.replace(":", "").uppercase() }.toSet()
            val actual = parsed[pkg].orEmpty().map { it.replace(":", "").uppercase() }.toSet()
            assertEquals("包 $pkg 的指纹集合必须与已取证表一致", expected, actual)
        }
    }

    private companion object {
        val SHA256_HEX_UPPER = Regex("[0-9A-F]{64}")

        /** 冒号分隔指纹的分段数（SHA-256 = 32 字节 = 32 段两字符） */
        const val COLON_HEX_SEGMENTS = 32
    }
}
