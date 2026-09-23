package com.keepasskey.database.io

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * ISSUE-P3-118（威胁建模 T-16 残余）回归：**序列化缓冲必须确定性擦除**。
 *
 * 缺陷形态：`DatabaseSession` 的保存 / 导出 / 换密三条路径都以
 * `ByteArrayOutputStream().also { … }.toByteArray()` 产出整库字节——`toByteArray()` 返回的是
 * **副本**，而内部缓冲（第二份完整序列化字节）在对象被 GC 前不会被清零，`reset()` 也只置计数
 * 不清内容。序列化产物为整库密文（内层除头部外全部加密），属会话派生敏态数据。
 *
 * 本用例分两层：
 * 1. **原语行为**：`wipe()` 必须把内部缓冲**逐字节置 0**（而不只是把计数复位）——用探针子类
 *    读取父类 `protected buf` 直接断言，这是唯一能证明"字节已擦"的可观测口径；
 * 2. **接线守卫**（静态源码断言）：三条路径都必须用具名缓冲并 `finally` 收口 `wipe()`，
 *    且不得残留匿名链式写法（该写法下缓冲无处可擦，正是本缺陷的成因）。
 */
class WipableByteArrayOutputStreamTest {

    @Test
    fun `wipe 逐字节清零内部缓冲而非仅复位计数`() {
        val stream = WipableByteArrayOutputStream()
        val payload = ByteArray(64) { (it + 1).toByte() }
        stream.write(payload)

        assertTrue("前置：已写入数据", stream.size() == payload.size)

        stream.wipe()

        assertEquals("wipe 后计数必须复位", 0, stream.size())
        assertTrue("wipe 后 toByteArray 必须为空", stream.toByteArray().isEmpty())
        assertTrue(
            "wipe 必须把内部缓冲**逐字节清零**（reset() 只置计数不清内容，故不能只调 reset）",
            stream.bufferedBytesSnapshot().all { it == 0.toByte() }
        )
    }

    @Test
    fun `wipe 幂等且擦除后仍可继续写入`() {
        val stream = WipableByteArrayOutputStream()
        stream.write(ByteArray(16) { 9 })
        stream.wipe()
        stream.wipe()

        assertTrue(stream.bufferedBytesSnapshot().all { it == 0.toByte() })

        stream.write(byteArrayOf(1, 2, 3))
        assertEquals(3, stream.size())
        assertTrue(stream.toByteArray().contentEquals(byteArrayOf(1, 2, 3)))
    }

    @Test
    fun `DatabaseSession 三条序列化路径必须用具名缓冲并收口 wipe`() {
        val source = readSource(DATABASE_SESSION_PATH)
        val namedBuffers = Regex("""val buffer = WipableByteArrayOutputStream\(\)""")
            .findAll(source).count()
        val wipes = Regex("""buffer\.wipe\(\)""").findAll(source).count()

        assertTrue(
            "三条序列化路径（save / exportToBytes / changeCredentials）都必须使用具名可擦缓冲" +
                "（实际具名缓冲 $namedBuffers 处）",
            namedBuffers >= 3
        )
        assertTrue(
            "每处具名缓冲都必须有对应的 buffer.wipe() 收口（实际 $wipes 处 vs 缓冲 $namedBuffers 处）",
            wipes >= namedBuffers
        )
        assertFalse(
            "不得残留匿名链式写法 `ByteArrayOutputStream().also { … }.toByteArray()`——" +
                "该写法下缓冲无处可擦，正是 ISSUE-P3-118 的成因",
            source.contains("ByteArrayOutputStream().also {")
        )
    }

    /**
     * ISSUE-P3-296：两个**明文**导出器与加密序列化同口径——具名可擦缓冲 + `finally` 收口 `wipe()`。
     * 明文 XML / CSV 的内部缓冲是整库全部字段值的第二份明文副本，必须确定性擦除而非等 GC。
     */
    @Test
    fun `两个明文导出器必须用可擦缓冲并在 finally 收口 wipe`() {
        for (path in PLAINTEXT_EXPORTER_PATHS) {
            val source = readSource(path)

            assertTrue(
                "[$path] 明文导出器必须使用 WipableByteArrayOutputStream 承载整份明文字节",
                source.contains("val buffer = WipableByteArrayOutputStream()")
            )
            assertTrue(
                "[$path] 必须以 finally { buffer.wipe() } 收口（成功与失败路径均擦）",
                Regex("""finally\s*\{\s*buffer\.wipe\(\)\s*\}""").containsMatchIn(source)
            )
            assertTrue(
                "[$path] 必须在 toByteArray() 之后、return 之前仍有 finally 擦除——" +
                    "「只清 toByteArray() 交出的副本」正是 ISSUE-P3-296 的缺口",
                source.indexOf("buffer.toByteArray()") < source.indexOf("buffer.wipe()")
            )
            assertFalse(
                "[$path] 不得回退为不可擦的 ByteArrayOutputStream（内部 buf 将留存至 GC）",
                Regex("""(?<!Wipable)ByteArrayOutputStream\(""").containsMatchIn(source)
            )
        }
    }

    private fun readSource(path: String): String {
        val file = File(repositoryRoot, path)
        assertTrue("源码文件不存在（是否被重命名或移动）：$path", file.isFile)
        return file.readText()
    }

    private companion object {
        const val DATABASE_SESSION_PATH =
            "database/src/main/java/com/keepasskey/database/session/DatabaseSession.kt"
        val PLAINTEXT_EXPORTER_PATHS = listOf(
            "database/src/main/java/com/keepasskey/database/csv/KdbxCsvExporter.kt",
            "database/src/main/java/com/keepasskey/database/xml/KeePassXmlExporter.kt"
        )
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
