package com.keepasskey.app.sync

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * 凭据中间量清零的**接线守卫**（ISSUE-P3-100 / ISSUE-P3-101）。
 *
 * 两处缺陷的共同点：被泄漏的都是**方法内部**的派生敏态缓冲（解密得到的 WebDAV 口令 / S3 AK-SK、
 * `"AWS4"‖secretAccessKey` 拼接缓冲），清零效果无法由外部用例直接观测——故本类以
 * 「源码接线断言」锁定整改，配合全量回归证明未改变既有行为：
 * - [SyncProviderResolver.resolveRemotePath] 只取 `remotePath` / `objectKey`，却会解密出**完整凭据**；
 *   两个分支必须在 `finally` 中擦除（对照 `resolveProvider` 的既有收口口径）；
 * - [com.keepasskey.sync.s3.S3RequestSigner] 的 `getSignatureKey` 原只擦 `prefix` / `keyBytes`，
 *   漏擦 `combined`（其 `finally` 位于 `combined.copyOf()` 之后，补擦对返回值无影响）。
 *
 * 断言前剔除注释（整改说明自身会提到 `fill('0')` 等字样）。
 */
class CredentialIntermediateWipeTest {

    private val resolverBody: String by lazy { functionBody(readSource(RESOLVER_SOURCE), "fun resolveRemotePath(") }

    private val signerSource: String by lazy { stripComments(readSource(SIGNER_SOURCE)) }

    @Test
    fun `远端路径解析的 WebDAV 分支必须擦除解密得到的口令`() {
        assertTrue(
            "resolveRemotePath 的 WebDAV 分支必须在 finally 中清零 cfg.password（用毕即擦）",
            Regex("""cfg\?\.password\?\.fill\('0'\)""").containsMatchIn(resolverBody)
        )
    }

    @Test
    fun `远端路径解析的 S3 分支必须擦除 AK 与 SK`() {
        assertTrue(
            "resolveRemotePath 的 S3 分支必须清零 cfg.accessKey",
            Regex("""cfg\?\.accessKey\?\.fill\('0'\)""").containsMatchIn(resolverBody)
        )
        assertTrue(
            "resolveRemotePath 的 S3 分支必须清零 cfg.secretKey",
            Regex("""cfg\?\.secretKey\?\.fill\('0'\)""").containsMatchIn(resolverBody)
        )
    }

    @Test
    fun `远端路径解析必须保持 finally 收口（异常路径亦不泄漏）`() {
        val finallyCount = Regex("""finally\s*\{""").findAll(resolverBody).count()
        assertTrue(
            "两个分支各需一个 finally（实际 $finallyCount 个）——否则解密失败/路径处理抛异常时会漏擦凭据",
            finallyCount >= 2
        )
    }

    @Test
    fun `S3 签名密钥派生必须擦除 AWS4 前缀与密钥的拼接缓冲`() {
        // 提取 getSignatureKey 中「prefix/keyBytes」的收口块（注释已剔除，块内不含 `}`）
        val wipeBlock = Regex(
            """finally\s*\{[^}]*prefix\.fill\(0\)[^}]*keyBytes\.fill\(0\)[^}]*}""",
            RegexOption.DOT_MATCHES_ALL
        ).find(signerSource)?.value

        assertTrue("未找到 prefix/keyBytes 的擦除块（源码结构可能已变更）", wipeBlock != null)
        assertTrue(
            "含 secretAccessKey 的 combined 缓冲必须与 prefix/keyBytes 一并擦除（ISSUE-P3-101）",
            wipeBlock!!.contains("combined.fill(0)")
        )
    }

    @Test
    fun `combined 的擦除必须晚于其独立副本的取出`() {
        val copyIndex = signerSource.indexOf("combined.copyOf()")
        val wipeIndex = signerSource.indexOf("combined.fill(0)")
        assertTrue("combined 必须先 copyOf 出独立副本再擦除（否则返回值会被清零）", copyIndex in 0 until wipeIndex)
        assertFalse(
            "不得出现对 combined 的原地返回（会与擦除动作冲突）",
            signerSource.contains("return combined")
        )
    }

    /** 抽取指定函数的函数体（自函数签名起，按花括号配对截取） */
    private fun functionBody(source: String, signature: String): String {
        val code = stripComments(source)
        val start = code.indexOf(signature)
        assertTrue("未找到函数：$signature", start >= 0)
        var depth = 0
        var index = code.indexOf('{', start)
        val bodyStart = index
        while (index < code.length) {
            when (code[index]) {
                '{' -> depth++
                '}' -> {
                    depth--
                    if (depth == 0) return code.substring(bodyStart, index + 1)
                }
            }
            index++
        }
        error("函数体括号未闭合：$signature")
    }

    private fun stripComments(source: String): String =
        source.replace(Regex("""/\*.*?\*/""", RegexOption.DOT_MATCHES_ALL), "")
            .replace(Regex("""//[^\n]*"""), "")

    private fun readSource(path: String): String {
        val file = File(repositoryRoot, path)
        assertTrue("源码文件不存在（是否被重命名或移动）：$path", file.isFile)
        return file.readText()
    }

    private companion object {
        const val RESOLVER_SOURCE = "app/src/main/java/com/keepasskey/app/sync/SyncProviderResolver.kt"
        const val SIGNER_SOURCE = "sync/src/main/java/com/keepasskey/sync/s3/S3RequestSigner.kt"
        const val ROOT_SEARCH_DEPTH = 6

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
            error("未能定位仓库根（自 ${System.getProperty("user.dir")} 向上 ${ROOT_SEARCH_DEPTH} 层）")
        }
    }
}
