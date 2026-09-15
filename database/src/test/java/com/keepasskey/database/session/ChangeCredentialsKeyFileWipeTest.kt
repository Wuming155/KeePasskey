package com.keepasskey.database.session

import com.keepasskey.core.model.KdbxConstants
import com.keepasskey.core.model.KdbxEntry
import com.keepasskey.core.model.KdbxGroup
import com.keepasskey.core.security.ProtectedString
import com.keepasskey.database.file.KdbxDatabase
import com.keepasskey.database.file.KdbxFile
import com.keepasskey.database.file.KdbxHeader
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File

/**
 * ISSUE-P3-99（审计 L2）回归：**换密路径的密钥文件快照副本必须清零，且不得擦到生效中的凭据**。
 *
 * 缺陷形态：`changeCredentials` 的密钥文件快照以**默认参数表达式**
 * （`newKeyFileData: ByteArray? = credentials.keyFileSnapshot()`）形态注入——`keyFileSnapshot()`
 * 返回**克隆副本**，`rotateCredentials` 与 `KdbxFile.save` 都只读取它（前者内部再克隆写入缓存），
 * 故该副本归方法所有却**无处可擦**：换密后随局部变量出栈静默留存至 GC（RC-02 族）。
 *
 * 本用例分两层：
 * 1. **行为级（非空跑）**：换密后产出的库必须能用「新主密码 + **原密钥文件**」解锁——
 *    若实现把快照清零**写在写盘之前**，产出的库将使用全零密钥文件派生，此断言必红；
 * 2. **静态接线**：单参重载必须自持快照并在 `finally` 中清零；双参重载**不得**再有默认值
 *    （默认值形态正是「副本无处可擦」的成因）。
 */
class ChangeCredentialsKeyFileWipeTest {

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

    @Test
    fun `换密后产出的库必须能用新主密码与原密钥文件解锁`() = runBlocking {
        val oldPwd = "Old#Pass#2026".toCharArray()
        val newPwd = "New#Pass#2026".toCharArray()
        var saved: ByteArray? = null

        val session = DatabaseSession()
        assertTrue(
            "前置：会话打开失败",
            session.openStream(
                pathIdentifier = "change.kdbx",
                inputStreamProvider = { ByteArrayInputStream(initialBytes(oldPwd)) },
                saveWriter = { bytes -> saved = bytes.copyOf() },
                passwordChars = oldPwd,
                keyFileData = keyFile
            ).isSuccess
        )

        assertTrue("换密失败", session.changeCredentials(newPwd).isSuccess)

        val written = saved
        assertNotNull("换密后必须触发一次写盘", written)
        // 行为级核心断言：产物必须由「新主密码 + 原密钥文件」派生
        // （若快照在写盘前被清零，这里会因派生密钥不匹配而失败）
        val reopened = KdbxFile.load(ByteArrayInputStream(written!!), newPwd, keyFile)
        assertTrue("换密后库应可正常解锁", reopened.rootGroup.entries.isNotEmpty())

        written.fill(0)
        session.close()
    }

    @Test
    fun `单参重载必须自持快照并清零且双参重载不得再有默认值`() {
        val source = readSource(DATABASE_SESSION_PATH)

        assertTrue(
            "必须存在单参重载 changeCredentials(newPasswordChars)：由它自持密钥文件快照",
            Regex("""suspend fun changeCredentials\(\s*newPasswordChars: CharArray\?\s*\)\s*: KdbxResult<Unit>""")
                .containsMatchIn(source)
        )
        assertTrue(
            "单参重载必须在 finally 中清零快照副本（ISSUE-P3-99）",
            Regex("""keyFileSnapshot\?\.fill\(0\)""").containsMatchIn(source)
        )
        assertFalse(
            "双参重载不得再使用默认参数表达式注入密钥文件快照——默认值形态下副本无处可擦",
            Regex("""newKeyFileData: ByteArray\?\s*=""").containsMatchIn(source)
        )
        // 顺序硬约束：清零必须晚于写盘（否则会写出用全零密钥文件加密的库）
        val zeroIndex = source.indexOf("keyFileSnapshot?.fill(0)")
        val saveIndex = source.indexOf("changeCredentials(newPasswordChars, keyFileSnapshot)")
        assertTrue("清零点必须位于委托调用之后（即写盘之后）", zeroIndex > saveIndex)
    }

    private fun readSource(path: String): String {
        val file = File(repositoryRoot, path)
        assertTrue("源码文件不存在（是否被重命名或移动）：$path", file.isFile)
        return file.readText()
    }

    private companion object {
        const val DATABASE_SESSION_PATH =
            "database/src/main/java/com/keepasskey/database/session/DatabaseSession.kt"
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
