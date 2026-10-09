package com.keepasskey.database.session

import com.keepasskey.core.model.KdbxConstants
import com.keepasskey.core.model.KdbxEntry
import com.keepasskey.core.model.KdbxGroup
import com.keepasskey.core.security.ProtectedString
import com.keepasskey.database.file.KdbxDatabase
import com.keepasskey.database.file.KdbxFile
import com.keepasskey.database.file.KdbxHeader
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.IOException

/**
 * ISSUE-P3-551 回归：**序列化缓冲（整库密文）在写盘异常路径也必须清零**。
 *
 * 缺陷形态：`SessionPersistence` 的两条写盘路径把 `serialized.fill(0)` 写在 **`try` 体内**
 * 而非 `finally`——`writer(serialized)` 抛异常（磁盘满 / SAF 通道中断 / URI 授权失效）时，
 * 整库密文副本直接滞留堆上等 GC。与**同文件**换密路径对凭据快照的处理（`finally` + 失败路径
 * 所有权移交 `restoreCredentials`）相比，本处属**遗漏而非取舍**。
 *
 * 断言手法：`saveWriter` 只**持有引用**（不拷贝）就抛异常；若实现未在异常路径清零，
 * 则返回后该引用仍可读到整库密文（全零即证明已擦）。
 */
class SessionSerializationBufferWipeTest {

    private val keyFile = ByteArray(64) { (it + 1).toByte() }

    private fun initialBytes(password: CharArray): ByteArray {
        val entry = KdbxEntry(
            fields = linkedMapOf(
                KdbxConstants.Fields.PASSWORD to ProtectedString("secret#2026", isProtected = true)
            )
        )
        val db = KdbxDatabase(
            header = KdbxHeader.createDefault(useArgon2 = false),
            rootGroup = KdbxGroup(name = "Root", entries = listOf(entry))
        )
        return ByteArrayOutputStream().also { KdbxFile.save(it, db, password, keyFile) }.toByteArray()
    }

    private suspend fun openedSession(
        password: CharArray,
        writer: suspend (ByteArray) -> Unit
    ): DatabaseSession {
        val session = DatabaseSession()
        assertTrue(
            "前置：会话打开失败",
            session.openStream(
                pathIdentifier = "wipe.kdbx",
                inputStreamProvider = { ByteArrayInputStream(initialBytes(password)) },
                saveWriter = writer,
                passwordChars = password,
                keyFileData = keyFile
            ).isSuccess
        )
        return session
    }

    @Test
    fun `保存写盘抛异常时序列化缓冲必须已清零`() = runBlocking {
        val pwd = "Pass#2026".toCharArray()
        var captured: ByteArray? = null
        val session = openedSession(pwd) { bytes ->
            // 刻意**只持引用**：fill(0) 与写入者看到的是同一个数组
            captured = bytes
            throw IOException("disk full")
        }

        val result = session.save()
        assertTrue("写盘异常必须如实失败", result.isFailure)

        val buffer = captured
        assertNotNull("写盘必须被触发一次", buffer)
        assertAllZero("保存路径：写盘异常后整库密文缓冲必须清零", buffer!!)

        session.close()
    }

    @Test
    fun `换密写盘抛异常时序列化缓冲必须已清零`() = runBlocking {
        val oldPwd = "Old#Pass#2026".toCharArray()
        val newPwd = "New#Pass#2026".toCharArray()
        var captured: ByteArray? = null
        val session = openedSession(oldPwd) { bytes ->
            captured = bytes
            throw IOException("disk full")
        }

        val result = session.changeCredentials(newPwd)
        assertTrue("换密写盘异常必须如实失败", result.isFailure)

        val buffer = captured
        assertNotNull("换密必须触发一次写盘", buffer)
        assertAllZero("换密路径：写盘异常后整库密文缓冲必须清零", buffer!!)

        // 失败路径的既有不变量：凭据必须回滚到旧值（否则库与凭据不匹配 ⇒ 后续无法解锁）
        val reopened = KdbxFile.load(ByteArrayInputStream(initialBytes(oldPwd)), oldPwd, keyFile)
        assertTrue("凭据回滚后旧口令仍须可解锁", reopened.rootGroup.entries.isNotEmpty())

        session.close()
    }

    @Test
    fun `两处清零必须位于 finally 内`() {
        val source = readSource(SESSION_PERSISTENCE_PATH)
        val occurrences = Regex("serialized\\.fill\\(0\\)").findAll(source).toList()
        assertTrue(
            "两处 serialized.fill(0) 都必须存在（保存 / 换密），实测 ${occurrences.size} 处",
            occurrences.size == 2
        )
        occurrences.forEach { match ->
            val prefix = source.substring(maxOf(0, match.range.first - 400), match.range.first)
            assertTrue(
                "清零必须写在 `finally` 内（异常路径才收得到），上下文：…${prefix.takeLast(80)}",
                prefix.contains("finally")
            )
        }
    }

    private fun assertAllZero(message: String, bytes: ByteArray) {
        val firstNonZero = bytes.indexOfFirst { it != 0.toByte() }
        assertTrue(
            "$message（首个非零字节下标=$firstNonZero，长度=${bytes.size}）",
            firstNonZero < 0
        )
    }

    private fun readSource(path: String): String {
        val file = File(repositoryRoot, path)
        assertTrue("源码文件不存在（是否被重命名或移动）：$path", file.isFile)
        return file.readText()
    }

    private companion object {
        const val SESSION_PERSISTENCE_PATH =
            "database/src/main/java/com/keepasskey/database/session/SessionPersistence.kt"
        const val ROOT_SEARCH_DEPTH = 6

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
            error("未能定位仓库根（自 ${System.getProperty("user.dir")} 向上 ${ROOT_SEARCH_DEPTH} 层）")
        }
    }
}
