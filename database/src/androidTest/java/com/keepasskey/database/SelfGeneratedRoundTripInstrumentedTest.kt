package com.keepasskey.database

import androidx.test.ext.junit.runners.AndroidJUnit4
import com.keepasskey.core.model.KdbxConstants
import com.keepasskey.core.model.KdbxEntry
import com.keepasskey.core.model.KdbxGroup
import com.keepasskey.core.security.ProtectedString
import com.keepasskey.crypto.kdf.KdfParameters
import com.keepasskey.database.file.KdbxDatabase
import com.keepasskey.database.file.KdbxFile
import com.keepasskey.database.file.KdbxHeader
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream

/**
 * **自生成库 round-trip（设备侧）** —— ISSUE-P3-23 的**辅助**用例，**不是**验收标准 2。
 *
 * > ## ⚠️ 本用例**不构成**与 KeePass 2.61.1 / KeePassXC 的互操作证据
 * > 库由本工程 `KdbxFile.save` 写出、再由本工程 `KdbxFile.load` 读回，
 * > **两边都是同一份实现**：它能证明「写侧与读侧自洽」，**不能**证明「外部官方客户端产出的
 * > `.kdbx` 能被本工程打开」（互操作证据只认 `RealKdbxCorpusUnlockTest` 消费的**外部工具产出语料**，
 * > 以及 `database/src/test/resources/fixtures/` 下的既有官方夹具）。
 *
 * 它存在的唯一工程价值：
 * 1. 证明 `database` 模块**新建的 androidTest 源集确实能在设备上真实执行**（不只是编译通过），
 *    且**不依赖任何语料**（语料缺失时 [RealKdbxCorpusUnlockTest] 会整体跳过，本用例仍会跑）；
 * 2. 覆盖「设备侧完整 KDBX 写入 → 落盘字节 → 重新解析 → 条目明文一致」的通路，
 *    其中 KDF 走的是设备运行时真实的 Argon2 路径（原生 JNI 可用时用原生，否则回退 BC）。
 *
 * 敏感数据：口令与条目明文均为**虚构测试数据**（非真实凭据），用后清零；
 * 断言信息只输出条目标题，不输出密码明文。
 */
@RunWith(AndroidJUnit4::class)
class SelfGeneratedRoundTripInstrumentedTest {

    private companion object {

        /** 虚构测试口令（非真实凭据），用后清零。 */
        private const val TEST_PASSPHRASE = "Round-Trip-Not-Interop-Evidence-2026!"

        /** 虚构条目标题（占位内容，非敏感）。 */
        private const val TEST_ENTRY_TITLE = "roundtrip-placeholder-entry"

        /** 虚构条目用户名（占位内容，非敏感）。 */
        private const val TEST_ENTRY_USER = "roundtrip-user"

        /** 虚构条目密码（占位内容，非真实凭据），用后清零。 */
        private const val TEST_ENTRY_PASSWORD = "roundtrip-fake-secret"

        /** 语料/往返用例的 Argon2 现实档位（与验收档位一致：t=2, m=64MiB, p=2）。 */
        private const val EXPECTED_ITERATIONS = 2L
        private const val EXPECTED_MEMORY_KIB = 64L * 1024
        private const val EXPECTED_PARALLELISM = 2
    }

    @Test
    fun 自生成库设备侧写入后读回且不构成互操作证据() {
        println(
            "[自生成 round-trip · 非互操作证据] 本库由本工程 KdbxFile.save 生成、由本工程 KdbxFile.load 读回，" +
                "**不构成与 KeePass 2.61.1 / KeePassXC 的互操作证据**（见 ISSUE-P3-23 验收标准 2）。"
        )

        val passphrase = TEST_PASSPHRASE.toCharArray()
        val entryPassword = ProtectedString(TEST_ENTRY_PASSWORD, isProtected = true)
        val entry = KdbxEntry(
            fields = mapOf(
                KdbxConstants.Fields.TITLE to ProtectedString(TEST_ENTRY_TITLE),
                KdbxConstants.Fields.USER_NAME to ProtectedString(TEST_ENTRY_USER),
                KdbxConstants.Fields.PASSWORD to entryPassword
            )
        )
        // 默认头部即设备侧真实 Argon2id 档位（t=2, m=64MiB, p=2），使本用例同时覆盖设备侧 KDF 通路
        val database = KdbxDatabase(
            header = KdbxHeader.createDefault(),
            databaseName = "roundtrip-non-interop",
            rootGroup = KdbxGroup(name = "Root", entries = listOf(entry))
        )

        val saved = try {
            ByteArrayOutputStream().also { KdbxFile.save(it, database, passphrase) }.toByteArray()
        } finally {
            passphrase.fill('\u0000')
        }
        assertTrue("自生成库字节流必须非空", saved.isNotEmpty())

        val reloadPassphrase = TEST_PASSPHRASE.toCharArray()
        try {
            val reloaded = KdbxFile.load(ByteArrayInputStream(saved), reloadPassphrase)
            val kdf = reloaded.header.kdfParameters
            assertTrue("新建库默认 KDF 应为 Argon2", kdf is KdfParameters.Argon2)
            kdf as KdfParameters.Argon2
            assertEquals("默认档位 t 应为 $EXPECTED_ITERATIONS", EXPECTED_ITERATIONS, kdf.iterations)
            assertEquals(
                "默认档位 m 应为 ${EXPECTED_MEMORY_KIB}KiB",
                EXPECTED_MEMORY_KIB * 1024,
                kdf.memoryInBytes
            )
            assertEquals("默认档位 p 应为 $EXPECTED_PARALLELISM", EXPECTED_PARALLELISM, kdf.parallelism)

            val reloadedEntries = reloaded.rootGroup.allEntries()
            assertEquals("往返后条目数应一致", 1, reloadedEntries.size)
            val target = reloadedEntries.first()
            assertEquals("往返后标题应一致", TEST_ENTRY_TITLE, target.title)
            assertEquals("往返后用户名应一致", TEST_ENTRY_USER, target.userName)
            // 明文比较在内存中完成，不打印任何明文
            assertEquals("往返后密码明文应一致", TEST_ENTRY_PASSWORD, target.password?.readString())
            assertNotNull("往返后密码字段不应丢失", target.password)
            assertTrue("往返后密码字段应保持受保护标记", target.password?.isProtected == true)

            println(
                "[自生成 round-trip · 非互操作证据] 往返成功：entryCount=${reloadedEntries.size}, " +
                    "KDF=argon2id v${kdf.version} t=${kdf.iterations} " +
                    "m=${kdf.memoryInBytes / 1024 / 1024}MiB p=${kdf.parallelism}"
            )
            reloaded.clearSensitiveData()
        } finally {
            reloadPassphrase.fill('\u0000')
            entryPassword.clear()
        }
    }
}
