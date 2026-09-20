package com.keepasskey.database.file

import com.keepasskey.core.model.KdbxConstants
import com.keepasskey.core.model.KdbxEntry
import com.keepasskey.core.model.KdbxGroup
import com.keepasskey.core.security.ProtectedString
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File

/**
 * ISSUE-P3-235（`RC-02` 敏感缓冲所有权收口）：**内层随机流密钥的擦除契约**。
 *
 * 缺陷形态：读路径用文件自述的 64 字节内层流密钥构造 `InnerRandomStreamCipher`
 * 解密全部 `Protected="True"` 字段，随后 `InnerHeader` 实例只把 `binaries` 交给数据库，
 * **密钥数组本身无任何清零点**（随局部变量出栈静默留存至 GC）；写路径的 `freshInnerKey`
 * 同理。两者都是「解密/加密全部受保护字段的密钥」，属秘密（**不得**与头部公开字段
 * `masterSeed` / `encryptionIv` 同等看待，见 `docs/architecture/敏感缓冲所有权契约.md` §5 豁免清单）。
 *
 * 本用例分两层，与既有 `CredentialIntermediateWipeTest` 同范式：
 * 1. **行为级**：`InnerHeader.clearSensitive()` 的真值语义（清的是密钥、幂等、不触碰非秘密字段、
 *    默认构造的实例互不共享数组）；以及**非空跑**——受保护字段经 `save → load` 往返仍可原样
 *    解密（防「擦到了序列化/解析之前」这一方向性回归）。
 * 2. **静态接线**：擦除效果无法由外部用例观测（`KdbxDatabase` 不保留 `InnerHeader`），
 *    故两条调用点的存在性与**顺序**（读：解析之后；写：序列化之后）以源码接线断言锁定。
 */
class InnerStreamKeyWipeTest {

    // ---------- 行为级：擦除原语 ----------

    @Test
    fun `内层随机流密钥必须可被确定性擦除`() {
        val key = ByteArray(INNER_KEY_SIZE) { (it + 1).toByte() }
        val header = InnerHeader(innerRandomStreamKey = key)

        header.clearSensitive()

        assertArrayEquals("clearSensitive 必须把内层随机流密钥逐位清零", ByteArray(INNER_KEY_SIZE), key)
    }

    @Test
    fun `擦除是幂等的且不触碰算法标识与二进制池`() {
        val key = ByteArray(INNER_KEY_SIZE) { 0x5A }
        val binary = InnerHeader.BinaryItem(flags = 1, data = byteArrayOf(9, 8, 7))
        val header = InnerHeader(
            innerRandomStreamId = KdbxConstants.InnerRandomStream.CHACHA20,
            innerRandomStreamKey = key,
            binaries = listOf(binary)
        )

        header.clearSensitive()
        header.clearSensitive()

        assertArrayEquals(ByteArray(INNER_KEY_SIZE), key)
        assertEquals(
            "算法标识非秘密，不得被 clearSensitive 改动",
            KdbxConstants.InnerRandomStream.CHACHA20,
            header.innerRandomStreamId
        )
        assertEquals("二进制池不属于本方法的擦除边界（见限界 §1.6）", 1, header.binaries.size)
        assertArrayEquals("二进制池条目内容不得被连带清零", byteArrayOf(9, 8, 7), header.binaries[0].data)
    }

    @Test
    fun `默认构造的实例互不共享密钥数组`() {
        val first = InnerHeader()
        val second = InnerHeader()

        assertNotSame(
            "默认密钥必须是每次求值的新数组——若被提为共享常量，擦除一个实例会污染其余实例",
            first.innerRandomStreamKey,
            second.innerRandomStreamKey
        )
        first.clearSensitive()
        assertEquals(
            "擦除一个实例不得影响另一个实例",
            ByteArray(INNER_KEY_SIZE).toList(),
            second.innerRandomStreamKey.toList()
        )
    }

    // ---------- 行为级：非空跑（顺序方向性的回归闸门） ----------

    @Test
    fun `受保护字段经保存与重载往返仍可原样解密`() {
        val password = "Old#Pass#2026"
        val entry = KdbxEntry(
            fields = linkedMapOf(
                KdbxConstants.Fields.PASSWORD to ProtectedString(password, isProtected = true)
            )
        )
        val db = KdbxDatabase(
            header = KdbxHeader.createDefault(useArgon2 = false),
            rootGroup = KdbxGroup(name = "Root", entries = listOf(entry))
        )
        val passwordChars = "Master#Pass#2026".toCharArray()

        val bytes = ByteArrayOutputStream().also { KdbxFile.save(it, db, passwordChars) }.toByteArray()
        try {
            val reopened = KdbxFile.load(ByteArrayInputStream(bytes), passwordChars)
            assertEquals(
                "写侧擦除若早于序列化，受保护字段会以错误密钥流写出、此处必红",
                password,
                reopened.rootGroup.entries.first().password?.readString()
            )
        } finally {
            bytes.fill(0)
        }
    }

    // ---------- 静态接线 ----------

    @Test
    fun `InnerHeader 必须提供擦除内层随机流密钥的入口`() {
        val body = functionBody(readSource(INNER_HEADER_SOURCE), "fun clearSensitive()")
        assertTrue(
            "InnerHeader.clearSensitive() 必须就地清零 innerRandomStreamKey",
            Regex("""innerRandomStreamKey\.fill\(0\)""").containsMatchIn(body)
        )
    }

    @Test
    fun `读路径必须在解析之后且于 finally 内擦除密钥`() {
        val body = functionBody(readSource(KDBX_FILE_SOURCE), "private fun decodeInnerPayload(")

        val parseIndex = body.indexOf("KdbxXmlParser(")
        val wipeIndex = body.indexOf("innerHeader.clearSensitive()")
        assertTrue("decodeInnerPayload 必须含 KdbxXmlParser(", parseIndex >= 0)
        assertTrue(
            "读路径必须调用 innerHeader.clearSensitive()（密钥的擦除责任归 InnerHeader，见契约 §4）",
            wipeIndex >= 0
        )
        assertTrue(
            "擦除必须晚于 XML 解析（早于解析会以全零密钥解出乱码，库直接打不开）",
            wipeIndex > parseIndex
        )
        val finallyIndex = body.lastIndexOf("finally")
        assertTrue(
            "擦除必须落在 finally 内（解析失败 / HMAC 终止块校验失败 / 内层算法不受支持三条异常出口同样不得残留）",
            finallyIndex in 0 until wipeIndex
        )
    }

    @Test
    fun `写路径必须在序列化之后擦除密钥`() {
        val body = functionBody(readSource(KDBX_FILE_SOURCE), "private fun savePayload(")

        val serializeIndex = body.indexOf("KdbxXmlSerializer(")
        val wipeIndex = body.indexOf("innerHeader.clearSensitive()")
        assertTrue("savePayload 必须含 KdbxXmlSerializer(", serializeIndex >= 0)
        assertTrue(
            "写路径必须调用 innerHeader.clearSensitive()（freshInnerKey 与 innerHeader 持同一数组）",
            wipeIndex >= 0
        )
        assertTrue(
            "擦除必须晚于内层负载序列化（早于构造内层流密码即会写出全部受保护字段不可解的库）",
            wipeIndex > serializeIndex
        )
    }

    @Test
    fun `写路径的擦除必须落在 finally 内`() {
        val body = functionBody(readSource(KDBX_FILE_SOURCE), "private fun savePayload(")

        val wipeIndex = body.indexOf("innerHeader.clearSensitive()")
        assertTrue("savePayload 必须调用 innerHeader.clearSensitive()", wipeIndex >= 0)
        assertTrue(
            "擦除必须落在 finally 内——级联收尾（GZip finish / 加密流 doFinal）抛异常时同样不得残留",
            body.lastIndexOf("finally") in 0 until wipeIndex
        )
    }

    // ---------- 工具 ----------

    /** 抽取指定函数的函数体（自签名起按花括号配对截取；断言前剔除注释）。 */
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

    /**
     * 剔除行注释与块注释（整改说明自身会提到 `clearSensitive()` 等字样，不剔除会误判）。
     * 逐字符扫描并跳过字符串字面量与字符字面量，避免把 `"//"` 一类内容当成注释起点。
     */
    private fun stripComments(source: String): String {
        val out = StringBuilder(source.length)
        var index = 0
        while (index < source.length) {
            val ch = source[index]
            when {
                ch == '"' || ch == '\'' -> {
                    val quote = ch
                    out.append(ch)
                    index++
                    while (index < source.length) {
                        val c = source[index]
                        out.append(c)
                        index++
                        if (c == '\\' && index < source.length) {
                            out.append(source[index])
                            index++
                        } else if (c == quote) {
                            break
                        }
                    }
                }
                ch == '/' && index + 1 < source.length && source[index + 1] == '/' -> {
                    while (index < source.length && source[index] != '\n') index++
                }
                ch == '/' && index + 1 < source.length && source[index + 1] == '*' -> {
                    index += 2
                    while (index + 1 < source.length &&
                        !(source[index] == '*' && source[index + 1] == '/')
                    ) {
                        index++
                    }
                    index += 2
                }
                else -> {
                    out.append(ch)
                    index++
                }
            }
        }
        return out.toString()
    }

    private fun readSource(path: String): String {
        val file = File(repositoryRoot, path)
        assertTrue("源码文件不存在（是否被重命名或移动）：$path", file.isFile)
        return file.readText()
    }

    private companion object {
        /** 官方 `InnerRandomStreamKey` 恒为 64 字节（与 `InnerHeader` 的常量一致）。 */
        const val INNER_KEY_SIZE = 64

        const val INNER_HEADER_SOURCE =
            "database/src/main/java/com/keepasskey/database/file/InnerHeader.kt"
        const val KDBX_FILE_SOURCE =
            "database/src/main/java/com/keepasskey/database/file/KdbxFile.kt"
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
