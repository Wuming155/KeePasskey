package com.keepasskey.app.security

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

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
    fun `passkey 白名单的带冒号与不带冒号副本必须规范化一致`() {
        val source = readSource(CALLING_ORIGIN_RESOLVER_SOURCE)
        val raw = FINGERPRINT_ENTRY.findAll(source).map { it.groupValues[1] }.toList()

        assertTrue("未从 $CALLING_ORIGIN_RESOLVER_SOURCE 提取到指纹（解析正则已失效）", raw.size >= 2)

        val normalized = raw.map { it.replace(":", "").uppercase() }
        val formatOffenders = normalized.filterNot { SHA256_HEX_UPPER.matches(it) }
        assertTrue("白名单存在非 64 位 hex 指纹：$formatOffenders", formatOffenders.isEmpty())

        val colonForm = raw.filter { it.contains(":") }.map { it.replace(":", "").uppercase() }.toSet()
        val plainForm = raw.filterNot { it.contains(":") }.map { it.uppercase() }.toSet()

        assertEquals(
            "同一指纹的两种写法必须指向同一值（否则其中一份是笔误，且永不匹配）",
            colonForm,
            plainForm
        )
    }

    /** 源码全文；路径相对仓库根（app 模块测试工作目录为 app/，向上回溯定位仓库根） */
    private fun readSource(path: String): String {
        val file = File(repositoryRoot, path)
        assertTrue("源码文件不存在（是否被重命名/移动）：$path", file.isFile)
        return file.readText()
    }

    private companion object {
        const val CALLING_ORIGIN_RESOLVER_SOURCE =
            "app/src/main/java/com/keepasskey/app/passkey/CallingOriginResolver.kt"

        val SHA256_HEX_UPPER = Regex("[0-9A-F]{64}")

        /** 白名单 JSON 内的指纹条目（`"cert_fingerprint_sha256": "<value>"`） */
        val FINGERPRINT_ENTRY =
            Regex(""""cert_fingerprint_sha256"\s*:\s*"([0-9a-fA-F:]+)"""")

        /** 仓库根：同时具备 app 与 core 模块源码目录的最近祖先 */
        val repositoryRoot: File by lazy {
            var dir: File? = File(System.getProperty("user.dir")).absoluteFile
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
