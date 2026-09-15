package com.keepasskey.app.security

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * ISSUE-P2-55（审计 F-06）回归：发布签名口令的**示例模板 + 构建期闸门**。
 *
 * 缺陷背景：`keystore.properties.example` 曾把示例口令写成 `keepasskey123`，而本地开发与
 * 流水线直接照抄该值签名——示例值随公开仓库泄露，等于把发布密钥公开（任何人可签出
 * 被系统认可的"官方"包）。
 *
 * 本用例为**静态接线检查**（沿用仓库既有 `*WiringTest` 先例），守护两层不变式：
 * 1. 示例文件本身不得再出现任何**可用口令**（口令字段必须是占位符）；
 * 2. `app/build.gradle.kts` 必须保留构建期断言（长度下限 + 已公开弱口令黑名单 + 占位符检测），
 *    且必须以 `error(...)` fail-closed 终止配置阶段。
 *
 * 动态行为（弱口令/占位符真的会让构建失败、强口令能正常签名）已在批次验收中以真实
 * `gradlew` 运行现场实测留痕（见 `RESOLVED_LOG.md` §49.2），此处只防"被人删掉"。
 */
class ReleaseSigningPasswordGateTest {

    @Test
    fun `示例文件不得包含任何可用口令且口令字段为占位符`() {
        val example = readSource("keystore.properties.example")
        val passwordLines = example.lines()
            .map { it.trim() }
            .filter { it.startsWith("storePassword=") || it.startsWith("keyPassword=") }

        assertTrue("示例文件必须同时声明 storePassword / keyPassword", passwordLines.size >= 2)
        for (line in passwordLines) {
            val value = line.substringAfter('=')
            assertTrue(
                "示例口令字段必须是占位符（含 __REPLACE_WITH 标记），实际：$line",
                value.contains("__REPLACE_WITH")
            )
            for (weak in FORBIDDEN_WEAK_PASSWORDS) {
                assertFalse(
                    "示例文件不得再出现已公开的弱口令 '$weak'（审计 F-06 的直接对象）：$line",
                    value.equals(weak, ignoreCase = true)
                )
            }
        }
        // 注释里可以提到该值（用于说明"绝不可复用"），但绝不可出现在口令字段中
        assertTrue(
            "示例文件应显式警示历史泄露口令的不可复用性（便于使用者理解闸门动机）",
            example.contains("keepasskey123")
        )
    }

    @Test
    fun `构建脚本必须保留发布签名口令闸门且 fail-closed`() {
        val buildScript = readSource("app/build.gradle.kts")

        assertTrue(
            "构建脚本须含已公开弱口令黑名单",
            buildScript.contains("forbiddenReleasePasswords")
        )
        assertTrue(
            "构建脚本须含口令长度下限判定",
            buildScript.contains("minReleasePasswordLength")
        )
        assertTrue(
            "构建脚本须含模板占位符检测",
            buildScript.contains("releasePasswordPlaceholderMarker")
        )
        assertTrue(
            "闸门须以 error(...) 终止配置阶段（fail-closed，不得仅告警）",
            buildScript.contains("发布签名口令命中**已公开的示例 / 弱口令**")
        )
        assertTrue(
            "历史公开示例值必须在黑名单内（审计 F-06 的直接对象）",
            buildScript.contains("\"keepasskey123\"")
        )
    }

    /** 源码全文；路径相对仓库根（app 模块测试工作目录为 app/，向上回溯定位仓库根） */
    private fun readSource(path: String): String {
        val file = File(repositoryRoot, path)
        assertTrue("清单文件不存在（是否被重命名/移动）：$path", file.isFile)
        return file.readText()
    }

    private companion object {
        val FORBIDDEN_WEAK_PASSWORDS = setOf(
            "keepasskey123", "password", "changeme", "changeit", "123456",
        )

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

        const val ROOT_SEARCH_DEPTH = 6
    }
}
