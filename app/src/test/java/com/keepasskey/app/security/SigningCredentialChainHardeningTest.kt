package com.keepasskey.app.security

import com.keepasskey.app.testutil.stripCommentsOnly
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.security.MessageDigest

/**
 * 发布签名口令链的构建期加固守护（ISSUE-P2-573）。
 *
 * 缺陷背景：`tools/signing/read-cred.ps1` 是**仓库内、PR 可改**脚本，`app/build.gradle.kts` 曾在
 * **Gradle 配置阶段**被**任意** gradle 任务（`test` / `lint` / `help` / IDE 同步……）无条件执行它——
 * 恶意 PR 在保持「退出码 0/1/2 + stdout 语义」（脚本头部调用方契约）不变的前提下插入外传调用即可
 * 伪装，被合并后（`main` 无分支保护，`PD-04`）Windows 维护者本机跑任意 `gradlew` 即静默外传凭证
 * 管理器中的签名口令与 keystore 文件本身。整改落地两道闸门（收窄执行面 + 执行前完整性基线），
 * 两者都属**静默失效型**：基线常量忘了随脚本更新、或闸门 / 校验被挪到 `ProcessBuilder` 之后，
 * 编译与运行都不会有任何症状（正是缺陷本身的形态）。故按 `BackupAndSafHardeningTest` 同范式以
 * 静态断言锁定三条不变量：
 * ① `credManReadScriptSha256` 基线必须与 `read-cred.ps1` 的实际字节一致——任一侧单独改动即红；
 * ② 脚本执行（`ProcessBuilder`）之前必须先过「release 产物闸门」与完整性校验，且顺序为闸门一 → 闸门二；
 * ③ 完整性校验在失配（及脚本缺失）时必须抛 `GradleException` 中止构建（fail-closed，而非告警后继续）。
 *
 * 断言前剔除注释——整改说明本身会写出被断言的字面量。
 */
class SigningCredentialChainHardeningTest {

    @Test
    fun `完整性基线必须与脚本实际字节一致`() {
        val script = File(repositoryRoot, READ_CRED_SCRIPT)
        assertTrue("凭证读取脚本不存在（是否被重命名 / 移动）：$READ_CRED_SCRIPT", script.isFile)

        val pinned = PINNED_SHA256.find(buildScriptText())?.groupValues?.get(1)
        assertTrue(
            "[$BUILD_SCRIPT] 未找到 64 位十六进制形态的 credManReadScriptSha256 基线常量——" +
                "完整性闸门被删除 / 改名即静默失效（ISSUE-P2-573 闸门二）",
            pinned != null,
        )

        assertEquals(
            "[$READ_CRED_SCRIPT] 与构建脚本钉定的 SHA-256 不一致——脚本任何字节改动都必须在同一批" +
                "同步更新基线常量（ISSUE-P2-573 完整性基线）",
            sha256Hex(script.readBytes()),
            pinned,
        )
    }

    @Test
    fun `脚本执行必须先过 release 闸门与完整性校验`() {
        val code = stripCommentsOnly(buildScriptText())
        val readFunIndex = code.indexOf("fun readWindowsCredentialPassword(")
        val gateIndex = code.indexOf("if (!releaseArtifactRequested)")
        val execIndex = code.indexOf("ProcessBuilder(")
        val integrityCallIndex = code.lastIndexOf(INTEGRITY_CALL)
        val integrityCallCount = INTEGRITY_CALL.toRegex().findAll(code).count()

        assertTrue("[$BUILD_SCRIPT] 已无凭证读取函数 readWindowsCredentialPassword", readFunIndex >= 0)
        assertTrue("[$BUILD_SCRIPT] 已无 ProcessBuilder 脚本执行点（执行形态已变更）", execIndex >= 0)
        assertTrue(
            "[$BUILD_SCRIPT] release 产物闸门（'if (!releaseArtifactRequested)'）不在凭证读取函数内、" +
                "或已被挪到执行点之后——闸门拆除后任意 gradle 任务都会再次执行 PR 可改脚本" +
                "（ISSUE-P2-573 闸门一）",
            gateIndex in readFunIndex until execIndex,
        )
        assertTrue(
            "[$BUILD_SCRIPT] 完整性校验必须被真实调用（除函数声明外还须有调用点）——" +
                "否则基线只是摆设（ISSUE-P2-573 闸门二）",
            integrityCallCount >= 2,
        )
        assertTrue(
            "[$BUILD_SCRIPT] 完整性校验必须执行于 ProcessBuilder 之前——后置校验等于不校验",
            integrityCallIndex in readFunIndex until execIndex,
        )
        assertTrue("[$BUILD_SCRIPT] 闸门一（release 产物判定）必须先于闸门二（完整性校验）", gateIndex < integrityCallIndex)
    }

    @Test
    fun `完整性基线失配必须 fail-closed 中止构建`() {
        val code = stripCommentsOnly(buildScriptText())
        val funStart = code.indexOf("fun verifyCredManReadScriptIntegrity()")
        assertTrue("[$BUILD_SCRIPT] 已无完整性校验函数 verifyCredManReadScriptIntegrity", funStart >= 0)
        val afterFun = code.indexOf("fun ", funStart + 1)
        val body = code.substring(funStart, if (afterFun < 0) code.length else afterFun)

        assertTrue(
            "[$BUILD_SCRIPT] 完整性校验函数必须现场重算哈希（MessageDigest）并与 credManReadScriptSha256" +
                " 比较——比较缺失等于基线失效",
            "MessageDigest" in body && "credManReadScriptSha256" in body,
        )
        assertTrue(
            "[$BUILD_SCRIPT] 完整性失配（及脚本缺失）必须以 GradleException 中止构建——" +
                "fail-closed；（『告警后继续』等于把基线降级为注释）",
            "throw GradleException" in body,
        )
    }

    /** 构建脚本全文（相对仓库根）。 */
    private fun buildScriptText(): String {
        val file = File(repositoryRoot, BUILD_SCRIPT)
        assertTrue("构建脚本不存在（是否被重命名 / 移动）：$BUILD_SCRIPT", file.isFile)
        return file.readText()
    }

    private fun sha256Hex(bytes: ByteArray): String =
        MessageDigest.getInstance("SHA-256")
            .digest(bytes)
            .joinToString("") { byte -> "%02x".format(byte.toInt() and 0xFF) }

    private companion object {
        const val BUILD_SCRIPT = "app/build.gradle.kts"
        const val READ_CRED_SCRIPT = "tools/signing/read-cred.ps1"

        /** 完整性校验函数的调用形态（声明处亦含该子串，故调用点取末次出现位次）。 */
        const val INTEGRITY_CALL = "verifyCredManReadScriptIntegrity()"

        /** 构建脚本内钉定的 SHA-256 基线常量（小写十六进制 64 位）。 */
        val PINNED_SHA256 = Regex("credManReadScriptSha256\\s*=\\s*\"([0-9a-f]{64})\"")

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
